package io.github.nyzeep.orchestella.ui.screens.dashboard

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 工作站页截图（UI3-11A 补录）：概览态（有实例）暗/亮各一张。
 *
 * golden 收进 `src/test/screenshots/ui3/dashboard/`，命名与 UI3-06/08 的
 * `<state>-<mode>` 约定一致；样本为纯静态 [DashboardUiState] 字面量
 * （与 `DashboardPreviews.kt` 的「有实例」矩阵同源，仅把示例实例 id
 * `preview-ubuntu` 改名 `golden-ubuntu`、第二条最近实例 Fedora 41 换成
 * Rocky Linux 9），动作全为空实现，动效
 * 关闭，所以干净检出重放得到同一批像素。页面未暴露 screen testTag，
 * 按既有约定捕获整窗根节点（UI3-06 同款）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class DashboardScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overviewDark() {
        capture("overview-dark", ThemeCatalog.moonshotDark)
    }

    @Test
    fun overviewLight() {
        capture("overview-light", ThemeCatalog.moonshotLight)
    }

    private fun capture(name: String, theme: AppTheme) {
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = true) {
                DashboardScreen(
                    state = overviewState(),
                    actions = DashboardActions(),
                )
            }
        }
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/ui3/dashboard/$name.png",
        )
    }

    /** 概览样本：一台运行中的主实例 + 存储环 + 紧凑指标 + 两条最近实例。 */
    private fun overviewState() = DashboardUiState(
        instanceCount = 3,
        runningCount = 1,
        primary = DashboardInstanceUi(
            id = "golden-ubuntu",
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
            DashboardRecentUi("golden-debian", "debian", "Debian 12", DashboardStatus.Stopped),
            DashboardRecentUi("golden-rocky", "rocky", "Rocky Linux 9", DashboardStatus.Crashed),
        ),
    )

    private companion object {
        const val GIB = 1024L * 1024L * 1024L
    }
}
