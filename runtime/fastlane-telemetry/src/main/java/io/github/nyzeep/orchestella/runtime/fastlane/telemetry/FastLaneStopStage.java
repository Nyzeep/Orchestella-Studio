package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

/** The last stage reached by an unconfirmed stop. */
public enum FastLaneStopStage {
    SIGNAL_SENT,
    PROCESS_EXIT_WAIT,
    VSOCK_CLOSE,
    GATEWAY_EXIT,
    UNKNOWN
}
