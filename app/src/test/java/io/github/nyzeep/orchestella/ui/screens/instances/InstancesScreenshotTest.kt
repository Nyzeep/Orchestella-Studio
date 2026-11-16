package io.github.nyzeep.orchestella.ui.screens.instances

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
 * 实例页截图（UI3-06）：运行中/空态 × 暗/亮，共 4 张。
 *
 * 状态是纯静态样本，主题与字号来自固定 catalog，动作全为空实现；动效关闭，
 * 所以在干净检出上重放得到同一批像素。golden 在
 * `src/test/screenshots/ui3/instances/`，不改动 UI3-11 已有的 8 张。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class InstancesScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun row(
        id: String,
        distroId: String,
        name: String,
        status: InstanceStatus,
        engine: String?,
        ip: String?,
        terminal: Boolean,
        launch: Boolean,
        delete: Boolean,
    ) = InstanceRowUi(
        id = id,
        shortId = id.take(8),
        distroId = distroId,
        name = name,
        status = status,
        engineLabel = engine,
        specsText = "2 vCPU · 2048 MB · 8 GB",
        ipAddress = ip,
        canOpenTerminal = terminal,
        canLaunch = launch,
        canLaunchDesktop = false,
        canPowerOff = terminal,
        canShowLogs = true,
        canExport = !terminal,
        canDelete = delete,
        hasCrashDiagnostic = status == InstanceStatus.Crashed,
    )

    private fun withInstances() = InstancesUiState(
        groups = listOf(
            InstanceGroupUi(
                InstanceGroup.Running,
                listOf(
                    row(
                        id = "a1b2c3d4-running",
                        distroId = "ubuntu",
                        name = "Ubuntu 24.04",
                        status = InstanceStatus.Running,
                        engine = "QEMU 软件虚拟化",
                        ip = "192.168.1.42",
                        terminal = true,
                        launch = false,
                        delete = false,
                    ),
                ),
            ),
            InstanceGroupUi(
                InstanceGroup.Stopped,
                listOf(
                    row(
                        id = "e5f6a7b8-stopped",
                        distroId = "debian",
                        name = "Debian 12",
                        status = InstanceStatus.Stopped,
                        engine = "QEMU 软件虚拟化",
                        ip = null,
                        terminal = false,
                        launch = true,
                        delete = true,
                    ),
                ),
            ),
            InstanceGroupUi(
                InstanceGroup.Error,
                listOf(
                    row(
                        id = "c9d0e1f2-crashed",
                        distroId = "rocky",
                        name = "Rocky Linux 9",
                        status = InstanceStatus.Crashed,
                        engine = null,
                        ip = null,
                        terminal = false,
                        launch = false,
                        delete = true,
                    ),
                ),
            ),
        ),
        totalCount = 3,
        runningCount = 1,
    )

    private fun empty() = InstancesUiState(emptyList(), totalCount = 0, runningCount = 0)

    private fun capture(name: String, theme: AppTheme, state: InstancesUiState) {
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = true) {
                InstancesScreen(
                    state = state,
                    actions = InstancesActions(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/ui3/instances/$name.png")
    }

    @Test
    fun runningDark() {
        capture("running-dark", ThemeCatalog.moonshotDark, withInstances())
    }

    @Test
    fun runningLight() {
        capture("running-light", ThemeCatalog.moonshotLight, withInstances())
    }

    @Test
    fun emptyDark() {
        capture("empty-dark", ThemeCatalog.moonshotDark, empty())
    }

    @Test
    fun emptyLight() {
        capture("empty-light", ThemeCatalog.moonshotLight, empty())
    }
}
