package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
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
        val resolved = Oe3Colors.resolve(dark)
        val colors = homePreviewColors(dark)
        assertEquals(resolved.background, colors.background)
        assertEquals(resolved.surface, colors.surface)
        assertEquals(resolved.primary, colors.primary)
        assertEquals(resolved.onSurface, colors.onSurface)
        assertEquals(resolved.accent, colors.accent)
        assertEquals(resolved.surfaceHigh, colors.ringTrack)
    }

    @Test
    fun lightThemeMapsEveryRoleFromTheResolvedPalette() {
        val resolved = Oe3Colors.resolve(light)
        val colors = homePreviewColors(light)
        assertEquals(resolved.background, colors.background)
        assertEquals(resolved.surface, colors.surface)
        assertEquals(resolved.primary, colors.primary)
        assertEquals(resolved.onSurface, colors.onSurface)
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
        val resolved = Oe3Colors.resolve(dark)
        val colors = homePreviewColors(dark, opacityPercent = 55)
        assertEquals(140, colors.surface ushr 24)
        assertEquals(resolved.surface and 0x00FFFFFF, colors.surface and 0x00FFFFFF)
        assertEquals(resolved.background, colors.background)
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
