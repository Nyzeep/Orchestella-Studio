package io.github.nyzeep.orchestella.ui.screens.hardware

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 硬件页的零设备语义契约：三环有描述、状态有文字、底部表可滚动，
 * 行/按钮不低于 48dp，失败态只给重试不给假读数。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w393dp-h1600dp-mdpi")
class HardwareScreenSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        state: HardwareUiState,
        actions: HardwareActions = HardwareActions(),
    ) {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                HardwareScreen(state = state, actions = actions)
            }
        }
    }

    @Test
    fun threeRingsExposeNonEmptyDescriptionsWithTheirValues() {
        setScreen(
            HardwareTestFixtures.ready(
                hardware = HardwareTestFixtures.profile(8L * HardwareTestFixtures.GIB, 4L * HardwareTestFixtures.GIB),
            ),
        )

        val nodes = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
        ).fetchSemanticsNodes()
        assertEquals(3, nodes.size)

        val descriptions = nodes.map { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
        }
        descriptions.forEach { description ->
            assertTrue("ring description must not be blank", description.isNotBlank())
        }
        assertTrue(descriptions.any { it.contains("内存") && it.contains("50%") })
        assertTrue(descriptions.any { it.contains("存储") })
        assertTrue(descriptions.any { it.contains("电池") || it.contains("电量") })
    }

    @Test
    fun engineStateIsReadableAsTextNotOnlyAsColour() {
        setScreen(HardwareTestFixtures.ready())

        compose.onNodeWithText("预检通过", substring = true).assertIsDisplayed()
        compose.onNodeWithText("可用").assertIsDisplayed()
        compose.onNodeWithText("编译通道未接线", substring = true).assertIsDisplayed()
    }

    @Test
    fun rowsAndButtonsMeetThe48DpFloor() {
        setScreen(HardwareTestFixtures.ready())

        listOf(
            "hardware:details-button",
            "hardware:engine:avf",
            "hardware:engine:qemu",
            "hardware:engine:uml",
            "hardware:summary",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun readyAvfDoesNotOfferTheAuthorizationEntry() {
        setScreen(
            HardwareTestFixtures.ready(
                engineFacts = HardwareTestFixtures.engines(
                    status = AvfCapabilityEvaluator.AvfStatus.READY,
                ),
            ),
        )
        val entries = compose.onAllNodesWithTag("hardware:avf-authorize").fetchSemanticsNodes()
        assertEquals(0, entries.size)
    }

    @Test
    fun needsAuthorizationOffersTheWizardEntry() {
        var authorized = false
        setScreen(
            state = HardwareTestFixtures.ready(
                engineFacts = HardwareTestFixtures.engines(
                    status = AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                ),
            ),
            actions = HardwareActions(requestAvfAuthorization = { authorized = true }),
        )

        compose.onNodeWithTag("hardware:avf-authorize").assertIsDisplayed().performClick()
        assertTrue(authorized)
    }

    @Test
    fun failedProbeShowsRetryAndNoInventedReadings() {
        var refreshed = false
        setScreen(
            state = HardwareUiState.Failed(R.string.ui3_hardware_probe_failed_body),
            actions = HardwareActions(refresh = { refreshed = true }),
        )

        compose.onNodeWithTag("hardware:probe-failed").assertIsDisplayed()
        compose.onNodeWithText("重试").assertIsDisplayed().performClick()
        assertTrue(refreshed)

        val descriptions = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription),
        ).fetchSemanticsNodes()
        assertEquals(0, descriptions.size)
    }

    @Test
    fun detailsListScrollsAndEveryRowIsReadable() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.moonshotDark, reduceMotion = true) {
                HardwareDetailsContent(groups = HardwareTestFixtures.ready().details)
            }
        }

        compose.onNodeWithTag("hardware:details-list")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))

        val rows = compose.onAllNodesWithTag("hardware:detail-row").fetchSemanticsNodes()
        assertTrue("details need a full parameter table", rows.size >= 10)
        rows.forEach { row ->
            val height = with(row.layoutInfo.density) { row.touchBoundsInRoot.height.toDp() }
            assertTrue("detail row $height is below 48dp", height >= 48.dp)
        }
    }
}
