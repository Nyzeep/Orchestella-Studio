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
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/**
 * A compact filter/selection chip. The visual height is 32dp; the Material
 * minimum interactive size expands the hit target to 48dp without inflating
 * the drawn surface.
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
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(
                if (selected) Modifier.semantics { this.selected = true } else Modifier,
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
 * three are 48dp tall and fully rounded except Text, which uses the 12dp
 * inline radius.
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
    val colors = Oe3ThemeValues.colors
    val fill: Color = when (style) {
        Oe3ButtonStyle.Pill -> colors.primary.oe3Color()
        Oe3ButtonStyle.Tonal -> colors.surfaceHigh.oe3Color()
        Oe3ButtonStyle.Text -> Color.Transparent
    }
    val ink: Color = when (style) {
        Oe3ButtonStyle.Pill -> colors.onPrimary.oe3Color()
        Oe3ButtonStyle.Tonal -> colors.primary.oe3Color()
        Oe3ButtonStyle.Text -> colors.primary.oe3Color()
    }
    val shape: Shape = when (style) {
        Oe3ButtonStyle.Text -> RoundedCornerShape(Oe3Shapes.sm)
        else -> Oe3Shapes.pill
    }
    val padding = when (style) {
        Oe3ButtonStyle.Text -> PaddingValues(horizontal = Oe3Spacing.sm, vertical = 0.dp)
        else -> PaddingValues(horizontal = Oe3Spacing.lg, vertical = 0.dp)
    }

    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(fill.copy(alpha = if (enabled) fill.alpha else fill.alpha * 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = Oe3Sizes.button)
            .padding(padding),
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
            color = ink.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
