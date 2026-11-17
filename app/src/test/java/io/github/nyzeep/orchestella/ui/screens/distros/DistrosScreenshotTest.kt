package io.github.nyzeep.orchestella.ui.screens.distros

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 票面要求的三张暗色截图：折叠 / 展开 / 空目录。
 *
 * 输出到 `src/test/screenshots/ui3/distros/`，与 UI3-06/11 的 golden 约定一致；
 * 只新增本页的 3 张，不改 UI3-11 已有的 8 张。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1400dp-mdpi")
class DistrosScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun collapsedDark() {
        capture(name = "collapsed-dark", state = DistrosPreviewStates.collapsed, expandedId = null)
    }

    @Test
    fun expandedDark() {
        capture(
            name = "expanded-dark",
            state = DistrosPreviewStates.expanded,
            expandedId = "ubuntu",
        )
    }

    @Test
    fun emptyDark() {
        capture(name = "empty-dark", state = DistrosPreviewStates.empty, expandedId = null)
    }

    private fun capture(name: String, state: DistrosUiState, expandedId: String?) {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                Box(
                    modifier = Modifier
                        .size(width = 412.dp, height = 1400.dp)
                        .testTag("distros-shot"),
                ) {
                    DistrosScreen(
                        state = state,
                        actions = DistrosActions(),
                        initialExpandedId = expandedId,
                    )
                }
            }
        }
        compose.onNodeWithTag("distros-shot").captureRoboImage(
            "src/test/screenshots/ui3/distros/$name.png",
        )
    }
}
