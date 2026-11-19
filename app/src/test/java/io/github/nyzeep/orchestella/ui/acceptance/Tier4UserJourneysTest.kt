package io.github.nyzeep.orchestella.ui.acceptance

import android.app.ActivityManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassDeviceCap
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassMode
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassTier
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassFrameGate
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassStrategy
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.screens.config.ConfigRoute
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardActions
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardCardId
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardEditState
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardScreen
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosActions
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosScreen
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesActions
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeBackgroundScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemePresetScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeTerminalScreen
import io.github.nyzeep.orchestella.ui.screens.theme.rememberThemeStudioHandle
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardController
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardScreen
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3NavStyle
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3ShellController
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowStatFs

/**
 * Tier 4 — Realistic End-User Acceptance Journeys (6 tests across R1–R5).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-mdpi")
class Tier4UserJourneysTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    @Test
    fun t4_01_configToThemeStudioPresetAndModeAndBackWithoutFlickerOrBackgroundLoss_m2() {
        val prefs = AcceptanceTestSupport.InMemorySharedPreferences()
        val store = ThemeStore(prefs).apply {
            setMode(ThemeStore.Mode.SYSTEM)
            setThemeId(ThemeCatalog.mascotLight.id)
            setBackgroundUri("content://test/journey1.png")
            setUiPureBlack(true)
        }
        val controller = Oe3ShellController(Oe3Tab.Workstation)
        val backStack = OeBackStack()

        compose.setContent {
            var rev by remember { mutableStateOf(0) }
            DisposableEffect(store) {
                val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> rev++ }
                store.addOnThemeChangeListener(l)
                onDispose { store.removeOnThemeChangeListener(l) }
            }
            @Suppress("UNUSED_EXPRESSION")
            rev
            val resolvedTheme = store.resolveForMode(systemDark = true)
            val navStyle = Oe3NavStyle.fromKey(store.uiNavStyle())
            val cornerDp = store.cornerRadiusDpOrNull()
            val pureBlack = resolvedTheme.isDark && store.uiPureBlack()

            fun pushPresets() {
                backStack.push(
                    OeDestination("theme-presets", "主题预设") {
                        val handle = rememberThemeStudioHandle(store)
                        ThemePresetScreen(
                            state = handle.state,
                            onEvent = handle.onEvent,
                            onBack = { backStack.pop() },
                        )
                    },
                )
            }

            fun pushStudio() {
                backStack.push(
                    OeDestination("theme-studio", "主题与外观") {
                        val handle = rememberThemeStudioHandle(store)
                        ThemeStudioScreen(
                            state = handle.state,
                            onEvent = handle.onEvent,
                            onBack = { backStack.pop() },
                            onOpenPresets = { pushPresets() },
                            reduceMotion = true,
                        )
                    },
                )
            }

            Oe3App(
                selectedTab = controller.selectedTab,
                onTabSelected = controller::select,
                theme = resolvedTheme,
                pureBlack = pureBlack,
                navStyle = navStyle,
                cornerRadiusDp = cornerDp,
                backgroundUri = store.backgroundUri(),
                reduceMotion = true,
                backStack = backStack,
                pages = AcceptanceTestSupport.samplePages(
                    onConfigRoute = { route ->
                        if (route == ConfigRoute.THEME_STUDIO) pushStudio()
                    },
                ),
            )
        }
        compose.waitForIdle()

        // Navigate Workstation -> Config -> Theme Studio -> Presets
        compose.onNodeWithText(Oe3Tab.Config.label).performClick()
        compose.waitForIdle()
        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue(
            "M2 R4 requires overlaid header on ConfigScreen",
            compose.onNodeWithTag("config:screen").fetchSemanticsNode().boundsInRoot.top <= maxTopPx,
        )

        compose.onNodeWithTag("config:route:appearance.theme_studio").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("theme-studio:screen").assertIsDisplayed()

        compose.onNodeWithTag("theme-entry:presets").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("theme-presets:screen").assertIsDisplayed()

        // Select Catppuccin preset
        compose.onNodeWithTag("theme-preset:catppuccin").performScrollTo().performClick()
        compose.waitForIdle()

        // Pop Presets -> Studio
        compose.runOnUiThread { backStack.pop() }
        compose.waitForIdle()
        compose.onNodeWithTag("theme-studio:screen").assertIsDisplayed()
        compose.onNodeWithTag("theme-presets:screen").assertDoesNotExist()

        // Switch corner = 20dp and navStyle = Fixed
        compose.onNodeWithTag("theme-corner:20").performScrollTo().performClick()
        compose.onNodeWithTag("theme-row:nav:fixed").performScrollTo().performClick()
        compose.waitForIdle()

        // Pop Studio -> Config
        compose.runOnUiThread { backStack.pop() }
        compose.waitForIdle()
        compose.onNodeWithTag("theme-studio:screen").assertDoesNotExist()
        compose.onNodeWithTag("config:screen").assertIsDisplayed()

        // Verify invariants preserved
        assertEquals("content://test/journey1.png", store.backgroundUri())
        assertEquals(ThemeStore.Mode.SYSTEM, store.mode())
        assertTrue(store.uiPureBlack())
        assertEquals(20, store.cornerRadiusDp())
        assertEquals("fixed", store.uiNavStyle())

        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val navBounds = compose.onNodeWithTag("shell:nav-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(rootBounds.bottom, navBounds.bottom, 2.0f)
    }

    @Test
    fun t4_02_workstationEditModeReorderHideDockNavigationPersistence_m2() {
        val editStore = AcceptanceTestSupport.InMemoryDashboardEditStore()
        val editState = DashboardEditState(editStore)
        val controller = Oe3ShellController(Oe3Tab.Workstation)

        compose.setContent {
            Oe3App(
                selectedTab = controller.selectedTab,
                onTabSelected = controller::select,
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = AcceptanceTestSupport.samplePages(dashboardEdit = editState),
            )
        }
        compose.waitForIdle()

        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue(
            "M2 R4 requires overlaid header on DashboardScreen",
            compose.onNodeWithTag("dashboard:cards").fetchSemanticsNode().boundsInRoot.top <= maxTopPx,
        )

        // Scroll beneath header, then enter edit mode
        compose.onNodeWithTag("dashboard:cards").performTouchInput { swipeUp() }
        compose.waitForIdle()
        compose.onNodeWithTag("dashboard:edit-toggle").performClick()
        compose.waitForIdle()
        assertTrue(editState.isEditing)

        // Reorder Storage ahead of Primary & hide Recent
        compose.runOnUiThread {
            editState.moveVisible(1, 0)
            editState.hide(DashboardCardId.Recent)
        }
        compose.waitForIdle()

        // Save edit mode
        compose.onNodeWithTag("dashboard:edit-toggle").performClick()
        compose.waitForIdle()
        assertFalse(editState.isEditing)

        // Navigate Hardware -> Workstation via dock
        compose.onNodeWithText(Oe3Tab.Hardware.label).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("hardware:screen").assertIsDisplayed()
        compose.onNodeWithText(Oe3Tab.Workstation.label).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("dashboard:cards").assertIsDisplayed()

        // Reload store and verify persistence
        val reloaded = DashboardEditState(editStore)
        assertEquals(DashboardCardId.Storage, reloaded.visible.first())
        assertEquals(listOf(DashboardCardId.Recent), reloaded.hidden)
        assertEquals(4, reloaded.order.size)
    }

    @Test
    fun t4_03_distrosSelectionToFourStepWizardValidationGateAndBack_baseline() {
        ShadowStatFs.registerStats(compose.activity.filesDir.path, 16_000_000, 16_000_000, 16_000_000)
        val am = compose.activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Shadows.shadowOf(am).setMemoryInfo(
            ActivityManager.MemoryInfo().apply {
                totalMem = 12L * 1024L * 1024L * 1024L
                availMem = 8L * 1024L * 1024L * 1024L
                threshold = 512L * 1024L * 1024L
                lowMemory = false
            },
        )
        val controller = Oe3ShellController(Oe3Tab.Distros)
        val backStack = OeBackStack()
        val wizardController = WizardController(
            context = compose.activity,
            targetDistroId = "ubuntu",
            callbacks = WizardController.Callbacks(onCreated = {}, onShowShizukuClient = {}),
        )
        try {
            compose.setContent {
                Oe3App(
                    selectedTab = controller.selectedTab,
                    onTabSelected = controller::select,
                    theme = ThemeCatalog.constructivistWorkstation,
                    reduceMotion = true,
                    backStack = backStack,
                    pages = AcceptanceTestSupport.samplePages(
                        distrosActions = DistrosActions(
                            install = {
                                backStack.push(
                                    OeDestination("wizard", "新建实例") {
                                        WizardScreen(
                                            controller = wizardController,
                                            onExit = { backStack.pop() },
                                        )
                                    },
                                )
                            },
                        ),
                    ),
                )
            }
            compose.waitForIdle()
            compose.onNodeWithTag("distros-grid").assertIsDisplayed()

            // Expand Ubuntu card and launch Wizard
            compose.onNodeWithTag("distro-card-ubuntu").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("distro-install-ubuntu").performClick()
            compose.waitForIdle()

            // Step 1: test invalid disk input -> canProceed == false & storage-warning
            compose.onNodeWithTag("wizard:disk-input").performScrollTo().performTextReplacement("0")
            compose.waitForIdle()
            compose.onNodeWithTag("wizard:storage-warning").assertExists()
            compose.onNodeWithTag("wizard:next").assertIsNotEnabled()

            // Select valid preset 16 GB -> next enabled
            compose.onNodeWithText("16 GB").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("wizard:next").assertIsEnabled()

            // Verify >= 48dp touch floor on next button
            AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(
                compose.onNodeWithTag("wizard:next"),
                compose.density,
                "wizard:next",
            )

            // Step 1 -> 2 -> 3 -> 4
            compose.onNodeWithTag("wizard:next").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("wizard:next").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("wizard:next").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("wizard:create").assertExists()

            // Exit back to Distros
            compose.runOnUiThread { backStack.pop() }
            compose.waitForIdle()
            compose.onNodeWithTag("distros-grid").assertIsDisplayed()
        } finally {
            wizardController.dispose()
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w343dp-h800dp-mdpi")
    fun t4_04_instancesManagementOnNarrow343DpAt130PercentFontScale_m2() {
        var createdClicks = 0
        var importedClicks = 0
        var deletedId: String? = null

        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Instances,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                textScale = 1.3f,
                reduceMotion = true,
                pages = AcceptanceTestSupport.samplePages(
                    instancesState = AcceptanceTestSupport.sampleInstancesState(
                        engineLabel = "QEMU/TCG 兼容回退引擎 (Software)",
                    ),
                    instancesActions = InstancesActions(
                        createInstance = { createdClicks++ },
                        importArchive = { importedClicks++ },
                        deleteInstance = { deletedId = it },
                    ),
                ),
            )
        }
        compose.waitForIdle()

        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue(
            "M2 R4 requires overlaid header on InstancesScreen",
            compose.onNodeWithTag("instances-list").fetchSemanticsNode().boundsInRoot.top <= maxTopPx,
        )

        // All 5 nav tabs non-empty & >= 48dp
        Oe3Tab.entries.forEach { tab ->
            val tabNode = compose.onNode(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.Tab,
                ) and hasText(tab.label),
            )
            tabNode.assertIsDisplayed()
            AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(tabNode, compose.density, "t4_04:${tab.key}")
        }

        // BadgeRow wraps on narrow screen
        val statusBounds = compose.onNodeWithTag("instances-status-running-inst-ubuntu-01", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val engineBounds = compose.onNodeWithTag("instances-engine-inst-ubuntu-01", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(engineBounds.top >= statusBounds.bottom)

        compose.onNodeWithTag("instances-create").performClick()
        compose.onNodeWithTag("instances-import").performClick()
        assertEquals(1, createdClicks)
        assertEquals(1, importedClicks)

        // Open InstanceActionsSheet and verify actions >= 48dp
        compose.onNodeWithTag("instances-more-inst-ubuntu-01").performClick()
        compose.waitForIdle()
        listOf(
            "instances-sheet-power",
            "instances-sheet-desktop",
            "instances-sheet-logs",
            "instances-sheet-export",
            "instances-sheet-delete",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).assertExists()
            AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(
                compose.onNodeWithTag(tag),
                compose.density,
                tag,
            )
        }

        compose.onNodeWithTag("instances-sheet-delete").performClick()
        compose.waitForIdle()
        assertEquals("inst-ubuntu-01", deletedId)
    }

    @Test
    fun t4_05_runtimeDegradationCascadeL2ToFrameGateToPowerSaveToReduceMotion_m3() {
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        store.setUiGlassMode("auto")

        // Step 1: Start at API 35 with Auto -> M3 expects L2
        val level = Oe3GlassStrategy.resolveLevel(
            GlassMode.fromKey(store.uiGlassMode()),
            store.uiGlassLevelKeyOrNull(),
        )
        assertEquals(Oe3GlassLevel.Standard, level)
        assertEquals(
            "M3 must start capable API 35 device at GlassTier.L2",
            GlassTier.L2,
            Oe3GlassStrategy.resolveTier(
                level = level,
                enabled = true,
                apiLevel = 35,
                isLowRamDevice = false,
                deviceCap = GlassDeviceCap.fromKey(store.uiGlassDeviceCap()),
            ),
        )

        // Step 2: FrameMetrics gate receives 60 slow frames -> persists L0 cap
        val gate = Oe3GlassFrameGate(budgetMs = 16.67) { verdict ->
            if (verdict.downgrade) store.setUiGlassDeviceCap(GlassDeviceCap.L0.key)
        }
        repeat(60) { gate.onFrame(22.0) }
        assertEquals("l0", store.uiGlassDeviceCap())
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(
                level = level,
                enabled = true,
                apiLevel = 35,
                isLowRamDevice = false,
                deviceCap = GlassDeviceCap.fromKey(store.uiGlassDeviceCap()),
            ),
        )

        // Step 3: PowerSave (enabled = false) + ReduceMotion (reduceMotion = true)
        var reduceMotion by mutableStateOf(false)
        var glassEnabled by mutableStateOf(true)
        val controller = Oe3ShellController(Oe3Tab.Workstation)
        val backStack = OeBackStack()

        compose.setContent {
            Oe3App(
                selectedTab = controller.selectedTab,
                onTabSelected = controller::select,
                theme = ThemeCatalog.constructivistWorkstation,
                glassLevel = level,
                glassEnabled = glassEnabled,
                reduceMotion = reduceMotion,
                backStack = backStack,
                pages = AcceptanceTestSupport.samplePages(),
            )
        }
        compose.waitForIdle()

        glassEnabled = false
        reduceMotion = true
        compose.waitForIdle()
        assertEquals(0, Oe3Motion.reduced().pagePush.durationMillis)

        compose.onNodeWithText(Oe3Tab.Config.label).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("config:screen").assertIsDisplayed()
    }

    @Test
    fun t4_06_themeStudioSubPagesDeepNavigationTerminalFakeShellAndDockPopAll_baseline() {
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        val controller = Oe3ShellController(Oe3Tab.Config)
        val backStack = OeBackStack()

        compose.setContent {
            fun pushBackground() {
                backStack.push(
                    OeDestination("theme-bg", "背景与透明度") {
                        val handle = rememberThemeStudioHandle(store)
                        ThemeBackgroundScreen(
                            state = handle.state,
                            onEvent = handle.onEvent,
                            onBack = { backStack.pop() },
                        )
                    },
                )
            }

            fun pushTerminal() {
                backStack.push(
                    OeDestination("theme-term", "终端个性化") {
                        val handle = rememberThemeStudioHandle(store)
                        ThemeTerminalScreen(
                            state = handle.state,
                            onEvent = handle.onEvent,
                            onBack = { backStack.pop() },
                            renderTerminalView = false,
                        )
                    },
                )
            }

            Oe3App(
                selectedTab = controller.selectedTab,
                onTabSelected = controller::select,
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                backStack = backStack,
                pages = AcceptanceTestSupport.samplePages(
                    onConfigRoute = { route ->
                        if (route == ConfigRoute.THEME_STUDIO) {
                            backStack.push(
                                OeDestination("theme-studio", "主题与外观") {
                                    val handle = rememberThemeStudioHandle(store)
                                    ThemeStudioScreen(
                                        state = handle.state,
                                        onEvent = handle.onEvent,
                                        onBack = { backStack.pop() },
                                        onOpenTerminal = { pushTerminal() },
                                        onOpenBackground = { pushBackground() },
                                        reduceMotion = true,
                                    )
                                },
                            )
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()

        // Config -> Theme Studio
        compose.onNodeWithTag("config:route:appearance.theme_studio").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("theme-studio:screen").assertIsDisplayed()

        // Theme Studio -> Theme Terminal (scroll end marker first so terminal entry sits above bottom nav bar)
        compose.onNodeWithTag("theme-studio:end").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("theme-entry:terminal").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("theme-terminal:screen").assertIsDisplayed()
        compose.onNodeWithTag("theme-terminal:preview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("theme-terminal:sketch").assertIsDisplayed()

        // Run fake shell command
        compose.onNodeWithTag("theme-terminal:input").performScrollTo().performTextInput("help")
        compose.onNodeWithTag("theme-terminal:send").performClick()
        compose.waitForIdle()

        // Pop 2 -> 1 back to Theme Studio
        compose.runOnUiThread { backStack.pop() }
        compose.waitForIdle()
        compose.onNodeWithTag("theme-terminal:screen").assertDoesNotExist()
        compose.onNodeWithTag("theme-studio:screen").assertIsDisplayed()

        // Push Theme Background
        compose.onNodeWithTag("theme-studio:end").performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag("theme-entry:background").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("theme-background:screen").assertIsDisplayed()

        // Tap Hardware dock tab -> popAll() and land on HardwareScreen
        compose.onNodeWithText(Oe3Tab.Hardware.label).performClick()
        compose.waitForIdle()
        assertEquals(0, backStack.size)
        assertFalse(backStack.canPop)
        compose.onNodeWithTag("theme-background:screen").assertDoesNotExist()
        compose.onNodeWithTag("theme-studio:screen").assertDoesNotExist()
        compose.onNodeWithTag("hardware:screen").assertIsDisplayed()
    }
}
