package io.github.nyzeep.orchestella.ui.oe3.screenshot

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * Palette + component board across the three modes and both text scales.
 *
 * Six goldens total (3 modes x 2 scales), all under
 * `src/test/screenshots/oe3/tokens/`. The board is a pure function of the
 * tokens: no preferences, clocks or activity state are read, and motion is
 * disabled by [Oe3Theme]'s `reduceMotion`, so a clean CI checkout renders the
 * same pixels as the recording machine.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1320dp-mdpi")
class Oe3TokensScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun darkAt100Percent() {
        capture("dark-100", ThemeCatalog.constructivistWorkstation, pureBlack = false, textScale = 1f)
    }

    @Test
    fun darkAt130Percent() {
        capture("dark-130", ThemeCatalog.constructivistWorkstation, pureBlack = false, textScale = 1.3f)
    }

    @Test
    fun lightAt100Percent() {
        capture("light-100", ThemeCatalog.constructivistWorkstationLight, pureBlack = false, textScale = 1f)
    }

    @Test
    fun lightAt130Percent() {
        capture("light-130", ThemeCatalog.constructivistWorkstationLight, pureBlack = false, textScale = 1.3f)
    }

    @Test
    fun pureBlackAt100Percent() {
        capture("pure-black-100", ThemeCatalog.constructivistWorkstation, pureBlack = true, textScale = 1f)
    }

    @Test
    fun pureBlackAt130Percent() {
        capture("pure-black-130", ThemeCatalog.constructivistWorkstation, pureBlack = true, textScale = 1.3f)
    }

    private fun capture(name: String, theme: AppTheme, pureBlack: Boolean, textScale: Float) {
        compose.setContent {
            Oe3Theme(
                theme = theme,
                pureBlack = pureBlack,
                textScale = textScale,
                reduceMotion = true,
            ) {
                Oe3TokenBoard(
                    Modifier
                        .testTag("board")
                        .width(BoardWidth)
                        .height(BoardHeight),
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
            "src/test/screenshots/oe3/tokens/$name.png",
        )
    }
}
