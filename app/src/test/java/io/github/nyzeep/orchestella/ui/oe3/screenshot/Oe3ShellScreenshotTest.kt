package io.github.nyzeep.orchestella.ui.oe3.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3ShellController
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Shell goldens for the Oe3 chrome (ticket UI3-11A; re-anchored on the real
 * root by UI3-12): brand top bar, the workstation page and the real floating
 * capsule nav bar, across dark / light / pure black x 1.0 / 1.3 text scale —
 * six goldens under `src/test/screenshots/oe3/shell/`.
 *
 * UI3-12 forwarded `pureBlack` through [Oe3App], so the mirror board is gone:
 * these render the production root directly, with the selection driven by a
 * real [Oe3ShellController] exactly like `MainActivity.mountOe3Shell`. The
 * placeholder page content still comes from [Oe3App]'s own pager — the page
 * wiring is pinned by the per-page suites under `ui3/`. Safe-drawing insets
 * resolve to zero under Robolectric, so the horizontal padding is a no-op and
 * the pixels stay stable. Every rendered piece is a production composable,
 * motion is disabled and nothing is read from the clock, preferences or an
 * activity, so a clean checkout replays the pixels.
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
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = theme,
                textScale = textScale,
                reduceMotion = true,
                pureBlack = pureBlack,
            )
        }
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/oe3/shell/$name.png",
        )
    }
}
