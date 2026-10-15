package io.github.nyzeep.orchestella.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import io.github.nyzeep.orchestella.FakeSharedPreferences
import io.github.nyzeep.orchestella.SettingsManager
import org.junit.Assert.*
import org.junit.Test

class ThemePreferencesTest {
    @Test fun liveSystemModeReadsApplicationConfigurationInsteadOfActivityNightOverride() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(ThemeStore.KEY_THEME_ID, ThemeCatalog.github.id).apply()
        val store = ThemeStore(prefs)
        val system = ModeContext(isDark = true)
        val activity = ModeContext(isDark = false, system = system)
        assertEquals(ThemeCatalog.github, store.resolve(activity))
        system.modeConfiguration.uiMode = Configuration.UI_MODE_NIGHT_NO
        assertEquals(ThemeCatalog.githubLight, store.resolve(activity))
        assertEquals("live resolution must not read the Activity's creation-time override", 0, activity.resourceReads)
    }

    @Test fun returningToSystemModeUsesExternalSystemOnTheSameContextWithoutResettingPreferences() {
        val store = ThemeStore(FakeSharedPreferences())
        val system = ModeContext(isDark = true)
        val activity = ModeContext(isDark = false, system = system)
        store.setThemeId(ThemeCatalog.claudeLight.id)
        store.setTerminalFontSizeSp(18)
        store.setCustomAccent(0xFF123456.toInt())
        assertFalse(store.resolve(activity).isDark)
        store.setMode(ThemeStore.Mode.SYSTEM)
        val resolved = store.resolve(activity)
        assertTrue(resolved.isDark)
        assertEquals(ThemeCatalog.claudeDark.terminalBackground, resolved.terminalBackground)
        assertEquals(0xFF123456.toInt(), resolved.primary)
        assertEquals(18, store.terminalFontSizeSp())
        assertEquals(ThemeCatalog.claudeLight.id, store.themeId())
        assertEquals(0, activity.resourceReads)
    }

    @Test fun selectingLightPresetOverridesDarkSystemForTerminalAndPageActions() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setThemeId(ThemeCatalog.constructivistAmberLight.id)
        val resolved = store.resolveForMode(true)
        assertEquals("selected light terminal survives a dark system", ThemeCatalog.constructivistAmberLight.terminalBackground,
            resolved.terminalBackground)
        assertEquals("preset and page actions share light primary", ThemeCatalog.constructivistAmberLight.primary,
            resolved.primary)
        assertFalse(resolved.isDark)
    }

    @Test fun selectingDarkPresetOverridesLightSystemAndPreservesItsAnsiPalette() {
        val store = ThemeStore(FakeSharedPreferences())
        store.setThemeId(ThemeCatalog.github.id)
        val resolved = store.resolveForMode(false)
        assertEquals(ThemeCatalog.github.terminalBackground, resolved.terminalBackground)
        assertEquals(ThemeCatalog.github.primary, resolved.primary)
        assertEquals(ThemeCatalog.github.ansi, resolved.ansi)
        assertTrue(resolved.isDark)
    }

    @Test fun legacyAndUnknownModePreferencesFollowSystemWithoutRewritingSavedTheme() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString(ThemeStore.KEY_THEME_ID, ThemeCatalog.github.id).apply()
        val store = ThemeStore(prefs)
        assertEquals(ThemeStore.Mode.SYSTEM, store.mode())
        assertEquals(ThemeCatalog.githubLight, store.resolveForMode(false))
        assertEquals(ThemeCatalog.github, store.resolveForMode(true))
        assertFalse(prefs.contains(ThemeStore.KEY_THEME_MODE))
        prefs.edit().putString(ThemeStore.KEY_THEME_MODE, "obsolete-mode").apply()
        assertEquals(ThemeStore.Mode.SYSTEM, store.mode())
        assertEquals(ThemeCatalog.githubLight, store.resolveForMode(false))
        assertEquals("obsolete-mode", prefs.getString(ThemeStore.KEY_THEME_MODE, null))
        assertEquals(ThemeCatalog.github.id, store.themeId())
    }

    @Test fun everyPresetCanOverrideEitherSystemModeAndReturnToFollowingSystem() {
        for (preset in ThemeCatalog.all) {
            val prefs = FakeSharedPreferences()
            val store = ThemeStore(prefs)
            store.setThemeId(preset.id)
            assertEquals(preset, store.resolveForMode(true))
            assertEquals(preset, store.resolveForMode(false))
            assertEquals(if (preset.isDark) ThemeStore.Mode.DARK else ThemeStore.Mode.LIGHT, store.mode())
            val restored = ThemeStore(prefs)
            assertEquals(preset, restored.resolveForMode(!preset.isDark))
            restored.setMode(ThemeStore.Mode.SYSTEM)
            for (systemDark in listOf(false, true)) {
                assertEquals(ThemeCatalog.resolveForMode(preset.id, systemDark), restored.resolveForMode(systemDark))
            }
            assertEquals(preset.id, restored.themeId())
        }
    }

    @Test fun changingOnlyModePreservesCustomColorsWallpaperTypographyAndInstancePreferences() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setThemeId(ThemeCatalog.catppuccin.id)
        store.setCustomAccent(0xFF123456.toInt())
        store.setCustomText(0xFF654321.toInt())
        store.setBackgroundUri("content://test/images/42")
        store.setTerminalFontSizeSp(18)
        store.setTerminalLetterSpacingEm(0.10f)
        store.setTerminalLineSpacingPx(3f)
        SettingsManager(prefs).lastActiveInstanceId = "keep-instance"
        for (mode in ThemeStore.Mode.entries) {
            store.setMode(mode)
            for (systemDark in listOf(false, true)) {
                val resolved = store.resolveForMode(systemDark)
                assertEquals(0xFF123456.toInt(), resolved.primary)
                assertEquals(0xFF654321.toInt(), resolved.text)
                assertEquals(resolved.text, resolved.terminalForeground)
                assertEquals(if (resolved.isDark) ThemeCatalog.catppuccin.ansi else ThemeCatalog.catppuccinLatte.ansi, resolved.ansi)
                assertEquals(if (mode == ThemeStore.Mode.SYSTEM) systemDark else mode == ThemeStore.Mode.DARK, resolved.isDark)
            }
            assertEquals(mode, ThemeStore(prefs).mode())
            assertEquals("content://test/images/42", store.backgroundUri())
            assertEquals(18, store.terminalFontSizeSp())
            assertEquals(0.10f, store.terminalLetterSpacingEm(), 0f)
            assertEquals(3f, store.terminalLineSpacingPx(), 0f)
            assertEquals("keep-instance", SettingsManager(prefs).lastActiveInstanceId)
        }
    }

    @Test fun modeUpdatesNotifyObserversWithTheEffectivePaletteAndCanBeRemoved() {
        val prefs = ObservablePreferences()
        val store = ThemeStore(prefs)
        store.setThemeId(ThemeCatalog.claudeDark.id)
        val snapshots = mutableListOf<AppTheme>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> snapshots += store.resolveForMode(true) }
        store.addOnThemeChangeListener(listener)
        store.setMode(ThemeStore.Mode.LIGHT)
        assertEquals(listOf(ThemeCatalog.claudeLight), snapshots)
        store.removeOnThemeChangeListener(listener)
        store.setMode(ThemeStore.Mode.DARK)
        assertEquals(1, snapshots.size)
    }

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

    /** Only the configuration boundary is faked; tests execute the real resolve(Context) path. */
    @Suppress("DEPRECATION")
    private class ModeContext(isDark: Boolean, private val system: Context? = null) : ContextWrapper(null) {
        val modeConfiguration = Configuration().apply {
            uiMode = if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        var resourceReads = 0
        private val modeResources = object : Resources(null, null, null) {
            override fun getConfiguration(): Configuration = modeConfiguration
        }
        override fun getApplicationContext(): Context = system ?: this
        override fun getResources(): Resources {
            resourceReads++
            return modeResources
        }
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
