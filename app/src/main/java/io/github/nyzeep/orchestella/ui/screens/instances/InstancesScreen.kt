package io.github.nyzeep.orchestella.ui.screens.instances

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3EmptyState
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SectionHeader
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3IconTint
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3CardEnterMotion
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3DialogEnterMotion
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/** 页面级几何常量；语义与命中区断言直接读这里。 */
object InstancesSpec {
    /**
     * 票面写 44dp pill，但 Oe3Button 的可点底限是 48dp（spec §2.3 / WCAG
     * 2.5.5）；两个创建/导入入口保持一致，不另造 44dp 变体。
     */
    val headerActionHeight = Oe3Sizes.button

    /** 卡片主操作与按钮的 48dp 命中区。 */
    val actionHeight = Oe3Sizes.button

    /** 发行版单色标记所在的 48dp well。 */
    val distroWell = Oe3Sizes.minTouchTarget

    val progressBarHeight = 4.dp
}

/** 页面标题下的元信息（spec §2.3：13/18）。 */
private val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** 实例 id / IP 行；票面要求 mono 12，不在八级 token 内，属于页面局部样式。 */
private val MonoCaptionStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)

/**
 * 实例页：按状态分组的大卡列表、创建/导入、创建进度横幅与空态。
 *
 * 页面只读 [InstancesUiState]；路由、真实动作与刷新入口由 UI3-12 注入。
 * 独立可预览，不依赖壳层。旧壳的页码、0dp 圆角与 1dp 描边都不在这里出现。
 */
@Composable
fun InstancesScreen(
    state: InstancesUiState,
    actions: InstancesActions,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    var sheetInstanceId by rememberSaveable { mutableStateOf<String?>(null) }
    // 卡片入场只播一次（spec §2.5 cardEnter，motion.md 的「列表首屏卡片」）：
    // rememberSaveable 让 tab 切换回来不重播；首帧之后新组合（滚动）的卡片
    // 一律读到已入场，滚动不产生新动画。
    var cardsEntered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { cardsEntered = true }
    val sheetItem = sheetInstanceId?.let { id ->
        state.groups.asSequence()
            .flatMap { it.items.asSequence() }
            .firstOrNull { it.id == id }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = actions.refresh,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background.oe3Color()),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("instances-list"),
            contentPadding = PaddingValues(
                start = Oe3Spacing.pageInset,
                end = Oe3Spacing.pageInset,
                top = Oe3Spacing.xl,
                bottom = LocalOe3PageBottomPadding.current,
            ),
        ) {
            item(key = "instances:header") {
                PageHeader(actions = actions)
            }
            item(key = "instances:meta") {
                MetaLine(state = state)
            }
            state.progress?.let { progress ->
                item(key = "instances:progress") {
                    ProgressBanner(progress = progress)
                }
            }
            if (!state.hasInstances) {
                item(key = "instances:empty") {
                    EmptyCard(actions = actions)
                }
            } else {
                state.groups.forEach { group ->
                    item(key = InstancesListKeys.group(group.kind)) {
                        GroupHeader(group = group)
                    }
                    items(
                        items = group.items,
                        key = { item -> InstancesListKeys.item(item.id) },
                    ) { item ->
                        Oe3CardEnterMotion(
                            entered = cardsEntered,
                            modifier = Modifier
                                .animateItem()
                                .padding(bottom = Oe3Spacing.xs),
                        ) {
                            InstanceCard(
                                item = item,
                                onOpen = { actions.openInstance(item.id) },
                                onPrimary = {
                                    when (item.primaryAction) {
                                        InstancePrimaryAction.Terminal -> actions.openTerminal(item.id)
                                        InstancePrimaryAction.Launch -> actions.launch(item.id)
                                        InstancePrimaryAction.Resume -> actions.resume(item.id)
                                    }
                                },
                                onMore = { sheetInstanceId = item.id },
                            )
                        }
                    }
                }
            }
        }
    }

    sheetItem?.let { item ->
        InstanceActionsSheet(
            item = item,
            actions = actions,
            onDismiss = { sheetInstanceId = null },
        )
    }
}

@Composable
private fun PageHeader(actions: InstancesActions) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.ui3_instances_title),
            style = Oe3Typography.style(Oe3TextRole.PageTitle),
            color = colors.onSurface.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Oe3Button(
            text = stringResource(R.string.ui3_instances_create),
            onClick = actions.createInstance,
            modifier = Modifier
                .heightIn(min = InstancesSpec.headerActionHeight)
                .testTag("instances-create"),
        )
        Spacer(Modifier.width(Oe3Spacing.xs))
        Oe3Button(
            text = stringResource(R.string.ui3_instances_import),
            onClick = actions.importArchive,
            style = Oe3ButtonStyle.Tonal,
            modifier = Modifier
                .heightIn(min = InstancesSpec.headerActionHeight)
                .testTag("instances-import"),
        )
    }
}

@Composable
private fun MetaLine(state: InstancesUiState) {
    val colors = Oe3ThemeValues.colors
    Text(
        text = if (state.hasInstances) {
            stringResource(
                R.string.ui3_instances_meta_summary,
                state.totalCount,
                state.runningCount,
            )
        } else {
            stringResource(R.string.ui3_instances_meta_empty)
        },
        style = MetaTextStyle,
        color = colors.onSurfaceVariant.oe3Color(),
        modifier = Modifier.padding(top = Oe3Spacing.xxs, bottom = Oe3Spacing.sm),
    )
}

@Composable
private fun ProgressBanner(progress: InstancesProgressUi) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Oe3Spacing.sm)
            .testTag("instances-progress"),
        contentPadding = PaddingValues(Oe3Spacing.md),
    ) {
        Text(
            text = progress.statusText,
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurface.oe3Color(),
            modifier = Modifier
                .testTag("instances-progress-text")
                // 进度文案由创建流程异步更新，读屏应播报而不是等焦点。
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        progress.fraction?.let { fraction ->
            Spacer(Modifier.height(Oe3Spacing.sm))
            val progressLabel = stringResource(R.string.ui3_instances_progress_description)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(InstancesSpec.progressBarHeight)
                    .clip(Oe3Shapes.pill)
                    .semantics { contentDescription = progressLabel }
                    .testTag("instances-progress-bar"),
                color = colors.primary.oe3Color(),
                trackColor = colors.surfaceHigh.oe3Color(),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun GroupHeader(group: InstanceGroupUi) {
    val label = stringResource(
        R.string.ui3_instances_group_count,
        stringResource(group.kind.titleRes),
        group.count,
    )
    Oe3SectionHeader(
        title = label,
        modifier = Modifier.testTag("instances-group-${group.kind.name.lowercase()}"),
    )
}

@Composable
private fun EmptyCard(actions: InstancesActions) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(modifier = Modifier.fillMaxWidth()) {
        Oe3EmptyState(
            title = stringResource(R.string.ui3_instances_empty_title),
            description = stringResource(R.string.ui3_instances_empty_body),
            modifier = Modifier.testTag("instances-empty"),
            graphic = {
                Box(
                    modifier = Modifier
                        .size(Oe3Sizes.minTouchTarget + Oe3Spacing.xs)
                        .clip(RoundedCornerShape(Oe3Shapes.md))
                        .background(colors.surfaceRaised.oe3Color()),
                    contentAlignment = Alignment.Center,
                ) {
                    Oe3Icon(image = Oe3Icons.Instances, contentDescription = null)
                }
            },
            action = {
                Oe3Button(
                    text = stringResource(R.string.ui3_instances_empty_cta),
                    onClick = actions.createInstance,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = InstancesSpec.actionHeight)
                        .testTag("instances-empty-cta"),
                )
            },
        )
    }
}

/**
 * 实例大卡。
 *
 * 票面同时要求“卡片整体可点且有描述”和卡内主操作/更多，所以这里有意在可点
 * [Oe3Card] 里保留两个按钮——这是对 Oe3Card “单节点”建议的显式例外。为了
 * 不让读屏只听到重复的“终端/更多”，两个按钮各自带上实例名描述。
 */
@Composable
private fun InstanceCard(
    item: InstanceRowUi,
    onOpen: () -> Unit,
    onPrimary: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    val statusLabel = stringResource(item.status.labelRes)
    val description = stringResource(
        R.string.ui3_instances_card_description,
        item.name,
        statusLabel,
        item.specsText,
    )
    val primaryLabel = stringResource(item.primaryAction.labelRes)
    val primaryDescription = stringResource(
        R.string.ui3_instances_action_description,
        item.name,
        primaryLabel,
    )
    val moreLabel = stringResource(R.string.ui3_instances_action_more)
    val moreDescription = stringResource(
        R.string.ui3_instances_action_description,
        item.name,
        moreLabel,
    )
    val primaryIcon: ImageVector = when (item.primaryAction) {
        InstancePrimaryAction.Terminal -> Oe3Icons.Terminal
        InstancePrimaryAction.Launch -> Oe3Icons.Power
        InstancePrimaryAction.Resume -> Oe3Icons.Update
    }

    Oe3Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("instances-card-${item.id}")
            .semantics { contentDescription = description },
        onClick = onOpen,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DistroWell(distroId = item.distroId)
            Spacer(Modifier.width(Oe3Spacing.iconTextGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.ui3_instances_card_id, item.shortId),
                    style = MonoCaptionStyle,
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.specsText,
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.ipAddress?.let { ip ->
                    Text(
                        text = stringResource(R.string.ui3_instances_card_ip, ip),
                        style = MonoCaptionStyle,
                        color = colors.onSurfaceVariant.oe3Color(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(Oe3Spacing.xs))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(status = item.status, instanceId = item.id)
            item.engineLabel?.let { engine ->
                Oe3Chip(
                    label = engine,
                    modifier = Modifier.testTag("instances-engine-${item.id}"),
                )
            }
        }

        Spacer(Modifier.height(Oe3Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oe3Button(
                text = primaryLabel,
                onClick = onPrimary,
                enabled = item.primaryEnabled,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = InstancesSpec.actionHeight)
                    .semantics { contentDescription = primaryDescription }
                    .testTag("instances-primary-${item.id}"),
                leading = {
                    Oe3Icon(
                        image = primaryIcon,
                        contentDescription = null,
                        tint = if (item.primaryEnabled) {
                            colors.onPrimary.oe3Color()
                        } else {
                            colors.onPrimary.oe3Color().copy(alpha = 0.5f)
                        },
                    )
                },
            )
            Spacer(Modifier.width(Oe3Spacing.xs))
            Oe3Button(
                text = moreLabel,
                onClick = onMore,
                style = Oe3ButtonStyle.Text,
                modifier = Modifier
                    .semantics { contentDescription = moreDescription }
                    .testTag("instances-more-${item.id}"),
            )
        }
    }
}

/** 状态 chip：文字是语义，圆点只是权重提示；不设点击，所以不占 48dp 命中区。 */
@Composable
private fun StatusChip(status: InstanceStatus, instanceId: String) {
    val colors = Oe3ThemeValues.colors
    val dot = when (status.tone) {
        InstanceStatusTone.Active -> colors.primary.oe3Color()
        InstanceStatusTone.Busy -> colors.accent.oe3Color()
        InstanceStatusTone.Idle -> colors.onSurfaceVariant.oe3Color()
        InstanceStatusTone.Error -> colors.error.oe3Color()
    }
    Oe3Chip(
        label = stringResource(status.labelRes),
        selected = status.tone == InstanceStatusTone.Active,
        modifier = Modifier.testTag("instances-status-${status.name.lowercase()}-$instanceId"),
        leading = {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dot),
            )
        },
    )
}

@Composable
private fun DistroWell(distroId: String) {
    val colors = Oe3ThemeValues.colors
    Box(
        modifier = Modifier
            .size(InstancesSpec.distroWell)
            .clip(RoundedCornerShape(Oe3Shapes.md))
            .background(colors.surfaceRaised.oe3Color()),
        contentAlignment = Alignment.Center,
    ) {
        Oe3Icon(
            image = Oe3DistroIcons.of(distroId),
            contentDescription = null,
            tint = Oe3IconTint.of(selected = false),
        )
    }
}

/**
 * “更多”动作表：电源、桌面、日志、导出、删除。
 *
 * 动作可用性来自 [InstanceRowUi] 的能力位；禁用项保留在原位，让用户看到
 * 为什么现在不能做，而不是整行消失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceActionsSheet(
    item: InstanceRowUi,
    actions: InstancesActions,
    onDismiss: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    val statusLabel = stringResource(item.status.labelRes)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface.oe3Color(),
        contentColor = colors.onSurface.oe3Color(),
        shape = RoundedCornerShape(
            topStart = Oe3Shapes.xl,
            topEnd = Oe3Shapes.xl,
        ),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = Oe3Spacing.sm, bottom = Oe3Spacing.xs)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(Oe3Shapes.pill)
                    .background(colors.surfaceHigh.oe3Color()),
            )
        },
    ) {
        // 底部表同属对话框事件面：sheet 滑入保持 Material3 原生，token 在
        // 内容层按 250ms easeOutCubic 播放，减少动态时首帧即终态。
        Oe3DialogEnterMotion {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Oe3Spacing.pageInset)
                    .padding(bottom = Oe3Spacing.xl),
            ) {
                Text(
                    text = item.name,
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.ui3_instances_sheet_subtitle,
                        statusLabel,
                        item.shortId,
                    ),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                )
                Spacer(Modifier.height(Oe3Spacing.sm))
                SheetAction(
                    icon = Oe3Icons.Power,
                    label = stringResource(R.string.ui3_instances_sheet_power),
                    enabled = item.canPowerOff,
                    modifier = Modifier.testTag("instances-sheet-power"),
                ) {
                    actions.powerOff(item.id)
                    onDismiss()
                }
                SheetAction(
                    icon = Oe3Icons.Workstation,
                    label = stringResource(R.string.ui3_instances_sheet_desktop),
                    enabled = item.canLaunchDesktop,
                    modifier = Modifier.testTag("instances-sheet-desktop"),
                ) {
                    actions.openDesktop(item.id)
                    onDismiss()
                }
                SheetAction(
                    icon = Oe3Icons.Terminal,
                    label = stringResource(R.string.ui3_instances_sheet_logs),
                    enabled = item.canShowLogs,
                    modifier = Modifier.testTag("instances-sheet-logs"),
                ) {
                    actions.openLogs(item.id)
                    onDismiss()
                }
                SheetAction(
                    icon = Oe3Icons.Import,
                    label = stringResource(R.string.ui3_instances_sheet_export),
                    enabled = item.canExport,
                    modifier = Modifier.testTag("instances-sheet-export"),
                ) {
                    actions.exportInstance(item.id)
                    onDismiss()
                }
                SheetAction(
                    icon = null,
                    label = stringResource(R.string.ui3_instances_sheet_delete),
                    enabled = item.canDelete,
                    danger = true,
                    modifier = Modifier.testTag("instances-sheet-delete"),
                ) {
                    actions.deleteInstance(item.id)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector?,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    val ink: Color = when {
        !enabled -> colors.onSurfaceVariant.oe3Color().copy(alpha = 0.4f)
        danger -> colors.error.oe3Color()
        else -> colors.onSurface.oe3Color()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Oe3Sizes.interactiveMinHeight(Oe3Spacing.rowHeight))
            .clip(RoundedCornerShape(Oe3Shapes.sm))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Oe3Spacing.rowHorizontal, vertical = Oe3Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Oe3Icon(image = icon, contentDescription = null, tint = ink)
            Spacer(Modifier.width(Oe3Spacing.iconTextGap))
        }
        Text(
            text = label,
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
