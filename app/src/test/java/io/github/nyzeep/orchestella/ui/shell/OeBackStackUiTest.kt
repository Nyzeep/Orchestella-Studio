package io.github.nyzeep.orchestella.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 栈式导航在真实壳层根（[Oe3App]）上的行为钉（工单 UI-03 验收标准第 1 条）：
 *
 * - push 后演示目的地覆盖 pager，系统返回键 pop 回原页（两级逐层弹出）；
 * - 坞 tab 切换清栈（栈语义不跨 tab 拖尾）；
 * - 栈句柄经 [LocalOeBackStack] 对页面槽位可达（Wave 4 二级页的 push 入口）。
 *
 * 栈空时 `BackHandler` enabled=false、事件放行给宿主——该语义由
 * [OeBackStackTest.anEmptyStackCannotPop] 与 `pop()` 返回值钉住；派发链上
 * 更外层的行为（tab 回工作站/退出）归 `Oe3ShellController` 与宿主 Activity，
 * 不在本用例范围内。push 触发器是页面槽位里的测试锚点（Wave 4 由真实
 * 二级页入口替代）。减动效开启（reduceMotion = true）验证的正是"全时长
 * 归零、状态即时落位"的红线。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class OeBackStackUiTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private fun setContent() {
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        // CompositionLocal 只能在组合上下文里读；事件回调里用捕获值。
                        val stack = LocalOeBackStack.current
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("oe-test-push-trigger")
                                .clickable {
                                    stack?.push(OeBackStackDemo.destination())
                                },
                        )
                    },
                ),
            )
        }
        compose.waitForIdle()
    }

    private fun pushDemo() {
        compose.onNodeWithTag("oe-test-push-trigger").performClick()
        compose.waitForIdle()
    }

    @Test
    fun pushedDestinationCoversThePagerAndPopBringsItBack() {
        setContent()
        compose.onNodeWithText(OeBackStackDemo.TITLE).assertDoesNotExist()
        pushDemo()
        compose.onNodeWithTag(OeBackStackDemo.PAGE_TAG).assertExists()
        compose.onNodeWithText(OeBackStackDemo.TITLE).assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithTag(OeBackStackDemo.PAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun pushingTwicePopsOneLevelAtATime() {
        setContent()
        pushDemo()
        compose.onNodeWithTag(OeBackStackDemo.PAGE_TAG).assertExists()
        // 第二层从演示页页内触发（真实二级页同样可以继续 push）；页外触发器
        // 已被演示页覆盖，触摸注入落在覆盖层上，这正是栈的可视语义。
        compose.onNodeWithTag(OeBackStackDemo.NESTED_TRIGGER_TAG).performClick()
        compose.waitForIdle()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithTag(OeBackStackDemo.PAGE_TAG).assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithTag(OeBackStackDemo.PAGE_TAG).assertDoesNotExist()
    }

    @Test
    fun selectingADockTabClearsTheStack() {
        setContent()
        pushDemo()
        compose.onNodeWithText(Oe3Tab.Instances.label).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OeBackStackDemo.PAGE_TAG).assertDoesNotExist()
        // 切页后落在新 tab 的占位标题上（instances 槽位为空 → placeholder）。
        compose.onNodeWithText(Oe3Tab.Instances.label).assertExists()
    }

    @Test
    fun theShellPublishesTheStackToLocal() {
        var handle: OeBackStack? = null
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        handle = LocalOeBackStack.current
                    },
                ),
            )
        }
        compose.waitForIdle()
        assertNotNull(handle)
        assertFalse(handle!!.canPop)
    }
}
