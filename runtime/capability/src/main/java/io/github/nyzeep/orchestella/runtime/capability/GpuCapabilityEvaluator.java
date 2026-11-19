package io.github.nyzeep.orchestella.runtime.capability;

/**
 * G-07 + DW-02: static VirGL GPU capability declaration for the GPU toggle
 * honesty (wizard / VM hardware page). Whether {@code libvirglrenderer.so} is
 * packaged is an adapter concern: the caller passes the pre-read load state
 * ({@code VirglJniBridge.isAvailable()} — same JVM as the engine process) and
 * this evaluator returns the product-level verdict.
 *
 * <p>DW-02 adds the QEMU device-model factor: {@code libvirglrenderer.so}
 * being packaged does not make the chain bootable — the packaged QEMU binary
 * may lack the {@code virtio-gpu-gl-device} device model entirely, in which
 * case an "available" verdict promised a launch that exits with code 1. That
 * factor is an adapter concern too: {@code runtime:capability} is a pure JVM
 * module and must not exec processes, so the caller injects the probe result
 * (engine-qemu's {@code QemuDeviceModelProbe}, cached process-wide, run off
 * the main thread). The pre-launch probe can only be conservative:
 * "not yet probed" must be passed as {@code false} — an honest
 * "unproven" never promises a chain that may not boot.
 *
 * <p>Scope note: this declaration only answers pre-launch facts (driver
 * packaged, device model present). The per-launch decision (ACTIVE / explicit
 * FALLBACK / DISABLED) stays with the engine-qemu single source
 * ({@code GpuAccelerationStatus}, carried on the session); the UI combines the
 * two — session status when a session is running, this static verdict when
 * not. The static state can only ever know two fallback reasons (driver
 * missing, device model unsupported), so {@link #fallbackReason()} emits the
 * grep-able tokens aligned with engine-qemu's {@code GpuFallbackReason} names;
 * the literals are pinned by {@code GpuCapabilityEvaluatorTest}.
 */
public final class GpuCapabilityEvaluator {
    /** Aligned with engine-qemu {@code GpuFallbackReason.NATIVE_UNAVAILABLE}. */
    public static final String FALLBACK_REASON_NATIVE_UNAVAILABLE = "NATIVE_UNAVAILABLE";
    /** Aligned with engine-qemu {@code GpuFallbackReason.NONE} (active or flag off). */
    public static final String FALLBACK_REASON_NONE = "NONE";
    /** Aligned with engine-qemu {@code GpuFallbackReason.QEMU_DEVICE_MODEL_UNSUPPORTED}. */
    public static final String FALLBACK_REASON_QEMU_DEVICE_MODEL_UNSUPPORTED =
            "QEMU_DEVICE_MODEL_UNSUPPORTED";

    private GpuCapabilityEvaluator() {}

    /**
     * Legacy two-factor-free overload: identical to
     * {@link #evaluate(boolean, boolean)} with the QEMU device model assumed
     * supported. Kept for existing callers/tests; product UI must pass the
     * real probe outcome via {@link #evaluate(boolean, boolean)}.
     *
     * @param virglNativeAvailable pre-read native load state
     *        ({@code VirglJniBridge.isAvailable()}); detection stays at the caller
     */
    public static Assessment evaluate(boolean virglNativeAvailable) {
        return new Assessment(virglNativeAvailable, true);
    }

    /**
     * @param virglNativeAvailable pre-read native load state
     *        ({@code VirglJniBridge.isAvailable()}); detection stays at the caller
     * @param qemuDeviceModelSupported pre-read QEMU device-model probe outcome
     *        ({@code QemuDeviceModelProbe}; engine-qemu owns the exec + cache).
     *        A pending/failed probe must arrive as {@code false} (fail-closed).
     */
    public static Assessment evaluate(boolean virglNativeAvailable,
            boolean qemuDeviceModelSupported) {
        return new Assessment(virglNativeAvailable, qemuDeviceModelSupported);
    }

    /** Product-facing result consumed by the GPU toggle UI. */
    public static final class Assessment {
        private final boolean virglNativeAvailable;
        private final boolean qemuDeviceModelSupported;

        private Assessment(boolean virglNativeAvailable, boolean qemuDeviceModelSupported) {
            this.virglNativeAvailable = virglNativeAvailable;
            this.qemuDeviceModelSupported = qemuDeviceModelSupported;
        }

        /** Whether the VirGL native driver is packaged and loadable in this process. */
        public boolean virglNativeAvailable() {
            return virglNativeAvailable;
        }

        /** Whether the packaged QEMU binary supports {@code virtio-gpu-gl-device}. */
        public boolean qemuDeviceModelSupported() {
            return qemuDeviceModelSupported;
        }

        /**
         * The real-chain verdict: both the VirGL driver is packaged AND the
         * QEMU device model exists. Only then may UI copy promise that
         * enabling the toggle can produce a bootable GL device.
         */
        public boolean available() {
            return virglNativeAvailable && qemuDeviceModelSupported;
        }

        /**
         * {@link #FALLBACK_REASON_NONE} when the full chain is available;
         * {@link #FALLBACK_REASON_NATIVE_UNAVAILABLE} when the driver is
         * missing (the more fundamental gap wins the reason slot);
         * {@link #FALLBACK_REASON_QEMU_DEVICE_MODEL_UNSUPPORTED} when only the
         * device model is missing. Init failures are per-launch facts and
         * live on the session status instead.
         */
        public String fallbackReason() {
            if (!virglNativeAvailable) {
                return FALLBACK_REASON_NATIVE_UNAVAILABLE;
            }
            return qemuDeviceModelSupported
                    ? FALLBACK_REASON_NONE
                    : FALLBACK_REASON_QEMU_DEVICE_MODEL_UNSUPPORTED;
        }
    }
}
