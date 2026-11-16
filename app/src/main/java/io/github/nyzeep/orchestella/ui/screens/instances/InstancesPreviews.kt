package io.github.nyzeep.orchestella.ui.screens.instances

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/**
 * 实例页预览矩阵：有实例/空态 × 暗/亮/纯黑 × 1.0/1.3 字号，另有创建进度横幅。
 *
 * 只依赖 [InstancesUiState] 的静态样本，不启动 Activity、不读磁盘；截图工单
 * （UI3-11 基建）可以直接复用这些 pane 的名称。
 */

private fun previewRow(
    id: String,
    distroId: String,
    name: String,
    status: InstanceStatus,
    specs: String,
    engine: String?,
    ip: String?,
    terminal: Boolean,
    launch: Boolean,
    desktop: Boolean,
    power: Boolean,
    export: Boolean,
    delete: Boolean,
) = InstanceRowUi(
    id = id,
    shortId = id.take(8),
    distroId = distroId,
    name = name,
    status = status,
    engineLabel = engine,
    specsText = specs,
    ipAddress = ip,
    canOpenTerminal = terminal,
    canLaunch = launch,
    canLaunchDesktop = desktop,
    canPowerOff = power,
    canShowLogs = status != InstanceStatus.Created,
    canExport = export,
    canDelete = delete,
)

private val previewRunning = previewRow(
    id = "preview-ubuntu-0001",
    distroId = "ubuntu",
    name = "Ubuntu 24.04",
    status = InstanceStatus.Running,
    specs = "4 vCPU · 4096 MB · 16 GB · GPU加速",
    engine = "QEMU 软件虚拟化",
    ip = "192.0.2.42",
    terminal = true,
    launch = false,
    desktop = false,
    power = true,
    export = false,
    delete = false,
)

private val previewStopped = previewRow(
    id = "preview-debian-0002",
    distroId = "debian",
    name = "Debian 12",
    status = InstanceStatus.Stopped,
    specs = "2 vCPU · 2048 MB · 8 GB",
    engine = "QEMU 软件虚拟化",
    ip = null,
    terminal = false,
    launch = true,
    desktop = false,
    power = false,
    export = true,
    delete = true,
)

private val previewSuspended = previewRow(
    id = "preview-opensuse-0003",
    distroId = "opensuse",
    name = "openSUSE 24.04",
    status = InstanceStatus.Suspended,
    specs = "4 vCPU · 4096 MB · 16 GB",
    engine = "AVF 硬件虚拟化",
    ip = null,
    terminal = false,
    launch = false,
    desktop = false,
    power = true,
    export = false,
    delete = false,
)

private val previewCrashed = previewRow(
    id = "preview-rocky-0004",
    distroId = "rocky",
    name = "Rocky Linux 9",
    status = InstanceStatus.Crashed,
    specs = "2 vCPU · 2048 MB · 8 GB",
    engine = "QEMU 软件虚拟化",
    ip = null,
    terminal = false,
    launch = false,
    desktop = false,
    power = false,
    export = true,
    delete = true,
)

private val previewProvisioning = previewRow(
    id = "preview-fedora-0005",
    distroId = "fedora",
    name = "Fedora 41",
    status = InstanceStatus.Provisioning,
    specs = "2 vCPU · 2048 MB · 8 GB",
    engine = null,
    ip = null,
    terminal = false,
    launch = false,
    desktop = false,
    power = false,
    export = false,
    delete = false,
)

private val previewWithInstances = InstancesUiState(
    groups = listOf(
        InstanceGroupUi(InstanceGroup.Running, listOf(previewRunning)),
        InstanceGroupUi(InstanceGroup.Stopped, listOf(previewStopped, previewSuspended)),
        InstanceGroupUi(InstanceGroup.Error, listOf(previewCrashed)),
    ),
    totalCount = 4,
    runningCount = 1,
)

private val previewEmpty = InstancesUiState(
    groups = emptyList(),
    totalCount = 0,
    runningCount = 0,
)

private val previewWithProgress = InstancesUiState(
    groups = listOf(
        InstanceGroupUi(InstanceGroup.Provisioning, listOf(previewProvisioning)),
        InstanceGroupUi(InstanceGroup.Running, listOf(previewRunning)),
    ),
    totalCount = 2,
    runningCount = 1,
    progress = InstancesProgressUi.of("正在写入 rootfs… 42%", 0.42f),
)

@Composable
private fun InstancesPreview(
    theme: AppTheme,
    pureBlack: Boolean,
    textScale: Float,
    state: InstancesUiState,
) {
    Oe3Theme(theme = theme, pureBlack = pureBlack, textScale = textScale) {
        InstancesScreen(state = state, actions = InstancesActions())
    }
}

// --- 有实例 -------------------------------------------------------------

@Preview(name = "Running · Dark · 1.0x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesRunningDarkPreview() {
    InstancesPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1f, state = previewWithInstances)
}

@Preview(name = "Running · Dark · 1.3x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesRunningDarkLargePreview() {
    InstancesPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1.3f, state = previewWithInstances)
}

@Preview(name = "Running · Light · 1.0x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesRunningLightPreview() {
    InstancesPreview(ThemeCatalog.moonshotLight, pureBlack = false, textScale = 1f, state = previewWithInstances)
}

@Preview(name = "Running · Light · 1.3x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesRunningLightLargePreview() {
    InstancesPreview(ThemeCatalog.moonshotLight, pureBlack = false, textScale = 1.3f, state = previewWithInstances)
}

@Preview(name = "Running · Pure black · 1.0x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesRunningBlackPreview() {
    InstancesPreview(ThemeCatalog.moonshotDark, pureBlack = true, textScale = 1f, state = previewWithInstances)
}

@Preview(name = "Running · Pure black · 1.3x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesRunningBlackLargePreview() {
    InstancesPreview(ThemeCatalog.moonshotDark, pureBlack = true, textScale = 1.3f, state = previewWithInstances)
}

// --- 空态 ---------------------------------------------------------------

@Preview(name = "Empty · Dark · 1.0x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesEmptyDarkPreview() {
    InstancesPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1f, state = previewEmpty)
}

@Preview(name = "Empty · Dark · 1.3x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesEmptyDarkLargePreview() {
    InstancesPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1.3f, state = previewEmpty)
}

@Preview(name = "Empty · Light · 1.0x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesEmptyLightPreview() {
    InstancesPreview(ThemeCatalog.moonshotLight, pureBlack = false, textScale = 1f, state = previewEmpty)
}

@Preview(name = "Empty · Light · 1.3x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesEmptyLightLargePreview() {
    InstancesPreview(ThemeCatalog.moonshotLight, pureBlack = false, textScale = 1.3f, state = previewEmpty)
}

@Preview(name = "Empty · Pure black · 1.0x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesEmptyBlackPreview() {
    InstancesPreview(ThemeCatalog.moonshotDark, pureBlack = true, textScale = 1f, state = previewEmpty)
}

@Preview(name = "Empty · Pure black · 1.3x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesEmptyBlackLargePreview() {
    InstancesPreview(ThemeCatalog.moonshotDark, pureBlack = true, textScale = 1.3f, state = previewEmpty)
}

// --- 创建进度横幅 -------------------------------------------------------

@Preview(name = "Progress · Dark · 1.3x", group = "instances", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun InstancesProgressDarkLargePreview() {
    InstancesPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1.3f, state = previewWithProgress)
}
