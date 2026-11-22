package io.github.nyzeep.orchestella.ui.shell

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassChrome
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassChromeState
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardActions
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardCpuUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardEditState
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardInstanceUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardRamUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardRecentUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardScreen
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardStatus
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardStorageUi
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardUiState
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosActions
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosPreviewStates
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosScreen
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareActions
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareScreen
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareTestFixtures
import io.github.nyzeep.orchestella.ui.screens.instances.InstanceGroup
import io.github.nyzeep.orchestella.ui.screens.instances.InstanceGroupUi
import io.github.nyzeep.orchestella.ui.screens.instances.InstanceRowUi
import io.github.nyzeep.orchestella.ui.screens.instances.InstanceStatus
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesActions
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesScreen
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesUiState
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W2-06（A4-P4 / M5）：复点已激活 Tab 滚回顶部与顶栏滞回联动契约（Cashew 模式净室转译）。
 *
 * 验证范围：
 * 1. `Oe3App` 仅在复点当前已选中 Tab 时为其页签槽位递增单调序号，切到其他 Tab 互不串扰；
 * 2. 四页主壳（工作站 / 实例 / 硬件 / 发行版）在标准与减动效档位均响应复点回顶，
 *    滚动位置归零并同步复位 `OeScaffold` 折叠态与顶栏滚动边缘；
 * 3. 二级页压栈时复点当前 Tab 会先清栈并把底层 Tab 页滚回顶部；
 * 4. 切离后再切回已复点过的 Tab，不得产生多余回顶。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h540dp-mdpi")
class Oe3TabReselectScrollToTopTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private fun scrollOffset(tag: String): Float =
        compose.onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.VerticalScrollAxisRange)
            ?.value()
            ?: error("node $tag does not expose VerticalScrollAxisRange")

    private fun clickNavTab(label: String) {
        compose.onNode(
            hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        ).performClick()
        compose.waitForIdle()
    }

    private fun scrollDown(tag: String, deltaPx: Float = 600f) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            scrollBy(0f, deltaPx)
        }
        compose.waitForIdle()
    }

    private fun assertTabReselectScrollsToTop(
        tab: Oe3Tab,
        scrollableTag: String,
        reduceMotion: Boolean = false,
        glassLevel: Oe3GlassLevel = Oe3GlassLevel.None,
        verifyHeaderCollapseReset: Boolean = false,
        pageSlot: @Composable () -> Unit,
    ) {
        var selectedTab by mutableStateOf(tab)
        var chrome: Oe3GlassChromeState? = null
        val pages = when (tab) {
            Oe3Tab.Workstation -> Oe3Pages(workstation = {
                chrome = LocalOe3GlassChrome.current
                pageSlot()
            })
            Oe3Tab.Instances -> Oe3Pages(instances = {
                chrome = LocalOe3GlassChrome.current
                pageSlot()
            })
            Oe3Tab.Hardware -> Oe3Pages(hardware = {
                chrome = LocalOe3GlassChrome.current
                pageSlot()
            })
            Oe3Tab.Distros -> Oe3Pages(distros = {
                chrome = LocalOe3GlassChrome.current
                pageSlot()
            })
            Oe3Tab.Config -> Oe3Pages(config = {
                chrome = LocalOe3GlassChrome.current
                pageSlot()
            })
        }

        compose.setContent {
            Oe3App(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = reduceMotion,
                glassLevel = glassLevel,
                pages = pages,
            )
        }
        compose.waitForIdle()

        scrollDown(scrollableTag)
        assertTrue("${tab.label} 滚动容器应能向下滚动", scrollOffset(scrollableTag) > 0f)
        if (verifyHeaderCollapseReset) {
            assertTrue("滚动后顶栏应进入折叠边缘态", requireNotNull(chrome).hasScrollEdge)
        }

        clickNavTab(tab.label)
        assertEquals(0f, scrollOffset(scrollableTag), 0.5f)
        if (verifyHeaderCollapseReset) {
            assertFalse("回顶后顶栏滚动边缘态应同步复位", requireNotNull(chrome).hasScrollEdge)
        }
    }

    private fun dashboardState(): DashboardUiState {
        val gib = 1024L * 1024L * 1024L
        return DashboardUiState(
            instanceCount = 3,
            runningCount = 1,
            primary = DashboardInstanceUi(
                id = "ubuntu-1",
                distroId = "ubuntu",
                name = "Ubuntu 24.04",
                status = DashboardStatus.Running,
                engineLabel = "QEMU 软件虚拟化",
                cpuCores = 4,
                ramMb = 4096,
                diskGb = 16,
                canOpenTerminal = true,
            ),
            storage = DashboardStorageUi(
                totalBytes = 128L * gib,
                usedBytes = 66L * gib,
                fraction = 0.52f,
                percent = 52,
            ),
            cpu = DashboardCpuUi(hostCores = 8, instanceCores = 4, fraction = 0.5f, enabled = true),
            ram = DashboardRamUi(usedBytes = 4L * gib, totalBytes = 8L * gib, fraction = 0.5f, enabled = true),
            recent = listOf(
                DashboardRecentUi("debian-2", "debian", "Debian 12", DashboardStatus.Stopped),
                DashboardRecentUi("fedora-3", "fedora", "Fedora 41", DashboardStatus.Crashed),
            ),
        )
    }

    private fun instanceRow(id: String, name: String, status: InstanceStatus) = InstanceRowUi(
        id = id,
        shortId = id.take(8),
        distroId = "ubuntu",
        name = name,
        status = status,
        engineLabel = "QEMU",
        specsText = "2 vCPU · 2048 MB · 8 GB",
        ipAddress = null,
        canOpenTerminal = status == InstanceStatus.Running,
        canLaunch = status == InstanceStatus.Stopped,
        canLaunchDesktop = false,
        canPowerOff = status == InstanceStatus.Running,
        canShowLogs = true,
        canExport = status == InstanceStatus.Stopped,
        canDelete = status == InstanceStatus.Stopped,
    )

    private fun instancesState(): InstancesUiState = InstancesUiState(
        groups = listOf(
            InstanceGroupUi(
                InstanceGroup.Running,
                listOf(
                    instanceRow("inst-1", "Ubuntu 24.04", InstanceStatus.Running),
                    instanceRow("inst-2", "Alpine 3.20", InstanceStatus.Running),
                ),
            ),
            InstanceGroupUi(
                InstanceGroup.Stopped,
                listOf(
                    instanceRow("inst-3", "Debian 12", InstanceStatus.Stopped),
                    instanceRow("inst-4", "Fedora 41", InstanceStatus.Stopped),
                ),
            ),
        ),
        totalCount = 4,
        runningCount = 2,
    )

    @Test
    fun reselectingActiveTabIncrementsOnlyThatSlotSequence() {
        var selectedTab by mutableStateOf(Oe3Tab.Workstation)
        var workstationSeq = -1L
        var instancesSeq = -1L

        compose.setContent {
            Oe3App(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = { workstationSeq = LocalOe3TabReselectSequence.current },
                    instances = { instancesSeq = LocalOe3TabReselectSequence.current },
                ),
            )
        }
        compose.waitForIdle()
        assertEquals(0L, workstationSeq)

        clickNavTab("工作站")
        assertEquals(1L, workstationSeq)

        clickNavTab("工作站")
        assertEquals(2L, workstationSeq)

        clickNavTab("实例")
        assertEquals(Oe3Tab.Instances, selectedTab)
        assertEquals("切到其他 Tab 时该槽位序号应从 0 开始", 0L, instancesSeq)

        clickNavTab("工作站")
        assertEquals(Oe3Tab.Workstation, selectedTab)
        assertEquals("切回先前 Tab 不得递增其复点序号", 2L, workstationSeq)
    }

    @Test
    fun dashboardReselectScrollsToTopInReducedMotion() {
        val dash = dashboardState()
        val edit = DashboardEditState(store = null)
        assertTabReselectScrollsToTop(
            tab = Oe3Tab.Workstation,
            scrollableTag = "dashboard:cards",
            reduceMotion = true,
        ) {
            DashboardScreen(dash, DashboardActions(), edit = edit)
        }
    }

    @Test
    fun dashboardReselectScrollsToTopAndResetsScaffoldHeaderCollapseAnimated() {
        val dash = dashboardState()
        val edit = DashboardEditState(store = null)
        assertTabReselectScrollsToTop(
            tab = Oe3Tab.Workstation,
            scrollableTag = "dashboard:cards",
            reduceMotion = false,
            glassLevel = Oe3GlassLevel.Standard,
            verifyHeaderCollapseReset = true,
        ) {
            DashboardScreen(dash, DashboardActions(), edit = edit)
        }
    }

    @Test
    fun instancesReselectScrollsToTop() {
        val state = instancesState()
        assertTabReselectScrollsToTop(
            tab = Oe3Tab.Instances,
            scrollableTag = "instances-list",
            reduceMotion = false,
        ) {
            InstancesScreen(state, InstancesActions())
        }
    }

    @Test
    fun hardwareReselectScrollsToTop() {
        val state = HardwareTestFixtures.ready()
        assertTabReselectScrollsToTop(
            tab = Oe3Tab.Hardware,
            scrollableTag = "hardware:screen",
            reduceMotion = false,
        ) {
            HardwareScreen(state, HardwareActions())
        }
    }

    @Test
    fun distrosReselectScrollsToTop() {
        val state = DistrosPreviewStates.collapsed
        assertTabReselectScrollsToTop(
            tab = Oe3Tab.Distros,
            scrollableTag = "distros-grid",
            reduceMotion = false,
        ) {
            DistrosScreen(state, DistrosActions())
        }
    }

    @Test
    fun reselectingActiveTabWithNonEmptyBackStackPopsStackAndScrollsToTop() {
        var selectedTab by mutableStateOf(Oe3Tab.Workstation)
        val backStack = OeBackStack()
        val dash = dashboardState()
        val edit = DashboardEditState(store = null)

        compose.setContent {
            Oe3App(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                backStack = backStack,
                pages = Oe3Pages(
                    workstation = { DashboardScreen(dash, DashboardActions(), edit = edit) },
                ),
            )
        }
        compose.waitForIdle()

        scrollDown("dashboard:cards")
        assertTrue(scrollOffset("dashboard:cards") > 0f)

        compose.runOnUiThread {
            backStack.push(
                OeDestination(key = "secondary-test", title = "二级页") {
                    androidx.compose.material3.Text("secondary-content")
                },
            )
        }
        compose.waitForIdle()
        assertTrue(backStack.canPop)

        clickNavTab("工作站")
        assertFalse("复点当前 Tab 必须先清栈", backStack.canPop)
        assertEquals("清栈后底层页应同步滚回顶部", 0f, scrollOffset("dashboard:cards"), 0.5f)
    }

    @Test
    fun switchingTabsAndReturningDoesNotSpuriouslyScrollToTop() {
        var selectedTab by mutableStateOf(Oe3Tab.Workstation)
        val dash = dashboardState()
        val edit = DashboardEditState(store = null)
        val inst = instancesState()

        compose.setContent {
            Oe3App(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = { DashboardScreen(dash, DashboardActions(), edit = edit) },
                    instances = { InstancesScreen(inst, InstancesActions()) },
                ),
            )
        }
        compose.waitForIdle()

        // 首次复点回顶，然后再次向下滚。
        scrollDown("dashboard:cards")
        clickNavTab("工作站")
        assertEquals(0f, scrollOffset("dashboard:cards"), 0.5f)

        scrollDown("dashboard:cards")
        val savedScroll = scrollOffset("dashboard:cards")
        assertTrue(savedScroll > 0f)

        // 切到实例页再切回工作站，不得重播先前的回顶信号。
        clickNavTab("实例")
        clickNavTab("工作站")

        assertEquals(savedScroll, scrollOffset("dashboard:cards"), 0.5f)
    }
}
