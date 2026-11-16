package io.github.nyzeep.orchestella.ui.oe3.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Accessibility contract for the Oe3 shell and controls.
 *
 * The five-tab fixture stands in for UI3-02's shell (not merged at this
 * branch); the assertions are the ones the real shell must keep. The expected
 * labels are hardcoded here rather than read from the fixture, so removing or
 * renaming a destination fails the test instead of moving the goalposts.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class Oe3SemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun navigationContractHasFiveTabsAndOneSelectedDestination() {
        compose.setContent {
            Oe3NavContractFixture(
                selectedIndex = 2,
                appTheme = ThemeCatalog.constructivistWorkstation,
            )
        }

        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assertCountEquals(ExpectedNavLabels.size)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)

        ExpectedNavLabels.indices.forEach { index ->
            val tab = compose.onNodeWithTag("nav-tab-$index")
            tab.assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            assertTouchTarget(tab)
        }
        compose.onNodeWithTag("nav-tab-2").assertIsSelected()
        listOf(0, 1, 3, 4).forEach { index ->
            compose.onNodeWithTag("nav-tab-$index").assertIsNotSelected()
        }
    }

    @Test
    fun navigationLabelsStayNonEmptyAt130Percent() {
        compose.setContent {
            Oe3NavContractFixture(
                selectedIndex = 0,
                appTheme = ThemeCatalog.constructivistWorkstation,
                textScale = 1.3f,
            )
        }

        ExpectedNavLabels.forEachIndexed { index, expected ->
            val node = compose.onNodeWithTag("nav-label-$index", useUnmergedTree = true)
            node.assertIsDisplayed()
            val text = node.fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString(separator = "") { it.text }
            assertTrue("nav label $index must not be empty at 130%", !text.isNullOrBlank())
            assertEquals(expected, text)
        }
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
