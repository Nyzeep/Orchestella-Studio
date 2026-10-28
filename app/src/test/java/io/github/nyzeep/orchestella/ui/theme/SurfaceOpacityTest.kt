package io.github.nyzeep.orchestella.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure JVM contract for the UI surface opacity slider (W-C.2). */
class SurfaceOpacityTest {

    @Test
    fun clampPercent_bounds() {
        assertEquals(70, SurfaceOpacity.clampPercent(69))
        assertEquals(100, SurfaceOpacity.clampPercent(100))
        assertEquals(100, SurfaceOpacity.clampPercent(101))
        assertEquals(70, SurfaceOpacity.clampPercent(Int.MIN_VALUE))
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
    fun wcagContrast_blackOnWhiteIs21() {
        val ratio = SurfaceOpacity.wcagContrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(21.0, ratio, 0.1)
    }

    @Test
    fun resolveEffectivePercent_darkSurfaceOverBlackKeepsFloorReachable() {
        val effective = SurfaceOpacity.resolveEffectivePercent(
            0xFF16171D.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 70)
        assertEquals(70, effective)
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
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF757575.toInt(), 70)
        assertEquals(70, effective)
    }
}
