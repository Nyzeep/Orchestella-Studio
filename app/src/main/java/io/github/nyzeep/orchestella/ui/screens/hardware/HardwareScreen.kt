package io.github.nyzeep.orchestella.ui.screens.hardware

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageBottomPadding
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Divider
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3EmptyState
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ListRow
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Ring
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SectionHeader
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/** 页面级几何常量；语义与命中区断言直接读这里。 */
object HardwareSpec {
    val ringDiameter = 72.dp
    val ringStroke = 8.dp

    val engineRowHeight = 56.dp
    val detailsButtonHeight = 48.dp
    val detailRowMinHeight = 48.dp

    val summaryCellPadding = 12.dp
}

/** 72dp 环心读数；八级 token 之外的页面局部样式（与工作站页同口径）。 */
private val RingFigureTextStyle = TextStyle(
    fontSize = 18.sp,
    lineHeight = 22.sp,
    fontWeight = FontWeight.SemiBold,
)

/**
 * 硬件页：摘要 + 三环 + 引擎状态 + 完整参数底部表。
 *
 * 页面只读 [HardwareUiState]，不读 Context、不启动探测、不碰设备标识；
 * 刷新与授权向导由 [HardwareActions] 注入，真实接线在 UI3-12。旧壳的
 * 页码、遥测条与 `01/K` 元素都不在这里出现。
 */
@Composable
fun HardwareScreen(
    state: HardwareUiState,
    actions: HardwareActions,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    var showDetails by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background.oe3Color())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Oe3Spacing.pageInset)
            .padding(top = Oe3Spacing.xl, bottom = LocalOe3PageBottomPadding.current)
            .testTag("hardware:screen"),
    ) {
        Text(
            text = stringResource(R.string.ui3_hardware_title),
            style = Oe3Typography.style(Oe3TextRole.PageTitle),
            color = colors.onSurface.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.md))

        when (state) {
            is HardwareUiState.Ready -> HardwareContent(
                state = state,
                actions = actions,
                onOpenDetails = { showDetails = true },
            )
            is HardwareUiState.Failed -> FailureContent(state = state, actions = actions)
        }
    }

    if (showDetails && state is HardwareUiState.Ready) {
        HardwareDetailsSheet(
            groups = state.details,
            onDismiss = { showDetails = false },
        )
    }
}

@Composable
private fun HardwareContent(
    state: HardwareUiState.Ready,
    actions: HardwareActions,
    onOpenDetails: () -> Unit,
) {
    SummaryCard(summary = state.summary, onRefresh = actions.refresh)

    Oe3SectionHeader(title = stringResource(R.string.ui3_hardware_section_gauges))
    RingsCard(rings = state.rings)

    Oe3SectionHeader(title = stringResource(R.string.ui3_hardware_section_engines))
    EngineCard(engines = state.engines, actions = actions)

    Spacer(Modifier.height(Oe3Spacing.md))
    Oe3Button(
        text = stringResource(R.string.ui3_hardware_details_action),
        onClick = onOpenDetails,
        style = Oe3ButtonStyle.Tonal,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HardwareSpec.detailsButtonHeight)
            .testTag("hardware:details-button"),
        leading = {
            Oe3Icon(image = Oe3Icons.More, contentDescription = null)
        },
    )
}

@Composable
private fun SummaryCard(summary: HardwareSummaryUi, onRefresh: () -> Unit) {
    val colors = Oe3ThemeValues.colors
    val placeholder = stringResource(R.string.ui3_hardware_value_placeholder)

    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hardware:summary"),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary.deviceName ?: placeholder,
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Oe3Spacing.xxs))
                Text(
                    text = stringResource(
                        R.string.ui3_hardware_summary_platform,
                        summary.androidRelease,
                        summary.apiLevel,
                        summary.architecture ?: placeholder,
                        summary.cores,
                    ),
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Oe3Button(
                text = stringResource(R.string.ui3_hardware_refresh),
                onClick = onRefresh,
                style = Oe3ButtonStyle.Text,
                modifier = Modifier
                    .heightIn(min = HardwareSpec.detailsButtonHeight)
                    .testTag("hardware:refresh"),
                leading = {
                    Oe3Icon(image = Oe3Icons.Update, contentDescription = null)
                },
            )
        }
        Spacer(Modifier.height(Oe3Spacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.gridGap)) {
            SummaryFact(
                label = stringResource(R.string.ui3_hardware_summary_ram),
                value = summary.ramTotal ?: placeholder,
                modifier = Modifier.weight(1f),
            )
            SummaryFact(
                label = stringResource(R.string.ui3_hardware_summary_storage),
                value = summary.storageFree ?: placeholder,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(Oe3Spacing.gridGap))
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.gridGap)) {
            SummaryFact(
                label = stringResource(R.string.ui3_hardware_summary_battery),
                value = summary.batteryPercent?.let(HardwareFormat::percent) ?: placeholder,
                modifier = Modifier.weight(1f),
            )
            SummaryFact(
                label = stringResource(R.string.ui3_hardware_summary_temperature),
                value = summary.temperature ?: placeholder,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryFact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Oe3Shapes.md))
            .background(colors.surfaceRaised.oe3Color())
            .padding(HardwareSpec.summaryCellPadding),
    ) {
        Text(
            text = label,
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Oe3Spacing.xxs))
        Text(
            text = value,
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = colors.onSurface.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RingsCard(rings: HardwareRingsUi) {
    Oe3Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
        ) {
            RingColumn(
                label = stringResource(R.string.ui3_hardware_ring_memory),
                ring = rings.memory,
                tag = "hardware:ring:memory",
            )
            RingColumn(
                label = stringResource(R.string.ui3_hardware_ring_storage),
                ring = rings.storage,
                tag = "hardware:ring:storage",
            )
            RingColumn(
                label = stringResource(R.string.ui3_hardware_ring_battery),
                ring = rings.battery,
                tag = "hardware:ring:battery",
            )
        }
    }
}

@Composable
private fun RowScope.RingColumn(
    label: String,
    ring: HardwareRingUi,
    tag: String,
) {
    val colors = Oe3ThemeValues.colors
    val placeholder = stringResource(R.string.ui3_hardware_value_placeholder)
    val percentText = ring.percent?.let(HardwareFormat::percent)
    val detailText = ring.detail ?: placeholder
    val description = if (percentText == null) {
        stringResource(R.string.ui3_hardware_ring_unavailable, label)
    } else {
        stringResource(R.string.ui3_hardware_ring_description, label, percentText, detailText)
    }

    Column(
        modifier = Modifier
            .weight(1f)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Oe3Ring(
            progress = ring.fraction,
            diameter = HardwareSpec.ringDiameter,
            ringWidth = HardwareSpec.ringStroke,
            color = colors.primary.oe3Color(),
            trackColor = colors.surfaceHigh.oe3Color(),
            contentDescription = description,
        ) {
            Text(
                text = percentText ?: placeholder,
                style = RingFigureTextStyle,
                color = colors.onSurface.oe3Color(),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = label,
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = colors.onSurface.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = detailText,
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EngineCard(engines: HardwareEnginesUi, actions: HardwareActions) {
    val colors = Oe3ThemeValues.colors
    val avf = engines.avf
    val avfSubtitle = stringResource(
        R.string.ui3_hardware_engine_avf_subtitle,
        stringResource(avf.state.labelRes),
        avf.reason ?: stringResource(avf.state.fallbackReasonRes),
    )
    val uml = engines.uml
    val umlSubtitle = if (uml.running) {
        stringResource(
            R.string.ui3_hardware_uml_running_summary,
            stringResource(R.string.ui3_hardware_uml_running),
            stringResource(uml.channelRes ?: R.string.ui3_hardware_uml_unwired),
            uml.runningWidth,
        )
    } else {
        stringResource(R.string.ui3_hardware_uml_idle)
    }

    Oe3Card(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
    ) {
        Oe3ListRow(
            title = stringResource(R.string.ui3_hardware_engine_avf),
            subtitle = avfSubtitle,
            leading = {
                Oe3Icon(
                    image = Oe3Icons.Hardware,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.oe3Color(),
                )
            },
            trailing = if (avf.state.offersAuthorization) {
                {
                    Oe3Button(
                        text = stringResource(R.string.ui3_hardware_avf_authorize),
                        onClick = actions.requestAvfAuthorization,
                        style = Oe3ButtonStyle.Text,
                        modifier = Modifier
                            .heightIn(min = HardwareSpec.detailRowMinHeight)
                            .testTag("hardware:avf-authorize"),
                    )
                }
            } else {
                null
            },
            showDivider = true,
            modifier = Modifier
                .heightIn(min = HardwareSpec.engineRowHeight)
                .testTag("hardware:engine:avf"),
        )
        Oe3ListRow(
            title = stringResource(R.string.ui3_hardware_engine_qemu),
            subtitle = stringResource(engines.qemuStatusRes),
            leading = {
                Oe3Icon(
                    image = Oe3Icons.Terminal,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.oe3Color(),
                )
            },
            showDivider = true,
            modifier = Modifier
                .heightIn(min = HardwareSpec.engineRowHeight)
                .testTag("hardware:engine:qemu"),
        )
        Oe3ListRow(
            title = stringResource(R.string.ui3_hardware_engine_uml),
            subtitle = umlSubtitle,
            leading = {
                Oe3Icon(
                    image = Oe3Icons.Distros,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.oe3Color(),
                )
            },
            showDivider = false,
            modifier = Modifier
                .heightIn(min = HardwareSpec.engineRowHeight)
                .testTag("hardware:engine:uml"),
        )
    }
}

@Composable
private fun FailureContent(state: HardwareUiState.Failed, actions: HardwareActions) {
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hardware:probe-failed"),
    ) {
        Oe3EmptyState(
            title = stringResource(R.string.ui3_hardware_probe_failed_title),
            description = stringResource(state.reasonRes),
            graphic = {
                Oe3Icon(image = Oe3Icons.Hardware, contentDescription = null)
            },
            action = {
                Oe3Button(
                    text = stringResource(R.string.ui3_hardware_retry),
                    onClick = actions.refresh,
                    modifier = Modifier
                        .heightIn(min = HardwareSpec.detailsButtonHeight)
                        .testTag("hardware:retry"),
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HardwareDetailsSheet(
    groups: List<HardwareDetailGroup>,
    onDismiss: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface.oe3Color(),
        contentColor = colors.onSurface.oe3Color(),
        shape = RoundedCornerShape(topStart = Oe3Shapes.xl, topEnd = Oe3Shapes.xl),
        modifier = Modifier.testTag("hardware:details-sheet"),
    ) {
        HardwareDetailsContent(groups = groups)
    }
}

/**
 * 底部参数表本体。独立出来是为了让语义用例直接渲染可滚动列表，
 * 不依赖 dialog 窗口；每一行都是 48dp 起、标签与值合并成一个阅读节点。
 */
@Composable
internal fun HardwareDetailsContent(
    groups: List<HardwareDetailGroup>,
    modifier: Modifier = Modifier,
) {
    val colors = Oe3ThemeValues.colors

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hardware:details-list"),
        contentPadding = PaddingValues(
            start = Oe3Spacing.pageInset,
            end = Oe3Spacing.pageInset,
            top = Oe3Spacing.xs,
            bottom = Oe3Spacing.xxl,
        ),
    ) {
        item(key = "details-title") {
            Text(
                text = stringResource(R.string.ui3_hardware_details_title),
                style = Oe3Typography.style(Oe3TextRole.Section),
                color = colors.onSurface.oe3Color(),
                modifier = Modifier.padding(bottom = Oe3Spacing.xs),
            )
        }
        groups.forEach { group ->
            item(key = "group:${group.titleRes}") {
                Oe3SectionHeader(title = stringResource(group.titleRes))
            }
            items(group.rows, key = { it.labelRes }) { row ->
                DetailRow(row = row)
            }
        }
    }
}

@Composable
private fun DetailRow(row: HardwareDetailUi) {
    val colors = Oe3ThemeValues.colors
    val placeholder = stringResource(R.string.ui3_hardware_value_placeholder)
    val valueText = when (val value = row.value) {
        null -> placeholder
        is HardwareDetailValue.Text -> value.text
        is HardwareDetailValue.Resource -> stringResource(value.res)
        is HardwareDetailValue.Template -> stringResource(value.res, *value.args.toTypedArray())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HardwareSpec.detailRowMinHeight)
            .semantics(mergeDescendants = true) {}
            .padding(vertical = Oe3Spacing.xs)
            .testTag("hardware:detail-row"),
    ) {
        Text(
            text = stringResource(row.labelRes),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = valueText,
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurface.oe3Color(),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Oe3Divider(startIndent = 0.dp, endIndent = 0.dp)
}
