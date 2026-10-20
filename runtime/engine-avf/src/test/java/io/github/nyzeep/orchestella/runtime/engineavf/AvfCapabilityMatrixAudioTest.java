package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import java.util.Objects;

/**
 * Ticket 04: the AVF engine's no-session matrix is an upper bound derived from
 * actual wiring, not a promise list. RFB is the display; audio, clipboard,
 * pointer capture and GPU acceleration are absent in AVF v1.
 */
public final class AvfCapabilityMatrixAudioTest {

    public static void main(String[] arguments) {
        avfEngineDeclaresOnlyWiredCapabilities();
        avfBadgesStayHonest();
        System.out.println(
                "AVF_CAPABILITY_MATRIX_AUDIO_TEST_OK matrix=1 rfb=1 audio_absent=1 clipboard_absent=1");
    }

    private static void avfEngineDeclaresOnlyWiredCapabilities() {
        FullLinuxContract.CapabilityMatrix matrix = AvfVirtualMachineEngine.capabilityMatrix();
        assertTrue(matrix != null, "matrix must not be null");
        assertEquals(
                FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                matrix.executionStrategy(),
                "execution strategy must be HARDWARE_VIRTUALIZATION");

        assertTrue(matrix.supports(FullLinuxContract.Capability.TERMINAL), "supports TERMINAL");
        assertTrue(matrix.supports(FullLinuxContract.Capability.BRIDGE_HANDSHAKE), "supports BRIDGE_HANDSHAKE");
        assertTrue(matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION), "supports HARDWARE_ACCELERATION");
        assertTrue(matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB), "supports GRAPHICS_RFB");

        assertFalse(matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND),
                "RFB must not be declared as Wayland");
        assertFalse(matrix.supports(FullLinuxContract.Capability.AUDIO_OUTPUT),
                "AVF v1 has no audio relay, so AUDIO_OUTPUT must be absent");
        assertFalse(matrix.supports(FullLinuxContract.Capability.CLIPBOARD_SYNC),
                "AVF v1 has no wired clipboard agent, so CLIPBOARD_SYNC must be absent");
        assertFalse(matrix.supports(FullLinuxContract.Capability.POINTER_CAPTURE),
                "RFB pointer events are not Android pointer capture");
        assertFalse(matrix.supports(FullLinuxContract.Capability.GPU_HARDWARE_ACCELERATION),
                "AVF v1 has no guest GPU acceleration");
        assertFalse(matrix.supports(FullLinuxContract.Capability.SHARED_DIRECTORY),
                "the static upper bound does not start a 9p server");

        assertFalse(matrix.supportsAudio(), "supportsAudio helper must be false");
        assertFalse(matrix.supportsClipboard(), "supportsClipboard helper must be false");
        assertFalse(matrix.supportsPointerCapture(), "supportsPointerCapture helper must be false");
    }

    private static void avfBadgesStayHonest() {
        FullLinuxContract.CapabilityMatrix matrix = AvfVirtualMachineEngine.capabilityMatrix();

        assertEquals("无音频输出", matrix.audioBadge(), "audio badge must say there is no audio");
        assertEquals("无剪贴板同步", matrix.clipboardBadge(), "clipboard badge must say there is no clipboard");
        assertEquals("无指针捕获", matrix.pointerCaptureBadge(), "pointer badge must say there is no capture");
        assertEquals("RFB 显示（vsock CPU 拷贝）", matrix.graphicsBadge(), "graphics badge must name RFB");
        assertEquals("无 GPU 加速（软件光栅）", matrix.gpuBadge(), "GPU badge must stay absent");
        assertEquals("完整 Linux · 硬件加速", matrix.performanceBadge(), "performance badge reflects hardware acceleration");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
