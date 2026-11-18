package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.ssh.SshProfile
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3Theme
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UI-13 基线：三张新增二级页各浅色/深色一张，共六张。
 *
 * 只捕获页面本体（外壳底色由测试补齐），样本为字面量，动效关闭。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h1080dp-mdpi")
class ConnectionsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val gib = 1024L * 1024L * 1024L

    private fun capture(name: String, theme: AppTheme, content: @Composable () -> Unit) {
        compose.setContent {
            Oe3Theme(theme = theme, reduceMotion = true) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(Oe3ThemeValues.colors.background)),
                ) {
                    content()
                }
            }
        }
        compose.onRoot().captureRoboImage(
            "src/test/screenshots/ui4/connections/$name.png",
        )
    }

    @Composable
    private fun portForwardingSample() {
        PortForwardingContent(
            state = PortForwardingPageUiState(
                hasLiveEngine = true,
                rules = listOf(
                    PortForwardingRuleRow(
                        id = "rule-web",
                        protocol = "TCP",
                        hostPort = 8080,
                        guestPort = 80,
                        description = "Web service · 已应用到 QEMU 网关",
                        enabled = true,
                        canToggle = true,
                    ),
                    PortForwardingRuleRow(
                        id = "rule-ssh",
                        protocol = "TCP",
                        hostPort = 2222,
                        guestPort = 22,
                        description = "SSH · 已保存配置，当前未确认生效",
                        enabled = false,
                        canToggle = true,
                    ),
                ),
            ),
            onBack = {},
            onHostPortChange = {},
            onGuestPortChange = {},
            onDescriptionChange = {},
            onAddRule = {},
            onToggleRule = { _, _ -> },
            onDeleteRule = {},
        )
    }

    @Composable
    private fun sshSample() {
        SshManagerContent(
            profiles = listOf(
                SshProfile(
                    id = "ssh-local",
                    label = "本地 Linux 实例",
                    host = "127.0.0.1",
                    port = 2222,
                    username = "root",
                ),
                SshProfile(
                    id = "ssh-remote",
                    label = "构建服务器",
                    host = "192.0.2.10",
                    port = 22,
                    username = "builder",
                ),
            ),
            onBack = {},
            onNew = {},
            onConnect = {},
            onCopy = {},
            onEdit = {},
            onDelete = {},
        )
    }

    @Composable
    private fun vmSample() {
        val profile = HostHardwareProfile(
            8,
            8L * 1024L * 1024L * 1024L,
            4L * 1024L * 1024L * 1024L,
            512L * 1024L * 1024L,
            false,
        )
        val state = VmHardwarePageState(
            VmHardwareConfig(4, 4096, 16L * gib, true, null),
            profile,
        )
        VmHardwareContent(
            state = state,
            onBack = {},
            onCpuChange = state::setCpuCores,
            onRamChange = state::setRamMegabytes,
            onDiskChange = state::setDiskGb,
            onGpuChange = state::setGpuAcceleration,
            onSave = {},
        )
    }

    @Test
    fun portForwardingDark() {
        capture("port-forwarding-dark", ThemeCatalog.moonshotDark) { portForwardingSample() }
    }

    @Test
    fun portForwardingLight() {
        capture("port-forwarding-light", ThemeCatalog.moonshotLight) { portForwardingSample() }
    }

    @Test
    fun sshManagerDark() {
        capture("ssh-manager-dark", ThemeCatalog.moonshotDark) { sshSample() }
    }

    @Test
    fun sshManagerLight() {
        capture("ssh-manager-light", ThemeCatalog.moonshotLight) { sshSample() }
    }

    @Test
    fun vmHardwareDark() {
        capture("vm-hardware-dark", ThemeCatalog.moonshotDark) { vmSample() }
    }

    @Test
    fun vmHardwareLight() {
        capture("vm-hardware-light", ThemeCatalog.moonshotLight) { vmSample() }
    }
}
