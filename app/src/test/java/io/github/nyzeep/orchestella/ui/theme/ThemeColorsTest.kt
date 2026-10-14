package io.github.nyzeep.orchestella.ui.theme

import org.junit.Assert.*
import org.junit.Test

class ThemeColorsTest {
    @Test fun everyExistingPaletteHasUniquePersistentIdAndCompleteOpaqueAnsiColors() {
        assertEquals(ThemeCatalog.all.size, ThemeCatalog.all.map { it.id }.toSet().size)
        for (theme in ThemeCatalog.all) {
            assertSame(theme, ThemeCatalog.resolve(theme.id))
            assertEquals(theme.displayName, 16, theme.ansi.size)
            assertTrue(theme.displayName, theme.ansi.all { it ushr 24 == 255 })
        }
    }

    @Test fun lightAndDarkPalettesHaveReadableButtonInkAndMutedLabels() {
        for (theme in ThemeCatalog.all) {
            assertTrue("${theme.id}: button ink", ThemeColors.contrast(ThemeColors.onColor(theme.primary), theme.primary) >= 4.5)
            val muted = ThemeColors.color(theme, ThemeColors.Role.MUTED)
            assertTrue("${theme.id}: secondary text", ThemeColors.contrast(muted, theme.surface) >= 4.5)
        }
        assertEquals(0xFF000000.toInt(), ThemeColors.onColor(ThemeCatalog.constructivistAmber.primary))
        assertEquals(0xFFFFFFFF.toInt(), ThemeColors.onColor(ThemeCatalog.constructivistCobalt.primary))
    }

    @Test fun sourceRolesStillResolveAgainstTheNewPaletteAfterRepeatedSwitches() {
        val textRole = ThemeColors.textRole(0xFF8E929B.toInt())
        val actionRole = ThemeColors.backgroundRole(0xFFD62828.toInt())
        for (theme in listOf(ThemeCatalog.constructivistWorkstation, ThemeCatalog.catppuccinLatte,
            ThemeCatalog.nord, ThemeCatalog.constructivistAmber, ThemeCatalog.dracula)) {
            assertEquals(theme.primary, ThemeColors.color(theme, actionRole))
            assertTrue(ThemeColors.contrast(ThemeColors.color(theme, textRole), theme.surface) >= 4.5)
            assertEquals(theme.background, ThemeColors.color(theme, ThemeColors.Role.BACKGROUND))
        }
    }

    @Test fun onlyNestedDashboardContentIsTransparentToThePageWallpaper() {
        assertTrue(ThemeColors.isWallpaperContentWrapper("root_container", "root_coordinator"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("root_coordinator", "content"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("card_settings_theme", "root_coordinator"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("root_container", "settings_root_coordinator"))
        assertFalse(ThemeColors.isWallpaperContentWrapper("root_container", "dialog_root"))
    }

    @Test fun allFontSizesRoundTripThroughTheZeroBasedSliderIncludingEndpoints() {
        for (sp in 10..22) assertEquals(sp, ThemeColors.fontSize(ThemeColors.fontProgress(sp)))
        assertEquals(10, ThemeColors.fontSize(-50))
        assertEquals(22, ThemeColors.fontSize(50))
        assertEquals(0f, ThemeColors.boundedSpacing(Float.NaN, 0.2f), 0f)
        assertEquals(0.2f, ThemeColors.boundedSpacing(0.5f, 0.2f), 0f)
    }
}
