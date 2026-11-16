package io.github.nyzeep.orchestella.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature-flag policy for the Compose shell (ticket UI3-02, acceptance 1). */
class Oe3FeatureTest {

    @Test
    fun flagDefaultsOffEvenInDebugBuilds() {
        assertFalse(Oe3Feature.resolve(savedState = null, requested = false, debugBuild = true))
        assertFalse(Oe3Feature.resolve(savedState = null, requested = false, debugBuild = false))
    }

    @Test
    fun debugBuildHonoursTheIntentExtra() {
        assertTrue(Oe3Feature.resolve(savedState = null, requested = true, debugBuild = true))
    }

    @Test
    fun releaseBuildIgnoresTheIntentExtra() {
        assertFalse(Oe3Feature.resolve(savedState = null, requested = true, debugBuild = false))
    }

    @Test
    fun savedStateKeepsTheShellOpenAcrossRecreation() {
        assertTrue(Oe3Feature.resolve(savedState = true, requested = false, debugBuild = true))
        assertFalse(Oe3Feature.resolve(savedState = false, requested = false, debugBuild = true))
    }

    @Test
    fun savedStateCannotOpenTheShellInReleaseBuilds() {
        assertFalse(Oe3Feature.resolve(savedState = true, requested = false, debugBuild = false))
    }

    @Test
    fun theAdbExtraIsTheDocumentedKey() {
        assertEquals("oe3", Oe3Feature.EXTRA_ENABLED)
        assertEquals("oe3_enabled", Oe3Feature.STATE_KEY)
    }
}
