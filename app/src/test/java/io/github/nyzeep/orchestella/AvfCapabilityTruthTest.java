package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import org.junit.Test;

/**
 * Ticket 04: an AVF session declares only what its assembly constructed. RFB
 * is the display; audio, clipboard, pointer capture, 9p and forwarding stay
 * absent until their tickets wire them.
 */
public final class AvfCapabilityTruthTest {

    @Test
    public void sessionWithDisplayDeclaresRfbAndNothingElse() {
        AvfInstanceLauncher.AvfSession session =
                new AvfInstanceLauncher.AvfSession(null, "a".repeat(64), null);
        FullLinuxContract.CapabilityMatrix matrix = session.capabilityMatrix();

        assertTrue("terminal channel is wired",
                matrix.supports(FullLinuxContract.Capability.TERMINAL));
        assertTrue("bridge channel is wired",
                matrix.supports(FullLinuxContract.Capability.BRIDGE_HANDSHAKE));
        assertTrue("AVF is hardware virtualization",
                matrix.supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION));
        assertTrue("the RFB display is the live AVF display",
                matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB));
        assertFalse("RFB is not Wayland",
                matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND));
        assertFalse("no audio adapter is assembled", matrix.supportsAudio());
        assertFalse("no clipboard agent is assembled", matrix.supportsClipboard());
        assertFalse("RFB input is not pointer capture", matrix.supportsPointerCapture());
        assertFalse("no 9p server starts at launch", matrix.supports(FullLinuxContract.Capability.SHARED_DIRECTORY));
        assertFalse("no guest GPU acceleration", matrix.supportsGpuAcceleration());
        assertTrue("the UI badge names RFB", matrix.graphicsBadge().contains("RFB"));
    }

    @Test
    public void sessionWithoutDisplayDoesNotInventGraphics() {
        AvfInstanceLauncher.AvfSession session =
                new AvfInstanceLauncher.AvfSession(null, null, null);
        FullLinuxContract.CapabilityMatrix matrix = session.capabilityMatrix();

        assertFalse(matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB));
        assertFalse(matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND));
        assertTrue("the session still has its terminal", matrix.supports(FullLinuxContract.Capability.TERMINAL));
    }

    @Test
    public void registeringAClipboardBridgeDoesNotClaimClipboardSupport() {
        AvfInstanceLauncher.AvfSession session =
                new AvfInstanceLauncher.AvfSession(null, "a".repeat(64), null);
        session.registerClipboardBridge(new io.github.nyzeep.orchestella.runtime.contract.BridgeControl.Clipboard() {
            @Override public boolean onClipboardPut(String text) { return true; }
            @Override public String onClipboardGet() { return ""; }
        });

        assertFalse("Bridge v1 + a host callback alone is not an end-to-end clipboard agent proof",
                session.capabilityMatrix().supportsClipboard());
        assertFalse("the descriptor keeps clipboard absent until the guest agent contract is proven",
                session.capabilities().clipboardSync());
    }
}
