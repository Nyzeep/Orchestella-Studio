package io.github.nyzeep.orchestella.runtime.instance.duallane;

/**
 * Issues one lane start. Implementations must return without waiting for
 * readiness so the coordinator can issue both starts before any wait.
 */
@FunctionalInterface
public interface LaneStarter {
    LaneRuntime start() throws LaneStartException;
}
