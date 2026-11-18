package io.github.nyzeep.orchestella.runtime.capability;

/** G-07: the static VirGL capability declaration the GPU toggle UI reads. */
public final class GpuCapabilityEvaluatorTest {
    public static void main(String[] arguments) {
        declaresNativeAvailableWhenTheDriverIsPackaged();
        declaresNativeUnavailableWithTheStaticFallbackReason();
        keepsTheReasonTokenAlignedWithEngineQemuGpuFallbackReason();
    }

    private static void declaresNativeAvailableWhenTheDriverIsPackaged() {
        GpuCapabilityEvaluator.Assessment assessment = GpuCapabilityEvaluator.evaluate(true);

        assertTrue(assessment.virglNativeAvailable(), "packaged driver is declared available");
        assertEquals(
                GpuCapabilityEvaluator.FALLBACK_REASON_NONE,
                assessment.fallbackReason(),
                "an available driver has no fallback reason");
    }

    private static void declaresNativeUnavailableWithTheStaticFallbackReason() {
        GpuCapabilityEvaluator.Assessment assessment = GpuCapabilityEvaluator.evaluate(false);

        assertFalse(assessment.virglNativeAvailable(), "missing driver must not be declared available");
        assertEquals(
                GpuCapabilityEvaluator.FALLBACK_REASON_NATIVE_UNAVAILABLE,
                assessment.fallbackReason(),
                "the only pre-launch knowable fallback reason is the missing driver");
    }

    /**
     * The static reason token doubles as the grep-able key that engine-qemu's
     * {@code GpuFallbackReason.NATIVE_UNAVAILABLE} emits at launch time; renaming
     * either side would silently desynchronize the UI copy from the structured log.
     */
    private static void keepsTheReasonTokenAlignedWithEngineQemuGpuFallbackReason() {
        assertEquals(
                "NATIVE_UNAVAILABLE",
                GpuCapabilityEvaluator.FALLBACK_REASON_NATIVE_UNAVAILABLE,
                "reason token must stay aligned with engine-qemu GpuFallbackReason");
        assertEquals(
                "NONE",
                GpuCapabilityEvaluator.FALLBACK_REASON_NONE,
                "no-fallback token must stay aligned with engine-qemu GpuFallbackReason");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
