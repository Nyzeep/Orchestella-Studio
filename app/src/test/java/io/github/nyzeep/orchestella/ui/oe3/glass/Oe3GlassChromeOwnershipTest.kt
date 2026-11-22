package io.github.nyzeep.orchestella.ui.oe3.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * W2-05 玻璃所有权仲裁端到端（R-02 红线）：底栏是第一类玻璃所有者，顶栏滚动
 * 边缘是 shell 唯一 haze 源的次级消费者——滚动过折叠阈值经**真实注册路径**
 * （页面位移 → OeScaffold 折叠滞回 → rememberOe3ScrollEdgeGlass → chrome 账本）
 * 出现 scroll edge 后，dock 材质必须保持 haze 路径（L1/L2 模糊底板）。
 *
 * 环境口径与 TransitionGhostPixelTest 一致：Robolectric NATIVE、glassLevel=
 * Standard、reduceMotion=false（reducedMotion 会把两态都压到 L0，探针失去
 * 检出力）。首帧 waitForIdle 落定后再采样。
 *
 * 断言口径：dock 材质用**通道容差逐点比较**（[DOCK_PIXEL_TOLERANCE]）而非
 * 逐点严格相等。机理：源层在重组间重录时，Robolectric 的重模糊在两个仅差
 * ≤9/通道 的稳定渲染间交替（跨运行完全确定的伪影，与 chrome 注册无关）；
 * 而材质真正退化（glassState 被降档 → 96% 回退面替代模糊底板）的通道位移
 * ≥50，容差两侧分离度 ≥3 倍，红线检出力不变。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-mdpi")
class Oe3GlassChromeOwnershipTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private companion object {
        /** 页面端色（dock 后面的内容，静置与滚动态共用）。 */
        val PageCyan = Color(0xFF00FFFF)

        /** 顶栏折叠进入阈值（与 OeScaffoldSpec.ENTER_COLLAPSE_DP 一致的 dp 值）。 */
        val EnterCollapseDp = 56.dp

        /** dock 采样带：避开顶缘抗锯齿的 dy 行位（TransitionGhostPixelTest 同口径）。 */
        val ProbeDys = listOf(6, 14, 32)

        /** dock 横向采样列（宽度的 20% / 50% / 80%）。 */
        val ProbeXFractions = listOf(0.2f, 0.5f, 0.8f)

        /**
         * dock 材质通道容差：吸收 Robolectric 重模糊伪影（实测 ≤9/通道），
         * 远小于回退面替代模糊底板的位移（≥50/通道）。
         */
        const val DOCK_PIXEL_TOLERANCE = 16
    }

    @Test
    fun scrollEdgeRegistration_keepsDockMaterialPixelIdentical() {
        var offsetPx by mutableStateOf(0)
        var capturedChrome: Oe3GlassChromeState? = null
        var enterThresholdPx = 0f
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = false,
                glassLevel = Oe3GlassLevel.Standard,
                pages = Oe3Pages(
                    workstation = {
                        // 页面组合体读 chrome 与密度感知阈值：真实注册路径的观察点。
                        capturedChrome = LocalOe3GlassChrome.current
                        enterThresholdPx = with(LocalDensity.current) { EnterCollapseDp.toPx() }
                        OeScaffold(
                            title = "所有权仲裁",
                            scrollOffsetPx = { offsetPx },
                        ) {
                            Box(Modifier.fillMaxSize().background(PageCyan))
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()
        val chrome = capturedChrome ?: error("页面组合体必须能读到 LocalOe3GlassChrome")

        val dock = compose.onNodeWithTag("shell:nav-bar")
            .fetchSemanticsNode().boundsInRoot
        val probeXs = ProbeXFractions.map { fraction ->
            dock.left.roundToInt() + (dock.width * fraction).roundToInt()
        }
        val dockTop = dock.top.roundToInt()
        fun dockPixel(probeX: Int, dy: Int, bitmap: Bitmap): Int =
            bitmap.getPixel(probeX, dockTop + dy)

        // —— 阶段 A：offset=0 静置基线 ——
        assertFalse("静置时 chrome 账本不得有 scroll edge", chrome.hasScrollEdge)
        val rest = captureRootBitmap(name = "ownership-rest")

        // —— 阶段 B：滚过 56dp 进入阈值（+10px 余量），走真实折叠滞回注册 ——
        compose.runOnUiThread {
            offsetPx = (enterThresholdPx + 10f).roundToInt()
        }
        compose.waitForIdle()
        assertTrue(
            "滚过折叠阈值后必须经 rememberOe3ScrollEdgeGlass 真实路径注册 scroll edge",
            chrome.hasScrollEdge,
        )
        val scrolled = captureRootBitmap(name = "ownership-scrolled")

        // R-02 红线：scroll edge 出现后 dock 材质必须保持模糊底板——glassState
        // 保持非空，不得从 L1/L2 haze 路径退化成 96% 回退面（旧接线行在旧实现必红）。
        for (probeX in probeXs) {
            for (dy in ProbeDys) {
                val before = dockPixel(probeX, dy, rest)
                val after = dockPixel(probeX, dy, scrolled)
                val drifted = listOf(0, 8, 16).any { channel ->
                    abs(((before ushr channel) and 0xFF) - ((after ushr channel) and 0xFF)) >
                        DOCK_PIXEL_TOLERANCE
                }
                assertFalse(
                    "scroll edge 注册后 dock 像素 x=$probeX dy=$dy 材质退化：" +
                        "静置=$before 滚动后=$after（glassState 必须保持非空，底栏不得降档）",
                    drifted,
                )
            }
        }
    }

    /**
     * 桥接分支回归锁（W2-05 审查 P1-2）：页面不传 [OeScaffold] 的
     * scrollOffsetPx 时，滞回位移源桥接到内部 NestedScrollConnection 累积的
     * **未钳制**净滚动距离——修复前误喂被 [OeScaffoldSpec.advance] 钳在
     * 12dp（collapsePx 上限）的 offset，永远够不到 56dp 折叠阈值，无列表位移
     * 源页面（配置页/向导页等）的顶栏滚动边缘玻璃被静默禁用。
     *
     * 真手势路径：LazyColumn 消费拖拽 → nested scroll 分发到 OeScaffold 的
     * onPostScroll → scrollDistancePx 累积 → 滞回翻转 → 账本注册。
     */
    @Test
    fun bridgedScrollPath_reachesCollapseThresholdAndRegistersEdge() {
        var capturedChrome: Oe3GlassChromeState? = null
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = false,
                glassLevel = Oe3GlassLevel.Standard,
                pages = Oe3Pages(
                    workstation = {
                        capturedChrome = LocalOe3GlassChrome.current
                        // 不传 scrollOffsetPx：走 OeScaffold 内部位移桥接分支。
                        OeScaffold(title = "桥接滚动路径") {
                            LazyColumn(
                                Modifier
                                    .fillMaxSize()
                                    .testTag("bridged:list"),
                            ) {
                                items(40) { index ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .background(
                                                if (index % 2 == 0) PageCyan
                                                else Color(0xFF22333344),
                                            ),
                                    )
                                }
                            }
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()
        val chrome = capturedChrome ?: error("页面组合体必须能读到 LocalOe3GlassChrome")

        assertFalse("静置时 chrome 账本不得有 scroll edge", chrome.hasScrollEdge)
        repeat(3) {
            compose.onNodeWithTag("bridged:list").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        assertTrue(
            "桥接分支（无 scrollOffsetPx）必须能滚过 56dp 折叠阈值并注册 scroll edge" +
                "（修复前 offset 被 advance 钳在 12dp，滞回永不翻转）",
            chrome.hasScrollEdge,
        )
    }

    /**
     * 离屏捕获 compose 根成位图（采样口径与 TransitionGhostPixelTest 的
     * dockPixel 模式一致，只是捕获机制不同）。
     *
     * 机理：content view 的软画布捕获（`content.draw(Canvas(bitmap))`）会让
     * haze 在绘制期落到 RenderScript 后端（`updateBlurEffectIfNeeded` 要求
     * `canvas.isHardwareAccelerated` 才走 RenderEffect），在 Robolectric 上取
     * 输入 surface 时 NPE；Roborazzi 的 NATIVE 捕获走硬件加速画布，haze 正常
     * 走 RenderEffect 后端（与 V4GlassScreenshotTest 的金样捕获同一路径）。
     * 普通 testDebugUnitTest 任务下 taskType 默认 None（捕获静默跳过），显式
     * 声明 Record 才会真正写出像素；捕获前先删旧文件保证每次都是纯记录。
     */
    private fun captureRootBitmap(name: String): Bitmap {
        val path = "build/tmp/oe3-ownership/$name.png"
        File(path).delete()
        compose.onRoot().captureRoboImage(
            path,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
        )
        return BitmapFactory.decodeFile(path)
    }
}
