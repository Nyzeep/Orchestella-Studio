package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Glass environment contract (ticket UI3-10): the kill-switch local defaults
 * to enabled — the production composition only flips it when the system asks
 * for reduced motion or power saving — and the fallback decoration keeps its
 * documented constants so the below-API-31 surface never degrades into a flat
 * black block (spec §2.6).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class Oe3GlassLocalTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun glassIsEnabledByDefault() {
        var enabled: Boolean? = null
        compose.setContent {
            enabled = LocalOe3GlassEnabled.current
        }
        compose.waitForIdle()
        assertEquals(true, enabled)
    }

    @Test
    fun theKillSwitchOverridesTheDefault() {
        var enabled: Boolean? = null
        compose.setContent {
            CompositionLocalProvider(LocalOe3GlassEnabled provides false) {
                enabled = LocalOe3GlassEnabled.current
            }
        }
        compose.waitForIdle()
        assertEquals(false, enabled)
    }

    @Test
    fun fallbackSurfaceStaysAlmostSolidWithATopScrim() {
        // 245/255 = 96% — the same alpha `Oe3Glass.resolve` bakes into
        // `fallbackTint`; the scrim covers exactly the top 24dp.
        assertEquals(245, Oe3GlassFallback.SURFACE_ALPHA_8BIT)
        assertEquals(0.96f, Oe3GlassFallback.SURFACE_ALPHA, 0.0001f)
        assertEquals(24.dp, Oe3GlassFallback.SCRIM_HEIGHT)
        assertTrue(Oe3GlassFallback.SCRIM_TOP_ALPHA in 0.01f..0.99f)
    }
}
