package io.github.nyzeep.orchestella.ui.screens.wizard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * W2-03：向导步骤切换的 fade-through 零叠化契约。
 *
 * 时间线（与 [io.github.nyzeep.orchestella.ui.shell.Oe3PageTransitionSpecTest]
 * 同源钉值）：退场步骤 90ms 内淡出并轻移 25%，留白 20ms，进场步骤延迟 110ms
 * 后 210ms 淡入——两个透明度窗口零重叠。窗口零重叠意味着**任何时刻至多一个
 * 步骤内容可见**：在留白 gap 帧（退场已结束、进场未开始）内容区必须只显示
 * 背景色，双步骤交叉叠化在几何上不可能。
 *
 * 帧时钟口径：状态变更后的第一个帧 tick（clock=16ms）启动转场，gap 对应
 * clock ∈ (106, 126)，唯一落点帧 = 112（转场 t=96ms ∈ (90, 110)）。
 * 冻结 mainClock 精确推进到该帧采样。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-mdpi")
class WizardStepTransitionTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    /** 步骤可变的桩：委托金样桩的全部 no-op 命令，仅 state 可写。 */
    private class MutableStepStub(state: WizardUiState) : WizardScreenModel by WizardScreenModelStub(state) {
        val stepState = mutableStateOf(state)
        override val state: State<WizardUiState> get() = stepState
    }

    private var backgroundArgb = 0

    private fun mount(stub: MutableStepStub) {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = false) {
                backgroundArgb = Oe3ThemeValues.colors.background
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(Oe3ThemeValues.colors.background)),
                ) {
                    WizardScreen(controller = stub, onExit = {})
                }
            }
        }
        compose.waitForIdle()
    }

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

    /** 内容区采样带（避开步骤指示器与底部操作条）：35%–62% 高度。 */
    private fun contentBand(bitmap: Bitmap): IntRange =
        (bitmap.height * 0.35f).roundToInt()..(bitmap.height * 0.62f).roundToInt()

    /** 带内非背景像素计数（RGB 逐通道容差 2，吸收量化抖动）。 */
    private fun nonBackgroundPixelCount(bitmap: Bitmap): Int {
        val bgR = (backgroundArgb ushr 16) and 0xFF
        val bgG = (backgroundArgb ushr 8) and 0xFF
        val bgB = backgroundArgb and 0xFF
        var count = 0
        for (y in contentBand(bitmap)) {
            for (x in 0 until bitmap.width) {
                val px = bitmap.getPixel(x, y)
                val same =
                    abs(((px ushr 16) and 0xFF) - bgR) <= 2 &&
                        abs(((px ushr 8) and 0xFF) - bgG) <= 2 &&
                        abs((px and 0xFF) - bgB) <= 2
                if (!same) count++
            }
        }
        return count
    }

    @Test
    fun stepSwitchShowsNeitherStepDuringTheFadeThroughGap() {
        val stub = MutableStepStub(WizardPreviewStates.storage)
        mount(stub)
        val restFrame = captureContentBitmap()
        assertTrue(
            "前置自检：静止态步骤内容必须在采样带内可见（非背景像素 > 0）",
            nonBackgroundPixelCount(restFrame) > 0,
        )

        compose.mainClock.autoAdvance = false
        try {
            stub.stepState.value = WizardPreviewStates.network
            // 逐帧采样整个转场窗（30 帧 = 480ms > 320ms）。写入后有数帧陈旧/
            // 挂载帧（静止内容），随后退场淡出、零内容 gap、进场淡入。扫描式
            // 断言对帧索引漂移鲁棒。
            val counts = mutableListOf<Int>()
            repeat(30) {
                compose.mainClock.advanceTimeByFrame()
                counts += nonBackgroundPixelCount(captureContentBitmap())
            }
            val gapIdx = counts.indexOfFirst { it == 0 }
            assertTrue(
                "必须存在零内容 gap 帧（两步透明度窗口零重叠 → 交叉叠化不可能）：counts=$counts",
                gapIdx > 0,
            )
            assertTrue(
                "gap 前必须仍有可见帧（退场步骤淡出中）：counts=$counts",
                counts.take(gapIdx).any { it > 0 },
            )
            val afterGap = counts.drop(gapIdx + 1)
            assertTrue(
                "gap 后必须重新出现可见帧（进场步骤淡入中）：counts=$counts",
                afterGap.any { it > 0 },
            )
            assertEquals(
                "gap 帧内容区必须精确零步骤像素",
                0,
                counts[gapIdx],
            )
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }
}
