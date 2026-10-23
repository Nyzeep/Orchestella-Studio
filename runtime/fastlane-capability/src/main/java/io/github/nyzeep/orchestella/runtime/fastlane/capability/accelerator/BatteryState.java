package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

/** Coarse battery budget observed from the platform. Unknown never enables. */
public enum BatteryState {
    OK,
    LOW,
    CRITICAL,
    CHARGING,
    UNKNOWN
}
