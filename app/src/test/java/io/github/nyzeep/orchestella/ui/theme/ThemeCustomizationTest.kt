package io.github.nyzeep.orchestella.ui.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test

/**
 * JVM tests for the theme customization trio: three editable custom theme slots,
 * the global button corner-radius token, and the terminal background image keys.
 * All storage goes through flat preference keys so no org.json/android.util is needed.
 */
class ThemeCustomizationTest {
    private fun darkSlot(name: String = "夜航") = CustomThemeSlot(
        name = name,
        isDark = true,
        background = 0xFF0D0E11.toInt(),
        surface = 0xFF16171D.toInt(),
        primary = 0xFFD62828.toInt(),
        accent = 0xFFFDBB24.toInt(),
        text = 0xFFF5F4F0.toInt(),
        textDim = 0xFF8E929B.toInt(),
        terminalBackground = 0xFF07080A.toInt(),
        terminalForeground = 0xFFF1F2F5.toInt(),
    )

    private fun lightSlot(name: String = "晨光") = CustomThemeSlot(
        name = name,
        isDark = false,
        background = 0xFFF5F4F0.toInt(),
        surface = 0xFFEAE8E2.toInt(),
        primary = 0xFF0047AB.toInt(),
        accent = 0xFFC2410C.toInt(),
        text = 0xFF16171D.toInt(),
        textDim = 0xFF5F6368.toInt(),
        terminalBackground = 0xFFF8F7F4.toInt(),
        terminalForeground = 0xFF16171D.toInt(),
    )

    // --- E1: custom theme slots ---

    @Test fun customThemeSlotsStartEmpty() {
        val store = ThemeStore(FakeSharedPreferences())
        assertEquals(listOf<CustomThemeSlot?>(null, null, null), store.customSlots())
        assertNull(store.resolveCustomSlot("custom:1"))
    }

    @Test fun customSlotRoundTripsNameAndFullPalette() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setCustomSlot(0, darkSlot())
        val slots = ThemeStore(prefs).customSlots()
        assertEquals(darkSlot(), slots[0])
        assertNull(slots[1])
        assertNull(slots[2])
        store.setCustomSlot(2, lightSlot())
        assertEquals(lightSlot(), ThemeStore(prefs).customSlots()[2])
        assertEquals(darkSlot(), ThemeStore(prefs).customSlots()[0])
    }

    @Test fun setCustomSlotOutOfRangeIndexIsRejected() {
        val store = ThemeStore(FakeSharedPreferences())
        for (index in listOf(-1, 3)) {
            try {
                store.setCustomSlot(index, darkSlot())
                fail("Slot index $index must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun setThemeIdCustomAppliesSlotModeAndKeepsCustomId() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setCustomSlot(0, darkSlot())
        store.setCustomSlot(1, lightSlot())
        store.setThemeId("custom:1")
        assertEquals("custom:1", store.themeId())
        assertEquals(ThemeStore.Mode.DARK, store.mode())
        store.setThemeId("custom:2")
        assertEquals("custom:2", store.themeId())
        assertEquals(ThemeStore.Mode.LIGHT, store.mode())
    }

    @Test fun setThemeIdCustomWithoutSlotDataIsIgnored() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setThemeId(ThemeCatalog.raycast.id)
        store.setThemeId("custom:3")
        assertEquals("empty slots must not become the active theme", ThemeCatalog.raycast.id, store.themeId())
    }

    @Test fun selectingPresetKeepsCustomSlotsIntact() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setCustomSlot(0, darkSlot())
        store.setCustomAccent(0xFF123456.toInt())
        store.setThemeId(ThemeCatalog.constructivistWorkstation.id)
        assertEquals("preset selection must not clear saved slots", darkSlot(), store.customSlots()[0])
        assertNull("existing custom-accent reset contract is preserved", store.customAccent())
    }

    @Test fun resolveCustomSlotReturnsNullForEmptyCorruptAndOutOfRange() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setCustomSlot(0, darkSlot())
        assertNull(store.resolveCustomSlot("custom:2"))
        assertNull(store.resolveCustomSlot("custom:9"))
        assertNull(store.resolveCustomSlot("custom:abc"))
        assertNull(store.resolveCustomSlot(ThemeCatalog.github.id))
        prefs.edit().putString("custom_slot_2_name", "残缺").apply()
        assertNull("slot missing palette keys is corrupt, not partial", store.resolveCustomSlot("custom:2"))
    }

    @Test fun customThemeResolvesSlotPaletteDerivedGlowAndDefaultAnsi() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setCustomSlot(0, darkSlot())
        store.setThemeId("custom:1")
        for (systemDark in listOf(false, true)) {
            val theme = store.resolveForMode(systemDark)
            assertEquals("custom:1", theme.id)
            assertEquals("夜航", theme.displayName)
            assertTrue("slot carries its own darkness regardless of system mode", theme.isDark)
            assertEquals(0xFF0D0E11.toInt(), theme.background)
            assertEquals(0xFF16171D.toInt(), theme.surface)
            assertEquals(0xFFD62828.toInt(), theme.primary)
            assertEquals(0xFFFDBB24.toInt(), theme.accent)
            assertEquals(0xFFF5F4F0.toInt(), theme.text)
            assertEquals(0xFF8E929B.toInt(), theme.textDim)
            assertEquals(0xFF07080A.toInt(), theme.terminalBackground)
            assertEquals(0xFFF1F2F5.toInt(), theme.terminalForeground)
            assertEquals(
                "glow derives from primary blended into surface",
                ThemeColors.blend(0xFFD62828.toInt(), 0xFF16171D.toInt(), 0.5f), theme.glow,
            )
            assertEquals(0xE6, (theme.surfaceTranslucent ushr 24) and 0xFF)
            assertEquals(ThemeCatalog.defaultAnsi(true), theme.ansi)
            assertEquals(16, theme.ansi.size)
        }
        store.setCustomSlot(0, lightSlot())
        val light = store.resolveForMode(true)
        assertFalse(light.isDark)
        assertEquals(ThemeCatalog.defaultAnsi(false), light.ansi)
    }

    @Test fun clearingActiveSlotFallsBackToCatalogDefaultWithoutRewritingId() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setCustomSlot(0, darkSlot())
        store.setThemeId("custom:1")
        store.setCustomSlot(0, null)
        assertEquals("custom:1", prefs.getString(ThemeStore.KEY_THEME_ID, null))
        assertEquals(ThemeCatalog.constructivistWorkstation, store.resolveForMode(false))
        assertNull(store.resolveCustomSlot("custom:1"))
    }

    // --- E3: global button corner radius token ---

    @Test fun cornerRadiusDefaultsToZero() {
        val store = ThemeStore(FakeSharedPreferences())
        assertEquals(0, store.cornerRadiusDp())
    }

    @Test fun cornerRadiusClampsToTheZeroThroughTwentyFourRange() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setCornerRadiusDp(8)
        assertEquals(8, store.cornerRadiusDp())
        store.setCornerRadiusDp(30)
        assertEquals(24, store.cornerRadiusDp())
        store.setCornerRadiusDp(-3)
        assertEquals(0, store.cornerRadiusDp())
    }

    @Test fun cornerRadiusPersistsAcrossStoreInstances() {
        val prefs = FakeSharedPreferences()
        ThemeStore(prefs).setCornerRadiusDp(16)
        assertEquals(16, ThemeStore(prefs).cornerRadiusDp())
    }

    // --- E2: terminal background image keys ---

    @Test fun terminalBackgroundUriRejectsNonContentUris() {
        val store = ThemeStore(FakeSharedPreferences())
        for (uri in listOf("https://example.test/image", "file:///private/image", "/storage/bg.png")) {
            try {
                store.setTerminalBackgroundUri(uri)
                fail("Non-picked terminal background URI must be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertNull(store.terminalBackgroundUri())
    }

    @Test fun terminalBackgroundUriAcceptsPickedUriAndClearsToNull() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setTerminalBackgroundUri("content://test/images/7")
        assertEquals("content://test/images/7", ThemeStore(prefs).terminalBackgroundUri())
        store.setTerminalBackgroundUri(null)
        assertNull(ThemeStore(prefs).terminalBackgroundUri())
    }

    @Test fun terminalScrimAlphaDefaultsToOpaqueScrimAndClampsToByteRange() {
        val store = ThemeStore(FakeSharedPreferences())
        assertEquals(0xB0, store.terminalScrimAlpha())
        store.setTerminalScrimAlpha(300)
        assertEquals(255, store.terminalScrimAlpha())
        store.setTerminalScrimAlpha(-5)
        assertEquals(0, store.terminalScrimAlpha())
        store.setTerminalScrimAlpha(230)
        assertEquals(230, store.terminalScrimAlpha())
    }

    @Test fun terminalScrimArgbCombinesThemeTerminalBackgroundWithAlpha() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setTerminalScrimAlpha(0xB0)
        val argb = store.terminalScrimArgb(ThemeCatalog.constructivistWorkstation)
        assertEquals(0xB0, (argb ushr 24) and 0xFF)
        assertEquals(ThemeCatalog.constructivistWorkstation.terminalBackground and 0x00FFFFFF, argb and 0x00FFFFFF)
    }

    @Test fun terminalBackgroundKeysSurviveThemeSwitchesAndStoreRecreation() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setCustomSlot(0, darkSlot())
        store.setTerminalBackgroundUri("content://test/images/9")
        store.setTerminalScrimAlpha(120)
        store.setCornerRadiusDp(8)
        store.setThemeId(ThemeCatalog.claudeDark.id)
        val restored = ThemeStore(prefs)
        assertEquals("content://test/images/9", restored.terminalBackgroundUri())
        assertEquals(120, restored.terminalScrimAlpha())
        assertEquals(8, restored.cornerRadiusDp())
        assertEquals(darkSlot(), restored.customSlots()[0])
        restored.setThemeId("custom:1")
        assertEquals("content://test/images/9", restored.terminalBackgroundUri())
    }
}
