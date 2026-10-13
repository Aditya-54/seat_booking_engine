/**
 * Represents the outcome of a booking operation.
 *
 * Every engine method returns a Result so callers can inspect success/failure
 * and the associated data without catching exceptions for normal flow control.
 */
public class Result {

    public enum Code {
        OK,
        SEAT_NOT_FOUND,
        SEAT_UNAVAILABLE,       // seat is already held or sold
        HOLD_NOT_FOUND,         // hold ID does not exist
        HOLD_BELONGS_TO_OTHER,  // the hold was created by a different user
        HOLD_ALREADY_CONFIRMED, // trying to confirm an already confirmed hold
        HOLD_SUPERSEDED,        // another user acquired the seat first
        HOLD_ALREADY_RELEASED,  // trying to operate on a released hold
        TOO_MANY_HOLDS,         // user already has MAX_HOLDS_PER_USER active holds
        INVALID_ARGUMENT,       // null/blank input or non-positive duration
        BATCH_FAILED            // hold_many: at least one seat could not be held
    }

    private final Code code;
    private final String message;
    private final Object data;   // HoldRecord, SeatInfo, or List<HoldRecord>

    private Result(Code code, String message, Object data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static Result ok(String message, Object data) {
        return new Result(Code.OK, message, data);
    }

    public static Result error(Code code, String message) {
        return new Result(code, message, null);
    }

    public boolean isOk()    { return code == Code.OK; }
    public Code getCode()    { return code; }
    public String getMessage()  { return message; }
    public Object getData()  { return data; }

    /** Convenience cast. Returns null if data is not a HoldRecord. */
    public HoldRecord getHoldRecord() {
        return (data instanceof HoldRecord h) ? h : null;
    }

    /** Convenience cast. Returns null if data is not a SeatInfo. */
    public SeatInfo getSeatInfo() {
        return (data instanceof SeatInfo s) ? s : null;
    }

    /** Convenience cast for hold_many results. */
    @SuppressWarnings("unchecked")
    public java.util.List<HoldRecord> getHoldRecords() {
        return (data instanceof java.util.List<?> list) ? (java.util.List<HoldRecord>) list : null;
    }

    @Override
    public String toString() {
        return "Result{code=" + code + ", message=" + message +
               ", data=" + data + "}";
    }
}
