package io.github.nyzeep.orchestella.runtime.instance.duallane;

/**
 * One running lane. Implementations adapt an existing owner stop; they never
 * add a second stop implementation.
 */
public interface LaneRuntime {
    LaneId laneId();

    /** True while the backing runtime may still be alive. */
    boolean isAlive();

    /** True once the lane can accept work (payload ready for ACCEL). */
    boolean isReady();

    /** Bounded, idempotent stop delegated to the lane's existing owner. */
    LaneStopOutcome stop(long graceMs);
}
