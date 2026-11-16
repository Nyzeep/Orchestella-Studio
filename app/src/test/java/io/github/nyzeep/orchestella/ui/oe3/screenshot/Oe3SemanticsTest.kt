package io.github.nyzeep.orchestella.ui.oe3.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ListRow
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Ring
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3ShellController
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Accessibility contract for the real Oe3 shell (`ui/shell/Oe3App.kt`).
 *
 * The shell is rendered as-is with motion disabled and the oe3 mode passed
 * explicitly (never through the debug-only intent gate), mirroring the wiring
 * `MainActivity.mountOe3Shell` uses: the selection lives in a real
 * [Oe3ShellController] and the bar writes back through [Oe3ShellController.select].
 * The production nav bar exposes no testTags, so a destination is located as
 * the merged `Role.Tab` node carrying its label. The expected labels are
 * hardcoded here rather than read from [Oe3Tab], so removing or renaming a
 * destination fails the test instead of moving the goalposts. The back
 * fallthrough of the controller itself is pinned by `Oe3ShellModelTest`;
 * this suite covers the composed shell contract on top of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class Oe3SemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun realShellHasFiveTabsAndOneSelectedDestination() {
        compose.setContent {
            RealShell(controller = Oe3ShellController(Oe3Tab.Hardware))
        }

        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertCountEquals(ExpectedNavLabels.size)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)

        ExpectedNavLabels.forEach { label ->
            val tab = compose.onNode(tabWithLabel(label))
            tab.assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            assertTouchTarget(tab)
        }
        compose.onNode(tabWithLabel("硬件")).assertIsSelected()
        listOf("工作站", "实例", "发行版", "配置").forEach { label ->
            compose.onNode(tabWithLabel(label)).assertIsNotSelected()
        }
    }

    @Test
    fun navigationLabelsStayNonEmptyAt130Percent() {
        compose.setContent {
            RealShell(
                controller = Oe3ShellController(Oe3Tab.Workstation),
                textScale = 1.3f,
            )
        }

        ExpectedNavLabels.forEach { expected ->
            val node = compose.onNode(tabWithLabel(expected))
            node.assertIsDisplayed()
            val text = node.fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString(separator = "") { it.text }
            assertTrue("nav label '$expected' must not be empty at 130%", !text.isNullOrBlank())
            assertEquals(expected, text)
        }
    }

    /**
     * 工单「非首 tab 返回工作站」的两个落点：
     * 返回键（`Oe3ShellController.back()`）语义由 `Oe3ShellModelTest.
     * everyNonFirstTabBacksOutToWorkstation` 钉住（back dispatcher 归
     * MainActivity 持有，`Oe3App` 不可测）；本用例覆盖点击工作站 tab
     * 的路径，断言控制器状态与选中态同步迁移。
     */
    @Test
    fun tappingWorkstationFromANonFirstTabReturnsToTheWorkstation() {
        val controller = Oe3ShellController(Oe3Tab.Config)
        compose.setContent {
            RealShell(controller = controller)
        }

        compose.onNode(tabWithLabel("配置")).assertIsSelected()
        compose.onNode(tabWithLabel("工作站")).performClick()
        compose.waitForIdle()

        assertEquals(Oe3Tab.Workstation, controller.selectedTab)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)
        compose.onNode(tabWithLabel("工作站")).assertIsSelected()
        compose.onNode(tabWithLabel("配置")).assertIsNotSelected()
    }

    @Test
    fun interactivePrimitivesMeet48DpFloor() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Oe3Button(
                        text = "启动",
                        onClick = {},
                        modifier = Modifier.testTag("button"),
                    )
                    Oe3Chip(
                        label = "全部",
                        modifier = Modifier.testTag("chip"),
                        onClick = {},
                    )
                    Oe3ListRow(
                        title = "终端",
                        modifier = Modifier.testTag("row"),
                        onClick = {},
                    )
                    Oe3SwitchRow(
                        title = "后台保活",
                        checked = true,
                        onCheckedChange = {},
                    )
                }
            }
        }

        listOf("button", "chip", "row").forEach { tag ->
            assertTouchTarget(compose.onNodeWithTag(tag))
        }
        // The tag on the wrapper would measure the wrong node: the toggleable
        // semantics live on the inner row (Oe3Rows.kt).
        assertTouchTarget(compose.onNode(isToggleable()))
    }

    @Test
    fun ringAndStandaloneIconsExposeDescriptions() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Oe3Ring(progress = 0.72f, contentDescription = "72%")
                    Oe3Icon(
                        image = Oe3Icons.Power,
                        contentDescription = "电源",
                        modifier = Modifier.testTag("power"),
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("72%").assertIsDisplayed()
        compose.onNodeWithTag("power").assertContentDescriptionEquals("电源")
    }

    /**
     * The production nav bar exposes no testTags: a destination is the merged
     * selectable node with `Role.Tab` semantics carrying its label text.
     */
    private fun tabWithLabel(label: String): SemanticsMatcher = SemanticsMatcher(
        "tab labeled '$label'",
    ) { node ->
        node.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
            node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text == label } == true
    }

    /**
     * The real application shell with the production state holder. The
     * selection is read inside composition so [Oe3ShellController.select]
     * recomposes the bar exactly like the Activity wiring does.
     */
    @Composable
    private fun RealShell(
        controller: Oe3ShellController,
        textScale: Float = 1f,
    ) {
        Oe3App(
            selectedTab = controller.selectedTab,
            onTabSelected = controller::select,
            theme = ThemeCatalog.constructivistWorkstation,
            textScale = textScale,
            reduceMotion = true,
        )
    }

    /**
     * WCAG 2.5.5 measures the hit target, not the drawn surface: the chip draws
     * 32dp and expands its touch bounds to 48dp, which is the contract.
     */
    private fun assertTouchTarget(node: SemanticsNodeInteraction) {
        val semanticsNode = node.fetchSemanticsNode()
        val density = semanticsNode.layoutInfo.density
        val width = with(density) { semanticsNode.touchBoundsInRoot.width.toDp() }
        val height = with(density) { semanticsNode.touchBoundsInRoot.height.toDp() }
        assertTrue(
            "touch target $width x $height is below 48dp",
            width >= 48.dp && height >= 48.dp,
        )
    }

    private companion object {
        /** Spec §2.3 destinations, in bottom-bar order. */
        val ExpectedNavLabels = listOf("工作站", "实例", "硬件", "发行版", "配置")
    }
}
