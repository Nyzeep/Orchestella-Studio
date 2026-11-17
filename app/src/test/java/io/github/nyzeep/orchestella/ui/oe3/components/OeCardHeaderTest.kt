package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-04 切片 3：卡内标题行 [OeCardHeader] 的规格。
 * 16dp 内边距；标题 17/22 w600（22px 行高在 mdpi 下直接可测）；
 * 副标题紧贴标题；trailing 靠右；subtitle/trailing 可省略。
 * 定位走语义文本，不给组件强加测试标签。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class OeCardHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun headerPinsTheDocumentedPaddingAndTypeGeometry() {
        var titleStyle: androidx.compose.ui.text.TextStyle? = null
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = true) {
                titleStyle = oeCardTitleStyle()
                Box(Modifier.testTag("header").width(240.dp)) {
                    OeCardHeader(
                        title = "存储",
                        subtitle = "已用 66 GB",
                        trailing = { Text("值", Modifier.testTag("header-trailing")) },
                    )
                }
            }
        }
        val header = compose.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithText("存储").fetchSemanticsNode().boundsInRoot
        val subtitle = compose.onNodeWithText("已用 66 GB").fetchSemanticsNode().boundsInRoot
        val trailing = compose.onNodeWithTag("header-trailing").fetchSemanticsNode().boundsInRoot

        assertEquals("标题从 16dp 内边距开始", header.left + 16f, title.left)
        assertEquals("标题从 16dp 顶部内边距开始", header.top + 16f, title.top)
        // 17/22 w600：字号与行高钉在 token 层，行盒实际高度是平台字体度量
        // （Roboto 自然高约 25px），不作为断言。
        assertEquals(17f, titleStyle?.fontSize?.value)
        assertEquals(22f, titleStyle?.lineHeight?.value)
        assertEquals(FontWeight.SemiBold, titleStyle?.fontWeight)
        assertEquals("副标题紧贴标题", title.bottom, subtitle.top)
        assertEquals("trailing 靠右对齐 16dp 内边距", header.right - 16f, trailing.right)
        assertTrue("trailing 不得越过右内边距", trailing.right <= header.right - 16f)
    }

    @Test
    fun subtitleAndTrailingAreOptional() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = true) {
                OeCardHeader(title = "最近实例")
            }
        }
        compose.onNodeWithText("最近实例").assertExists()
        compose.onNodeWithText("已用 66 GB").assertDoesNotExist()
    }
}
