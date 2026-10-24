package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

/**
 * Fixed, user-visible reason codes for the accelerator decision.
 *
 * <p>The original six codes stay unchanged. The PVM codes come from the
 * compile-service spec (§5.3); {@code ACCEL_UNAVAILABLE_NO_NON_PROTECTED} is
 * produced only by the {@code CUSTOM_VM} branch of the ordered selection, and
 * {@code ACCEL_UNAVAILABLE_NO_BACKEND} is the terminal verdict when no backend
 * was even a candidate.
 */
public enum AcceleratorReasonCode {
    ACCEL_AVAILABLE,
    ACCEL_UNAVAILABLE_NO_NON_PROTECTED,
    ACCEL_IMAGE_MISSING,
    ACCEL_VSOCK_UNAVAILABLE,
    COMPILER_MISMATCH,
    THERMAL_BUDGET,
    /** No backend was a candidate; nothing was evaluated. */
    ACCEL_UNAVAILABLE_NO_BACKEND,
    /** The protected VM bit the payload backend needs is missing. */
    PAYLOAD_UNAVAILABLE_NO_PROTECTED,
    /** The payload compiler .so or its manifest is missing. */
    PAYLOAD_IMAGE_MISSING,
    /** Payload compiler version or target triple does not match the guest. */
    PAYLOAD_COMPILER_MISMATCH,
    /** The payload .so failed to load or initialize. */
    PAYLOAD_LOAD_FAILED,
    /** The guest argv hit the payload's rejection table. */
    ARGV_REJECTED,
    /** A single job exceeded the input or output limit. */
    JOB_TOO_LARGE,
    /** A single job exceeded its wall-clock or CPU-time limit. */
    JOB_TIMEOUT
}
