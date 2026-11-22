package io.github.nyzeep.orchestella.ui.oe3.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.design.tokens.OeDuration
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * UI-04 切片 5（W2-04 收紧）：卡片按压反馈即时化——首帧即显墨洗、Micro 淡入
 * 收尾、Short 释放回退、取消快速退回，全部取自 [OeMotion] token，组件与页面
 * 内不得出现硬编码时长。
 *
 * 行为断言（mdpi，1dp = 1px，PNG 无损，端点色逐通道精确比较）：
 * - 按下后 32ms（≤ 首帧红线）→ 洗色必须可见（首帧洗色 snap 保证，帧陈旧
 *   也不会读回静置面）——这是 W2-04 的红绿分界（旧实现 150ms 揭示延迟内恒 0）。
 * - 200ms（≥ 首帧洗色 + Micro 淡入）→ 卡心像素等于 10% ink 的按压色端点。
 * - 抬起后 60ms 处于 Short 释放回退中 → 介于两端点之间。
 * - 抬起后 240ms（W2-04 红线 ≤240ms）→ 回到静置卡面。
 * - 滚动抢占（Cancel）→ 快速退回静置卡面，墨洗不得滞留。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class OeCardPressTimingTest {

    @get:Rule
    val compose = createComposeRule()

    private val recordProperty = "roborazzi.test.record"

    /**
     * 本类不是 golden：只在普通 testDebugUnitTest（record/verify/compare 均未
     * 由插件接管）里临时开启 record 写临时位图。校验任务里像素断言让位给
     * golden 门，仅保留常量钉值。
     */
    private var pluginOwnsCaptureMode = false

    @Before
    fun enableTemporaryCapture() {
        // 先于自身写入读取插件属性，避免 @Before 设置的 record 被自己的
        // @Test 守卫误读为“插件接管”。
        pluginOwnsCaptureMode = System.getProperty("roborazzi.test.record") != null ||
            System.getProperty("roborazzi.test.verify") != null ||
            System.getProperty("roborazzi.test.compare") != null
        if (!pluginOwnsCaptureMode) {
            System.setProperty(recordProperty, "true")
        }
    }

    @After
    fun disableTemporaryCapture() {
        if (!pluginOwnsCaptureMode) {
            System.clearProperty(recordProperty)
        }
    }

    @Test
    fun pressTimingConstantsArePinnedInTheSingleSource() {
        assertEquals(0, OeMotion.CARD_PRESS_REVEAL_DELAY_MS)
        // 120/200 是 v2 时长阶梯的 Micro/Short 档——钉住令牌接线而非裸数字。
        assertEquals(OeDuration.Micro, OeMotion.CARD_PRESS_TINT_FADE_IN_MS)
        assertEquals(OeDuration.Short, OeMotion.CARD_PRESS_RELEASE_MS)
        assertEquals(OeDuration.Short, OeMotion.CARD_PRESS_INK_FADE_MS)
        assertEquals(0.85f, OeMotion.CARD_PRESS_FIRST_FRAME_WASH, 0.0001f)
        assertEquals(0.985f, OeMotion.CARD_PRESS_SCALE, 0.0001f)
    }

    @Test
    fun pressShowsTheWashOnTheFirstFrameThenCompletesWithinTheMicroTick() {
        assumeTrue("像素断言只在非 golden 门模式下运行", !pluginOwnsCaptureMode)
        val colors = renderPressingCard()

        compose.onNodeWithTag("press-card").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(32)
        assertNotEquals(
            "按压 32ms 必须已有首帧墨洗反馈（W2-04 红线，旧 150ms 揭示延迟恒 0）",
            colors.surfaceArgb,
            centerPixel(),
        )

        compose.mainClock.advanceTimeBy(168)
        assertEquals(
            "200ms ≥ 首帧洗色 + Micro 淡入，洗色到达 10% ink 端点",
            colors.pressedArgb,
            centerPixel(),
        )
    }

    @Test
    fun releaseSettlesBackWithin240ms() {
        assumeTrue("像素断言只在非 golden 门模式下运行", !pluginOwnsCaptureMode)
        val colors = renderPressingCard()

        compose.onNodeWithTag("press-card").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("press-card").performTouchInput { up() }

        compose.mainClock.advanceTimeBy(60)
        val mid = centerPixel()
        assertNotEquals("释放 60ms 时洗色尚未回到静置面", colors.surfaceArgb, mid)
        assertNotEquals("释放 60ms 时洗色尚未贴住按压色", colors.pressedArgb, mid)

        compose.mainClock.advanceTimeBy(180)
        assertEquals(
            "释放 ≤240ms 必须回到静置卡面（W2-04 红线）",
            colors.surfaceArgb,
            centerPixel(),
        )
    }

    @Test
    fun scrollStealCancelRetreatsQuicklyToRest() {
        assumeTrue("像素断言只在非 golden 门模式下运行", !pluginOwnsCaptureMode)
        val colors = renderPressingCard()

        compose.onNodeWithTag("press-card").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("press-card").performTouchInput { cancel() }

        compose.mainClock.advanceTimeBy(200)
        assertEquals(
            "滚动抢占（Cancel）后墨洗必须快速退回静置卡面",
            colors.surfaceArgb,
            centerPixel(),
        )
    }

    private class PressColors(val surfaceArgb: Int, val pressedArgb: Int)

    /** 渲染黑底居中的可点击卡（200×120），并读出静置/按压两个端点色。 */
    private fun renderPressingCard(): PressColors {
        var surfaceArgb = 0
        var pressedArgb = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = false) {
                val colors = Oe3ThemeValues.colors
                surfaceArgb = colors.surface
                // pressedFill 已是 ARGB Int，与卡面 lerp 的目标端完全一致。
                pressedArgb = ThemeColors.pressedFill(colors.surface, colors.onSurface)
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Oe3Card(
                        modifier = Modifier
                            .testTag("press-card")
                            .width(200.dp)
                            .height(120.dp),
                        onClick = {},
                    ) {
                        Box(Modifier.height(1.dp))
                    }
                }
            }
        }
        return PressColors(surfaceArgb, pressedArgb)
    }

    /** 取卡心像素的 ARGB。 */
    private fun centerPixel(): Int {
        val origin = compose.onNodeWithTag("press-card").fetchSemanticsNode().boundsInRoot.center
        val image = File("build/tmp/oe-card-press-test").apply { mkdirs() }
            .resolve("frame.png")
        compose.onRoot().captureRoboImage(image.absolutePath)
        assertTrue("帧未写出：${image.absolutePath}", image.exists() && image.length() > 0)
        val bitmap = BitmapFactory.decodeFile(image.absolutePath)!!
        return bitmap.getPixel(origin.x.toInt(), origin.y.toInt())
    }
}
