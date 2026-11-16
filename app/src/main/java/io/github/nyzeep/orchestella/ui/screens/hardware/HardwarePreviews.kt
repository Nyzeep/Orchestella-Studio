package io.github.nyzeep.orchestella.ui.screens.hardware

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.screens.common.StorageFacts
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog

/**
 * 硬件页预览矩阵：正常/失败 × 暗/亮 × 1.0/1.3 字号。
 *
 * 样本全部是静态公开参数（无真机标识、无序列号），不启动 Activity、
 * 不读磁盘；截图工单（UI3-11）可以直接复用这些 pane 的名称。
 */
private const val PREVIEW_GIB = 1024L * 1024L * 1024L

private val previewReady: HardwareUiState.Ready = HardwareUiState.from(
    device = HardwareDeviceFacts(
        alias = "工作机",
        model = "Pixel 8",
        androidRelease = "15",
        sdkInt = 35,
        abis = listOf("arm64-v8a"),
        socModel = "Tensor G3",
        kernelVersion = "6.1.75-android14",
        screenSpec = "1080×2400 · 420 dpi",
        gpuRenderer = "Mali-G715",
        selinuxStatus = "Enforcing",
    ),
    hardware = HostHardwareProfile(
        8,
        8L * PREVIEW_GIB,
        3L * PREVIEW_GIB,
        2L * PREVIEW_GIB,
        false,
    ),
    storage = StorageFacts(totalBytes = 128L * PREVIEW_GIB, freeBytes = 62L * PREVIEW_GIB),
    battery = HardwareBatteryFacts(percent = 76, temperatureCelsius = 36.5f),
    engines = HardwareEngineFacts(
        avfStatus = AvfCapabilityEvaluator.AvfStatus.READY,
        avfModeSummary = "protected=支持；non-protected=支持",
        qemuAvailable = true,
        umlRunningWidth = 2,
        umlChannelWired = false,
    ),
)

private val previewFailed: HardwareUiState = HardwareUiState.Failed(
    R.string.ui3_hardware_probe_failed_body,
)

@Composable
private fun HardwarePreview(
    theme: AppTheme,
    state: HardwareUiState,
    textScale: Float,
) {
    Oe3Theme(theme = theme, textScale = textScale) {
        HardwareScreen(state = state, actions = HardwareActions())
    }
}

// --- 正常 -----------------------------------------------------------------

@Preview(
    name = "Hardware · Dark · 1.0x",
    group = "hardware",
    widthDp = 393,
    heightDp = 1080,
    showBackground = true,
)
@Composable
fun HardwareReadyDarkPreview() {
    HardwarePreview(theme = ThemeCatalog.moonshotDark, state = previewReady, textScale = 1f)
}

@Preview(
    name = "Hardware · Dark · 1.3x",
    group = "hardware",
    widthDp = 393,
    heightDp = 1080,
    showBackground = true,
)
@Composable
fun HardwareReadyDarkLargePreview() {
    HardwarePreview(theme = ThemeCatalog.moonshotDark, state = previewReady, textScale = 1.3f)
}

@Preview(
    name = "Hardware · Light · 1.0x",
    group = "hardware",
    widthDp = 393,
    heightDp = 1080,
    showBackground = true,
)
@Composable
fun HardwareReadyLightPreview() {
    HardwarePreview(theme = ThemeCatalog.moonshotLight, state = previewReady, textScale = 1f)
}

@Preview(
    name = "Hardware · Light · 1.3x",
    group = "hardware",
    widthDp = 393,
    heightDp = 1080,
    showBackground = true,
)
@Composable
fun HardwareReadyLightLargePreview() {
    HardwarePreview(theme = ThemeCatalog.moonshotLight, state = previewReady, textScale = 1.3f)
}

// --- 探针失败 -------------------------------------------------------------

@Preview(
    name = "Probe failed · Dark · 1.0x",
    group = "hardware",
    widthDp = 393,
    heightDp = 1080,
    showBackground = true,
)
@Composable
fun HardwareFailedDarkPreview() {
    HardwarePreview(theme = ThemeCatalog.moonshotDark, state = previewFailed, textScale = 1f)
}

@Preview(
    name = "Probe failed · Light · 1.0x",
    group = "hardware",
    widthDp = 393,
    heightDp = 1080,
    showBackground = true,
)
@Composable
fun HardwareFailedLightPreview() {
    HardwarePreview(theme = ThemeCatalog.moonshotLight, state = previewFailed, textScale = 1f)
}

// --- 完整参数底部表（内容预览，无需 dialog 窗口）---------------------------

@Preview(
    name = "Full parameters · Dark",
    group = "hardware",
    widthDp = 393,
    heightDp = 900,
    showBackground = true,
)
@Composable
fun HardwareDetailsDarkPreview() {
    Oe3Theme(theme = ThemeCatalog.moonshotDark, textScale = 1f) {
        val colors = Oe3ThemeValues.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background.oe3Color()),
        ) {
            HardwareDetailsContent(groups = previewReady.details)
        }
    }
}
