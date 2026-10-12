package io.github.nyzeep.orchestella.ssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/**
 * Modern Termius-style SSH Profile Manager Dialog.
 * Enables quick one-click SSH connection copying, editing, and launching.
 */
object SshManagerDialog {

    fun show(
        context: Context,
        defaultInstanceId: String? = null,
        defaultHost: String = "127.0.0.1",
        defaultPort: Int = 2222,
        defaultUser: String = "root",
        onConnect: ((SshProfile) -> Unit)? = null
    ) {
        val store = SshProfileStore(context)
        val theme = ThemeStore(context).resolve()

        fun renderDialog() {
            var profiles = store.getAllProfiles()
            if (profiles.isEmpty() && defaultInstanceId != null) {
                // Auto-create local instance profile for convenience
                val defaultProf = SshProfile(
                    label = "本地实例 SSH ($defaultInstanceId)",
                    host = defaultHost,
                    port = defaultPort,
                    username = defaultUser,
                    instanceId = defaultInstanceId
                )
                store.saveOrUpdate(defaultProf)
                profiles = store.getAllProfiles()
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(context, 20), dpToPx(context, 16), dpToPx(context, 20), dpToPx(context, 16))
            }

            val scroll = ScrollView(context).apply {
                addView(container)
            }

            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle("SSH 连接管理器")
                .setView(scroll)
                .setPositiveButton("+ 新建配置", null)
                .setNegativeButton("关闭", null)
                .create()

            fun populateProfiles() {
                container.removeAllViews()
                val currentProfiles = store.getAllProfiles()
                if (currentProfiles.isEmpty()) {
                    val emptyTv = TextView(context).apply {
                        text = "暂无 SSH 连接配置。\n点击下方「+ 新建配置」添加本地或远程 Linux 主机连接。"
                        textSize = 13f
                        setTextColor(theme.textDim)
                        setPadding(0, dpToPx(context, 24), 0, dpToPx(context, 24))
                        gravity = android.view.Gravity.CENTER
                    }
                    container.addView(emptyTv)
                    return
                }

                currentProfiles.forEach { profile ->
                    val card = MaterialCardView(context).apply {
                        radius = dpToPx(context, 16).toFloat()
                        strokeWidth = dpToPx(context, 1)
                        strokeColor = (theme.glow and 0x00FFFFFF) or 0x33000000
                        setCardBackgroundColor(theme.surfaceTranslucent)
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, 0, dpToPx(context, 12))
                        }
                        layoutParams = lp
                    }

                    val cardContent = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dpToPx(context, 16), dpToPx(context, 14), dpToPx(context, 16), dpToPx(context, 14))
                    }

                    val titleRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }

                    val titleText = TextView(context).apply {
                        text = profile.label
                        textSize = 15f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(theme.text)
                        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        layoutParams = lp
                    }
                    titleRow.addView(titleText)

                    val btnDelete = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                        text = "删除"
                        textSize = 12f
                        setTextColor(0xFFEF4444.toInt())
                        setOnClickListener {
                            store.deleteProfile(profile.id)
                            populateProfiles()
                        }
                    }
                    titleRow.addView(btnDelete)
                    cardContent.addView(titleRow)

                    val cmdText = TextView(context).apply {
                        text = profile.buildCliCommand()
                        textSize = 13f
                        typeface = Typeface.MONOSPACE
                        setTextColor(theme.primary)
                        setBackgroundResource(R.drawable.bg_status_badge)
                        setPadding(dpToPx(context, 10), dpToPx(context, 6), dpToPx(context, 10), dpToPx(context, 6))
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, dpToPx(context, 8), 0, dpToPx(context, 12))
                        }
                        layoutParams = lp
                    }
                    cardContent.addView(cmdText)

                    val actionRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }

                    val btnCopy = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "复制命令"
                        textSize = 12f
                        cornerRadius = dpToPx(context, 10)
                        val lp = LinearLayout.LayoutParams(0, dpToPx(context, 38), 1f).apply {
                            setMargins(0, 0, dpToPx(context, 6), 0)
                        }
                        layoutParams = lp
                        setOnClickListener {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SSH Command", profile.buildCliCommand()))
                            Toast.makeText(context, "已复制: ${profile.buildCliCommand()}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    actionRow.addView(btnCopy)

                    val btnConnect = MaterialButton(context).apply {
                        text = "启动连接"
                        textSize = 12f
                        cornerRadius = dpToPx(context, 10)
                        val lp = LinearLayout.LayoutParams(0, dpToPx(context, 38), 1f)
                        layoutParams = lp
                        setOnClickListener {
                            if (onConnect != null) {
                                onConnect(profile)
                                dialog.dismiss()
                            } else {
                                // Try launching standard ssh URI intent
                                try {
                                    val sshUri = Uri.parse(profile.buildUriString())
                                    val intent = Intent(Intent.ACTION_VIEW, sshUri).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    // Fallback to clipboard
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("SSH Command", profile.buildCliCommand()))
                                    Toast.makeText(context, "未找到外部 SSH 客户端，已复制连接命令至剪贴板", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    actionRow.addView(btnConnect)
                    cardContent.addView(actionRow)

                    card.addView(cardContent)
                    container.addView(card)
                }
            }

            populateProfiles()

            dialog.show()
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                showEditProfileDialog(context, null, defaultInstanceId, defaultHost, defaultPort, defaultUser) { newProf ->
                    store.saveOrUpdate(newProf)
                    populateProfiles()
                }
            }
        }

        renderDialog()
    }

    private fun showEditProfileDialog(
        context: Context,
        existing: SshProfile?,
        defaultInstanceId: String?,
        defaultHost: String,
        defaultPort: Int,
        defaultUser: String,
        onSaved: (SshProfile) -> Unit
    ) {
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(context, 20), dpToPx(context, 14), dpToPx(context, 20), dpToPx(context, 14))
        }

        val etLabel = EditText(context).apply {
            hint = "连接名称 (如：本地 Linux 虚拟机)"
            setText(existing?.label ?: "本地 Linux 实例")
        }
        form.addView(etLabel)

        val etHost = EditText(context).apply {
            hint = "主机地址 (如：127.0.0.1 或 局域网 IP)"
            setText(existing?.host ?: defaultHost)
        }
        form.addView(etHost)

        val etPort = EditText(context).apply {
            hint = "端口号 (默认 22 或 转发端口 2222)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(existing?.port?.toString() ?: defaultPort.toString())
        }
        form.addView(etPort)

        val etUser = EditText(context).apply {
            hint = "用户名 (如：root)"
            setText(existing?.username ?: defaultUser)
        }
        form.addView(etUser)

        MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) "新建 SSH 配置" else "编辑 SSH 配置")
            .setView(form)
            .setPositiveButton("保存") { _, _ ->
                val label = etLabel.text.toString().trim().ifEmpty { "SSH 连接" }
                val host = etHost.text.toString().trim().ifEmpty { "127.0.0.1" }
                val port = etPort.text.toString().trim().toIntOrNull() ?: 22
                val user = etUser.text.toString().trim().ifEmpty { "root" }
                val profile = (existing ?: SshProfile(
                    label = label,
                    instanceId = defaultInstanceId
                )).copy(
                    label = label,
                    host = host,
                    port = port,
                    username = user
                )
                onSaved(profile)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dpToPx(context: Context, dp: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt()
}
