package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3DialogEnterMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot

private data class SlotRole(
    @StringRes val labelRes: Int,
    val read: (CustomThemeSlot) -> Int,
    val write: (CustomThemeSlot, Int) -> CustomThemeSlot,
)

private val slotRoles = listOf(
    SlotRole(R.string.ui3_theme_role_background, { it.background }, { slot, c -> slot.copy(background = c) }),
    SlotRole(R.string.ui3_theme_role_surface, { it.surface }, { slot, c -> slot.copy(surface = c) }),
    SlotRole(R.string.ui3_theme_role_primary, { it.primary }, { slot, c -> slot.copy(primary = c) }),
    SlotRole(R.string.ui3_theme_role_accent, { it.accent }, { slot, c -> slot.copy(accent = c) }),
    SlotRole(R.string.ui3_theme_role_text, { it.text }, { slot, c -> slot.copy(text = c) }),
    SlotRole(R.string.ui3_theme_role_text_dim, { it.textDim }, { slot, c -> slot.copy(textDim = c) }),
    SlotRole(R.string.ui3_theme_role_terminal_background, { it.terminalBackground }, { slot, c -> slot.copy(terminalBackground = c) }),
    SlotRole(R.string.ui3_theme_role_terminal_foreground, { it.terminalForeground }, { slot, c -> slot.copy(terminalForeground = c) }),
)

/**
 * Eight-role custom palette editor. It uses the existing ThemeStore slot
 * setter and never edits the active theme id unless the user taps
 * "保存并应用". The same 8-colour board is used for every role, mirroring the
 * legacy appearance dialog's interaction without copying its private code.
 */
@Composable
fun CustomSlotEditorDialog(
    index: Int,
    existing: CustomThemeSlot?,
    fallback: AppTheme,
    onDismiss: () -> Unit,
    onSave: (CustomThemeSlot, apply: Boolean) -> Unit,
) {
    val fallbackName = stringResource(R.string.ui3_theme_slot, index + 1)
    val initial = existing ?: CustomThemeSlot(
        name = fallbackName,
        isDark = fallback.isDark,
        background = fallback.background,
        surface = fallback.surface,
        primary = fallback.primary,
        accent = fallback.accent,
        text = fallback.text,
        textDim = fallback.textDim,
        terminalBackground = fallback.terminalBackground,
        terminalForeground = fallback.terminalForeground,
    )
    var name by remember { mutableStateOf(initial.name) }
    var isDark by remember { mutableStateOf(initial.isDark) }
    var slot by remember { mutableStateOf(initial) }
    var editingRole by remember { mutableIntStateOf(-1) }

    val colors = Oe3ThemeValues.colors
    // 对话框进场接 Oe3Motion.dialog（spec §2.5：250ms easeOutCubic，fade +
    // scale 0.96→1）。Material3 AlertDialog 本体不 fork：窗口仍是平台默认，
    // token 走内容层的进出场，减少动态时首帧即终态。
    Oe3DialogEnterMotion {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(OeShape.alert),
            title = {
                Text(
                    text = if (editingRole < 0) {
                        stringResource(R.string.ui3_theme_slot_dialog_title, index + 1)
                    } else {
                        stringResource(R.string.ui3_theme_slot_pick_title, stringResource(slotRoles[editingRole].labelRes))
                    },
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = Color(colors.onSurface),
                )
            },
            text = {
                if (editingRole < 0) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.ui3_theme_slot_name)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Oe3SwitchRow(
                            title = stringResource(R.string.ui3_theme_slot_dark),
                            subtitle = stringResource(R.string.ui3_theme_slot_dark_desc),
                            checked = isDark,
                            onCheckedChange = { isDark = it },
                        )
                        slotRoles.forEachIndexed { roleIndex, role ->
                            SlotColorRow(
                                label = stringResource(role.labelRes),
                                argb = role.read(slot),
                                onClick = { editingRole = roleIndex },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                slot = initial.copy(name = name, isDark = isDark)
                            },
                        ) {
                            Text(stringResource(R.string.ui3_theme_slot_reset))
                        }
                    }
                } else {
                    val role = slotRoles[editingRole]
                    val roleLabel = stringResource(role.labelRes)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(
                                R.string.ui3_theme_slot_current_color,
                                roleLabel,
                                hex(role.read(slot)),
                            ),
                            style = Oe3Typography.style(Oe3TextRole.Caption),
                            color = Color(colors.onSurfaceVariant),
                        )
                        ThemeStudioSpec.customSlotSwatches.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { argb ->
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(argb))
                                            .clickable {
                                                slot = role.write(slot, argb)
                                                editingRole = -1
                                            }
                                            .semantics { contentDescription = "$roleLabel ${hex(argb)}" },
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (editingRole < 0) {
                    Row {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.ui3_theme_cancel))
                        }
                        TextButton(
                            onClick = {
                                onSave(slot.copy(name = name.ifBlank { fallbackName }, isDark = isDark), false)
                            },
                        ) {
                            Text(stringResource(R.string.ui3_theme_slot_save))
                        }
                        TextButton(
                            onClick = {
                                onSave(slot.copy(name = name.ifBlank { fallbackName }, isDark = isDark), true)
                            },
                        ) {
                            Text(stringResource(R.string.ui3_theme_slot_save_apply), fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    TextButton(onClick = { editingRole = -1 }) { Text(stringResource(R.string.ui3_theme_back)) }
                }
            },
            dismissButton = if (editingRole < 0) {
                {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui3_theme_close)) }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun SlotColorRow(label: String, argb: Int, onClick: () -> Unit) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(argb)),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = Color(colors.onSurface),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = hex(argb),
            style = Oe3Typography.style(Oe3TextRole.Mono),
            color = Color(colors.onSurfaceVariant),
        )
    }
}

