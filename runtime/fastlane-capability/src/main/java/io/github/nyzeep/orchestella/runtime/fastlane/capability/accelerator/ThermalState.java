package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

/** Coarse thermal budget observed from the platform. Unknown never enables. */
public enum ThermalState {
    NOMINAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    UNKNOWN
}
