package io.github.nyzeep.orchestella.ui.oe3

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import kotlin.math.cbrt
import kotlin.math.pow
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
    ) = oe3TestTheme(
        dark = dark,
        background = background,
        surface = surface,
        primary = primary,
        text = text,
        textDim = textDim,
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

    private fun lightness(argb: Int): Double {
        fun channel(shift: Int): Double {
            val value = (argb ushr shift and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val y = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        return if (y <= 216.0 / 24389.0) y * (24389.0 / 27.0) else 116.0 * cbrt(y) - 16.0
    }

    @Test
    fun darkRolesConsumeTheResolvedAppTheme() {
        val theme = dark.copy(textDim = 0xFFB3B6BE.toInt())
        val tokens = Oe3Colors.resolve(theme)
        assertEquals(theme.text, tokens.onSurface)
        assertEquals(theme.primary, tokens.primary)
        assertEquals(theme.textDim, tokens.onSurfaceVariant)
        assertEquals(theme.accent, tokens.accent)
    }

    @Test
    fun darkLadderRisesFromPageToRaisedToHigh() {
        ThemeCatalog.all.filter { it.isDark }.forEach { preset ->
            val tokens = Oe3Colors.resolve(preset)
            assertEquals(preset.id, 9.0, lightness(tokens.surface) - lightness(tokens.background), 0.8)
            assertEquals(preset.id, 6.0, lightness(tokens.surfaceRaised) - lightness(tokens.surface), 0.8)
            assertEquals(preset.id, 6.0, lightness(tokens.surfaceHigh) - lightness(tokens.surfaceRaised), 0.8)
            assertEquals(tokens.surfaceRaised, tokens.surfaceFloating)
        }
    }

    @Test
    fun lightLadderFallsFromPageToRaisedToHigh() {
        ThemeCatalog.all.filterNot { it.isDark }.forEach { preset ->
            val tokens = Oe3Colors.resolve(preset)
            assertEquals(preset.surface, tokens.surface)
            assertEquals(preset.id, 4.0, lightness(tokens.surface) - lightness(tokens.background), 0.6)
            assertEquals(preset.id, 4.0, lightness(tokens.surface) - lightness(tokens.surfaceRaised), 0.6)
            assertEquals(preset.id, 4.0, lightness(tokens.surfaceRaised) - lightness(tokens.surfaceHigh), 0.6)
            assertEquals(preset.id, 3.0, lightness(tokens.surface) - lightness(tokens.surfaceFloating), 0.6)
        }
    }

    @Test
    fun pureBlackOverridesEverySurfaceWithTheSpecLadder() {
        val tokens = Oe3Colors.resolve(dark, pureBlack = true)
        assertEquals(0xFF000000.toInt(), tokens.background)
        assertEquals(0xFF0B0B0C.toInt(), tokens.surface)
        assertEquals(0xFF131315.toInt(), tokens.surfaceRaised)
        assertEquals(0xFF1C1C1F.toInt(), tokens.surfaceHigh)
        assertTrue(lightness(tokens.surfaceFloating) - lightness(tokens.surface) >= 5.0)
    }

    @Test
    fun pureBlackNeverDarkensALightTheme() {
        assertEquals(Oe3Colors.resolve(light), Oe3Colors.resolve(light, pureBlack = true))
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
