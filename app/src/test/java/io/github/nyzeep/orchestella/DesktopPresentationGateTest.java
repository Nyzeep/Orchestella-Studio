package io.github.nyzeep.orchestella;

import static org.junit.Assert.*;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import org.junit.Test;

public class DesktopPresentationGateTest {
    private WaylandChannel.FrameData frame(int width, int height, long time) {
        byte[] pixels = new byte[width * height * 4];
        if (pixels.length > 4) pixels[4] = (byte) 255;
        return new WaylandChannel.FrameData(width, height, width * 4, 1, pixels, 1, time);
    }

    @Test public void resizeRequiresFreshMatchingCaptureAndIgnoresOldCompletion() {
        DesktopPresentationGate gate = new DesktopPresentationGate();
        String first = "a".repeat(32), second = "b".repeat(32);
        assertTrue(gate.observe(first, first, 2, 2, 100));
        assertNull(gate.begin(frame(2, 2, 99)));
        assertNull(gate.begin(frame(1, 2, 101)));
        DesktopPresentationGate.Confirmation old = gate.begin(frame(2, 2, 101));
        assertEquals(first, old.generation());
        assertTrue(gate.observe(second, second, 4, 2, 200));
        gate.finish(old, true);
        assertNull(gate.begin(frame(2, 2, 201)));
        DesktopPresentationGate.Confirmation current = gate.begin(frame(4, 2, 201));
        assertEquals(second, current.generation());
        gate.finish(current, true);
        assertNull(gate.begin(frame(4, 2, 202)));
    }

    @Test public void servicePlaceholderDoesNotConfirmDesktopReadiness() {
        DesktopPresentationGate gate = new DesktopPresentationGate();
        String generation = "a".repeat(32);
        gate.observe(generation, generation, 2, 2, 100);
        byte[] placeholder = new byte[16];
        for (int index = 0; index < placeholder.length; index += 4) {
            placeholder[index] = 0x60; placeholder[index + 1] = 0x60; placeholder[index + 2] = 0x60;
            placeholder[index + 3] = (byte) 255;
        }
        assertNull(gate.begin(new WaylandChannel.FrameData(2, 2, 8, 1, placeholder, 1, 101)));
        assertEquals(generation, gate.begin(frame(2, 2, 102)).generation());
    }

    @Test public void missingOrStaleGuestCaptureCannotAuthorizeHostFrame() {
        DesktopPresentationGate gate = new DesktopPresentationGate();
        assertFalse(gate.observe("a".repeat(32), "b".repeat(32), 2, 2, 100));
        assertFalse(gate.observe("bad", "bad", 2, 2, 100));
        assertNull(gate.begin(frame(2, 2, 101)));
        gate.observe("a".repeat(32), "a".repeat(32), 2, 2, 100);
        gate.invalidate();
        assertNull(gate.begin(frame(2, 2, 101)));
    }

    @Test public void sameGuestGenerationResizeRejectsLateAckWithoutConsumingNewAttempt() {
        DesktopPresentationGate gate = new DesktopPresentationGate();
        String generation = "a".repeat(32);
        gate.observe(generation, generation, 2, 2, 100);
        DesktopPresentationGate.Confirmation old = gate.begin(frame(2, 2, 101));
        assertTrue(gate.observe(generation, generation, 4, 2, 200));
        DesktopPresentationGate.Confirmation current = gate.begin(frame(4, 2, 201));
        assertFalse(gate.isCurrent(old));
        gate.finish(old, true);
        assertTrue(gate.isCurrent(current));
        gate.finish(current, false);
        assertNotNull(gate.begin(frame(4, 2, 202)));
    }

    @Test public void retriedConfirmationHasItsOwnEpochAndCannotBeCompletedTwice() {
        DesktopPresentationGate gate = new DesktopPresentationGate();
        String generation = "a".repeat(32);
        gate.observe(generation, generation, 2, 2, 100);
        DesktopPresentationGate.Confirmation first = gate.begin(frame(2, 2, 101));
        gate.finish(first, false);
        DesktopPresentationGate.Confirmation retry = gate.begin(frame(2, 2, 102));
        assertNotEquals(first, retry);
        gate.finish(first, true);
        assertTrue(gate.isCurrent(retry));
        gate.finish(retry, true);
        gate.finish(retry, false);
        assertNull(gate.begin(frame(2, 2, 103)));
    }

    @Test public void invalidCaptureRevokesPreviouslyObservedGeneration() {
        DesktopPresentationGate gate = new DesktopPresentationGate();
        String generation = "a".repeat(32);
        gate.observe(generation, generation, 2, 2, 100);
        DesktopPresentationGate.Confirmation old = gate.begin(frame(2, 2, 101));
        assertFalse(gate.observe(generation, "b".repeat(32), 2, 2, 102));
        gate.finish(old, true);
        assertNull(gate.begin(frame(2, 2, 103)));
        assertTrue(gate.observe(generation, generation, 2, 2, 104));
        assertNotNull(gate.begin(frame(2, 2, 105)));
    }
}
