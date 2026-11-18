package io.github.nyzeep.orchestella.ui.v4

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassTier
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassTierOverride
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h240dp-mdpi")
class V4GlassScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val testName = TestName()

    @Test
    fun glass_dark_l0() = capture(GlassTier.L0)

    @Test
    fun glass_dark_l1() = capture(GlassTier.L1)

    /** UI-06b defers edge refraction; the current L2 input shares the L1 renderer. */
    @Test
    fun `glass_dark_l2-fallback`() = capture(GlassTier.L2)

    private fun capture(tier: GlassTier) {
        compose.setContent {
            CompositionLocalProvider(LocalOe3GlassTierOverride provides tier) {
                Oe3App(
                    selectedTab = Oe3Tab.Workstation,
                    onTabSelected = {},
                    theme = ThemeCatalog.mascotDark,
                    reduceMotion = true,
                    glassLevel = Oe3GlassLevel.Standard,
                    pages = Oe3Pages(workstation = {
                        // Fixed edges behind the real dock make its tint and backdrop sampling legible.
                        Canvas(Modifier.fillMaxSize()) {
                            val bandWidth = size.width / Backdrop.size
                            Backdrop.forEachIndexed { index, color ->
                                drawRect(
                                    color,
                                    topLeft = Offset(bandWidth * index, 0f),
                                    size = Size(bandWidth, size.height),
                                )
                            }
                        }
                    }),
                )
            }
        }
        compose.onNode(hasText(Oe3Tab.Workstation.label) and isSelected()).assertIsDisplayed()
        compose.onRoot().captureRoboImage("src/test/screenshots/v4/${testName.methodName}.png")
    }

    private companion object {
        val Backdrop = listOf(
            Color(0xFF25375C),
            Color(0xFF854255),
            Color(0xFFAD774B),
            Color(0xFF37746B),
            Color(0xFF594984),
        )
    }
}
