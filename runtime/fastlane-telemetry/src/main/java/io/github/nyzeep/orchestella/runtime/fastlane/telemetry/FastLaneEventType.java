package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

/** The ten P10 stage events, in launch-to-stop order. */
public enum FastLaneEventType {
    FASTLANE_REQUESTED,
    VM_CREATED,
    PAYLOAD_READY,
    TERMINAL_READY,
    PROXY_READY,
    EXPORT_READY,
    DISTCC_READY,
    STOP_REQUESTED,
    STOP_CONFIRMED,
    STOP_UNCONFIRMED;

    /** Stop events are retained preferentially when the bounded queue overflows. */
    public boolean isStop() {
        return this == STOP_REQUESTED || this == STOP_CONFIRMED || this == STOP_UNCONFIRMED;
    }
}
