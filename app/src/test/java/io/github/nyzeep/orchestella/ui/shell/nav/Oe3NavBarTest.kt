package io.github.nyzeep.orchestella.ui.shell.nav

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W2-04 复点语义（Cashew 模式净室转译）：点击已激活 Tab 透传 onSelect 的同时
 * 触发 [Oe3NavBar.onTabReselected] 钩子（W2-06 各页回顶的标准入口）；点击其他
 * Tab 只走 onSelect。钩子默认 null，既有调用方源码兼容。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class Oe3NavBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tappingTheSelectedTabRaisesTheReselectHookAndKeepsTheSelection() {
        var selectedBy: Oe3Tab? = null
        var reselected: Oe3Tab? = null
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = false) {
                Oe3NavBar(
                    selected = Oe3Tab.Instances,
                    onSelect = { selectedBy = it },
                    onTabReselected = { reselected = it },
                )
            }
        }

        compose.onNodeWithText("实例").performClick()

        assertEquals("复点已激活 Tab 必须触发 onTabReselected", Oe3Tab.Instances, reselected)
        assertEquals("onSelect 透传行为保持不变", Oe3Tab.Instances, selectedBy)
    }

    @Test
    fun tappingAnotherTabOnlySelects() {
        var selectedBy: Oe3Tab? = null
        var reselected: Oe3Tab? = null
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotLight, reduceMotion = false) {
                Oe3NavBar(
                    selected = Oe3Tab.Instances,
                    onSelect = { selectedBy = it },
                    onTabReselected = { reselected = it },
                )
            }
        }

        compose.onNodeWithText("工作站").performClick()

        assertEquals(Oe3Tab.Workstation, selectedBy)
        assertNull("切页不得触发复点回顶钩子", reselected)
    }
}
