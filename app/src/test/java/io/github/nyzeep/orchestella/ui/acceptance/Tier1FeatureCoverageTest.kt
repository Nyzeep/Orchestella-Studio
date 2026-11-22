package io.github.nyzeep.orchestella.ui.acceptance

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.ViewGroup
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ConnectionPagesActivity
import io.github.nyzeep.orchestella.MainActivity
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.components.OeDialogAction
import io.github.nyzeep.orchestella.ui.design.components.OeDialogHost
import io.github.nyzeep.orchestella.ui.design.components.OeDialogSpec
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
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
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
import io.github.nyzeep.orchestella.ui.screens.theme.NavStyleOption
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeOptionRow
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioController
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioScreen
import io.github.nyzeep.orchestella.ui.screens.theme.rememberThemeStudioHandle
import io.github.nyzeep.orchestella.ui.screens.wizard.HardwareBadge
import io.github.nyzeep.orchestella.ui.screens.wizard.StorageGate
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardController
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardScreen
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardUiState
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import org.w3c.dom.Element

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
        // W1-02：规格仓检出前置（解析链与口径见 AcceptanceTestSupport.specsToolRoot）。
        AcceptanceTestSupport.assumeSpecsToolAvailable()
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
            // HCT tone is CIE L*. The 15 → 21 → 27 reference allows two units
            // of variation per step to accommodate a preset's own palette.
            val step1 = AcceptanceTestSupport.perceptualTone(c.surfaceRaised) -
                AcceptanceTestSupport.perceptualTone(c.surface)
            val step2 = AcceptanceTestSupport.perceptualTone(c.surfaceHigh) -
                AcceptanceTestSupport.perceptualTone(c.surfaceRaised)
            assertTrue(
                "${theme.id}: surface → raised needs at least 4 L* of separation, measured $step1",
                step1 >= 4.0,
            )
            assertTrue(
                "${theme.id}: raised → high needs at least 4 L* of separation, measured $step2",
                step2 >= 4.0,
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
            Triple(ThemeCatalog.mascotLight, false, Oe3NavStyle.Fixed),
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
            compose.runOnUiThread { activeIndex = idx }
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

            val img = AcceptanceTestSupport.captureRootBitmap(compose)
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
        var radius by mutableIntStateOf(0)
        val theme = ThemeCatalog.constructivistWorkstation
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = theme,
                cornerRadiusDp = radius,
                surfaceAlpha = 0.55f,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        Box(Modifier.fillMaxSize().background(Color.Magenta)) {
                            Column(Modifier.fillMaxSize().padding(Oe3Spacing.pageInset)) {
                                Oe3Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                        .testTag("probe:alpha-card"),
                                ) {
                                }
                                Spacer(Modifier.height(Oe3Spacing.gridGap))
                                OeListGroup(Modifier.fillMaxWidth().testTag("probe:group")) {
                                    Spacer(Modifier.height(80.dp))
                                }
                                Spacer(Modifier.height(Oe3Spacing.gridGap))
                                Oe3GridCard(Modifier.fillMaxWidth().height(80.dp).testTag("probe:grid")) {}
                            }
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()
        val mixed = Color(Oe3Colors.resolve(theme).surface).copy(alpha = 0.55f).compositeOver(Color.Magenta)
        val squareFrame = AcceptanceTestSupport.captureRootBitmap(compose)
        listOf("probe:alpha-card", "probe:group").forEach { tag ->
            val center = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
            val sampled = Color(squareFrame.getPixel(center.x.roundToInt(), center.y.roundToInt()))
            assertTrue("$tag must mix the 55% surface with the backdrop, observed $sampled",
                AcceptanceTestSupport.colorDistance(sampled, mixed) <= 4.0)
        }

        val gridBounds = compose.onNodeWithTag("probe:grid").fetchSemanticsNode().boundsInRoot
        val cornerProbe = with(compose.density) { 2.dp.roundToPx() }
        val cornerX = gridBounds.left.roundToInt() + cornerProbe
        val cornerY = gridBounds.top.roundToInt() + cornerProbe
        val squareCorner = Color(squareFrame.getPixel(cornerX, cornerY))
        assertTrue("A grid card with a custom 0dp radius must fill its square corner",
            AcceptanceTestSupport.colorDistance(squareCorner, mixed) <= 4.0)

        compose.runOnUiThread { radius = 24 }
        compose.waitForIdle()
        val roundFrame = AcceptanceTestSupport.captureRootBitmap(compose)
        val roundCorner = Color(roundFrame.getPixel(cornerX, cornerY))
        assertTrue("A grid card with a custom 24dp radius must expose the backdrop at its corner",
            AcceptanceTestSupport.colorDistance(roundCorner, Color.Magenta) <= 4.0)
    }

    @Test
    fun t1_r2_05_typographyRolesAndWcagContrastPassAcrossAll41Presets_baseline() {
        val shippedRoles = setOf("Brand", "PageTitle", "Section", "Body", "BodyStrong", "Caption", "NavLabel", "Mono")
        assertTrue("Existing typography roles must remain available",
            Oe3TextRole.entries.map { it.name }.containsAll(shippedRoles))
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

        // W1-02：规格仓检出前置（解析链与口径见 AcceptanceTestSupport.specsToolRoot）。
        AcceptanceTestSupport.assumeSpecsToolAvailable()
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

        val main = Robolectric.buildActivity(MainActivity::class.java).create()
        val connections = Robolectric.buildActivity(
            ConnectionPagesActivity::class.java,
            ConnectionPagesActivity.sshIntent(app),
        ).create()
        try {
            listOf(main.get(), connections.get()).forEach { activity: Activity ->
                val name = activity.javaClass.simpleName
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                val composeRoot = checkNotNull(content.getChildAt(0))
                val initialBackground = composeRoot.background
                assertEquals("$name starts with a transparent status bar", 0, activity.window.statusBarColor)
                assertEquals("$name starts with a transparent navigation bar", 0, activity.window.navigationBarColor)

                ThemeApplier.bind(activity)
                store.setThemeId(ThemeCatalog.mascotLight.id)
                store.setMode(ThemeStore.Mode.LIGHT)
                Shadows.shadowOf(Looper.getMainLooper()).idle()

                assertEquals("$name keeps its status bar transparent", 0, activity.window.statusBarColor)
                assertEquals("$name keeps its navigation bar transparent", 0, activity.window.navigationBarColor)
                assertEquals("$name keeps its original root background", initialBackground, composeRoot.background)
            }
        } finally {
            connections.destroy()
            main.destroy()
        }
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

        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val tolerancePx = with(compose.density) { 2.dp.toPx() }

        fun pushAndCheckFrameZero(entering: String, previous: String) {
            compose.mainClock.autoAdvance = false
            try {
                compose.runOnUiThread {
                    Snapshot.withMutableSnapshot {
                        backStack.push(OeDestination(entering, entering) {
                            Box(Modifier.fillMaxSize().testTag(entering))
                        })
                    }
                }
                compose.mainClock.advanceTimeByFrame()
                val left = compose.onNodeWithTag(entering).fetchSemanticsNode().boundsInRoot.left
                assertTrue("$entering must start offscreen at the right edge, left=$left",
                    left >= rootBounds.right - tolerancePx)
                compose.onNodeWithTag(previous).assertExists()
            } finally {
                compose.mainClock.autoAdvance = true
            }
            compose.waitForIdle()
            compose.onNodeWithTag(entering).assertIsDisplayed()
            compose.onNodeWithTag(previous).assertDoesNotExist()
        }

        pushAndCheckFrameZero(entering = "dest-a", previous = "pager:base")
        pushAndCheckFrameZero(entering = "dest-b", previous = "dest-a")

        // Pop B (2 -> 1): mid-flight must reveal A (under layer), never base pager bleed-through
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread {
                Snapshot.withMutableSnapshot { backStack.pop() }
            }
            compose.mainClock.advanceTimeByFrame()
            val leaving = compose.onNodeWithTag("dest-b")
            val initialLeft = leaving.fetchSemanticsNode().boundsInRoot.left
            assertEquals("Leaving destination starts in place at the first pop frame", rootBounds.left, initialLeft, tolerancePx)
            compose.mainClock.advanceTimeBy(64)
            val midLeft = leaving.fetchSemanticsNode().boundsInRoot.left
            assertTrue("Leaving destination must remain on screen and move during pop, left=$midLeft",
                midLeft > initialLeft + tolerancePx && midLeft < rootBounds.right - tolerancePx)
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
                reduceMotion = false,
                backStack = backStack,
                pages = AcceptanceTestSupport.samplePages(),
            )
        }
        compose.waitForIdle()

        fun push(key: String, label: String) {
            compose.runOnUiThread {
                Snapshot.withMutableSnapshot {
                    backStack.push(OeDestination(key, label) { Box(Modifier.fillMaxSize().testTag("stack:$key")) })
                }
            }
        }
        push("lvl1", "一级")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            push("lvl2", "二级")
            compose.mainClock.advanceTimeBy(64)
            assertEquals(2, backStack.size)
            compose.onNodeWithTag("stack:lvl1").assertExists()
            compose.onNodeWithTag("stack:lvl2").assertIsDisplayed()

            compose.onNodeWithText(Oe3Tab.Instances.label).performClick()
            compose.mainClock.advanceTimeByFrame()

            assertEquals(0, backStack.size)
            assertFalse(backStack.canPop)
            compose.onNodeWithTag("stack:lvl1").assertDoesNotExist()
            compose.onNodeWithTag("stack:lvl2").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
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
        val committedTheme = ThemeCatalog.mascotLight
        val draftTheme = ThemeCatalog.catppuccin

        compose.setContent {
            Oe3Theme(theme = committedTheme, reduceMotion = true) {
                HomePreview(
                    theme = draftTheme,
                    pureBlack = false,
                    cornerDp = 24,
                    surfaceOpacityPercent = 70,
                    fontFamily = FontFamily.Monospace,
                    reduceMotion = true,
                )
            }
        }
        compose.waitForIdle()
        val rendered = homePreviewBackground()
        val draft = Color(Oe3Colors.resolve(draftTheme).background)
        val committed = Color(Oe3Colors.resolve(committedTheme).background)
        assertTrue("Preview must show the draft background, observed $rendered",
            AcceptanceTestSupport.colorDistance(rendered, draft) <= 3.0)
        assertTrue("Preview must differ from the committed shell background",
            AcceptanceTestSupport.colorDistance(rendered, committed) > 20.0)
        assertEquals("content://committed/wallpaper.png", store.backgroundUri())
        assertEquals(committedTheme.id, controller.read().theme.id)
    }

    @Test
    fun t1_r3_07_homePreviewAnimatesDraftChangeAndRetiresOutgoingDock_baseline() {
        var darkDraft by mutableStateOf(false)
        val light = ThemeCatalog.mascotLight
        val dark = ThemeCatalog.catppuccin
        compose.setContent {
            Oe3Theme(theme = light, reduceMotion = false) {
                HomePreview(
                    theme = if (darkDraft) dark else light,
                    pureBlack = false,
                    cornerDp = 24,
                    navStyle = if (darkDraft) NavStyleOption.Fixed else NavStyleOption.Floating,
                )
            }
        }
        val start = homePreviewBackground()
        val target = Color(Oe3Colors.resolve(dark).background)
        compose.onNodeWithTag("theme-preview:nav-floating").assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        try {
            compose.runOnUiThread { Snapshot.withMutableSnapshot { darkDraft = true } }
            compose.mainClock.advanceTimeBy(120)
            val intermediate = homePreviewBackground()
            assertTrue("Animated preset switch must progress beyond the starting palette",
                AcceptanceTestSupport.colorDistance(start, intermediate) > 8.0)
            assertTrue("Animated preset switch must leave an intermediate palette before settling",
                AcceptanceTestSupport.colorDistance(target, intermediate) > 8.0)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        val end = homePreviewBackground()
        assertTrue("Preview must settle on the last selected palette",
            AcceptanceTestSupport.colorDistance(target, end) <= 3.0)
        compose.onNodeWithTag("theme-preview:nav-fixed").assertIsDisplayed()
        compose.onNodeWithTag("theme-preview:nav-floating").assertDoesNotExist()
    }

    private fun homePreviewBackground(): Color {
        val preview = compose.onNodeWithTag("theme-preview:home")
        preview.assertIsDisplayed()
        val bounds = preview.fetchSemanticsNode().boundsInRoot
        val offset = with(compose.density) { 12.dp.roundToPx() }
        val img = AcceptanceTestSupport.captureRootBitmap(compose)
        return Color(img.getPixel((bounds.left + bounds.width * 0.75f).roundToInt(), bounds.top.roundToInt() + offset))
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
        compose.onAllNodesWithText(Oe3Tab.Workstation.label).assertCountEquals(2)
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
                                items(30) {
                                    Row(Modifier.fillMaxWidth().height(64.dp)) {
                                        Spacer(Modifier.weight(1f).fillMaxSize().background(Color(0xFF00AAFF)))
                                        Spacer(Modifier.weight(1f).fillMaxSize().background(Color(0xFF22DD88)))
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

        // The two colours must still contribute to the same header row. A
        // gradient or blur is allowed, including an opaque edge at the very top.
        val img = AcceptanceTestSupport.captureRootBitmap(compose)
        val title = compose.onNodeWithText("滚动融合").fetchSemanticsNode().boundsInRoot
        val leftX = (listBounds.left + listBounds.width * 0.42f).roundToInt()
        val rightX = (listBounds.left + listBounds.width * 0.8f).roundToInt()
        val rowDeltas = listOf(0.25f, 0.50f, 0.85f).map { position ->
            val y = (title.top + title.height * position).roundToInt()
            AcceptanceTestSupport.colorDistance(Color(img.getPixel(leftX, y)), Color(img.getPixel(rightX, y)))
        }
        assertTrue(
            "Content beneath the title should contribute to header pixels after scrolling, deltas=$rowDeltas",
            rowDeltas.count { it >= 8.0 } >= 2,
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
        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue("Content must overlay under header (top=${contentBounds.top})", contentBounds.top <= maxTopPx)
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

        val viewportTags = mapOf(
            Oe3Tab.Workstation to "dashboard:cards",
            Oe3Tab.Instances to "instances-list",
            Oe3Tab.Distros to "distros-grid",
            Oe3Tab.Config to "config:screen",
        )
        val context = compose.activity
        val dock = hasAnyAncestor(hasTestTag("shell:nav-bar"))
        val dashboard = AcceptanceTestSupport.sampleDashboardState()
        val firstItems = mapOf(
            Oe3Tab.Workstation to hasText(
                context.getString(R.string.ui3_dashboard_meta_summary, dashboard.instanceCount, dashboard.runningCount),
            ),
            Oe3Tab.Instances to hasTestTag("instances-create"),
            Oe3Tab.Hardware to hasTestTag("hardware:summary"),
            Oe3Tab.Distros to hasText(context.getString(R.string.ui3_distros_meta_line)),
            Oe3Tab.Config to hasText(context.getString(R.string.config_page_meta)),
        )
        val maxTopPx = with(compose.density) { 4.dp.toPx() }

        for (tab in Oe3Tab.entries) {
            compose.runOnUiThread { controller.select(tab) }
            compose.waitForIdle()
            compose.onAllNodesWithText("Orchestella").assertCountEquals(0)
            compose.onAllNodesWithText(tab.label, useUnmergedTree = true).assertCountEquals(2)
            val title = compose.onNode(hasText(tab.label) and !dock, useUnmergedTree = true)
            title.assertIsDisplayed()
            val titleBottom = title.fetchSemanticsNode().boundsInRoot.bottom
            val firstItem = compose.onNode(firstItems.getValue(tab), useUnmergedTree = true)
            firstItem.assertIsDisplayed()
            val itemTop = firstItem.fetchSemanticsNode().boundsInRoot.top
            assertTrue("${tab.key} first content item at $itemTop overlaps title ending at $titleBottom", itemTop >= titleBottom)

            viewportTags[tab]?.let { tag ->
                val top = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
                assertTrue("${tab.key} scroll viewport should begin under its title, top=$top", top <= maxTopPx)
            }
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
        // W2-05 R-03 重标定（预算改 DEADLINE 实测口径）后的阈值基线：
        // P90 降档线 = 预算×1.25 = 20.83ms；真滞留线 = 预算×1.5 = 25ms；
        // 前 6 帧暖帧计入采样窗但剥离出裁决子集（以下剖面全部落在单一通道上）。
        // Healthy: 60 帧 8ms——剥离暖帧后稳态 P90 8ms < 20.83，真滞留 0。
        var verdictHealthy = false
        var downgradedHealthy = true
        val healthyGate = Oe3GlassFrameGate(budgetMs = 16.67) { v ->
            verdictHealthy = true
            downgradedHealthy = v.downgrade
        }
        repeat(60) { healthyGate.onFrame(8.0) }
        assertTrue(verdictHealthy)
        assertFalse(downgradedHealthy)

        // High P90: 60 帧 22ms——(1.25, 1.5]×预算 的持续超限（> 20.83 且 < 25），
        // P90 通道单独触发降档，真滞留 0。
        var downgradedP90 = false
        val p90Gate = Oe3GlassFrameGate(budgetMs = 16.67) { v -> downgradedP90 = v.downgrade }
        repeat(60) { p90Gate.onFrame(22.0) }
        assertTrue(downgradedP90)

        // High drop rate: 58 帧 6ms + 2 帧 30ms——30 > 25 记真滞留，
        // 2/54 = 3.7% > 2%，P90 良好（6ms）时真滞留通道独立触发降档。
        var downgradedDrop = false
        val dropGate = Oe3GlassFrameGate(budgetMs = 16.67) { v -> downgradedDrop = v.downgrade }
        repeat(58) { dropGate.onFrame(6.0) }
        repeat(2) { dropGate.onFrame(30.0) }
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
        val topPage = compose.onNodeWithTag("pb:dest")
        val settledBounds = topPage.fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(20f, 0f, 0.20f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        val earlyBounds = topPage.fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 0f, 0.45f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        val midBounds = topPage.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Top destination must follow both observed predictive back progress events",
            earlyBounds.left > settledBounds.left && midBounds.left > earlyBounds.left,
        )
        val underneath = compose.onNodeWithTag("pb:base")
        val underMidBounds = underneath.fetchSemanticsNode().boundsInRoot

        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(backStack.canPop)
        compose.onNodeWithTag("pb:dest").assertDoesNotExist()
        underneath.assertIsDisplayed()
        val underRestBounds = underneath.fetchSemanticsNode().boundsInRoot
        assertTrue("Underlying page must show parallax while the gesture is active",
            underMidBounds.width < underRestBounds.width || underMidBounds.left != underRestBounds.left)

        compose.runOnIdle { backStack.push(OeDestination("first", "First") {}) }
        compose.waitForIdle()
        compose.runOnIdle {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        }
        compose.runOnIdle {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 0f, 0.45f, BackEventCompat.EDGE_LEFT))
        }
        compose.runOnIdle { backStack.push(OeDestination("new-top", "New top") {}) }
        compose.waitForIdle()
        compose.runOnIdle { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("A gesture from an earlier entry must leave the new destination open", "new-top", backStack.top?.key)
        assertEquals(2, backStack.size)

        val manifestFile = File(AcceptanceTestSupport.appMainDir(), "AndroidManifest.xml")
        val manifestDoc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifestFile)
        val applicationElement = manifestDoc.getElementsByTagName("application").item(0) as Element
        assertEquals(
            "Manifest must opt into system predictive back",
            "true",
            applicationElement.getAttributeNS("http://schemas.android.com/apk/res/android", "enableOnBackInvokedCallback"),
        )
    }

    @Test
    fun t1_r5_06_api33GlassUsesRuntimeShaderAndHalfSizeHazeInput_m3() {
        assertEquals(
            GlassTier.L2,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 33, false, GlassDeviceCap.Unlimited),
        )

        // Host screenshots cannot certify GPU AGSL refraction. Guard the two
        // stable Android/Haze integration APIs across ui/ without file/regex coupling.
        val uiSources = AcceptanceTestSupport.uiSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map(File::readText)
            .toList()
        assertTrue(
            "L2 must use Android RuntimeShader for AGSL rim refraction",
            uiSources.any { "RuntimeShader" in it },
        )
        assertTrue(
            "The Haze backdrop must use HazeInputScale.Fixed for downsampled blur input",
            uiSources.any { "HazeInputScale.Fixed" in it },
        )
    }

    @Test
    @OptIn(ExperimentalMaterial3Api::class)
    fun t1_r5_07_oe3ButtonAndChipElasticPressFeedback_m3() {
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = false) {
                    // Sample the Compose spring without the separately timed native ripple.
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(Color.Magenta)
                                .padding(16.dp),
                        ) {
                            Oe3Button(
                                text = "主要动作",
                                onClick = {},
                                style = Oe3ButtonStyle.Pill,
                                modifier = Modifier.testTag("m3:btn"),
                            )
                            Spacer(Modifier.height(12.dp))
                            Oe3Chip(
                                label = "筛选标签",
                                onClick = {},
                                modifier = Modifier.testTag("m3:chip"),
                            )
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeByFrame()

            fun diffPixelStats(
                before: android.graphics.Bitmap,
                after: android.graphics.Bitmap,
                bounds: androidx.compose.ui.geometry.Rect,
            ): Pair<Int, Double> {
                val x0 = (bounds.left.toInt() - 2).coerceIn(0, before.width - 1)
                val x1 = (bounds.right.toInt() + 2).coerceIn(0, before.width - 1)
                val y0 = (bounds.top.toInt() - 2).coerceIn(0, before.height - 1)
                val y1 = (bounds.bottom.toInt() + 2).coerceIn(0, before.height - 1)
                var changed = 0
                var maxDelta = 0.0
                for (y in y0..y1) {
                    for (x in x0..x1) {
                        val d = AcceptanceTestSupport.colorDistance(
                            Color(before.getPixel(x, y)),
                            Color(after.getPixel(x, y)),
                        )
                        if (d > maxDelta) maxDelta = d
                        if (d >= 10.0) changed++
                    }
                }
                return changed to maxDelta
            }

            listOf("m3:btn", "m3:chip").forEach { tag ->
                val node = compose.onNodeWithTag(tag)
                val bounds = node.fetchSemanticsNode().boundsInRoot
                val restBitmap = AcceptanceTestSupport.captureRootBitmap(compose)

                node.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(80)
                val pressedBitmap = AcceptanceTestSupport.captureRootBitmap(compose)
                val (changedOnPress, maxPressDelta) = diffPixelStats(restBitmap, pressedBitmap, bounds)
                assertTrue(
                    "$tag must exhibit visible elastic scale at +80ms (changedPixels=$changedOnPress, maxDelta=$maxPressDelta, bounds=$bounds)",
                    changedOnPress >= 24 && maxPressDelta >= 15.0,
                )

                node.performTouchInput { up() }
                AcceptanceTestSupport.advanceComposeFrames(compose, frames = 60)
                val releasedBitmap = AcceptanceTestSupport.captureRootBitmap(compose)
                val (residualDiffs, maxResidualDelta) = diffPixelStats(restBitmap, releasedBitmap, bounds)
                assertTrue(
                    "$tag must settle cleanly back to rest appearance after release (residualPixels=$residualDiffs, maxResidualDelta=$maxResidualDelta)",
                    residualDiffs <= 4 && maxResidualDelta <= 10.0,
                )
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun t1_r5_08_oeDialogHostSpringEntranceMotion_m3() {
        val host = OeDialogHost.attach(compose.activity)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                Box(Modifier.fillMaxSize().background(Color.Magenta))
            }
            compose.mainClock.advanceTimeByFrame()

            fun measureDialogWidthPx(bitmap: android.graphics.Bitmap): Int {
                val midY = bitmap.height / 2
                val bgColor = Color(bitmap.getPixel(4, midY))
                var first = -1
                var last = -1
                for (x in 0 until bitmap.width) {
                    if (AcceptanceTestSupport.colorDistance(bgColor, Color(bitmap.getPixel(x, midY))) > 8.0) {
                        if (first < 0) first = x
                        last = x
                    }
                }
                return if (first < 0) 0 else (last - first + 1)
            }

            host.show(
                OeDialogSpec(
                    title = "弹簧弹窗",
                    message = "验证 F13 对话框缩放弹簧入场",
                    positive = OeDialogAction("确定"),
                    testTag = "m3:dialog",
                ),
            )
            // Capture initial mount frame so the overlay ComposeView attaches and renders Frame 0
            // before stepping the Compose frame clock.
            AcceptanceTestSupport.captureWindowContentBitmap(compose)
            var earlyWidth = 0
            for (frame in 1..10) {
                compose.mainClock.advanceTimeByFrame()
                val w = measureDialogWidthPx(AcceptanceTestSupport.captureWindowContentBitmap(compose))
                if (w > 0) {
                    earlyWidth = w
                    break
                }
            }

            // Let the entrance spring settle completely
            AcceptanceTestSupport.advanceComposeFrames(compose, frames = 60)
            compose.onNodeWithTag("m3:dialog").assertIsDisplayed()
            val settledBitmap = AcceptanceTestSupport.captureWindowContentBitmap(compose)
            val settledWidth = measureDialogWidthPx(settledBitmap)

            assertTrue("Early dialog frame must be visible (earlyWidth=$earlyWidth)", earlyWidth > 0)
            assertTrue("Settled dialog width must be measurable (settledWidth=$settledWidth)", settledWidth > 100)
            val initialRatio = earlyWidth.toFloat() / settledWidth.toFloat()
            assertTrue(
                "F13 requires OeDialogHost spring entrance starting from <= 0.945f scale (earlyWidth=$earlyWidth, settledWidth=$settledWidth, initialRatio=$initialRatio)",
                initialRatio in 0.80f..0.945f,
            )
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { host.dismiss() }
            compose.waitForIdle()
        }
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
            // W2-03 起页签切换是整页滑移：进场页从行进侧 ±100% 起步（减动效下
            // 0ms 补间），首帧应用重组、次帧落位——再推一帧即瞬落基线。
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("distros-grid").assertIsDisplayed()

            compose.runOnUiThread {
                backStack.push(OeDestination("snap", "Snap") { Box(Modifier.fillMaxSize().testTag("snap:dest")) })
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("snap:dest").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
