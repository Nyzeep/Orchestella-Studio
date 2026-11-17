package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing

/**
 * Grouped-list container (UI-04): one clipped 24dp surface holding square
 * rows. The outer radius ([Oe3Shapes.groupedOuter]) belongs to the container,
 * so the first row exposes the top corners, the last row the bottom corners
 * and the middle rows stay straight — the shape.md grouped rule; rows never
 * carry their own radius. Compose the rows with [OeListGroupRow] (its 10dp
 * vertical rhythm is this component's documented row spec) and let the last
 * row drop its divider; the hairline runs to the container edge.
 */
@Composable
fun OeListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Oe3Shapes.groupedOuter))
            .background(colors.surface.oe3Color()),
        content = content,
    )
}

/** Grouped-row vertical rhythm (UI-04): the FlClash list row's 16/10 padding. */
private val GroupRowVerticalPadding = 10.dp

/**
 * A grouped-list row: minimum 56dp height, 10dp vertical rhythm and the 16dp
 * horizontal inset of [Oe3Spacing.rowHorizontal]. Clickable rows merge into
 * one accessibility node, so [trailing] must stay decorative. The row itself
 * stays square — the [OeListGroup] container owns the corners.
 */
@Composable
fun OeListGroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(role = Role.Button, onClick = onClick)
                        .semantics(mergeDescendants = true) {}
                } else {
                    Modifier
                },
            ),
    ) {
        Oe3RowBody(
            title = title,
            subtitle = subtitle,
            leading = leading,
            trailing = trailing,
            verticalPadding = GroupRowVerticalPadding,
        )
        if (showDivider) {
            Oe3Divider(
                startIndent = Oe3DividerSpec.startIndent(hasLeading = leading != null),
                endIndent = Oe3Sizes.dividerEnd,
            )
        }
    }
}
