package io.github.nyzeep.orchestella.ui.acceptance

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassDeviceCap
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassTier
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassLowRam
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassTierOverride
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassStrategy
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Motion
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesActions
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioController
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioEvent
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioScreen
import io.github.nyzeep.orchestella.ui.screens.theme.rememberThemeStudioHandle
import io.github.nyzeep.orchestella.ui.shell.LocalOeSurfaceAlpha
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3NavStyle
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3ShellController
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * Tier 3 — Pairwise Combinatorial Integration Tests (12 tests across R1–R5).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-mdpi")
class Tier3CrossFeatureCombinationsTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    @Test
    fun t3_01_pureBlackPlusLightPresetPlusWallpaperInThemeStudio_m2() {
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        store.setMode(ThemeStore.Mode.LIGHT)
        store.setThemeId(ThemeCatalog.mascotLight.id)
        store.setUiPureBlack(true)
        store.setBackgroundUri("content://test/bg.png")

        val ctrl = ThemeStudioController(store)
        val lightState = ctrl.read()
        assertFalse("Light preset must suppress pureBlack in UI state", lightState.pureBlack)
        val lightColors = Oe3Colors.resolve(lightState.theme, pureBlack = lightState.pureBlack)
        assertEquals(ThemeCatalog.mascotLight.surface, lightColors.surface)

        val darkState = ctrl.handle(ThemeStudioEvent.SelectTheme(ThemeCatalog.mascotDark.id))
        assertTrue(darkState.pureBlack)
        assertEquals("content://test/bg.png", darkState.backgroundUri)
        val darkColors = Oe3Colors.resolve(darkState.theme, pureBlack = darkState.pureBlack)
        assertEquals(0xFF0B0B0C.toInt(), darkColors.surface)

        // Dock visually distinct from cards in M2 Pure Black
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = darkState.theme,
                pureBlack = darkState.pureBlack,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.weight(1f))
                            Oe3Card(Modifier.fillMaxWidth().height(200.dp)) { Box(Modifier.fillMaxSize()) }
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()
        val navBounds = compose.onNodeWithTag("shell:nav-bar").fetchSemanticsNode().boundsInRoot
        val img = AcceptanceTestSupport.captureRootBitmap(compose)
        val cardSample = AcceptanceTestSupport.sampleAverageColor(
            img,
            (navBounds.left.roundToInt() + 20)..(navBounds.right.roundToInt() - 20),
            (navBounds.top.roundToInt() - 40)..(navBounds.top.roundToInt() - 15),
        )
        val dockSample = AcceptanceTestSupport.sampleAverageColor(
            img,
            (navBounds.right.roundToInt() - 24)..(navBounds.right.roundToInt() - 10),
            (navBounds.top.roundToInt() + 10)..(navBounds.bottom.roundToInt() - 10),
        )
        assertTrue(
            "PureBlack dock vs card separation too low (${AcceptanceTestSupport.colorDistance(cardSample, dockSample)})",
            AcceptanceTestSupport.colorDistance(cardSample, dockSample) >= 4.0,
        )
    }

    @Test
    fun t3_02_floatingAndFixedNavStyleAcrossGlassTiersL0L1L2Solid_m3() {
        assertEquals(
            "M3 requires L2 tier resolution at API 35",
            GlassTier.L2,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 35, false, GlassDeviceCap.Unlimited),
        )

        val combinations = listOf(Oe3NavStyle.Floating, Oe3NavStyle.Fixed).flatMap { style ->
            listOf(GlassTier.Solid, GlassTier.L0, GlassTier.L1, GlassTier.L2).map { tier -> style to tier }
        }
        var activeCombo by mutableIntStateOf(0)

        compose.setContent {
            val (_, tier) = combinations[activeCombo]
            val (style, _) = combinations[activeCombo]
            val level = if (tier == GlassTier.Solid) Oe3GlassLevel.None else Oe3GlassLevel.Standard
            CompositionLocalProvider(LocalOe3GlassTierOverride provides tier) {
                Oe3App(
                    selectedTab = Oe3Tab.Workstation,
                    onTabSelected = {},
                    theme = ThemeCatalog.constructivistWorkstation,
                    navStyle = style,
                    glassLevel = level,
                    reduceMotion = true,
                    pages = Oe3Pages(
                        workstation = {
                            Column(Modifier.fillMaxSize()) {
                                Spacer(Modifier.weight(1f))
                                Oe3Card(Modifier.fillMaxWidth().height(200.dp)) {
                                    Box(Modifier.fillMaxSize())
                                }
                            }
                        },
                    ),
                )
            }
        }

        combinations.forEachIndexed { idx, (style, tier) ->
            activeCombo = idx
            compose.waitForIdle()
            val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val navBounds = compose.onNodeWithTag("shell:nav-bar").fetchSemanticsNode().boundsInRoot
            if (style == Oe3NavStyle.Floating) {
                val expectedGap = with(compose.density) { 21.dp.toPx() }
                assertEquals(expectedGap, rootBounds.bottom - navBounds.bottom, 2.0f)
            } else {
                assertEquals(rootBounds.bottom, navBounds.bottom, 2.0f)
            }

            val img = AcceptanceTestSupport.captureRootBitmap(compose)
            val cardSample = AcceptanceTestSupport.sampleAverageColor(
                img,
                (navBounds.left.roundToInt() + 20)..(navBounds.right.roundToInt() - 20),
                (navBounds.top.roundToInt() - 40)..(navBounds.top.roundToInt() - 15),
            )
            val dockSample = AcceptanceTestSupport.sampleAverageColor(
                img,
                (navBounds.right.roundToInt() - 24)..(navBounds.right.roundToInt() - 10),
                (navBounds.top.roundToInt() + 10)..(navBounds.bottom.roundToInt() - 10),
            )
            val delta = AcceptanceTestSupport.colorDistance(cardSample, dockSample)
            assertTrue("Style=$style Tier=$tier dock-vs-card delta=$delta < 4.0", delta >= 4.0)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w343dp-h800dp-mdpi")
    fun t3_03_narrow343DpPlus130PercentScalePlusScrolledOeScaffold_m2() {
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Instances,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                textScale = 1.3f,
                reduceMotion = true,
                pages = Oe3Pages(
                    instances = {
                        InstancesScreen(
                            state = AcceptanceTestSupport.sampleInstancesState(
                                engineLabel = "QEMU/TCG 兼容回退引擎 (Software)",
                            ),
                            actions = InstancesActions(),
                        )
                    },
                ),
            )
        }
        compose.waitForIdle()
        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue(
            "M2 R4 requires instances-list to scroll under header (top <= 4dp)",
            compose.onNodeWithTag("instances-list").fetchSemanticsNode().boundsInRoot.top <= maxTopPx,
        )

        compose.onNodeWithTag("instances-list").performTouchInput { swipeUp() }
        compose.waitForIdle()

        Oe3Tab.entries.forEach { tab ->
            assertTrue(tab.label.isNotBlank())
            val node = compose.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(tab.label),
            )
            node.assertIsDisplayed()
            AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(node, compose.density, "t3_03:${tab.key}")
        }
    }

    @Test
    fun t3_04_predictiveBackAndTabSwitchDuringBackStackTransition_m3() {
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

        compose.runOnUiThread {
            backStack.push(OeDestination("s1", "S1") { Box(Modifier.fillMaxSize().testTag("t304:s1")) })
        }
        compose.waitForIdle()

        // Start predictive back gesture then interrupt by tapping Config dock tab
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(40f, 0f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertTrue("The gesture must be visibly underway before switching tabs",
            compose.onNodeWithTag("t304:s1").fetchSemanticsNode().boundsInRoot.left > 0f)

        compose.onNodeWithText(Oe3Tab.Config.label).performClick()
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()

        assertEquals(0, backStack.size)
        compose.onNodeWithTag("t304:s1").assertDoesNotExist()
        compose.onNodeWithTag("config:screen").assertIsDisplayed()
    }

    @Test
    fun t3_05_reduceMotionPlusLowRamPlusPowerSaveInLiveShell_baseline() {
        val controller = Oe3ShellController(Oe3Tab.Workstation)
        val backStack = OeBackStack()
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        var observedReduced = false
        var observedTier: GlassTier? = null

        compose.setContent {
            CompositionLocalProvider(LocalOe3GlassLowRam provides true) {
                Oe3App(
                    selectedTab = controller.selectedTab,
                    onTabSelected = controller::select,
                    theme = ThemeCatalog.constructivistWorkstation,
                    reduceMotion = true,
                    glassLevel = Oe3GlassLevel.Ultra,
                    glassEnabled = false,
                    backStack = backStack,
                    pages = Oe3Pages(
                        workstation = {
                            observedReduced = Oe3ThemeValues.info.reducedMotion
                            observedTier = Oe3GlassStrategy.resolveTier(
                                level = Oe3ThemeValues.info.glassLevel,
                                enabled = false,
                                apiLevel = 35,
                                isLowRamDevice = LocalOe3GlassLowRam.current,
                                deviceCap = GlassDeviceCap.Unlimited,
                            )
                            Box(Modifier.fillMaxSize().testTag("t305:ws"))
                        },
                        config = { Box(Modifier.fillMaxSize().testTag("t305:cfg")) },
                    ),
                )
            }
        }
        compose.waitForIdle()
        assertTrue(observedReduced)
        assertEquals(GlassTier.L0, observedTier)

        compose.onNodeWithText(Oe3Tab.Config.label).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("t305:cfg").assertIsDisplayed()

        compose.runOnUiThread {
            backStack.push(
                OeDestination("studio", "主题与外观") {
                    val handle = rememberThemeStudioHandle(store)
                    ThemeStudioScreen(handle.state, handle.onEvent, reduceMotion = true, onBack = { backStack.pop() })
                },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("theme-studio:screen").assertIsDisplayed()
    }

    @Test
    fun t3_06_systemModeFollowsDarkLightFlipWithoutLosingCustomBackgroundAndOpacity_baseline() {
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        store.setMode(ThemeStore.Mode.SYSTEM)
        store.setThemeId(ThemeCatalog.catppuccin.id)
        store.setBackgroundUri("content://test/user_wall.png")
        store.setUiSurfaceOpacityPercent(75)
        store.setCornerRadiusDp(20)

        assertEquals(ThemeStore.Mode.SYSTEM, store.mode())
        val light = store.resolveForMode(systemDark = false)
        assertEquals(ThemeCatalog.catppuccinLatte.id, light.id)
        assertFalse(light.isDark)

        val dark = store.resolveForMode(systemDark = true)
        assertEquals(ThemeCatalog.catppuccin.id, dark.id)
        assertTrue(dark.isDark)

        val lightAgain = store.resolveForMode(systemDark = false)
        assertEquals(ThemeCatalog.catppuccinLatte.id, lightAgain.id)

        assertEquals("content://test/user_wall.png", store.backgroundUri())
        assertEquals(75, store.uiSurfaceOpacityPercent())
        assertEquals(20, store.cornerRadiusDp())
        assertEquals(ThemeStore.Mode.SYSTEM, store.mode())
    }

    @Test
    fun t3_07_scrolledContentUnderHeaderAndAboveFloatingDockSimultaneously_m2() {
        val dashboard = AcceptanceTestSupport.sampleDashboardState()
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                navStyle = Oe3NavStyle.Floating,
                reduceMotion = true,
                pages = AcceptanceTestSupport.samplePages(dashboardState = dashboard),
            )
        }
        compose.waitForIdle()

        val cardsBounds = compose.onNodeWithTag("dashboard:cards").fetchSemanticsNode().boundsInRoot
        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue("Top content must slide behind overlaid header (top=${cardsBounds.top})", cardsBounds.top <= maxTopPx)

        // The last recent-instance row sits at the bottom edge of the last card.
        compose.onNodeWithTag("dashboard:cards").performScrollToIndex(4)
        compose.waitForIdle()
        val lastRow = compose.onNodeWithText(dashboard.recent.last().name)
        lastRow.assertIsDisplayed()
        val lastRowBounds = lastRow.fetchSemanticsNode().boundsInRoot
        val navBounds = compose.onNodeWithTag("shell:nav-bar").fetchSemanticsNode().boundsInRoot
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(rootBounds.bottom - navBounds.bottom >= with(compose.density) { 20.dp.toPx() })
        val minGap = with(compose.density) { 4.dp.toPx() }
        assertTrue("Dashboard's last card must clear the dock, gap=${navBounds.top - lastRowBounds.bottom}px",
            navBounds.top - lastRowBounds.bottom >= minGap)
    }

    @Test
    fun t3_08_customCornerRadius20PlusMonoFontPlusMaxTextScale_baseline() {
        var observedCorner: Dp? = null
        var observedFontScale = 0f
        var bodyFontFamily: FontFamily? = null

        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                cornerRadiusDp = 20,
                monoBody = true,
                textScale = 1.30f,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        observedCorner = LocalOe3CornerRadius.current
                        observedFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                        bodyFontFamily = Oe3Typography.style(Oe3TextRole.Body).fontFamily
                        Box(Modifier.fillMaxSize().testTag("t308:ok"))
                    },
                ),
            )
        }
        compose.waitForIdle()
        assertEquals(20.dp, observedCorner)
        assertEquals(1.30f, observedFontScale, 0.01f)
        assertEquals(FontFamily.Monospace, bodyFontFamily)

        Oe3Tab.entries.forEach { tab ->
            AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(
                compose.onNode(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(tab.label),
                ),
                compose.density,
                "t308:${tab.key}",
            )
        }
    }

    @Test
    fun t3_09_pushingSecondaryOeScaffoldFromScrolledRootTab_m2() {
        val backStack = OeBackStack()
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                backStack = backStack,
                pages = Oe3Pages(
                    workstation = {
                        OeScaffold(title = "工作站") {
                            LazyColumn(Modifier.fillMaxSize().testTag("t309:list")) {
                                items(30) { i ->
                                    Box(Modifier.fillMaxWidth().height(72.dp)) { Text("WS Row #$i") }
                                }
                            }
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()
        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue(compose.onNodeWithTag("t309:list").fetchSemanticsNode().boundsInRoot.top <= maxTopPx)

        compose.onNodeWithTag("t309:list").performScrollToIndex(18)
        compose.waitForIdle()
        compose.onNodeWithText("WS Row #18").assertIsDisplayed()

        compose.runOnUiThread {
            backStack.push(
                OeDestination("studio", "主题与外观") {
                    val handle = rememberThemeStudioHandle(store)
                    ThemeStudioScreen(handle.state, handle.onEvent, reduceMotion = true, onBack = { backStack.pop() })
                },
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag("theme-studio:screen").assertIsDisplayed()

        compose.runOnUiThread { backStack.pop() }
        compose.waitForIdle()
        compose.onNodeWithText("WS Row #18").assertIsDisplayed()
    }

    @Test
    fun t3_10_tokenGateComplianceWithL2GlassAndExpandedMotionTokens_m3() {
        // W1-02：规格仓检出前置（解析链与口径见 AcceptanceTestSupport.specsToolRoot）。
        AcceptanceTestSupport.assumeSpecsToolAvailable()
        val result = AcceptanceTestSupport.runCheckTokens()
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("0 FAIL"))
        assertTrue(result.stdout.contains("0 occurrence(s)"))

        val reduced = Oe3Motion.reduced()
        assertEquals(0, reduced.navSelection.durationMillis)
        assertEquals(0, reduced.pagePush.durationMillis)
        assertEquals(null, reduced.navSelectionSpring)
        assertEquals(null, reduced.pagePushSpring)

        assertEquals(
            GlassTier.L2,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 35, false, GlassDeviceCap.Unlimited),
        )
    }

    @Test
    fun t3_11_surfaceAlpha55PercentWithGlassStandardAndCardLuminance_m2() {
        var capturedAlpha = 0f
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                glassLevel = Oe3GlassLevel.Standard,
                surfaceAlpha = 0.55f,
                backgroundUri = null,
                reduceMotion = true,
                pages = Oe3Pages(
                    workstation = {
                        capturedAlpha = LocalOeSurfaceAlpha.current
                        Box(Modifier.fillMaxSize().background(Color(0xFF00C896))) {
                            Column(Modifier.fillMaxSize()) {
                                Spacer(Modifier.weight(1f))
                                Oe3Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .testTag("t311:card"),
                                ) { Box(Modifier.fillMaxSize()) }
                            }
                        }
                    },
                ),
            )
        }
        compose.waitForIdle()
        assertEquals(0.55f, capturedAlpha, 0.001f)

        val cardBounds = compose.onNodeWithTag("t311:card").fetchSemanticsNode().boundsInRoot
        val navBounds = compose.onNodeWithTag("shell:nav-bar").fetchSemanticsNode().boundsInRoot
        val img = AcceptanceTestSupport.captureRootBitmap(compose)

        val cardSample = AcceptanceTestSupport.sampleAverageColor(
            img,
            (cardBounds.left.roundToInt() + 30)..(cardBounds.right.roundToInt() - 30),
            (cardBounds.top.roundToInt() + 20)..(navBounds.top.roundToInt() - 20),
        )
        // Card must show green tint from alpha = 0.55f over #00C896 backdrop
        assertTrue("Oe3Card must apply surfaceAlpha=0.55f (green=${cardSample.green})", cardSample.green >= 0.30f)

        val dockSample = AcceptanceTestSupport.sampleAverageColor(
            img,
            (navBounds.right.roundToInt() - 24)..(navBounds.right.roundToInt() - 10),
            (navBounds.top.roundToInt() + 10)..(navBounds.bottom.roundToInt() - 10),
        )
        assertTrue(
            "Dock vs translucent card delta too small (${AcceptanceTestSupport.colorDistance(cardSample, dockSample)})",
            AcceptanceTestSupport.colorDistance(cardSample, dockSample) >= 4.0,
        )
    }

    @Test
    fun t3_12_progressiveScrollEdgeBlurAtApi33PlusVsGradientFallbackAtApi29_m3() {
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 29, false, GlassDeviceCap.Unlimited),
        )
        assertEquals(
            GlassTier.L2,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Standard, true, 33, false, GlassDeviceCap.Unlimited),
        )
        // GPU blur strength is device-dependent. The host test can still guard the
        // actual progressive Android integration independently of implementation layout.
        val usesVerticalHaze = AcceptanceTestSupport.uiSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { "HazeProgressive.verticalGradient" in it.readText() }
        assertTrue("API 31+ scroll edge must use a vertical Haze progression", usesVerticalHaze)
    }

    @Test
    fun t3_13_dockDragTracksFingerThenCommitsOneDestination_m3() {
        var selected by mutableStateOf(Oe3Tab.Workstation)
        val selections = mutableListOf<Oe3Tab>()
        compose.setContent {
            Oe3App(
                selectedTab = selected,
                onTabSelected = {
                    selections += it
                    selected = it
                },
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = false,
                pages = AcceptanceTestSupport.samplePages(),
            )
        }
        compose.waitForIdle()

        fun dockTab(tab: Oe3Tab) = compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(tab.label),
        )

        val dock = compose.onNodeWithTag("shell:nav-bar")
        val origin = dock.fetchSemanticsNode().boundsInRoot.topLeft
        val start = dockTab(Oe3Tab.Workstation).fetchSemanticsNode().boundsInRoot.center - origin
        val middleTab = dockTab(Oe3Tab.Hardware).fetchSemanticsNode().boundsInRoot
        val middle = middleTab.center - origin
        val end = dockTab(Oe3Tab.Config).fetchSemanticsNode().boundsInRoot.center - origin
        val probeX = (middleTab.center.x + middleTab.width * 0.25f).roundToInt()
        val probeY = middleTab.center.y.roundToInt()

        fun middleHighlight(): Color = AcceptanceTestSupport.sampleAverageColor(
            AcceptanceTestSupport.captureRootBitmap(compose),
            (probeX - 2)..(probeX + 2),
            (probeY - 3)..(probeY + 3),
        )

        val rest = middleHighlight()
        dock.performTouchInput {
            down(start)
            moveTo(start + Offset((middle.x - start.x) * 0.25f, 0f))
            moveTo(middle)
        }
        compose.waitForIdle()
        val duringDrag = middleHighlight()
        assertTrue("The dock must show a moving highlight while the finger is down",
            AcceptanceTestSupport.colorDistance(rest, duringDrag) >= 3.0)
        assertTrue("Dragging over a tab must leave the current page selected until release",
            compose.runOnIdle { selections.isEmpty() && selected == Oe3Tab.Workstation })

        dock.performTouchInput {
            moveTo(end)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(Oe3Tab.Config), compose.runOnIdle { selections.toList() })
        dockTab(Oe3Tab.Config).assertIsSelected()
        compose.onNodeWithTag("config:screen").assertIsDisplayed()
    }
}
