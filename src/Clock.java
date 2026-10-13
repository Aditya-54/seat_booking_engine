/**
 * Injectable clock interface so tests can control time without Thread.sleep().
 */
public interface Clock {
    /** Returns the current time in milliseconds since the epoch. */
    long nowMillis();
}
