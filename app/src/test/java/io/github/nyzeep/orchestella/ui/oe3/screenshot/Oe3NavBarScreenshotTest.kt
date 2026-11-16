package io.github.nyzeep.orchestella.ui.oe3.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassEnabled
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.nav.Oe3NavBar
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Nav-bar fallback goldens (ticket UI3-10, spec §2.6).
 *
 * Robolectric does not render backdrop blur, so the goldens pin the fallback
 * draw path — the solid α0.96 surface with the 24dp top scrim that API < 31
 * (or a disabled glass layer) renders instead of the Haze effect. The board
 * forces that path deterministically by composing `glassLevel = Standard`
 * with [LocalOe3GlassEnabled] off, which is exactly the "glass requested but
 * unrenderable" branch of [io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassStrategy].
 * The default None level (solid, scrim-free, pixel-identical to the previous
 * shell) is already covered by the UI3-11A shell goldens.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h240dp-mdpi")
class Oe3NavBarScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fallbackInDark() {
        capture("fallback-dark", ThemeCatalog.constructivistWorkstation)
    }

    @Test
    fun fallbackInLight() {
        capture("fallback-light", ThemeCatalog.constructivistWorkstationLight)
    }

    private fun capture(name: String, theme: AppTheme) {
        compose.setContent {
            Oe3NavBarFallbackBoard(theme = theme)
        }
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/oe3/nav/$name.png",
        )
    }
}

/** The bar exactly as `Oe3App` anchors it, under the forced fallback path. */
@Composable
private fun Oe3NavBarFallbackBoard(theme: AppTheme) {
    Oe3Theme(
        theme = theme,
        glassLevel = Oe3GlassLevel.Standard,
        reduceMotion = true,
    ) {
        CompositionLocalProvider(LocalOe3GlassEnabled provides false) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(Oe3ThemeValues.colors.background)),
            ) {
                Oe3NavBar(
                    selected = Oe3Tab.Workstation,
                    onSelect = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}
