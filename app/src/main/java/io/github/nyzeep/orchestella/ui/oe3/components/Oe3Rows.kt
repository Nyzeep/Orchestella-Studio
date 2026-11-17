package io.github.nyzeep.orchestella.ui.oe3.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/**
 * A 56dp list row. The leading slot is a 24dp box followed by the 16dp
 * icon-to-text gap, so the default divider start (56dp) lines up with the
 * title edge. [showDivider] lets the caller leave the last row in a group
 * undivided without tracking indexes.
 *
 * A clickable row reads as one accessibility node; [trailing] must therefore
 * be decorative, not a second control.
 */
@Composable
fun Oe3ListRow(
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
        )
        if (showDivider) {
            Oe3Divider(
                startIndent = Oe3DividerSpec.startIndent(hasLeading = leading != null),
                endIndent = Oe3Sizes.dividerEnd,
            )
        }
    }
}

/**
 * A switch row. The whole 56dp row is the toggle target and carries
 * [Role.Switch]; the switch drawing itself is inert so a screen reader sees
 * exactly one control. [leading] is decorative and must not be interactive.
 */
@Composable
fun Oe3SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    showDivider: Boolean = false,
) {
    val colors = Oe3ThemeValues.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Oe3RowBody(
            title = title,
            subtitle = subtitle,
            leading = leading,
            modifier = Modifier
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .semantics(mergeDescendants = true) {},
            trailing = {
                Switch(
                    checked = checked,
                    onCheckedChange = null,
                    enabled = enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onPrimary.oe3Color(),
                        checkedTrackColor = colors.primary.oe3Color(),
                        uncheckedThumbColor = colors.onSurfaceVariant.oe3Color(),
                        uncheckedTrackColor = colors.surfaceHigh.oe3Color(),
                    ),
                )
            },
        )
        if (showDivider) {
            Oe3Divider(
                startIndent = Oe3DividerSpec.startIndent(hasLeading = leading != null),
                endIndent = Oe3Sizes.dividerEnd,
            )
        }
    }
}

/** Shared title/subtitle/slot geometry for both row variants. */
@Composable
private fun Oe3RowBody(
    title: String,
    subtitle: String?,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Oe3Sizes.interactiveMinHeight(Oe3Spacing.rowHeight))
            .padding(horizontal = Oe3Spacing.rowHorizontal, vertical = Oe3Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier.size(Oe3Sizes.icon),
                contentAlignment = Alignment.Center,
            ) {
                leading()
            }
            Spacer(Modifier.width(Oe3Spacing.iconTextGap))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = Oe3Typography.style(Oe3TextRole.BodyStrong),
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
        if (trailing != null) {
            Spacer(Modifier.width(Oe3Spacing.sm))
            trailing()
        }
    }
}
