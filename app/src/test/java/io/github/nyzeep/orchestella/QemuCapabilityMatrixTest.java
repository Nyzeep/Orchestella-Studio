package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Ticket 04: the QEMU session derives its matrix from the display it actually
 * constructed, so the desktop HUD stops reading a live RFB display as a
 * software baseline without graphics.
 */
public final class QemuCapabilityMatrixTest {

    @Test
    public void sessionWithDisplayDeclaresRfbDisplay() {
        QemuInstanceLauncher.Session session = new QemuInstanceLauncher.Session(
                "control-socket", "terminal-socket", "proxy-socket",
                "/tmp/orchestella-display.sock", "a".repeat(64), new FakeHandle(), "instance-1");
        FullLinuxContract.CapabilityMatrix matrix = session.capabilityMatrix();

        assertTrue("the constructed display is declared",
                matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB));
        assertFalse("the QEMU display is RFB, not Wayland",
                matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND));
        assertTrue(matrix.supports(FullLinuxContract.Capability.TERMINAL));
        assertTrue(matrix.supports(FullLinuxContract.Capability.BRIDGE_HANDSHAKE));
        assertFalse("QEMU is software virtualization",
                matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION));
        assertTrue("the HUD badge is no longer \"无图形通道\"",
                !matrix.graphicsBadge().equals("无图形通道"));
        session.close();
    }

    @Test
    public void sessionWithoutDisplayKeepsTheBaseline() {
        QemuInstanceLauncher.Session session = new QemuInstanceLauncher.Session(
                "control-socket", "terminal-socket", "proxy-socket",
                null, null, new FakeHandle(), "instance-2");
        FullLinuxContract.CapabilityMatrix matrix = session.capabilityMatrix();

        assertFalse(matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB));
        assertFalse(matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND));
        assertTrue(matrix.supports(FullLinuxContract.Capability.TERMINAL));
        session.close();
    }

    private static final class FakeHandle implements QemuProcessLauncher.ProcessHandle {
        private volatile boolean alive = true;

        @Override public int waitFor() { alive = false; return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { alive = false; return true; }
        @Override public void destroy() { alive = false; }
        @Override public void destroyForcibly() { alive = false; }
        @Override public boolean isAlive() { return alive; }
    }
}
