package io.github.nyzeep.orchestella.ui.oe3.screenshot

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Icon sheet for the two families owned by UI3-03: five navigation glyphs,
 * nine actions, and the distro marks. Light and dark share the same board so a
 * tint or path regression is visible in both modes.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h650dp-mdpi")
class Oe3IconsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun darkIconSheet() {
        capture("dark", ThemeCatalog.constructivistWorkstation)
    }

    @Test
    fun lightIconSheet() {
        capture("light", ThemeCatalog.constructivistWorkstationLight)
    }

    private fun capture(name: String, theme: AppTheme) {
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = true) {
                Oe3IconBoard(
                    Modifier
                        .testTag("board")
                        .width(BoardWidth)
                        .height(560.dp),
                )
            }
        }
        val boardBottom = compose.onNodeWithTag("board").fetchSemanticsNode().boundsInRoot.bottom
        val endBottom = compose.onNodeWithTag("board-end", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "board content is clipped: end=$endBottom, board=$boardBottom",
            endBottom <= boardBottom,
        )
        compose.onNodeWithTag("board").captureRoboImage(
            "src/test/screenshots/oe3/icons/$name.png",
        )
    }
}
