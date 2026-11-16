package io.github.nyzeep.orchestella.ui.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI3-13: the four Theme Studio presentation options become real preferences.
 * Each key follows the ThemeStore contract: a fresh file behaves like the
 * shipped default, a write survives a new store instance (the "leave the page
 * and come back" path), and corrupt or unknown values fall back instead of
 * crashing or being honoured.
 */
class ThemeStoreUiOptionsTest {

    @Test
    fun freshPreferenceFileStartsAtTheShippedDefaults() {
        val store = ThemeStore(FakeSharedPreferences())
        assertFalse(store.uiPureBlack())
        assertEquals("none", store.uiGlassLevel())
        assertEquals("floating", store.uiNavStyle())
        assertEquals("system", store.uiFontChoice())
    }

    @Test
    fun everyOptionRoundTripsThroughANewStoreInstance() {
        val prefs = FakeSharedPreferences()
        ThemeStore(prefs).apply {
            setUiPureBlack(true)
            setUiGlassLevel("ultra")
            setUiNavStyle("fixed")
            setUiFontChoice("mono")
        }
        val reread = ThemeStore(prefs)
        assertTrue(reread.uiPureBlack())
        assertEquals("ultra", reread.uiGlassLevel())
        assertEquals("fixed", reread.uiNavStyle())
        assertEquals("mono", reread.uiFontChoice())

        ThemeStore(prefs).apply {
            setUiPureBlack(false)
            setUiGlassLevel("standard")
            setUiNavStyle("floating")
            setUiFontChoice("system")
        }
        assertFalse(reread.uiPureBlack())
        assertEquals("standard", reread.uiGlassLevel())
        assertEquals("floating", reread.uiNavStyle())
        assertEquals("system", reread.uiFontChoice())
    }

    @Test
    fun glassAcceptsExactlyNoneStandardAndUltra() {
        val store = ThemeStore(FakeSharedPreferences())
        listOf("none", "standard", "ultra").forEach { level ->
            store.setUiGlassLevel(level)
            assertEquals(level, store.uiGlassLevel())
        }
    }

    @Test
    fun unknownOrCorruptGlassValuesFallBackToNone() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        // The setter coerces before writing, in the house coerceIn style.
        store.setUiGlassLevel("bogus")
        assertEquals("none", store.uiGlassLevel())
        assertEquals("none", prefs.getString(ThemeStore.KEY_UI_GLASS_LEVEL, null))
        // A corrupt file (written by anything else) reads back as none too.
        prefs.edit().putString(ThemeStore.KEY_UI_GLASS_LEVEL, "bogus").apply()
        assertEquals("none", store.uiGlassLevel())
    }

    @Test
    fun unknownOrCorruptNavStylesFallBackToFloating() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setUiNavStyle("bogus")
        assertEquals("floating", store.uiNavStyle())
        assertEquals("floating", prefs.getString(ThemeStore.KEY_UI_NAV_STYLE, null))
        prefs.edit().putString(ThemeStore.KEY_UI_NAV_STYLE, "bogus").apply()
        assertEquals("floating", store.uiNavStyle())
        store.setUiNavStyle("fixed")
        assertEquals("fixed", store.uiNavStyle())
    }

    @Test
    fun fontChoiceAcceptsOnlySystemAndMono() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setUiFontChoice("mono")
        assertEquals("mono", store.uiFontChoice())
        store.setUiFontChoice("system")
        assertEquals("system", store.uiFontChoice())
    }

    @Test
    fun importedFontChoiceIsNeverPersisted() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setUiFontChoice("mono")
        // Font import does not exist yet (UI3-13): "imported" must never reach
        // the file, so no unloadable font can come back on the next launch.
        store.setUiFontChoice("imported")
        assertEquals("system", store.uiFontChoice())
        assertEquals("system", prefs.getString(ThemeStore.KEY_UI_FONT_CHOICE, null))
        prefs.edit().putString(ThemeStore.KEY_UI_FONT_CHOICE, "imported").apply()
        assertEquals("system", store.uiFontChoice())
    }

    @Test
    fun legacyFilesWithoutTheNewKeysBehaveExactlyAsBefore() {
        val prefs = FakeSharedPreferences()
        // A pre-UI3-13 file: existing theme keys only, no ui_* presentation keys.
        prefs.edit()
            .putString(ThemeStore.KEY_THEME_ID, "github")
            .putString(ThemeStore.KEY_THEME_MODE, "dark")
            .putInt(ThemeStore.KEY_CORNER_RADIUS_DP, 20)
            .apply()
        val store = ThemeStore(prefs)
        // Reading the studio options touches nothing and reports the defaults.
        assertFalse(store.uiPureBlack())
        assertEquals("none", store.uiGlassLevel())
        assertEquals("floating", store.uiNavStyle())
        assertEquals("system", store.uiFontChoice())
        assertFalse(prefs.contains(ThemeStore.KEY_UI_PURE_BLACK))
        assertFalse(prefs.contains(ThemeStore.KEY_UI_GLASS_LEVEL))
        assertFalse(prefs.contains(ThemeStore.KEY_UI_NAV_STYLE))
        assertFalse(prefs.contains(ThemeStore.KEY_UI_FONT_CHOICE))
        // And a later write of the new options leaves the legacy data intact.
        store.setUiPureBlack(true)
        store.setUiFontChoice("mono")
        assertEquals("github", store.themeId())
        assertEquals(ThemeStore.Mode.DARK, store.mode())
        assertEquals(20, store.cornerRadiusDp())
    }
}
