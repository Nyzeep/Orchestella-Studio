package io.github.nyzeep.orchestella.runtime.fastlane.capability;

/** The runtime lane selected by {@link FastLaneRouter}. */
public enum Lane {
    /** Existing QEMU-backed Full Linux Runtime. */
    FULL,
    /** Protected hardware-vCPU workspace; not Full Linux. */
    FAST,
    /** Neither lane is available on this device. */
    UNSUPPORTED
}
