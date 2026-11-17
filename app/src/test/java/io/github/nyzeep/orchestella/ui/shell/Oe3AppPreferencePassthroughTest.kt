package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * UI-02 透传回归钉：`Oe3App` 曾经把「圆角」与「坞样式」偏好读进来却不下发。
 *
 * 断言点在真实 `Oe3App` 根的页面槽位里：圆角偏好必须落到
 * [LocalOe3CornerRadius]（主壳卡片读它），坞样式必须改变
 * [LocalOe3PageBottomPadding]（Fixed 比 Floating 少 21dp 边距），
 * 表面不透明度必须落到 [LocalOeSurfaceAlpha]。
 *
 * UI-03 扩展：坞样式还必须改变**坞本体几何**——悬浮坞底边距屏幕底 21dp
 * （Robolectric 里系统 inset 为 0），固定坞底边贴住屏幕底。断言读
 * `shell:nav-bar` 标签节点的 boundsInRoot，钉住"Fixed 形态可切换且页面
 * 留白正确"的验收标准第 6 条。
 *
 * 每个用例只 setContent 一次（Compose 规则的限制），所以一对取值拆成两个
 * 用例；两个用例合起来才说明「切换真的改变了数值」。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class Oe3AppPreferencePassthroughTest {

    @get:Rule
    val compose = createComposeRule()

    private data class Probed(
        val cornerRadius: Dp?,
        val bottomReserve: Dp,
        val surfaceAlpha: Float,
    )

    private fun probe(
        cornerRadiusDp: Int? = null,
        navStyle: Oe3NavStyle = Oe3NavStyle.Floating,
        surfaceAlpha: Float = 1f,
    ): Probed {
        var captured: Probed? = null
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                navStyle = navStyle,
                cornerRadiusDp = cornerRadiusDp,
                surfaceAlpha = surfaceAlpha,
                pages = Oe3Pages(
                    workstation = {
                        captured = Probed(
                            cornerRadius = LocalOe3CornerRadius.current,
                            bottomReserve = LocalOe3PageBottomPadding.current,
                            surfaceAlpha = LocalOeSurfaceAlpha.current,
                        )
                    },
                ),
            )
        }
        compose.waitForIdle()
        return requireNotNull(captured) { "页面槽位没有组合" }
    }

    @Test
    fun cornerRadiusPreferenceReachesTheCards() {
        assertEquals(8.dp, probe(cornerRadiusDp = 8).cornerRadius)
    }

    @Test
    fun cornerRadiusUnsetKeepsTheLadder() {
        // 未下发时保持规范阶梯（卡片 24dp），不被一个假 0dp 顶掉。
        assertNull(probe(cornerRadiusDp = null).cornerRadius)
    }

    @Test
    fun floatingNavStyleReservesSixtyTwoPlusTwentyOne() {
        // Robolectric 里系统底栏 inset 为 0：悬浮 62 + 21 = 83dp。
        assertEquals(83.dp, probe(navStyle = Oe3NavStyle.Floating).bottomReserve)
    }

    @Test
    fun fixedNavStyleReservesOnlyTheBar() {
        // 固定坞贴住系统栏：62 + 0 = 62dp，与悬浮态差 21dp。
        assertEquals(62.dp, probe(navStyle = Oe3NavStyle.Fixed).bottomReserve)
    }

    private fun navBarBottom(): Float =
        compose.onNodeWithTag("shell:nav-bar").fetchSemanticsNode().boundsInRoot.bottom

    private fun rootHeight(): Float =
        compose.onRoot().fetchSemanticsNode().boundsInRoot.height

    @Test
    fun floatingBarFloatsTwentyOneAboveTheBottomEdge() {
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                navStyle = Oe3NavStyle.Floating,
            )
        }
        compose.waitForIdle()
        // mdpi 下 1dp = 1px；悬浮坞底边距屏幕底 21dp。
        assertEquals(rootHeight() - 21f, navBarBottom(), 0.5f)
    }

    @Test
    fun fixedBarDocksFlushToTheBottomEdge() {
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                navStyle = Oe3NavStyle.Fixed,
            )
        }
        compose.waitForIdle()
        // 固定坞贴住系统栏：底边与屏幕底重合（延伸到系统栏后面）。
        assertEquals(rootHeight(), navBarBottom(), 0.5f)
    }

    @Test
    fun surfaceAlphaReachesTheGlassConsumers() {
        assertEquals(0.6f, probe(surfaceAlpha = 0.6f).surfaceAlpha, 0.0001f)
    }
}
