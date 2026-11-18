package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.LocalOe3CornerRadius
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeColors

/** 强调色页 / 根页共用的 8 色板 + 「跟随主题」。 */
@Composable
fun AccentSection(customAccent: Int?, onEvent: (ThemeStudioEvent) -> Unit) {
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.xl
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(12.dp),
        shape = RoundedCornerShape(radius),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.ui3_theme_accent_title),
                modifier = Modifier.weight(1f),
                style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                color = colors.onSurface.oe3Color(),
            )
            Oe3Chip(
                label = stringResource(R.string.ui3_theme_accent_follow),
                selected = customAccent == null,
                onClick = { onEvent(ThemeStudioEvent.SetCustomAccent(null)) },
                modifier = Modifier.testTag("theme-accent:follow"),
            )
        }
        Spacer(Modifier.height(8.dp))
        ThemeStudioSpec.accentSwatches.chunked(4).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { argb ->
                    AccentSwatch(
                        argb = argb,
                        selected = customAccent == argb,
                        onClick = { onEvent(ThemeStudioEvent.SetCustomAccent(argb)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AccentSwatch(
    argb: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentDescription = stringResource(R.string.ui3_theme_accent_desc, hex(argb))
    Box(
        modifier = modifier
            .height(48.dp)
            .testTag("theme-accent:${hex(argb)}")
            .semantics {
                this.selected = selected
                contentDescription = accentDescription
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(argb)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                CheckMark(
                    Color(ThemeColors.onColor(argb)).copy(alpha = 0.85f),
                )
            }
        }
    }
}

/**
 * 自定义配色 3 组的编辑入口。旧 XML 页 `btn_pick_custom_theme` 的落点：
 * 预设页与强调色页都提供同一入口，编辑仍是 [CustomSlotEditorDialog] 对话框。
 */
@Composable
fun CustomSlotSection(
    slots: List<CustomThemeSlot?>,
    activeId: String,
    fallbackTheme: io.github.nyzeep.orchestella.ui.theme.AppTheme,
    onEvent: (ThemeStudioEvent) -> Unit,
) {
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    val colors = Oe3ThemeValues.colors
    val radius = LocalOe3CornerRadius.current ?: Oe3Shapes.xl
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(radius),
    ) {
        slots.forEachIndexed { index, slot ->
            if (index > 0) OptionDivider()
            val active = activeId == "custom:${index + 1}"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("theme-slot:$index")
                    .clickable(role = Role.Button) { editingSlot = index }
                    .padding(horizontal = Oe3Spacing.rowHorizontal, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (slot != null) {
                    Row(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(OeShape.xs)),
                    ) {
                        listOf(slot.background, slot.surface, slot.primary, slot.accent).forEach {
                            Box(Modifier.weight(1f).fillMaxSize().background(Color(it)))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(OeShape.xs))
                            .background(colors.surfaceHigh.oe3Color()),
                    )
                }
                Spacer(Modifier.width(Oe3Spacing.iconTextGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.ui3_theme_slot, index + 1),
                        style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                        color = colors.onSurface.oe3Color(),
                        maxLines = 1,
                    )
                    Text(
                        text = when {
                            slot == null -> stringResource(R.string.ui3_theme_slot_unset)
                            active -> "${slot.name} · ${stringResource(R.string.ui3_theme_slot_current)}"
                            else -> slot.name
                        },
                        style = MetaTextStyle,
                        color = colors.onSurfaceVariant.oe3Color(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.ui3_theme_slot_edit),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.primary.oe3Color(),
                )
            }
        }
    }

    editingSlot?.let { index ->
        CustomSlotEditorDialog(
            index = index,
            existing = slots.getOrNull(index),
            fallback = fallbackTheme,
            onDismiss = { editingSlot = null },
            onSave = { slot, apply ->
                onEvent(ThemeStudioEvent.SaveCustomSlot(index, slot, apply))
                editingSlot = null
            },
        )
    }
}
