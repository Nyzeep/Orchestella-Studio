package io.github.nyzeep.orchestella.ui.screens.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/**
 * 工作站页预览矩阵：有实例/无实例 × 暗/亮/纯黑 × 1.0/1.3 字号。
 *
 * 只依赖 [DashboardUiState] 的静态样本，不启动 Activity、不读磁盘；
 * 截图工单（UI3-11）可以直接复用这些 pane 的名称。
 */
private const val GIB = 1024L * 1024L * 1024L

private val previewWithInstances = DashboardUiState(    instanceCount = 3,
    runningCount = 1,
    primary = DashboardInstanceUi(
        id = "preview-ubuntu",
        distroId = "ubuntu",
        name = "Ubuntu 24.04",
        status = DashboardStatus.Running,
        engineLabel = "QEMU 软件虚拟化",
        cpuCores = 4,
        ramMb = 4096,
        diskGb = 16,
        canOpenTerminal = true,
    ),
    storage = DashboardStorageUi(
        totalBytes = 128L * GIB,
        usedBytes = 66L * GIB,
        fraction = 0.5156f,
        percent = 52,
    ),
    cpu = DashboardCpuUi(hostCores = 8, instanceCores = 4, fraction = 0.5f, enabled = true),
    ram = DashboardRamUi(
        usedBytes = 5L * GIB,
        totalBytes = 8L * GIB,
        fraction = 0.625f,
        enabled = true,
    ),
    recent = listOf(
        DashboardRecentUi("preview-debian", "debian", "Debian 12", DashboardStatus.Stopped),
        DashboardRecentUi("preview-fedora", "fedora", "Fedora 41", DashboardStatus.Crashed),
    ),
)

private val previewWithoutInstances = DashboardUiState(
    instanceCount = 0,
    runningCount = 0,
    primary = null,
    storage = DashboardStorageUi(
        totalBytes = 128L * GIB,
        usedBytes = 42L * GIB,
        fraction = 0.328f,
        percent = 33,
    ),
    cpu = DashboardCpuUi(hostCores = 8, instanceCores = 0, fraction = 0f, enabled = false),
    ram = DashboardRamUi(usedBytes = 0L, totalBytes = 8L * GIB, fraction = 0f, enabled = false),
    recent = emptyList(),
)

@Composable
private fun DashboardPreview(
    theme: AppTheme,
    pureBlack: Boolean,
    textScale: Float,
    state: DashboardUiState,
) {
    Oe3Theme(theme = theme, pureBlack = pureBlack, textScale = textScale) {
        DashboardScreen(state = state, actions = DashboardActions())
    }
}

// --- 有实例 -------------------------------------------------------------

@Preview(name = "Instances · Dark · 1.0x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardInstancesDarkPreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1f, state = previewWithInstances)
}

@Preview(name = "Instances · Dark · 1.3x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardInstancesDarkLargePreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1.3f, state = previewWithInstances)
}

@Preview(name = "Instances · Light · 1.0x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardInstancesLightPreview() {
    DashboardPreview(ThemeCatalog.moonshotLight, pureBlack = false, textScale = 1f, state = previewWithInstances)
}

@Preview(name = "Instances · Light · 1.3x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardInstancesLightLargePreview() {
    DashboardPreview(ThemeCatalog.moonshotLight, pureBlack = false, textScale = 1.3f, state = previewWithInstances)
}

@Preview(name = "Instances · Pure black · 1.0x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardInstancesBlackPreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = true, textScale = 1f, state = previewWithInstances)
}

@Preview(name = "Instances · Pure black · 1.3x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardInstancesBlackLargePreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = true, textScale = 1.3f, state = previewWithInstances)
}

// --- 无实例 -------------------------------------------------------------

@Preview(name = "Empty · Dark · 1.0x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardEmptyDarkPreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1f, state = previewWithoutInstances)
}

@Preview(name = "Empty · Dark · 1.3x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardEmptyDarkLargePreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1.3f, state = previewWithoutInstances)
}

@Preview(name = "Empty · Light · 1.0x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardEmptyLightPreview() {
    DashboardPreview(ThemeCatalog.moonshotLight, pureBlack = false, textScale = 1f, state = previewWithoutInstances)
}

@Preview(name = "Empty · Light · 1.3x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardEmptyLightLargePreview() {
    DashboardPreview(ThemeCatalog.moonshotLight, pureBlack = false, textScale = 1.3f, state = previewWithoutInstances)
}

@Preview(name = "Empty · Pure black · 1.0x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardEmptyBlackPreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = true, textScale = 1f, state = previewWithoutInstances)
}

@Preview(name = "Empty · Pure black · 1.3x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardEmptyBlackLargePreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = true, textScale = 1.3f, state = previewWithoutInstances)
}

// --- 非运行主卡（禁用终端 + 错误 chip）---------------------------------

private fun primaryWith(status: DashboardStatus) = previewWithInstances.copy(
    primary = previewWithInstances.primary!!.copy(
        status = status,
        engineLabel = null,
        canOpenTerminal = status.canOpenTerminal,
    ),
)

@Preview(name = "Stopped · Dark · 1.3x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardStoppedDarkLargePreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1.3f, state = primaryWith(DashboardStatus.Stopped))
}

@Preview(name = "Crashed · Dark · 1.3x", group = "dashboard", widthDp = 393, heightDp = 1080, showBackground = true)
@Composable
fun DashboardCrashedDarkLargePreview() {
    DashboardPreview(ThemeCatalog.moonshotDark, pureBlack = false, textScale = 1.3f, state = primaryWith(DashboardStatus.Crashed))
}
