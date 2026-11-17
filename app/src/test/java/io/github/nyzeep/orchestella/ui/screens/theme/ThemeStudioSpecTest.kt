package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.tokens.HomePreviewBlockKind
import io.github.nyzeep.orchestella.ui.oe3.tokens.HomePreviewSpec
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Test

/** The page order is a spec contract, not a rendering detail. */
class ThemeStudioSpecTest {

    @Test
    fun sectionOrderMatchesSpec33() {
        assertEquals(
            listOf(
                ThemeStudioSection.HomePreview,
                ThemeStudioSection.Mode,
                ThemeStudioSection.PureBlack,
                ThemeStudioSection.Presets,
                ThemeStudioSection.AccentSlots,
                ThemeStudioSection.NavStyle,
                ThemeStudioSection.Glass,
                ThemeStudioSection.Corner,
                ThemeStudioSection.Font,
                ThemeStudioSection.TextScale,
                ThemeStudioSection.WallpaperOpacity,
                ThemeStudioSection.Terminal,
            ),
            ThemeStudioSpec.sectionOrder,
        )
    }

    @Test
    fun homePreviewBlocksFollowTheSharedSpec() {
        assertEquals(
            listOf(
                HomePreviewBlockKind.Title,
                HomePreviewBlockKind.Meta,
                HomePreviewBlockKind.HeroCard,
                HomePreviewBlockKind.StorageRing,
                HomePreviewBlockKind.Metrics,
                HomePreviewBlockKind.RecentInstances,
            ),
            HomePreviewSpec.blocks.map { it.kind },
        )
        assertEquals(1f, HomePreviewSpec.totalFraction(), 0.0001f)
    }

    @Test
    fun previewGeometryMatchesTheTicket() {
        assertEquals(168.dp, ThemeStudioSpec.previewWidth)
        assertEquals(28.dp, ThemeStudioSpec.previewCorner)
        assertEquals(9f / 19.5f, ThemeStudioSpec.previewAspectRatio, 0.0001f)
        assertEquals(44.dp, ThemeStudioSpec.thumbnailWidth)
        assertEquals(30.dp, ThemeStudioSpec.thumbnailHeight)
        assertEquals(8.dp, ThemeStudioSpec.thumbnailCorner)
        assertEquals(48.dp, ThemeStudioSpec.optionRowMinHeight)
        assertEquals(200.dp, ThemeStudioSpec.terminalHeight)
        assertEquals(80, ThemeStudioSpec.terminalColumns)
        assertEquals(24, ThemeStudioSpec.terminalRows)
    }

    @Test
    fun catalogHasFortyTwoPresets() {
        assertEquals(ThemeStudioSpec.expectedPresetCount, ThemeCatalog.all.size)
        // UI-14 split the single-form mascot into a light/dark pair: 41 → 42.
        assertEquals(42, ThemeCatalog.all.size)
    }

    @Test
    fun cornerOptionsAreTheFiveSpecifiedTiers() {
        assertEquals(listOf(8, 12, 16, 20, 24), ThemeStudioSpec.cornerOptions)
    }
}
