package io.github.nyzeep.orchestella.ui.oe3.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.shell.Oe3NavBar
import io.github.nyzeep.orchestella.ui.shell.Oe3Pager
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.Oe3TopBar
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Shell goldens for the Oe3 chrome (ticket UI3-11A): brand top bar, the
 * workstation placeholder page and the real floating capsule nav bar, across
 * dark / light / pure black x 1.0 / 1.3 text scale — six goldens under
 * `src/test/screenshots/oe3/shell/`.
 *
 * [io.github.nyzeep.orchestella.ui.shell.Oe3App] applies its own `Oe3Theme`
 * internally and does not forward a `pureBlack` argument, so the board
 * composes the shell exactly like `Oe3App` does (same background, same
 * horizontal safe-drawing padding, same top bar / pager / bottom-anchored nav
 * bar tree) under one `Oe3Theme` that carries the mode; only the pure-black
 * injection differs from the app root. Safe-drawing insets resolve to zero
 * under Robolectric, so keeping the padding is pixel-neutral and keeps the
 * mirror verbatim. The shell semantics themselves are exercised against the
 * real `Oe3App` composable in [Oe3SemanticsTest]; UI3-12 may re-anchor this
 * board on the real root once it forwards the mode. Every rendered piece is
 * a production composable, motion is disabled and nothing is read from the
 * clock, preferences or an activity, so a clean checkout replays the pixels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class Oe3ShellScreenshotTest {

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
            Oe3ShellBoard(
                theme = theme,
                pureBlack = pureBlack,
                textScale = textScale,
            )
        }
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/oe3/shell/$name.png",
        )
    }
}

/**
 * The [io.github.nyzeep.orchestella.ui.shell.Oe3App] layout, verbatim, under a
 * theme call that exposes the pure-black mode the app root does not forward.
 */
@Composable
private fun Oe3ShellBoard(
    theme: AppTheme,
    pureBlack: Boolean,
    textScale: Float,
) {
    Oe3Theme(
        theme = theme,
        pureBlack = pureBlack,
        textScale = textScale,
        reduceMotion = true,
    ) {
        val colors = Oe3ThemeValues.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(colors.background))
                // 横屏/挖孔：与 Oe3App 相同，只让左右安全区推开内容；Robolectric
                // 下 insets 为零，保留该层是为了让镜像与生产根逐字一致。
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Oe3TopBar()
                Oe3Pager(
                    selected = Oe3Tab.Workstation,
                    modifier = Modifier.weight(1f),
                )
            }
            Oe3NavBar(
                selected = Oe3Tab.Workstation,
                onSelect = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
