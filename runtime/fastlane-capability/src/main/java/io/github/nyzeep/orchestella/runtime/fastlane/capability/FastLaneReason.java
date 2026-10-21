package io.github.nyzeep.orchestella.runtime.fastlane.capability;

/** Typed reason attached to every routing decision. */
public enum FastLaneReason {
    FAST_LANE_AVAILABLE,
    REQUIRES_FULL_LINUX,
    NO_AVF,
    HIDDEN_API_BLOCKED,
    PERMISSION_DENIED,
    PROTECTED_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
    PROXY_UNAVAILABLE,
    TOOLBOX_UNAVAILABLE,
    DISTCC_UNAVAILABLE,
    UNKNOWN_DEVICE,
    UNKNOWN_WORKLOAD
}
