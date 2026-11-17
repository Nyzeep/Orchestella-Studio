package io.github.nyzeep.orchestella.ui.oe3.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.After
import org.junit.Assert.assertEquals
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
 * UI-04 切片 4：分组列表 [OeListGroup]。
 *
 * 圆角规则（shape.md 分组容器）：整组一个 24dp 外圆角容器 clip，组内行保持
 * 0dp —— 首行露出 top 24、末行露出 bottom 24、中间行两侧平直。行规格：
 * 最小高 56dp、上下 padding 10dp（FlClash 列表行 16/10）。
 *
 * 像素断言走临时截图（非 golden）：仅在测试生命周期打开
 * `roborazzi.test.record`，写 build/tmp，不影响其他 golden 测试。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class OeListGroupTest {

    @get:Rule
    val compose = createComposeRule()

    private val recordProperty = "roborazzi.test.record"

    /** 插件接管（record/verify/compare 任务）时像素断言让位给 golden 门。 */
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
    fun groupClipsFirstAndLastRowCornersAndKeepsMiddleRowsSquare() {
        assumeTrue("像素断言只在非 golden 门模式下运行", !pluginOwnsCaptureMode)
        renderGroup(rowCount = 3)
        val group = compose.onNodeWithTag("group").fetchSemanticsNode().boundsInRoot
        val bitmap = captureWindow()

        // 组高 = 3 × 56dp（三行单行文本，全部落在 56dp 下限）。
        assertEquals(168f, group.height)

        // 首行左上角：24dp 圆角把 (5,5) 裁掉 → 黑底。
        assertTrue(
            "首行左上角应被外圆角裁掉（luminance=${luminanceAt(bitmap, group, 5, 5)}）",
            luminanceAt(bitmap, group, 5, 5) < 0.5f,
        )
        // 末行左下角：(5, 163) 在 24dp 圆角外 → 黑底。
        assertTrue(
            "末行左下角应被外圆角裁掉（luminance=${luminanceAt(bitmap, group, 5, 163)}）",
            luminanceAt(bitmap, group, 5, 163) < 0.5f,
        )
        // 中间行左缘：不属于任何圆角区域 → 组面色，行本身是平直的。
        assertTrue(
            "中间行左缘应保持平直露出组面（luminance=${luminanceAt(bitmap, group, 2, 80)}）",
            luminanceAt(bitmap, group, 2, 80) > 0.5f,
        )
    }

    @Test
    fun rowsKeepThe56dpFloorAndThe10dpVerticalRhythm() {
        renderGroup(rowCount = 2, tallTrailing = true)
        val plain = compose.onNodeWithTag("row-plain").fetchSemanticsNode().boundsInRoot
        val tall = compose.onNodeWithTag("row-tall").fetchSemanticsNode().boundsInRoot

        // 单行文本（约 25px 自然行盒）+ 上下 10dp，落在 56dp 下限上。
        assertEquals("短行贴住 56dp 下限", 56f, plain.height)
        // 60dp 高的 trailing 内容 + 上下各 10dp → 80；若上下 padding 是旧
        // 行的 8dp 这里会是 76。
        assertEquals("行内容上下 padding 为 10dp", 80f, tall.height)
    }

    /** 渲染黑底 240dp 宽的分组列表；[tallTrailing] 给末行加 60dp 高的尾部内容。 */
    private fun renderGroup(rowCount: Int, tallTrailing: Boolean = false) {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = true) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = androidx.compose.ui.Alignment.TopCenter,
                ) {
                    OeListGroup(modifier = Modifier.testTag("group").width(240.dp)) {
                        repeat(rowCount) { index ->
                            val isLast = index == rowCount - 1
                            OeListGroupRow(
                                title = "行 ${index + 1}",
                                modifier = Modifier.testTag(
                                    if (isLast && tallTrailing) "row-tall" else "row-plain",
                                ),
                                trailing = if (isLast && tallTrailing) {
                                    { Box(Modifier.width(8.dp).height(60.dp)) }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun captureWindow() = run {
        val image = File("build/tmp/oe-list-group-test").apply { mkdirs() }
            .resolve("group.png")
        compose.onRoot().captureRoboImage(image.absolutePath)
        assertTrue("截图未写出：${image.absolutePath}", image.exists() && image.length() > 0)
        BitmapFactory.decodeFile(image.absolutePath)!!
    }

    private fun luminanceAt(bitmap: android.graphics.Bitmap, group: androidx.compose.ui.geometry.Rect, dx: Int, dy: Int): Float =
        Color(bitmap.getPixel(group.left.toInt() + dx, group.top.toInt() + dy)).luminance()
}
