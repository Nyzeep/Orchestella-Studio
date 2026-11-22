package io.github.nyzeep.orchestella.ui.oe3

import android.provider.Settings
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ui.design.components.OeDialogAction
import io.github.nyzeep.orchestella.ui.design.components.OeDialogHost
import io.github.nyzeep.orchestella.ui.design.components.OeDialogSpec
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassDeviceCap
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassTier
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassBlurSafety
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassFrameGate
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassStrategy
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3LiquidGlass
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.screens.theme.GlassOption
import io.github.nyzeep.orchestella.ui.screens.theme.HomePreview
import io.github.nyzeep.orchestella.ui.screens.theme.NavStyleOption
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.nav.Oe3NavBar
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Tier-5 adversarial hardening tests targeting unchallenged areas from M4 handoff:
 * - OeDialogHost under system animator scale = 0f (ReduceMotion 0ms instant frame-1 size)
 * - Ultra-narrow Oe3NavBar layout (`cellWidthPx <= 0f`) crash guard
 * - FrameMetrics gate boundary & idempotency, plus blur non-IAE stop & L2 reducedMotion drop
 * - Oe3Button & Oe3Chip instant 0ms state stability under reduced motion
 * - HomePreview mid-tween reduceMotion flip convergence
 * - Oe3LiquidGlass invalid geometry bounds safety (Zero / NaN)
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
class Tier5HardeningTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun oeDialogHostWithSystemReduceMotionSnapsAtFullWidthOnFirstFrame() {
        val resolver = compose.activity.contentResolver
        val scaleSetting = Settings.Global.ANIMATOR_DURATION_SCALE
        val originalScale = Settings.Global.getString(resolver, scaleSetting)
        val host = OeDialogHost.attach(compose.activity)
        Settings.Global.putString(resolver, scaleSetting, "0")
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                Box(Modifier.fillMaxSize().background(Color.Magenta))
            }
            compose.mainClock.advanceTimeByFrame()

            fun measureWidth(bitmap: Bitmap): Int {
                val midY = bitmap.height / 2
                val bg = bitmap.getPixel(4, midY)
                val bgR = android.graphics.Color.red(bg)
                val bgG = android.graphics.Color.green(bg)
                val bgB = android.graphics.Color.blue(bg)
                var first = -1
                var last = -1
                for (x in 0 until bitmap.width) {
                    val px = bitmap.getPixel(x, midY)
                    val delta = abs(android.graphics.Color.red(px) - bgR) +
                        abs(android.graphics.Color.green(px) - bgG) +
                        abs(android.graphics.Color.blue(px) - bgB)
                    if (delta > 24) {
                        if (first < 0) first = x
                        last = x
                    }
                }
                return if (first < 0) 0 else (last - first + 1)
            }

            host.show(
                OeDialogSpec(
                    title = "减弱动态弹窗",
                    message = "第 1 帧即达到终态宽度",
                    positive = OeDialogAction("确定"),
                    testTag = "t5:dialog",
                ),
            )
            // Render Frame 0 attach before advancing frame clock
            val contentView = compose.activity.findViewById<android.view.View>(android.R.id.content)
            fun captureContent(): Bitmap {
                val bmp = Bitmap.createBitmap(contentView.width.coerceAtLeast(1), contentView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                contentView.draw(canvas)
                return bmp
            }
            captureContent()
            var earlyWidth = 0
            for (frame in 1..5) {
                compose.mainClock.advanceTimeByFrame()
                val w = measureWidth(captureContent())
                if (w > 0) {
                    earlyWidth = w
                    break
                }
            }
            repeat(30) { compose.mainClock.advanceTimeByFrame() }
            val settledWidth = measureWidth(captureContent())
            assertTrue("Dialog must render immediately under reduceMotion ($earlyWidth)", earlyWidth > 100)
            assertEquals("Reduced motion dialog must open at final width without scaling up", settledWidth, earlyWidth)
        } finally {
            Settings.Global.putString(resolver, scaleSetting, originalScale)
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread { host.dismiss() }
            compose.waitForIdle()
        }
    }

    @Test
    fun oe3NavBarZeroWidthDragGuardDoesNotCrash() {
        var selected by mutableStateOf(Oe3Tab.Workstation)
        compose.setContent {
            Oe3Theme(theme = ThemeCatalog.constructivistWorkstation) {
                // Width smaller than horizontal navPadding * 2 (20dp * 2 = 40dp) forces cellWidthPx <= 0f
                Box(Modifier.width(12.dp)) {
                    Oe3NavBar(
                        selected = selected,
                        onSelect = { selected = it },
                    )
                }
            }
        }
        compose.waitForIdle()
        val dock = compose.onNodeWithTag("shell:nav-bar")
        dock.performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(20f, 0f))
            up()
        }
        compose.waitForIdle()
        assertEquals(Oe3Tab.Workstation, selected)
    }

    @Test
    fun glassFrameGateAndTierAndShaderGeometryAdversarialBounds() {
        var verdicts = 0
        var downgraded = false
        val gate = Oe3GlassFrameGate(budgetMs = 16.67) { v ->
            verdicts++
            downgraded = v.downgrade
        }
        repeat(59) { assertFalse(gate.onFrame(25.0)) }
        assertEquals(0, verdicts)
        assertTrue(gate.onFrame(25.0))
        assertEquals(1, verdicts)
        assertTrue(downgraded)
        // Extra frames stay idempotent
        repeat(10) { assertTrue(gate.onFrame(25.0)) }
        assertEquals(1, verdicts)

        // Reduced motion drops L2 -> L0 at API 35
        assertEquals(
            GlassTier.L0,
            Oe3GlassStrategy.resolveTier(Oe3GlassLevel.Ultra, true, 35, false, GlassDeviceCap.Unlimited, reducedMotion = true),
        )

        // Non-IAE runtime failure stops blur probing immediately without walking fallback ladder
        val radiiTried = mutableListOf<androidx.compose.ui.unit.Dp>()
        var logged = false
        val chosen = Oe3GlassBlurSafety.resolveBlurRadius(
            requested = 28.dp,
            logNonFatal = { _, _ -> logged = true },
        ) { radius ->
            radiiTried += radius
            throw IllegalStateException("GPU driver error")
        }
        assertNull(chosen)
        assertTrue(logged)
        assertEquals(listOf(28.dp), radiiTried)

        // L2 liquid glass shader geometry boundary safety
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lg = Oe3LiquidGlass.create(Oe3Shapes.pill, androidx.compose.ui.unit.LayoutDirection.Ltr, isDark = true)
            if (lg != null) {
                assertNull(lg.effectFor(Size.Zero, Density(1f)))
                assertNull(lg.effectFor(Size(Float.NaN, 48f), Density(1f)))
            }
        }
    }

    @Test
    fun homePreviewMidTweenReduceMotionToggleAndControlsReducedMotionStayClean() {
        var theme by mutableStateOf(ThemeCatalog.mascotLight)
        var glass by mutableStateOf(GlassOption.None)
        var reduceMotion by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                Oe3Theme(theme = theme, reduceMotion = reduceMotion) {
                    Column(Modifier.fillMaxWidth()) {
                        HomePreview(
                            theme = theme,
                            pureBlack = false,
                            cornerDp = 16,
                            glass = glass,
                            navStyle = NavStyleOption.Floating,
                            reduceMotion = reduceMotion,
                        )
                        Oe3Button(
                            text = "确定",
                            onClick = {},
                            modifier = Modifier.testTag("t5:btn"),
                        )
                        Oe3Chip(
                            label = "标签",
                            onClick = {},
                            modifier = Modifier.testTag("t5:chip"),
                        )
                    }
                }
            }
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread {
                theme = ThemeCatalog.catppuccin
                glass = GlassOption.Standard
            }
            compose.mainClock.advanceTimeBy(90)
            // Flip reduceMotion mid-tween -> must snap cleanly without getting stuck
            compose.runOnUiThread { reduceMotion = true }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("theme-preview:home").assertIsDisplayed()
            compose.onNodeWithTag("t5:btn").performTouchInput { down(center); up() }
            compose.onNodeWithTag("t5:chip").performTouchInput { down(center); up() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
