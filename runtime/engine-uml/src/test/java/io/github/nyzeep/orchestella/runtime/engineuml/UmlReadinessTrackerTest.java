package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UmlReadinessTrackerTest {

    @Test
    public void tracksThreeDistinctMilestonesInOrderForActiveGeneration() {
        UmlReadinessTracker tracker = new UmlReadinessTracker("inst-1", "gen-1");
        assertEquals(UmlReadinessTracker.Phase.IDLE, tracker.snapshot().phase());

        assertTrue(tracker.markProcessStarted("gen-1", 1234L, 100L));
        UmlReadinessTracker.Snapshot afterProc = tracker.snapshot();
        assertEquals(UmlReadinessTracker.Phase.PROCESS_STARTED, afterProc.phase());
        assertTrue(afterProc.processStarted());
        assertFalse(afterProc.bridgeReady());
        assertFalse(afterProc.shellReady());
        assertEquals(100L, afterProc.processStartedNanos());

        assertTrue(tracker.markBridgeReady("gen-1", 250L));
        UmlReadinessTracker.Snapshot afterBridge = tracker.snapshot();
        assertEquals(UmlReadinessTracker.Phase.BRIDGE_READY, afterBridge.phase());
        assertTrue(afterBridge.bridgeReady());
        assertFalse(afterBridge.shellReady());
        assertEquals(250L, afterBridge.bridgeReadyNanos());

        assertTrue(tracker.markShellReady("gen-1", 400L));
        UmlReadinessTracker.Snapshot afterShell = tracker.snapshot();
        assertEquals(UmlReadinessTracker.Phase.SHELL_READY, afterShell.phase());
        assertTrue(afterShell.shellReady());
        assertEquals(400L, afterShell.shellReadyNanos());
    }

    @Test
    public void earlyShellPromptWaitsForBridgeAuthBeforePromotingShellReady() {
        UmlReadinessTracker tracker = new UmlReadinessTracker("inst-2", "gen-1");
        assertTrue(tracker.markProcessStarted("gen-1", 555L, 100L));

        // Shell prompt observed on con0 before Bridge finishes HELLO/READY:
        assertFalse(tracker.markShellReady("gen-1", 180L));
        assertFalse(tracker.snapshot().shellReady());
        assertTrue(tracker.snapshot().shellPromptObserved());

        // Once Bridge HELLO/READY succeeds, readiness promotes cleanly.
        assertTrue(tracker.markBridgeReady("gen-1", 300L));
        UmlReadinessTracker.Snapshot ready = tracker.snapshot();
        assertTrue(ready.bridgeReady());
        assertTrue(ready.shellReady());
        assertEquals(UmlReadinessTracker.Phase.SHELL_READY, ready.phase());
        assertEquals(300L, ready.shellReadyNanos());
    }

    @Test
    public void timeoutUnsupportedVersionUnauthorizedAndCancelBlockReadinessPromotion() {
        for (UmlReadinessTracker.RejectionReason reason : new UmlReadinessTracker.RejectionReason[] {
                UmlReadinessTracker.RejectionReason.TIMEOUT,
                UmlReadinessTracker.RejectionReason.UNSUPPORTED_VERSION,
                UmlReadinessTracker.RejectionReason.UNAUTHORIZED,
                UmlReadinessTracker.RejectionReason.MALFORMED_HANDSHAKE
        }) {
            UmlReadinessTracker tracker = new UmlReadinessTracker("inst-rej", "gen-1");
            assertTrue(tracker.markProcessStarted("gen-1", 10L, 10L));
            assertFalse(tracker.markShellReady("gen-1", 20L));
            assertTrue(tracker.markBridgeRejected("gen-1", reason, 30L));

            assertFalse(tracker.markBridgeReady("gen-1", 40L));
            assertFalse(tracker.markShellReady("gen-1", 50L));
            UmlReadinessTracker.Snapshot snap = tracker.snapshot();
            assertEquals(UmlReadinessTracker.Phase.FAILED, snap.phase());
            assertEquals(reason, snap.rejectionReason());
            assertFalse(snap.bridgeReady());
            assertFalse(snap.shellReady());
        }

        UmlReadinessTracker cancelled = new UmlReadinessTracker("inst-cancel", "gen-1");
        assertTrue(cancelled.markProcessStarted("gen-1", 22L, 10L));
        assertTrue(cancelled.cancel("gen-1", 15L));
        assertFalse(cancelled.markBridgeReady("gen-1", 25L));
        assertFalse(cancelled.markShellReady("gen-1", 35L));
        assertEquals(UmlReadinessTracker.RejectionReason.CANCELLED, cancelled.snapshot().rejectionReason());
    }

    @Test
    public void staleGenerationCallbacksAreRejectedAndDoNotPolluteCurrentGeneration() {
        UmlReadinessTracker tracker = new UmlReadinessTracker("inst-gen", "gen-1");
        assertTrue(tracker.markProcessStarted("gen-1", 100L, 10L));

        tracker.invalidateAndAdvanceGeneration("gen-2");

        // Late callbacks from gen-1 must never promote gen-2 or mark gen-2 failed.
        assertFalse(tracker.markBridgeReady("gen-1", 50L));
        assertFalse(tracker.markShellReady("gen-1", 60L));
        assertFalse(tracker.markBridgeRejected("gen-1", UmlReadinessTracker.RejectionReason.TIMEOUT, 70L));
        assertFalse(tracker.cancel("gen-1", 80L));

        UmlReadinessTracker.Snapshot snap = tracker.snapshot();
        assertEquals("gen-2", snap.generation());
        assertEquals(UmlReadinessTracker.Phase.IDLE, snap.phase());
        assertFalse(snap.processStarted());
        assertFalse(snap.bridgeReady());
        assertFalse(snap.shellReady());
        assertEquals(UmlReadinessTracker.RejectionReason.NONE, snap.rejectionReason());

        // Active generation gen-2 proceeds normally.
        assertTrue(tracker.markProcessStarted("gen-2", 200L, 90L));
        assertTrue(tracker.markBridgeReady("gen-2", 110L));
        assertTrue(tracker.markShellReady("gen-2", 130L));
        assertEquals(UmlReadinessTracker.Phase.SHELL_READY, tracker.snapshot().phase());
    }
}
