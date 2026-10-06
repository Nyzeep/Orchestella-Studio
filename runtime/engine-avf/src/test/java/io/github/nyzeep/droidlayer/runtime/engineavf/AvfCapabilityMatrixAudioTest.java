package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import java.util.Objects;

/**
 * Validates the AVF capability matrix and transparent badge declarations
 * conforming to ADR-0002, ADR-0018, and Ticket 48.
 */
public final class AvfCapabilityMatrixAudioTest {

    public static void main(String[] arguments) {
        avfVirtualMachineEngineDeclaresCompletePeripheralCapabilities();
        avfBadgeStringsMatchContractSpecifications();
        avfAudioChannelReflectsHardwareVirtualizationParity();
        System.out.println(
                "AVF_CAPABILITY_MATRIX_AUDIO_TEST_OK matrix_support=1 badges=1 audio_channel_parity=1");
    }

    private static void avfVirtualMachineEngineDeclaresCompletePeripheralCapabilities() {
        FullLinuxContract.CapabilityMatrix matrix = AvfVirtualMachineEngine.capabilityMatrix();
        assertTrue(matrix != null, "matrix must not be null");
        assertEquals(
                FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                matrix.executionStrategy(),
                "execution strategy must be HARDWARE_VIRTUALIZATION");

        assertTrue(matrix.supports(FullLinuxContract.Capability.TERMINAL), "supports TERMINAL");
        assertTrue(matrix.supports(FullLinuxContract.Capability.BRIDGE_HANDSHAKE), "supports BRIDGE_HANDSHAKE");
        assertTrue(matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION), "supports HARDWARE_ACCELERATION");
        assertTrue(matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND), "supports GRAPHICS_WAYLAND");
        assertTrue(matrix.supports(FullLinuxContract.Capability.AUDIO_OUTPUT), "supports AUDIO_OUTPUT");
        assertTrue(matrix.supports(FullLinuxContract.Capability.CLIPBOARD_SYNC), "supports CLIPBOARD_SYNC");
        assertTrue(matrix.supports(FullLinuxContract.Capability.POINTER_CAPTURE), "supports POINTER_CAPTURE");

        assertTrue(matrix.supportsAudio(), "supportsAudio helper must be true");
        assertTrue(matrix.supportsClipboard(), "supportsClipboard helper must be true");
        assertTrue(matrix.supportsPointerCapture(), "supportsPointerCapture helper must be true");
    }

    private static void avfBadgeStringsMatchContractSpecifications() {
        FullLinuxContract.CapabilityMatrix matrix = AvfVirtualMachineEngine.capabilityMatrix();

        assertEquals(
                "低延迟 PCM 音频（vsock 硬件加速 48kHz）",
                matrix.audioBadge(),
                "audio badge reflects vsock hardware accelerated 48kHz PCM");

        assertEquals(
                "剪贴板双向同步（vsock 硬件通道）",
                matrix.clipboardBadge(),
                "clipboard badge reflects vsock hardware channel sync");

        assertEquals(
                "相对指针捕获（无边界）",
                matrix.pointerCaptureBadge(),
                "pointer capture badge reflects borderless relative capture");

        assertEquals(
                "Wayland 原生通道（vsock 硬件加速）",
                matrix.graphicsBadge(),
                "graphics badge reflects vsock Wayland hardware channel");

        assertEquals(
                "完整 Linux · 硬件加速",
                matrix.performanceBadge(),
                "performance badge reflects hardware acceleration");
    }

    private static void avfAudioChannelReflectsHardwareVirtualizationParity() {
        FullLinuxContract.CapabilityMatrix matrix = AvfAudioChannel.capabilityMatrix();
        assertTrue(matrix != null, "AvfAudioChannel capability matrix must not be null");
        assertTrue(matrix.supports(FullLinuxContract.Capability.AUDIO_OUTPUT), "AvfAudioChannel supports AUDIO_OUTPUT");
        assertEquals(
                FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                matrix.executionStrategy(),
                "AvfAudioChannel execution strategy is HARDWARE_VIRTUALIZATION");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
