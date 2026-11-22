package io.github.nyzeep.orchestella.ui.design.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * W2-04 流光门控回归锁：环境辉光只在「减动效关闭 + 宿主 RESUMED + 可交互 +
 * 在屏」四个条件同时成立时推进；任一不满足即静止在确定相位，零帧回调。
 * 帧级像素比较（mainClock 冻结逐段推进，mdpi 无损 PNG）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class OeSliderGlowGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val recordProperty = "roborazzi.test.record"

    /**
     * 本类不是 golden：与 OeCardPressTimingTest 同口径，只在普通
     * testDebugUnitTest（record/verify/compare 均未由插件接管）里临时开启
     * record 写临时位图，否则 captureRoboImage 是空操作。
     */
    private var pluginOwnsCaptureMode = false

    @Before
    fun enableTemporaryCapture() {
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

    /** 黑底居中渲染 40% 值的滑块，时钟冻结。 */
    private fun renderSlider(enabled: Boolean, reduceMotion: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = reduceMotion) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    OeSlider(
                        value = 0.4f,
                        onValueChange = {},
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }

    private fun frame(tag: String): Bitmap {
        val image = File("build/tmp/oe-slider-glow-test").apply { mkdirs() }.resolve("$tag.png")
        compose.onRoot().captureRoboImage(image.absolutePath)
        assertTrue("帧未写出：${image.absolutePath}", image.exists() && image.length() > 0)
        return BitmapFactory.decodeFile(image.absolutePath)!!
    }

    /** 任一像素通道差异即视为画面变动。 */
    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        check(a.width == b.width && a.height == b.height)
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return true
            }
        }
        return false
    }

    @Test
    fun glowAdvancesWhileInteractiveAndOnScreen() {
        renderSlider(enabled = true, reduceMotion = false)
        // 先推进一帧窗口让布局与首绘落定，比较窗口内只剩流光一个变量。
        compose.mainClock.advanceTimeBy(500)
        val first = frame("active-500")
        compose.mainClock.advanceTimeBy(2000)
        val later = frame("active-2500")
        assertTrue("可交互在屏滑块的流光必须推进", differs(first, later))
    }

    @Test
    fun glowRestsWhileDragged() {
        renderSlider(enabled = true, reduceMotion = false)
        compose.onRoot().performTouchInput { down(center) }
        // 先让拇指按压缩放落定，画面只剩流光一个变量。
        compose.mainClock.advanceTimeBy(500)
        val first = frame("drag-500")
        compose.mainClock.advanceTimeBy(2000)
        val later = frame("drag-2500")
        assertFalse("拖拽中的滑块流光必须静止（W2-04 门控）", differs(first, later))
    }

    @Test
    fun glowRestsWhileDisabled() {
        renderSlider(enabled = false, reduceMotion = false)
        compose.mainClock.advanceTimeBy(500)
        val first = frame("disabled-500")
        compose.mainClock.advanceTimeBy(2000)
        val later = frame("disabled-2500")
        assertFalse("禁用滑块的流光必须静止", differs(first, later))
    }

    @Test
    fun reducedMotionRestsTheGlow() {
        renderSlider(enabled = true, reduceMotion = true)
        compose.mainClock.advanceTimeBy(500)
        val first = frame("reduced-500")
        compose.mainClock.advanceTimeBy(2000)
        val later = frame("reduced-2500")
        assertFalse("减动效下滑块流光必须静止在确定相位", differs(first, later))
    }
}
