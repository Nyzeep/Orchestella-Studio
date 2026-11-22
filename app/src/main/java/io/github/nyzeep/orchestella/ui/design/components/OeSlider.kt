package io.github.nyzeep.orchestella.ui.design.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import kotlin.math.roundToInt

/**
 * The one slider of the app: a 16dp capsule with a theme-derived
 * `primary → accent` fill, a slow 4s light sweep, eight drifting dots and a
 * 24dp white thumb. Hand-drawn on [Canvas] instead of forking Material's
 * `Slider`, whose internal layout reserves room for tick marks and a taller
 * thumb that cannot be overridden cleanly.
 *
 * The glow deliberately reuses one [Brush] for the whole lifetime of a size
 * (and one for the fill); only the canvas translation and the alpha change per
 * frame. Never move the brush construction into the draw lambda.
 */
@Composable
fun OeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    stateDescription: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = Oe3ThemeValues.colors
    val themeInfo = Oe3ThemeValues.info
    val pressMotion = Oe3ThemeValues.motion.press
    val haptics = LocalHapticFeedback.current

    val primary = remember(colors.primary) { Color(colors.primary) }
    val accent = remember(colors.accent) { Color(colors.accent) }
    val surfaceHigh = remember(colors.surfaceHigh) { Color(colors.surfaceHigh) }

    var widthPx by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    var laidOut by remember { mutableStateOf(false) }

    // W2-04: the ambient glow burns frames only while the slider can actually
    // show it — motion allowed, host lifecycle RESUMED, interactive (enabled
    // and not mid-drag) and laid out on screen. Any failed condition stops
    // every frame callback: the phase freezes where it is (it only starts on
    // the deterministic band when never run), so an off-screen or backgrounded
    // slider costs nothing and the band never jumps on a gate toggle.
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val glowRunning = !themeInfo.reducedMotion && resumed && enabled && !pressed && laidOut

    // Keep the phase as State and read it inside the draw lambda: the glow
    // animates without recomposing this composable on every frame. Restarting
    // a gated-off sweep resumes the remainder of the current cycle, so the
    // band never jumps; the 8-dot / 90dp geometry it drives is untouched.
    val phase = remember { Animatable(0.35f) }
    LaunchedEffect(glowRunning) {
        if (!glowRunning) return@LaunchedEffect
        while (true) {
            phase.animateTo(
                1f,
                // First segment covers the remainder of the cycle from the
                // resting band; full sweeps follow at the spec period.
                tween((OeSliderSpec.GlowPeriodMillis * (1f - phase.value)).toInt(), easing = LinearEasing),
            )
            phase.snapTo(0f)
        }
    }

    val pressedScale by animateFloatAsState(
        targetValue = if (pressed) OeSliderSpec.PressedScale else 1f,
        animationSpec = tween(
            durationMillis = pressMotion.durationMillis,
            easing = pressMotion.easing,
        ),
        label = "oe-slider-thumb-scale",
    )

    val density = LocalDensity.current
    val trackHeightPx = with(density) { OeSliderSpec.TrackHeightDp.dp.toPx() }
    val thumbRadiusPx = with(density) { (OeSliderSpec.ThumbDiameterDp / 2f).dp.toPx() }
    val dotRadiusPx = with(density) { (OeSliderSpec.DotDiameterDp / 2f).dp.toPx() }
    val bandPx = with(density) { OeSliderSpec.GlowBandDp.dp.toPx() }
    val controlHeightPx = with(density) { 48.dp.toPx() }

    val filledBrush = remember(widthPx, primary, accent) {
        Brush.horizontalGradient(
            colors = listOf(primary, accent),
            startX = 0f,
            endX = widthPx.coerceAtLeast(1f),
        )
    }
    val glowBrush = remember(bandPx) {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color.Transparent,
                0.5f to Color.White,
                1f to Color.Transparent,
            ),
            startX = 0f,
            endX = bandPx,
        )
    }
    val shadowBrush = remember(thumbRadiusPx) {
        Brush.radialGradient(
            colors = listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent),
            center = Offset(thumbRadiusPx, thumbRadiusPx),
            radius = thumbRadiusPx * 1.35f,
        )
    }
    val jitter = remember { OeSliderSpec.dotJitter(OeSliderSpec.DotSeed, OeSliderSpec.DotCount) }

    val fraction = OeSliderSpec.fractionOf(value, valueRange)
    val controlAlpha = if (enabled) 1f else OeSliderSpec.DisabledAlpha

    val trackPath = remember(widthPx, trackHeightPx, controlHeightPx) {
        val top = (controlHeightPx - trackHeightPx) / 2f
        Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = top,
                    right = widthPx.coerceAtLeast(1f),
                    bottom = top + trackHeightPx,
                    cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f),
                ),
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged {
                widthPx = it.width.toFloat()
                laidOut = it.width > 0 && it.height > 0
            }
            .semantics {
                if (stateDescription != null) this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                if (enabled) {
                    setProgress { target ->
                        onValueChange(OeSliderSpec.snap(target, valueRange, steps))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled, valueRange, steps) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    var lastStep = stepIndex(value, valueRange, steps)
                    fun emit(x: Float) {
                        // 拇指圆心被内缩一个半径：0%/100% 时圆不再被画布切掉一半，
                        // 触摸映射与绘制用同一段可用行程。
                        val travel = (size.width - 2f * thumbRadiusPx).coerceAtLeast(1f)
                        val target = OeSliderSpec.snap(
                            OeSliderSpec.valueAt(
                                ((x - thumbRadiusPx) / travel).coerceIn(0f, 1f),
                                valueRange,
                            ),
                            valueRange,
                            steps,
                        )
                        onValueChange(target)
                        val nextStep = stepIndex(target, valueRange, steps)
                        if (steps > 0 && nextStep != lastStep) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            lastStep = nextStep
                        }
                    }
                    emit(down.position.x)
                    down.consume()
                    var pressedChange = true
                    while (pressedChange) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull()
                        if (change == null || !change.pressed) {
                            pressedChange = false
                        } else {
                            emit(change.position.x)
                            change.consume()
                        }
                    }
                    pressed = false
                    onValueChangeFinished?.invoke()
                }
            },
    ) {
        val phaseValue = phase.value
        val glowAlpha = OeSliderSpec.glowAlpha(phaseValue)
        val trackTop = (size.height - trackHeightPx) / 2f
        val centerY = size.height / 2f
        val thumbTravel = (size.width - 2f * thumbRadiusPx).coerceAtLeast(1f)
        val thumbX = thumbRadiusPx + fraction * thumbTravel
        val filledWidth = thumbX.coerceAtLeast(0f)

        drawPath(trackPath, color = surfaceHigh, alpha = controlAlpha)

        if (filledWidth > 0f) {
            drawRoundRect(
                brush = filledBrush,
                topLeft = Offset(0f, trackTop),
                size = Size(filledWidth.coerceAtLeast(trackHeightPx), trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2f, trackHeightPx / 2f),
                alpha = controlAlpha,
            )
        }

        clipPath(trackPath) {
            val glowLeft = phaseValue * (size.width + bandPx) - bandPx
            translate(left = glowLeft) {
                drawRect(
                    brush = glowBrush,
                    topLeft = Offset(0f, trackTop),
                    size = Size(bandPx, trackHeightPx),
                    alpha = glowAlpha * controlAlpha,
                )
            }
            val maxJitter = ((trackHeightPx - 2f * dotRadiusPx) / 2f) * 0.6f
            repeat(OeSliderSpec.DotCount) { index ->
                val dotX = OeSliderSpec.dotPhase(phaseValue, index) * size.width
                val dotY = centerY + jitter[index] * maxJitter
                drawCircle(
                    color = Color.White,
                    radius = dotRadiusPx,
                    center = Offset(dotX, dotY),
                    alpha = OeSliderSpec.DotAlpha * controlAlpha,
                )
            }
        }

        drawCircle(
            brush = shadowBrush,
            radius = thumbRadiusPx * 1.35f * pressedScale,
            center = Offset(thumbX, centerY + 1.dp.toPx()),
            alpha = controlAlpha,
        )
        drawCircle(
            color = Color.White,
            radius = thumbRadiusPx * pressedScale,
            center = Offset(thumbX, centerY),
            alpha = controlAlpha,
        )
    }
}

private fun stepIndex(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Int {
    if (steps <= 0) return 0
    val intervals = steps + 1
    return (OeSliderSpec.fractionOf(value, range) * intervals).roundToInt()
}
