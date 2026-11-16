package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePreviewColorsTest {

    private val dark = ThemeCatalog.constructivistWorkstation
    private val light = ThemeCatalog.constructivistWorkstationLight

    @Test
    fun darkThemeMapsEveryRoleFromTheResolvedPalette() {
        val colors = homePreviewColors(dark)
        assertEquals(dark.background, colors.background)
        assertEquals(dark.surface, colors.surface)
        assertEquals(dark.primary, colors.primary)
        assertEquals(dark.text, colors.onSurface)
        assertEquals(dark.accent, colors.accent)
        assertEquals(io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors.resolve(dark).surfaceHigh, colors.ringTrack)
    }

    @Test
    fun lightThemeMapsEveryRoleFromTheResolvedPalette() {
        val colors = homePreviewColors(light)
        assertEquals(light.background, colors.background)
        assertEquals(light.surface, colors.surface)
        assertEquals(light.primary, colors.primary)
        assertEquals(light.text, colors.onSurface)
    }

    @Test
    fun pureBlackKeepsTheThemeAccentAndReplacesOnlyDarkSurfaces() {
        val colors = homePreviewColors(dark, pureBlack = true)
        assertEquals(0xFF000000.toInt(), colors.background)
        assertEquals(0xFF0B0B0C.toInt(), colors.surface)
        assertEquals(dark.primary, colors.primary)
        assertEquals(dark.text, colors.onSurface)
        assertEquals(dark.accent, colors.accent)
    }

    @Test
    fun pureBlackIsIgnoredOnLightPalettes() {
        assertEquals(homePreviewColors(light), homePreviewColors(light, pureBlack = true))
    }

    @Test
    fun surfaceOpacityChangesOnlyTheSurfaceRoleAlpha() {
        val colors = homePreviewColors(dark, opacityPercent = 55)
        assertEquals(140, colors.surface ushr 24)
        assertEquals(dark.surface and 0x00FFFFFF, colors.surface and 0x00FFFFFF)
        assertEquals(dark.background, colors.background)
    }

    @Test
    fun glassChangesTheFloatingChromeFillNotThePageColour() {
        val none = homePreviewColors(dark, glass = GlassOption.None)
        val standard = homePreviewColors(dark, glass = GlassOption.Standard)
        val ultra = homePreviewColors(dark, glass = GlassOption.Ultra)
        assertEquals(none.background, standard.background)
        assertEquals(none.background, ultra.background)
        assertNotEquals(none.navFill, standard.navFill)
        assertNotEquals(standard.navFill, ultra.navFill)
        assertTrue("nav chrome must stay opaque", standard.navFill ushr 24 == 0xFF)
    }
}
