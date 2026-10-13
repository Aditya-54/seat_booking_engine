/**
 * Fake clock for tests. Time only advances when advance() is called explicitly.
 * Tests never need Thread.sleep().
 */
public class FakeClock implements Clock {
    private long currentTimeMillis;

    public FakeClock(long startTimeMillis) {
        this.currentTimeMillis = startTimeMillis;
    }

    /** Advance clock by the given number of milliseconds. */
    public void advance(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("Cannot advance clock backwards by " + millis + " ms");
        }
        this.currentTimeMillis += millis;
    }

    @Override
    public long nowMillis() {
        return currentTimeMillis;
    }
}
