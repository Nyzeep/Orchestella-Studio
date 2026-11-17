package io.github.nyzeep.orchestella.ui.screens.instances

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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

/**
 * 实例页的零设备语义契约（UI3-06）。
 *
 * 覆盖票面要求：卡片整体可点且有描述、状态 chip 文本可读、按钮 48dp；
 * 另外锁定空态 CTA 与主操作的可用性。全部走 Robolectric + Compose 语义树，
 * 不需要真机。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class InstancesScreenSemanticsTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(
        id: String,
        name: String,
        status: InstanceStatus,
        engine: String?,
        terminal: Boolean,
        launch: Boolean,
    ) = InstanceRowUi(
        id = id,
        shortId = id.take(8),
        distroId = "ubuntu",
        name = name,
        status = status,
        engineLabel = engine,
        specsText = "2 vCPU · 2048 MB · 8 GB",
        ipAddress = if (terminal) "192.0.2.42" else null,
        canOpenTerminal = terminal,
        canLaunch = launch,
        canLaunchDesktop = false,
        canPowerOff = status == InstanceStatus.Running ||
            status == InstanceStatus.Provisioning ||
            status == InstanceStatus.Suspended ||
            status == InstanceStatus.Crashed ||
            status == InstanceStatus.Interrupted,
        canShowLogs = true,
        canExport = status == InstanceStatus.Stopped ||
            status == InstanceStatus.Created ||
            status == InstanceStatus.Crashed ||
            status == InstanceStatus.Interrupted,
        canDelete = status == InstanceStatus.Stopped ||
            status == InstanceStatus.Created ||
            status == InstanceStatus.Crashed,
    )

    private val running = row(
        id = "running-1",
        name = "Ubuntu 24.04",
        status = InstanceStatus.Running,
        engine = "QEMU 软件虚拟化",
        terminal = true,
        launch = false,
    )
    private val stopped = row(
        id = "stopped-2",
        name = "Debian 12",
        status = InstanceStatus.Stopped,
        engine = null,
        terminal = false,
        launch = true,
    )
    private val provisioning = row(
        id = "prov-3",
        name = "Fedora 41",
        status = InstanceStatus.Provisioning,
        engine = null,
        terminal = false,
        launch = false,
    )

    private val state = InstancesUiState(
        groups = listOf(
            InstanceGroupUi(InstanceGroup.Running, listOf(running)),
            InstanceGroupUi(InstanceGroup.Provisioning, listOf(provisioning)),
            InstanceGroupUi(InstanceGroup.Stopped, listOf(stopped)),
        ),
        totalCount = 3,
        runningCount = 1,
    )

    private fun content(actions: InstancesActions = InstancesActions()) {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                InstancesScreen(state = state, actions = actions)
            }
        }
    }

    @Test
    fun cardIsOneClickableNodeCarryingADescription() {
        var opened: String? = null
        content(InstancesActions(openInstance = { opened = it }))

        val card = compose.onNodeWithTag("instances-card-running-1")
        card.assertHasClickAction()
            .assert(containsDescription("Ubuntu 24.04"))
            .assert(containsDescription("运行中"))
        card.performClick()

        assertEquals("running-1", opened)
        // 卡内按钮各带实例名，读屏不会只听到重复的“终端/更多”。
        compose.onNodeWithContentDescription("Ubuntu 24.04：终端").assertExists()
        compose.onNodeWithContentDescription("Ubuntu 24.04：更多").assertExists()
    }

    @Test
    fun statusChipTextStaysReadableNextToTheColourDot() {
        content()

        compose.onNodeWithText("运行中", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("装配中", useUnmergedTree = true).assertExists()
        // UI-02 起页面有 72dp 单标题栏，可视区变短：末组必须先滚进来，
        // 断言本身不变（仍是「状态 chip 文本可读」）。
        compose.onNodeWithTag("instances-list").performScrollToNode(hasText("已停止"))
        compose.onNodeWithText("已停止", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("QEMU 软件虚拟化", useUnmergedTree = true).assertExists()
    }

    @Test
    fun primaryActionFollowsAvailabilityAndStaysAtThe48DpFloor() {
        content()

        // 运行中：主操作是终端，可用；启动在模型层被禁用（见状态单测）。
        compose.onNodeWithTag("instances-primary-running-1")
            .assertIsEnabled()
            .also(::assertTouchTarget)

        // LazyColumn 只组合可视条目，先把目标滚进来再量命中区。
        compose.onNodeWithTag("instances-list")
            .performScrollToNode(hasTestTag("instances-primary-stopped-2"))
        // 已停止：主操作是启动，可用。
        compose.onNodeWithTag("instances-primary-stopped-2")
            .assertIsEnabled()
            .also(::assertTouchTarget)

        compose.onNodeWithTag("instances-list")
            .performScrollToNode(hasTestTag("instances-primary-prov-3"))
        // 装配中：主操作存在但禁用。
        compose.onNodeWithTag("instances-primary-prov-3")
            .assertIsNotEnabled()
            .also(::assertTouchTarget)

        compose.onNodeWithTag("instances-list")
            .performScrollToNode(hasTestTag("instances-create"))
        compose.onNodeWithTag("instances-more-running-1")
            .assertHasClickAction()
            .also(::assertTouchTarget)
        assertTouchTarget(compose.onNodeWithTag("instances-create"))
        assertTouchTarget(compose.onNodeWithTag("instances-import"))
    }

    @Test
    fun moreOpensTheActionSheetWithAvailabilityIntact() {
        var opened = 0
        content(InstancesActions(openInstance = { opened += 1 }))

        compose.onNodeWithTag("instances-more-running-1").performClick()

        // 卡内按钮消费点击，不应顺带触发整卡点击。
        assertEquals(0, opened)

        // 运行中：电源/日志可用；桌面未接线、冷备份要等关机、删除要等关机。
        compose.onNodeWithTag("instances-sheet-power").assertIsEnabled()
        compose.onNodeWithTag("instances-sheet-desktop").assertIsNotEnabled()
        compose.onNodeWithTag("instances-sheet-logs").assertIsEnabled()
        compose.onNodeWithTag("instances-sheet-export").assertIsNotEnabled()
        compose.onNodeWithTag("instances-sheet-delete").assertIsNotEnabled()
    }

    @Test
    fun suspendedCardOffersResumeInsteadOfStart() {
        val suspended = row(
            id = "suspended-4",
            name = "openSUSE 24.04",
            status = InstanceStatus.Suspended,
            engine = null,
            terminal = false,
            launch = false,
        )
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                InstancesScreen(
                    state = InstancesUiState(
                        groups = listOf(InstanceGroupUi(InstanceGroup.Stopped, listOf(suspended))),
                        totalCount = 1,
                        runningCount = 0,
                    ),
                    actions = InstancesActions(),
                )
            }
        }

        compose.onNodeWithTag("instances-primary-suspended-4")
            .assertIsEnabled()
            .also(::assertTouchTarget)
        compose.onNodeWithText("继续", useUnmergedTree = true).assertExists()
    }

    @Test
    fun emptyStateOffersOneCreateCallToAction() {
        var created = 0
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                InstancesScreen(
                    state = InstancesUiState(emptyList(), totalCount = 0, runningCount = 0),
                    actions = InstancesActions(createInstance = { created += 1 }),
                )
            }
        }

        compose.onNodeWithTag("instances-empty").assertExists()
        val cta = compose.onNodeWithTag("instances-empty-cta")
        cta.assertHasClickAction()
            .also(::assertTouchTarget)
        cta.performClick()

        assertEquals(1, created)
        compose.onNodeWithTag("instances-card-running-1").assertDoesNotExist()
    }

    private fun containsDescription(part: String) = SemanticsMatcher(
        "content description contains '$part'",
    ) { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription)
            ?.any { it.contains(part) } == true
    }

    /**
     * WCAG 2.5.5 量的是命中区，不是绘制面：Oe3Button 的 Text 变体画得窄，
     * 但 `minimumInteractiveComponentSize` 把触摸框扩到 48dp，这就是契约。
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
}
