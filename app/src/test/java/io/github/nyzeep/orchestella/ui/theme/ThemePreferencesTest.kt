package io.github.nyzeep.orchestella.ui.theme

import android.content.SharedPreferences
import io.github.nyzeep.orchestella.FakeSharedPreferences
import io.github.nyzeep.orchestella.SettingsManager
import org.junit.Assert.*
import org.junit.Test

class ThemePreferencesTest {
    @Test fun freshAndUnknownThemeIdsUseCatalogDefaultWithoutRewritingUserData() {
        val prefs = FakeSharedPreferences()
        assertEquals(ThemeCatalog.constructivistWorkstation, ThemeStore(prefs).resolve())
        prefs.edit().putString(ThemeStore.KEY_THEME_ID, "removed-theme").putString("instance", "keep-me").apply()
        assertEquals(ThemeCatalog.constructivistWorkstation.id, ThemeStore(prefs).themeId())
        assertEquals("removed-theme", prefs.getString(ThemeStore.KEY_THEME_ID, null))
        assertEquals("keep-me", prefs.getString("instance", null))
    }

    @Test fun themeSelectionPersistsAcrossStoreInstancesAndPreservesWallpaperTypographyAndSettings() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        val settings = SettingsManager(prefs)
        settings.lastActiveInstanceId = "existing-instance"
        settings.isTerminalCursorBlinkEnabled = false
        store.setBackgroundUri("content://test/images/42")
        store.setTerminalFontSizeSp(17)
        store.setTerminalLetterSpacingEm(0.12f)
        store.setTerminalLineSpacingPx(4f)
        store.setCustomAccent(0xFF123456.toInt())
        store.setCustomText(0xFFEEDDCC.toInt())
        store.setThemeId(ThemeCatalog.claudeLight.id)
        val restored = ThemeStore(prefs)
        assertEquals(ThemeCatalog.claudeLight, restored.resolve())
        assertNull(restored.customAccent())
        assertNull(restored.customText())
        assertEquals("content://test/images/42", restored.backgroundUri())
        assertEquals(17, restored.terminalFontSizeSp())
        assertEquals(0.12f, restored.terminalLetterSpacingEm(), 0f)
        assertEquals(4f, restored.terminalLineSpacingPx(), 0f)
        assertEquals("existing-instance", SettingsManager(prefs).lastActiveInstanceId)
        assertFalse(SettingsManager(prefs).isTerminalCursorBlinkEnabled)
    }

    @Test fun customAccentControlsButtonsNavigationAndGlowWithoutReplacingTerminalAnsi() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setThemeId(ThemeCatalog.github.id)
        store.setCustomAccent(0x00123456)
        val theme = store.resolve()
        assertEquals(0xFF123456.toInt(), theme.primary)
        assertEquals(theme.primary, theme.accent)
        assertEquals(theme.primary, theme.glow)
        assertEquals(ThemeCatalog.github.ansi, theme.ansi)
        store.setCustomAccent(null)
        assertEquals(ThemeCatalog.github, store.resolve())
    }

    @Test fun invalidThemeSelectionAndNonLocalWallpaperCannotReplaceSavedPreferences() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setThemeId(ThemeCatalog.raycast.id)
        store.setThemeId("unknown")
        assertEquals(ThemeCatalog.raycast, store.resolve())
        store.setBackgroundUri("content://test/images/1")
        for (uri in listOf("https://example.test/image", "file:///private/image")) {
            try {
                store.setBackgroundUri(uri)
                fail("Non-picked background URI must be rejected")
            } catch (_: IllegalArgumentException) {
                assertEquals("content://test/images/1", store.backgroundUri())
            }
        }
    }

    @Test fun malformedTypographyIsBoundedAndOpeningSettingsCannotChangeStoredFont() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        for (sp in 10..22) {
            store.setTerminalFontSizeSp(sp)
            assertEquals(sp, ThemeColors.fontSize(ThemeColors.fontProgress(store.terminalFontSizeSp())))
        }
        store.setTerminalFontSizeSp(200)
        assertEquals(22, store.terminalFontSizeSp())
        prefs.edit().putInt(ThemeStore.KEY_TERMINAL_FONT_SP, -1)
            .putFloat(ThemeStore.KEY_TERMINAL_LETTER_SPACING, Float.NaN)
            .putFloat(ThemeStore.KEY_TERMINAL_LINE_SPACING, Float.POSITIVE_INFINITY).apply()
        assertEquals(10, store.terminalFontSizeSp())
        assertEquals(0f, store.terminalLetterSpacingEm(), 0f)
        assertEquals(0f, store.terminalLineSpacingPx(), 0f)
    }

    @Test fun listenersReceiveEffectiveSnapshotAndStopAfterRemoval() {
        val prefs = ObservablePreferences()
        val store = ThemeStore(prefs)
        var snapshots = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            snapshots++
            assertEquals(ThemeCatalog.gruvbox, store.resolve())
        }
        store.setCustomAccent(0xFFFF0000.toInt())
        store.addOnThemeChangeListener(listener)
        store.setThemeId(ThemeCatalog.gruvbox.id)
        assertTrue(snapshots > 0)
        store.removeOnThemeChangeListener(listener)
        val before = snapshots
        store.setThemeId(ThemeCatalog.raycast.id)
        assertEquals(before, snapshots)
    }

    @Test fun cursorPreferencePersistsAndNotifiesWithoutTouchingInstanceAndOnboardingData() {
        val prefs = ObservablePreferences()
        val settings = SettingsManager(prefs)
        settings.lastActiveInstanceId = "keep-instance"
        settings.isOnboardingCompleted = true
        assertTrue(settings.isTerminalCursorBlinkEnabled)
        val changes = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changes += key }
        settings.addOnSettingsChangeListener(listener)
        settings.isTerminalCursorBlinkEnabled = false
        assertEquals(listOf(SettingsManager.KEY_TERMINAL_CURSOR_BLINK), changes)
        assertFalse(SettingsManager(prefs).isTerminalCursorBlinkEnabled)
        assertEquals("keep-instance", settings.lastActiveInstanceId)
        assertTrue(settings.isOnboardingCompleted)
        settings.removeOnSettingsChangeListener(listener)
        settings.isTerminalCursorBlinkEnabled = true
        assertEquals(1, changes.size)
    }

    /** Exercises the registration seam without an emulator, Robolectric or Android Context. */
    private class ObservablePreferences(private val backing: FakeSharedPreferences = FakeSharedPreferences()) :
        SharedPreferences by backing {
        private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners += listener
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners -= listener
        }
        override fun edit(): SharedPreferences.Editor {
            val before = backing.all
            val editor = backing.edit()
            fun notifyChanges() {
                val after = backing.all
                (before.keys + after.keys).filter { before[it] != after[it] }.forEach { key ->
                    listeners.toList().forEach { it.onSharedPreferenceChanged(this, key) }
                }
            }
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    editor.putString(key, value); return this
                }
                override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                    editor.putInt(key, value); return this
                }
                override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                    editor.putFloat(key, value); return this
                }
                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                    editor.putBoolean(key, value); return this
                }
                override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
                override fun apply() { editor.apply(); notifyChanges() }
                override fun commit(): Boolean = editor.commit().also { if (it) notifyChanges() }
            }
        }
    }
}
