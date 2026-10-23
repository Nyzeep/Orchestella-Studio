package io.github.nyzeep.orchestella.runtime.instance.duallane;

/** Monotonic clock plus a bounded sleep, injectable for deterministic tests. */
public interface LaneClock {
    long nanoTime();

    void sleep(long millis) throws InterruptedException;

    static LaneClock system() {
        return new LaneClock() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void sleep(long millis) throws InterruptedException {
                Thread.sleep(Math.max(0L, millis));
            }
        };
    }
}
