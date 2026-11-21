package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3ColorTokens
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.oe3.tokens.OeSpring

@Composable
private fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
): State<Float> {
    val motion = Oe3ThemeValues.motion
    val pressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (enabled && pressed) OeMotion.CONTROL_PRESS_SCALE else 1f,
        animationSpec = if (motion.press.durationMillis == 0) snap() else OeSpring.Press,
        label = "oe3ControlPress",
    )
}

/**
 * A compact filter/selection chip. The drawn surface is 32dp;
 * `minimumInteractiveComponentSize` reserves the 48dp layout box and the
 * platform expands the touch target at the input layer. [selected] is exposed
 * to accessibility services for every interactive chip, not only selected ones.
 */
@Composable
fun Oe3Chip(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = Oe3ThemeValues.colors
    val fill = if (selected) colors.primaryContainer else colors.surfaceHigh
    val ink = if (selected) colors.primary else colors.onSurfaceVariant
    val shape: Shape = RoundedCornerShape(Oe3Shapes.sm)
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction, enabled = enabled && onClick != null)

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onClick,
                        )
                        .semantics { this.selected = selected }
                } else {
                    Modifier
                },
            )
            .graphicsLayer {
                val scale = pressScale.value
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fill.oe3Color().copy(alpha = if (enabled) 1f else 0.4f))
            .then(
                if (onClick != null) Modifier.indication(interaction, LocalIndication.current) else Modifier,
            )
            .heightIn(min = Oe3Sizes.chip)
            .padding(horizontal = Oe3Spacing.sm, vertical = Oe3Spacing.xxs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Oe3Spacing.xs))
        }
        Text(
            text = label,
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = ink.oe3Color().copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

enum class Oe3ButtonStyle { Pill, Tonal, Text }

/**
 * The three action weights. Pill is the single primary action on a page,
 * Tonal is a secondary action, Text is a low-emphasis inline action. All
 * three reserve a 48dp hit area. The capsules paint a 44dp surface and the
 * inline action reserves 36dp for its text and feedback.
 */
@Composable
fun Oe3Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: Oe3ButtonStyle = Oe3ButtonStyle.Pill,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val paint = buttonPaint(style, Oe3ThemeValues.colors)
    val visualHeight = if (style == Oe3ButtonStyle.Text) Oe3Sizes.sectionActionVisual else Oe3Sizes.interactiveVisual
    val interaction = remember { MutableInteractionSource() }
    val pressScale = rememberPressScale(interaction, enabled = enabled)

    Row(
        modifier = modifier
            .widthIn(min = Oe3Sizes.minTouchTarget)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = (Oe3Sizes.button - visualHeight) / 2)
            .graphicsLayer {
                val scale = pressScale.value
                scaleX = scale
                scaleY = scale
            }
            .clip(paint.shape)
            .background(
                paint.fill.copy(alpha = if (enabled) paint.fill.alpha else paint.fill.alpha * 0.4f),
            )
            .indication(interaction, LocalIndication.current)
            .heightIn(min = visualHeight)
            .padding(paint.padding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Oe3Spacing.xs))
        }
        Text(
            text = text,
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = paint.ink.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class ButtonPaint(
    val fill: Color,
    val ink: Color,
    val shape: Shape,
    val padding: PaddingValues,
)

private fun buttonPaint(style: Oe3ButtonStyle, colors: Oe3ColorTokens): ButtonPaint = when (style) {
    Oe3ButtonStyle.Pill -> ButtonPaint(
        fill = colors.primary.oe3Color(),
        ink = colors.onPrimary.oe3Color(),
        shape = Oe3Shapes.pill,
        padding = PaddingValues(horizontal = Oe3Spacing.lg, vertical = 0.dp),
    )
    Oe3ButtonStyle.Tonal -> ButtonPaint(
        fill = colors.surfaceHigh.oe3Color(),
        ink = colors.primary.oe3Color(),
        shape = Oe3Shapes.pill,
        padding = PaddingValues(horizontal = Oe3Spacing.lg, vertical = 0.dp),
    )
    Oe3ButtonStyle.Text -> ButtonPaint(
        fill = Color.Transparent,
        ink = colors.primary.oe3Color(),
        shape = RoundedCornerShape(Oe3Shapes.sm),
        padding = PaddingValues(horizontal = Oe3Spacing.sm, vertical = 0.dp),
    )
}
