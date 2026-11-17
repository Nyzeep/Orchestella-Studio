package io.github.nyzeep.orchestella.ui.screens.wizard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nyzeep.orchestella.InstanceCreationWizardModel
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.design.components.OeSlider
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Chip
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Divider
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icon
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3Icons
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3SwitchRow
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.components.oeCardTitleStyle
import io.github.nyzeep.orchestella.ui.oe3.icons.Oe3DistroIcons
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography

/**
 * 四步内容（UI-12 步骤 2）。每个可组合件只读 [WizardUiState]，交互以命令
 * 进 [WizardScreenModel]；控件全部走 Oe3 token —— 卡片 24dp、卡内 well 16dp、
 * 胶囊按钮、UI-05 唯一滑块 [OeSlider]、输入框 16dp（shape.md 输入框档）。
 *
 * 宽度适配一律用 `weight` / `fillMaxWidth` / `FlowRow` 换行，不写硬编码 dp。
 */

// ---------------------------------------------------------------------------
// 步骤 1 · 存储与挂载（发行版 + 磁盘配额 + AVF 预检）
// ---------------------------------------------------------------------------

@Composable
fun StorageStepContent(
    state: WizardUiState,
    controller: WizardScreenModel,
    onPickSharedDir: () -> Unit,
) {
    WizardAvfCard(state = state, controller = controller)

    DistroSelectionCard(
        options = state.storage.distroOptions,
        selectedId = state.storage.selectedDistroId,
        onSelect = controller::selectDistro,
    )

    StorageQuotaCard(state = state, controller = controller)

    SharedDirectoryCard(state = state, onPickSharedDir = onPickSharedDir)
}

/** 步骤 1 顶部：AVF 平台预检卡（结论行默认收起，详情可展开）。 */
@Composable
private fun WizardAvfCard(state: WizardUiState, controller: WizardScreenModel) {
    val colors = Oe3ThemeValues.colors
    val severityColor = when (state.avf.severity) {
        AvfSeverity.Green -> colorResource(R.color.zone_green)
        AvfSeverity.Yellow -> colorResource(R.color.zone_yellow)
        AvfSeverity.Red -> colorResource(R.color.zone_red)
        AvfSeverity.Neutral -> colors.onSurfaceVariant.oe3Color()
    }
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:avf-card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.avf.badge,
                style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                color = severityColor,
                modifier = Modifier.weight(1f),
            )
            val expandDesc = stringResource(R.string.wizard_avf_expand_desc)
            Box(
                modifier = Modifier
                    .size(Oe3Sizes.minTouchTarget)
                    .clip(Oe3Shapes.pill)
                    .clickable(role = Role.Button, onClick = controller::toggleAvfDetails)
                    .semantics { contentDescription = expandDesc },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.avf.expanded) "▾" else "▸",
                    style = Oe3Typography.style(Oe3TextRole.Body),
                    color = colors.onSurfaceVariant.oe3Color(),
                )
            }
        }
        Spacer(Modifier.height(Oe3Spacing.xxs))
        Text(
            text = state.avf.summary,
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurfaceVariant.oe3Color(),
        )
        val fadeSpec = tween<Float>(
            Oe3ThemeValues.motion.pageFade.durationMillis,
            easing = Oe3ThemeValues.motion.pageFade.easing,
        )
        val expandSpec = tween<IntSize>(
            Oe3ThemeValues.motion.pageFade.durationMillis,
            easing = Oe3ThemeValues.motion.pageFade.easing,
        )
        AnimatedVisibility(
            visible = state.avf.expanded && state.avf.details.isNotEmpty(),
            // 折叠展开动效取页面切换 token（旧 OrchestellaMotion.expand/collapse 的
            // Compose 对应物；减动效下 token 时长归零）。
            enter = expandVertically(expandSpec) + fadeIn(fadeSpec),
            exit = shrinkVertically(expandSpec) + fadeOut(fadeSpec),
        ) {
            Column {
                Spacer(Modifier.height(Oe3Spacing.xs))
                Text(
                    text = state.avf.details,
                    style = Oe3Typography.style(Oe3TextRole.Caption),
                    color = colors.onSurfaceVariant.oe3Color(),
                )
            }
        }
        if (state.avf.fallbackVisible) {
            Spacer(Modifier.height(Oe3Spacing.xs))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OeShape.md))
                    .background(colors.surfaceHigh.oe3Color())
                    .padding(Oe3Spacing.sm),
            ) {
                Column {
                    Text(
                        text = "启动验证与回退",
                        style = Oe3Typography.style(Oe3TextRole.Caption),
                        color = colors.primary.oe3Color(),
                    )
                    Spacer(Modifier.height(Oe3Spacing.xxs))
                    Text(
                        text = "当前仅检查 AVF 平台声明与应用权限。custom VM 在实例启动时验证创建与运行；预检不满足或启动失败时自动回退至 QEMU。",
                        style = Oe3Typography.style(Oe3TextRole.Caption),
                        color = colors.onSurfaceVariant.oe3Color(),
                    )
                }
            }
        }
    }
}

/** 发行版选择卡：卡内 2 列瓦片（卡内块 12dp），选中瓦片 primaryContainer + 对勾。 */
@Composable
private fun DistroSelectionCard(
    options: List<DistroOptionUi>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:distro-card"),
    ) {
        Text(
            text = stringResource(R.string.wizard_distro_section_title),
            style = oeCardTitleStyle(),
            color = colors.onSurface.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.sm))
        val tileShape = RoundedCornerShape(Oe3Shapes.sm)
        options.forEachIndexed { index, option ->
            val selected = option.id == selectedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(tileShape)
                    .background(
                        if (selected) {
                            colors.primaryContainer.oe3Color()
                        } else {
                            colors.surfaceHigh.oe3Color()
                        },
                    )
                    .clickable(role = Role.Button) { onSelect(option.id) }
                    .padding(Oe3Spacing.sm)
                    .semantics { this.selected = selected },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Oe3Icon(
                    image = Oe3DistroIcons.of(option.id),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(Oe3Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = option.name,
                        style = Oe3Typography.style(Oe3TextRole.BodyStrong),
                        color = colors.onSurface.oe3Color(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = option.tag,
                        style = Oe3Typography.style(Oe3TextRole.Caption),
                        color = colors.onSurfaceVariant.oe3Color(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    Oe3Icon(
                        image = Oe3Icons.Check,
                        contentDescription = null,
                        tint = colors.primary.oe3Color(),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (index != options.lastIndex) {
                Spacer(Modifier.height(Oe3Spacing.xs))
            }
        }
        val selectedOption = options.firstOrNull { it.id == selectedId }
        if (selectedOption != null) {
            Spacer(Modifier.height(Oe3Spacing.sm))
            Text(
                text = selectedOption.desc,
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
            )
        }
    }
}

/** 存储配额卡：可用空间读数、预设胶囊、自定义输入、预检结论、共享目录开关。 */
@Composable
private fun StorageQuotaCard(state: WizardUiState, controller: WizardScreenModel) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:storage-card"),
    ) {
        Text(
            text = stringResource(R.string.wizard_storage_card_title),
            style = oeCardTitleStyle(),
            color = colors.onSurface.oe3Color(),
        )
        Text(
            text = stringResource(R.string.wizard_storage_card_desc),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.sm))
        Text(
            text = stringResource(
                R.string.wizard_storage_available,
                String.format("%.1f GB", state.storage.availableGb),
            ),
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurface.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs)) {
            state.storage.presets.forEach { preset ->
                Oe3Chip(
                    label = "$preset GB",
                    selected = state.storage.selectedPresetGb == preset,
                    onClick = { controller.selectDiskPreset(preset) },
                    enabled = !state.isCreating,
                )
            }
        }
        Spacer(Modifier.height(Oe3Spacing.sm))
        OutlinedTextField(
            value = controller.diskInputText,
            onValueChange = { raw -> controller.onCustomDiskInput(raw) },
            singleLine = true,
            label = { Text(stringResource(R.string.wizard_disk_custom_hint)) },
            isError = state.storage.gate is StorageGate.InvalidInput,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(OeShape.md),
            enabled = !state.isCreating,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("wizard:disk-input"),
        )
        StorageWarning(state = state)
    }
}

/** 预检结论行：InvalidInput 提示 / InsufficientSpace 的 E-STORAGE-FULL 族文案。 */
@Composable
private fun StorageWarning(state: WizardUiState) {
    val colors = Oe3ThemeValues.colors
    when (val gate = state.storage.gate) {
        StorageGate.Ready -> Unit
        StorageGate.InvalidInput -> {
            Spacer(Modifier.height(Oe3Spacing.xs))
            Text(
                text = stringResource(R.string.wizard_disk_input_invalid),
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.error.oe3Color(),
                modifier = Modifier.testTag("wizard:storage-warning"),
            )
        }
        is StorageGate.InsufficientSpace -> {
            Spacer(Modifier.height(Oe3Spacing.xs))
            val entry = gate.error
            val text = if (entry != null) {
                stringResource(entry.headlineRes) +
                    stringResource(R.string.error_code_suffix, entry.code) + "\n" +
                    stringResource(
                        R.string.wizard_storage_insufficient_warn,
                        gate.availableGb.toFloat(),
                        gate.requiredGb.toFloat(),
                    ) + "\n" +
                    stringResource(entry.hintRes)
            } else {
                stringResource(
                    R.string.wizard_storage_insufficient_warn,
                    gate.availableGb.toFloat(),
                    gate.requiredGb.toFloat(),
                )
            }
            Text(
                text = text,
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.error.oe3Color(),
                modifier = Modifier.testTag("wizard:storage-warning"),
            )
        }
    }
}

/** 共享目录卡：通道现状开关（旧文案）+ 已选目录的重选入口。 */
@Composable
private fun SharedDirectoryCard(state: WizardUiState, onPickSharedDir: () -> Unit) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:shared-dir-card"),
    ) {
        Oe3SwitchRow(
            title = stringResource(R.string.wizard_mount_shared_dir_label),
            checked = state.storage.mountSharedDir,
            onCheckedChange = {},
            subtitle = stringResource(R.string.wizard_shared_dir_disabled),
            enabled = false,
        )
        if (state.storage.sharedDirPath != null) {
            Spacer(Modifier.height(Oe3Spacing.xs))
            Text(
                text = stringResource(R.string.wizard_shared_folder_selected, state.storage.sharedDirPath),
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
            )
            Oe3Button(
                text = stringResource(R.string.wizard_select_shared_folder),
                onClick = onPickSharedDir,
                style = Oe3ButtonStyle.Text,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 步骤 2 · 网络与镜像源
// ---------------------------------------------------------------------------

@Composable
fun NetworkStepContent(state: WizardUiState, controller: WizardScreenModel) {
    NetworkDiagnosisCard(state = state, controller = controller)
    MirrorSourceCard(state = state, controller = controller)
}

@Composable
private fun NetworkDiagnosisCard(state: WizardUiState, controller: WizardScreenModel) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:network-card"),
    ) {
        Text(
            text = stringResource(R.string.wizard_network_card_title),
            style = oeCardTitleStyle(),
            color = colors.onSurface.oe3Color(),
        )
        Text(
            text = stringResource(R.string.wizard_network_card_desc),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.sm))
        NetworkFactLine(state.network.modeLine)
        NetworkFactLine(state.network.ipLine)
        NetworkFactLine(state.network.gatewayLine)
        NetworkFactLine(state.network.dnsLine)
        Spacer(Modifier.height(Oe3Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Oe3Button(
                text = stringResource(R.string.wizard_net_test_btn),
                onClick = controller::testNetworkConnectivity,
                style = Oe3ButtonStyle.Tonal,
                enabled = !state.isCreating,
                modifier = Modifier.testTag("wizard:test-network"),
            )
            Spacer(Modifier.width(Oe3Spacing.sm))
            val resultColor = when (state.network.testResult) {
                NetworkTestResult.Idle -> colors.onSurfaceVariant.oe3Color()
                NetworkTestResult.Testing -> colorResource(R.color.status_provisioning)
                NetworkTestResult.Ok -> colorResource(R.color.zone_green)
                NetworkTestResult.Failed -> colorResource(R.color.zone_red)
            }
            Text(
                text = when (state.network.testResult) {
                    NetworkTestResult.Idle -> ""
                    NetworkTestResult.Testing -> stringResource(R.string.wizard_net_testing)
                    NetworkTestResult.Ok -> stringResource(R.string.wizard_net_test_ok)
                    NetworkTestResult.Failed -> stringResource(R.string.wizard_net_test_failed)
                },
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = resultColor,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NetworkFactLine(line: String) {
    if (line.isEmpty()) return
    val colors = Oe3ThemeValues.colors
    Text(
        text = line,
        style = Oe3Typography.style(Oe3TextRole.Body),
        color = colors.onSurfaceVariant.oe3Color(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MirrorSourceCard(state: WizardUiState, controller: WizardScreenModel) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:mirror-card"),
    ) {
        Text(
            text = stringResource(R.string.wizard_mirror_selector_label),
            style = oeCardTitleStyle(),
            color = colors.onSurface.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.sm))
        // 镜像源选择：chip 流式换行（宽度自适应，无硬编码断点）。
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
        ) {
            state.network.mirrorOptions.forEachIndexed { index, name ->
                Oe3Chip(
                    label = name,
                    selected = index == state.network.selectedMirrorOrdinal,
                    onClick = { controller.selectMirror(index) },
                    enabled = !state.isCreating,
                )
            }
        }
        Spacer(Modifier.height(Oe3Spacing.sm))
        Oe3Button(
            text = stringResource(R.string.wizard_speed_test_btn),
            onClick = controller::runMirrorSpeedTest,
            style = Oe3ButtonStyle.Tonal,
            enabled = !state.isCreating,
            modifier = Modifier.testTag("wizard:speed-test"),
        )
        state.network.speedTestSummary?.let { summary ->
            Spacer(Modifier.height(Oe3Spacing.xs))
            Text(
                text = summary,
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
                modifier = Modifier.testTag("wizard:speed-result"),
            )
        }
        Spacer(Modifier.height(Oe3Spacing.sm))
        Text(
            text = state.network.mirrorPreview,
            style = Oe3Typography.style(Oe3TextRole.Mono),
            color = colors.onSurfaceVariant.oe3Color(),
            modifier = Modifier.testTag("wizard:mirror-preview"),
        )
    }
}

// ---------------------------------------------------------------------------
// 步骤 3 · 硬件配额分配
// ---------------------------------------------------------------------------

@Composable
fun HardwareStepContent(state: WizardUiState, controller: WizardScreenModel) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:hardware-card"),
    ) {
        Text(
            text = stringResource(R.string.wizard_hardware_card_title),
            style = oeCardTitleStyle(),
            color = colors.onSurface.oe3Color(),
        )
        Text(
            text = stringResource(R.string.wizard_hardware_card_desc),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.sm))

        // vCPU：唯一滑块 + 步进按钮（整数步）。
        Text(
            text = stringResource(R.string.wizard_config_vcpu_info, state.hardware.cpuCores),
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurface.oe3Color(),
        )
        if (state.hardware.cpuAvfNotice) {
            Text(
                text = stringResource(R.string.wizard_config_vcpu_avf_notice),
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
            )
        }
        Spacer(Modifier.height(Oe3Spacing.xxs))
        OeSlider(
            value = state.hardware.cpuCores.toFloat(),
            onValueChange = { controller.onCpuCoresChange(it.toInt()) },
            valueRange = 1f..state.hardware.cpuMax.toFloat(),
            steps = (state.hardware.cpuMax - 1).coerceAtLeast(0),
            enabled = !state.isCreating,
            stateDescription = stringResource(R.string.wizard_config_vcpu_info, state.hardware.cpuCores),
            modifier = Modifier.testTag("wizard:cpu-slider"),
        )
        StepperRow(
            onMinus = { controller.onCpuCoresChange((state.hardware.cpuCores - 1).coerceAtLeast(1)) },
            onPlus = { controller.onCpuCoresChange((state.hardware.cpuCores + 1).coerceAtMost(state.hardware.cpuMax)) },
            enabled = !state.isCreating,
            valueLabel = "${state.hardware.cpuCores}",
        )

        Spacer(Modifier.height(Oe3Spacing.sm))
        // RAM：镜像最低要求为下界；宿主装不下时滑块禁用并给出提示。
        Text(
            text = stringResource(R.string.wizard_config_ram_info, state.hardware.ramMb),
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = colors.onSurface.oe3Color(),
        )
        state.hardware.minimumBootRamMb?.let { minimum ->
            Text(
                text = stringResource(R.string.wizard_config_ram_minimum, minimum),
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
            )
        }
        Spacer(Modifier.height(Oe3Spacing.xxs))
        if (state.hardware.ramEnabled) {
            OeSlider(
                value = state.hardware.ramMb.toFloat(),
                onValueChange = { controller.onRamMbChange(it.toInt()) },
                valueRange = state.hardware.ramMinMb.toFloat()..state.hardware.ramMaxMb.toFloat(),
                steps = if (state.hardware.ramStepMb > 0) {
                    ((state.hardware.ramMaxMb - state.hardware.ramMinMb) / state.hardware.ramStepMb).coerceAtLeast(0)
                } else {
                    0
                },
                enabled = !state.isCreating,
                stateDescription = stringResource(R.string.wizard_config_ram_info, state.hardware.ramMb),
                modifier = Modifier.testTag("wizard:ram-slider"),
            )
        } else {
            Text(
                text = stringResource(R.string.wizard_config_ram_unavailable),
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
            )
        }

        Spacer(Modifier.height(Oe3Spacing.sm))
        // 安全区徽标（GREEN/YELLOW/错误文案）。
        val badgeColor = when (state.hardware.badge) {
            HardwareBadge.Green -> colorResource(R.color.zone_green)
            HardwareBadge.Yellow -> colorResource(R.color.zone_yellow)
            is HardwareBadge.Error -> colorResource(R.color.zone_red)
        }
        val badgeText = when (val badge = state.hardware.badge) {
            HardwareBadge.Green -> stringResource(R.string.wizard_config_zone_green)
            HardwareBadge.Yellow -> stringResource(R.string.hardware_ram_zone_yellow)
            is HardwareBadge.Error -> badge.message
        }
        Text(
            text = badgeText,
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = badgeColor,
            modifier = Modifier.testTag("wizard:zone-badge"),
        )

        if (state.hardware.needsAuthEntry) {
            Spacer(Modifier.height(Oe3Spacing.xs))
            Oe3Button(
                text = stringResource(R.string.wizard_btn_auth_guide),
                onClick = controller::openAvfAuthorization,
                style = Oe3ButtonStyle.Tonal,
                enabled = !state.isCreating,
                modifier = Modifier.testTag("wizard:avf-auth"),
            )
        }
    }
}

/** 滑块旁的整数步进（旧 btn_vcpu_minus/plus 的 48dp 命中形态）。 */
@Composable
private fun StepperRow(
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    enabled: Boolean,
    valueLabel: String,
) {
    val colors = Oe3ThemeValues.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
    ) {
        Oe3Button(text = "−", onClick = onMinus, style = Oe3ButtonStyle.Tonal, enabled = enabled)
        Text(
            text = valueLabel,
            style = Oe3Typography.style(Oe3TextRole.BodyStrong),
            color = colors.onSurface.oe3Color(),
        )
        Oe3Button(text = "+", onClick = onPlus, style = Oe3ButtonStyle.Tonal, enabled = enabled)
    }
}

// ---------------------------------------------------------------------------
// 步骤 4 · 启动任务与部署
// ---------------------------------------------------------------------------

@Composable
fun StartupStepContent(state: WizardUiState, controller: WizardScreenModel) {
    IdentityCard(state = state, controller = controller)
    OptionalFeaturesCard(state = state, controller = controller)
    // 进行中的进度面板在摘要卡之前：创建中的用户先看活进度，再看静态摘要。
    CreationProgressPanel(state = state)
    CreationSummaryCard(state = state)
}

@Composable
private fun IdentityCard(state: WizardUiState, controller: WizardScreenModel) {
    val colors = Oe3ThemeValues.colors
    val error = controller.identityError.value
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:identity-card"),
    ) {
        Text(
            text = stringResource(R.string.wizard_identity_section_title),
            style = oeCardTitleStyle(),
            color = colors.onSurface.oe3Color(),
        )
        Text(
            text = stringResource(R.string.wizard_identity_section_desc),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.sm))
        OutlinedTextField(
            value = state.startup.username,
            onValueChange = { raw ->
                controller.clearIdentityError()
                controller.onIdentityChange(raw, state.startup.hostname)
            },
            singleLine = true,
            label = { Text(stringResource(R.string.wizard_identity_username_hint)) },
            isError = error == WizardController.IdentityError.Username ||
                error == WizardController.IdentityError.RootConflict,
            supportingText = {
                when (error) {
                    WizardController.IdentityError.Username ->
                        Text(stringResource(R.string.wizard_identity_username_error))
                    WizardController.IdentityError.RootConflict ->
                        Text(stringResource(R.string.wizard_identity_root_conflict))
                    else -> Unit
                }
            },
            shape = RoundedCornerShape(OeShape.md),
            enabled = !state.isCreating,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("wizard:username-input"),
        )
        Spacer(Modifier.height(Oe3Spacing.xs))
        OutlinedTextField(
            value = state.startup.hostname,
            onValueChange = { raw ->
                controller.clearIdentityError()
                controller.onIdentityChange(state.startup.username, raw)
            },
            singleLine = true,
            label = { Text(stringResource(R.string.wizard_identity_hostname_hint)) },
            isError = error == WizardController.IdentityError.HostnameBlank ||
                error == WizardController.IdentityError.HostnameInvalid,
            supportingText = {
                when (error) {
                    WizardController.IdentityError.HostnameBlank ->
                        Text(stringResource(R.string.wizard_identity_hostname_blank))
                    WizardController.IdentityError.HostnameInvalid ->
                        Text(stringResource(R.string.wizard_identity_hostname_invalid))
                    else -> Unit
                }
            },
            shape = RoundedCornerShape(OeShape.md),
            enabled = !state.isCreating,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("wizard:hostname-input"),
        )
        Oe3SwitchRow(
            title = stringResource(R.string.wizard_identity_sudo),
            checked = state.startup.sudo,
            onCheckedChange = controller::onSudoChange,
            enabled = !state.isCreating,
        )
    }
}

@Composable
private fun OptionalFeaturesCard(state: WizardUiState, controller: WizardScreenModel) {
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:optional-card"),
    ) {
        Text(
            text = stringResource(R.string.wizard_optional_section_title),
            style = oeCardTitleStyle(),
            color = colors.onSurface.oe3Color(),
        )
        Text(
            text = stringResource(R.string.wizard_optional_section_desc),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.xs))
        Oe3SwitchRow(
            title = stringResource(R.string.wizard_optional_desktop),
            checked = state.startup.desktopSelected,
            onCheckedChange = { controller.onOptionalProfileChange(it, state.startup.podmanSelected, state.startup.editorSelected) },
            enabled = state.startup.desktopEnabled,
        )
        Oe3Divider()
        Oe3SwitchRow(
            title = stringResource(R.string.wizard_optional_podman),
            checked = state.startup.podmanSelected,
            onCheckedChange = { controller.onOptionalProfileChange(state.startup.desktopSelected, it, state.startup.editorSelected) },
            enabled = state.startup.podmanEnabled,
        )
        Oe3Divider()
        Oe3SwitchRow(
            title = stringResource(R.string.wizard_optional_editor),
            checked = state.startup.editorSelected,
            onCheckedChange = { controller.onOptionalProfileChange(state.startup.desktopSelected, state.startup.podmanSelected, it) },
            enabled = state.startup.editorEnabled,
        )
        Oe3Divider()
        Oe3SwitchRow(
            title = stringResource(R.string.wizard_auto_update_label),
            checked = state.startup.autoUpdate,
            onCheckedChange = controller::onAutoUpdateChange,
            enabled = !state.isCreating,
        )
        Spacer(Modifier.height(Oe3Spacing.xs))
        Text(
            text = stringResource(R.string.wizard_update_desc),
            style = Oe3Typography.style(Oe3TextRole.Caption),
            color = colors.onSurfaceVariant.oe3Color(),
        )
        Text(
            text = if (state.startup.autoUpdate) state.startup.updateCommand else {
                stringResource(R.string.wizard_update_preview_off)
            },
            style = Oe3Typography.style(Oe3TextRole.Mono),
            color = colors.onSurfaceVariant.oe3Color(),
            modifier = Modifier.testTag("wizard:update-preview"),
        )
    }
}

/** 创建确认摘要：结构化字段 → 逐行组装（旧 bullet 列表）。 */
@Composable
private fun CreationSummaryCard(state: WizardUiState) {
    val colors = Oe3ThemeValues.colors
    val sudoLabel = stringResource(
        if (state.startup.sudo) R.string.wizard_summary_sudo_on else R.string.wizard_summary_sudo_off,
    )
    val sharedLabel = stringResource(
        if (state.storage.mountSharedDir) {
            R.string.wizard_summary_shared_mounted
        } else {
            R.string.wizard_summary_shared_unmounted
        },
    )
    val firstBootLabel = stringResource(
        if (state.startup.autoUpdate) {
            R.string.wizard_summary_first_boot_on
        } else {
            R.string.wizard_summary_first_boot_off
        },
    )
    val features = listOfNotNull(
        if (state.startup.desktopSelected) stringResource(R.string.wizard_summary_feature_desktop) else null,
        if (state.startup.podmanSelected) "Podman" else null,
        if (state.startup.editorSelected) "code-server" else null,
    ).joinToString("、").ifEmpty { stringResource(R.string.wizard_summary_features_none) }
    val mirrorName = state.network.mirrorOptions.getOrNull(state.network.selectedMirrorOrdinal) ?: ""

    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:summary-card"),
    ) {
        Text(
            text = stringResource(R.string.wizard_creation_title),
            style = oeCardTitleStyle(),
            color = colors.onSurface.oe3Color(),
        )
        Spacer(Modifier.height(Oe3Spacing.xs))
        SummaryLine(stringResource(R.string.wizard_summary_distro, summaryDistroName(state)))
        SummaryLine(stringResource(R.string.wizard_summary_disk, state.storage.diskSizeGb))
        SummaryLine(stringResource(R.string.wizard_summary_shared, sharedLabel))
        SummaryLine(
            stringResource(
                R.string.wizard_summary_hardware,
                state.hardware.cpuCores,
                state.hardware.ramMb,
            ),
        )
        SummaryLine(stringResource(R.string.wizard_summary_mirror, mirrorName))
        SummaryLine(stringResource(R.string.wizard_summary_identity, state.startup.username, state.startup.hostname))
        SummaryLine(stringResource(R.string.wizard_summary_sudo, sudoLabel))
        SummaryLine(stringResource(R.string.wizard_summary_features, features))
        SummaryLine(stringResource(R.string.wizard_summary_first_boot, firstBootLabel))
        SummaryLine(stringResource(R.string.wizard_summary_retriable))
    }
}

@Composable
private fun summaryDistroName(state: WizardUiState): String =
    state.storage.selectedDistro?.name ?: state.storage.selectedDistroId

@Composable
private fun SummaryLine(line: String) {
    val colors = Oe3ThemeValues.colors
    Text(
        text = line,
        style = Oe3Typography.style(Oe3TextRole.Body),
        color = colors.onSurfaceVariant.oe3Color(),
    )
}

/** 下载与装配进度面板：Fetching/Provisioning 有真实进度，KeptRunning 诚实无进度。 */
@Composable
private fun CreationProgressPanel(state: WizardUiState) {
    val phase = state.creation.phase
    if (phase == CreationPhase.Idle) return
    val colors = Oe3ThemeValues.colors
    Oe3Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wizard:progress-card"),
    ) {
        val statusLine = state.creation.statusLine
            ?: stringResource(R.string.wizard_kept_running_line)
        Text(
            text = statusLine,
            style = Oe3Typography.style(Oe3TextRole.Body),
            color = when (phase) {
                CreationPhase.Failed -> colorResource(R.color.status_crashed)
                else -> colorResource(R.color.status_provisioning)
            },
        )
        if (phase == CreationPhase.Fetching || phase == CreationPhase.Provisioning) {
            Spacer(Modifier.height(Oe3Spacing.xs))
            val percent = state.creation.progressPercent
            if (percent != null && percent in 1..99) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(Oe3Shapes.pill),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(Oe3Shapes.pill),
                )
            }
        }
        state.creation.speedEtaLine?.let { line ->
            Spacer(Modifier.height(Oe3Spacing.xxs))
            Text(
                text = line,
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
            )
        }
        state.creation.mirrorLine?.let { line ->
            Text(
                text = line,
                style = Oe3Typography.style(Oe3TextRole.Caption),
                color = colors.onSurfaceVariant.oe3Color(),
            )
        }
    }
}
