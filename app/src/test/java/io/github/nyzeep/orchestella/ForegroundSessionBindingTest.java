package io.github.nyzeep.orchestella;

import static org.junit.Assert.*;
import org.junit.Test;

public final class ForegroundSessionBindingTest {
    @Test public void lateStopCannotClearNewStartOrAcceptMissingToken() {
        ForegroundSessionBinding binding = new ForegroundSessionBinding();
        assertTrue(binding.start("instance", "generation-a"));
        assertTrue(binding.start("instance", "generation-b"));
        assertFalse(binding.stop("instance", "generation-a"));
        assertFalse(binding.stop("instance", null));
        assertFalse(binding.stop("different-instance", "generation-b"));
        assertTrue(binding.isActive());
        assertTrue(binding.stop("instance", "generation-b"));
        assertFalse(binding.stop("instance", "generation-b"));
        assertFalse(binding.isActive());
    }

    @Test public void coldStopAndLegacyStopDoNotInventAnOwner() {
        ForegroundSessionBinding binding = new ForegroundSessionBinding();
        assertFalse(binding.stop("instance", "old-process-generation"));
        assertFalse(binding.isActive());
        assertTrue(binding.start("legacy-instance", null));
        assertTrue(binding.stop(null, null));
        assertFalse(binding.stop(null, null));
    }

    @Test public void multiInstanceLeasesStayIndependentAndRetireForegroundOnlyOnLastRelease() {
        ForegroundSessionBinding binding = new ForegroundSessionBinding();
        assertTrue(binding.start("inst-a", "gen-a1", "Alpine 3.22", "UML", BatteryPolicy.BALANCED));
        assertTrue(binding.start("inst-b", "gen-b1", "Alpine 3.22", "UML", BatteryPolicy.PERFORMANCE));
        assertEquals(2, binding.activeCount());
        assertTrue(binding.isActive());
        assertTrue(binding.hasLease("inst-a", "gen-a1"));
        assertTrue(binding.hasLease("inst-b", "gen-b1"));

        // Stale generation stop is ignored and leaves both leases active.
        assertEquals(
                ForegroundSessionBinding.StopOutcome.IGNORED,
                binding.stopLease("inst-a", "gen-old"));
        assertEquals(2, binding.activeCount());

        // Stopping inst-a keeps inst-b protected and keeps foreground active.
        assertEquals(
                ForegroundSessionBinding.StopOutcome.LEASE_RELEASED_OTHERS_ACTIVE,
                binding.stopLease("inst-a", "gen-a1"));
        assertEquals(1, binding.activeCount());
        assertTrue(binding.isActive());
        assertFalse(binding.hasLease("inst-a", "gen-a1"));
        assertTrue(binding.hasLease("inst-b", "gen-b1"));
        assertEquals("inst-b", binding.primaryLease().instanceId());

        // Stopping the final lease releases the foreground service.
        assertEquals(
                ForegroundSessionBinding.StopOutcome.LAST_LEASE_RELEASED,
                binding.stopLease("inst-b", "gen-b1"));
        assertEquals(0, binding.activeCount());
        assertFalse(binding.isActive());
    }
}
