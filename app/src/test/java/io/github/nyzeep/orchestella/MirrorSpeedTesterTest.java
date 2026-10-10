package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * TDD Unit tests for MirrorSpeedTester (Phase 5.0 R2).
 * Verifies candidate generation, probe result sorting, and fastest mirror selection.
 */
public class MirrorSpeedTesterTest {

    @Test
    public void defaultCandidatesAreNotEmpty() {
        List<MirrorSpeedTester.MirrorCandidate> systemMirrors = MirrorSpeedTester.defaultSystemCandidates();
        assertNotNull(systemMirrors);
        assertTrue(systemMirrors.size() >= 5);

        List<MirrorSpeedTester.MirrorCandidate> dockerMirrors = MirrorSpeedTester.defaultDockerCandidates();
        assertNotNull(dockerMirrors);
        assertTrue(dockerMirrors.size() >= 4);
    }

    @Test
    public void fastestSelectionPrefersLowestLatencySuccessfulResult() {
        MirrorSpeedTester.MirrorCandidate c1 = new MirrorSpeedTester.MirrorCandidate("C1", "https://c1.test");
        MirrorSpeedTester.MirrorCandidate c2 = new MirrorSpeedTester.MirrorCandidate("C2", "https://c2.test");
        MirrorSpeedTester.MirrorCandidate c3 = new MirrorSpeedTester.MirrorCandidate("C3", "https://c3.test");

        List<MirrorSpeedTester.SpeedResult> results = new ArrayList<>();
        // C1 failed, C2 120ms, C3 45ms
        results.add(new MirrorSpeedTester.SpeedResult(c1, 2000, false, "Connection refused"));
        results.add(new MirrorSpeedTester.SpeedResult(c2, 120, true, null));
        results.add(new MirrorSpeedTester.SpeedResult(c3, 45, true, null));

        // When sorted (as probeAll does):
        results.sort((a, b) -> {
            if (a.isSuccess() && b.isSuccess()) {
                return Long.compare(a.latencyMs(), b.latencyMs());
            } else if (a.isSuccess()) {
                return -1;
            } else if (b.isSuccess()) {
                return 1;
            }
            return Long.compare(a.latencyMs(), b.latencyMs());
        });

        MirrorSpeedTester.MirrorCandidate fastest = MirrorSpeedTester.selectFastest(results, c1);
        assertEquals("C3", fastest.name());
        assertEquals("https://c3.test", fastest.url());
    }

    @Test
    public void fastestSelectionFallsBackWhenAllFail() {
        MirrorSpeedTester.MirrorCandidate c1 = new MirrorSpeedTester.MirrorCandidate("C1", "https://c1.test");
        List<MirrorSpeedTester.SpeedResult> results = new ArrayList<>();
        results.add(new MirrorSpeedTester.SpeedResult(c1, 500, false, "Timeout"));

        MirrorSpeedTester.MirrorCandidate fallback = new MirrorSpeedTester.MirrorCandidate("Fallback", "https://fallback.test");
        MirrorSpeedTester.MirrorCandidate selected = MirrorSpeedTester.selectFastest(results, fallback);
        assertEquals("Fallback", selected.name());
    }
}
