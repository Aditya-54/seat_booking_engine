/**
 * Production clock that delegates to System.currentTimeMillis().
 */
public class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
