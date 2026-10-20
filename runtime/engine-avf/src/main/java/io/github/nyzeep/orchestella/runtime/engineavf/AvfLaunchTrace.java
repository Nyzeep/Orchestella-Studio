package io.github.nyzeep.orchestella.runtime.engineavf;

/**
 * Ordered lifecycle stages of one AVF launch. The engine records the stages it
 * observes; the App maps them onto its own {@code LaunchTimeline}. Implementations
 * must be cheap and non-blocking — this is diagnostic telemetry, never a
 * lifecycle owner — and must not receive tokens, passwords or user content.
 */
@FunctionalInterface
public interface AvfLaunchTrace {
    void mark(Stage stage);

    /** A trace that records nothing (tests and callers without telemetry). */
    AvfLaunchTrace NONE = stage -> { };

    enum Stage {
        LAUNCH_REQUESTED,
        VM_CREATED,
        RUN_RETURNED,
        STOP_REQUESTED,
        STOP_CONFIRMED,
        STOP_UNCONFIRMED,
        EXIT_OBSERVED
    }
}
