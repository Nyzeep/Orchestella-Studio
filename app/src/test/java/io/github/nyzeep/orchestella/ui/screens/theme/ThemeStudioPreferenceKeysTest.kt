package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Theme Studio may only write the preference keys ThemeStore owns. This
 * sweeps every event that persists something and checks the resulting keys are
 * all whitelisted: the schema grew exactly once for UI3-13 (pure black, glass,
 * bottom-bar style, app font) and nothing else may join it.
 */
class ThemeStudioPreferenceKeysTest {

    @Test
    fun everyPersistingEventWritesOnlyWhitelistedThemeStoreKeys() {
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
            ThemeStudioEvent.SetPureBlack(true),
            ThemeStudioEvent.SetGlass(GlassOption.Ultra),
            ThemeStudioEvent.SetNavStyle(NavStyleOption.Fixed),
            ThemeStudioEvent.SetFontChoice(FontChoice.Mono),
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
        val whitelistedKeys = setOf(
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
            ThemeStore.KEY_UI_PURE_BLACK,
            ThemeStore.KEY_UI_GLASS_LEVEL,
            ThemeStore.KEY_UI_NAV_STYLE,
            ThemeStore.KEY_UI_FONT_CHOICE,
        ) + slotKeys

        assertTrue(
            "Theme Studio invented preference keys: ${prefs.all.keys - whitelistedKeys}",
            whitelistedKeys.containsAll(prefs.all.keys),
        )
        // The UI3-13 sweep above must actually reach each of the four new keys;
        // anything else staying unwritten keeps the schema frozen.
        listOf(
            ThemeStore.KEY_UI_PURE_BLACK,
            ThemeStore.KEY_UI_GLASS_LEVEL,
            ThemeStore.KEY_UI_NAV_STYLE,
            ThemeStore.KEY_UI_FONT_CHOICE,
        ).forEach { key ->
            assertTrue("expected the sweep to persist $key", prefs.all.containsKey(key))
        }
        assertTrue(prefs.all.isNotEmpty())
    }
}
