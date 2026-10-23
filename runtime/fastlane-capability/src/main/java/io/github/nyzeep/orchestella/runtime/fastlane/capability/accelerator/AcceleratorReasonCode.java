package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

/** Fixed, user-visible reason codes for the accelerator decision. */
public enum AcceleratorReasonCode {
    ACCEL_AVAILABLE,
    ACCEL_UNAVAILABLE_NO_NON_PROTECTED,
    ACCEL_IMAGE_MISSING,
    ACCEL_VSOCK_UNAVAILABLE,
    COMPILER_MISMATCH,
    THERMAL_BUDGET
}
