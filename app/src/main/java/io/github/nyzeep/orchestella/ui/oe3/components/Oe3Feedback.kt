package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/** Pure arc math so the ring's 0/50/100 behaviour is testable off-device. */
object Oe3RingSpec {
    const val START_ANGLE = -90f
    const val FULL_SWEEP = 360f

    fun clamp(progress: Float): Float = when {
        !progress.isFinite() -> 0f
        else -> progress.coerceIn(0f, 1f)
    }

    fun sweep(progress: Float): Float = clamp(progress) * FULL_SWEEP
}

/**
 * A Canvas progress ring. [diameter] and [ringWidth] size the geometry; the
 * optional [content] slot carries the centre readout. Set [contentDescription]
 * to expose the value to accessibility services; a purely decorative ring
 * leaves it null.
 */
@Composable
fun Oe3Ring(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 96.dp,
    ringWidth: Dp = 10.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentDescription: String? = null,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    val clamped = Oe3RingSpec.clamp(progress)
    val description = contentDescription
    Box(
        modifier = modifier
            .size(diameter)
            .then(
                if (description != null) {
                    Modifier.semantics {
                        this.contentDescription = description
                        progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val widthPx = ringWidth.toPx()
            val inset = widthPx / 2f
            val arcSize = Size(size.width - widthPx, size.height - widthPx)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = Oe3RingSpec.START_ANGLE,
                sweepAngle = Oe3RingSpec.FULL_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = widthPx, cap = StrokeCap.Round),
            )
            if (clamped > 0f) {
                drawArc(
                    color = color,
                    startAngle = Oe3RingSpec.START_ANGLE,
                    sweepAngle = Oe3RingSpec.sweep(clamped),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = widthPx, cap = StrokeCap.Round),
                )
            }
        }
        content?.invoke(this)
    }
}

/**
 * Empty-state block: optional graphic, title, one-sentence explanation and an
 * optional action. Centred by default because it replaces a whole list.
 */
@Composable
fun Oe3EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    graphic: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = Oe3ThemeValues.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Oe3Spacing.xxl, vertical = Oe3Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
    ) {
        graphic?.invoke()
        Text(
            text = title,
            style = Oe3Typography.style(Oe3TextRole.Section),
            color = colors.onSurface.oe3Color(),
            textAlign = TextAlign.Center,
        )
        Text(
            text = description,
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurfaceVariant.oe3Color(),
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** Row-start/end insets for the in-card divider, independent of layout. */
object Oe3DividerSpec {
    fun startIndent(hasLeading: Boolean): Dp =
        if (hasLeading) Oe3Sizes.dividerStart else Oe3Spacing.rowHorizontal

    fun endIndent(): Dp = Oe3Sizes.dividerEnd
}

/**
 * 0.75dp rule at 14% ink. The default start inset clears a 24dp leading icon
 * and its 16dp gap; callers without a leading slot use [Oe3DividerSpec].
 */
@Composable
fun Oe3Divider(
    modifier: Modifier = Modifier,
    startIndent: Dp = Oe3Sizes.dividerStart,
    endIndent: Dp = Oe3Sizes.dividerEnd,
) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startIndent, end = endIndent),
        thickness = Oe3Sizes.dividerThickness,
        color = Oe3ThemeValues.colors.divider.oe3Color(),
    )
}
