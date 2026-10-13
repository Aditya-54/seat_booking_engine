/**
 * Tracks the current ownership state of a single seat.
 *
 * The engine holds a SeatInfo per seat. All reads and writes happen under
 * the single engine-wide lock, so no additional synchronization is needed here.
 */
public class SeatInfo {

    public enum SeatStatus {
        /** Seat is free and can be held or sold. */
        AVAILABLE,
        /** Seat is currently held by a user (hold may be active or expired). */
        HELD,
        /** Seat has been sold to a user. */
        SOLD
    }

    private final String seatId;
    private SeatStatus status;

    /**
     * The hold record currently associated with this seat, or null when the
     * seat is AVAILABLE or SOLD. After a sale the hold field is cleared.
     */
    private HoldRecord currentHold;

    /** The user who bought the seat, or null if not yet sold. */
    private String soldToUserId;

    public SeatInfo(String seatId) {
        this.seatId = seatId;
        this.status = SeatStatus.AVAILABLE;
    }

    public String getSeatId()           { return seatId; }
    public SeatStatus getStatus()       { return status; }
    public HoldRecord getCurrentHold()  { return currentHold; }
    public String getSoldToUserId()     { return soldToUserId; }

    /** Called by the engine when a user successfully places a hold. */
    void applyHold(HoldRecord hold) {
        this.status = SeatStatus.HELD;
        this.currentHold = hold;
        this.soldToUserId = null;
    }

    /** Called by the engine when a hold is confirmed and the seat is sold. */
    void applySale(String userId) {
        this.status = SeatStatus.SOLD;
        this.soldToUserId = userId;
        this.currentHold = null;
    }

    /** Called by the engine when a hold is released and the seat becomes free again. */
    void applyRelease() {
        this.status = SeatStatus.AVAILABLE;
        this.currentHold = null;
        this.soldToUserId = null;
    }

    @Override
    public String toString() {
        return "SeatInfo{seatId=" + seatId + ", status=" + status +
               ", holdId=" + (currentHold != null ? currentHold.getHoldId() : "none") +
               ", soldTo=" + soldToUserId + "}";
    }
}
