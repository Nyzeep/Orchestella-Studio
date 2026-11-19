package io.github.nyzeep.orchestella

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3BackButton
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Card
import io.github.nyzeep.orchestella.ui.oe3.components.oe3Color
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.oe3.tokens.LocalOe3PageTopPadding
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Shapes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Spacing
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.shell.OeScaffold
import io.github.nyzeep.orchestella.ui.shell.oeScrollOffsetPx
import io.github.nyzeep.orchestella.ui.shell.setOe3HostContent
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import org.json.JSONObject
import java.util.concurrent.Executors

class GuestFeaturesActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var id: String
    private var token: String? = null
    private val statusMap = mutableStateMapOf<String, String>()
    private var busy by mutableStateOf(false)
    private var active = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!active || isFinishing) return
            if (!busy) request("STATUS", null)
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)

        id = intent.getStringExtra("instanceId") ?: run {
            finish()
            return
        }
        token = intent.getStringExtra("bridgeToken")

        val distro = try {
            io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.FileStore(
                filesDir.toPath().resolve("instances"),
            ).load(id).distro().id()
        } catch (_: Exception) {
            "unknown"
        }

        val allProfiles = linkedMapOf(
            "desktop-labwc" to "labwc 轻量 Wayland 桌面 · Debian 13",
            "podman" to "Rootless Podman · 普通用户容器",
            "code-server" to "code-server · 本机开发服务",
            "docker-cli" to "Docker CLI · 客户端与 daemon 分别检查",
        )
        val visibleProfiles = allProfiles.filterKeys { profile ->
            if (profile in setOf("desktop-labwc", "docker-cli")) distro == "debian" else distro in setOf("debian", "ubuntu")
        }
        visibleProfiles.keys.forEach { profile ->
            statusMap[profile] = "尚未查询 guest 状态"
        }

        val toolchainCommand = DEVTOOLCHAIN_COMMANDS[distro] ?: DEVTOOLCHAIN_COMMANDS.getValue("ubuntu")

        setOe3HostContent {
            val desktopRunning = ExecutionSessionRegistry.get(id) != null
            val desktopEnabled = DesktopEntryPolicy.canLaunchDesktop(desktopRunning)
            val desktopDisabledReason = stringResource(DesktopEntryPolicy.disabledReasonRes(desktopRunning))

            GuestFeaturesScreen(
                toolchainCommand = toolchainCommand,
                profiles = visibleProfiles,
                statusMap = statusMap,
                busy = busy,
                desktopEnabled = desktopEnabled,
                desktopDisabledReason = desktopDisabledReason,
                onBack = ::finish,
                onInsertCommand = {
                    startActivity(
                        DualModeNavigation.createTerminalIntent(this, id, token)
                            .putExtra(TerminalActivity.EXTRA_COMMAND_DRAFT, toolchainCommand),
                    )
                    finish()
                },
                onCopyCommand = {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Orchestella 开发工具链", toolchainCommand))
                    Toast.makeText(this, "命令已复制", Toast.LENGTH_SHORT).show()
                },
                onAction = { action, profile -> request(action, profile) },
                onOpenDesktop = {
                    startActivity(DesktopLaunchIntent.create(this, id, token, false))
                },
                onOpenLocalEditor = {
                    startActivity(
                        Intent(this, LocalEditorActivity::class.java)
                            .putExtra("instanceId", id)
                            .putExtra("bridgeToken", token),
                    )
                },
                onReturnToTerminal = {
                    DualModeNavigation.navigateToTerminal(this, id, token)
                },
            )
        }
    }

    private fun request(action: String, selected: String?) {
        if (busy || worker.isShutdown) return
        busy = true
        worker.execute {
            val result = mutableMapOf<String, String>()
            try {
                val client = ExecutionSessionRegistry.get(id)?.bridgeClient()
                    ?: throw IllegalStateException("实例未运行，请先返回终端启动实例")
                if (!client.isReady) throw IllegalStateException("控制连接尚未认证，请在终端等待启动完成")
                val targets = if (selected == null) statusMap.keys.toList() else listOf(selected)
                targets.forEach { profile ->
                    if (isDestroyed || ExecutionSessionRegistry.get(id)?.bridgeClient() !== client || !client.isReady) {
                        throw IllegalStateException("会话已变更，请返回终端重新连接")
                    }
                    val response = client.feature(action, profile)
                    result[profile] = if (!response.accepted()) {
                        "请求未受理：${response.responseKind()}"
                    } else {
                        val state = JSONObject(response.payload() ?: "{}")
                        val reason = state.optString("reason", "未提供")
                        val identityHint = if (reason in IDENTITY_REASONS) {
                            "\n需要操作：请使用创建时配置的普通 Linux 用户。旧 root 实例可返回终端配置普通用户并显式设置身份；不会使用 root 替代或重置密码。"
                        } else {
                            ""
                        }
                        "状态：${state.optString("state", "unknown")}\n阶段：${state.optString("phase", state.optString("stage", "未提供"))}" +
                            "\n原因：$reason" + identityHint
                    }
                }
            } catch (failure: Exception) {
                (if (selected == null) statusMap.keys else setOf(selected)).forEach {
                    result[it] = failure.message ?: "连接失败"
                }
            }
            main.post {
                if (!isDestroyed) {
                    result.forEach { (profile, text) -> statusMap[profile] = text }
                }
                busy = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        main.post(refresh)
    }

    override fun onPause() {
        active = false
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        active = false
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2500L
        private val IDENTITY_REASONS = setOf(
            "ordinary-user-action-required",
            "ordinary-user-missing",
            "unsafe-instance-identity",
            "unsafe-ordinary-user",
            "profile-identity-action-required",
        )
        private val DEVTOOLCHAIN_COMMANDS = mapOf(
            "ubuntu" to "apt-get update && apt-get install -y --no-install-recommends build-essential python3 python3-venv vim",
            "debian" to "apt-get update && apt-get install -y --no-install-recommends build-essential python3 python3-venv vim",
            "alpine" to "apk add --no-cache build-base python3 py3-pip vim",
            "fedora" to "dnf install -y gcc gcc-c++ make python3 python3-pip vim-enhanced",
            "almalinux" to "dnf install -y gcc gcc-c++ make python3 python3-pip vim-enhanced",
            "rocky" to "dnf install -y gcc gcc-c++ make python3 python3-pip vim-enhanced",
            "arch" to "pacman -Sy --needed --noconfirm base-devel python python-pip vim",
            "opensuse" to "zypper --non-interactive install -y gcc gcc-c++ make python3 python3-pip vim",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestFeaturesScreen(
    toolchainCommand: String,
    profiles: Map<String, String>,
    statusMap: Map<String, String>,
    busy: Boolean,
    desktopEnabled: Boolean,
    desktopDisabledReason: String,
    onBack: () -> Unit,
    onInsertCommand: () -> Unit,
    onCopyCommand: () -> Unit,
    onAction: (String, String) -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenLocalEditor: () -> Unit,
    onReturnToTerminal: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    val scrollState = rememberScrollState()
    val backDescription = stringResource(R.string.wizard_back_desc)
    OeScaffold(
        title = "桌面与开发工具",
        leading = {
            Oe3BackButton(
                onBack = onBack,
                contentDescription = backDescription,
            )
        },
        scrollOffsetPx = { scrollState.oeScrollOffsetPx() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = Oe3Spacing.pageInset,
                    end = Oe3Spacing.pageInset,
                    top = LocalOe3PageTopPadding.current + Oe3Spacing.sm,
                    bottom = Oe3Spacing.xxl,
                ),
            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.md),
        ) {
            Text(
                text = "可选功能安装在当前 Linux 实例内，失败可重试。桌面与开发服务仍处于实验阶段，当前设备运行验证尚未完成。桌面使用 CPU 拷贝通路。功能以实例配置的普通用户运行；root 或缺失身份须先配置普通用户，sudo 未选不会自动授权。终端始终可返回。",
                style = Oe3Typography.style(Oe3TextRole.Body),
                color = colors.onSurfaceVariant.oe3Color(),
            )

            Oe3Card {
                Text(
                    text = "开发者工具链（基础镜像未预装）",
                    style = Oe3Typography.style(Oe3TextRole.Section),
                    color = colors.onSurface.oe3Color(),
                )
                Text(
                    text = "基础环境只装终端、网络与 SSH 工具，gcc / make / python3 / vim 需要一次性安装。按当前镜像速度大约 10-20 分钟，装完即可在实例内直接编译。",
                    style = Oe3Typography.style(Oe3TextRole.Body),
                    color = colors.onSurfaceVariant.oe3Color(),
                    modifier = Modifier.padding(top = Oe3Spacing.xs),
                )
                Text(
                    text = toolchainCommand,
                    style = Oe3Typography.style(Oe3TextRole.Mono),
                    color = colors.onSurface.oe3Color(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Oe3Spacing.sm)
                        .clip(RoundedCornerShape(Oe3Shapes.md))
                        .background(colors.surfaceHigh.oe3Color())
                        .padding(Oe3Spacing.sm),
                )
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Oe3Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
                ) {
                    Oe3Button(
                        text = "在终端填入命令",
                        onClick = onInsertCommand,
                        style = Oe3ButtonStyle.Tonal,
                    )
                    Oe3Button(
                        text = "复制命令",
                        onClick = onCopyCommand,
                        style = Oe3ButtonStyle.Tonal,
                    )
                }
            }

            profiles.forEach { (profile, title) ->
                Oe3Card {
                    Text(
                        text = title,
                        style = Oe3Typography.style(Oe3TextRole.Section),
                        color = colors.onSurface.oe3Color(),
                    )
                    Text(
                        text = statusMap[profile] ?: "尚未查询 guest 状态",
                        style = Oe3Typography.style(Oe3TextRole.Meta),
                        color = colors.onSurfaceVariant.oe3Color(),
                        modifier = Modifier.padding(top = Oe3Spacing.xs),
                    )
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Oe3Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
                        verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
                    ) {
                        listOf(
                            "INSTALL" to "安装/重试",
                            "START" to "启动",
                            "STOP" to "停止",
                            "CANCEL" to "取消任务",
                        ).forEach { (action, label) ->
                            Oe3Button(
                                text = label,
                                onClick = { onAction(action, profile) },
                                enabled = !busy,
                                style = Oe3ButtonStyle.Tonal,
                            )
                        }
                    }
                    if (profile == "desktop-labwc") {
                        Column(
                            modifier = Modifier.padding(top = Oe3Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
                        ) {
                            Oe3Button(
                                text = "查看真实桌面",
                                onClick = onOpenDesktop,
                                enabled = desktopEnabled,
                                style = Oe3ButtonStyle.Pill,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (!desktopEnabled) {
                                Text(
                                    text = desktopDisabledReason,
                                    style = Oe3Typography.style(Oe3TextRole.Caption),
                                    color = colors.onSurfaceVariant.oe3Color(),
                                )
                            }
                        }
                    }
                    if (profile == "code-server") {
                        Column(
                            modifier = Modifier.padding(top = Oe3Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Oe3Spacing.xs),
                        ) {
                            Text(
                                text = "认证密码在 guest 普通用户的私有配置中。请在终端查看后输入登录页；密码不进入访问地址或状态记录。",
                                style = Oe3Typography.style(Oe3TextRole.Caption),
                                color = colors.onSurfaceVariant.oe3Color(),
                            )
                            Oe3Button(
                                text = "在手机打开本地编辑器",
                                onClick = onOpenLocalEditor,
                                style = Oe3ButtonStyle.Tonal,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Oe3Button(
                text = "返回终端",
                onClick = onReturnToTerminal,
                style = Oe3ButtonStyle.Tonal,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
