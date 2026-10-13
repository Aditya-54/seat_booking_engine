import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency-safe seat reservation engine.
 *
 * <h2>Locking strategy</h2>
 * Every public method synchronizes on {@code this} (a single engine-wide lock).
 * This is the simplest possible strategy: only one operation runs at a time,
 * so there are no TOCTOU races and no partial-state visibility between threads.
 *
 * <p><b>Trade-off:</b> A single lock limits throughput under heavy concurrency.
 * For this assignment the simplicity and correctness guarantees are more
 * valuable than raw throughput. A future production engine could use
 * per-seat locking, optimistic CAS, or a relational database with row-level
 * locking to scale further. Those approaches are substantially harder to
 * reason about and test correctly.
 *
 * <h2>Business rules enforced</h2>
 * <ol>
 *   <li>R1 – A seat cannot be held by two users simultaneously or sold twice.</li>
 *   <li>R2 – Maximum {@value #MAX_HOLDS_PER_USER} active holds per user.</li>
 *   <li>R3 – An expired hold may still be confirmed if no one else has taken the seat.</li>
 *   <li>R4 – Holds are never extended; the original expiry timestamp is final.</li>
 *   <li>R5 – Repeating the same active hold request for the same seat by the same user
 *            is idempotent: returns the original hold ID.</li>
 *   <li>R6 – {@code hold_many} is atomic: either every hold succeeds or no state changes.</li>
 * </ol>
 */
public class SeatBookingEngine {

    /** Maximum number of concurrent active holds per user. */
    public static final int MAX_HOLDS_PER_USER = 4;

    /**
     * Default hold duration in milliseconds (10 minutes). Callers may override
     * per-call via the holdDurationMillis parameter.
     */
    public static final long DEFAULT_HOLD_DURATION_MILLIS = 10 * 60 * 1000L;

    // -----------------------------------------------------------------------
    // State – all accessed only under the engine lock
    // -----------------------------------------------------------------------

    /** All seats known to the engine. */
    private final Map<String, SeatInfo> seats = new HashMap<>();

    /** All hold records ever created, indexed by hold ID. */
    private final Map<String, HoldRecord> holdsById = new HashMap<>();

    /**
     * Index from userId to list of hold records.
     * Used to count active holds quickly without scanning the whole map.
     */
    private final Map<String, List<HoldRecord>> holdsByUser = new HashMap<>();

    private final Clock clock;

    /** Counter for hold IDs, used in addition to random UUIDs for readability in tests. */
    private final AtomicLong holdIdCounter = new AtomicLong(0);

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    /** Create an engine with the system clock. */
    public SeatBookingEngine() {
        this(new SystemClock());
    }

    /** Create an engine with an injectable clock (for tests). */
    public SeatBookingEngine(Clock clock) {
        if (clock == null) throw new IllegalArgumentException("clock must not be null");
        this.clock = clock;
    }

    // -----------------------------------------------------------------------
    // Seat registration
    // -----------------------------------------------------------------------

    /**
     * Register seats so the engine knows they exist. Call this before
     * any booking operations. Re-registering a known seat is a no-op.
     *
     * @param seatIds one or more seat identifiers
     */
    public synchronized void registerSeats(String... seatIds) {
        for (String seatId : seatIds) {
            validateNonBlank(seatId, "seatId");
            seats.putIfAbsent(seatId, new SeatInfo(seatId));
        }
    }

    // -----------------------------------------------------------------------
    // hold
    // -----------------------------------------------------------------------

    /**
     * Hold a seat for a user for the default duration.
     *
     * @param userId  the requesting user
     * @param seatId  the seat to hold
     * @return Result containing the new (or existing idempotent) HoldRecord on success
     */
    public synchronized Result hold(String userId, String seatId) {
        return hold(userId, seatId, DEFAULT_HOLD_DURATION_MILLIS);
    }

    /**
     * Hold a seat for a user for a specific duration.
     *
     * <p>Idempotent: if the user already has an active (non-expired) hold on this seat,
     * the original HoldRecord is returned unchanged. (R5)
     *
     * @param userId              the requesting user
     * @param seatId              the seat to hold
     * @param holdDurationMillis  how long the hold should last
     * @return Result containing a HoldRecord on success
     */
    public synchronized Result hold(String userId, String seatId, long holdDurationMillis) {
        // --- Input validation (before touching state) ---
        Result inputError = validateHoldInputs(userId, seatId, holdDurationMillis);
        if (inputError != null) return inputError;

        SeatInfo seat = seats.get(seatId);
        if (seat == null) {
            return Result.error(Result.Code.SEAT_NOT_FOUND,
                    "Seat '" + seatId + "' does not exist");
        }

        long now = clock.nowMillis();

        // Check for idempotency: same user already holds this seat actively (R5)
        if (seat.getStatus() == SeatInfo.SeatStatus.HELD) {
            HoldRecord existingHold = seat.getCurrentHold();
            if (existingHold != null &&
                existingHold.getUserId().equals(userId) &&
                !existingHold.isExpiredAt(now)) {
                // Idempotent: return the original hold (no extension, R4)
                return Result.ok("Idempotent hold returned (seat already held by you)", existingHold);
            }
        }

        // Check seat availability
        if (!isSeatAvailableForHold(seat, now)) {
            return Result.error(Result.Code.SEAT_UNAVAILABLE,
                    "Seat '" + seatId + "' is not available for a new hold (status: " + seat.getStatus() + ")");
        }

        // Check active hold count for this user (R2)
        int activeHolds = countActiveHolds(userId, now);
        if (activeHolds >= MAX_HOLDS_PER_USER) {
            return Result.error(Result.Code.TOO_MANY_HOLDS,
                    "User '" + userId + "' already has " + MAX_HOLDS_PER_USER +
                    " active hold(s). Release or confirm some before adding more.");
        }

        // Check for overflow in expiry calculation
        if (willOverflow(now, holdDurationMillis)) {
            return Result.error(Result.Code.INVALID_ARGUMENT,
                    "Hold duration " + holdDurationMillis + " ms would overflow the expiry timestamp");
        }

        // --- All validations passed; mutate state ---
        String holdId = generateHoldId();
        long expiresAt = now + holdDurationMillis;
        HoldRecord newHold = new HoldRecord(holdId, userId, seatId, now, expiresAt);

        // If the seat had a prior expired hold, mark the old hold as superseded
        if (seat.getCurrentHold() != null) {
            seat.getCurrentHold().setStatus(HoldRecord.Status.SUPERSEDED);
        }

        seat.applyHold(newHold);
        holdsById.put(holdId, newHold);
        holdsByUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(newHold);

        return Result.ok("Seat '" + seatId + "' held successfully", newHold);
    }

    // -----------------------------------------------------------------------
    // confirm
    // -----------------------------------------------------------------------

    /**
     * Confirm a hold, completing the sale of the seat.
     *
     * <p>An expired hold may still be confirmed if no one else has taken the seat (R3).
     * The hold's expiry timestamp is never extended (R4).
     *
     * @param userId  must match the user who created the hold
     * @param holdId  the hold to confirm
     * @return Result indicating success or the reason for failure
     */
    public synchronized Result confirm(String userId, String holdId) {
        Result inputError = validateNonBlankPair(userId, holdId, "userId", "holdId");
        if (inputError != null) return inputError;

        HoldRecord hold = holdsById.get(holdId);
        if (hold == null) {
            return Result.error(Result.Code.HOLD_NOT_FOUND,
                    "Hold ID '" + holdId + "' not found");
        }

        // Ownership check
        if (!hold.getUserId().equals(userId)) {
            return Result.error(Result.Code.HOLD_BELONGS_TO_OTHER,
                    "Hold '" + holdId + "' belongs to a different user");
        }

        // Lifecycle checks
        switch (hold.getStatus()) {
            case CONFIRMED:
                return Result.error(Result.Code.HOLD_ALREADY_CONFIRMED,
                        "Hold '" + holdId + "' has already been confirmed");
            case RELEASED:
                return Result.error(Result.Code.HOLD_ALREADY_RELEASED,
                        "Hold '" + holdId + "' was released and cannot be confirmed");
            case SUPERSEDED:
                return Result.error(Result.Code.HOLD_SUPERSEDED,
                        "Hold '" + holdId + "' was superseded: another user acquired this seat first");
            case ACTIVE:
            default:
                break;
        }

        // At this point the hold is ACTIVE (possibly expired but not superseded yet).
        // Per R3: expired is OK if the seat is still ours. We check the seat directly.
        SeatInfo seat = seats.get(hold.getSeatId());
        if (seat == null) {
            // Should never happen if seats are registered consistently
            return Result.error(Result.Code.SEAT_NOT_FOUND,
                    "Seat '" + hold.getSeatId() + "' not found (data inconsistency)");
        }

        // The seat's current hold must still be this hold (ensures no one else snuck in)
        if (seat.getCurrentHold() == null || !seat.getCurrentHold().getHoldId().equals(holdId)) {
            return Result.error(Result.Code.HOLD_SUPERSEDED,
                    "Hold '" + holdId + "' was superseded: another user acquired this seat first");
        }

        // --- All checks passed; finalize the sale ---
        hold.setStatus(HoldRecord.Status.CONFIRMED);
        seat.applySale(userId);

        return Result.ok("Seat '" + hold.getSeatId() + "' confirmed and sold to user '" + userId + "'", hold);
    }

    // -----------------------------------------------------------------------
    // release
    // -----------------------------------------------------------------------

    /**
     * Release an active hold, making the seat available again.
     *
     * @param userId  must match the user who created the hold
     * @param holdId  the hold to release
     * @return Result indicating success or the reason for failure
     */
    public synchronized Result release(String userId, String holdId) {
        Result inputError = validateNonBlankPair(userId, holdId, "userId", "holdId");
        if (inputError != null) return inputError;

        HoldRecord hold = holdsById.get(holdId);
        if (hold == null) {
            return Result.error(Result.Code.HOLD_NOT_FOUND,
                    "Hold ID '" + holdId + "' not found");
        }

        if (!hold.getUserId().equals(userId)) {
            return Result.error(Result.Code.HOLD_BELONGS_TO_OTHER,
                    "Hold '" + holdId + "' belongs to a different user");
        }

        switch (hold.getStatus()) {
            case CONFIRMED:
                return Result.error(Result.Code.HOLD_ALREADY_CONFIRMED,
                        "Hold '" + holdId + "' has been confirmed; a sold seat cannot be released");
            case RELEASED:
                return Result.error(Result.Code.HOLD_ALREADY_RELEASED,
                        "Hold '" + holdId + "' has already been released");
            case SUPERSEDED:
                return Result.error(Result.Code.HOLD_SUPERSEDED,
                        "Hold '" + holdId + "' was superseded; nothing to release");
            case ACTIVE:
            default:
                break;
        }

        SeatInfo seat = seats.get(hold.getSeatId());
        if (seat == null) {
            return Result.error(Result.Code.SEAT_NOT_FOUND,
                    "Seat '" + hold.getSeatId() + "' not found (data inconsistency)");
        }

        // Mark the hold record as released
        hold.setStatus(HoldRecord.Status.RELEASED);
        // Free the seat
        seat.applyRelease();

        return Result.ok("Hold '" + holdId + "' released; seat '" + hold.getSeatId() + "' is now available", hold);
    }

    // -----------------------------------------------------------------------
    // seat_status
    // -----------------------------------------------------------------------

    /**
     * Return the current status of a seat.
     *
     * @param seatId  the seat to query
     * @return Result containing a SeatInfo snapshot on success
     */
    public synchronized Result seatStatus(String seatId) {
        if (seatId == null || seatId.isBlank()) {
            return Result.error(Result.Code.INVALID_ARGUMENT, "seatId must not be blank");
        }

        SeatInfo seat = seats.get(seatId);
        if (seat == null) {
            return Result.error(Result.Code.SEAT_NOT_FOUND, "Seat '" + seatId + "' does not exist");
        }

        return Result.ok("Status retrieved for seat '" + seatId + "'", seat);
    }

    // -----------------------------------------------------------------------
    // hold_many
    // -----------------------------------------------------------------------

    /**
     * Atomically hold multiple seats for a user. (R6)
     *
     * <p>Either every seat is held successfully, or no state changes are applied.
     * The implementation validates all seats first, then applies holds only if
     * every pre-condition passes.
     *
     * @param userId              the requesting user
     * @param seatIds             the seats to hold
     * @param holdDurationMillis  hold duration for all seats
     * @return Result containing a List of HoldRecords on success
     */
    public synchronized Result holdMany(String userId, List<String> seatIds, long holdDurationMillis) {
        // --- Input validation ---
        if (userId == null || userId.isBlank()) {
            return Result.error(Result.Code.INVALID_ARGUMENT, "userId must not be blank");
        }
        if (seatIds == null || seatIds.isEmpty()) {
            return Result.error(Result.Code.INVALID_ARGUMENT, "seatIds must not be null or empty");
        }
        if (holdDurationMillis <= 0) {
            return Result.error(Result.Code.INVALID_ARGUMENT,
                    "holdDurationMillis must be positive, got " + holdDurationMillis);
        }

        // Detect duplicate seat IDs in the request
        long distinctCount = seatIds.stream().distinct().count();
        if (distinctCount != seatIds.size()) {
            return Result.error(Result.Code.INVALID_ARGUMENT,
                    "seatIds contains duplicates; each seat can only appear once in a batch request");
        }

        long now = clock.nowMillis();

        // Check overflow once for all seats
        if (willOverflow(now, holdDurationMillis)) {
            return Result.error(Result.Code.INVALID_ARGUMENT,
                    "Hold duration would overflow the expiry timestamp");
        }

        // Count current active holds – we will add seatIds.size() more
        int currentActive = countActiveHolds(userId, now);
        if ((long) currentActive + seatIds.size() > MAX_HOLDS_PER_USER) {
            return Result.error(Result.Code.TOO_MANY_HOLDS,
                    "Batch of " + seatIds.size() + " hold(s) would exceed the " +
                    MAX_HOLDS_PER_USER + "-hold limit for user '" + userId + "' " +
                    "(currently has " + currentActive + " active hold(s))");
        }

        // Validate every seat before changing any state
        List<SeatInfo> seatInfos = new ArrayList<>(seatIds.size());
        for (String seatId : seatIds) {
            if (seatId == null || seatId.isBlank()) {
                return Result.error(Result.Code.INVALID_ARGUMENT,
                        "seatIds contains a blank or null entry");
            }
            SeatInfo seat = seats.get(seatId);
            if (seat == null) {
                return Result.error(Result.Code.SEAT_NOT_FOUND,
                        "Batch failed: seat '" + seatId + "' does not exist");
            }
            // For idempotency in batch: if the user already holds this seat, treat as ok
            if (seat.getStatus() == SeatInfo.SeatStatus.HELD) {
                HoldRecord existing = seat.getCurrentHold();
                if (existing != null && existing.getUserId().equals(userId) && !existing.isExpiredAt(now)) {
                    // Will return the existing hold in the result list — counted below
                    seatInfos.add(seat);
                    continue;
                }
            }
            if (!isSeatAvailableForHold(seat, now)) {
                return Result.error(Result.Code.BATCH_FAILED,
                        "Batch failed: seat '" + seatId + "' is not available (status: " +
                        seat.getStatus() + "). No holds were placed.");
            }
            seatInfos.add(seat);
        }

        // --- All validations passed; apply all holds atomically ---
        List<HoldRecord> newHolds = new ArrayList<>(seatIds.size());
        long expiresAt = now + holdDurationMillis;

        for (int i = 0; i < seatIds.size(); i++) {
            String seatId = seatIds.get(i);
            SeatInfo seat = seatInfos.get(i);

            // Check if this is an idempotent entry (user already holds it actively)
            if (seat.getStatus() == SeatInfo.SeatStatus.HELD) {
                HoldRecord existing = seat.getCurrentHold();
                if (existing != null && existing.getUserId().equals(userId) && !existing.isExpiredAt(now)) {
                    newHolds.add(existing);
                    continue;
                }
            }

            // Mark any old expired hold as superseded
            if (seat.getCurrentHold() != null) {
                seat.getCurrentHold().setStatus(HoldRecord.Status.SUPERSEDED);
            }

            String holdId = generateHoldId();
            HoldRecord newHold = new HoldRecord(holdId, userId, seatId, now, expiresAt);
            seat.applyHold(newHold);
            holdsById.put(holdId, newHold);
            holdsByUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(newHold);
            newHolds.add(newHold);
        }

        return Result.ok("Batch of " + newHolds.size() + " hold(s) placed for user '" + userId + "'", newHolds);
    }

    /** Convenience overload that uses the default hold duration. */
    public synchronized Result holdMany(String userId, List<String> seatIds) {
        return holdMany(userId, seatIds, DEFAULT_HOLD_DURATION_MILLIS);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the number of holds for this user that are currently active
     * (status ACTIVE and not yet expired at the given time).
     */
    private int countActiveHolds(String userId, long nowMillis) {
        List<HoldRecord> userHolds = holdsByUser.getOrDefault(userId, List.of());
        int count = 0;
        for (HoldRecord h : userHolds) {
            if (h.getStatus() == HoldRecord.Status.ACTIVE && !h.isExpiredAt(nowMillis)) {
                count++;
            }
        }
        return count;
    }

    /**
     * A seat is available for a new hold if:
     * - it is AVAILABLE, or
     * - it is HELD but the current hold is expired (the hold stays in ACTIVE status
     *   until confirmed/released/superseded, but the time has passed).
     *
     * Sold seats are never available for a new hold.
     */
    private boolean isSeatAvailableForHold(SeatInfo seat, long nowMillis) {
        switch (seat.getStatus()) {
            case AVAILABLE:
                return true;
            case HELD:
                // The seat can be taken over only if the current hold has expired
                HoldRecord currentHold = seat.getCurrentHold();
                return currentHold != null && currentHold.isExpiredAt(nowMillis);
            case SOLD:
                return false;
            default:
                return false;
        }
    }

    private String generateHoldId() {
        // Combine a counter with a UUID fragment for uniqueness + readability
        return "H" + holdIdCounter.incrementAndGet() + "-" +
               UUID.randomUUID().toString().substring(0, 8);
    }

    private Result validateHoldInputs(String userId, String seatId, long holdDurationMillis) {
        if (userId == null || userId.isBlank()) {
            return Result.error(Result.Code.INVALID_ARGUMENT, "userId must not be blank");
        }
        if (seatId == null || seatId.isBlank()) {
            return Result.error(Result.Code.INVALID_ARGUMENT, "seatId must not be blank");
        }
        if (holdDurationMillis <= 0) {
            return Result.error(Result.Code.INVALID_ARGUMENT,
                    "holdDurationMillis must be positive, got " + holdDurationMillis);
        }
        return null;
    }

    private Result validateNonBlankPair(String a, String b, String nameA, String nameB) {
        if (a == null || a.isBlank()) {
            return Result.error(Result.Code.INVALID_ARGUMENT, nameA + " must not be blank");
        }
        if (b == null || b.isBlank()) {
            return Result.error(Result.Code.INVALID_ARGUMENT, nameB + " must not be blank");
        }
        return null;
    }

    private void validateNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }

    /** Returns true if {@code base + delta} would overflow a long. */
    private boolean willOverflow(long base, long delta) {
        return delta > 0 && base > Long.MAX_VALUE - delta;
    }
}
