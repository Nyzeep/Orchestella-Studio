package io.github.nyzeep.orchestella.ui.acceptance

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ListRow
import io.github.nyzeep.orchestella.ui.oe3.components.OeListGroup
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassDeviceCap
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassMode
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassTier
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassBlurSafety
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassFrameGate
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassStrategy
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassSurface
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3MotionSystem
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Colors
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TypeScale
import io.github.nyzeep.orchestella.ui.screens.config.ConfigScreen
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardCardId
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardEditSpec
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardEditState
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareScreen
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesActions
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemePresetScreen
import io.github.nyzeep.orchestella.ui.screens.theme.TerminalPreview
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioController
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioEvent
import io.github.nyzeep.orchestella.ui.screens.theme.rememberThemeStudioHandle
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardController
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardScreen
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3ShellController
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.OeScaffoldAction
import io.github.nyzeep.orchestella.ui.shell.OeScaffoldSpec
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.OeTextScale
import io.github.nyzeep.orchestella.ui.theme.SurfaceOpacity
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Tier 2 — Boundary, Edge & Error-Path Tests (26 tests across R1–R5).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-mdpi")
class Tier2BoundaryCasesTest {

    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    // =========================================================================
    // R1 Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun t2_r1_01_checkTokensDetectsShadowViolationAndMissingPath_baseline() {
        // W1-02：规格仓检出前置（解析链与口径见 AcceptanceTestSupport.specsToolRoot）。
        AcceptanceTestSupport.assumeSpecsToolAvailable()
        val missingTokens = File(AcceptanceTestSupport.projectRoot(), "docs/design-system/tokens/nonexistent_oe3.json")
        val missingResult = AcceptanceTestSupport.runCheckTokens(tokensFile = missingTokens)
        assertEquals(1, missingResult.exitCode)
        assertTrue(
            "Expected FATAL in output:\n${missingResult.stdout}\n${missingResult.stderr}",
            missingResult.stdout.contains("FATAL") || missingResult.stderr.contains("FATAL"),
        )

        // Copy ui source to temp dir and inject a synthetic .shadow( violation
        val tempDir = Files.createTempDirectory("oe3_shadow_gate_").toFile()
        try {
            AcceptanceTestSupport.uiSourceRoot().copyRecursively(tempDir, overwrite = true)
            val probeFile = File(tempDir, "oe3/components/ShadowProbe.kt")
            probeFile.writeText("package test\nval x = Modifier.shadow(4.dp)\n")
            val shadowResult = AcceptanceTestSupport.runCheckTokens(kotlinRoot = tempDir)
            assertEquals("check_tokens.py must exit 1 on .shadow( violation", 1, shadowResult.exitCode)
            assertTrue(
                "Expected RESULT: FAIL on .shadow(:\n${shadowResult.stdout}",
                shadowResult.stdout.contains("RESULT: FAIL"),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun t2_r1_02_themeStoreRejectsRemoteAndFileUrisAndClampsCorruptNums_baseline() {
        val prefs = AcceptanceTestSupport.InMemorySharedPreferences()
        val store = ThemeStore(prefs)
        store.setBackgroundUri("content://valid/wallpaper.png")

        listOf("https://evil.test/bg.png", "file:///sdcard/bg.jpg", "http://127.0.0.1/x.png").forEach { bad ->
            try {
                store.setBackgroundUri(bad)
                fail("Expected IllegalArgumentException for $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
            assertEquals("content://valid/wallpaper.png", store.backgroundUri())
        }

        prefs.edit()
            .putInt(ThemeStore.KEY_CORNER_RADIUS_DP, -5)
            .putInt(ThemeStore.KEY_UI_SURFACE_OPACITY, 10)
            .putInt(ThemeStore.KEY_TEXT_SCALE_PERCENT, 50)
            .putString(ThemeStore.KEY_UI_GLASS_MODE, "invalid-mode")
            .putString(ThemeStore.KEY_UI_GLASS_LEVEL, "corrupt-level")
            .putString(ThemeStore.KEY_UI_NAV_STYLE, "corrupt-nav")
            .putString(ThemeStore.KEY_UI_FONT_CHOICE, "imported")
            .apply()

        assertEquals(0, store.cornerRadiusDp())
        assertEquals(55, store.uiSurfaceOpacityPercent())
        assertEquals(85, store.textScalePercent())
        assertEquals("auto", store.uiGlassMode())
        assertEquals("none", store.uiGlassLevel())
        assertEquals("floating", store.uiNavStyle())
        assertEquals("system", store.uiFontChoice())

        prefs.edit()
            .putInt(ThemeStore.KEY_CORNER_RADIUS_DP, 99)
            .putInt(ThemeStore.KEY_UI_SURFACE_OPACITY, 200)
            .putInt(ThemeStore.KEY_TEXT_SCALE_PERCENT, 300)
            .apply()

        assertEquals(24, store.cornerRadiusDp())
        assertEquals(100, store.uiSurfaceOpacityPercent())
        assertEquals(130, store.textScalePercent())
    }

    @Test
    fun t2_r1_03_themeStoreMigratesLegacyMascotIdAndDecaysFixedMode_baseline() {
        val prefs = AcceptanceTestSupport.InMemorySharedPreferences()
        prefs.edit()
            .putString(ThemeStore.KEY_THEME_ID, "mascot-lavender")
            .putString(ThemeStore.KEY_THEME_MODE, "fixed")
            .apply()

        val store = ThemeStore(prefs)
        assertEquals("mascot-light", store.themeId())
        assertEquals(ThemeStore.Mode.SYSTEM, store.mode())
        // Read path does not destructively erase stored prefs
        assertEquals("mascot-lavender", prefs.getString(ThemeStore.KEY_THEME_ID, null))
    }

    @Test
    fun t2_r1_04_dashboardEditStoreIgnoresCorruptTokensAndNonEditOps_baseline() {
        val store = AcceptanceTestSupport.InMemoryDashboardEditStore(
            mapOf(
                DashboardEditSpec.KEY_ORDER to "Unknown,,metrics,METRICS",
                DashboardEditSpec.KEY_HIDDEN to "bogus,recent",
            ),
        )
        val edit = DashboardEditState(store)
        assertEquals(
            listOf(
                DashboardCardId.Metrics,
                DashboardCardId.Primary,
                DashboardCardId.Storage,
                DashboardCardId.Recent,
            ),
            edit.order,
        )
        assertEquals(listOf(DashboardCardId.Recent), edit.hidden)

        // Mutations while isEditing == false must be strict no-ops
        val visibleBefore = edit.visible.toList()
        val hiddenBefore = edit.hidden.toList()
        edit.moveVisible(0, 1)
        edit.hide(DashboardCardId.Primary)
        edit.restore(DashboardCardId.Recent)
        assertEquals(visibleBefore, edit.visible)
        assertEquals(hiddenBefore, edit.hidden)
    }

    @Test
    fun t2_r1_05_themeCatalogFallbackHandlesUnknownAndNullIds_baseline() {
        assertEquals(ThemeCatalog.default.id, ThemeCatalog.resolve(null).id)
        assertEquals(ThemeCatalog.default.id, ThemeCatalog.resolve("does-not-exist").id)
        assertEquals(ThemeCatalog.default.id, ThemeCatalog.resolve("").id)

        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        assertNull(store.resolveCustomSlot("${CustomThemeSlot.ID_PREFIX}99"))
        assertNull(store.resolveCustomSlot("invalid-slot"))
    }

    // =========================================================================
    // R2 Boundary Cases (6 tests)
    // =========================================================================

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w343dp-h800dp-mdpi")
    fun t2_r2_01_narrowViewport343DpAt130PercentFontScaleNoTruncation_baseline() {
        val longEngine = "QEMU/TCG 兼容回退引擎 (Software Emulation)"
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
                            state = AcceptanceTestSupport.sampleInstancesState(engineLabel = longEngine),
                            actions = InstancesActions(),
                        )
                    },
                ),
            )
        }
        compose.waitForIdle()

        val density = compose.density
        val rootWidthDp = with(density) { compose.onRoot().fetchSemanticsNode().boundsInRoot.width.toDp() }
        assertEquals("Test must exercise the narrow 343dp viewport", 343f, rootWidthDp.value, 1f)
        Oe3Tab.entries.forEach { tab ->
            assertTrue(tab.label.isNotBlank())
            val tabNode = compose.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(tab.label),
            )
            tabNode.assertIsDisplayed()
            AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(tabNode, density, "narrow-tab:${tab.key}")

            val textNode = compose.onNode(
                hasText(tab.label) and hasAnyAncestor(hasTestTag("shell:nav-bar")),
                useUnmergedTree = true,
            )
            val layouts = mutableListOf<TextLayoutResult>()
            textNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
                assertTrue("${tab.label} must expose its laid-out text", read(layouts))
            }
            val layout = layouts.single()
            val lineRight = layout.getLineRight(0)
            val maxWidth = layout.layoutInput.constraints.maxWidth
            val fitsCell = !layout.didOverflowHeight &&
                !layout.isLineEllipsized(0) &&
                lineRight <= maxWidth + 0.5f &&
                layout.size.width <= maxWidth
            val diagnostics = "root=$rootWidthDp, size=${layout.size}, " +
                "paragraph=${layout.multiParagraph.width}×${layout.multiParagraph.height}, " +
                "lineRight=$lineRight, maxWidth=$maxWidth, " +
                "constraints=${layout.layoutInput.constraints}, scale=${layout.layoutInput.density.fontScale}, " +
                "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                "ellipsized=${layout.isLineEllipsized(0)}"
            assertTrue("${tab.label} must fit its dock cell at 343dp / 130%, $diagnostics", fitsCell)
            assertFalse("${tab.label} must be shown without ellipsis, $diagnostics", layout.isLineEllipsized(0))
        }

        // Verify BadgeRow wraps engine badge onto second line below status chip
        val statusBounds = compose.onNodeWithTag("instances-status-running-inst-ubuntu-01", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val engineBounds = compose.onNodeWithTag("instances-engine-inst-ubuntu-01", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Long engine badge must wrap below status chip on 343dp @ 130% scale (engine.top=${engineBounds.top}, status.bottom=${statusBounds.bottom})",
            engineBounds.top >= statusBounds.bottom,
        )
    }

    @Test
    fun t2_r2_02_pureBlackNeverAppliesToLightThemesOrMascotLight_baseline() {
        val normalLight = Oe3Colors.resolve(ThemeCatalog.mascotLight, pureBlack = false)
        val pureBlackAttempt = Oe3Colors.resolve(ThemeCatalog.mascotLight, pureBlack = true)
        assertEquals(normalLight.background, pureBlackAttempt.background)
        assertEquals(normalLight.surface, pureBlackAttempt.surface)
        assertEquals(normalLight.surfaceRaised, pureBlackAttempt.surfaceRaised)
        assertEquals(normalLight.surfaceHigh, pureBlackAttempt.surfaceHigh)

        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        store.setThemeId(ThemeCatalog.mascotLight.id)
        store.setMode(ThemeStore.Mode.LIGHT)
        store.setUiPureBlack(true)
        val controller = ThemeStudioController(store)
        assertFalse(controller.read().pureBlack)
    }

    @Test
    fun t2_r2_03_textScaleNonFiniteAndZeroFactorsFallbackSafely_baseline() {
        assertEquals(1.0f, OeTextScale.safeFactor(Float.NaN), 0.0001f)
        assertEquals(1.0f, OeTextScale.safeFactor(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(1.0f, OeTextScale.safeFactor(Float.NEGATIVE_INFINITY), 0.0001f)
        assertEquals(1.0f, OeTextScale.safeFactor(-1f), 0.0001f)
        assertEquals(1.0f, OeTextScale.safeFactor(0f), 0.0001f)
        assertEquals(0.85f, OeTextScale.safeFactor(0.5f), 0.0001f)
        assertEquals(1.30f, OeTextScale.safeFactor(2.5f), 0.0001f)

        assertEquals(0.85f, Oe3TypeScale.clamp(0.5f), 0.0001f)
        assertEquals(1.30f, Oe3TypeScale.clamp(2.5f), 0.0001f)
        // System accessibility fontScale is preserved (multiplied, never clamped down)
        assertEquals(1.7f * 1.30f, Oe3TypeScale.effective(1.7f, 2.0f), 0.001f)
    }

    @Test
    fun t2_r2_04_surfaceOpacityFallsBackTo55PercentWhenContrastBelow45_baseline() {
        // Synthetic low-contrast surface vs text (both dark grey)
        val lowContrastBg = 0xFF333333.toInt()
        val lowContrastSurface = 0xFF3A3A3A.toInt()
        val lowContrastText = 0xFF555555.toInt()
        val resolvedNoBackdrop = SurfaceOpacity.resolveEffectivePercent(
            surfaceArgb = lowContrastSurface,
            behindArgb = lowContrastBg,
            textArgb = lowContrastText,
            requestedPercent = 90,
        )
        assertEquals(SurfaceOpacity.MIN_PERCENT, resolvedNoBackdrop)

        // With wallpaper backdrop, requested percent in [55..100] is honored without contrast clamping
        val resolvedColor = SurfaceOpacity.resolveSurfaceColor(
            surfaceArgb = lowContrastSurface,
            behindArgb = lowContrastBg,
            textArgb = lowContrastText,
            requestedPercent = 80,
            hasBackdrop = true,
        )
        assertEquals((80 * 255) / 100, (resolvedColor ushr 24) and 0xFF)
    }

    @Test
    fun t2_r2_05_hardwareMissingSensorsRenderEmDashNeverZeroOrNa_baseline() {
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                HardwareScreen(
                    state = AcceptanceTestSupport.missingSensorsHardwareState(),
                    actions = io.github.nyzeep.orchestella.ui.screens.hardware.HardwareActions(),
                )
            }
        }
        compose.waitForIdle()
        val emDashNodes = compose.onAllNodesWithText("\u2014", substring = true)
        assertTrue("Expected em-dash placeholders for missing sensors", emDashNodes.fetchSemanticsNodes().isNotEmpty())
        compose.onAllNodesWithText("N/A", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("null", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("0.0 °C", substring = true).assertCountEquals(0)
    }

    @Test
    fun t2_r2_06_concentricCornerMathAndCornerRadiusClampAt24Dp_m2() {
        var observedCorner: Dp? = null
        var stage by mutableIntStateOf(0)
        val wizardCtrl = WizardController(
            context = compose.activity,
            targetDistroId = "ubuntu",
            callbacks = WizardController.Callbacks(onCreated = {}, onShowShizukuClient = {}),
        )
        try {
            compose.setContent {
                when (stage) {
                    0 -> Oe3App(
                        selectedTab = Oe3Tab.Workstation,
                        onTabSelected = {},
                        theme = ThemeCatalog.constructivistWorkstation,
                        cornerRadiusDp = 40,
                        reduceMotion = true,
                        pages = Oe3Pages(
                            workstation = {
                                observedCorner = LocalOe3CornerRadius.current
                                OeListGroup(modifier = Modifier.fillMaxWidth()) {
                                    Oe3ListRow(title = "Row 1", showDivider = true)
                                    Oe3ListRow(title = "Row 2")
                                }
                            },
                        ),
                    )
                    1 -> Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                        ConfigScreen(
                            state = AcceptanceTestSupport.sampleConfigState(),
                            onRoute = {},
                            onOptionsChange = {},
                            onBack = {},
                        )
                    }
                    2 -> Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                        WizardScreen(controller = wizardCtrl, onExit = {})
                    }
                    else -> Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                        TerminalPreview(
                            theme = ThemeCatalog.constructivistWorkstation,
                            fontSizeSp = 12,
                            letterSpacingEm = 0f,
                            lineSpacingPx = 0f,
                            cursorBlink = false,
                            renderAndroidView = false,
                        )
                    }
                }
            }
            compose.waitForIdle()
            assertEquals(24.dp, observedCorner)
            assertEquals(0.dp, Oe3Shapes.groupedInner)

            listOf("config:back", "wizard:back", "theme-terminal:send").forEachIndexed { index, tag ->
                compose.runOnUiThread { stage = index + 1 }
                compose.waitForIdle()
                AcceptanceTestSupport.assertControlLayoutAtLeast48Dp(
                    compose.onNodeWithTag(tag, useUnmergedTree = true),
                    compose.density,
                    tag,
                )
            }
        } finally {
            wizardCtrl.dispose()
        }
    }

    // =========================================================================
    // R3 Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun t2_r3_01_invalidOrRevokedWallpaperUriFallsBackToGradientWithoutCrash_baseline() {
        val authority = "acceptance.revoked.wallpaper.test"
        val provider = Robolectric.setupContentProvider(SyntheticWallpaperProvider::class.java, authority)
        val attempted = CountDownLatch(1)
        provider.openError = SecurityException("Permission Denial: revoked wallpaper URI")
        provider.attemptedLatch = attempted
        try {
            compose.setContent {
                Oe3App(
                    selectedTab = Oe3Tab.Workstation,
                    onTabSelected = {},
                    theme = ThemeCatalog.mascotDark,
                    backgroundUri = "content://$authority/revoked_wallpaper.png",
                    reduceMotion = true,
                    pages = AcceptanceTestSupport.samplePages(),
                )
            }
            assertTrue("Wallpaper loader must attempt the revoked URI", attempted.await(5, TimeUnit.SECONDS))
            compose.waitForIdle()
            compose.onNodeWithTag("dashboard:cards").assertIsDisplayed()
            compose.onNodeWithTag("shell:nav-bar").assertIsDisplayed()
        } finally {
            provider.openError = null
            provider.attemptedLatch = null
        }
    }

    @Test
    fun t2_r3_06_validWallpaperRemountRendersCachedImageBeforeProviderReturns_baseline() {
        val authority = "acceptance.wallpaper.test"
        val fixture = File.createTempFile("wallpaper", ".fixture", compose.activity.cacheDir)
        val provider = Robolectric.setupContentProvider(SyntheticWallpaperProvider::class.java, authority)
        provider.fixture = fixture
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        try {
            fixture.outputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        } finally {
            bitmap.recycle()
        }

        val uri = "content://$authority/sample"
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        store.setThemeId(ThemeCatalog.mascotDark.id)
        store.setBackgroundUri(uri)
        var showingWallpaper by mutableStateOf(true)
        var isDarkPreset by mutableStateOf(false)

        fun wallpaperIsRendered(): Boolean {
            val screenshot = AcceptanceTestSupport.captureRootBitmap(compose)
            val color = Color(screenshot.getPixel(screenshot.width / 2, screenshot.height / 2))
            return color.red - color.green > 0.20f && color.blue - color.green > 0.20f
        }

        val revalidationGate = CountDownLatch(1)
        try {
            compose.setContent {
                Oe3App(
                    selectedTab = Oe3Tab.Workstation,
                    onTabSelected = {},
                    theme = if (isDarkPreset) store.resolveForMode(systemDark = true) else ThemeCatalog.mascotDark,
                    backgroundUri = if (showingWallpaper) store.backgroundUri() else null,
                    reduceMotion = true,
                    pages = Oe3Pages(workstation = { Box(Modifier.fillMaxSize()) }),
                )
            }
            compose.waitUntil(timeoutMillis = 5_000) { wallpaperIsRendered() }

            compose.runOnUiThread {
                store.setThemeId(ThemeCatalog.catppuccin.id)
                store.setMode(ThemeStore.Mode.DARK)
                isDarkPreset = true
            }
            compose.waitForIdle()
            assertEquals(uri, store.backgroundUri())
            assertTrue("Wallpaper pixels must survive a preset and mode change", wallpaperIsRendered())

            compose.runOnUiThread { showingWallpaper = false }
            compose.waitForIdle()
            assertFalse("Without an active wallpaper the shell must show its theme background", wallpaperIsRendered())

            provider.openGate = revalidationGate
            compose.runOnUiThread { showingWallpaper = true }
            compose.waitForIdle()
            assertTrue("Previously decoded wallpaper must render while its provider is still blocked", wallpaperIsRendered())
        } finally {
            revalidationGate.countDown()
            compose.runOnUiThread { showingWallpaper = false }
            compose.waitForIdle()
            fixture.delete()
        }
    }

    class SyntheticWallpaperProvider : ContentProvider() {
        lateinit var fixture: File
        @Volatile var openGate: CountDownLatch? = null
        @Volatile var openError: RuntimeException? = null
        @Volatile var attemptedLatch: CountDownLatch? = null

        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
            attemptedLatch?.countDown()
            openError?.let { throw it }
            openGate?.let { check(it.await(10, TimeUnit.SECONDS)) { "Wallpaper test left its provider blocked" } }
            val descriptor = ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY)
            return AssetFileDescriptor(descriptor, 0, fixture.length())
        }
    }

    @Test
    fun t2_r3_02_rapidDoublePushAndInterruptPopDoesNotStallOrGhost_baseline() {
        val backStack = OeBackStack()
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                Oe3App(
                    selectedTab = Oe3Tab.Workstation,
                    onTabSelected = {},
                    theme = ThemeCatalog.constructivistWorkstation,
                    reduceMotion = false,
                    backStack = backStack,
                    pages = Oe3Pages(
                        workstation = { Box(Modifier.fillMaxSize().testTag("rapid:base")) },
                    ),
                )
            }
            compose.mainClock.advanceTimeByFrame()

            compose.runOnUiThread {
                backStack.push(OeDestination("a", "A") { Box(Modifier.fillMaxSize().testTag("rapid:a")) })
                backStack.push(OeDestination("b", "B") { Box(Modifier.fillMaxSize().testTag("rapid:b")) })
            }
            AcceptanceTestSupport.advanceComposeFrames(compose, frames = 2)
            compose.runOnUiThread { backStack.pop() }
            AcceptanceTestSupport.advanceComposeFrames(compose, frames = 2)
            compose.runOnUiThread { backStack.pop() }

            AcceptanceTestSupport.advanceComposeFrames(compose)
            assertEquals(0, backStack.size)
            assertFalse(backStack.canPop)
            compose.onNodeWithTag("rapid:a").assertDoesNotExist()
            compose.onNodeWithTag("rapid:b").assertDoesNotExist()
            compose.onNodeWithTag("rapid:base").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun t2_r3_03_popOnEmptyBackStackReturnsFalseAndLeavesHostUnconsumed_baseline() {
        val backStack = OeBackStack()
        assertFalse(backStack.pop())

        val controller = Oe3ShellController(Oe3Tab.Instances)
        compose.activity.onBackPressedDispatcher.addCallback(
            compose.activity,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!controller.back()) {
                        isEnabled = false
                        compose.activity.onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
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
        assertEquals(Oe3Tab.Instances, controller.selectedTab)

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(Oe3Tab.Workstation, controller.selectedTab)
        compose.onNodeWithTag("dashboard:cards").assertIsDisplayed()
    }

    @Test
    fun t2_r3_04_switchingBetweenMultiplePresetsWithPureBlackAndWallpaper_baseline() {
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        val slot = CustomThemeSlot(
            name = "Custom",
            isDark = true,
            background = 0xFF101010.toInt(),
            surface = 0xFF1A1A1A.toInt(),
            primary = 0xFF44AAFF.toInt(),
            accent = 0xFFFF8844.toInt(),
            text = 0xFFEEEEEE.toInt(),
            textDim = 0xFFAAAAAA.toInt(),
            terminalBackground = 0xFF101010.toInt(),
            terminalForeground = 0xFFEEEEEE.toInt(),
        )
        store.setCustomSlot(0, slot)
        store.setBackgroundUri("content://user/custom_wallpaper.jpg")
        store.setUiPureBlack(true)

        val ctrl = ThemeStudioController(store)
        val targets = listOf(
            ThemeCatalog.catppuccin.id,
            ThemeCatalog.catppuccinLatte.id,
            ThemeCatalog.gruvbox.id,
            "${CustomThemeSlot.ID_PREFIX}1",
            ThemeCatalog.mascotLight.id,
        )
        for (id in targets) {
            val state = ctrl.handle(ThemeStudioEvent.SelectTheme(id))
            assertEquals("content://user/custom_wallpaper.jpg", store.backgroundUri())
            assertTrue(store.uiPureBlack())
            assertEquals(state.theme.isDark, state.pureBlack)
        }
    }

    @Test
    fun t2_r3_05_rapidTabSwitchingRestoresOnlyTargetTabContent_baseline() {
        val controller = Oe3ShellController(Oe3Tab.Workstation)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                Oe3App(
                    selectedTab = controller.selectedTab,
                    onTabSelected = controller::select,
                    theme = ThemeCatalog.constructivistWorkstation,
                    reduceMotion = false,
                    pages = AcceptanceTestSupport.samplePages(),
                )
            }
            compose.mainClock.advanceTimeByFrame()

            compose.onNodeWithText(Oe3Tab.Config.label).performClick()
            AcceptanceTestSupport.advanceComposeFrames(compose, frames = 4)
            compose.onNodeWithText(Oe3Tab.Distros.label).performClick()
            AcceptanceTestSupport.advanceComposeFrames(compose, frames = 4)
            compose.onNodeWithText(Oe3Tab.Instances.label).performClick()

            AcceptanceTestSupport.advanceComposeFrames(compose)
            compose.onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(Oe3Tab.Instances.label),
            ).assertIsSelected()
            compose.onNodeWithTag("instances-list").assertIsDisplayed()
            compose.onNodeWithTag("config:screen").assertDoesNotExist()
            compose.onNodeWithTag("distros-grid").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    // =========================================================================
    // R4 Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun t2_r4_01_oeScaffoldBarAlphaClampsZeroCollapseAndNegativeScroll_baseline() {
        assertEquals(1f, OeScaffoldSpec.barAlpha(0f, 0f), 0.0001f)
        assertEquals(1f, OeScaffoldSpec.barAlpha(10f, -5f), 0.0001f)
        assertEquals(0f, OeScaffoldSpec.barAlpha(-10f, 36f), 0.0001f)
        assertEquals(1f, OeScaffoldSpec.barAlpha(100f, 36f), 0.0001f)
        assertEquals(0f, OeScaffoldSpec.advance(0f, 50f, 36f), 0.0001f)
        assertEquals(36f, OeScaffoldSpec.advance(20f, -50f, 36f), 0.0001f)
    }

    @Test
    fun t2_r4_02_oeScaffoldZeroActionsAndFiveActionsOverflowBoundary_baseline() {
        var stage by mutableIntStateOf(0)
        val three = (1..3).map { i -> OeScaffoldAction(Oe3Icons.Edit, "A$i", {}, testTag = "act3:$i") }
        val five = (1..5).map { i -> OeScaffoldAction(Oe3Icons.Edit, "A$i", {}, testTag = "act5:$i") }

        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation, reduceMotion = true) {
                when (stage) {
                    0 -> OeScaffold(title = "零动作", actions = emptyList()) { Box(Modifier.fillMaxSize()) }
                    1 -> OeScaffold(title = "三动作", actions = three) { Box(Modifier.fillMaxSize()) }
                    else -> OeScaffold(title = "五动作", actions = five) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("scaffold:overflow").assertDoesNotExist()

        // 3 actions -> 3 direct, 0 overflow
        stage = 1
        compose.waitForIdle()
        (1..3).forEach { compose.onNodeWithTag("act3:$it").assertExists() }
        compose.onNodeWithTag("scaffold:overflow").assertDoesNotExist()

        // 5 actions -> 2 direct + overflow(3)
        stage = 2
        compose.waitForIdle()
        compose.onNodeWithTag("act5:1").assertExists()
        compose.onNodeWithTag("act5:2").assertExists()
        compose.onNodeWithTag("act5:3").assertDoesNotExist()
        compose.onNodeWithTag("scaffold:overflow").performClick()
        compose.waitForIdle()
        (3..5).forEach { compose.onNodeWithTag("act5:$it").assertExists() }
    }

    @Test
    @Config(sdk = [35], qualifiers = "zh-rCN-w343dp-h800dp-mdpi")
    fun t2_r4_03_oeScaffoldLongTitleSingleLineEllipsisPreservesActionBounds_m2() {
        val longTitle = "超长功能页面标题用于测试窄屏与大字号条件下单行省略并且绝不挤出右上动作按钮".repeat(2)
        compose.setContent {
            Oe3Theme(
                theme = ThemeCatalog.constructivistWorkstation,
                textScale = 1.3f,
                reduceMotion = true,
            ) {
                OeScaffold(
                    title = longTitle,
                    actions = listOf(
                        OeScaffoldAction(Oe3Icons.Edit, "动作一", {}, testTag = "long:act1"),
                        OeScaffoldAction(Oe3Icons.Check, "动作二", {}, testTag = "long:act2"),
                    ),
                ) {
                    Box(Modifier.fillMaxSize().testTag("long:content"))
                }
            }
        }
        compose.waitForIdle()
        val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val act1 = compose.onNodeWithTag("long:act1")
        val act2 = compose.onNodeWithTag("long:act2")
        AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(act1, compose.density, "long:act1")
        AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(act2, compose.density, "long:act2")
        assertTrue(act2.fetchSemanticsNode().boundsInRoot.right <= rootBounds.right)

        val titleBounds = compose.onNodeWithText(longTitle).fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.right <= act1.fetchSemanticsNode().boundsInRoot.left + 1f)

        // M2 R4 overlaid Box: content starts at top under floating header
        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue(compose.onNodeWithTag("long:content").fetchSemanticsNode().boundsInRoot.top <= maxTopPx)
    }

    @Test
    fun t2_r4_04_oeScaffoldScrollBackToTopRestoresZeroOverlayFade_m2() {
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Config,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                pages = AcceptanceTestSupport.samplePages(),
            )
        }
        compose.waitForIdle()
        val list = compose.onNodeWithTag("config:screen")
        val maxTopPx = with(compose.density) { 4.dp.toPx() }
        assertTrue(
            "M2 R4 requires overlaid Box content top <= 4dp",
            list.fetchSemanticsNode().boundsInRoot.top <= maxTopPx,
        )

        val configTitle = compose.onNode(
            hasText(Oe3Tab.Config.label) and !hasAnyAncestor(hasTestTag("shell:nav-bar")),
            useUnmergedTree = true,
        )
        val firstItem = compose.onNodeWithText(compose.activity.getString(R.string.config_page_meta))
        val firstItemTop = firstItem.fetchSemanticsNode().boundsInRoot.top
        val fadeExtensionPx = with(compose.density) { 14.dp.toPx() }
        fun sampleHeaderBands(): Pair<Color, Color> {
            val title = configTitle.fetchSemanticsNode().boundsInRoot
            val image = AcceptanceTestSupport.captureRootBitmap(compose)
            val xRange = (image.width * 0.55f).roundToInt()..(image.width * 0.90f).roundToInt()
            val titleSample = AcceptanceTestSupport.sampleAverageColor(
                image,
                xRange,
                title.top.roundToInt()..title.bottom.roundToInt(),
            )
            val lowerTop = title.bottom.roundToInt()
            val lowerBottom = minOf(
                title.bottom + fadeExtensionPx,
                maxOf(title.bottom + 2f, firstItemTop - 2f),
            ).roundToInt()
            val fadeBandSample = AcceptanceTestSupport.sampleAverageColor(
                image,
                xRange,
                lowerTop..lowerBottom,
            )
            return titleSample to fadeBandSample
        }
        fun scrollPosition(): Float {
            val range = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            return compose.runOnIdle { range.value() }
        }

        val (restTitleSample, restFadeSample) = sampleHeaderBands()
        list.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("Test must genuinely scroll away from the top", scrollPosition() > 0f)
        val (scrolledTitleSample, scrolledFadeSample) = sampleHeaderBands()
        val scrolledDelta = maxOf(
            AcceptanceTestSupport.colorDistance(restTitleSample, scrolledTitleSample),
            AcceptanceTestSupport.colorDistance(restFadeSample, scrolledFadeSample),
        )
        assertTrue(
            "Scrolled header/fade band should reflect scrolled content beneath it (delta=$scrolledDelta)",
            scrolledDelta > 4.0,
        )

        list.performScrollToIndex(0)
        compose.waitForIdle()
        assertEquals("List must return exactly to the top", 0f, scrollPosition(), 0f)
        assertEquals("The first item must recover its original resting frame", firstItemTop,
            firstItem.fetchSemanticsNode().boundsInRoot.top, 0f)

        val (restoredTitleSample, restoredFadeSample) = sampleHeaderBands()
        val restoredTitleDelta = AcceptanceTestSupport.colorDistance(restTitleSample, restoredTitleSample)
        val restoredFadeDelta = AcceptanceTestSupport.colorDistance(restFadeSample, restoredFadeSample)
        assertTrue(
            "Header backdrop did not restore cleanly to rest appearance (titleDelta=$restoredTitleDelta, fadeDelta=$restoredFadeDelta)",
            restoredTitleDelta <= 3.0 && restoredFadeDelta <= 3.0,
        )
    }

    @Test
    fun t2_r4_05_secondaryPagesUseBackLeading48DpAndZeroBottomBarOverlap_baseline() {
        val store = ThemeStore(AcceptanceTestSupport.InMemorySharedPreferences())
        val backStack = OeBackStack()
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Config,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = true,
                backStack = backStack,
                pages = AcceptanceTestSupport.samplePages(),
            )
        }
        compose.waitForIdle()

        compose.runOnUiThread {
            backStack.push(
                OeDestination("presets", "主题预设") {
                    val handle = rememberThemeStudioHandle(store)
                    ThemePresetScreen(
                        state = handle.state,
                        onEvent = handle.onEvent,
                        onBack = { backStack.pop() },
                    )
                },
            )
        }
        compose.waitForIdle()

        AcceptanceTestSupport.assertTouchTargetAtLeast48Dp(
            compose.onNodeWithTag("theme-studio:back", useUnmergedTree = true),
            compose.density,
            "theme-studio:back",
        )
        compose.onNodeWithTag("theme-presets:end").performScrollTo()
        compose.waitForIdle()

        val endBounds = compose.onNodeWithTag("theme-presets:end").fetchSemanticsNode().boundsInRoot
        val navBounds = compose.onNodeWithTag("shell:nav-bar").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Bottom end marker (${endBounds.bottom}) must clear nav-bar top (${navBounds.top})",
            endBounds.bottom <= navBounds.top + 1f,
        )
    }

    // =========================================================================
    // R5 Boundary Cases (5 tests)
    // =========================================================================

    @Test
    fun t2_r5_01_glassBlurSafetyStepsDown28To16To8ToL0AndStopsOnNonIae_baseline() {
        val tried1 = mutableListOf<Dp>()
        val r1 = Oe3GlassBlurSafety.resolveBlurRadius(28.dp) { r ->
            tried1 += r
            if (r == 28.dp) throw IllegalArgumentException("too big")
        }
        assertEquals(16.dp, r1)
        assertEquals(listOf(28.dp, 16.dp), tried1)

        val tried2 = mutableListOf<Dp>()
        val r2 = Oe3GlassBlurSafety.resolveBlurRadius(28.dp) { r ->
            tried2 += r
            if (r >= 16.dp) throw IllegalArgumentException("too big")
        }
        assertEquals(8.dp, r2)
        assertEquals(listOf(28.dp, 16.dp, 8.dp), tried2)

        val r3 = Oe3GlassBlurSafety.resolveBlurRadius(28.dp) {
            throw IllegalArgumentException("unsupported")
        }
        assertNull(r3)

        val nonFatalLogs = mutableListOf<Pair<Throwable, Dp>>()
        val tried4 = mutableListOf<Dp>()
        val r4 = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 28.dp,
            logNonFatal = { t, r -> nonFatalLogs += t to r },
        ) { r ->
            tried4 += r
            throw IllegalStateException("unexpected state")
        }
        assertNull(r4)
        assertEquals(listOf(28.dp), tried4)
        assertEquals(1, nonFatalLogs.size)
        assertEquals(28.dp, nonFatalLogs[0].second)
    }

    @Test
    fun t2_r5_02_glassFrameGateIgnoresIncompleteWindowAndExcessFrames_baseline() {
        var callbackCount = 0
        var lastDowngrade = false
        val gate = Oe3GlassFrameGate(budgetMs = 16.67) { v ->
            callbackCount++
            lastDowngrade = v.downgrade
        }

        repeat(59) {
            assertFalse("Frame ${it + 1} must not complete 60-frame window", gate.onFrame(50.0))
        }
        assertEquals(0, callbackCount)
        assertFalse(gate.isDone)

        assertTrue("60th frame must complete window", gate.onFrame(50.0))
        assertEquals(1, callbackCount)
        assertTrue(lastDowngrade)
        assertTrue(gate.isDone)

        repeat(5) {
            assertTrue("Excess frames must return true without re-firing", gate.onFrame(50.0))
        }
        assertEquals(1, callbackCount)
    }

    @Test
    fun t2_r5_03_glassAlwaysModeBypassesDeviceCapButRespectsHardGates_baseline() {
        val level = Oe3GlassStrategy.resolveLevel(GlassMode.Always, "none")
        assertEquals(Oe3GlassLevel.Ultra, level)

        // When Activity respects GlassMode.Always by passing DeviceCap.Unlimited, API 31+ uses blur
        val tierBypass = Oe3GlassStrategy.resolveTier(
            level = level,
            enabled = true,
            apiLevel = 32,
            isLowRamDevice = false,
            deviceCap = GlassDeviceCap.Unlimited,
        )
        assertEquals(GlassTier.L1, tierBypass)

        // Hard gates still force L0 even under Ultra/Always
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(level, enabled = false, apiLevel = 35, isLowRamDevice = false, deviceCap = GlassDeviceCap.Unlimited),
        )
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(level, enabled = true, apiLevel = 35, isLowRamDevice = true, deviceCap = GlassDeviceCap.Unlimited),
        )
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(level, enabled = true, apiLevel = 29, isLowRamDevice = false, deviceCap = GlassDeviceCap.Unlimited),
        )
    }

    @Test
    fun t2_r5_04_glassMismatchedLevelFallsBackToSolid_baseline() {
        compose.setContent {
            Oe3Theme(
                theme = ThemeCatalog.constructivistWorkstation,
                glassLevel = Oe3GlassLevel.Standard,
                reduceMotion = true,
            ) {
                // Deliberately request Ultra inside Standard theme -> must render Solid without crashing
                Oe3GlassSurface(
                    level = Oe3GlassLevel.Ultra,
                    modifier = Modifier.fillMaxWidth().height(64.dp).testTag("glass:mismatch"),
                    state = null,
                ) {
                    Text("Solid fallback")
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("glass:mismatch").assertIsDisplayed()
    }

    @Test
    fun t2_r5_05_motionSystemParsesCorruptScalesAndPredictiveBackCancelRestoresState_m3() {
        assertEquals(1.0f, Oe3MotionSystem.parseScale(null), 0.0001f)
        assertEquals(1.0f, Oe3MotionSystem.parseScale(""), 0.0001f)
        assertEquals(1.0f, Oe3MotionSystem.parseScale("abc"), 0.0001f)
        assertEquals(1.0f, Oe3MotionSystem.parseScale("-1"), 0.0001f)
        assertTrue(Oe3MotionSystem.isReducedMotion("0"))
        assertFalse(Oe3MotionSystem.isReducedMotion("0.5"))

        val backStack = OeBackStack()
        compose.setContent {
            Oe3App(
                selectedTab = Oe3Tab.Workstation,
                onTabSelected = {},
                theme = ThemeCatalog.constructivistWorkstation,
                reduceMotion = false,
                backStack = backStack,
                pages = Oe3Pages(workstation = { Box(Modifier.fillMaxSize().testTag("pbc:base")) }),
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            backStack.push(OeDestination("pbc", "PBC") { Box(Modifier.fillMaxSize().testTag("pbc:top")) })
        }
        compose.waitForIdle()

        val dispatcher = compose.activity.onBackPressedDispatcher
        val topPage = compose.onNodeWithTag("pbc:top")
        val restingBounds = topPage.fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(50f, 0f, 0.5f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        assertTrue("The top destination must move before the cancelled gesture",
            topPage.fetchSemanticsNode().boundsInRoot.left > restingBounds.left)
        compose.onNodeWithTag("pbc:base").assertExists()

        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()

        assertTrue("Cancelled predictive back must keep destination on stack", backStack.canPop)
        assertEquals(1, backStack.size)
        val topBounds = topPage.fetchSemanticsNode().boundsInRoot
        assertEquals(restingBounds, topBounds)
        compose.onNodeWithTag("pbc:base").assertDoesNotExist()
    }
}
