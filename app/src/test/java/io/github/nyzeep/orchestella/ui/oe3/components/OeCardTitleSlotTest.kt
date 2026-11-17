package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-04 切片 2：卡片标题必须在卡片内部，作为卡内第一行（title 槽）。
 * 首页曾把标题画在卡外（分区标题），v4 以卡内 title 槽取代。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class OeCardTitleSlotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun titleSlotRendersAsTheFirstRowInsideTheCard() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = true) {
                Oe3Card(
                    modifier = Modifier.testTag("card").width(200.dp),
                    title = { Text("卡内标题", Modifier.testTag("card-title")) },
                ) {
                    Text("内容", Modifier.testTag("card-content"))
                }
            }
        }
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("card-title").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("card-content").fetchSemanticsNode().boundsInRoot

        // 标题在卡内第一行：顶部正好是 20dp 内容内边距，且在内容之上。
        assertEquals("标题必须从卡内 20dp 内边距处开始", card.top + 20f, title.top)
        assertTrue("标题必须渲染在内容之前", title.bottom <= content.top)
        // 标题水平方向落在卡内。
        assertTrue(title.left >= card.left && title.right <= card.right)
    }

    @Test
    fun cardWithoutTitleRendersOnlyTheContent() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = true) {
                Oe3Card(modifier = Modifier.testTag("card").width(200.dp)) {
                    Text("内容", Modifier.testTag("card-content"))
                }
            }
        }
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("card-content").fetchSemanticsNode().boundsInRoot
        assertEquals("无标题时内容仍是第一行", card.top + 20f, content.top)
    }
}
