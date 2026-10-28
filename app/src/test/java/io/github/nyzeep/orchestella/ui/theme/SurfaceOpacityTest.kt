package io.github.nyzeep.orchestella.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM contract for the UI surface opacity slider (session 18, floor lowered in session 19). */
class SurfaceOpacityTest {

    @Test
    fun clampPercent_bounds() {
        // Session 19 (T-B.1): the floor moved from 70 to 55; 69 is now a legal in-range value.
        assertEquals(55, SurfaceOpacity.clampPercent(54))
        assertEquals(69, SurfaceOpacity.clampPercent(69))
        assertEquals(100, SurfaceOpacity.clampPercent(100))
        assertEquals(100, SurfaceOpacity.clampPercent(101))
        assertEquals(55, SurfaceOpacity.clampPercent(Int.MIN_VALUE))
        assertEquals(100, SurfaceOpacity.clampPercent(Int.MAX_VALUE))
        assertEquals(100, SurfaceOpacity.clampPercent(SurfaceOpacity.DEFAULT_PERCENT))
    }

    @Test
    fun applyOpacity_fullPercentKeepsOpaqueColor() {
        val surface = 0xFF16171D.toInt()
        assertEquals(surface, SurfaceOpacity.applyOpacity(surface, 100))
    }

    @Test
    fun applyOpacity_seventyPercentScalesAlphaKeepsRgb() {
        val applied = SurfaceOpacity.applyOpacity(0xFF16171D.toInt(), 70)
        assertEquals(178, (applied ushr 24) and 0xFF)
        assertEquals(0x16171D, applied and 0x00FFFFFF)
    }

    @Test
    fun applyOpacity_fiftyFivePercentScalesAlphaKeepsRgb() {
        // 55 * 255 / 100 = 140.25 -> 140; the RGB channel must survive untouched.
        val applied = SurfaceOpacity.applyOpacity(0xFF16171D.toInt(), 55)
        assertEquals(140, (applied ushr 24) and 0xFF)
        assertEquals(0x16171D, applied and 0x00FFFFFF)
    }

    @Test
    fun applyOpacity_belowFloorClampsToFiftyFive() {
        val applied = SurfaceOpacity.applyOpacity(0xFF16171D.toInt(), 10)
        assertEquals(140, (applied ushr 24) and 0xFF)
    }

    @Test
    fun wcagContrast_blackOnWhiteIs21() {
        val ratio = SurfaceOpacity.wcagContrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(21.0, ratio, 0.1)
    }

    @Test
    fun resolveEffectivePercent_darkSurfaceOverBlackKeepsFloorReachable() {
        val effective = SurfaceOpacity.resolveEffectivePercent(
            0xFF16171D.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 55)
        assertEquals(55, effective)
    }

    @Test
    fun resolveEffectivePercent_darkSurfaceRequestedMaxStaysMax() {
        val effective = SurfaceOpacity.resolveEffectivePercent(
            0xFF16171D.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 100)
        assertEquals(100, effective)
    }

    @Test
    fun resolveEffectivePercent_failingComboFallsBackToFloor() {
        // Light surface over a dark behind with dark text never reaches 4.5:1; the floor must win.
        val effective = SurfaceOpacity.resolveEffectivePercent(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF757575.toInt(), 55)
        assertEquals(55, effective)
    }

    @Test
    fun resolveSurfaceColor_solidPageKeepsRequestedPercentWhenContrastHolds() {
        // Dark surface over the dark page background with light text: 85% is safe as requested.
        val color = SurfaceOpacity.resolveSurfaceColor(
            0xFF16171D.toInt(), 0xFF0B0C0F.toInt(), 0xFFF5F4F0.toInt(), 85, hasBackdrop = false)
        assertEquals(SurfaceOpacity.applyOpacity(0xFF16171D.toInt(), 85), color)
    }

    @Test
    fun resolveSurfaceColor_solidPageFallsBackToFloorOnContrastFailure() {
        // White surface, dark page, mid-grey text: any transparency breaks 4.5:1, so the floor wins.
        val color = SurfaceOpacity.resolveSurfaceColor(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF757575.toInt(), 55, hasBackdrop = false)
        assertEquals(SurfaceOpacity.applyOpacity(0xFFFFFFFF.toInt(), 55), color)
    }

    @Test
    fun resolveSurfaceColor_backdropSkipsContrastFloor() {
        // With a wallpaper behind, the scrim owns legibility: the requested percent is painted.
        val color = SurfaceOpacity.resolveSurfaceColor(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF757575.toInt(), 55, hasBackdrop = true)
        assertEquals(SurfaceOpacity.applyOpacity(0xFFFFFFFF.toInt(), 55), color)
    }

    @Test
    fun resolveSurfaceColor_neverExceedsOneHundredOrDropsBelowFloor() {
        val tooLow = SurfaceOpacity.resolveSurfaceColor(
            0xFF16171D.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1, hasBackdrop = true)
        val alpha = (tooLow ushr 24) and 0xFF
        assertTrue("alpha $alpha must stay at or above the 55% floor", alpha >= 140)
    }
}
