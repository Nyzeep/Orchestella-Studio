package io.github.nyzeep.orchestella.ui.design.components

import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Values shared by the Compose [OeSlider] and the XML [OeSliderView], so the two
 * renderers cannot drift apart.
 *
 * The glow numbers come from the UI-05 throwaway prototype (2026-11-17):
 * three parameter sets were rasterised off-device at 16dp track height and the
 * 8-dot / 2dp / 90dp-band / 0.16 peak set was the only one that read as a slow
 * flow instead of speckle on the unfilled track. Do not retune these without
 * repeating that comparison — the ticket's screenshot goldens deliberately do
 * not assert the animation.
 */
object OeSliderSpec {

    /** Track height in dp. Shape.pill applies: the track is a full capsule. */
    const val TrackHeightDp = 16f

    /** White thumb diameter in dp (2dp shadow is drawn under it). */
    const val ThumbDiameterDp = 24f

    /** One linear glow sweep takes four seconds (ambient loop token, W1-02). */
    const val GlowPeriodMillis = OeMotion.GLOW_CYCLE_MS

    /** Glow alpha ramps min → max → min over one period. */
    const val GlowAlphaMin = 0.10f
    const val GlowAlphaMax = 0.16f

    /** Width of the moving highlight band, measured in dp. */
    const val GlowBandDp = 90f

    /** Spec allows 6–10; eight keeps the track lively without reading as noise. */
    const val DotCount = 8

    /** Dot diameter in dp (spec allows 1.5–2dp). */
    const val DotDiameterDp = 2f
    const val DotAlpha = 0.25f

    /** Whole control alpha while disabled. */
    const val DisabledAlpha = 0.4f

    /** Thumb scale while pressed (press-scale token, W1-02). */
    const val PressedScale = OeMotion.SLIDER_PRESS_SCALE

    /** Fixed seed: dot jitter must not wobble between frames or recompositions. */
    const val DotSeed = 0x0E3

    /** Maps a value onto 0..1 for the given range, clamped at both ends. */
    fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
        val span = range.endInclusive - range.start
        if (span <= 0f) return 0f
        return ((value - range.start) / span).coerceIn(0f, 1f)
    }

    /** Maps 0..1 back onto the range. */
    fun valueAt(fraction: Float, range: ClosedFloatingPointRange<Float>): Float {
        val clamped = fraction.coerceIn(0f, 1f)
        return range.start + (range.endInclusive - range.start) * clamped
    }

    /**
     * Material-style `steps`: the number of stops *between* the endpoints, so
     * `steps = 7` over 0..8 yields eight intervals of 1.
     */
    fun snap(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
        if (steps <= 0) return value.coerceIn(range.start, range.endInclusive)
        val intervals = steps + 1
        val fraction = fractionOf(value, range)
        val snapped = (fraction * intervals).roundToInt() / intervals.toFloat()
        return valueAt(snapped, range)
    }

    /** Triangular ramp: min at phase 0, max at 0.5, min again at 1. */
    fun glowAlpha(phase: Float): Float {
        val t = wrap(phase)
        val triangle = if (t <= 0.5f) t * 2f else (1f - t) * 2f
        return GlowAlphaMin + (GlowAlphaMax - GlowAlphaMin) * triangle
    }

    /** Dot index 0..DotCount-1 ride the same phase as the glow. */
    fun dotPhase(phase: Float, index: Int): Float =
        wrap(index / DotCount.toFloat() + phase)

    /**
     * Static per-dot offsets in -1..1, used for the vertical jitter and the
     * small spacing irregularity. Deterministic for a given seed.
     */
    fun dotJitter(seed: Int, count: Int): List<Float> {
        val random = Random(seed)
        return List(count) { random.nextFloat() * 2f - 1f }
    }

    private fun wrap(value: Float): Float {
        val wrapped = value % 1f
        return if (wrapped < 0f) wrapped + 1f else wrapped
    }
}
