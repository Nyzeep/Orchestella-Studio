package io.github.nyzeep.orchestella.runtime.contract;

import java.util.EnumSet;

/**
 * Tests capability matrix expansion for audio output, clipboard sync, and pointer capture.
 */
public final class FullLinuxContractAudioCapabilityTest {
    public static void main(String[] arguments) {
        exposesAudioClipboardAndPointerCapabilities();
        exposesTransparentBadgesForSoftwareVirtualization();
        exposesTransparentBadgesForHardwareVirtualization();
        exposesBadgesWhenCapabilitiesAbsent();
        validatesConsistencyInFullMatrixPresets();
        refusesSoftwareClaimingHardwareAccelerationWithAudio();
        System.out.println("FULL_LINUX_CONTRACT_AUDIO_CAPABILITY_TEST_OK");
    }

    private static void exposesAudioClipboardAndPointerCapabilities() {
        FullLinuxContract.CapabilityMatrix matrix =
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationWithPeripherals();

        assertTrue(matrix.supports(FullLinuxContract.Capability.AUDIO_OUTPUT), "supports AUDIO_OUTPUT");
        assertTrue(matrix.supports(FullLinuxContract.Capability.CLIPBOARD_SYNC), "supports CLIPBOARD_SYNC");
        assertTrue(matrix.supports(FullLinuxContract.Capability.POINTER_CAPTURE), "supports POINTER_CAPTURE");
        assertTrue(matrix.supportsAudio(), "supportsAudio helper");
        assertTrue(matrix.supportsClipboard(), "supportsClipboard helper");
        assertTrue(matrix.supportsPointerCapture(), "supportsPointerCapture helper");
        assertTrue(matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB), "supports RFB graphics");
        assertTrue(matrix.supports(FullLinuxContract.Capability.TERMINAL), "supports terminal");
        assertTrue(matrix.supports(FullLinuxContract.Capability.BRIDGE_HANDSHAKE), "supports bridge handshake");
    }

    private static void exposesTransparentBadgesForSoftwareVirtualization() {
        FullLinuxContract.CapabilityMatrix matrix =
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationWithPeripherals();

        assertEquals("低延迟 PCM 音频（本地套接字 48kHz）", matrix.audioBadge(), "software audio badge");
        assertEquals("剪贴板双向同步（本地通道）", matrix.clipboardBadge(), "software clipboard badge");
        assertEquals("相对指针捕获（无边界）", matrix.pointerCaptureBadge(), "pointer capture badge");
        assertEquals("RFB 显示（本地套接字）", matrix.graphicsBadge(), "software graphics badge");
        assertEquals("完整 Linux · 软件模拟（较慢）", matrix.performanceBadge(), "software performance badge");
    }

    private static void exposesTransparentBadgesForHardwareVirtualization() {
        FullLinuxContract.CapabilityMatrix matrix =
                FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithPeripherals();

        assertTrue(matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION), "hardware acceleration");
        assertEquals("低延迟 PCM 音频（vsock 硬件加速 48kHz）", matrix.audioBadge(), "hardware audio badge");
        assertEquals("剪贴板双向同步（vsock 硬件通道）", matrix.clipboardBadge(), "hardware clipboard badge");
        assertEquals("相对指针捕获（无边界）", matrix.pointerCaptureBadge(), "pointer capture badge");
        assertEquals("RFB 显示（vsock CPU 拷贝）", matrix.graphicsBadge(), "hardware graphics badge");
        assertEquals("完整 Linux · 硬件加速", matrix.performanceBadge(), "hardware performance badge");
    }

    private static void exposesBadgesWhenCapabilitiesAbsent() {
        FullLinuxContract.CapabilityMatrix baseline =
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline();

        assertFalse(baseline.supportsAudio(), "baseline no audio");
        assertFalse(baseline.supportsClipboard(), "baseline no clipboard");
        assertFalse(baseline.supportsPointerCapture(), "baseline no pointer capture");
        assertEquals("无音频输出", baseline.audioBadge(), "no audio badge");
        assertEquals("无剪贴板同步", baseline.clipboardBadge(), "no clipboard badge");
        assertEquals("无指针捕获", baseline.pointerCaptureBadge(), "no pointer capture badge");
    }

    private static void validatesConsistencyInFullMatrixPresets() {
        FullLinuxContract.CapabilityMatrix swFull =
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationWithPeripherals();
        assertEquals(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION, swFull.executionStrategy(), "software strategy");

        FullLinuxContract.CapabilityMatrix hwFull =
                FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithPeripherals();
        assertEquals(FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION, hwFull.executionStrategy(), "hardware strategy");
    }

    private static void refusesSoftwareClaimingHardwareAccelerationWithAudio() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FullLinuxContract.CapabilityMatrix(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        EnumSet.of(
                                FullLinuxContract.Capability.TERMINAL,
                                FullLinuxContract.Capability.BRIDGE_HANDSHAKE,
                                FullLinuxContract.Capability.AUDIO_OUTPUT,
                                FullLinuxContract.Capability.HARDWARE_ACCELERATION)),
                "software virtualization cannot claim hardware acceleration even with audio");
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

    private static <T extends Throwable> void assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
