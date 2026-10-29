package io.github.nyzeep.orchestella.ui.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class MascotThemeTest {
    /**
     * Nail for drift: every role color must stay byte-identical to the values measured by
     * `tools/branding/sample_palette.py`. Hand-editing a hex here without re-running the
     * script breaks this test on purpose.
     */
    @Test fun everyRoleColorMatchesTheMeasuredMascotPalette() {
        val theme = ThemeCatalog.mascotLavender
        assertEquals("boundary", 0xFFC2A8C7.toInt(), theme.background)
        assertEquals("boundary", 0xFFDCD0DF.toInt(), theme.surface)
        assertEquals("boundary translucency", 0xE6DCD0DF.toInt(), theme.surfaceTranslucent)
        assertEquals("boundary", 0xFF41375A.toInt(), theme.primary)
        assertEquals("boundary", 0xFF675775.toInt(), theme.accent)
        assertEquals("boundary", 0xFF1D1928.toInt(), theme.text)
        assertEquals("boundary", 0xFF4A3F57.toInt(), theme.textDim)
        assertEquals("boundary", 0xFF675775.toInt(), theme.glow)
        assertEquals("boundary", 0xFF242424.toInt(), theme.terminalBackground)
        assertEquals("boundary", 0xFFFDF1DB.toInt(), theme.terminalForeground)
    }

    @Test fun themeIsFixedLightAndCarriesACompleteAnsiPalette() {
        val theme = ThemeCatalog.mascotLavender
        assertEquals("mascot-lavender", theme.id)
        assertEquals("看板娘 · 淡紫工控", theme.displayName)
        assertTrue("the artwork has one brightness only", theme.isFixed)
        assertFalse("the lavender field is a light surface", theme.isDark)
        assertEquals(16, theme.ansi.size)
        assertEquals("看板娘 · 淡紫工控 · 固定", theme.selectionLabel)
    }

    @Test fun mascotThemeLeadsThePickerAndIdsStayUnique() {
        assertEquals("mascot-lavender", ThemeCatalog.all.first().id)
        assertSame(ThemeCatalog.mascotLavender, ThemeCatalog.all.first())
        assertEquals(ThemeCatalog.all.size, ThemeCatalog.all.map { it.id }.toSet().size)
        assertSame(ThemeCatalog.mascotLavender, ThemeCatalog.resolve(ThemeCatalog.mascotLavender.id))
    }

    @Test fun textAndChromeRolesMeetTheirContrastFloors() {
        val theme = ThemeCatalog.mascotLavender
        fun assertFloor(first: Int, second: Int, minimum: Double, label: String) {
            val ratio = ThemeColors.contrast(first, second)
            assertTrue("$label: $ratio < $minimum", ratio >= minimum)
        }
        assertFloor(theme.text, theme.background, 4.5, "text/background")
        assertFloor(theme.text, theme.surface, 4.5, "text/surface")
        assertFloor(theme.textDim, theme.background, 4.5, "textDim/background")
        assertFloor(theme.textDim, theme.surface, 4.5, "textDim/surface")
        assertFloor(theme.terminalForeground, theme.terminalBackground, 4.5, "terminalForeground/terminalBackground")
        assertFloor(theme.accent, theme.background, 3.0, "accent/background")
        assertFloor(theme.primary, theme.background, 3.0, "primary/background")
    }

    /** ANSI 0 (black) is a deliberately dark ink for the console; the other 15 must read clearly. */
    @Test fun everyAnsiSlotExceptBlackIsReadableOnTheTerminalBackground() {
        val theme = ThemeCatalog.mascotLavender
        theme.ansi.drop(1).forEachIndexed { offset, color ->
            val index = offset + 1
            val ratio = ThemeColors.contrast(color, theme.terminalBackground)
            assertTrue("ansi[$index] 0x${Integer.toHexString(color)}: $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test fun fixedThemeNeverSwapsVariantsForEitherSystemMode() {
        val id = ThemeCatalog.mascotLavender.id
        assertEquals(id, ThemeCatalog.resolveForMode(id, true).id)
        assertEquals(id, ThemeCatalog.resolveForMode(id, false).id)
        assertSame(ThemeCatalog.mascotLavender, ThemeCatalog.resolveForMode(id, true))
    }

    /** Hard evidence that a system dark change cannot reach the fixed palette. */
    @Test fun selectingMascotThemePinsFixedModeAndSurvivesADarkSystem() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setThemeId(ThemeCatalog.mascotLavender.id)
        assertEquals(ThemeStore.Mode.FIXED, store.mode())
        assertFalse("FIXED is chosen by a theme, never by the user", store.mode().isUserSelectable)
        val resolved = store.resolveForMode(true)
        assertEquals(ThemeCatalog.mascotLavender.id, resolved.id)
        assertFalse(resolved.isDark)
        assertEquals(ThemeCatalog.mascotLavender, resolved)
        assertEquals(ThemeCatalog.mascotLavender, store.resolveForMode(false))
    }

    /** Regression: FIXED must not leak into ordinary presets, which still pin DARK or LIGHT. */
    @Test fun ordinaryPresetsStillPinTheirOwnBrightness() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setThemeId(ThemeCatalog.constructivistWorkstation.id)
        assertEquals(ThemeStore.Mode.DARK, store.mode())
        assertTrue(store.resolveForMode(false).isDark)
        store.setThemeId(ThemeCatalog.claudeLight.id)
        assertEquals(ThemeStore.Mode.LIGHT, store.mode())
        assertFalse(store.resolveForMode(true).isDark)
        assertEquals(ThemeCatalog.claudeLight, store.resolveForMode(true))
    }
}
