package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Six page goldens: dark / light / pure black x 1.0 / 1.3 text scale.
 * The terminal inside the page is the labelled Compose palette sketch; the real
 * AndroidView terminal is a device-stage check, never a fabricated screenshot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h6000dp-mdpi")
class ThemeStudioScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun darkAt100Percent() = capture(
        name = "dark-100",
        theme = ThemeCatalog.constructivistWorkstation,
        pureBlack = false,
        textScalePercent = 100,
    )

    @Test
    fun darkAt130Percent() = capture(
        name = "dark-130",
        theme = ThemeCatalog.constructivistWorkstation,
        pureBlack = false,
        textScalePercent = 130,
    )

    @Test
    fun lightAt100Percent() = capture(
        name = "light-100",
        theme = ThemeCatalog.constructivistWorkstationLight,
        pureBlack = false,
        textScalePercent = 100,
    )

    @Test
    fun lightAt130Percent() = capture(
        name = "light-130",
        theme = ThemeCatalog.constructivistWorkstationLight,
        pureBlack = false,
        textScalePercent = 130,
    )

    @Test
    fun pureBlackAt100Percent() = capture(
        name = "pure-black-100",
        theme = ThemeCatalog.constructivistWorkstation,
        pureBlack = true,
        textScalePercent = 100,
    )

    @Test
    fun pureBlackAt130Percent() = capture(
        name = "pure-black-130",
        theme = ThemeCatalog.constructivistWorkstation,
        pureBlack = true,
        textScalePercent = 130,
    )

    private fun capture(
        name: String,
        theme: io.github.nyzeep.orchestella.ui.theme.AppTheme,
        pureBlack: Boolean,
        textScalePercent: Int,
    ) {
        compose.setContent {
            ThemeStudioScreen(
                state = themeStudioScreenshotState(theme, pureBlack, textScalePercent),
                onEvent = {},
                renderTerminalView = false,
                reduceMotion = true,
                onPickBackground = {},
                onPickTerminalBackground = {},
                onPickFont = {},
            )
        }
        val root = compose.onNodeWithTag("theme-studio:screen").fetchSemanticsNode()
        val end = compose.onNodeWithTag("theme-studio:end").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(
            "page content is clipped: end=$end, root=${root.boundsInRoot.bottom}",
            end <= root.boundsInRoot.bottom,
        )
        compose.onNodeWithTag("theme-studio:screen").captureRoboImage(
            "src/test/screenshots/oe3/screens/theme-studio-$name.png",
        )
    }
}

/** Two terminal palette goldens: the Compose sketch, not the AndroidView. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h480dp-mdpi")
class TerminalPreviewScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun darkPaletteSketch() = capture("dark-100", ThemeCatalog.constructivistWorkstation)

    @Test
    fun lightPaletteSketch() = capture("light-100", ThemeCatalog.constructivistWorkstationLight)

    private fun capture(name: String, theme: io.github.nyzeep.orchestella.ui.theme.AppTheme) {
        compose.setContent { TerminalPreviewScreenshotBoard(theme) }
        compose.onNodeWithTag("terminal-board").captureRoboImage(
            "src/test/screenshots/oe3/screens/theme-terminal-$name.png",
        )
    }
}
