package io.github.nyzeep.orchestella.ui.acceptance

import android.app.Application
import android.os.Build
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ConnectionPagesActivity
import io.github.nyzeep.orchestella.InstanceCreationWizardModel
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3BackButton
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3GridCard
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ListRow
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.components.OeListGroup
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassDeviceCap
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassMode
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassTier
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassTierOverride
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassFrameGate
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassStrategy
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Glass
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TypeScale
import io.github.nyzeep.orchestella.ui.screens.config.ConfigOptions
import io.github.nyzeep.orchestella.ui.screens.config.ConfigPreferences
import io.github.nyzeep.orchestella.ui.screens.config.DefaultMirror
import io.github.nyzeep.orchestella.ui.screens.config.SectionMotion
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardCardId
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardEditState
import io.github.nyzeep.orchestella.ui.screens.theme.HomePreview
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeOptionRow
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioController
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioEvent
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioScreen
import io.github.nyzeep.orchestella.ui.screens.theme.rememberThemeStudioHandle
import io.github.nyzeep.orchestella.ui.screens.wizard.HardwareBadge
import io.github.nyzeep.orchestella.ui.screens.wizard.StorageGate
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardController
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardScreen
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardUiState
import io.github.nyzeep.orchestella.ui.shell.LocalOeBackStack
import io.github.nyzeep.orchestella.ui.shell.LocalOeSurfaceAlpha
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3NavStyle
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3ShellController
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.OeScaffoldAction
import io.github.nyzeep.orchestella.ui.shell.OeScaffoldSpec
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/**
 * Tier 1 — Primary Feature Acceptance Tests (28 tests across R1–R5).
 *
 * Method suffixes indicate milestone gates per TEST_INFRA.md:
 * - `_baseline`: passes on baseline or M1 completion.
 * - `_m2`: targets M2 deliverables (R2 tonal ramp / bottom-bar separation / alpha / R4 integrated header).
 * - `_m3`: targets M3 deliverables (R5 L2 AGSL glass / PredictiveBackHandler / manifest).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-mdpi")
class Tier1FeatureCoverageTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    // =========================================================================
    // R1: Architecture, Token Gate & Lossless Business State (6 tests)
    // =========================================================================

    @Test
    fun t1_r1_01_tokenGatePassesZeroFailAndZeroShadows_baseline() {
        val result = AcceptanceTestSupport.runCheckTokens()
        assertEquals(
            "check_tokens.py failed (exit=${result.exitCode}):\n${result.stdout}\n${result.stderr}",
            0,
            result.exitCode,
        )
        assertTrue("Expected '0 FAIL' in stdout:\n${result.stdout}", result.stdout.contains("0 FAIL"))
        assertTrue("Expected 'RESULT: PASS' in stdout:\n${result.stdout}", result.stdout.contains("RESULT: PASS"))
        assertTrue(
            "Expected 0 .shadow( occurrences in stdout:\n${result.stdout}",
            result.stdout.contains("0 occurrence(s)"),
        )
    }

    @Test
    fun t1_r1_02_themeStoreRoundTripPreservesAllKeysAndBounds_baseline() {
        val prefs = AcceptanceTestSupport.InMemorySharedPreferences()
        val store = ThemeStore(prefs)

        store.setThemeId(ThemeCatalog.catppuccin.id)
        store.setMode(ThemeStore.Mode.DARK)
        store.setBackgroundUri("content://com.android.providers.media/1")
        store.setTerminalBackgroundUri("content://com.android.providers.media/2")
        store.setTerminalScrimAlpha(200)
        store.setUiPureBlack(true)
        store.setUiGlassLevel("ultra")
        store.setUiGlassMode("always")
        store.setUiGlassDeviceCap("l0")
        store.setUiNavStyle("fixed")
        store.setUiFontChoice("mono")
        store.setCornerRadiusDp(20)
        store.setUiSurfaceOpacityPercent(72)
        store.setTextScale(OeTextScaleMode.Custom, 125)
        store.setTerminalFontSizeSp(18)
        store.setTerminalLetterSpacingEm(0.12f)
        store.setTerminalLineSpacingPx(4.0f)
        store.setCustomAccent(0xFF112233.toInt())
        store.setCustomText(0xFFEEEEEE.toInt())
        val slot = CustomThemeSlot(
            name = "My Slot",
            isDark = true,
            background = 0xFF010203.toInt(),
            surface = 0xFF101216.toInt(),
            primary = 0xFF3399FF.toInt(),
            accent = 0xFFFF6699.toInt(),
            text = 0xFFF5F5F5.toInt(),
            textDim = 0xFFA0A0A0.toInt(),
            terminalBackground = 0xFF010203.toInt(),
            terminalForeground = 0xFFF5F5F5.toInt(),
        )
        store.setCustomSlot(0, slot)

        val reloaded = ThemeStore(prefs)
        assertEquals(ThemeCatalog.catppuccin.id, reloaded.themeId())
        assertEquals(ThemeStore.Mode.DARK, reloaded.mode())
        assertEquals("content://com.android.providers.media/1", reloaded.backgroundUri())
        assertEquals("content://com.android.providers.media/2", reloaded.terminalBackgroundUri())
        assertEquals(200, reloaded.terminalScrimAlpha())
        assertTrue(reloaded.uiPureBlack())
        assertEquals("ultra", reloaded.uiGlassLevel())
        assertEquals("always", reloaded.uiGlassMode())
        assertEquals("l0", reloaded.uiGlassDeviceCap())
        assertEquals("fixed", reloaded.uiNavStyle())
        assertEquals("mono", reloaded.uiFontChoice())
        assertEquals(20, reloaded.cornerRadiusDp())
        assertEquals(72, reloaded.uiSurfaceOpacityPercent())
        assertEquals(OeTextScaleMode.Custom, reloaded.textScaleMode())
        assertEquals(125, reloaded.textScalePercent())
        assertEquals(18, reloaded.terminalFontSizeSp())
        assertEquals(0.12f, reloaded.terminalLetterSpacingEm(), 0.0001f)
        assertEquals(4.0f, reloaded.terminalLineSpacingPx(), 0.0001f)
        assertEquals(0xFF112233.toInt(), reloaded.customAccent())
        assertEquals(0xFFEEEEEE.toInt(), reloaded.customText())
        assertEquals(slot, reloaded.customSlot(0))
        assertNull(reloaded.customSlot(1))
        assertNull(reloaded.customSlot(2))

        // Bounds verification
        reloaded.setTerminalFontSizeSp(99)
        assertEquals(22, reloaded.terminalFontSizeSp())
        reloaded.setTerminalFontSizeSp(2)
        assertEquals(10, reloaded.terminalFontSizeSp())
        reloaded.setUiSurfaceOpacityPercent(10)
        assertEquals(55, reloaded.uiSurfaceOpacityPercent())
        reloaded.setUiSurfaceOpacityPercent(150)
        assertEquals(100, reloaded.uiSurfaceOpacityPercent())
        reloaded.setCornerRadiusDp(99)
        assertEquals(24, reloaded.cornerRadiusDp())
        reloaded.setCornerRadiusDp(-3)
        assertEquals(0, reloaded.cornerRadiusDp())
        reloaded.setTextScale(OeTextScaleMode.Custom, 200)
        assertEquals(130, reloaded.textScalePercent())
        reloaded.setTextScale(OeTextScaleMode.Custom, 50)
        assertEquals(85, reloaded.textScalePercent())
    }

    @Test
    fun t1_r1_03_dashboardEditAndConfigAndTabKeysRemainLossless_baseline() {
        assertEquals(
            listOf("workstation", "instances", "hardware", "distros", "config"),
            Oe3Tab.entries.map { it.key },
        )

        val editStore = AcceptanceTestSupport.InMemoryDashboardEditStore()
        val edit = DashboardEditState(editStore)
        edit.setEditing(true)
        edit.moveVisible(1, 0) // Storage before Primary
        edit.hide(DashboardCardId.Recent)
        edit.setEditing(false)

        val reloadedEdit = DashboardEditState(editStore)
        assertFalse("isEditing must never persist", reloadedEdit.isEditing)
        assertEquals(listOf(DashboardCardId.Storage, DashboardCardId.Primary, DashboardCardId.Metrics), reloadedEdit.visible)
        assertEquals(listOf(DashboardCardId.Recent), reloadedEdit.hidden)
        assertEquals(4, reloadedEdit.order.size)

        // Restore brings card back without losing slot
        reloadedEdit.setEditing(true)
        reloadedEdit.restore(DashboardCardId.Recent)
        assertEquals(4, reloadedEdit.visible.size)
        assertTrue(reloadedEdit.hidden.isEmpty())

        val configStore = AcceptanceTestSupport.InMemoryConfigStore()
        val prefs = ConfigPreferences(configStore)
        val initial = prefs.readOptions()
        val updated = ConfigOptions(
            sectionMotion = SectionMotion.Reduced,
            defaultMirror = DefaultMirror.Tuna,
            bootRestore = true,
            updateCheck = false,
        )
        prefs.writeOptions(initial, updated)
        assertEquals(updated, ConfigPreferences(configStore).readOptions())
    }

    @Test
    fun t1_r1_04_wizardUiStateProjectionIsPureAndEnforcesStepGates_baseline() {
        val model = AcceptanceTestSupport.sampleWizardModel(
            distroId = "ubuntu",
            totalRamBytes = 12L * 1024L * 1024L * 1024L,
            availRamBytes = 8L * 1024L * 1024L * 1024L,
        )
        val stepBefore = model.currentStep
        val diskBefore = model.diskSizeGb

        // Valid storage step -> canProceed == true
        val validStorage = WizardUiState.of(
            model,
            AcceptanceTestSupport.sampleWizardFacts(availableBytes = 64L * 1024L * 1024L * 1024L),
        )
        assertEquals(StorageGate.Ready, validStorage.storage.gate)
        assertTrue(validStorage.canProceed)
        assertEquals(stepBefore, model.currentStep)
        assertEquals(diskBefore, model.diskSizeGb)

        // Pending storage debounce -> canProceed == false
        val pendingStorage = WizardUiState.of(
            model,
            AcceptanceTestSupport.sampleWizardFacts(
                availableBytes = 64L * 1024L * 1024L * 1024L,
                storageCheckPending = true,
            ),
        )
        assertFalse(pendingStorage.canProceed)

        // Invalid disk input -> canProceed == false
        val invalidStorage = WizardUiState.of(
            model,
            AcceptanceTestSupport.sampleWizardFacts(
                availableBytes = 64L * 1024L * 1024L * 1024L,
                diskInputInvalid = true,
            ),
        )
        assertFalse(invalidStorage.canProceed)

        // Insufficient space -> canProceed == false
        val fullStorage = WizardUiState.of(
            model,
            AcceptanceTestSupport.sampleWizardFacts(availableBytes = 100L * 1024L * 1024L),
        )
        assertTrue(fullStorage.storage.gate is StorageGate.InsufficientSpace)
        assertFalse(fullStorage.canProceed)

        // Step 2 -> 3 (Hardware gate): RED zone / low RAM -> HardwareBadge.Error -> canProceed == false
        val lowMemModel = AcceptanceTestSupport.sampleWizardModel(
            distroId = "ubuntu",
            totalRamBytes = 2L * 1024L * 1024L * 1024L,
            availRamBytes = 512L * 1024L * 1024L,
        )
        lowMemModel.nextStep() // step 2
        lowMemModel.nextStep() // step 3
        val badHw = WizardUiState.of(lowMemModel, AcceptanceTestSupport.sampleWizardFacts())
        assertTrue(badHw.hardware.badge is HardwareBadge.Error)
        assertFalse(badHw.canProceed)

        // Creating -> canProceed == false on every step
        model.setCreating(true)
        val creatingState = WizardUiState.of(
            model,
            AcceptanceTestSupport.sampleWizardFacts(availableBytes = 64L * 1024L * 1024L * 1024L),
        )
        assertFalse(creatingState.canProceed)
    }

    @Test
    fun t1_r1_05_preservedTestTagsAndStructureContractsPresent_baseline() {
        val controller = Oe3ShellController(Oe3Tab.Workstation)
        val backStack = OeBackStack()
        val themeStore = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        val wizardController = WizardController(
            context = compose.activity,
            targetDistroId = "ubuntu",
            callbacks = WizardController.Callbacks(onCreated = {}, onShowShizukuClient = {}),
        )
        try {
            compose.setContent {
                val themeHandle = rememberThemeStudioHandle(themeStore)
                Oe3App(
                    selectedTab = controller.selectedTab,
                    onTabSelected = controller::select,
                    theme = ThemeCatalog.mascotLight,
                    reduceMotion = true,
                    backStack = backStack,
                    pages = AcceptanceTestSupport.samplePages(),
                )
            }
            compose.waitForIdle()
            compose.onNodeWithTag("shell:nav-bar").assertExists()
            compose.onNodeWithTag("dashboard:cards").assertExists()

            compose.onNodeWithText(Oe3Tab.Instances.label).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("instances-create").assertExists()
            compose.onNodeWithTag("instances-import").assertExists()

            compose.onNodeWithText(Oe3Tab.Hardware.label).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("hardware:refresh").assertExists()

            compose.onNodeWithText(Oe3Tab.Distros.label).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("distros-grid").assertExists()

            compose.onNodeWithText(Oe3Tab.Config.label).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("config:screen").assertExists()

            compose.runOnUiThread {
                backStack.push(
                    OeDestination("theme-studio", "主题与外观") {
                        val handle = rememberThemeStudioHandle(themeStore)
                        ThemeStudioScreen(
                            state = handle.state,
                            onEvent = handle.onEvent,
                            onBack = { backStack.pop() },
                            reduceMotion = true,
                        )
                    },
                )
            }
            compose.waitForIdle()
            compose.onNodeWithTag("theme-studio:screen").assertExists()

            compose.runOnUiThread {
                backStack.push(
                    OeDestination("wizard", "新建实例") {
                        WizardScreen(controller = wizardController, onExit = { backStack.pop() })
                    },
                )
            }
            compose.waitForIdle()
            compose.onNodeWithTag("wizard:back").assertExists()

            // Structural source gate in ui/oe3/{tokens,theme,components}
            val uiRoot = AcceptanceTestSupport.uiSourceRoot()
            val oe3Subdirs = listOf("oe3/tokens", "oe3/theme", "oe3/components")
            oe3Subdirs.forEach { rel ->
                File(uiRoot, rel).walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                    val txt = f.readText()
                    assertFalse("${f.name} must not contain .border(", txt.contains(".border("))
                    assertFalse("${f.name} must not contain BorderStroke", txt.contains("BorderStroke"))
                }
            }
        } finally {
            wizardController.dispose()
        }
    }

    @Test
    fun t1_r1_06_oe3ShellControllerBackAndTabSelectionContract_baseline() {
        val controller = Oe3ShellController(Oe3Tab.Workstation)
        assertFalse("Workstation back() must return false", controller.back())
        assertEquals(Oe3Tab.Workstation, controller.selectedTab)

        for (tab in Oe3Tab.entries.filter { it != Oe3Tab.Workstation }) {
            controller.select(tab)
            assertEquals(tab, controller.selectedTab)
            assertTrue("${tab.name} back() must return true", controller.back())
            assertEquals(Oe3Tab.Workstation, controller.selectedTab)
        }

        for (tab in Oe3Tab.entries) {
            assertEquals(tab, Oe3ShellController.restore(tab.key))
        }
        assertEquals(Oe3Tab.Workstation, Oe3ShellController.restore("nonexistent-tab"))
        assertEquals(Oe3Tab.Workstation, Oe3ShellController.restore(null))
    }

    // =========================================================================
    // R2: Unified Design System, >=48dp Touch Targets & Dock Separation (6 tests)
    // =========================================================================

    @Test
    fun t1_r2_01_allInteractivePrimitivesAndScreenControlsMeet48DpFloor_baseline() {
        var switchVal by mutableStateOf(false)
        var selectedTab by mutableStateOf(Oe3Tab.Workstation)
        var stage by mutableIntStateOf(0)
        compose.setContent {
            if (stage == 0) {
                Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                    OeScaffold(
                        title = "测试",
                        leading = {
                            Oe3BackButton(
                                onBack = {},
                                contentDescription = "返回",
                                testTag = "test:back",
                            )
                        },
                        actions = listOf(
                            OeScaffoldAction(
                                icon = Oe3Icons.Edit,
                                contentDescription = "编辑",
                                onClick = {},
                                testTag = "test:scaffold-action",
                            ),
                        ),
                    ) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            Oe3Button(
                                text = "主要按钮",
                                onClick = {},
                                style = Oe3ButtonStyle.Pill,
                                modifier = Modifier.testTag("test:btn-pill"),
                            )
                            Oe3Button(
                                text = "次要按钮",
                                onClick = {},
                                style = Oe3ButtonStyle.Tonal,
                                modifier = Modifier.testTag("test:btn-tonal"),
                            )
                            Oe3Button(
                                text = "文本按钮",
                                onClick = {},
                                style = Oe3ButtonStyle.Text,
                                modifier = Modifier.testTag("test:btn-text"),
                            )
                            Oe3Chip(
                                label = "筛选",
                                onClick = {},
                                selected = true,
                                modifier = Modifier.testTag("test:chip"),
                            )
                            Oe3ListRow(
                                title = "列表行",
                                onClick = {},
                                modifier = Modifier.testTag("test:list-row"),
                            )
                            Oe3SwitchRow(
                                title = "开关行",
                                checked = switchVal,
                                onCheckedChange = { switchVal = it },
                                modifier = Modifier.testTag("test:switch-row"),
                            )
                            ThemeOptionRow(
                                title = "选项行",
                                selected = true,
                                onSelect = {},
                                modifier = Modifier.testTag("test:theme-option"),
                                thumbnail = { Box(Modifier.height(30.dp)) },
                            )
                        }
                    }
                }
            } else {
                Oe3App(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    theme = ThemeCatalog.constructivistWorkstation,
                    reduceMotion = true,
                )
            }
        }
        compose.waitForIdle()

        val density = compose.density
        listOf(
            "test:back",
            "test:scaffold-action",
            "test:btn-pill",
            "test:btn-tonal",
            "test:btn-text",
            "test:chip",
            "test:list-row",
            "test:switch-row",
            "test:theme-option",
        ).forEach { tag ->
            AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(
                compose.onNodeWithTag(tag, useUnmergedTree = true),
                density,
                tag,
            )
        }

        val rowMinPx = with(density) { Oe3Spacing.rowHeight.toPx() } - 0.5f
        listOf("test:list-row", "test:switch-row").forEach { tag ->
            val h = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
            assertTrue("$tag height $h < 56dp ($rowMinPx)", h >= rowMinPx)
        }

        // Verify Oe3NavBar tabs in stage 1
        stage = 1
        compose.waitForIdle()

        val tabNodes = compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
        )
        tabNodes.assertCountEquals(5)
        compose.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and isSelected(),
        ).assertCountEquals(1)
        Oe3Tab.entries.forEach { tab ->
            AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(
                compose.onNode(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(tab.label),
                ),
                density,
                "nav-tab:${tab.key}",
            )
        }
    }

    @Test
    fun t1_r2_02_oe3ColorsTonalRampHasDistinctStepsInDarkLightPureBlack_m2() {
        val darkThemes = listOf(
            ThemeCatalog.mascotDark,
            ThemeCatalog.constructivistWorkstation,
            ThemeCatalog.catppuccin,
        )
        darkThemes.forEach { theme ->
            val c = Oe3Colors.resolve(theme, pureBlack = false)
            val lBg = AcceptanceTestSupport.wcagLuminance(c.background)
            val lSurf = AcceptanceTestSupport.wcagLuminance(c.surface)
            val lRaised = AcceptanceTestSupport.wcagLuminance(c.surfaceRaised)
            val lHigh = AcceptanceTestSupport.wcagLuminance(c.surfaceHigh)
            assertTrue("${theme.id}: L(bg) < L(surface)", lBg < lSurf)
            assertTrue("${theme.id}: L(surface) < L(raised)", lSurf < lRaised)
            assertTrue("${theme.id}: L(raised) < L(high)", lRaised < lHigh)
            // M2 F8: widened dark tonal steps (>= 12 RGB units per step for clear hierarchy)
            val step1 = AcceptanceTestSupport.rgbChannelDistance(c.surface, c.surfaceRaised)
            val step2 = AcceptanceTestSupport.rgbChannelDistance(c.surfaceRaised, c.surfaceHigh)
            assertTrue(
                "${theme.id}: M2 dark tonal step surface->raised too narrow ($step1 < 12.0)",
                step1 >= 12.0,
            )
            assertTrue(
                "${theme.id}: M2 dark tonal step raised->high too narrow ($step2 < 12.0)",
                step2 >= 12.0,
            )
        }

        val lightThemes = listOf(ThemeCatalog.mascotLight, ThemeCatalog.catppuccinLatte)
        lightThemes.forEach { theme ->
            val c = Oe3Colors.resolve(theme, pureBlack = false)
            val lSurf = AcceptanceTestSupport.wcagLuminance(c.surface)
            val lRaised = AcceptanceTestSupport.wcagLuminance(c.surfaceRaised)
            val lHigh = AcceptanceTestSupport.wcagLuminance(c.surfaceHigh)
            assertTrue("${theme.id}: L(surface) > L(raised)", lSurf > lRaised)
            assertTrue("${theme.id}: L(raised) > L(high)", lRaised > lHigh)
        }

        val pureBlack = Oe3Colors.resolve(ThemeCatalog.mascotDark, pureBlack = true)
        assertEquals(0xFF000000.toInt(), pureBlack.background)
        assertEquals(0xFF0B0B0C.toInt(), pureBlack.surface)
        assertEquals(0xFF131315.toInt(), pureBlack.surfaceRaised)
        assertEquals(0xFF1C1C1F.toInt(), pureBlack.surfaceHigh)

        // M2 R2 contrast polish: all 42 presets must meet 4.5:1 for onSurface/surface as well
        ThemeCatalog.all.forEach { theme ->
            val tokens = Oe3Colors.resolve(theme, pureBlack = false)
            val ratio = AcceptanceTestSupport.wcagContrast(tokens.onSurface, tokens.surface)
            assertTrue("${theme.id} onSurface/surface=$ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun t1_r2_03_bottomBarSurfaceSeparatesVisiblyFromAdjacentCards_m2() {
        val configs = listOf(
            Triple(ThemeCatalog.mascotDark, false, Oe3NavStyle.Floating),
            Triple(ThemeCatalog.mascotDark, true, Oe3NavStyle.Floating),
            Triple(ThemeCatalog.mascotLight, false, Oe3NavStyle.Floating),
            Triple(ThemeCatalog.mascotDark, false, Oe3NavStyle.Fixed),
        )
        var activeIndex by mutableIntStateOf(0)
        compose.setContent {
            val (theme, pureBlack, navStyle) = configs[activeIndex]
            CompositionLocalProvider(LocalOe3GlassTierOverride provides GlassTier.Solid) {
                Oe3App(
                    selectedTab = Oe3Tab.Workstation,
                    onTabSelected = {},
                    theme = theme,
                    pureBlack = pureBlack,
                    navStyle = navStyle,
                    glassLevel = Oe3GlassLevel.None,
                    reduceMotion = true,
                    pages = Oe3Pages(
                        workstation = {
                            Column(Modifier.fillMaxSize()) {
                                Spacer(Modifier.weight(1f))
                                Oe3Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .testTag("probe:bottom-card"),
                                ) {
                                    Box(Modifier.fillMaxSize())
                                }
                            }
                        },
                    ),
                )
            }
        }

        configs.forEachIndexed { idx, (theme, pureBlack, navStyle) ->
            val tokens = Oe3Colors.resolve(theme, pureBlack = pureBlack)
            val glassTokens = Oe3Glass.resolve(theme, Oe3GlassLevel.Standard, pureBlack = pureBlack)
            assertNotEquals(
                "Dock glass tint (${Integer.toHexString(glassTokens.tint)}) must differ from card surface (${Integer.toHexString(tokens.surface)}) for ${theme.id}/pb=$pureBlack",
                tokens.surface,
                glassTokens.tint,
            )
            val solidTokens = Oe3Glass.resolve(theme, Oe3GlassLevel.None, pureBlack = pureBlack)
            assertNotEquals(
                "Solid dock tint must differ from card surface for ${theme.id}/pb=$pureBlack",
                tokens.surface,
                solidTokens.tint,
            )

            activeIndex = idx
            compose.waitForIdle()

            val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val navBounds = compose.onNodeWithTag("shell:nav-bar").fetchSemanticsNode().boundsInRoot
            if (navStyle == Oe3NavStyle.Floating) {
                val expectedGap = with(compose.density) { 21.dp.toPx() }
                val actualGap = rootBounds.bottom - navBounds.bottom
                assertEquals("Floating nav bottom gap must be 21dp", expectedGap, actualGap, 2.0f)
            } else {
                assertEquals("Fixed nav must dock to bottom", rootBounds.bottom, navBounds.bottom, 2.0f)
            }

            val img = AcceptanceTestSupport.captureRootBitmap(compose, "t1_r2_03_$idx")
            val cardSample = AcceptanceTestSupport.sampleAverageColor(
                img,
                xRange = (navBounds.left.roundToInt() + 20)..(navBounds.right.roundToInt() - 20),
                yRange = (navBounds.top.roundToInt() - 40)..(navBounds.top.roundToInt() - 15),
            )
            val dockSample = AcceptanceTestSupport.sampleAverageColor(
                img,
                xRange = (navBounds.right.roundToInt() - 24)..(navBounds.right.roundToInt() - 10),
                yRange = (navBounds.top.roundToInt() + 10)..(navBounds.bottom.roundToInt() - 10),
            )
            val delta = AcceptanceTestSupport.colorDistance(cardSample, dockSample)
            assertTrue(
                "Rendered dock vs card RGB delta too small ($delta < 4.0) for ${theme.id}/pb=$pureBlack/$navStyle",
                delta >= 4.0,
            )
        }
    }

    @Test
    fun t1_r2_04_designSystemSpacingAndRadiusTokensPropagateToCards_m2() {
        var observedCorner: Dp? = null
        var observedAlpha: Float = -1f
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                cornerRadiusDp = 12,
                surfaceAlpha = 0.55f,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        observedCorner = LocalOe3CornerRadius.current
                        observedAlpha = LocalOeSurfaceAlpha.current
                        Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                            Column(Modifier.fillMaxSize().padding(Oe3Spacing.pageInset)) {
                                Oe3Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                        .testTag("probe:alpha-card"),
                                ) {
                                    Text("Alpha Card")
                                }
                                Spacer(Modifier.height(Oe3Spacing.gridGap))
                                OeListGroup(modifier = Modifier.fillMaxWidth()) {
                                    Oe3ListRow(title = "Group Row")
                                }
                                Spacer(Modifier.height(Oe3Spacing.gridGap))
                                Oe3GridCard(modifier = Modifier.fillMaxWidth()) {
                                    Text("Grid Card")
                                }
                            }
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()
        assertEquals(12.dp, observedCorner)
        assertEquals(0.55f, observedAlpha, 0.001f)

        // Verify Oe3Card genuinely consumes LocalOeSurfaceAlpha (pixel blends with Magenta backdrop)
        val cardBounds = compose.onNodeWithTag("probe:alpha-card").fetchSemanticsNode().boundsInRoot
        val img = AcceptanceTestSupport.captureRootBitmap(compose, "t1_r2_04")
        val sampled = AcceptanceTestSupport.sampleAverageColor(
            img,
            xRange = (cardBounds.left.roundToInt() + 30)..(cardBounds.right.roundToInt() - 30),
            yRange = (cardBounds.top.roundToInt() + 40)..(cardBounds.bottom.roundToInt() - 20),
        )
        // Magenta contributes high red & blue when alpha = 0.55f; opaque #14151A has low red (<0.15)
        assertTrue(
            "Oe3Card must apply LocalOeSurfaceAlpha=0.55f over backdrop, observed red=${sampled.red}",
            sampled.red >= 0.30f,
        )
    }

    @Test
    fun t1_r2_05_typographyRolesAndWcagContrastPassAcrossAll41Presets_baseline() {
        assertEquals(8, Oe3TextRole.entries.size)
        assertEquals(1.30f, Oe3TypeScale.effective(1.0f, 1.50f), 0.001f)
        assertEquals(0.85f, Oe3TypeScale.effective(1.0f, 0.50f), 0.001f)
        assertTrue("Catalog must contain >= 41 presets", ThemeCatalog.presets.size >= 41)

        ThemeCatalog.all.forEach { theme ->
            val tokens = Oe3Colors.resolve(theme, pureBlack = false)
            val onSurfaceVarRatio = AcceptanceTestSupport.wcagContrast(tokens.onSurfaceVariant, tokens.surface)
            val onPrimaryRatio = AcceptanceTestSupport.wcagContrast(tokens.onPrimary, tokens.primary)
            assertTrue("${theme.id} onSurfaceVariant/surface=$onSurfaceVarRatio < 4.5", onSurfaceVarRatio >= 4.5)
            assertTrue("${theme.id} onPrimary/primary=$onPrimaryRatio < 4.5", onPrimaryRatio >= 4.5)
        }
    }

    @Test
    fun t1_r2_06_legacyXmlParityAndZeroHardcodedVisualPrimitives_baseline() {
        val mainDir = AcceptanceTestSupport.appMainDir()
        val layouts = File(mainDir, "res/layout").walkTopDown().filter { it.isFile && it.extension == "xml" }.toList()
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { xml ->
            val text = xml.readText()
            assertFalse("${xml.name} has cornerRadius=0dp", text.contains("cornerRadius=\"0dp\""))
            assertFalse("${xml.name} has cardCornerRadius=0dp", text.contains("cardCornerRadius=\"0dp\""))
        }

        val allSources = File(mainDir, "java").walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        allSources.forEach { src ->
            val lines = src.readLines()
            lines.forEach { line ->
                val trimmed = line.trimStart()
                assertFalse("${src.name} imports SeekBar", trimmed.startsWith("import android.widget.SeekBar"))
                assertFalse("${src.name} imports Material Slider", trimmed.startsWith("import com.google.android.material.slider.Slider"))
            }
        }

        val tokensXml = File(mainDir, "res/values/oe_design_tokens.xml").readText()
        assertTrue(tokensXml.contains("<dimen name=\"oe_corner_card\""))

        val result = AcceptanceTestSupport.runCheckTokens()
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("0 occurrence(s)"))
    }

    // =========================================================================
    // R3: Flicker-Free Theme Studio, Presets & Navigation Transitions (6 tests)
    // =========================================================================

    @Test
    fun t1_r3_01_themeApplierDoesNotMutateComposeHostedActivities_baseline() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ThemeStore(app)
        store.setThemeId(ThemeCatalog.github.id)
        store.setMode(ThemeStore.Mode.DARK)
        store.setBackgroundUri("content://test/wallpaper/excluded")

        val activity = Robolectric.buildActivity(
            ConnectionPagesActivity::class.java,
            ConnectionPagesActivity.sshIntent(app),
        ).setup().get()
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val composeRoot = content.getChildAt(0)
        assertNotNull(composeRoot)
        val initialBackground = composeRoot.background
        assertEquals(android.graphics.Color.TRANSPARENT, activity.window.statusBarColor)
        assertEquals(android.graphics.Color.TRANSPARENT, activity.window.navigationBarColor)

        // Mutate theme + mode and explicitly invoke ThemeApplier.bind(activity)
        store.setThemeId(ThemeCatalog.mascotLight.id)
        store.setMode(ThemeStore.Mode.LIGHT)
        ThemeApplier.bind(activity)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(android.graphics.Color.TRANSPARENT, activity.window.statusBarColor)
        assertEquals(android.graphics.Color.TRANSPARENT, activity.window.navigationBarColor)
        assertFalse(composeRoot.background is android.graphics.drawable.LayerDrawable)
        assertEquals(
            "ThemeApplier must NOT overwrite Compose-hosted Activity root background",
            initialBackground,
            composeRoot.background,
        )
        activity.finish()
    }

    @Test
    fun t1_r3_02_selectingPresetPreservesWallpaperPureBlackAndSystemMode_baseline() {
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        store.setMode(ThemeStore.Mode.SYSTEM)
        store.setBackgroundUri("content://test/wallpaper.png")
        store.setUiPureBlack(true)
        store.setUiSurfaceOpacityPercent(80)

        store.setThemeId(ThemeCatalog.catppuccin.id)

        assertEquals("content://test/wallpaper.png", store.backgroundUri())
        assertTrue(store.uiPureBlack())
        assertEquals(80, store.uiSurfaceOpacityPercent())
        assertEquals(
            "ThemeStore.setThemeId must preserve Mode.SYSTEM instead of overwriting KEY_THEME_MODE",
            ThemeStore.Mode.SYSTEM,
            store.mode(),
        )
        assertEquals(ThemeCatalog.catppuccinLatte.id, store.resolveForMode(systemDark = false).id)
        assertEquals(ThemeCatalog.catppuccin.id, store.resolveForMode(systemDark = true).id)
    }

    @Test
    fun t1_r3_03_backStackPushPopTwoLevelsHasZeroGhosting_baseline() {
        val backStack = OeBackStack()
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = false,
                backStack = backStack,
                pages = Oe3Pages(
                    workstation = { Box(Modifier.fillMaxSize().testTag("pager:base")) },
                ),
            )
        }
        compose.waitForIdle()

        // Push A
        compose.runOnUiThread {
            backStack.push(OeDestination("dest-a", "页面 A") { Box(Modifier.fillMaxSize().testTag("dest-a")) })
        }
        compose.waitForIdle()
        compose.onNodeWithTag("dest-a").assertIsDisplayed()
        compose.onNodeWithTag("pager:base").assertDoesNotExist()

        // Push B
        compose.runOnUiThread {
            backStack.push(OeDestination("dest-b", "页面 B") { Box(Modifier.fillMaxSize().testTag("dest-b")) })
        }
        compose.waitForIdle()
        compose.onNodeWithTag("dest-b").assertIsDisplayed()
        compose.onNodeWithTag("dest-a").assertDoesNotExist()

        // Pop B (2 -> 1): mid-flight must reveal A (under layer), never base pager bleed-through
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread {
                Snapshot.withMutableSnapshot { backStack.pop() }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("dest-a").assertExists()
            compose.onNodeWithTag("pager:base").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }

        // Settle 2 -> 1: only A displayed; B and base pager absent
        compose.waitForIdle()
        compose.onNodeWithTag("dest-b").assertDoesNotExist()
        compose.onNodeWithTag("dest-a").assertIsDisplayed()
        compose.onNodeWithTag("pager:base").assertDoesNotExist()

        // Pop A (1 -> 0): base pager returns
        compose.runOnUiThread { backStack.pop() }
        compose.waitForIdle()
        compose.onNodeWithTag("dest-a").assertDoesNotExist()
        compose.onNodeWithTag("pager:base").assertIsDisplayed()
    }

    @Test
    fun t1_r3_04_backStackPopAllOnTabSwitchClearsInstantlyWithoutPopFlight_baseline() {
        val controller = Oe3ShellController(Oe3Tab.Workstation)
        val backStack = OeBackStack()
        compose.setContent {
            Oe3App(
                selectedTab = controller.selectedTab,
                onTabSelected = controller::select,
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                backStack = backStack,
                pages = AcceptanceTestSupport.samplePages(),
            )
        }
        compose.waitForIdle()

        compose.runOnUiThread {
            backStack.push(OeDestination("lvl1", "一级") { Box(Modifier.testTag("stack:lvl1")) })
            backStack.push(OeDestination("lvl2", "二级") { Box(Modifier.testTag("stack:lvl2")) })
        }
        compose.waitForIdle()
        assertEquals(2, backStack.size)
        compose.onNodeWithTag("stack:lvl2").assertExists()

        compose.onNodeWithText(Oe3Tab.Instances.label).performClick()
        compose.waitForIdle()

        assertEquals(0, backStack.size)
        assertFalse(backStack.canPop)
        compose.onNodeWithTag("stack:lvl1").assertDoesNotExist()
        compose.onNodeWithTag("stack:lvl2").assertDoesNotExist()
        compose.onNodeWithTag("instances-list").assertIsDisplayed()
    }

    @Test
    fun t1_r3_05_pagerPreservesTabScrollStateAcrossTabSwitches_baseline() {
        val controller = Oe3ShellController(Oe3Tab.Workstation)
        compose.setContent {
            Oe3App(
                selectedTab = controller.selectedTab,
                onTabSelected = controller::select,
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        LazyColumn(Modifier.fillMaxSize().testTag("tab1:list")) {
                            items(40) { idx ->
                                Box(Modifier.fillMaxWidth().height(72.dp)) {
                                    Text("Workstation Row #$idx")
                                }
                            }
                        }
                    },
                    hardware = {
                        Box(Modifier.fillMaxSize().testTag("tab3:hardware")) {
                            Text("Hardware Page")
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("tab1:list").performScrollToIndex(22)
        compose.waitForIdle()
        compose.onNodeWithText("Workstation Row #22").assertIsDisplayed()
        compose.onNodeWithText("Workstation Row #0").assertDoesNotExist()

        // Switch away to Hardware and back to Workstation
        compose.onNodeWithText(Oe3Tab.Hardware.label).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tab3:hardware").assertIsDisplayed()

        compose.onNodeWithText(Oe3Tab.Workstation.label).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Workstation Row #22").assertIsDisplayed()
    }

    @Test
    fun t1_r3_06_homePreviewIsolatesDraftStateFromCommittedThemeStore_baseline() {
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        store.setBackgroundUri("content://committed/wallpaper.png")
        val controller = ThemeStudioController(store)
        val draftTheme = ThemeCatalog.catppuccin

        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.mascotLight, reduceMotion = true) {
                HomePreview(
                    theme = draftTheme,
                    pureBlack = true,
                    cornerDp = 24,
                    surfaceOpacityPercent = 70,
                    fontFamily = FontFamily.Monospace,
                    reduceMotion = true,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("theme-preview:home").assertIsDisplayed()
        assertEquals("content://committed/wallpaper.png", store.backgroundUri())
        assertEquals(ThemeCatalog.mascotLight.id, controller.read().theme.id)
    }

    // =========================================================================
    // R4: Background-Integrated Scroll-Edge Feature Headers (5 tests)
    // =========================================================================

    @Test
    fun t1_r4_01_oeScaffoldRendersSingleTitleAndIntegratesOverBackground_m2() {
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = AcceptanceTestSupport.samplePages(),
            )
        }
        compose.waitForIdle()
        compose.onAllNodesWithText("Orchestella").assertCountEquals(0)
        // Title exists in header (plus nav label "工作站" in dock = 2; no 3rd duplicate)
        val cardsBounds = compose.onNodeWithTag("dashboard:cards").fetchSemanticsNode().boundsInRoot
        // R4 overlaid Box: scrollable content container starts at root top (<= 4dp), NOT pushed down 72dp by a Column bar
        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue(
            "OeScaffold must overlay header over scroll-under Box (dashboard:cards top=${cardsBounds.top}px > $maxTopPx)",
            cardsBounds.top <= maxTopPx,
        )
    }

    @Test
    fun t1_r4_02_oeScaffoldScrollUnderDoesNotRenderOpaque72DpBarSeam_m2() {
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        OeScaffold(title = "滚动融合") {
                            LazyColumn(Modifier.fillMaxSize().testTag("r4:scroll-list")) {
                                items(30) { idx ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(64.dp)
                                            .background(if (idx % 2 == 0) Color(0xFF00AAFF) else Color(0xFF22DD88)),
                                    ) {
                                        Text("Row #$idx")
                                    }
                                }
                            }
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()
        val listBounds = compose.onNodeWithTag("r4:scroll-list").fetchSemanticsNode().boundsInRoot
        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue(
            "Scrollable list must start at top under overlaid header (top=${listBounds.top}px)",
            listBounds.top <= maxTopPx,
        )

        compose.onNodeWithTag("r4:scroll-list").performTouchInput { swipeUp() }
        compose.waitForIdle()

        // Verify top 72dp region is NOT an opaque flat colors.surface rectangle blocking scrolled content
        val img = AcceptanceTestSupport.captureRootBitmap(compose, "t1_r4_02")
        val barSurfaceColor = Color(Oe3Colors.resolve(ThemeCatalog.constructivistWorkstation).surface)
        val topBandSample = AcceptanceTestSupport.sampleAverageColor(img, 200..300, 12..30)
        val dist = AcceptanceTestSupport.colorDistance(topBandSample, barSurfaceColor)
        assertTrue(
            "Header must blend/scroll-under rather than rendering an opaque solid surface bar (dist=$dist)",
            dist >= 8.0,
        )
    }

    @Test
    fun t1_r4_03_oeScaffoldActionSlotsEnforceMaxThreeDirectAndOverflow_baseline() {
        assertEquals(3, OeScaffoldSpec.directCount(3))
        assertEquals(0, OeScaffoldSpec.overflowCount(3))
        assertEquals(2, OeScaffoldSpec.directCount(4))
        assertEquals(2, OeScaffoldSpec.overflowCount(4))

        var fourthClicked = 0
        val actions = (1..4).map { idx ->
            OeScaffoldAction(
                icon = Oe3Icons.Edit,
                contentDescription = "动作 $idx",
                onClick = { if (idx == 4) fourthClicked++ },
                testTag = "action:$idx",
            )
        }
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                OeScaffold(title = "标题", actions = actions) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("action:1").assertExists()
        compose.onNodeWithTag("action:2").assertExists()
        compose.onNodeWithTag("action:3").assertDoesNotExist()
        compose.onNodeWithTag("scaffold:overflow").assertExists()

        val density = compose.density
        AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(compose.onNodeWithTag("action:1"), density, "action:1")
        AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(compose.onNodeWithTag("scaffold:overflow"), density, "overflow")

        compose.onNodeWithTag("scaffold:overflow").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("action:4").performClick()
        compose.waitForIdle()
        assertEquals(1, fourthClicked)
    }

    @Test
    fun t1_r4_04_oeScaffoldHeaderRespectsStatusBarsPaddingAndPageInset_m2() {
        compose.setContent {
            Oe3Theme(
                theme = ThemeCatalog.constructivistWorkstation,
                textScale = 1.3f,
                reduceMotion = true,
            ) {
                OeScaffold(
                    title = "功能页标题",
                    actions = listOf(
                        OeScaffoldAction(Oe3Icons.Edit, "编辑", {}, testTag = "r4:act1"),
                        OeScaffoldAction(Oe3Icons.Check, "保存", {}, testTag = "r4:act2"),
                    ),
                ) {
                    Box(Modifier.fillMaxSize().testTag("r4:content"))
                }
            }
        }
        compose.waitForIdle()

        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val titleBounds = compose.onNodeWithText("功能页标题").fetchSemanticsNode().boundsInRoot
        val act1Bounds = compose.onNodeWithTag("r4:act1").fetchSemanticsNode().boundsInRoot
        val act2Bounds = compose.onNodeWithTag("r4:act2").fetchSemanticsNode().boundsInRoot
        val contentBounds = compose.onNodeWithTag("r4:content").fetchSemanticsNode().boundsInRoot

        AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(compose.onNodeWithTag("r4:act1"), compose.density, "r4:act1")
        AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(compose.onNodeWithTag("r4:act2"), compose.density, "r4:act2")

        assertTrue("Title right (${titleBounds.right}) must not overlap act1 left (${act1Bounds.left})", titleBounds.right <= act1Bounds.left + 1f)
        val minInsetPx = with(compose.density) { Oe3Spacing.pageInset.toPx() } - 2f
        assertTrue("act2 must respect right pageInset (${rootBounds.right - act2Bounds.right}px >= $minInsetPx)", rootBounds.right - act2Bounds.right >= minInsetPx)
        // Overlaid Box contract: content fills from root top underneath header
        assertTrue("Content must overlay under header (top=${contentBounds.top})", contentBounds.top <= 4f)
    }

    @Test
    fun t1_r4_05_allFiveFeatureTabsRenderSingleFunctionalTitleViaOeScaffold_m2() {
        val controller = Oe3ShellController(Oe3Tab.Workstation)
        compose.setContent {
            Oe3App(
                selectedTab = controller.selectedTab,
                onTabSelected = controller::select,
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = AcceptanceTestSupport.samplePages(),
            )
        }
        compose.waitForIdle()

        val expectedContentTags = mapOf(
            Oe3Tab.Workstation to "dashboard:cards",
            Oe3Tab.Instances to "instances-list",
            Oe3Tab.Hardware to "hardware:screen",
            Oe3Tab.Distros to "distros-grid",
            Oe3Tab.Config to "config:screen",
        )
        val maxTopPx = with(compose.density) { 4.dp.toPx() }

        for (tab in Oe3Tab.entries) {
            compose.runOnUiThread { controller.select(tab) }
            compose.waitForIdle()
            compose.onAllNodesWithText("Orchestella").assertCountEquals(0)
            val contentTag = expectedContentTags.getValue(tab)
            val top = compose.onNodeWithTag(contentTag).fetchSemanticsNode().boundsInRoot.top
            assertTrue(
                "${tab.key} ($contentTag) must render in M2 scroll-under Box (top=$top > $maxTopPx)",
                top <= maxTopPx,
            )
        }
    }

    // =========================================================================
    // R5: 4-Tier Adaptive Liquid Glass & HyperOS/iOS/FlClash Motion (5 tests)
    // =========================================================================

    @Test
    fun t1_r5_01_glassTierResolutionCoversL2L1L0SolidMatrix_m3() {
        // Off -> None -> Solid
        assertEquals(Oe3GlassLevel.None, Oe3GlassStrategy.resolveLevel(GlassMode.Off, "ultra"))
        assertEquals(
            GlassTier.Solid,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.None, true, 35, false, GlassDeviceCap.Unlimited),
        )

        // API < 31 -> L0
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 26, false, GlassDeviceCap.Unlimited),
        )
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Ultra, true, 30, false, GlassDeviceCap.Unlimited),
        )

        // API 31..32 -> L1
        assertEquals(
            GlassTier.L1,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 31, false, GlassDeviceCap.Unlimited),
        )
        assertEquals(
            GlassTier.L1,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 32, false, GlassDeviceCap.Unlimited),
        )

        // Hard gates -> L0
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, false, 35, false, GlassDeviceCap.Unlimited),
        )
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 35, true, GlassDeviceCap.Unlimited),
        )
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 35, false, GlassDeviceCap.L0),
        )

        // M3 Target: API 33+ with gates clean resolves to L2
        assertEquals(
            "M3 requires API 33+ capable devices to resolve to GlassTier.L2",
            GlassTier.L2,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 33, false, GlassDeviceCap.Unlimited),
        )
        assertEquals(
            GlassTier.L2,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Ultra, true, 35, false, GlassDeviceCap.Unlimited),
        )
    }

    @Test
    fun t1_r5_02_glassFrameGateDemotesOnP90OrDroppedFrameThreshold_baseline() {
        // Healthy 60 frames @ 8ms (< 16.67 * 0.7 = 11.669)
        var verdictHealthy = false
        var downgradedHealthy = true
        val healthyGate = Oe3GlassFrameGate(budgetMs = 16.67) { v ->
            verdictHealthy = true
            downgradedHealthy = v.downgrade
        }
        repeat(60) { healthyGate.onFrame(8.0) }
        assertTrue(verdictHealthy)
        assertFalse(downgradedHealthy)

        // High P90: 60 frames @ 12.0ms (> 11.669)
        var downgradedP90 = false
        val p90Gate = Oe3GlassFrameGate(budgetMs = 16.67) { v -> downgradedP90 = v.downgrade }
        repeat(60) { p90Gate.onFrame(12.0) }
        assertTrue(downgradedP90)

        // High drop rate: 2 dropped (20ms > 16.67) out of 60 = 3.33% > 2%
        var downgradedDrop = false
        val dropGate = Oe3GlassFrameGate(budgetMs = 16.67) { v -> downgradedDrop = v.downgrade }
        repeat(58) { dropGate.onFrame(6.0) }
        repeat(2) { dropGate.onFrame(20.0) }
        assertTrue(downgradedDrop)
    }

    @Test
    fun t1_r5_03_glassMaxOneChromePerScreenAndSurfaceViewIsolation_baseline() {
        val uiRoot = AcceptanceTestSupport.uiSourceRoot()
        // Only Oe3NavBar / Oe3GlassSurface should use hazeChild / Oe3GlassSurface; cards/rows/controls must not
        listOf(
            "oe3/components/Oe3Surfaces.kt",
            "oe3/components/OeListGroup.kt",
            "oe3/components/Oe3Controls.kt",
            "oe3/components/Oe3Rows.kt",
        ).forEach { rel ->
            val txt = File(uiRoot, rel).readText()
            assertFalse("$rel must not import Haze", txt.contains("dev.chrisbanes.haze"))
            assertFalse("$rel must not invoke Oe3GlassSurface", txt.contains("Oe3GlassSurface("))
        }

        // DesktopActivity + WaylandSurfaceView isolation
        val desktopJava = File(AcceptanceTestSupport.appMainDir(), "java/io/github/nyzeep/orchestella/DesktopActivity.java").readText()
        assertTrue(desktopJava.contains("WaylandSurfaceView"))
        assertFalse("DesktopActivity must never apply Oe3GlassSurface", desktopJava.contains("Oe3GlassSurface"))
        assertFalse("DesktopActivity must never import haze", desktopJava.contains("dev.chrisbanes.haze"))

        val applierKt = File(uiRoot, "theme/ThemeApplier.kt").readText()
        assertTrue(applierKt.contains("SurfaceView"))
        assertTrue(applierKt.contains("DesktopActivity"))
    }

    @Test
    fun t1_r5_04_predictiveBackManifestAndBackStackGestureContract_m3() {
        val manifest = File(AcceptanceTestSupport.appMainDir(), "AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest.xml must declare android:enableOnBackInvokedCallback=\"true\" for M3 Predictive Back",
            manifest.contains("android:enableOnBackInvokedCallback=\"true\""),
        )

        val backStackSource = File(AcceptanceTestSupport.uiSourceRoot(), "shell/OeBackStack.kt").readText()
        assertTrue(
            "OeBackStack.kt must wire PredictiveBackHandler for M3 gesture-driven back",
            Regex("""PredictiveBackHandler\s*[({]""").containsMatchIn(backStackSource),
        )

        val backStack = OeBackStack()
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = false,
                backStack = backStack,
                pages = Oe3Pages(
                    workstation = { Box(Modifier.fillMaxSize().testTag("pb:base")) },
                ),
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            backStack.push(OeDestination("pb-dest", "预测返回") { Box(Modifier.fillMaxSize().testTag("pb:dest")) })
        }
        compose.waitForIdle()

        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 0f, 0.45f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        val midBounds = compose.onNodeWithTag("pb:dest").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Predictive back progress (0.45) should visually offset/scale top destination (left=${midBounds.left})",
            midBounds.left > 0f,
        )

        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(backStack.canPop)
        compose.onNodeWithTag("pb:dest").assertDoesNotExist()
        compose.onNodeWithTag("pb:base").assertIsDisplayed()
    }

    @Test
    fun t1_r5_05_unifiedMotionTokensAndReduceMotionZeroMsSnap_baseline() {
        val std = Oe3Motion.standard()
        val red = Oe3Motion.reduced()
        assertTrue(std.pagePush.durationMillis > 0)
        assertNotNull(std.navSelectionSpring)
        assertNotNull(std.pagePushSpring)

        assertEquals(0, red.navSelection.durationMillis)
        assertEquals(0, red.press.durationMillis)
        assertEquals(0, red.pageFade.durationMillis)
        assertEquals(0, red.pageSlide.durationMillis)
        assertEquals(0, red.cardEnter.durationMillis)
        assertEquals(0, red.dialog.durationMillis)
        assertEquals(0, red.themeTween.durationMillis)
        assertEquals(0, red.pagePush.durationMillis)
        assertEquals(0, red.editWiggle.durationMillis)
        assertEquals(1f, red.cardEnterScaleFrom, 0.0001f)
        assertNull(red.navSelectionSpring)
        assertNull(red.pagePushSpring)

        val controller = Oe3ShellController(Oe3Tab.Workstation)
        val backStack = OeBackStack()
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                Oe3App(
                    selectedTab = controller.selectedTab,
                    onTabSelected = controller::select,
                    theme = ThemeCatalog.constructivistWorkstation,
                    reduceMotion = true,
                    backStack = backStack,
                    pages = AcceptanceTestSupport.samplePages(),
                )
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(Oe3Tab.Distros.label).performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("distros-grid").assertIsDisplayed()

            compose.runOnUiThread {
                backStack.push(OeDestination("snap", "Snap") { Box(Modifier.fillMaxSize().testTag("snap:dest")) })
            }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("snap:dest").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
