package io.github.nyzeep.orchestella;

/**
 * Observation-only seam for the real launch trigger points (ticket 27).
 *
 * <p>Implementations attach to existing routes — strategy launchers, the
 * runtime supervisor, the bridge serving loop and the session stop path — and
 * only observe: they must never throw, never block for long, never mutate
 * control flow, and never become a second owner of any resource. Default
 * methods keep every hook optional so a route without diagnostics keeps its
 * exact previous behavior.
 */
public interface LaunchStageObserver {
    /** Records one gate observed on the owner route. */
    default void onGate(ProductionStageRecorder.Stage stage) {}

    /**
     * Records one gate observed on the authenticated single-reader route (the
     * session's cached bridge client path). Wiring an owner-only gate here is a
     * route violation the recorder will retire loudly.
     */
    default void onGateAuthenticated(ProductionStageRecorder.Stage stage) {}

    /** Records that this strategy attempt was abandoned for the other strategy. */
    default void onFallback(ProductionStageRecorder.Failure failure) {}

    /** Records a real failure as the trace's terminal reason. */
    default void onFailure(ProductionStageRecorder.Failure failure) {}

    /** Records that this launch was cancelled (superseded by a newer generation). */
    default void onCancel() {}

    /** Records one independent stop fact (intent, owner exit, resource release). */
    default void onStopFact(ProductionStageRecorder.StopFact fact) {}

    /** Closes the trace with its honest end-of-record. */
    default void onFinish() {}
}
