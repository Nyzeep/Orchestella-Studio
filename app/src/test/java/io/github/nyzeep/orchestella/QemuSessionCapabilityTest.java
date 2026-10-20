package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import org.junit.Test;

/**
 * Ticket 10 boundary: a live QEMU session projects its own descriptor instead
 * of the software baseline. Audio playback, clipboard bridging and Android
 * pointer capture are driven by DesktopActivity/UI; they are not session
 * capabilities and must stay absent until a session-owned adapter exists.
 */
public final class QemuSessionCapabilityTest {

    @Test
    public void qemuSessionOverridesTheBaselineAndDoesNotClaimUiPeripherals() {
        QemuInstanceLauncher.Session session = new QemuInstanceLauncher.Session(
                "ticket10-control", "ticket10-terminal", null, null, null, new FakeHandle());
        FullLinuxContract.CapabilityMatrix matrix = session.capabilityMatrix();

        assertEquals("QEMU remains the software strategy",
                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                matrix.executionStrategy());
        assertTrue("the terminal is wired", matrix.supports(FullLinuxContract.Capability.TERMINAL));
        assertTrue("the bridge is wired", matrix.supports(FullLinuxContract.Capability.BRIDGE_HANDSHAKE));
        assertFalse("a session without a display must not invent graphics",
                matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB));
        assertFalse("RFB is never relabelled Wayland",
                matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND));
        assertFalse("audio is UI-driven, not a session capability", matrix.supportsAudio());
        assertFalse("clipboard is UI-driven, not a session capability", matrix.supportsClipboard());
        assertFalse("pointer capture is UI-driven, not a session capability", matrix.supportsPointerCapture());
        assertFalse("no 9p share is assembled here",
                matrix.supports(FullLinuxContract.Capability.SHARED_DIRECTORY));
        assertFalse("software QEMU does not claim hardware acceleration",
                matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION));
        assertFalse("no GPU acceleration is declared",
                matrix.supportsGpuAcceleration());
        assertEquals("无图形通道", matrix.graphicsBadge());
    }

    private static final class FakeHandle implements QemuProcessLauncher.ProcessHandle {
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
        @Override public void destroy() { }
        @Override public void destroyForcibly() { }
        @Override public boolean isAlive() { return false; }
    }
}
