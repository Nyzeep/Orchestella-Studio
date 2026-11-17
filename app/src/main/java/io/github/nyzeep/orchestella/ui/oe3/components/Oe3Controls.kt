package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3ColorTokens
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

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

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(fill.oe3Color().copy(alpha = if (enabled) 1f else 0.4f))
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                        .semantics { this.selected = selected }
                } else {
                    Modifier
                },
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
 * three meet the 48dp interactive floor; Pill and Tonal are full capsules,
 * Text uses the 12dp inline radius.
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

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(paint.shape)
            .background(
                paint.fill.copy(alpha = if (enabled) paint.fill.alpha else paint.fill.alpha * 0.4f),
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = Oe3Sizes.interactiveMinHeight(Oe3Sizes.button))
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
