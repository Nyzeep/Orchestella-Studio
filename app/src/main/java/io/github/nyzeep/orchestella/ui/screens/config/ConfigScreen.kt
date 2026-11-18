package io.github.nyzeep.orchestella.ui.screens.config

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ListRow
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SectionHeader
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3DialogEnterMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.screens.common.OeChevron
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.oeCardShape
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/** 页面标题下的元信息与「当前值」读数（spec §2.3：13/18）。 */
private val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** Live values the page needs besides [ConfigOptions]. */
@Immutable
data class ConfigScreenState(
    val versionName: String,
    /** One-line AVF/QEMU verdict for the 引擎与授权 row. */
    val engineSummary: String,
    /** Newer release cached by `UpdateCenter`, if any. */
    val updateAvailable: String? = null,
    /** Release-note summary shipped with [updateAvailable]. */
    val updateNote: String? = null,
    val options: ConfigOptions = ConfigOptions(),
)

/**
 * The 配置 page: five tonal group cards, a directory row per tool, and the
 * four persisted playability options.
 *
 * Structure rules from the ticket/spec: the page title is content (not a
 * toolbar), every group header sits outside its card, rows are Oe3's 56dp
 * rows (48dp interactive floor), and every chevron carries a description.
 * [onRoute] is the only navigation seam — the host activity owns Activity
 * starts and dialogs, so this screen stays previewable.
 */
@Composable
fun ConfigScreen(
    state: ConfigScreenState,
    onRoute: (ConfigRoute) -> Unit,
    onOptionsChange: (ConfigOptions) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    OeScaffold(
        title = stringResource(R.string.config_page_title),
        modifier = modifier,
        leading = onBack?.let { back ->
            { ConfigBackButton(onBack = back) }
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("config:screen"),
            contentPadding = PaddingValues(
                start = Oe3Spacing.pageInset,
                end = Oe3Spacing.pageInset,
                top = Oe3Spacing.xs,
                bottom = LocalOe3PageBottomPadding.current,
            ),
        ) {
            item(key = "config-header") {
                ConfigPageHeader(meta = stringResource(R.string.config_page_meta))
            }
            ConfigCatalog.groups().forEach { spec ->
                item(key = "config-header:${spec.group.name}") {
                    Oe3SectionHeader(title = stringResource(spec.group.titleRes))
                }
                item(key = "config-card:${spec.group.name}") {
                    Oe3Card(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp),
                        shape = oeCardShape(),
                    ) {
                        spec.entries.forEachIndexed { index, entry ->
                            ConfigRow(
                                entry = entry,
                                state = state,
                                showDivider = index < spec.entries.lastIndex || spec.group.hasOptions,
                                onClick = { onRoute(entry.route) },
                            )
                        }
                        if (spec.group.hasOptions) {
                            OptionLabel()
                            GroupOptions(
                                group = spec.group,
                                options = state.options,
                                onOptionsChange = onOptionsChange,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 页头只剩一行说明：标题在 OeScaffold 顶栏，返回入口在顶栏 leading 槽
 * （配置页被宿主当二级页打开时才有返回）。UI-02。
 */
@Composable
private fun ConfigPageHeader(meta: String) {
    val colors = Oe3ThemeValues.colors
    Text(
        text = meta,
        style = MetaTextStyle,
        color = colors.onSurfaceVariant.oe3Color(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Oe3Spacing.xs, bottom = Oe3Spacing.xxs),
    )
}

/** 顶栏 leading 槽里的返回钮；视觉 44dp，命中 48dp 由槽位保证。 */
@Composable
private fun ConfigBackButton(onBack: () -> Unit) {
    val back = stringResource(R.string.config_back_desc)
    Box(
        modifier = Modifier
            .size(Oe3Sizes.interactiveVisual)
            .testTag("config:back")
            .clickable(role = Role.Button, onClick = onBack)
            .semantics { contentDescription = back },
        contentAlignment = Alignment.Center,
    ) {
        DirectionChevron(pointsLeft = true)
    }
}

@Composable
private fun ConfigRow(
    entry: ConfigEntry,
    state: ConfigScreenState,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val title = stringResource(entry.titleRes)
    val colors = Oe3ThemeValues.colors
    Oe3ListRow(
        title = title,
        modifier = Modifier.testTag("config:route:${entry.route.id}"),
        subtitle = when (entry.route) {
            ConfigRoute.VERSION -> stringResource(R.string.config_version_value, state.versionName)
            ConfigRoute.ENGINE -> state.engineSummary
            ConfigRoute.UPDATE -> updateSubtitle(state)
            else -> entry.subtitleRes?.let { stringResource(it) }
        },
        leading = {
            Icon(
                painter = painterResource(entry.iconRes),
                contentDescription = null,
                tint = colors.primary.oe3Color(),
                modifier = Modifier.size(Oe3Sizes.icon),
            )
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
            ) {
                if (entry.route == ConfigRoute.UPDATE &&
                    state.options.updateCheck &&
                    state.updateAvailable != null
                ) {
                    UpdateBadge()
                }
                ConfigChevron(title)
            }
        },
        showDivider = showDivider,
        onClick = onClick,
    )
}

@Composable
private fun updateSubtitle(state: ConfigScreenState): String = when {
    !state.options.updateCheck -> stringResource(R.string.config_update_disabled)
    state.updateAvailable != null -> {
        val note = state.updateNote
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
        val head = stringResource(R.string.config_update_available, state.updateAvailable)
        if (note.isNullOrBlank()) head else "$head · $note"
    }
    else -> stringResource(R.string.config_update_current, state.versionName)
}

@Composable
private fun UpdateBadge() {
    val colors = Oe3ThemeValues.colors
    Text(
        text = stringResource(R.string.config_update_badge),
        style = MetaTextStyle,
        color = colors.primary.oe3Color(),
        modifier = Modifier
            .background(
                color = colors.primaryContainer.oe3Color(),
                shape = RoundedCornerShape(Oe3Shapes.sm),
            )
            .padding(horizontal = Oe3Spacing.xs, vertical = 2.dp),
    )
}

@Composable
private fun OptionLabel() {
    val colors = Oe3ThemeValues.colors
    Text(
        text = stringResource(R.string.config_options_label),
        style = MetaTextStyle,
        color = colors.onSurfaceVariant.oe3Color(),
        modifier = Modifier.padding(
            start = Oe3Spacing.rowHorizontal,
            end = Oe3Spacing.rowHorizontal,
            top = Oe3Spacing.sm,
            bottom = Oe3Spacing.xxs,
        ),
    )
}

@Composable
private fun GroupOptions(
    group: ConfigGroup,
    options: ConfigOptions,
    onOptionsChange: (ConfigOptions) -> Unit,
) {
    when (group) {
        ConfigGroup.APPEARANCE -> MotionOption(options, onOptionsChange)
        ConfigGroup.RUNTIME -> {
            MirrorOption(options, onOptionsChange)
            BootRestoreOption(options, onOptionsChange)
        }
        ConfigGroup.ABOUT -> UpdateCheckOption(options, onOptionsChange)
        ConfigGroup.CONNECTION, ConfigGroup.DATA -> Unit
    }
}

@Composable
private fun MotionOption(options: ConfigOptions, onOptionsChange: (ConfigOptions) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    ChoiceRow(
        title = stringResource(R.string.config_option_motion),
        description = stringResource(R.string.config_option_motion_desc),
        value = stringResource(options.sectionMotion.labelRes),
        tag = "motion",
        showDivider = false,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        ChoiceDialog(
            title = stringResource(R.string.config_option_motion),
            choices = SectionMotion.entries.map { it.labelRes to it },
            current = options.sectionMotion,
            onPick = { picked ->
                showDialog = false
                onOptionsChange(options.copy(sectionMotion = picked))
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun MirrorOption(options: ConfigOptions, onOptionsChange: (ConfigOptions) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    ChoiceRow(
        title = stringResource(R.string.config_option_mirror),
        description = null,
        value = stringResource(options.defaultMirror.labelRes),
        tag = "mirror",
        showDivider = true,
        onClick = { showDialog = true },
    )
    if (showDialog) {
        ChoiceDialog(
            title = stringResource(R.string.config_option_mirror),
            choices = DefaultMirror.entries.map { it.labelRes to it },
            current = options.defaultMirror,
            onPick = { picked ->
                showDialog = false
                onOptionsChange(options.copy(defaultMirror = picked))
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun BootRestoreOption(options: ConfigOptions, onOptionsChange: (ConfigOptions) -> Unit) {
    Oe3SwitchRow(
        title = stringResource(R.string.config_option_boot_restore),
        subtitle = stringResource(R.string.config_option_boot_restore_desc),
        checked = options.bootRestore,
        onCheckedChange = { onOptionsChange(options.copy(bootRestore = it)) },
        modifier = Modifier.testTag("config:option:boot_restore"),
        showDivider = false,
    )
}

@Composable
private fun UpdateCheckOption(options: ConfigOptions, onOptionsChange: (ConfigOptions) -> Unit) {
    Oe3SwitchRow(
        title = stringResource(R.string.config_option_update_check),
        subtitle = stringResource(R.string.config_option_update_check_desc),
        checked = options.updateCheck,
        onCheckedChange = { onOptionsChange(options.copy(updateCheck = it)) },
        modifier = Modifier.testTag("config:option:update_check"),
        showDivider = false,
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    description: String?,
    value: String,
    tag: String,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Oe3ListRow(
        title = title,
        modifier = Modifier.testTag("config:option:$tag"),
        subtitle = description,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
            ) {
                Text(
                    text = value,
                    style = MetaTextStyle,
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ConfigChevron(title)
            }
        },
        showDivider = showDivider,
        onClick = onClick,
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    choices: List<Pair<Int, T>>,
    current: T,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    // 对话框进场接 Oe3Motion.dialog（spec §2.5：250ms easeOutCubic，fade + scale
    // 0.96→1）。Material3 AlertDialog 本体不 fork：token 走内容层进出场，减少动态
    // 时首帧即终态（对照 CustomSlotEditorDialog 的接法）。
    Oe3DialogEnterMotion {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(OeShape.alert),
            title = {
                Text(
                    text = title,
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                )
            },
            text = {
                Column {
                    choices.forEach { (labelRes, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Oe3Sizes.minTouchTarget)
                                .selectable(
                                    selected = value == current,
                                    role = Role.RadioButton,
                                    onClick = { onPick(value) },
                                )
                                .semantics(mergeDescendants = true) {},
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == current, onClick = null)
                            Spacer(Modifier.width(Oe3Spacing.sm))
                            Text(
                                text = stringResource(labelRes),
                                style = Oe3Typography.style(Oe3TextRole.Body),
                                color = colors.onSurface.oe3Color(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.config_dialog_cancel),
                        color = colors.primary.oe3Color(),
                    )
                }
            },
            containerColor = colors.surfaceHigh.oe3Color(),
        )
    }
}

/**
 * A decorative chevron that still names its row for screen readers; the row
 * merges descendants, so the description is announced once with the title.
 */
@Composable
private fun ConfigChevron(title: String) {
    val description = stringResource(R.string.config_chevron_desc, title)
    val color = Oe3ThemeValues.colors.onSurfaceVariant.oe3Color()
    OeChevron(color = color, contentDescription = description)
}

@Composable
private fun DirectionChevron(pointsLeft: Boolean) {
    val color = Oe3ThemeValues.colors.onSurface.oe3Color()
    OeChevron(color = color, pointsLeft = pointsLeft)
}

@Preview(name = "配置 · 暗色", showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun ConfigScreenDarkPreview() {
    Oe3Theme(theme = ThemeCatalog.all.first { it.isDark }) {
        ConfigScreen(
            state = previewState(),
            onRoute = {},
            onOptionsChange = {},
        )
    }
}

@Preview(name = "配置 · 浅色", showBackground = true, widthDp = 390, heightDp = 900)
@Composable
private fun ConfigScreenLightPreview() {
    Oe3Theme(theme = ThemeCatalog.all.first { !it.isDark }) {
        ConfigScreen(
            state = previewState(),
            onRoute = {},
            onOptionsChange = {},
        )
    }
}

private fun previewState() = ConfigScreenState(
    versionName = "0.6.2",
    engineSummary = "AVF 未授权 · QEMU 回退",
    updateAvailable = "0.7.0",
    updateNote = "配置中心与设置目录\n新增可玩性选项",
    options = ConfigOptions(
        sectionMotion = SectionMotion.Standard,
        defaultMirror = DefaultMirror.MultiTier,
        bootRestore = false,
        updateCheck = true,
    ),
)
