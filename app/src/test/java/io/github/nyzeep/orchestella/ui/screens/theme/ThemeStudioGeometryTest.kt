package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Geometry contract for the two embedded previews. The page is rendered at a
 * tall viewport so every section is placed (not clipped by the scroll port),
 * and the preset list is limited to three cards: this test is about the
 * preview boxes, not the catalog.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h9000dp-mdpi")
class ThemeStudioGeometryTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homePreviewKeepsTheTicketGeometry() {
        val state = themeStudioPreviewState(ThemeCatalog.constructivistWorkstation)
        compose.setContent {
            Oe3Theme(theme = state.theme, reduceMotion = true) {
                ThemeStudioScreen(
                    state = state,
                    onEvent = {},
                    reduceMotion = true,
                )
            }
        }
        val preview = compose.onNodeWithTag("theme-preview:home").fetchSemanticsNode().boundsInRoot
        assertEquals(168f, preview.width, 0.5f)
        assertEquals(364f, preview.height, 1f)
        compose.onNodeWithTag("theme-preview:home")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun terminalPreviewKeepsItsHeightAndAllSixteenAnsiIndices() {
        val state = themeStudioPreviewState(ThemeCatalog.github)
        compose.setContent {
            Oe3Theme(theme = state.theme, reduceMotion = true) {
                ThemeTerminalScreen(
                    state = state,
                    onEvent = {},
                    onBack = {},
                    renderTerminalView = false,
                )
            }
        }
        val preview = compose.onNodeWithTag("theme-terminal:preview").fetchSemanticsNode().boundsInRoot
        assertEquals(200f, preview.height, 0.5f)
        compose.onNodeWithTag("theme-terminal:sketch").assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
        )
        repeat(16) { index ->
            assertTrue(
                "ANSI $index swatch is missing from the palette strips",
                compose.onAllNodesWithContentDescription("ANSI $index").fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }
}
