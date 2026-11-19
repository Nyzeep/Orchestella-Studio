package io.github.nyzeep.orchestella.runtime.capability;

/** G-07 + DW-02: the static VirGL capability declaration the GPU toggle UI reads. */
public final class GpuCapabilityEvaluatorTest {
    public static void main(String[] arguments) {
        declaresNativeAvailableWhenTheDriverIsPackaged();
        declaresNativeUnavailableWithTheStaticFallbackReason();
        keepsTheReasonTokenAlignedWithEngineQemuGpuFallbackReason();
        deviceModelUnsupportedDowngradesTheVerdictWithItsOwnReason();
        deviceModelSupportedKeepsTheOriginalAvailableVerdict();
        missingDriverStillWinsTheReasonSlotOverTheDeviceModel();
        availableVerdictRequiresBothFactors();
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
        assertEquals(
                "QEMU_DEVICE_MODEL_UNSUPPORTED",
                GpuCapabilityEvaluator.FALLBACK_REASON_QEMU_DEVICE_MODEL_UNSUPPORTED,
                "device-model token must stay aligned with engine-qemu GpuFallbackReason (DW-01)");
    }

    /**
     * DW-02: a packaged driver alone no longer promises the GL chain — the
     * packaged QEMU may lack the virtio-gpu-gl-device model, which made the
     * old copy promise a launch that exits with code 1.
     */
    private static void deviceModelUnsupportedDowngradesTheVerdictWithItsOwnReason() {
        GpuCapabilityEvaluator.Assessment assessment = GpuCapabilityEvaluator.evaluate(true, false);

        assertTrue(assessment.virglNativeAvailable(), "the driver is still packaged");
        assertFalse(assessment.qemuDeviceModelSupported(), "the device model is missing");
        assertFalse(assessment.available(), "the full GL chain must not be declared available");
        assertEquals(
                GpuCapabilityEvaluator.FALLBACK_REASON_QEMU_DEVICE_MODEL_UNSUPPORTED,
                assessment.fallbackReason(),
                "a missing device model carries its own grep-able fallback reason");
    }

    private static void deviceModelSupportedKeepsTheOriginalAvailableVerdict() {
        GpuCapabilityEvaluator.Assessment assessment = GpuCapabilityEvaluator.evaluate(true, true);

        assertTrue(assessment.available(), "driver + device model is the real chain verdict");
        assertEquals(
                GpuCapabilityEvaluator.FALLBACK_REASON_NONE,
                assessment.fallbackReason(),
                "a fully available chain has no fallback reason");
    }

    private static void missingDriverStillWinsTheReasonSlotOverTheDeviceModel() {
        GpuCapabilityEvaluator.Assessment assessment = GpuCapabilityEvaluator.evaluate(false, false);

        assertFalse(assessment.available(), "neither factor is satisfied");
        assertEquals(
                GpuCapabilityEvaluator.FALLBACK_REASON_NATIVE_UNAVAILABLE,
                assessment.fallbackReason(),
                "the missing driver is the more fundamental gap and keeps the reason slot");
    }

    private static void availableVerdictRequiresBothFactors() {
        GpuCapabilityEvaluator.Assessment driverOnly = GpuCapabilityEvaluator.evaluate(true, false);
        assertFalse(driverOnly.available(), "driver without device model is not available");

        GpuCapabilityEvaluator.Assessment neither = GpuCapabilityEvaluator.evaluate(false, true);
        assertFalse(neither.available(), "device model without driver is not available");
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
