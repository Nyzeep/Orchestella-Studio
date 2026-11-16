package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.theme.ThemeColors

/**
 * The canonical Oe3 card: one tonal surface, 24dp corners, no outline and no
 * depth effect. Press feedback is a 10% ink wash, not a moving shadow.
 *
 * [content] is a [ColumnScope], so pages compose their own internal rhythm;
 * the primitive owns only the surface, clipping and padding.
 */
@Composable
fun Oe3Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Oe3Spacing.cardPadding),
    shape: Shape = RoundedCornerShape(Oe3Shapes.xl),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    val press = Oe3ThemeValues.motion.press
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressFraction by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(press.durationMillis, easing = press.easing),
        label = "oe3CardPress",
    )
    val fill = lerp(
        colors.surface.oe3Color(),
        ThemeColors.pressedFill(colors.surface, colors.onSurface).oe3Color(),
        pressFraction,
    )

    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
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
        content = content,
    )
}

/** 20dp grid tile used by the two-column distro and preset grids. */
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
        shape = RoundedCornerShape(Oe3Shapes.lg),
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
