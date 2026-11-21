package io.github.nyzeep.orchestella.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.design.components.OeDialogAction
import io.github.nyzeep.orchestella.ui.design.components.OeDialogHost
import io.github.nyzeep.orchestella.ui.design.components.OeDialogSpec
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassGateVerdict
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassChrome
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassFrameGate
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.OeBackStackHost
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * W1-02（A4-M6）过渡幽灵像素回归钉：两个必绿用例 + Wave 2 红线探针组。
 *
 * == 必绿组（当前实现必须全绿，门禁过滤
 * `--tests "io.github.nyzeep.orchestella.ui.TransitionGhostPixelTest"`）==
 * 1. [reduceMotionToggle_liveSwitchAndDialogSync]：系统动画倍率 1x→0x→1x 热切换后，
 *    [OeDialogHost] 覆盖层实时跟随（W1-02 A4-M1 落地的 ContentObserver 热监听）。
 * 2. [predictiveBack_commitPopsOnce]：提交式返回绝不双弹的边界护栏（每派发一次
 *    返回恰好弹一层；连续提交深度精确递减；空栈第三次提交不消费、不为负）。
 *
 * == Wave 2 红线探针组（全部 @Ignore，禁止把当前缺陷行为写成预期）==
 * - [pagerSwitch_hasNoCrossBlendMix]（R-01 → 解锁 Wave 2 M2）
 * - [scrollPastZero_keepsDockGlassStateNonNull]（R-02 → 解锁 Wave 2 M4）
 * - [frameGate120Hz_coldStartDoesNotLockL0]（R-03 → 解锁 Wave 2 M4）
 * - [backStackCoverage_zeroUnderlaySeeThrough]（栈覆盖零透底护栏 → 解锁 Wave 2 M2）
 * - [backStackCoverage_negative_withoutPlateMustFail]（无底片负例 → 解锁 Wave 2 M2）
 * - [cardPress_firstFrameFeedbackWithin32ms_andReleaseWithin240ms]（→ 解锁 Wave 2 M3）
 *
 * 环境口径：Robolectric 本地 JVM + NATIVE 图形模式；固定 mainClock、无 sleep、
 * 无随机。像素采样统一走 [captureContentBitmap]（content view 离屏 draw），不依赖
 * 真机 GPU / 真实 RenderEffect 模糊渲染结果；玻璃相关探针断言的只是「材质是否
 * 退化/保持」，不是模糊本身的保真度。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-mdpi")
class TransitionGhostPixelTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private companion object {
        /** R-01 / R-04 探针的两个纯端色页背景。 */
        val PageMagenta = Color(0xFFFF00FF)
        val PageCyan = Color(0xFF00FFFF)

        /** 端色 ARGB（比较通道时忽略 alpha，离屏捕获为不透明位图）。 */
        val PURE_MAGENTA_ARGB = 0xFFFF00FF.toInt()
        val PURE_CYAN_ARGB = 0xFF00FFFF.toInt()

        /** 端色比较的通道容差（吸收渲染量化抖动；仍能检出 α≥0.97 的混入）。 */
        const val ENDPOINT_TOLERANCE = 6

        /** 转场接缝/边缘抗锯齿保护带（像素）。 */
        const val SEAM_GUARD_PX = 2

        const val DIALOG_TITLE = "W1-02 热切换对话框"
        const val DIALOG_TAG = "w102:dialog"
    }

    // =========================================================================
    // 必绿组 1：系统动画倍率热切换 → OeDialogHost 覆盖层实时跟随
    // =========================================================================

    /**
     * 系统动画倍率 1x→0x→1x 热切换后，已挂载的 [OeDialogHost] 覆盖层必须实时跟随。
     *
     * 断言路径：
     * 1. 1x 基线下 attach + show：覆盖层 ComposeView 挂到 `android.R.id.content`，
     *    attach 时在 `Settings.Global.ANIMATOR_DURATION_SCALE` URI 上注册
     *    ContentObserver；对话框入场动画（0.88 缩放 / α0.5 起步，α 补间 250ms）
     *    处于中段（冻结 mainClock，只显式推进 2 帧）。
     * 2. 写 0x：同时写 Settings.Global 与 ValueAnimator 隐藏档位（真机上系统会把
     *    Settings 值推进 `ValueAnimator.setDurationScale`；Robolectric 无该推送，
     *    测试同步写两侧保持与真机等价的观察者输入），再 `notifyChange` 触发观察者
     *    （Robolectric 的 ShadowContentResolver 同步 dispatchChange；观察者经主
     *    looper Handler 派发，`shadowOf(Looper).idle()` 兜底）。观察者把
     *    `!ValueAnimator.areAnimatorsEnabled()` 写回 reduceMotion 状态 → 重组合 →
     *    `Oe3DialogSpringEnterMotion` 的 `LaunchedEffect(motion)` 重启并 `snapTo`
     *    终态。
     * 3. 红线断言：切换后对话框立即稳定——再推进 1000ms 像素零变化（无动画残留）。
     *    若覆盖层不跟随（回归），入场弹簧/补间会在 mainClock 推进 1000ms 的窗口里
     *    继续演化，前后两帧必然不同。
     * 4. 0x→1x 恢复同理：内容不丢、已终态的对话框不被回切扰动。
     *
     * 帧时钟说明：compose 测试规则把窗口级 recomposer 工厂换成携带
     * TestMonotonicFrameClock 的共享实例（`AndroidComposeUiTestEnvironment`
     * `withWindowRecomposer`），对话框覆盖层的 ComposeView 与规则宿主同窗口，
     * 因此覆盖层动画同样受 mainClock 控制（Tier5HardeningTest 对话框用例同口径）。
     */
    @Test
    fun reduceMotionToggle_liveSwitchAndDialogSync() {
        val resolver = compose.activity.contentResolver
        val scaleUri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)

        // 基线 1x：Settings 值与 ValueAnimator 隐藏档位同步写。
        compose.runOnUiThread {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            setAnimatorDurationScale(1f)
        }
        val host = OeDialogHost.attach(compose.activity)
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Magenta))
        }
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread {
                host.show(
                    OeDialogSpec(
                        title = DIALOG_TITLE,
                        message = "覆盖层实时跟随系统动画倍率",
                        positive = OeDialogAction("确定"),
                        testTag = DIALOG_TAG,
                    ),
                )
            }
            // 显式推进 2 帧：覆盖层完成首组合，1x 入场动画进行中（α≈0.67）。
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag(DIALOG_TAG).assertExists()
            compose.onNodeWithText(DIALOG_TITLE).assertExists()
            val dialogCenter = compose.onNodeWithTag(DIALOG_TAG)
                .fetchSemanticsNode().boundsInRoot.center
            val midFrame = captureContentBitmap()
            val midPixel = midFrame.getPixel(dialogCenter.x.roundToInt(), dialogCenter.y.roundToInt())

            // 1x → 0x 热切换：写两侧档位 + notifyChange 触发观察者。
            compose.runOnUiThread {
                Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
                setAnimatorDurationScale(0f)
                resolver.notifyChange(scaleUri, null)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            // 重组合 + LaunchedEffect(motion) 重启 + snapTo 终态：给足 4 帧。
            repeat(4) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithText(DIALOG_TITLE).assertExists()
            val settledAfterToggle = captureContentBitmap()
            compose.mainClock.advanceTimeBy(1000)
            val afterAdvance = captureContentBitmap()
            assertTrue(
                "0x 热切换后对话框必须立即稳定（无动画残留）：再推进 1000ms 像素必须不变",
                settledAfterToggle.sameAs(afterAdvance),
            )

            // 前置自检（证明断言有区分力）：切换前确处入场中段，像素未达终态。
            val settledPixel = settledAfterToggle.getPixel(
                dialogCenter.x.roundToInt(),
                dialogCenter.y.roundToInt(),
            )
            assertTrue(
                "前置自检失败：1x 入场中帧像素(mid=$midPixel)已等于终态(settled=$settledPixel)，" +
                    "采样窗口未落在动画中段",
                midPixel != settledPixel,
            )

            // 0x → 1x 恢复：同理热切换，内容不丢、终态不扰动。
            compose.runOnUiThread {
                Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                setAnimatorDurationScale(1f)
                resolver.notifyChange(scaleUri, null)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            repeat(4) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithText(DIALOG_TITLE).assertExists()
            val restored1 = captureContentBitmap()
            compose.mainClock.advanceTimeBy(1000)
            val restored2 = captureContentBitmap()
            assertTrue(
                "恢复 1x 后已终态的对话框必须保持稳定（回切不扰动）",
                restored1.sameAs(restored2),
            )
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DIALOG_TAG).assertExists()
        compose.onNodeWithText(DIALOG_TITLE).assertExists()
    }

    // =========================================================================
    // 必绿组 2：提交式返回绝不双弹
    // =========================================================================

    /**
     * 提交式返回的边界防重入护栏。
     *
     * 护栏口径：**一次提交恰好弹一层**。手势级 predictive back（progress flow 注入）
     * 在 Robolectric 不可注入，本用例锁「提交路径绝不双弹」的行为面：
     * - 系统返回派发（`onBackPressedDispatcher.onBackPressed()`，走 [OeBackStackHost]
     *   内真实的 `PredictiveBackHandler` 提交分支 → `pop()`）一次弹一层；
     * - 连续两次提交后栈深精确归零、显示回到 pager，无残影层；
     * - 空栈第三次提交：公开 API `pop()` 返回 false，深度不为负，界面停在 pager。
     *
     * Wave 2 扩展：M2 转场重构将补手势注入（progress flow 逐事件跟手 + 提交/取消
     * 两分支），届时在真实手势路径上复刻本护栏。
     */
    @Test
    fun predictiveBack_commitPopsOnce() {
        val backStack = OeBackStack()
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = false) {
                OeBackStackHost(backStack = backStack) {
                    Box(Modifier.fillMaxSize().testTag("w102:pager-base"))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("w102:pager-base").assertIsDisplayed()

        compose.runOnUiThread {
            Snapshot.withMutableSnapshot {
                backStack.push(
                    OeDestination("w102-dest-a", "甲层") {
                        Box(Modifier.fillMaxSize().testTag("w102:dest-a"))
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            Snapshot.withMutableSnapshot {
                backStack.push(
                    OeDestination("w102-dest-b", "乙层") {
                        Box(Modifier.fillMaxSize().testTag("w102:dest-b"))
                    },
                )
            }
        }
        compose.waitForIdle()
        assertEquals(2, backStack.size)
        assertTrue(backStack.canPop)
        compose.onNodeWithTag("w102:dest-b").assertIsDisplayed()
        compose.onNodeWithTag("w102:pager-base").assertDoesNotExist()

        // 提交式返回 #1：一次系统返回派发 = 恰好弹一层。
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals("一次提交必须恰好弹一层", 1, backStack.size)
        assertTrue(backStack.canPop)
        assertEquals("栈顶必须是甲层", "w102-dest-a", backStack.top?.key)
        compose.onNodeWithTag("w102:dest-b").assertDoesNotExist()
        compose.onNodeWithTag("w102:dest-a").assertIsDisplayed()
        compose.onNodeWithTag("w102:pager-base").assertDoesNotExist()

        // 提交式返回 #2：再派发一次弹回 pager。
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(0, backStack.size)
        assertFalse(backStack.canPop)
        compose.onNodeWithTag("w102:dest-a").assertDoesNotExist()
        compose.onNodeWithTag("w102:pager-base").assertIsDisplayed()

        // 快速连续提交：同一 snapshot 内连发三次 pop（第三层是空栈护栏）。
        compose.runOnUiThread {
            Snapshot.withMutableSnapshot {
                backStack.push(
                    OeDestination("w102-dest-c", "丙层") {
                        Box(Modifier.fillMaxSize().testTag("w102:dest-c"))
                    },
                )
                backStack.push(
                    OeDestination("w102-dest-d", "丁层") {
                        Box(Modifier.fillMaxSize().testTag("w102:dest-d"))
                    },
                )
                assertEquals(2, backStack.size)
                assertTrue(backStack.pop())
                assertTrue(backStack.pop())
                assertFalse("空栈提交必须不消费且深度不为负", backStack.pop())
                assertEquals(0, backStack.size)
            }
        }
        compose.waitForIdle()
        assertEquals(0, backStack.size)
        compose.onNodeWithTag("w102:pager-base").assertIsDisplayed()
        compose.onNodeWithTag("w102:dest-c").assertDoesNotExist()
        compose.onNodeWithTag("w102:dest-d").assertDoesNotExist()
    }

    // =========================================================================
    // Wave 2 红线探针组（@Ignore：解锁条件见各用例 KDoc，禁止提前凑绿）
    // =========================================================================

    /**
     * R-01（解锁：Wave 2 M2）：Oe3Pager 页签切换全程不得出现交叉混叠。
     *
     * 纯洋红 / 纯青两个测试页切换，冻结 mainClock 以 16ms 步长逐帧采样 30 帧
     * （覆盖 220ms fade + 300ms slide 全窗口），断言任何中帧不出现混合紫。
     *
     * 混合紫判定（R>40 && B>40 && G<40）有一个必须的口径修正：纯洋红端色自身
     * 满足该谓词（R=255、B=255、G=0），若不排除端色则断言在任何实现下都不可满足。
     * 因此计数排除与两页端色同通道的像素（容差 [ENDPOINT_TOLERANCE]），保留对
     * 「洋红里混进青色残影（或反向）」的检出力——当前实现的交叉淡化在转场早/晚段
     * 产生 (≈184, 38, ≈224) 一类的混合紫，正落进判定。
     *
     * 解锁条件：Wave 2 M2 完成 Oe3Pager 转场重构（转场中帧只出现两端色与背景色）
     * 后移除 @Ignore；届时端点自检断言保证采样口径有效，防止凑绿。
     */
    @Test
    @Ignore("Wave 2 M2 fix target: R-01 页签切换交叉混叠（中帧混合紫）。解锁：Wave 2 M2 转场重构完成后移除本注解。")
    fun pagerSwitch_hasNoCrossBlendMix() {
        var selected by mutableStateOf(Oe3Tab.Workstation)
        compose.setContent {
            Oe3App(
                selectedTab = selected,
                onTabSelected = { selected = it },
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = false,
                glassLevel = Oe3GlassLevel.None,
                pages = Oe3Pages(
                    workstation = { Box(Modifier.fillMaxSize().background(PageMagenta)) },
                    instances = { Box(Modifier.fillMaxSize().background(PageCyan)) },
                ),
            )
        }
        compose.waitForIdle()

        // 端点自检：起点页在采样带内必须是纯洋红（防采样口径失效的凑绿）。
        val startFrame = captureContentBitmap()
        assertEquals(
            "端点自检：起点页采样带必须零混合紫",
            0,
            countMixedPurple(startFrame),
        )

        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { selected = Oe3Tab.Instances }
            // 220ms fade + 300ms slide：逐 16ms 扫过整个转场窗（30 帧 = 480ms）。
            repeat(30) { step ->
                compose.mainClock.advanceTimeBy(16)
                val mixed = countMixedPurple(captureContentBitmap())
                assertEquals(
                    "转场中帧 #$step 出现交叉混叠：混合紫像素（非端色 R>40&&B>40&&G<40）=$mixed",
                    0,
                    mixed,
                )
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        // 终点自检：切页真实发生（nav bar 仍在、新页占位标题出现）。
        compose.onNodeWithTag("shell:nav-bar").assertExists()
    }

    /**
     * R-02（解锁：Wave 2 M4）：滚动 >0px 后底栏 glassState 仍非 null。
     *
     * 可观测面说明：缺陷在 [Oe3App] 的接线行
     * `glassState = if (glassChrome.hasScrollEdge) null else glassState`——滚动过零
     * 注册 scroll edge 后底栏被降级。底栏收到的 HazeState 参数本身无对外 seam，
     * 本探针用「dock 材质不得随 scroll edge 出现而退化」作为代理断言：
     *
     * - reduceMotion 必须为 **false**：reducedMotion=true 会把两种 state 都压到
     *   L0/Fallback 同材质（`resolveTier` 的省电/减少动态硬闸先于 state 判定），
     *   探针将失去检出力（凑绿陷阱）。
     * - state 非空 → tier L2 → `Oe3GlassRender.Blurred` → Robolectric 4.18 的
     *   ShadowNativeRenderEffect 使 `createBlurEffect` 探针通过 → 走 hazeChild
     *   路径；state 为 null → `enabled && state != null` 为 false → tier L0 →
     *   FallbackSurface（fallbackTint + 噪点 + 24dp scrim + 顶高光）。两条渲染
     *   路径逐像素可分，且本断言不依赖模糊渲染保真度——只断言「材质不退化」。
     *
     * 滚动等价态：生产滚动头经 `rememberOe3ScrollEdgeGlass` 在 chrome 上注册
     * scroll edge，但注册以「真实模糊半径解析成功」为门槛。这里直接在
     * [LocalOe3GlassChrome] 上注册同一 chrome 状态（与生产 DisposableEffect 同一
     * 注册点），锁定「注册后底栏接线」这一受缺陷影响的状态组合。
     *
     * 解锁条件：Wave 2 M4 修复底栏玻璃接线后移除 @Ignore；修复若引入 dock
     * glassState 的只读 seam（如测试句柄/CompositionLocal），可把代理断言升级为
     * 直读断言。
     */
    @Test
    @Ignore("Wave 2 M4 fix target: R-02 滚动过零后底栏 glassState 被置 null。解锁：Wave 2 M4 修复底栏玻璃接线后移除本注解。")
    fun scrollPastZero_keepsDockGlassStateNonNull() {
        var edgeRegistered by mutableStateOf(false)
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = false,
                glassLevel = Oe3GlassLevel.Standard,
                pages = Oe3Pages(
                    workstation = {
                        val chrome = LocalOe3GlassChrome.current
                        val edgeOwner = remember { Any() }
                        if (edgeRegistered) {
                            DisposableEffect(edgeOwner) {
                                chrome?.addScrollEdge(edgeOwner)
                                onDispose { chrome?.removeScrollEdge(edgeOwner) }
                            }
                        }
                        Box(Modifier.fillMaxSize().background(PageCyan))
                    },
                ),
            )
        }
        compose.waitForIdle()

        val dock = compose.onNodeWithTag("shell:nav-bar")
            .fetchSemanticsNode().boundsInRoot
        val probeX = dock.left.roundToInt() + (dock.width * 0.2f).roundToInt()
        val dockTop = dock.top.roundToInt()
        fun dockPixel(dy: Int, bitmap: Bitmap): Int =
            bitmap.getPixel(probeX, dockTop + dy)

        val rest = captureContentBitmap()

        // 注册 scroll edge（滚动 >0px 的 chrome 等价态）。
        compose.runOnUiThread { edgeRegistered = true }
        compose.waitForIdle()
        val afterEdge = captureContentBitmap()

        // 红线：scroll edge 出现后 dock 材质必须与静置逐像素一致——glassState
        // 保持非空，不得从 haze 路径退化成 L0 回退面（当前实现必红）。
        listOf(SEAM_GUARD_PX + 4, 14, 32).forEach { dy ->
            assertEquals(
                "滚动过零（scroll edge 注册）后 dock 顶部像素 dy=$dy 必须与静置一致" +
                    "（glassState 保持非空，材质不得退化）",
                dockPixel(dy, rest),
                dockPixel(dy, afterEdge),
            )
        }
    }

    /**
     * R-03（解锁：Wave 2 M4）：8.33ms 预算（120Hz）下冷启动 60 帧不得误锁 L0。
     *
     * 直接喂 [Oe3GlassFrameGate] 纯数据层（其 KDoc 明确「单测用假帧时钟直接喂
     * [Oe3GlassFrameGate.onFrame]」）：冷启动剖面 = 前 6 帧预热抖动（18→9.8ms）
     * + 稳态 120Hz 帧耗时 ≈9ms。真实 120Hz 帧耗时略高于 8.33ms 预算是常态而非
     * 卡顿，按当前阈值（P90 > 预算×0.7 = 5.83ms、掉帧率 > 2%）整段剖面必然被判
     * 降档 → 永久锁 L0，这正是 R-03 缺陷。
     *
     * 红线断言：`verdict.downgrade == false`。
     *
     * 解锁条件：Wave 2 M4 修正性能门冷启动口径（预热帧豁免 / 预算相对阈值等）后
     * 移除 @Ignore；若修复改变剖面语义（如引入预热帧数常量），解锁时按新契约微调
     * 剖面但保持「正常 120Hz 冷启动不得降档」的红线不变。
     */
    @Test
    @Ignore("Wave 2 M4 fix target: R-03 8.33ms 预算（120Hz）冷启动 60 帧误锁 L0。解锁：Wave 2 M4 修正性能门冷启动口径后移除本注解。")
    fun frameGate120Hz_coldStartDoesNotLockL0() {
        val budgetMs = 1000.0 / 120.0
        val verdicts = mutableListOf<GlassGateVerdict>()
        val gate = Oe3GlassFrameGate(budgetMs = budgetMs) { verdicts += it }

        val coldStartFrames = listOf(18.0, 15.0, 13.0, 11.5, 10.5, 9.8) + List(54) { 9.0 }
        coldStartFrames.forEach { gate.onFrame(it) }

        assertTrue("60 帧采样后必须恰好给出一次裁决", gate.isDone && verdicts.size == 1)
        val verdict = verdicts.single()
        assertFalse(
            "红线：120Hz 冷启动 60 帧不得触发降档锁 L0" +
                "（p90=${verdict.p90Ms}ms, dropped=${verdict.droppedRatio}, budget=$budgetMs）",
            verdict.downgrade,
        )
    }

    /**
     * 栈转场零透底护栏（解锁：Wave 2 M2）：push 后用 [OeBackStackHost] 的手势
     * 进度注入（internal 重载，Wave 2 M2 手势接入的同一注入点）把移动层钉在
     * 25% / 50% / 75% 覆盖，采样覆盖区断言**零下层透底**——每个像素都必须是
     * 覆盖页端色（OePagePlate 不透明纪律）。
     *
     * gestureProgress p 与移动层进度互为 1−p（1f = 未拉开）：p=0.75 → 移动层
     * 进度 0.25 → 覆盖右侧 25%。
     *
     * 当前实现带底片，本断言今日即可满足；@Ignore 标记为 Wave 2 M2 的回归护栏
     * ——M2 重构转场几何/底片挂载时必须先解锁本用例，重构后覆盖区仍须零透底。
     */
    @Test
    @Ignore("Wave 2 M2 guard: 栈转场 25/50/75% 覆盖区零下层透底护栏，随 Wave 2 M2 转场重构解锁。")
    fun backStackCoverage_zeroUnderlaySeeThrough() {
        val backStack = OeBackStack()
        var gestureProgress by mutableStateOf<Float?>(null)
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = false) {
                OeBackStackHost(
                    backStack = backStack,
                    gestureProgress = gestureProgress,
                    base = { Box(Modifier.fillMaxSize().background(PageCyan)) },
                )
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            Snapshot.withMutableSnapshot {
                backStack.push(
                    OeDestination("w102-cover", "覆盖层") {
                        Box(Modifier.fillMaxSize().background(PageMagenta))
                    },
                )
            }
        }
        compose.waitForIdle()
        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width

        listOf(0.75f, 0.5f, 0.25f).forEach { gesture ->
            compose.runOnUiThread { gestureProgress = gesture }
            compose.waitForIdle()
            val img = captureContentBitmap()
            val coveragePx = (rootWidth * (1f - gesture)).roundToInt()
            val xFrom = (rootWidth - coveragePx + SEAM_GUARD_PX).roundToInt()
                .coerceIn(0, img.width)
            var leak = 0
            for (y in bandRange(img)) {
                for (x in xFrom until img.width) {
                    if (!isEndpointColor(img.getPixel(x, y), PURE_MAGENTA_ARGB)) leak++
                }
            }
            assertEquals(
                "覆盖 ${(coveragePx * 100 / rootWidth)}% 时覆盖区必须零下层透底" +
                    "（非端色像素 $leak > 0）",
                0,
                leak,
            )
        }
        compose.runOnUiThread { gestureProgress = null }
        compose.waitForIdle()
        assertEquals(1, backStack.size)
    }

    /**
     * 无底片必红负例（解锁：Wave 2 M2）：证明正例 [backStackCoverage_zeroUnderlaySeeThrough]
     * 的采样口径对「撤掉 OePagePlate」的回归有检出力。
     *
     * 以与 `StackLayer.Moving` 相同的几何（translationX = (1−p)×页宽）复刻一个
     * **不带不透明底片**的移动层（页面内容 α0.6），下层青色必然透进覆盖区——
     * 正例的「覆盖区全是纯端色」口径在此必然破防。若 Wave 2 M2 重构移除了真实
     * StackLayer 的 OePagePlate，正例立即转红，本负例固定该检出能力的基准。
     */
    @Test
    @Ignore("Wave 2 M2 companion: 无底片负例（固定采样口径的检出能力），随 Wave 2 M2 转场重构解锁。")
    fun backStackCoverage_negative_withoutPlateMustFail() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(PageCyan)) {
                // 无底片移动层复刻：同 StackLayer.Moving 几何，页面透明（撤掉 OePagePlate 的回归态）。
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = (1f - 0.5f) * size.width },
                ) {
                    Box(Modifier.fillMaxSize().background(PageMagenta.copy(alpha = 0.6f)))
                }
            }
        }
        compose.waitForIdle()
        val img = captureContentBitmap()
        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val xFrom = (rootWidth * 0.5f).roundToInt() + SEAM_GUARD_PX

        var total = 0
        var pure = 0
        for (y in bandRange(img)) {
            for (x in xFrom until img.width) {
                total++
                if (isEndpointColor(img.getPixel(x, y), PURE_MAGENTA_ARGB)) pure++
            }
        }
        assertTrue(
            "无底片复刻必须检出透底混合（纯端色 $pure / $total）——" +
                "撤掉 OePagePlate 后正例口径必须破防",
            pure < total,
        )
    }

    /**
     * 按压时序红线（解锁：Wave 2 M3）：Oe3Card 首帧反馈 ≤32ms、释放回退 ≤240ms。
     *
     * 渲染与取色口径沿用 OeCardPressTimingTest（黑底居中 200×120 卡、卡心取色、
     * mainClock 冻结逐段推进）。当前实现（150ms 揭示延迟 + 250ms 释放回退）：
     * - **32ms 检查是确定性红点**：仍在 150ms 揭示延迟内，洗色比例恰好为 0，
     *   卡心像素等于静置卡面 → 断言必红；
     * - **240ms 释放检查是规格边界钉**：250ms 回退在 240ms 处的残差（≈4% 洗色，
     *   再经减速缓动压缩）低于 8-bit 像素量化步长，本检查对 250ms 与 ≤240ms 的
     *   实现可能同样通过——它钉住「≤240ms 必须归零」的规格边界，真正的缺陷检出
     *   由 32ms 检查承担。
     *
     * 解锁条件：Wave 2 M3 修正按压时序（首帧反馈 ≤32ms 且释放 ≤240ms）后移除
     * @Ignore；解锁时同步钉回 [io.github.nyzeep.orchestella.ui.design.tokens.OeMotion]
     * 的 CARD_PRESS_* 常量口径。
     */
    @Test
    @Ignore("Wave 2 M3 fix target: 卡片按压首帧反馈 ≤32ms / 释放回退 ≤240ms（当前 150ms 揭示延迟 + 250ms 释放）。解锁：Wave 2 M3 按压时序修正后移除本注解。")
    fun cardPress_firstFrameFeedbackWithin32ms_andReleaseWithin240ms() {
        compose.mainClock.autoAdvance = false
        var surfaceArgb = 0
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = false) {
                val colors = Oe3ThemeValues.colors
                surfaceArgb = colors.surface
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Oe3Card(
                        modifier = Modifier
                            .testTag("w102:press-card")
                            .width(200.dp)
                            .height(120.dp),
                        onClick = {},
                    ) {
                        Box(Modifier.height(1.dp))
                    }
                }
            }
        }
        val cardCenter = compose.onNodeWithTag("w102:press-card")
            .fetchSemanticsNode().boundsInRoot.center
        fun cardPixel(): Int {
            val img = captureContentBitmap()
            return img.getPixel(cardCenter.x.roundToInt(), cardCenter.y.roundToInt())
        }

        // 红线 1：按下后 ≤32ms 必须已有首帧反馈（当前 150ms 延迟 → 必红）。
        compose.onNodeWithTag("w102:press-card").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(32)
        val at32 = cardPixel()
        assertTrue(
            "按压 32ms 必须已有首帧反馈（at32=$at32 == 静置卡面 → 零反馈）",
            at32 != surfaceArgb,
        )

        // 红线 2：释放后 ≤240ms 必须回到静置卡面（当前 250ms 回退 → 必红）。
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("w102:press-card").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(240)
        assertEquals(
            "释放 ≤240ms 必须回到静置卡面",
            surfaceArgb,
            cardPixel(),
        )
        compose.mainClock.autoAdvance = true
    }

    // =========================================================================
    // 私有工具：像素采样口径（必绿与探针共用）
    // =========================================================================

    /**
     * 离屏捕获 activity 内容层（含对话框覆盖层等全部子 view）。与
     * Tier5HardeningTest 的对话框采样同口径：content view 直接 draw 到 ARGB_8888
     * 位图，不经过 PixelCopy，也不需要推进 compose 时钟，冻结帧期间可用。
     */
    private fun captureContentBitmap(): Bitmap {
        val content = compose.activity.findViewById<View>(android.R.id.content)
        val bitmap = Bitmap.createBitmap(
            content.width.coerceAtLeast(1),
            content.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        content.draw(Canvas(bitmap))
        return bitmap
    }

    /** 垂直采样带：避开顶部标题区与底部坞（20%–75% 高度）。 */
    private fun bandRange(bitmap: Bitmap): IntRange =
        (bitmap.height * 0.20f).roundToInt()..(bitmap.height * 0.75f).roundToInt()

    /** 端色比较：RGB 逐通道容差 [ENDPOINT_TOLERANCE]，忽略 alpha。 */
    private fun isEndpointColor(px: Int, endpointArgb: Int): Boolean =
        abs(((px ushr 16) and 0xFF) - ((endpointArgb ushr 16) and 0xFF)) <= ENDPOINT_TOLERANCE &&
            abs(((px ushr 8) and 0xFF) - ((endpointArgb ushr 8) and 0xFF)) <= ENDPOINT_TOLERANCE &&
            abs((px and 0xFF) - (endpointArgb and 0xFF)) <= ENDPOINT_TOLERANCE

    /**
     * 混合紫计数（R-01 口径）：R>40 && B>40 && G<40，且排除与两页端色同通道的
     * 像素（纯洋红自身满足该谓词，不排除则断言不可满足；见用例 KDoc）。
     */
    private fun countMixedPurple(bitmap: Bitmap): Int {
        var count = 0
        for (y in bandRange(bitmap)) {
            for (x in 0 until bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                if (r > 40 && b > 40 && g < 40 &&
                    !isEndpointColor(px, PURE_MAGENTA_ARGB) &&
                    !isEndpointColor(px, PURE_CYAN_ARGB)
                ) {
                    count++
                }
            }
        }
        return count
    }

    /**
     * 隐藏平台 API：`Settings.Global.ANIMATOR_DURATION_SCALE` 在真机上最终由系统
     * 推进 `ValueAnimator.setDurationScale`。反射调用以保证对公开 SDK 编译
     * （先例：OrchestellaMotionReducedTest / Tier5HardeningTest）。
     */
    private fun setAnimatorDurationScale(scale: Float) {
        val method = ValueAnimator::class.java
            .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(null, scale)
    }

    @After
    fun restoreAnimatorScale() {
        setAnimatorDurationScale(1f)
        Settings.Global.putFloat(
            compose.activity.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }
}
