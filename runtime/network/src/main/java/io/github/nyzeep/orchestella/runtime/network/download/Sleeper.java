package io.github.nyzeep.orchestella.runtime.network.download;

/**
 * Strategy interface for thread pausing, enabling deterministic, zero-delay unit testing.
 */
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;

    Sleeper SYSTEM = Thread::sleep;
    Sleeper NO_OP = millis -> {};
}
