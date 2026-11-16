package io.github.nyzeep.orchestella.ui.screens.theme

import io.github.nyzeep.orchestella.FakeSharedPreferences
import io.github.nyzeep.orchestella.ui.design.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStudioControllerTest {

    private fun store(prefs: FakeSharedPreferences = FakeSharedPreferences()) = ThemeStore(prefs)

    @Test
    fun everyPresetAppliesAndReadsBack() {
        val prefs = FakeSharedPreferences()
        val controller = ThemeStudioController(ThemeStore(prefs))
        for (preset in ThemeCatalog.all) {
            val state = controller.handle(ThemeStudioEvent.SelectTheme(preset.id))
            assertEquals("readback id for ${preset.id}", preset.id, state.theme.id)
            assertEquals(preset, state.theme)
            assertEquals(preset.id, ThemeStore(prefs).themeId())
            val expectedMode = when {
                preset.isFixed -> ThemeStore.Mode.FIXED
                preset.isDark -> ThemeStore.Mode.DARK
                else -> ThemeStore.Mode.LIGHT
            }
            assertEquals("mode for ${preset.id}", expectedMode, state.mode)
        }
    }

    @Test
    fun modeSelectionSwapsTheCatalogVariant() {
        val controller = ThemeStudioController(store())
        controller.handle(ThemeStudioEvent.SelectTheme(ThemeCatalog.github.id))
        val light = controller.handle(ThemeStudioEvent.SelectMode(ThemeStore.Mode.LIGHT))
        assertEquals(ThemeCatalog.githubLight, light.theme)
        assertEquals(ThemeStore.Mode.LIGHT, light.mode)
        val dark = controller.handle(ThemeStudioEvent.SelectMode(ThemeStore.Mode.DARK))
        assertEquals(ThemeCatalog.github, dark.theme)
    }

    @Test
    fun fixedPalettePinsItsOwnBrightness() {
        val controller = ThemeStudioController(store())
        val state = controller.handle(ThemeStudioEvent.SelectTheme(ThemeCatalog.mascotLavender.id))
        assertEquals(ThemeStore.Mode.FIXED, state.mode)
        assertTrue(state.isFixedBrightness)
        assertEquals(ThemeCatalog.mascotLavender, state.theme)
    }

    @Test
    fun persistedAppearanceWritesRoundTripThroughThemeStore() {
        val prefs = FakeSharedPreferences()
        val controller = ThemeStudioController(ThemeStore(prefs))

        for (corner in ThemeStudioSpec.cornerOptions) {
            assertEquals(corner, controller.handle(ThemeStudioEvent.SetCornerDp(corner)).cornerDp)
        }
        val scaled = controller.handle(ThemeStudioEvent.SetTextScalePercent(130))
        assertEquals(OeTextScaleMode.Custom, scaled.textScaleMode)
        assertEquals(130, scaled.textScalePercent)
        assertEquals(1.3f, scaled.appTextScale, 0.0001f)

        val opacity = controller.handle(ThemeStudioEvent.SetSurfaceOpacity(55))
        assertEquals(55, opacity.surfaceOpacityPercent)

        val font = controller.handle(ThemeStudioEvent.SetTerminalFontSizeSp(22))
        assertEquals(22, font.terminalFontSizeSp)
        assertEquals(22, ThemeStore(prefs).terminalFontSizeSp())

        val spacing = controller.handle(ThemeStudioEvent.SetTerminalLetterSpacing(0.20f))
        assertEquals(0.20f, spacing.terminalLetterSpacingEm, 0.0001f)
        val line = controller.handle(ThemeStudioEvent.SetTerminalLineSpacing(8f))
        assertEquals(8f, line.terminalLineSpacingPx, 0.0001f)

        val accent = controller.handle(ThemeStudioEvent.SetCustomAccent(0xFF123456.toInt()))
        assertEquals(0xFF123456.toInt(), accent.customAccent)
        assertEquals(0xFF123456.toInt(), accent.theme.primary)
    }

    @Test
    fun followSystemTextScaleRestoresTheMultiplier() {
        val controller = ThemeStudioController(store())
        controller.handle(ThemeStudioEvent.SetTextScalePercent(130))
        val state = controller.handle(ThemeStudioEvent.SetTextScaleMode(OeTextScaleMode.FollowSystem))
        assertEquals(1f, state.appTextScale, 0.0001f)
    }

    @Test
    fun customSlotSaveAndApplyUsesTheExistingSlotSetter() {
        val prefs = FakeSharedPreferences()
        val controller = ThemeStudioController(ThemeStore(prefs))
        val slot = CustomThemeSlot(
            name = "Ocean",
            isDark = true,
            background = 0xFF001018.toInt(),
            surface = 0xFF002030.toInt(),
            primary = 0xFF55CCFF.toInt(),
            accent = 0xFF88DDFF.toInt(),
            text = 0xFFEAF8FF.toInt(),
            textDim = 0xFF9AB8C8.toInt(),
            terminalBackground = 0xFF00080C.toInt(),
            terminalForeground = 0xFFEAF8FF.toInt(),
        )
        val state = controller.handle(ThemeStudioEvent.SaveCustomSlot(1, slot, apply = true))
        assertEquals(slot, ThemeStore(prefs).customSlot(1))
        assertEquals("custom:2", state.theme.id)
        assertEquals("Ocean", state.theme.displayName)
        assertEquals(ThemeStore.Mode.DARK, state.mode)
    }

    @Test
    fun pureBlackIsDroppedWhenTheThemeTurnsLight() {
        val controller = ThemeStudioController(store())
        controller.handle(ThemeStudioEvent.SelectTheme(ThemeCatalog.constructivistWorkstation.id))
        assertTrue(controller.handle(ThemeStudioEvent.SetPureBlack(true)).pureBlack)
        val light = controller.handle(ThemeStudioEvent.SelectTheme(ThemeCatalog.constructivistWorkstationLight.id))
        assertFalse("pure black must not survive a light palette", light.pureBlack)
        controller.handle(ThemeStudioEvent.SelectTheme(ThemeCatalog.constructivistWorkstation.id))
        assertTrue(controller.handle(ThemeStudioEvent.SetPureBlack(true)).pureBlack)
    }

    @Test
    fun localPreviewOptionsNeverWritePreferences() {
        val prefs = FakeSharedPreferences()
        val controller = ThemeStudioController(ThemeStore(prefs))
        controller.handle(ThemeStudioEvent.SelectTheme(ThemeCatalog.constructivistWorkstation.id))
        val before = prefs.all.keys.toSet()

        controller.handle(ThemeStudioEvent.SetPureBlack(true))
        controller.handle(ThemeStudioEvent.SetGlass(GlassOption.Ultra))
        controller.handle(ThemeStudioEvent.SetNavStyle(NavStyleOption.Fixed))
        controller.handle(ThemeStudioEvent.SetFontChoice(FontChoice.Mono))
        controller.handle(ThemeStudioEvent.SetImportedFontLabel("inter.ttf"))

        assertEquals(before, prefs.all.keys.toSet())
        val state = controller.read()
        assertTrue(state.pureBlack)
        assertEquals(GlassOption.Ultra, state.glass)
        assertEquals(NavStyleOption.Fixed, state.navStyle)
        assertEquals(FontChoice.Imported, state.fontChoice)
        assertEquals("inter.ttf", state.importedFontLabel)
    }

    @Test
    fun wallpaperAndTerminalPathsClearThroughTheExistingSetters() {
        val prefs = FakeSharedPreferences()
        val store = ThemeStore(prefs)
        store.setBackgroundUri("content://test/wall")
        store.setTerminalBackgroundUri("content://test/term")
        val controller = ThemeStudioController(store)
        controller.handle(ThemeStudioEvent.ClearBackground)
        assertNull(store.backgroundUri())
        controller.handle(ThemeStudioEvent.ClearTerminalBackground)
        assertNull(store.terminalBackgroundUri())
    }

    @Test
    fun cursorBlinkGoesThroughTheExistingSettingsPort() {
        var blinkEnabled = true
        val port = object : CursorBlinkPort {
            override fun read(): Boolean = blinkEnabled
            override fun write(enabled: Boolean) {
                blinkEnabled = enabled
            }
        }
        val controller = ThemeStudioController(store(), port)
        assertTrue(controller.read().cursorBlink)
        val off = controller.handle(ThemeStudioEvent.SetCursorBlink(false))
        assertFalse(off.cursorBlink)
        assertFalse(blinkEnabled)
    }
}
