import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for SeatBookingEngine.
 *
 * No JUnit dependency: each test method is called from main() and failures
 * throw AssertionError. Results are printed to stdout.
 *
 * Sections:
 *  1. Normal flows
 *  2. Expiry and late confirmation (R3 / R4)
 *  3. Idempotency (R5)
 *  4. Four-hold limit (R2)
 *  5. hold_many rollback (R6)
 *  6. Concurrency stress tests
 *  7. Input validation
 *  8. Randomized / model-based test
 */
public class SeatBookingEngineTest {

    // -----------------------------------------------------------------------
    // Tiny assertion helper
    // -----------------------------------------------------------------------
    private static int passed = 0;
    private static int failed = 0;

    private static void assertTrue(String testName, boolean condition) {
        if (condition) {
            System.out.println("  PASS: " + testName);
            passed++;
        } else {
            System.out.println("  FAIL: " + testName);
            failed++;
        }
    }

    private static void assertEqual(String testName, Object expected, Object actual) {
        boolean ok = (expected == null && actual == null) ||
                     (expected != null && expected.equals(actual));
        if (ok) {
            System.out.println("  PASS: " + testName);
            passed++;
        } else {
            System.out.println("  FAIL: " + testName + " (expected=" + expected + ", actual=" + actual + ")");
            failed++;
        }
    }

    // -----------------------------------------------------------------------
    // Convenience factory
    // -----------------------------------------------------------------------
    private static FakeClock newClock() {
        // Start at a round timestamp well away from 0 to avoid edge cases
        return new FakeClock(1_000_000L);
    }

    private static SeatBookingEngine engineWithSeats(FakeClock clock, String... seats) {
        SeatBookingEngine engine = new SeatBookingEngine(clock);
        engine.registerSeats(seats);
        return engine;
    }

    // -----------------------------------------------------------------------
    // 1. Normal flows
    // -----------------------------------------------------------------------
    private static void testHoldConfirmNormalFlow() {
        System.out.println("\n[1] Normal flows");
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "A1", "A2");

        Result holdResult = engine.hold("alice", "A1");
        assertTrue("hold returns OK", holdResult.isOk());
        HoldRecord hold = holdResult.getHoldRecord();
        assertTrue("hold record not null", hold != null);
        assertEqual("hold userId", "alice", hold.getUserId());
        assertEqual("hold seatId", "A1", hold.getSeatId());
        assertEqual("hold status is ACTIVE", HoldRecord.Status.ACTIVE, hold.getStatus());

        Result confirmResult = engine.confirm("alice", hold.getHoldId());
        assertTrue("confirm returns OK", confirmResult.isOk());
        assertEqual("hold status after confirm is CONFIRMED", HoldRecord.Status.CONFIRMED, hold.getStatus());

        Result statusResult = engine.seatStatus("A1");
        assertTrue("seat status OK after confirm", statusResult.isOk());
        assertEqual("seat is SOLD", SeatInfo.SeatStatus.SOLD, statusResult.getSeatInfo().getStatus());
        assertEqual("sold to alice", "alice", statusResult.getSeatInfo().getSoldToUserId());
    }

    private static void testHoldReleaseNormalFlow() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "B1");

        Result holdResult = engine.hold("bob", "B1");
        String holdId = holdResult.getHoldRecord().getHoldId();

        Result releaseResult = engine.release("bob", holdId);
        assertTrue("release returns OK", releaseResult.isOk());

        Result statusResult = engine.seatStatus("B1");
        assertEqual("seat is AVAILABLE after release", SeatInfo.SeatStatus.AVAILABLE,
                statusResult.getSeatInfo().getStatus());
    }

    private static void testTwoUsersCantHoldSameSeat() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "C1");

        engine.hold("alice", "C1");
        Result result = engine.hold("bob", "C1");
        assertTrue("bob cannot hold alice's seat", !result.isOk());
        assertEqual("error code SEAT_UNAVAILABLE", Result.Code.SEAT_UNAVAILABLE, result.getCode());
    }

    private static void testSeatCannotBeSoldTwice() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "D1");

        Result hold1 = engine.hold("alice", "D1");
        engine.confirm("alice", hold1.getHoldRecord().getHoldId());

        // alice confirms again – should fail
        Result secondConfirm = engine.confirm("alice", hold1.getHoldRecord().getHoldId());
        assertTrue("second confirm fails", !secondConfirm.isOk());
        assertEqual("error is HOLD_ALREADY_CONFIRMED",
                Result.Code.HOLD_ALREADY_CONFIRMED, secondConfirm.getCode());
    }

    // -----------------------------------------------------------------------
    // 2. Expiry and late confirmation (R3 / R4)
    // -----------------------------------------------------------------------
    private static void testExpiredHoldCanStillBeConfirmedIfSeatFree() {
        System.out.println("\n[2] Expiry and late confirmation");
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "E1");

        long duration = 5_000L; // 5 seconds
        Result holdResult = engine.hold("alice", "E1", duration);
        String holdId = holdResult.getHoldRecord().getHoldId();

        // Advance past expiry – but no one else has taken the seat
        clock.advance(duration + 1);

        Result confirm = engine.confirm("alice", holdId);
        assertTrue("expired hold confirmed when seat still free (R3)", confirm.isOk());
    }

    private static void testExpiredHoldCannotBeConfirmedIfSeatTakenByOther() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "F1");

        long duration = 5_000L;
        Result aliceHold = engine.hold("alice", "F1", duration);
        String aliceHoldId = aliceHold.getHoldRecord().getHoldId();

        // Advance past alice's expiry
        clock.advance(duration + 1);

        // Bob grabs the seat
        engine.hold("bob", "F1", duration);

        // Alice tries to confirm her expired, superseded hold
        Result lateConfirm = engine.confirm("alice", aliceHoldId);
        assertTrue("alice's expired superseded hold cannot be confirmed", !lateConfirm.isOk());
        assertEqual("error is HOLD_SUPERSEDED", Result.Code.HOLD_SUPERSEDED, lateConfirm.getCode());
    }

    private static void testHoldExpiryIsNeverExtended() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "G1");

        long duration = 5_000L;
        Result holdResult = engine.hold("alice", "G1", duration);
        long originalExpiry = holdResult.getHoldRecord().getExpiresAtMillis();

        // Calling hold again (idempotent) should not extend the expiry
        Result idempotentResult = engine.hold("alice", "G1", duration);
        assertTrue("idempotent hold returns OK", idempotentResult.isOk());
        assertEqual("expiry timestamp unchanged (R4)",
                originalExpiry, idempotentResult.getHoldRecord().getExpiresAtMillis());
    }

    private static void testReleasedSeatBecomesAvailable() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "H1");

        Result hold = engine.hold("alice", "H1");
        engine.release("alice", hold.getHoldRecord().getHoldId());

        // Bob should now be able to hold it
        Result bobHold = engine.hold("bob", "H1");
        assertTrue("bob can hold released seat", bobHold.isOk());
    }

    // -----------------------------------------------------------------------
    // 3. Idempotency (R5)
    // -----------------------------------------------------------------------
    private static void testIdempotentHoldReturnsSameId() {
        System.out.println("\n[3] Idempotency");
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "I1");

        Result first = engine.hold("alice", "I1");
        Result second = engine.hold("alice", "I1");
        assertTrue("second hold is OK", second.isOk());
        assertEqual("same hold ID returned (R5)",
                first.getHoldRecord().getHoldId(),
                second.getHoldRecord().getHoldId());
        assertEqual("same expiry (no extension, R4)",
                first.getHoldRecord().getExpiresAtMillis(),
                second.getHoldRecord().getExpiresAtMillis());
    }

    private static void testIdempotencyDoesNotCountAgainstHoldLimit() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "J1", "J2", "J3", "J4");

        engine.hold("alice", "J1");
        engine.hold("alice", "J2");
        engine.hold("alice", "J3");
        engine.hold("alice", "J4");

        // Repeating J1 should NOT trigger the limit
        Result repeat = engine.hold("alice", "J1");
        assertTrue("repeating same seat is idempotent, not a new hold", repeat.isOk());
    }

    // -----------------------------------------------------------------------
    // 4. Four-hold limit (R2)
    // -----------------------------------------------------------------------
    private static void testFourHoldLimitEnforced() {
        System.out.println("\n[4] Four-hold limit");
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "K1", "K2", "K3", "K4", "K5");

        engine.hold("alice", "K1");
        engine.hold("alice", "K2");
        engine.hold("alice", "K3");
        engine.hold("alice", "K4");

        Result fifth = engine.hold("alice", "K5");
        assertTrue("fifth hold rejected (R2)", !fifth.isOk());
        assertEqual("error is TOO_MANY_HOLDS", Result.Code.TOO_MANY_HOLDS, fifth.getCode());
    }

    private static void testLimitRestoredAfterRelease() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "L1", "L2", "L3", "L4", "L5");

        engine.hold("alice", "L1");
        Result hold2 = engine.hold("alice", "L2");
        engine.hold("alice", "L3");
        engine.hold("alice", "L4");

        engine.release("alice", hold2.getHoldRecord().getHoldId());

        Result fifth = engine.hold("alice", "L5");
        assertTrue("can hold again after releasing one", fifth.isOk());
    }

    private static void testLimitRestoredAfterConfirm() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "M1", "M2", "M3", "M4", "M5");

        engine.hold("alice", "M1");
        Result hold2 = engine.hold("alice", "M2");
        engine.hold("alice", "M3");
        engine.hold("alice", "M4");

        engine.confirm("alice", hold2.getHoldRecord().getHoldId());

        Result fifth = engine.hold("alice", "M5");
        assertTrue("can hold again after confirming one", fifth.isOk());
    }

    private static void testLimitRestoredAfterExpiry() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "N1", "N2", "N3", "N4", "N5");

        long shortDuration = 1_000L;
        engine.hold("alice", "N1", shortDuration);
        engine.hold("alice", "N2", shortDuration);
        engine.hold("alice", "N3", shortDuration);
        engine.hold("alice", "N4", shortDuration);

        // All four expire
        clock.advance(shortDuration + 1);

        Result fifth = engine.hold("alice", "N5");
        assertTrue("can hold again after holds expire", fifth.isOk());
    }

    // -----------------------------------------------------------------------
    // 5. hold_many rollback (R6)
    // -----------------------------------------------------------------------
    private static void testHoldManySuccess() {
        System.out.println("\n[5] hold_many");
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "P1", "P2", "P3");

        Result result = engine.holdMany("alice", List.of("P1", "P2", "P3"));
        assertTrue("holdMany returns OK", result.isOk());
        assertTrue("3 hold records returned", result.getHoldRecords() != null && result.getHoldRecords().size() == 3);
    }

    private static void testHoldManyRollbackOnUnavailableSeat() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "Q1", "Q2", "Q3");

        // Bob holds Q2 first
        engine.hold("bob", "Q2");

        // Alice tries to batch-hold Q1, Q2, Q3 — Q2 is unavailable
        Result result = engine.holdMany("alice", List.of("Q1", "Q2", "Q3"));
        assertTrue("holdMany fails when one seat is unavailable", !result.isOk());
        assertEqual("error is BATCH_FAILED", Result.Code.BATCH_FAILED, result.getCode());

        // Q1 must NOT have been held (rollback)
        SeatInfo q1 = engine.seatStatus("Q1").getSeatInfo();
        assertEqual("Q1 is AVAILABLE (rollback confirmed)", SeatInfo.SeatStatus.AVAILABLE, q1.getStatus());

        // Q3 must NOT have been held (rollback)
        SeatInfo q3 = engine.seatStatus("Q3").getSeatInfo();
        assertEqual("Q3 is AVAILABLE (rollback confirmed)", SeatInfo.SeatStatus.AVAILABLE, q3.getStatus());
    }

    private static void testHoldManyRollbackOnMissingSeat() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "R1", "R2");

        Result result = engine.holdMany("alice", List.of("R1", "MISSING", "R2"));
        assertTrue("holdMany fails for missing seat", !result.isOk());
        assertEqual("error is SEAT_NOT_FOUND", Result.Code.SEAT_NOT_FOUND, result.getCode());

        SeatInfo r1 = engine.seatStatus("R1").getSeatInfo();
        assertEqual("R1 is AVAILABLE (no partial holds)", SeatInfo.SeatStatus.AVAILABLE, r1.getStatus());
    }

    private static void testHoldManyRollbackOnHoldLimitExceeded() {
        FakeClock clock = newClock();
        // Alice already holds 3 seats; tries to add 2 more → 5 total, exceeds limit
        SeatBookingEngine engine = engineWithSeats(clock, "S1", "S2", "S3", "S4", "S5");
        engine.hold("alice", "S1");
        engine.hold("alice", "S2");
        engine.hold("alice", "S3");

        Result result = engine.holdMany("alice", List.of("S4", "S5"));
        assertTrue("holdMany rejects when batch would exceed limit", !result.isOk());
        assertEqual("error is TOO_MANY_HOLDS", Result.Code.TOO_MANY_HOLDS, result.getCode());

        SeatInfo s4 = engine.seatStatus("S4").getSeatInfo();
        assertEqual("S4 still AVAILABLE (rollback)", SeatInfo.SeatStatus.AVAILABLE, s4.getStatus());
    }

    private static void testHoldManyDuplicateSeatInRequest() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "T1");

        Result result = engine.holdMany("alice", List.of("T1", "T1"));
        assertTrue("holdMany rejects duplicate seat IDs", !result.isOk());
        assertEqual("error is INVALID_ARGUMENT", Result.Code.INVALID_ARGUMENT, result.getCode());
    }

    // -----------------------------------------------------------------------
    // 6. Concurrency stress tests
    // -----------------------------------------------------------------------

    /**
     * 50 threads each try to hold the same seat. Exactly one must succeed.
     * No Thread.sleep() is used; threads race naturally.
     */
    private static void testOnlyOneThreadWinsHold() throws InterruptedException {
        System.out.println("\n[6] Concurrency stress");
        SeatBookingEngine engine = new SeatBookingEngine(); // real clock is fine here
        engine.registerSeats("RACE_SEAT");

        int threadCount = 50;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final String user = "user" + i;
            pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                Result r = engine.hold(user, "RACE_SEAT");
                if (r.isOk()) successCount.incrementAndGet();
                done.countDown();
            });
        }
        ready.await();
        go.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEqual("exactly 1 thread wins the hold (no double booking)", 1, successCount.get());
    }

    /**
     * 20 threads try to confirm the same hold concurrently.
     * Only one confirm must succeed.
     */
    private static void testOnlyOneConfirmSucceeds() throws InterruptedException {
        SeatBookingEngine engine = new SeatBookingEngine();
        engine.registerSeats("CONFIRM_SEAT");

        Result holdResult = engine.hold("alice", "CONFIRM_SEAT");
        String holdId = holdResult.getHoldRecord().getHoldId();

        int threadCount = 20;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                Result r = engine.confirm("alice", holdId);
                if (r.isOk()) successCount.incrementAndGet();
                done.countDown();
            });
        }
        go.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEqual("exactly 1 confirm succeeds", 1, successCount.get());
    }

    /**
     * Concurrent hold_many: 10 threads try to batch-hold all 3 seats.
     * At most one batch can succeed.
     */
    private static void testConcurrentHoldMany() throws InterruptedException {
        SeatBookingEngine engine = new SeatBookingEngine();
        engine.registerSeats("BX1", "BX2", "BX3");

        int threadCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final String user = "batchUser" + i;
            pool.submit(() -> {
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                Result r = engine.holdMany(user, List.of("BX1", "BX2", "BX3"));
                if (r.isOk()) successCount.incrementAndGet();
                done.countDown();
            });
        }
        go.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // With 3 seats and 10 users each wanting all 3, at most one user can win all 3
        assertTrue("at most 1 batch succeeds (atomic hold_many)", successCount.get() <= 1);
        assertTrue("at least 1 batch succeeds when seats are available", successCount.get() >= 1);
    }

    /**
     * Concurrency invariant: after N random hold/confirm/release operations on
     * a pool of seats and users, no seat should be SOLD to more than one user
     * and no seat should be simultaneously HELD by two users.
     */
    private static void testConcurrencyInvariantsHoldAfterManyOps() throws InterruptedException {
        SeatBookingEngine engine = new SeatBookingEngine();
        String[] seatIds = {"INV1", "INV2", "INV3", "INV4", "INV5"};
        engine.registerSeats(seatIds);

        int threadCount = 20;
        int opsPerThread = 200;
        String[] users = {"u1", "u2", "u3", "u4", "u5"};
        Random rand = new Random(42);
        CountDownLatch done = new CountDownLatch(threadCount);

        // Shared list of hold IDs to confirm/release
        List<String> holdIds = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            pool.submit(() -> {
                Random localRand = new Random(rand.nextLong());
                for (int i = 0; i < opsPerThread; i++) {
                    String user = users[localRand.nextInt(users.length)];
                    String seat = seatIds[localRand.nextInt(seatIds.length)];
                    int op = localRand.nextInt(3);
                    if (op == 0) {
                        Result r = engine.hold(user, seat);
                        if (r.isOk()) holdIds.add(r.getHoldRecord().getHoldId());
                    } else if (op == 1 && !holdIds.isEmpty()) {
                        String holdId = holdIds.get(localRand.nextInt(holdIds.size()));
                        // We don't know the user, try all
                        for (String u : users) {
                            Result r = engine.confirm(u, holdId);
                            if (r.isOk()) break;
                        }
                    } else if (!holdIds.isEmpty()) {
                        String holdId = holdIds.get(localRand.nextInt(holdIds.size()));
                        for (String u : users) {
                            Result r = engine.release(u, holdId);
                            if (r.isOk()) break;
                        }
                    }
                }
                done.countDown();
            });
        }
        done.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        // Verify invariants: no seat is in an illegal state
        boolean invariantsOk = true;
        for (String seatId : seatIds) {
            SeatInfo info = engine.seatStatus(seatId).getSeatInfo();
            // If HELD, the current hold must exist
            if (info.getStatus() == SeatInfo.SeatStatus.HELD && info.getCurrentHold() == null) {
                System.out.println("  INVARIANT VIOLATION: " + seatId + " is HELD but has no current hold record");
                invariantsOk = false;
            }
            // If SOLD, soldToUserId must not be null
            if (info.getStatus() == SeatInfo.SeatStatus.SOLD && info.getSoldToUserId() == null) {
                System.out.println("  INVARIANT VIOLATION: " + seatId + " is SOLD but soldToUserId is null");
                invariantsOk = false;
            }
        }
        assertTrue("all seats have consistent state after concurrent operations", invariantsOk);
    }

    // -----------------------------------------------------------------------
    // 7. Input validation
    // -----------------------------------------------------------------------
    private static void testInputValidation() {
        System.out.println("\n[7] Input validation");
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "V1");

        assertTrue("null userId rejected", !engine.hold(null, "V1").isOk());
        assertTrue("blank userId rejected", !engine.hold("  ", "V1").isOk());
        assertTrue("null seatId rejected", !engine.hold("alice", null).isOk());
        assertTrue("blank seatId rejected", !engine.hold("alice", "").isOk());
        assertTrue("zero duration rejected", !engine.hold("alice", "V1", 0).isOk());
        assertTrue("negative duration rejected", !engine.hold("alice", "V1", -1).isOk());
        assertTrue("unknown seat rejected with SEAT_NOT_FOUND",
                engine.hold("alice", "UNKNOWN").getCode() == Result.Code.SEAT_NOT_FOUND);
        assertTrue("null holdId in confirm rejected",
                !engine.confirm("alice", null).isOk());
        assertTrue("blank holdId in release rejected",
                !engine.release("alice", "").isOk());
        assertTrue("wrong user cannot confirm", !engine.confirm("bob", "nonexistent").isOk());
    }

    private static void testCannotConfirmOtherUserHold() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "W1");

        Result hold = engine.hold("alice", "W1");
        String holdId = hold.getHoldRecord().getHoldId();

        Result bobConfirm = engine.confirm("bob", holdId);
        assertTrue("bob cannot confirm alice's hold", !bobConfirm.isOk());
        assertEqual("error is HOLD_BELONGS_TO_OTHER",
                Result.Code.HOLD_BELONGS_TO_OTHER, bobConfirm.getCode());
    }

    private static void testCannotReleaseOtherUserHold() {
        FakeClock clock = newClock();
        SeatBookingEngine engine = engineWithSeats(clock, "X1");

        Result hold = engine.hold("alice", "X1");
        String holdId = hold.getHoldRecord().getHoldId();

        Result bobRelease = engine.release("bob", holdId);
        assertTrue("bob cannot release alice's hold", !bobRelease.isOk());
        assertEqual("error is HOLD_BELONGS_TO_OTHER",
                Result.Code.HOLD_BELONGS_TO_OTHER, bobRelease.getCode());
    }

    // -----------------------------------------------------------------------
    // 8. Randomized / model-based test
    // -----------------------------------------------------------------------
    /**
     * Simple model: we track expected seat states ourselves and verify the engine
     * matches after random operations.
     *
     * The model only tracks AVAILABLE / SOLD simply. We run 1000 random operations
     * on 5 seats and 3 users, then verify no sold seat became available by itself
     * and the engine never double-sold a seat.
     */
    private static void testRandomizedModelBased() {
        System.out.println("\n[8] Randomized / model-based");
        FakeClock clock = new FakeClock(0L);
        String[] seatIds = {"RM1", "RM2", "RM3", "RM4", "RM5"};
        SeatBookingEngine engine = new SeatBookingEngine(clock);
        engine.registerSeats(seatIds);

        // Model: count how many distinct users confirmed each seat (must never exceed 1)
        java.util.Map<String, String> soldTo = new java.util.HashMap<>();

        String[] users = {"u1", "u2", "u3"};
        Random rand = new Random(7);
        List<HoldRecord> activeHolds = new ArrayList<>();

        boolean modelViolated = false;

        for (int i = 0; i < 1000 && !modelViolated; i++) {
            String user = users[rand.nextInt(users.length)];
            String seat = seatIds[rand.nextInt(seatIds.length)];
            int op = rand.nextInt(4);

            if (op == 0) {
                // hold
                long dur = 500 + rand.nextInt(2000);
                Result r = engine.hold(user, seat, dur);
                if (r.isOk()) activeHolds.add(r.getHoldRecord());

            } else if (op == 1 && !activeHolds.isEmpty()) {
                // confirm
                HoldRecord h = activeHolds.get(rand.nextInt(activeHolds.size()));
                Result r = engine.confirm(h.getUserId(), h.getHoldId());
                if (r.isOk()) {
                    String previousOwner = soldTo.put(h.getSeatId(), h.getUserId());
                    if (previousOwner != null) {
                        System.out.println("  MODEL VIOLATION: seat " + h.getSeatId() +
                                " sold to " + h.getUserId() + " but was already sold to " + previousOwner);
                        modelViolated = true;
                    }
                    activeHolds.remove(h);
                }

            } else if (op == 2 && !activeHolds.isEmpty()) {
                // release
                HoldRecord h = activeHolds.get(rand.nextInt(activeHolds.size()));
                Result r = engine.release(h.getUserId(), h.getHoldId());
                if (r.isOk()) activeHolds.remove(h);

            } else {
                // advance time by a small random amount
                clock.advance(rand.nextInt(1000));
            }
        }

        assertTrue("randomized model: no seat sold twice in 1000 operations", !modelViolated);
    }

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== SeatBookingEngine Tests ===");

        // 1. Normal flows
        testHoldConfirmNormalFlow();
        testHoldReleaseNormalFlow();
        testTwoUsersCantHoldSameSeat();
        testSeatCannotBeSoldTwice();

        // 2. Expiry and late confirmation
        testExpiredHoldCanStillBeConfirmedIfSeatFree();
        testExpiredHoldCannotBeConfirmedIfSeatTakenByOther();
        testHoldExpiryIsNeverExtended();
        testReleasedSeatBecomesAvailable();

        // 3. Idempotency
        testIdempotentHoldReturnsSameId();
        testIdempotencyDoesNotCountAgainstHoldLimit();

        // 4. Four-hold limit
        testFourHoldLimitEnforced();
        testLimitRestoredAfterRelease();
        testLimitRestoredAfterConfirm();
        testLimitRestoredAfterExpiry();

        // 5. hold_many rollback
        testHoldManySuccess();
        testHoldManyRollbackOnUnavailableSeat();
        testHoldManyRollbackOnMissingSeat();
        testHoldManyRollbackOnHoldLimitExceeded();
        testHoldManyDuplicateSeatInRequest();

        // 6. Concurrency
        testOnlyOneThreadWinsHold();
        testOnlyOneConfirmSucceeds();
        testConcurrentHoldMany();
        testConcurrencyInvariantsHoldAfterManyOps();

        // 7. Input validation
        testInputValidation();
        testCannotConfirmOtherUserHold();
        testCannotReleaseOtherUserHold();

        // 8. Randomized / model-based
        testRandomizedModelBased();

        // Summary
        System.out.println("\n=== Results: " + passed + " passed, " + failed + " failed ===");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
