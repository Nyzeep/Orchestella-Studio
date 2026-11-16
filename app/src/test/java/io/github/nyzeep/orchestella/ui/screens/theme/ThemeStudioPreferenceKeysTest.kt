package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import io.github.nyzeep.orchestella.ui.design.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Theme Studio is not allowed to grow the preference schema. This sweeps
 * every event that persists something and checks the resulting keys are all
 * ones ThemeStore already owned.
 */
class ThemeStudioPreferenceKeysTest {

    @Test
    fun everyPersistingEventWritesOnlyExistingThemeStoreKeys() {
        val prefs = FakeSharedPreferences()
        val controller = ThemeStudioController(ThemeStore(prefs))
        val slot = CustomThemeSlot(
            name = "测试",
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
        listOf(
            ThemeStudioEvent.SelectTheme(ThemeCatalog.github.id),
            ThemeStudioEvent.SelectMode(ThemeStore.Mode.DARK),
            ThemeStudioEvent.SetCornerDp(20),
            ThemeStudioEvent.SetTextScaleMode(OeTextScaleMode.Custom),
            ThemeStudioEvent.SetTextScalePercent(120),
            ThemeStudioEvent.SetSurfaceOpacity(55),
            ThemeStudioEvent.SetCustomAccent(0xFF123456.toInt()),
            ThemeStudioEvent.SaveCustomSlot(0, slot, apply = true),
            ThemeStudioEvent.SetTerminalFontSizeSp(18),
            ThemeStudioEvent.SetTerminalLetterSpacing(0.1f),
            ThemeStudioEvent.SetTerminalLineSpacing(4f),
            ThemeStudioEvent.SetTerminalScrimAlpha(128),
            ThemeStudioEvent.ClearBackground,
            ThemeStudioEvent.ClearTerminalBackground,
        ).forEach { controller.handle(it) }

        val slotKeys = (1..CustomThemeSlot.SLOT_COUNT).flatMap { slotNumber ->
            listOf(
                "name", "dark", "bg", "surface", "primary",
                "accent", "text", "text_dim", "term_bg", "term_fg",
            ).map { "${ThemeStore.KEY_CUSTOM_SLOT_PREFIX}${slotNumber}_$it" }
        }
        val existingKeys = setOf(
            ThemeStore.KEY_THEME_ID,
            ThemeStore.KEY_THEME_MODE,
            ThemeStore.KEY_CUSTOM_ACCENT,
            ThemeStore.KEY_CUSTOM_TEXT,
            ThemeStore.KEY_BACKGROUND_URI,
            ThemeStore.KEY_TERMINAL_BACKGROUND_URI,
            ThemeStore.KEY_TERMINAL_SCRIM_ALPHA,
            ThemeStore.KEY_UI_SURFACE_OPACITY,
            ThemeStore.KEY_CORNER_RADIUS_DP,
            ThemeStore.KEY_TEXT_SCALE_MODE,
            ThemeStore.KEY_TEXT_SCALE_PERCENT,
            ThemeStore.KEY_TERMINAL_FONT_SP,
            ThemeStore.KEY_TERMINAL_LETTER_SPACING,
            ThemeStore.KEY_TERMINAL_LINE_SPACING,
        ) + slotKeys

        assertTrue(
            "Theme Studio invented preference keys: ${prefs.all.keys - existingKeys}",
            existingKeys.containsAll(prefs.all.keys),
        )
        assertTrue(prefs.all.isNotEmpty())
    }
}
