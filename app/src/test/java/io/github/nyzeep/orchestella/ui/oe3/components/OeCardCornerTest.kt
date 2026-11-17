package io.github.nyzeep.orchestella.ui.oe3.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.After
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
 * UI-04 切片 1：卡片圆角必须由 [LocalOe3CornerRadius]（主题圆角偏好）驱动，
 * 且未注入偏好时保持规范阶梯的 24dp 不变。
 *
 * 断言走像素：黑底上放一张 200×120 的卡，取卡左上角附近的点。
 * mdpi 下 1dp = 1px。点 (5,5) 在 r=8 时落入卡内（距角圆心约 4.2px），
 * 在 r=24 时落在圆角外（距角圆心 26.9 > 24）——两个半径下该点颜色必然翻转，
 * 这是“半径由 Local 决定”的行为级证据。
 *
 * 本类不是 golden 基线：只在 build/tmp 下写临时位图做像素断言，因此仅在
 * 本类的生命周期里打开 `roborazzi.test.record`（默认门控会静默跳过截图），
 * 不影响其他 golden 测试的 record/verify 行为。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class OeCardCornerTest {

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
    fun cardCornerFollowsTheThemeCornerPreference() {
        assumeTrue("像素断言只在非 golden 门模式下运行", !pluginOwnsCaptureMode)
        val preferred = captureCornerLuminance(cornerRadius = 8.dp)
        assertTrue(
            "LocalOe3CornerRadius=8dp 时，(5,5) 应落入 8dp 圆角之内露出卡面（luminance=$preferred）",
            preferred > 0.5f,
        )
    }

    @Test
    fun cardCornerStaysOnThe24dpLadderWithoutAPreference() {
        assumeTrue("像素断言只在非 golden 门模式下运行", !pluginOwnsCaptureMode)
        val fallback = captureCornerLuminance(cornerRadius = null)
        assertTrue(
            "未注入偏好时保持 24dp：(5,5) 应仍被圆角裁掉而露出黑底（luminance=$fallback）",
            fallback < 0.5f,
        )
    }

    /** 渲染一张黑底居中的卡，返回卡左上角 (5,5) 处的像素亮度。 */
    private fun captureCornerLuminance(cornerRadius: Dp?): Float {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = true) {
                CompositionLocalProvider(LocalOe3CornerRadius provides cornerRadius) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Oe3Card(
                            modifier = Modifier
                                .testTag("corner-card")
                                .width(200.dp)
                                .height(120.dp),
                        ) {
                            Box(Modifier.height(1.dp))
                        }
                    }
                }
            }
        }
        val image = File("build/tmp/oe-card-corner-test").apply { mkdirs() }
            .resolve("card-${cornerRadius?.toString() ?: "default"}.png")
        compose.onRoot().captureRoboImage(image.absolutePath)
        assertTrue("截图未写出：${image.absolutePath}", image.exists() && image.length() > 0)
        val bitmap = BitmapFactory.decodeFile(image.absolutePath)!!
        val origin = compose.onNodeWithTag("corner-card").fetchSemanticsNode().boundsInRoot.topLeft
        // mdpi：1dp = 1px，(5,5) 直接用像素坐标。
        val pixel = bitmap.getPixel(
            origin.x.toInt() + 5,
            origin.y.toInt() + 5,
        )
        return Color(pixel).luminance()
    }
}
