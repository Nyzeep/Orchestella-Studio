package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-02 顶栏组件基线：动作槽的两种姿态。
 *
 * 页面级 golden 只跑得出「没有动作」的栏，动作槽的几何（44dp 胶囊、
 * 8dp 间隔、最多 3 个槽位）没有落点。这里直接摆 [OeScaffoldBar]：
 * 静止态（滚动 0，栏透明）带 1 个动作，折叠态（滚动满 12dp，
 * 栏实心）带 4 个动作——第 3 个槽位应变成溢出入口。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h240dp-mdpi")
class OeScaffoldBarScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun singleActionAtScrollTop() {
        capture("bar-rest-1-action", alpha = 0f, actionCount = 1)
    }

    @Test
    fun fourActionsCollapseIntoTheOverflowSlot() {
        capture("bar-collapsed-4-actions", alpha = 1f, actionCount = 4)
    }

    private fun capture(name: String, alpha: Float, actionCount: Int) {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                val colors = Oe3ThemeValues.colors
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(colors.background)),
                ) {
                    OeScaffoldBar(
                        title = "工作站",
                        alpha = alpha,
                        actions = List(actionCount) { index ->
                            OeScaffoldAction(
                                icon = if (index == 0) Oe3Icons.Create else Oe3Icons.Update,
                                contentDescription = "动作 $index",
                                onClick = {},
                            )
                        },
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = Oe3Sizes.topBar, start = Oe3Spacing.pageInset),
                    ) {
                        Oe3Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "内容区",
                                style = Oe3Typography.style(Oe3TextRole.Body),
                                color = Color(colors.onSurface),
                            )
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/oe3/shell/$name.png",
        )
    }
}
