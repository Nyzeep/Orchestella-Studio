package io.github.nyzeep.orchestella.runtime.capability;

/**
 * G-07: static VirGL GPU capability declaration for the GPU toggle honesty
 * (wizard / VM hardware page). Whether {@code libvirglrenderer.so} is packaged
 * is an adapter concern: the caller passes the pre-read load state
 * ({@code VirglJniBridge.isAvailable()} — same JVM as the engine process) and
 * this evaluator returns the product-level verdict.
 *
 * <p>Scope note: this declaration only answers "is the native driver packaged
 * at all". The per-launch decision (ACTIVE / explicit FALLBACK / DISABLED)
 * stays with the engine-qemu single source
 * ({@code GpuAccelerationStatus}, carried on the session); the UI combines the
 * two — session status when a session is running, this static verdict when not.
 * The static state can only ever know one fallback reason (driver missing), so
 * {@link #fallbackReason()} emits the grep-able token aligned with
 * engine-qemu's {@code GpuFallbackReason} names; the literal is pinned by
 * {@code GpuCapabilityEvaluatorTest}.
 */
public final class GpuCapabilityEvaluator {
    /** Aligned with engine-qemu {@code GpuFallbackReason.NATIVE_UNAVAILABLE}. */
    public static final String FALLBACK_REASON_NATIVE_UNAVAILABLE = "NATIVE_UNAVAILABLE";
    /** Aligned with engine-qemu {@code GpuFallbackReason.NONE} (active or flag off). */
    public static final String FALLBACK_REASON_NONE = "NONE";

    private GpuCapabilityEvaluator() {}

    /**
     * @param virglNativeAvailable pre-read native load state
     *        ({@code VirglJniBridge.isAvailable()}); detection stays at the caller
     */
    public static Assessment evaluate(boolean virglNativeAvailable) {
        return new Assessment(virglNativeAvailable);
    }

    /** Product-facing result consumed by the GPU toggle UI. */
    public static final class Assessment {
        private final boolean virglNativeAvailable;

        private Assessment(boolean virglNativeAvailable) {
            this.virglNativeAvailable = virglNativeAvailable;
        }

        /** Whether the VirGL native driver is packaged and loadable in this process. */
        public boolean virglNativeAvailable() {
            return virglNativeAvailable;
        }

        /**
         * {@link #FALLBACK_REASON_NONE} when the driver is available; otherwise
         * {@link #FALLBACK_REASON_NATIVE_UNAVAILABLE} — the only fallback reason
         * knowable before a launch (init failures are per-launch facts and live
         * on the session status instead).
         */
        public String fallbackReason() {
            return virglNativeAvailable
                    ? FALLBACK_REASON_NONE
                    : FALLBACK_REASON_NATIVE_UNAVAILABLE;
        }
    }
}
