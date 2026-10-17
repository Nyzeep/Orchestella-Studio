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
}
