package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * W1-04 (R-08): contract for the streaming shell-handover detector that closes
 * the launch timeline's T0 → SHELL_READY chain. Mirrors the {@link ProvisioningSignal}
 * contract: line-start markers only, any-byte chunk boundaries, at most one detection.
 */
public final class ShellHandoverSignalTest {

    @Test
    public void detectsTheFirstProvisioningHandoverMarker() {
        ShellHandoverSignal signal = new ShellHandoverSignal();
        assertFalse(signal.accept("boot noise\n"));
        assertTrue(signal.accept("DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine\n"));
        assertTrue(signal.detected());
    }

    @Test
    public void detectsTheRestartHandoverMarkerOfAProvisionedRootVolume() {
        ShellHandoverSignal signal = new ShellHandoverSignal();
        assertTrue(signal.accept("DROIDLAYER_ROOT_VOLUME_RESTART version=1 runtime=unverified\n"));
        assertTrue(signal.detected());
    }

    @Test
    public void detectsAMarkerSplitAcrossArbitraryChunkBoundaries() {
        ShellHandoverSignal signal = new ShellHandoverSignal();
        String marker = "DROIDLAYER_ROOT_VOLUME_RESTART version=1";
        for (int index = 0; index < marker.length(); index++) {
            assertFalse("no detection before the delimiter", signal.accept(
                    marker.substring(index, index + 1)));
        }
        assertTrue("the trailing newline completes the marker", signal.accept("\n"));
    }

    @Test
    public void reportsAtMostOneDetection() {
        ShellHandoverSignal signal = new ShellHandoverSignal();
        assertTrue(signal.accept("DROIDLAYER_ROOT_VOLUME_RESTART version=1\n"));
        assertFalse(signal.accept("DROIDLAYER_ROOT_VOLUME_RESTART version=1\n"));
        assertTrue("the first detection still stands", signal.detected());
    }

    @Test
    public void midLineCandidateIsNeverRescued() {
        ShellHandoverSignal signal = new ShellHandoverSignal();
        assertFalse(signal.accept("x DROIDLAYER_PROVISIONING_COMPLETE version=1\n"));
        assertFalse(signal.detected());
        // A later real marker on its own line still completes.
        assertTrue(signal.accept("\nDROIDLAYER_PROVISIONING_COMPLETE version=1 distro=alpine\n"));
    }

    @Test
    public void endOfStreamCompletesAMarkerWithoutTrailingWhitespace() {
        ShellHandoverSignal signal = new ShellHandoverSignal();
        assertFalse(signal.accept("DROIDLAYER_ROOT_VOLUME_RESTART version=1"));
        assertTrue(signal.finish());
        assertFalse("finish is one-shot like accept", signal.finish());
    }

    @Test
    public void endOfStreamWithoutAPendingMarkerIsNoEvidence() {
        ShellHandoverSignal signal = new ShellHandoverSignal();
        assertFalse(signal.accept("regular console transcript\n"));
        assertFalse(signal.finish());
        assertFalse(signal.detected());
    }

    @Test
    public void prefixMismatchesAndNullsStaySilent() {
        ShellHandoverSignal signal = new ShellHandoverSignal();
        assertFalse(signal.accept(null));
        assertFalse(signal.accept("DROIDLAYER_ROOT_VOLUME_RESTART version=2\n"));
        assertFalse(signal.accept("DROIDLAYER_ROOT_VOLUME_RESTART\n"));
        assertFalse(signal.detected());
    }
}
