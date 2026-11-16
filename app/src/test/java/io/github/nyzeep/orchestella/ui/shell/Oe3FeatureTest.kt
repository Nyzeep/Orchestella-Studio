package io.github.nyzeep.orchestella.ui.shell

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shell switch contract after UI3-12 (ticket UI3-12 acceptance 1): the Compose
 * shell is the only interface, the debug-only intent extra and the saved-state
 * suppression are gone with the legacy XML shell.
 */
class Oe3FeatureTest {

    @Test
    fun theComposeShellIsTheOnlyInterface() {
        assertTrue(Oe3Feature.enabled())
    }
}
