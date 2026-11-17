package io.github.nyzeep.orchestella.ui.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test

class MascotThemeTest {
    /**
     * UI-14 nail: the mascot ships as a light/dark pair sharing `familyId = "mascot"`.
     * Every role color must stay byte-identical to the values in the UI-14 ticket
     * (specs `issues/UI-14-mascot-theme.md`): the light half is a white field with
     * purple as the accent, the dark half is an independent purple-black design —
     * never an inversion of the light half. Hand-editing a hex here without
     * updating the ticket breaks this test on purpose.
     */
    @Test fun everyLightRoleColorMatchesTheU14TicketPalette() {
        val theme = ThemeCatalog.mascotLight
        assertEquals("background", 0xFFFFFFFF.toInt(), theme.background)
        assertEquals("surface", 0xFFF6F4F8.toInt(), theme.surface)
        assertEquals("surface translucency", 0xE6F6F4F8.toInt(), theme.surfaceTranslucent)
        assertEquals("primary", 0xFF5B4B78.toInt(), theme.primary)
        assertEquals("accent", 0xFF675775.toInt(), theme.accent)
        assertEquals("text", 0xFF1A1720.toInt(), theme.text)
        assertEquals("textDim", 0xFF5A5266.toInt(), theme.textDim)
        assertEquals("glow", 0xFF675775.toInt(), theme.glow)
        assertEquals("terminal background", 0xFF1C1A20.toInt(), theme.terminalBackground)
        assertEquals("terminal foreground", 0xFFFDF1DB.toInt(), theme.terminalForeground)
    }

    @Test fun everyDarkRoleColorMatchesTheU14TicketPalette() {
        val theme = ThemeCatalog.mascotDark
        assertEquals("background", 0xFF0E0C12.toInt(), theme.background)
        assertEquals("surface", 0xFF1A1620.toInt(), theme.surface)
        assertEquals("surface translucency", 0xE61A1620.toInt(), theme.surfaceTranslucent)
        assertEquals("primary", 0xFF9B87C4.toInt(), theme.primary)
        assertEquals("accent", 0xFFB9A6DC.toInt(), theme.accent)
        assertEquals("text", 0xFFEFEAF5.toInt(), theme.text)
        assertEquals("textDim", 0xFFA79FB5.toInt(), theme.textDim)
        assertEquals("glow", 0xFFB9A6DC.toInt(), theme.glow)
        assertEquals("terminal background", 0xFF0B0910.toInt(), theme.terminalBackground)
        assertEquals("terminal foreground", 0xFFEFEAF5.toInt(), theme.terminalForeground)
    }

    /** The dark half is an independent design: it must not be the light half inverted. */
    @Test fun darkPaletteIsAnIndependentDesignNotAnInversion() {
        val light = ThemeCatalog.mascotLight
        val dark = ThemeCatalog.mascotDark
        assertNotEquals("a straight inversion would put #000000 under the dark half",
            0xFF000000.toInt(), dark.background)
        assertNotEquals(light.surface, dark.surface)
        assertNotEquals(light.primary, dark.primary)
        assertNotEquals(light.accent, dark.accent)
        assertNotEquals(light.text, dark.text)
        fun redOf(color: Int) = color ushr 16 and 255
        fun blueOf(color: Int) = color and 255
        // Hue drift, not a chromatic inversion: the dark background keeps the
        // mascot's purple cast (blue channel above red) instead of neutral black.
        assertTrue("dark background is purple-tinted, not neutral black",
            blueOf(dark.background) > redOf(dark.background))
        assertTrue("dark primary is a lifted purple, not grey",
            ThemeColors.contrast(dark.primary, dark.background) > 3.0)
    }

    @Test fun theMascotShipsAsAPairedFamilyWithTwoBrightnesses() {
        assertEquals("mascot-light", ThemeCatalog.mascotLight.id)
        assertEquals("mascot-dark", ThemeCatalog.mascotDark.id)
        assertEquals("the pair shares one family", "mascot",
            ThemeCatalog.presetById("mascot-light")?.familyId)
        assertEquals("the pair shares one family", "mascot",
            ThemeCatalog.presetById("mascot-dark")?.familyId)
        assertFalse("the family pairs with the mode instead of pinning one brightness",
            ThemeCatalog.mascotLight.isFixed)
        assertFalse(ThemeCatalog.mascotDark.isFixed)
        assertFalse(ThemeCatalog.mascotLight.isDark)
        assertTrue(ThemeCatalog.mascotDark.isDark)
        assertEquals("看板娘 · 淡紫工控 · 亮", ThemeCatalog.mascotLight.selectionLabel)
        assertEquals("看板娘 · 淡紫工控 · 暗", ThemeCatalog.mascotDark.selectionLabel)
    }

    @Test fun mascotThemeLeadsThePickerAndIdsStayUnique() {
        assertEquals("mascot-light", ThemeCatalog.all.first().id)
        assertSame(ThemeCatalog.mascotLight, ThemeCatalog.all.first())
        assertEquals(ThemeCatalog.all.size, ThemeCatalog.all.map { it.id }.toSet().size)
        assertSame(ThemeCatalog.mascotLight, ThemeCatalog.resolve(ThemeCatalog.mascotLight.id))
        assertSame(ThemeCatalog.mascotDark, ThemeCatalog.resolve(ThemeCatalog.mascotDark.id))
    }

    /** UI-14 hard gate: WCAG relative-luminance floors on both halves of the family. */
    @Test fun textAndChromeRolesMeetTheirContrastFloorsOnBothHalves() {
        for (theme in listOf(ThemeCatalog.mascotLight, ThemeCatalog.mascotDark)) {
            fun assertFloor(first: Int, second: Int, minimum: Double, label: String) {
                val ratio = ThemeColors.contrast(first, second)
                assertTrue("${theme.id} $label: $ratio < $minimum", ratio >= minimum)
            }
            assertFloor(theme.text, theme.background, 4.5, "text/background")
            assertFloor(theme.text, theme.surface, 4.5, "text/surface")
            assertFloor(theme.textDim, theme.background, 4.5, "textDim/background")
            assertFloor(theme.textDim, theme.surface, 4.5, "textDim/surface")
            assertFloor(theme.terminalForeground, theme.terminalBackground, 4.5,
                "terminalForeground/terminalBackground")
            assertFloor(theme.accent, theme.background, 3.0, "accent/background")
            assertFloor(theme.primary, theme.background, 3.0, "primary/background")
        }
    }

    /** ANSI 0 (black) is a deliberately dark ink for the console; the other 15 must read clearly. */
    @Test fun everyAnsiSlotExceptBlackIsReadableOnEachHalfTerminalBackground() {
        for (theme in listOf(ThemeCatalog.mascotLight, ThemeCatalog.mascotDark)) {
            theme.ansi.drop(1).forEachIndexed { offset, color ->
                val index = offset + 1
                val ratio = ThemeColors.contrast(color, theme.terminalBackground)
                assertTrue("${theme.id} ansi[$index] 0x${Integer.toHexString(color)}: $ratio < 4.5",
                    ratio >= 4.5)
            }
        }
    }

    /** UI-14: the mascot family follows the mode instead of ignoring it. */
    @Test fun mascotFamilySwapsBetweenLightAndDarkForEitherSystemMode() {
        assertSame(
            ThemeCatalog.mascotDark,
            ThemeCatalog.resolveForMode(ThemeCatalog.mascotLight.id, isDark = true),
        )
        assertSame(
            ThemeCatalog.mascotLight,
            ThemeCatalog.resolveForMode(ThemeCatalog.mascotDark.id, isDark = false),
        )
        assertSame(
            ThemeCatalog.mascotLight,
            ThemeCatalog.resolveForMode(ThemeCatalog.mascotLight.id, isDark = false),
        )
        assertSame(
            ThemeCatalog.mascotDark,
            ThemeCatalog.resolveForMode(ThemeCatalog.mascotDark.id, isDark = true),
        )
    }

    /** Selecting either half pins that half's brightness, like every ordinary preset. */
    @Test fun selectingMascotLightPinsLightModeAndSurvivesADarkSystem() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setThemeId(ThemeCatalog.mascotLight.id)
        assertEquals(ThemeStore.Mode.LIGHT, store.mode())
        val resolved = store.resolveForMode(true)
        assertEquals(ThemeCatalog.mascotLight.id, resolved.id)
        assertFalse(resolved.isDark)
        assertEquals(ThemeCatalog.mascotLight, resolved)
        assertEquals(ThemeCatalog.mascotLight, store.resolveForMode(false))
    }

    /** Pre-UI-14 users stored the old single-form id; the upgrade must not lose their theme. */
    @Test fun legacyMascotLavenderUsersAreMigratedToMascotLightWithoutRewritingPrefs() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(ThemeStore.KEY_THEME_ID, "mascot-lavender").apply()
        val store = ThemeStore(prefs)
        assertEquals("the stored id maps forward on the read path", "mascot-light", store.themeId())
        assertEquals("the legacy preference itself is never rewritten",
            "mascot-lavender", prefs.getString(ThemeStore.KEY_THEME_ID, null))
        assertEquals(ThemeCatalog.mascotLight, store.resolveForMode(false))
    }

    /**
     * The old palette stored `theme_mode = fixed` because it owned its brightness.
     * Its successor is a paired family, so the stale pin decays to follow-system:
     * the migrated user keeps the light look on a light system and gains the dark
     * twin when the system goes dark.
     */
    @Test fun legacyFixedModePinDecaysToFollowSystemForMigratedMascotUsers() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString(ThemeStore.KEY_THEME_ID, "mascot-lavender")
            .putString(ThemeStore.KEY_THEME_MODE, ThemeStore.Mode.FIXED.value)
            .apply()
        val store = ThemeStore(prefs)
        assertEquals("the stale fixed pin is not user-authored; it follows the system now",
            ThemeStore.Mode.SYSTEM, store.mode())
        assertEquals("light system keeps the light look", ThemeCatalog.mascotLight,
            store.resolveForMode(false))
        assertEquals("dark system swaps to the dark twin", ThemeCatalog.mascotDark,
            store.resolveForMode(true))
        assertEquals("the legacy mode preference itself is never rewritten",
            ThemeStore.Mode.FIXED.value, prefs.getString(ThemeStore.KEY_THEME_MODE, null))
    }

    /** The FIXED machinery only ever served the old mascot; no shipped preset may pin it again. */
    @Test fun noShippedPresetIsFixedAnymore() {
        assertTrue(
            "UI-14 turned the last fixed palette into a paired family",
            ThemeCatalog.presets.none { it.isFixed },
        )
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
