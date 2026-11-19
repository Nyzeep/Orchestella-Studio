package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.design.tokens.OeMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.LocalOeSurfaceAlpha
import io.github.nyzeep.orchestella.ui.theme.ThemeColors

/**
 * Card corner resolution (UI-04): the radius is a decision, not a constant.
 * A caller-provided shape always wins; otherwise the theme's corner preference
 * ([LocalOe3CornerRadius], Theme Studio's 8–24dp choice) decides, and the
 * fallback stays the documented 24dp page-card step of the shape ladder.
 */
object Oe3CardSpec {
    fun radius(cornerRadius: Dp?): Dp = cornerRadius ?: Oe3Shapes.xl

    fun cornerShape(cornerRadius: Dp?): Shape = RoundedCornerShape(radius(cornerRadius))
}

/**
 * The canonical Oe3 card: one tonal surface, 24dp corners, no outline and no
 * depth effect.
 *
 * Press feedback (v4/UI-04) is the 10% ink wash on the spec clock: a 150ms
 * reveal delay, a 200ms tint fade-in, a 250ms release settle and a 200ms fade
 * for a wash interrupted before it settled — every duration comes from
 * [OeMotion], reduced motion collapses all of them through the injected
 * `motion.press` token.
 *
 * [title] renders inside the card as the first row (v4: a card's title lives
 * in the card, never as an outside section label). It participates in
 * [contentPadding]; pair it with [OeCardHeader] when the padding is zero.
 * [content] is a [ColumnScope], so pages compose their own internal rhythm;
 * the primitive owns only the surface, clipping and padding. A clickable card
 * is one accessibility node, so [title] and [content] must not add a second
 * click target.
 *
 * The default [shape] is not a fixed constant: it resolves through
 * [LocalOe3CornerRadius] so every card follows the Theme Studio radius
 * preference (fallback [Oe3Shapes.xl]). Pass [shape] explicitly to opt out.
 */
@Composable
fun Oe3Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Oe3Spacing.cardPadding),
    shape: Shape? = null,
    title: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedShape = shape ?: Oe3CardSpec.cornerShape(LocalOe3CornerRadius.current)
    val colors = Oe3ThemeValues.colors
    val press = Oe3ThemeValues.motion.press
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressFraction = remember { Animatable(0f) }
    LaunchedEffect(pressed) {
        val spec: TweenSpec<Float> = when {
            // Reduced motion lands instantly (token-driven zero duration).
            press.durationMillis == 0 -> tween(durationMillis = 0)
            pressed -> tween(
                durationMillis = OeMotion.CARD_PRESS_TINT_FADE_IN_MS,
                delayMillis = OeMotion.CARD_PRESS_REVEAL_DELAY_MS,
                easing = OeMotion.decelerateEasing,
            )
            pressFraction.value >= 1f -> tween(
                durationMillis = OeMotion.CARD_PRESS_RELEASE_MS,
                easing = OeMotion.decelerateEasing,
            )
            else -> tween(
                durationMillis = OeMotion.CARD_PRESS_INK_FADE_MS,
                easing = OeMotion.decelerateEasing,
            )
        }
        pressFraction.animateTo(if (pressed) 1f else 0f, spec)
    }
    val fill = lerp(
        colors.surface.oe3Color(),
        ThemeColors.pressedFill(colors.surface, colors.onSurface).oe3Color(),
        pressFraction.value,
    )

    Column(
        modifier = modifier
            .clip(resolvedShape)
            .background(fill.copy(alpha = fill.alpha * LocalOeSurfaceAlpha.current))
            .then(
                if (onClick != null) {
                    Modifier
                        .sizeIn(minWidth = Oe3Sizes.cardMinWidth, minHeight = Oe3Sizes.minTouchTarget)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            )
            .then(
                if (onClick != null) Modifier.semantics(mergeDescendants = true) {} else Modifier,
            )
            .padding(contentPadding),
    ) {
        title?.invoke()
        content()
    }
}

/**
 * The in-card title style: the Section scale (17sp / 22sp line height) at
 * weight 600 (UI-04). [OeCardHeader] renders it; pages that draw a card title
 * straight into the default 20dp card padding reuse it so the two stay
 * identical. Composition-aware: it follows the theme's base family wiring.
 */
@Composable
fun oeCardTitleStyle(): TextStyle =
    Oe3Typography.style(Oe3TextRole.Section).copy(fontWeight = FontWeight.SemiBold)

/**
 * In-card title row (UI-04): 16dp inner padding, a 17/22 w600 title in the
 * primary ink, an optional subtitle in the secondary ink and an optional
 * trailing slot aligned to the row's end. Designed as the [Oe3Card] `title`
 * slot content for zero-padding cards (grouped lists); a card with the
 * default 20dp padding renders its title directly with [oeCardTitleStyle]
 * instead, so the insets never stack.
 */
@Composable
fun OeCardHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(Oe3Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = oeCardTitleStyle(),
                color = colors.onSurface.oe3Color(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/** Shared card surface with the compact inset for two-column grids. */
@Composable
fun Oe3GridCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Oe3Card(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(Oe3Spacing.sm),
        content = content,
    )
}

/**
 * Section title that lives outside its card: 24dp above, 8dp below,
 * 17/22 medium in the secondary ink. An optional action sits on the same
 * baseline at the trailing edge.
 */
@Composable
fun Oe3SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Oe3Spacing.sectionTop, bottom = Oe3Spacing.sectionBottom),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = Oe3Typography.style(Oe3TextRole.Section),
            color = colors.onSurfaceVariant.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}
