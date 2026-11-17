package io.github.nyzeep.orchestella.ui.design.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the gradient-slider contract that both renderers share:
 * the value mapping, the step snapping and the glow/dot constants chosen by
 * the UI-05 prototype (see the ticket report). The animation itself is not
 * screenshot-asserted; these numbers are the stable part.
 */
class OeSliderSpecTest {

    @Test
    fun specNumbersMatchTheTicket() {
        assertEquals(16f, OeSliderSpec.TrackHeightDp, 0f)
        assertEquals(24f, OeSliderSpec.ThumbDiameterDp, 0f)
        assertEquals(4_000, OeSliderSpec.GlowPeriodMillis)
        assertEquals(0.10f, OeSliderSpec.GlowAlphaMin, 0.0001f)
        assertEquals(0.16f, OeSliderSpec.GlowAlphaMax, 0.0001f)
        assertEquals(90f, OeSliderSpec.GlowBandDp, 0f)
        assertEquals(0.25f, OeSliderSpec.DotAlpha, 0.0001f)
        assertEquals(0.4f, OeSliderSpec.DisabledAlpha, 0.0001f)
        assertEquals(0.92f, OeSliderSpec.PressedScale, 0.0001f)
        assertTrue(OeSliderSpec.DotCount in 6..10)
        assertTrue(OeSliderSpec.DotDiameterDp in 1.5f..2f)
    }

    @Test
    fun fractionClampsToTheRange() {
        assertEquals(0f, OeSliderSpec.fractionOf(-10f, 0f..100f), 0.0001f)
        assertEquals(1f, OeSliderSpec.fractionOf(140f, 0f..100f), 0.0001f)
        assertEquals(0.25f, OeSliderSpec.fractionOf(25f, 0f..100f), 0.0001f)
    }

    @Test
    fun valueAtClampsAndMapsBack() {
        assertEquals(50f, OeSliderSpec.valueAt(0f, 50f..150f), 0.0001f)
        assertEquals(150f, OeSliderSpec.valueAt(1f, 50f..150f), 0.0001f)
        assertEquals(100f, OeSliderSpec.valueAt(0.5f, 50f..150f), 0.0001f)
    }

    @Test
    fun continuousSlidersDoNotSnap() {
        assertEquals(37.4f, OeSliderSpec.snap(37.4f, 0f..100f, steps = 0), 0.0001f)
    }

    @Test
    fun steppedSlidersSnapToTheStepGrid() {
        // steps = 7 over 0..8 means eight intervals, i.e. integer stops.
        assertEquals(3f, OeSliderSpec.snap(3.4f, 0f..8f, steps = 7), 0.0001f)
        assertEquals(4f, OeSliderSpec.snap(3.6f, 0f..8f, steps = 7), 0.0001f)
        // steps = 4 over 0..10 means five intervals of 2.
        assertEquals(4f, OeSliderSpec.snap(3.2f, 0f..10f, steps = 4), 0.0001f)
        assertEquals(10f, OeSliderSpec.snap(9.4f, 0f..10f, steps = 4), 0.0001f)
    }

    @Test
    fun glowAlphaRunsMinToMaxAndBack() {
        assertEquals(OeSliderSpec.GlowAlphaMin, OeSliderSpec.glowAlpha(0f), 0.0001f)
        assertEquals(OeSliderSpec.GlowAlphaMax, OeSliderSpec.glowAlpha(0.5f), 0.0001f)
        assertEquals(OeSliderSpec.GlowAlphaMin, OeSliderSpec.glowAlpha(1f), 0.0001f)
        assertTrue(OeSliderSpec.glowAlpha(0.25f) > OeSliderSpec.GlowAlphaMin)
        assertTrue(OeSliderSpec.glowAlpha(0.25f) < OeSliderSpec.GlowAlphaMax)
    }

    @Test
    fun dotPhaseWrapsAndStaysEvenlySpaced() {
        assertEquals(0f, OeSliderSpec.dotPhase(0f, 0), 0.0001f)
        assertEquals(1f / OeSliderSpec.DotCount, OeSliderSpec.dotPhase(0f, 1), 0.0001f)
        assertEquals(0f, OeSliderSpec.dotPhase(1f, 0), 0.0001f)
        val wrapped = OeSliderSpec.dotPhase(0.9f, 2)
        assertTrue(wrapped in 0f..1f)
    }

    @Test
    fun dotJitterIsSeededAndStable() {
        val first = OeSliderSpec.dotJitter(0x0E3, OeSliderSpec.DotCount)
        val second = OeSliderSpec.dotJitter(0x0E3, OeSliderSpec.DotCount)
        assertEquals(first, second)
        assertEquals(OeSliderSpec.DotCount, first.size)
        assertTrue(first.all { it in -1f..1f })
    }
}
