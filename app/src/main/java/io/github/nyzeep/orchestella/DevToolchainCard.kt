package io.github.nyzeep.orchestella

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier

/**
 * The base image ships terminal, network and SSH tools only — no compiler, no editor, no
 * python. That is deliberate (a toolchain would add hundreds of megabytes to every first
 * boot), but it left users staring at `gcc: command not found` with no hint about what to
 * do next. This card hands over the exact per-distro command and can pre-fill the terminal's
 * send-command dialog; nothing is executed without the user pressing send.
 */
object DevToolchainCard {

    private val COMMANDS = mapOf(
        "ubuntu" to "apt-get update && apt-get install -y --no-install-recommends build-essential python3 python3-venv vim",
        "debian" to "apt-get update && apt-get install -y --no-install-recommends build-essential python3 python3-venv vim",
        "alpine" to "apk add --no-cache build-base python3 py3-pip vim",
        "fedora" to "dnf install -y gcc gcc-c++ make python3 python3-pip vim-enhanced",
        "almalinux" to "dnf install -y gcc gcc-c++ make python3 python3-pip vim-enhanced",
        "rocky" to "dnf install -y gcc gcc-c++ make python3 python3-pip vim-enhanced",
        "arch" to "pacman -Sy --needed --noconfirm base-devel python python-pip vim",
        "opensuse" to "zypper --non-interactive install -y gcc gcc-c++ make python3 python3-pip vim",
    )

    fun commandFor(distro: String): String = COMMANDS[distro] ?: COMMANDS.getValue("ubuntu")

    /** Builds the card and appends it to [container]; returns the created root view. */
    fun attach(
        activity: Activity,
        container: LinearLayout,
        distro: String,
        instanceId: String,
        bridgeToken: String?,
        atTop: Boolean = false,
    ): View {
        val command = commandFor(distro)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 8)
        }
        root.addView(TextView(activity).apply {
            text = "开发者工具链（基础镜像未预装）"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(activity).apply {
            text = "基础环境只装终端、网络与 SSH 工具，gcc / make / python3 / vim 需要一次性安装" +
                "（按镜像速度约 10-20 分钟，实测 80 MB / 约 8 分钟）。装完即可在实例内直接编译。"
            textSize = 13f
            setPadding(0, 6, 0, 6)
        })
        root.addView(TextView(activity).apply {
            text = command
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(0, 4, 0, 8)
            setTextIsSelectable(true)
        })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val fillIn = MaterialButton(activity).apply {
            text = "在终端填入命令"
            textSize = 11f
            setOnClickListener {
                if (ExecutionSessionRegistry.get(instanceId) == null) {
                    Toast.makeText(activity, "实例未运行：先开机，再把命令粘贴到终端", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                activity.startActivity(
                    DualModeNavigation.createTerminalIntent(activity, instanceId, bridgeToken ?: "")
                        .putExtra(TerminalActivity.EXTRA_COMMAND_DRAFT, command)
                )
            }
        }
        ThemeApplier.setRole(fillIn, ThemeApplier.Role.SECONDARY_ACTION)
        val copy = MaterialButton(activity).apply {
            text = "复制命令"
            textSize = 11f
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Orchestella 开发工具链", command))
                Toast.makeText(activity, "命令已复制", Toast.LENGTH_SHORT).show()
            }
        }
        ThemeApplier.setRole(copy, ThemeApplier.Role.SECONDARY_ACTION)
        row.addView(fillIn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        if (atTop) container.addView(root, 0) else container.addView(root)
        return root
    }
}
