/**
 * A hold record created when a user reserves a seat.
 *
 * Instances are immutable once created. The lifecycle status of a hold is
 * stored separately in the engine (via SeatInfo) so concurrent updates do
 * not require changing this record.
 */
public class HoldRecord {

    /** Possible lifecycle states of a hold. */
    public enum Status {
        /** Hold is active and has not yet been confirmed or released. */
        ACTIVE,
        /** Hold was confirmed: the seat is now sold to this user. */
        CONFIRMED,
        /** Hold was explicitly released by the user or the engine. */
        RELEASED,
        /**
         * Another user acquired the seat while this hold was active or expired.
         * The original holder can no longer confirm this hold.
         */
        SUPERSEDED
    }

    private final String holdId;
    private final String userId;
    private final String seatId;
    private final long createdAtMillis;
    private final long expiresAtMillis;

    /**
     * Status is mutable only by the SeatBookingEngine, always under the engine lock.
     * Declared volatile for safe read without holding the lock in seat_status queries.
     */
    private volatile Status status;

    public HoldRecord(String holdId, String userId, String seatId,
                      long createdAtMillis, long expiresAtMillis) {
        this.holdId = holdId;
        this.userId = userId;
        this.seatId = seatId;
        this.createdAtMillis = createdAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.status = Status.ACTIVE;
    }

    public String getHoldId()       { return holdId; }
    public String getUserId()       { return userId; }
    public String getSeatId()       { return seatId; }
    public long getCreatedAtMillis(){ return createdAtMillis; }
    public long getExpiresAtMillis(){ return expiresAtMillis; }
    public Status getStatus()       { return status; }

    /** Called only by SeatBookingEngine under the engine lock. */
    void setStatus(Status newStatus) {
        this.status = newStatus;
    }

    /**
     * Returns true when the hold's original expiry has passed at the given time.
     * An expired hold may still be confirmed if the seat is still available (R3).
     */
    public boolean isExpiredAt(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    @Override
    public String toString() {
        return "HoldRecord{holdId=" + holdId + ", userId=" + userId +
               ", seatId=" + seatId + ", status=" + status +
               ", expiresAt=" + expiresAtMillis + "}";
    }
}
