package io.github.nyzeep.orchestella.ui.oe3

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM contract for the Oe3 color roles (spec §2.1). */
class Oe3ColorsTest {

    private fun theme(
        dark: Boolean,
        background: Int,
        surface: Int,
        primary: Int,
        text: Int,
        textDim: Int,
    ) = AppTheme(
        id = "test",
        displayName = "test",
        isDark = dark,
        background = background,
        surface = surface,
        surfaceTranslucent = surface,
        primary = primary,
        accent = primary,
        text = text,
        textDim = textDim,
        glow = primary,
        terminalBackground = background,
        terminalForeground = text,
        ansi = List(16) { 0xFF000000.toInt() },
    )

    private val dark = theme(
        dark = true,
        background = 0xFF0D0E11.toInt(),
        surface = 0xFF16171D.toInt(),
        primary = 0xFFD62828.toInt(),
        text = 0xFFF5F4F0.toInt(),
        textDim = 0xFF8E929B.toInt(),
    )

    private val light = theme(
        dark = false,
        background = 0xFFF7F7F8.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        primary = 0xFF2563EB.toInt(),
        text = 0xFF16171A.toInt(),
        textDim = 0xFF5F646D.toInt(),
    )

    private fun brightness(argb: Int): Int =
        (argb ushr 16 and 0xFF) + (argb ushr 8 and 0xFF) + (argb and 0xFF)

    @Test
    fun darkRolesConsumeTheResolvedAppTheme() {
        val tokens = Oe3Colors.resolve(dark)
        assertEquals(dark.background, tokens.background)
        assertEquals(dark.surface, tokens.surface)
        assertEquals(dark.text, tokens.onSurface)
        assertEquals(dark.primary, tokens.primary)
        assertEquals(
            ThemeColors.readable(dark.textDim, dark.surface),
            tokens.onSurfaceVariant,
        )
    }

    @Test
    fun darkLadderRisesFromPageToRaisedToHigh() {
        val tokens = Oe3Colors.resolve(dark)
        assertTrue(brightness(tokens.surfaceRaised) > brightness(tokens.surface))
        assertTrue(brightness(tokens.surfaceHigh) > brightness(tokens.surfaceRaised))
    }

    @Test
    fun lightLadderFallsFromPageToRaisedToHigh() {
        val tokens = Oe3Colors.resolve(light)
        assertEquals(light.background, tokens.background)
        assertEquals(light.surface, tokens.surface)
        assertTrue(brightness(tokens.surfaceRaised) < brightness(tokens.surface))
        assertTrue(brightness(tokens.surfaceHigh) < brightness(tokens.surfaceRaised))
    }

    @Test
    fun pureBlackOverridesEverySurfaceWithTheSpecLadder() {
        val tokens = Oe3Colors.resolve(dark, pureBlack = true)
        assertEquals(0xFF000000.toInt(), tokens.background)
        assertEquals(0xFF0B0B0C.toInt(), tokens.surface)
        assertEquals(0xFF131315.toInt(), tokens.surfaceRaised)
        assertEquals(0xFF1C1C1F.toInt(), tokens.surfaceHigh)
    }

    @Test
    fun pureBlackNeverDarkensALightTheme() {
        val tokens = Oe3Colors.resolve(light, pureBlack = true)
        assertEquals(light.background, tokens.background)
        assertEquals(light.surface, tokens.surface)
    }

    @Test
    fun dividerIsOnSurfaceAtFourteenPercent() {
        val tokens = Oe3Colors.resolve(dark)
        assertEquals(36, tokens.divider ushr 24)
        assertEquals(tokens.onSurface and 0x00FFFFFF, tokens.divider and 0x00FFFFFF)
    }

    @Test
    fun errorRoleIsOpaqueAndBrightnessAware() {
        val darkTokens = Oe3Colors.resolve(dark)
        val lightTokens = Oe3Colors.resolve(light)
        assertEquals(0xFF, darkTokens.error ushr 24)
        assertEquals(0xFF, lightTokens.error ushr 24)
        assertNotEquals(darkTokens.error, lightTokens.error)
    }

    @Test
    fun onPrimaryIsTheHigherContrastInk() {
        val tokens = Oe3Colors.resolve(dark)
        assertTrue(
            tokens.onPrimary == 0xFF000000.toInt() || tokens.onPrimary == 0xFFFFFFFF.toInt(),
        )
        assertTrue(
            "onPrimary must contrast with primary",
            ThemeColors.contrast(tokens.onPrimary, tokens.primary) >= 4.5,
        )
    }

    @Test
    fun customPrimaryAndTextFlowThroughTheTokens() {
        val custom = dark.copy(primary = 0xFF00A86B.toInt(), text = 0xFFE0FFE0.toInt())
        val tokens = Oe3Colors.resolve(custom)
        assertEquals(0xFF00A86B.toInt(), tokens.primary)
        assertEquals(0xFFE0FFE0.toInt(), tokens.onSurface)
        assertEquals((0xFFE0FFE0.toInt() and 0x00FFFFFF) or (36 shl 24), tokens.divider)
    }
}
