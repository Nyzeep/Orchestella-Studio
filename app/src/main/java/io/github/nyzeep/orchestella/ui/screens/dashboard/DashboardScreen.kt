package io.github.nyzeep.orchestella.ui.screens.dashboard

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3EmptyState
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ListRow
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Ring
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SectionHeader
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.screens.common.formatGigabytes
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3IconTint
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.theme.ThemeColors

/** 页面级几何常量；语义与命中区断言直接读这里。 */
object DashboardSpec {
    /** “打开终端”是主卡唯一主动作，命中区不得低于 48dp。 */
    val terminalButtonHeight = 48.dp

    /** 存储环是页面主视觉。 */
    val ringDiameter = 120.dp
    val ringStroke = 12.dp

    /** CPU / RAM 紧凑卡的用量条。 */
    val metricBarHeight = 4.dp

    /** 指标读数由壳层每 3 秒刷新一次。 */
    const val refreshSeconds = 3
}

/** 页面标题下的元信息（spec §2.3：13/18）。 */
private val MetaTextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

/** 环心与指标卡的大数字；不在八级 token 内，属于页面局部样式。 */
private val FigureTextStyle = TextStyle(
    fontSize = 24.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.SemiBold,
)

/**
 * 工作站页：当前实例 + 存储 + 两台指标。
 *
 * 页面只读 [DashboardUiState]；路由、真实动作与 3 秒刷新由 UI3-12 接线。
 * 独立可预览，不依赖壳层。旧壳的页码、遥测条、控制矩阵、SSH well 与引擎
 * 选择都不在这里出现。
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    actions: DashboardActions,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background.oe3Color())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Oe3Spacing.pageInset)
            .padding(top = Oe3Spacing.xl, bottom = LocalOe3PageBottomPadding.current),
    ) {
        Text(
            text = stringResource(R.string.ui3_dashboard_title),
            style = Oe3Typography.style(Oe3TextRole.PageTitle),
            color = colors.onSurface.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.xxs))
        Text(
            text = metaLine(state),
            style = MetaTextStyle,
            color = colors.onSurfaceVariant.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.md))

        val primary = state.primary
        if (primary == null) {
            EmptyPrimaryCard(actions = actions)
        } else {
            PrimaryInstanceCard(
                instance = primary,
                onOpenTerminal = { actions.openTerminal(primary.id) },
            )
        }

        Oe3SectionHeader(title = stringResource(R.string.ui3_dashboard_section_storage))
        StorageCard(state = state.storage, onOpenSettings = actions.openStorageSettings)

        Oe3SectionHeader(title = stringResource(R.string.ui3_dashboard_section_metrics))
        MetricsRow(state = state)
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = stringResource(R.string.ui3_dashboard_metric_refresh, DashboardSpec.refreshSeconds),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
        )

        Oe3SectionHeader(title = stringResource(R.string.ui3_dashboard_section_recent))
        RecentCard(state = state, onOpenInstance = actions.openInstance)
    }
}

@Composable
private fun metaLine(state: DashboardUiState): String = if (state.primary == null) {
    stringResource(R.string.ui3_dashboard_meta_empty)
} else {
    stringResource(R.string.ui3_dashboard_meta_summary, state.instanceCount, state.runningCount)
}

@Composable
private fun PrimaryInstanceCard(
    instance: DashboardInstanceUi,
    onOpenTerminal: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DistroMark(distroId = instance.distroId)
            Spacer(Modifier.width(Oe3Spacing.iconTextGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = instance.name,
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.ui3_dashboard_instance_specs,
                        instance.cpuCores,
                        instance.ramMb,
                        instance.diskGb,
                    ),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Oe3Spacing.sm))
            DashboardPill(label = stringResource(instance.status.labelRes), tone = instance.status.tone)
        }
        if (instance.engineLabel != null) {
            Spacer(Modifier.height(Oe3Spacing.sm))
            DashboardPill(
                label = instance.engineLabel,
                tone = DashboardStatusTone.Idle,
            )
        }
        Spacer(Modifier.height(Oe3Spacing.md))
        Oe3Button(
            text = stringResource(R.string.ui3_dashboard_open_terminal),
            onClick = onOpenTerminal,
            enabled = instance.canOpenTerminal,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DashboardSpec.terminalButtonHeight),
            leading = {
                Oe3Icon(
                    image = Oe3Icons.Terminal,
                    contentDescription = null,
                    tint = if (instance.canOpenTerminal) {
                        colors.onPrimary.oe3Color()
                    } else {
                        colors.onPrimary.oe3Color().copy(alpha = 0.5f)
                    },
                )
            },
        )
    }
}

@Composable
private fun EmptyPrimaryCard(actions: DashboardActions) {
    Oe3Card(modifier = Modifier.fillMaxWidth()) {
        Oe3EmptyState(
            title = stringResource(R.string.ui3_dashboard_empty_title),
            description = stringResource(R.string.ui3_dashboard_empty_body),
            graphic = {
                Oe3Icon(image = Oe3Icons.Create, contentDescription = null)
            },
            action = {
                Oe3Button(
                    text = stringResource(R.string.ui3_dashboard_create_instance),
                    onClick = actions.createInstance,
                    modifier = Modifier.heightIn(min = DashboardSpec.terminalButtonHeight),
                )
            },
        )
    }
}

@Composable
private fun StorageCard(state: DashboardStorageUi, onOpenSettings: () -> Unit) {
    val colors = Oe3ThemeValues.colors
    val usedText = formatGigabytes(state.usedBytes)
    val totalText = formatGigabytes(state.totalBytes)
    Oe3Card(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oe3Ring(
                progress = state.fraction,
                diameter = DashboardSpec.ringDiameter,
                ringWidth = DashboardSpec.ringStroke,
                color = colors.primary.oe3Color(),
                trackColor = colors.surfaceHigh.oe3Color(),
                contentDescription = stringResource(
                    R.string.ui3_dashboard_storage_ring_description,
                    state.percent,
                    usedText,
                    totalText,
                ),
            ) {
                Text(
                    text = "${state.percent}%",
                    style = FigureTextStyle,
                    color = colors.onSurface.oe3Color(),
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            Spacer(Modifier.width(Oe3Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ui3_dashboard_storage_used, usedText),
                    style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                    color = colors.onSurface.oe3Color(),
                )
                Text(
                    text = stringResource(R.string.ui3_dashboard_storage_total, totalText),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                )
                Spacer(Modifier.height(Oe3Spacing.sm))
                Oe3Button(
                    text = stringResource(R.string.ui3_dashboard_storage_settings),
                    onClick = onOpenSettings,
                    style = Oe3ButtonStyle.Text,
                )
            }
        }
    }
}

@Composable
private fun MetricsRow(state: DashboardUiState) {
    val colors = Oe3ThemeValues.colors
    val placeholder = stringResource(R.string.ui3_dashboard_metric_placeholder)
    val placeholderCaption = stringResource(R.string.ui3_dashboard_metric_placeholder_caption)

    Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.gridGap)) {
        MetricCard(
            label = stringResource(R.string.ui3_dashboard_metric_cpu),
            value = if (state.cpu.enabled) "${state.cpu.instanceCores}" else placeholder,
            caption = if (state.cpu.enabled) {
                stringResource(R.string.ui3_dashboard_metric_cpu_caption, state.cpu.hostCores)
            } else {
                placeholderCaption
            },
            fraction = state.cpu.fraction,
            barColor = colors.primary.oe3Color(),
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            label = stringResource(R.string.ui3_dashboard_metric_ram),
            value = if (state.ram.enabled) formatGigabytes(state.ram.usedBytes) else placeholder,
            caption = if (state.ram.enabled) {
                stringResource(R.string.ui3_dashboard_metric_ram_caption, formatGigabytes(state.ram.totalBytes))
            } else {
                placeholderCaption
            },
            fraction = state.ram.fraction,
            barColor = colors.accent.oe3Color(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    caption: String,
    fraction: Float,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = modifier,
        contentPadding = PaddingValues(Oe3Spacing.md),
    ) {
        Text(
            text = label,
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
            maxLines = 1,
        )
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = value,
            style = FigureTextStyle,
            color = colors.onSurface.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Oe3Spacing.sm))
        UsageBar(fraction = fraction, barColor = barColor)
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = caption,
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UsageBar(fraction: Float, barColor: Color) {
    val colors = Oe3ThemeValues.colors
    val safeFraction = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DashboardSpec.metricBarHeight)
            .clip(Oe3Shapes.pill)
            .background(colors.surfaceHigh.oe3Color()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safeFraction)
                .height(DashboardSpec.metricBarHeight)
                .clip(Oe3Shapes.pill)
                .background(barColor),
        )
    }
}

@Composable
private fun RecentCard(state: DashboardUiState, onOpenInstance: (String) -> Unit) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
    ) {
        if (state.recent.isEmpty()) {
            Text(
                text = stringResource(R.string.ui3_dashboard_recent_empty),
                style = Oe3Typography.style(Oe3TextRole.Body),
                color = colors.onSurfaceVariant.oe3Color(),
                modifier = Modifier.padding(
                    horizontal = Oe3Spacing.rowHorizontal,
                    vertical = Oe3Spacing.md,
                ),
            )
        } else {
            state.recent.forEachIndexed { index, item ->
                Oe3ListRow(
                    title = item.name,
                    subtitle = stringResource(item.status.labelRes),
                    leading = {
                        Oe3Icon(
                            image = Oe3DistroIcons.of(item.distroId),
                            contentDescription = null,
                            tint = Oe3IconTint.of(selected = false),
                        )
                    },
                    onClick = { onOpenInstance(item.id) },
                    showDivider = index < state.recent.lastIndex,
                )
            }
        }
    }
}

/** 发行版单色标记，放在卡片首列的 48dp well 内。 */
@Composable
private fun DistroMark(distroId: String) {
    val colors = Oe3ThemeValues.colors
    Box(
        modifier = Modifier
            .size(Oe3Sizes.minTouchTarget)
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
 * 状态 / 引擎 chip。文字永远表达语义，颜色只是辅助；不设点击，所以不占
 * 48dp 命中区，视觉高度 28dp。
 */
@Composable
private fun DashboardPill(label: String, tone: DashboardStatusTone) {
    val colors = Oe3ThemeValues.colors
    val (fill, ink) = when (tone) {
        DashboardStatusTone.Active -> colors.primaryContainer to colors.primary
        DashboardStatusTone.Busy -> colors.surfaceHigh to colors.accent
        DashboardStatusTone.Idle -> colors.surfaceHigh to colors.onSurfaceVariant
        DashboardStatusTone.Error -> {
            ThemeColors.blend(colors.surface, colors.error, 0.18f) to colors.error
        }
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Oe3Shapes.sm))
            .background(fill.oe3Color())
            .heightIn(min = 28.dp)
            .padding(horizontal = 10.dp, vertical = Oe3Spacing.xxs)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(ink.oe3Color()),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = ink.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
