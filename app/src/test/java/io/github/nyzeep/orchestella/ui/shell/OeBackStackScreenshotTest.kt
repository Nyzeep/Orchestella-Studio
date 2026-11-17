package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 栈式导航的 golden（工单 UI-03）：真实壳根里 push 演示目的地后的落位帧——
 * 二级页以 `OeScaffold(leading=返回)` 覆盖 pager，浮动坞保持原几何。
 * 减动效开启，帧即终态（motion.md：截图断言终态，不等动画）。
 *
 * 2026-11-18 语义订正（UI-12 真机走查裁决）：栈静止态宿主不再渲染 base 与
 * 更深层——v4 页面体透明，静止时目的地直接落在背景层上；旧语义（pager 垫底）
 * 会让透明页体透出栈下页面（真机残影）。本帧钉新语义：demo 目的地之下是
 * 背景层，无 pager 内容。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class OeBackStackScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pushedDemoDestinationRestsOnBackgroundLayer() {
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        val stack = LocalOeBackStack.current
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("oe-shot-push-trigger")
                                .clickable { stack?.push(OeBackStackDemo.destination()) },
                        )
                    },
                ),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("oe-shot-push-trigger").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/oe3/nav/backstack-demo-pushed.png",
        )
    }
}
