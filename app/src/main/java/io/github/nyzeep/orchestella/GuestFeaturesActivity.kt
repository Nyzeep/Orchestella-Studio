package io.github.nyzeep.orchestella

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import org.json.JSONObject
import java.util.concurrent.Executors

class GuestFeaturesActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var id: String
    private var token: String? = null
    private val status = mutableMapOf<String, TextView>()
    private val buttons = mutableListOf<View>()
    private var busy = false
    private var active = false
    private val refresh = object : Runnable {
        override fun run() {
            if (!active || isFinishing) return
            if (!busy) request("STATUS", null)
            main.postDelayed(this, 2500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra("instanceId") ?: run { finish(); return }
        token = intent.getStringExtra("bridgeToken")
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 32)
        }
        content.addView(TextView(this).apply { text = "桌面与开发工具"; textSize = 24f })
        content.addView(TextView(this).apply {
            text = "可选功能安装在当前 Linux 实例内，失败可重试。桌面与开发服务仍处于实验阶段，当前设备运行验证尚未完成。桌面使用 CPU 拷贝通路。功能以实例配置的普通用户运行；root 或缺失身份须先配置普通用户，sudo 未选不会自动授权。终端始终可返回。"
            textSize = 14f
        })
        val profiles = linkedMapOf("desktop-labwc" to "labwc 轻量 Wayland 桌面 · Debian 13",
            "podman" to "Rootless Podman · 普通用户容器",
            "code-server" to "code-server · 本机开发服务",
            "docker-cli" to "Docker CLI · 客户端与 daemon 分别检查")
        val distro = try { io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.FileStore(
            filesDir.toPath().resolve("instances")).load(id).distro().id() } catch (_: Exception) { "unknown" }
        addDevtoolchainCard(content, distro)
        profiles.filterKeys { profile ->
            if (profile in setOf("desktop-labwc", "docker-cli")) distro == "debian" else distro in setOf("debian", "ubuntu")
        }.forEach { (profile, title) ->
            content.addView(TextView(this).apply { text = title; textSize = 19f; setPadding(0, 24, 0, 8) })
            status[profile] = TextView(this).also { it.text = "尚未查询 guest 状态"; content.addView(it) }
            val row = LinearLayout(this)
            listOf("INSTALL" to "安装/重试", "START" to "启动", "STOP" to "停止", "CANCEL" to "取消任务").forEach { (action, label) ->
                val button = MaterialButton(this).apply {
                    text = label; textSize = 11f
                    setOnClickListener { request(action, profile) }
                }
                ThemeApplier.setRole(button, ThemeApplier.Role.SECONDARY_ACTION)
                buttons.add(button)
                row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            content.addView(row)
            if (profile == "desktop-labwc") content.addView(MaterialButton(this).apply {
                text = "查看真实桌面"
                setOnClickListener { startActivity(DesktopLaunchIntent.create(this@GuestFeaturesActivity, this@GuestFeaturesActivity.id, token, false)) }
            })
            if (profile == "code-server") {
                content.addView(TextView(this).apply { text = "认证密码在 guest 普通用户的私有配置中。请在终端查看后输入登录页；密码不进入访问地址或状态记录。" })
                content.addView(MaterialButton(this).apply {
                    text = "在手机打开本地编辑器"
                    setOnClickListener { startActivity(Intent(this@GuestFeaturesActivity, LocalEditorActivity::class.java)
                        .putExtra("instanceId", this@GuestFeaturesActivity.id).putExtra("bridgeToken", token)) }
                })
            }
        }
        content.addView(MaterialButton(this).apply {
            text = "返回终端"
            setOnClickListener { DualModeNavigation.navigateToTerminal(this@GuestFeaturesActivity, this@GuestFeaturesActivity.id, token) }
        })
        setContentView(ScrollView(this).apply { addView(content) })
        ThemeApplier.bind(this)
    }

    /**
     * The base image deliberately ships a small package set (systemd, ssh, git, curl,
     * nano/tmux…). Compilers and an editor are a one-off install, so this card hands the
     * exact per-distro command to the user instead of silently downloading hundreds of
     * megabytes during first boot. "在终端填入" opens the terminal with the command typed
     * into the existing send-command dialog; nothing runs until the user confirms.
     */
    private fun addDevtoolchainCard(content: LinearLayout, distro: String) {
        val commands = mapOf(
            "ubuntu" to "apt-get update && apt-get install -y --no-install-recommends build-essential python3 python3-venv vim",
            "debian" to "apt-get update && apt-get install -y --no-install-recommends build-essential python3 python3-venv vim",
            "alpine" to "apk add --no-cache build-base python3 py3-pip vim",
            "fedora" to "dnf install -y gcc gcc-c++ make python3 python3-pip vim-enhanced",
            "almalinux" to "dnf install -y gcc gcc-c++ make python3 python3-pip vim-enhanced",
            "rocky" to "dnf install -y gcc gcc-c++ make python3 python3-pip vim-enhanced",
            "arch" to "pacman -Sy --needed --noconfirm base-devel python python-pip vim",
            "opensuse" to "zypper --non-interactive install -y gcc gcc-c++ make python3 python3-pip vim",
        )
        val command = commands[distro] ?: commands.getValue("ubuntu")

        content.addView(TextView(this).apply {
            text = "开发者工具链（基础镜像未预装）"
            textSize = 19f
            setPadding(0, 24, 0, 8)
        })
        content.addView(TextView(this).apply {
            text = "基础环境只装终端、网络与 SSH 工具，gcc / make / python3 / vim 需要一次性安装。" +
                "按当前镜像速度大约 10-20 分钟，装完即可在实例内直接编译。"
            textSize = 14f
        })
        content.addView(TextView(this).apply {
            text = command
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 8, 0, 8)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val toTerminal = MaterialButton(this).apply {
            text = "在终端填入命令"
            textSize = 11f
            setOnClickListener {
                startActivity(
                    DualModeNavigation.createTerminalIntent(this@GuestFeaturesActivity, this@GuestFeaturesActivity.id, token)
                        .putExtra(TerminalActivity.EXTRA_COMMAND_DRAFT, command)
                )
            }
        }
        ThemeApplier.setRole(toTerminal, ThemeApplier.Role.SECONDARY_ACTION)
        val copy = MaterialButton(this).apply {
            text = "复制命令"
            textSize = 11f
            setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Orchestella 开发工具链", command))
                android.widget.Toast.makeText(this@GuestFeaturesActivity, "命令已复制", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        ThemeApplier.setRole(copy, ThemeApplier.Role.SECONDARY_ACTION)
        row.addView(toTerminal, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
    }

    private fun request(action: String, selected: String?) {
        if (busy || worker.isShutdown) return
        busy = true
        buttons.forEach { it.isEnabled = false }
        worker.execute {
            val result = mutableMapOf<String, String>()
            try {
                val client = ExecutionSessionRegistry.get(id)?.bridgeClient()
                    ?: throw IllegalStateException("实例未运行，请先返回终端启动实例")
                if (!client.isReady) throw IllegalStateException("控制连接尚未认证，请在终端等待启动完成")
                val targets = if (selected == null) status.keys.toList() else listOf(selected)
                targets.forEach { profile ->
                    if (isDestroyed || ExecutionSessionRegistry.get(id)?.bridgeClient() !== client || !client.isReady) {
                        throw IllegalStateException("会话已变更，请返回终端重新连接")
                    }
                    val response = client.feature(action, profile)
                    result[profile] = if (!response.accepted()) "请求未受理：${response.responseKind()}" else {
                        val state = JSONObject(response.payload() ?: "{}")
                        val reason = state.optString("reason", "未提供")
                        val identityHint = if (reason in setOf("ordinary-user-action-required", "ordinary-user-missing", "unsafe-instance-identity", "unsafe-ordinary-user", "profile-identity-action-required")) {
                            "\n需要操作：请使用创建时配置的普通 Linux 用户。旧 root 实例可返回终端配置普通用户并显式设置身份；不会使用 root 替代或重置密码。"
                        } else ""
                        "状态：${state.optString("state", "unknown")}\n阶段：${state.optString("phase", state.optString("stage", "未提供"))}" +
                            "\n原因：$reason" + identityHint
                    }
                }
            } catch (failure: Exception) {
                (if (selected == null) status.keys else setOf(selected)).forEach { result[it] = failure.message ?: "连接失败" }
            }
            main.post {
                if (!isDestroyed) {
                    result.forEach { (profile, text) -> status[profile]?.text = text }
                    buttons.forEach { it.isEnabled = true }
                }
                busy = false
            }
        }
    }

    override fun onResume() { super.onResume(); active = true; main.post(refresh) }
    override fun onPause() { active = false; main.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { active = false; main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy() }
}
