package io.github.nyzeep.orchestella.ui.oe3.glass

import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM contract for the glass draw-path decision (spec §2.6, ticket UI3-10).
 *
 * The palette numbers themselves (blur 16/28dp, tint α0.72/0.55) live in
 * `Oe3Glass.resolve` and are pinned by `Oe3ThemeTest`; this suite pins that the
 * [Oe3GlassStrategy] routes each level to the right path and forwards the
 * resolved parameters unchanged, so there is exactly one place that owns the
 * numbers and exactly one place that owns the branching.
 */
class Oe3GlassStrategyTest {

    private val theme = ThemeCatalog.constructivistWorkstation

    @Test
    fun realBlurNeedsApi31() {
        assertFalse(Oe3GlassStrategy.supportsRealBlur(apiLevel = 30))
        assertTrue(Oe3GlassStrategy.supportsRealBlur(apiLevel = 31))
        assertTrue(Oe3GlassStrategy.supportsRealBlur(apiLevel = 35))
    }

    @Test
    fun noneIsAlwaysSolidWhateverTheEnvironment() {
        val tokens = Oe3Glass.resolve(theme, Oe3GlassLevel.None)
        assertEquals(
            Oe3GlassRender.Solid,
            Oe3GlassStrategy.resolve(
                level = Oe3GlassLevel.None,
                enabled = true,
                supportsRealBlur = true,
                tokens = tokens,
            ),
        )
        assertEquals(
            Oe3GlassRender.Solid,
            Oe3GlassStrategy.resolve(
                level = Oe3GlassLevel.None,
                enabled = false,
                supportsRealBlur = false,
                tokens = tokens,
            ),
        )
    }

    @Test
    fun standardWithRealBlurIsBlurredWithTheDocumentedParameters() {
        val tokens = Oe3Glass.resolve(theme, Oe3GlassLevel.Standard)
        val render = Oe3GlassStrategy.resolve(
            level = Oe3GlassLevel.Standard,
            enabled = true,
            supportsRealBlur = true,
            tokens = tokens,
        )
        val blurred = render as Oe3GlassRender.Blurred
        assertEquals(16.dp, blurred.blurRadius)
        assertEquals(0.72f, blurred.tintAlpha, 0.0001f)
        assertEquals(tokens.tint, blurred.tintArgb)
        assertEquals(tokens.fallbackTint, blurred.fallbackTintArgb)
    }

    @Test
    fun ultraWithRealBlurIsBlurredWithTheDocumentedParameters() {
        val tokens = Oe3Glass.resolve(theme, Oe3GlassLevel.Ultra)
        val render = Oe3GlassStrategy.resolve(
            level = Oe3GlassLevel.Ultra,
            enabled = true,
            supportsRealBlur = true,
            tokens = tokens,
        )
        val blurred = render as Oe3GlassRender.Blurred
        assertEquals(28.dp, blurred.blurRadius)
        assertEquals(0.55f, blurred.tintAlpha, 0.0001f)
    }

    @Test
    fun glassBelowTheBlurApiFallsBackToTheScrimmedSurface() {
        val tokens = Oe3Glass.resolve(theme, Oe3GlassLevel.Standard)
        assertEquals(
            Oe3GlassRender.Fallback,
            Oe3GlassStrategy.resolve(
                level = Oe3GlassLevel.Standard,
                enabled = true,
                supportsRealBlur = false,
                tokens = tokens,
            ),
        )
        val ultra = Oe3Glass.resolve(theme, Oe3GlassLevel.Ultra)
        assertEquals(
            Oe3GlassRender.Fallback,
            Oe3GlassStrategy.resolve(
                level = Oe3GlassLevel.Ultra,
                enabled = true,
                supportsRealBlur = false,
                tokens = ultra,
            ),
        )
    }

    @Test
    fun glassDisabledByTheSystemFallsBackToTheScrimmedSurface() {
        val tokens = Oe3Glass.resolve(theme, Oe3GlassLevel.Standard)
        assertEquals(
            Oe3GlassRender.Fallback,
            Oe3GlassStrategy.resolve(
                level = Oe3GlassLevel.Standard,
                enabled = false,
                supportsRealBlur = true,
                tokens = tokens,
            ),
        )
    }
}
