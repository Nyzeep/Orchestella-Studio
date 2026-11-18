package io.github.nyzeep.orchestella.ui.v4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.design.components.OeSlider
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
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
class V4SliderScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val testName = TestName()

    @Test
    fun slider_dark_default() {
        mount(value = 0.5f)
        capture()
    }

    @Test
    fun slider_dark_dragging() {
        mount(value = 0.25f)
        val slider = compose.onNodeWithTag("slider")
        slider.performTouchInput {
            down(Offset(width / 4f, center.y))
            moveTo(center)
        }
        try {
            slider.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.5f, 0f..1f, 3),
                ),
            )
            // Capture before releasing the pointer; at the same value as the resting case.
            capture()
        } finally {
            slider.performTouchInput { up() }
        }
    }

    @Test
    fun slider_dark_disabled() {
        mount(value = 0.5f, enabled = false)
        compose.onNodeWithTag("slider").assertIsNotEnabled()
        capture()
    }

    @Test
    fun slider_dark_min() {
        mount(value = 0f)
        capture()
    }

    @Test
    fun slider_dark_max() {
        mount(value = 1f)
        capture()
    }

    private fun mount(value: Float, enabled: Boolean = true) {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.mascotDark, reduceMotion = true) {
                var current by remember { mutableFloatStateOf(value) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .testTag("slider-board")
                        .background(Color(Oe3ThemeValues.colors.background))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    OeSlider(
                        value = current,
                        onValueChange = { current = it },
                        modifier = Modifier.testTag("slider"),
                        steps = 3,
                        enabled = enabled,
                    )
                }
            }
        }
    }

    private fun capture() {
        compose.onNodeWithTag("slider-board")
            .captureRoboImage("src/test/screenshots/v4/${testName.methodName}.png")
    }
}
