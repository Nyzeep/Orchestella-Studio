package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.compose.runtime.Composable
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.ssh.SshProfile

private const val GIB = 1024L * 1024L * 1024L

@Composable
internal fun PortForwardingScreenshotSample(onBack: () -> Unit = {}) {
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
        onBack = onBack,
        onHostPortChange = {},
        onGuestPortChange = {},
        onDescriptionChange = {},
        onAddRule = {},
        onToggleRule = { _, _ -> },
        onDeleteRule = {},
    )
}

@Composable
internal fun SshScreenshotSample(onBack: () -> Unit = {}) {
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
        onBack = onBack,
        onNew = {},
        onConnect = {},
        onCopy = {},
        onEdit = {},
        onDelete = {},
    )
}

@Composable
internal fun VmHardwareScreenshotSample(onBack: () -> Unit = {}) {
    val profile = HostHardwareProfile(
        8,
        8L * 1024L * 1024L * 1024L,
        4L * 1024L * 1024L * 1024L,
        512L * 1024L * 1024L,
        false,
    )
    val state = VmHardwarePageState(
        VmHardwareConfig(4, 4096, 16L * GIB, true, null),
        profile,
    )
    VmHardwareContent(
        state = state,
        onBack = onBack,
        onCpuChange = state::setCpuCores,
        onRamChange = state::setRamMegabytes,
        onDiskChange = state::setDiskGb,
        onGpuChange = state::setGpuAcceleration,
        onSave = {},
    )
}
