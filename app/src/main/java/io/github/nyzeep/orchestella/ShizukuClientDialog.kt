package io.github.nyzeep.orchestella

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import io.github.nyzeep.orchestella.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.ShizukuClientController
import io.github.nyzeep.orchestella.ui.design.tokens.OeShape
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/**
 * Visible consent flow only; listeners and outstanding requests retire with this dialog.
 *
 * UI-15 §2：内容仍是 View 体系（状态文本 + 三个动作 + 三个页脚按钮，全部由
 * [ShizukuClientController] 驱动），但窗口语言换成与全项目一致的一种——32dp
 * 圆角（`OeShape.alert`）、`surface` 底、主/次动作配色取自 [AppTheme]。
 * 这里不走 `OeDialogHost`：DialogFragment 自己就是生命周期所有者，把内容搬进
 * Activity 覆盖层会丢掉「随宿主销毁自动停客户端」这条既有保证。
 */
class ShizukuClientDialog : DialogFragment() {
    private lateinit var client: ShizukuClientController
    private lateinit var statusText: TextView
    private lateinit var requestButton: MaterialButton
    private lateinit var managerButton: MaterialButton
    private val deadlineHandler = Handler(Looper.getMainLooper())
    private var deadlineRequestCode = -1

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val theme = ThemeStore(context).resolve()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(12))
            background = MaterialShapeDrawable().apply {
                shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCorners(CornerFamily.ROUNDED, OeShape.alert.value * density)
                    .build()
                fillColor = ColorStateList.valueOf(theme.surface)
            }
        }

        content.addView(TextView(context).apply {
            text = getString(R.string.shizuku_client_title)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(theme.text)
        })

        statusText = TextView(context).apply {
            setTextIsSelectable(true)
            textSize = 14f
            setTextColor(ThemeColors.readable(theme.textDim, theme.surface))
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(statusText)

        content.addView(TextView(context).apply {
            text = getString(R.string.shizuku_client_boundary)
            textSize = 13f
            setTextColor(ThemeColors.readable(theme.textDim, theme.surface))
            setPadding(0, dp(12), 0, dp(12))
        })

        fun action(label: Int, click: () -> Unit): MaterialButton =
            MaterialButton(context).apply {
                text = getString(label)
                textSize = 14f
                cornerRadius = (OeShape.sm.value * density).toInt()
                backgroundTintList = ColorStateList.valueOf(ThemeColors.surfaceHigh(theme.surface, theme.text))
                setTextColor(theme.primary)
                setOnClickListener { click() }
                content.addView(this)
            }

        action(R.string.shizuku_refresh) { client.refresh() }
        action(R.string.shizuku_official_setup) { openUrl(AndroidShizukuTransport.SETUP_URL) }
        action(R.string.shizuku_copy_adb) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.wizard_copy_command),
                AvfAuthorizationWizardModel.adbGrantCommand(context.packageName)))
            Toast.makeText(context, R.string.wizard_command_copied, Toast.LENGTH_SHORT).show()
        }

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        requestButton = MaterialButton(context).apply {
            text = getString(R.string.shizuku_request_client)
            textSize = 14f
            cornerRadius = (OeShape.sm.value * density).toInt()
            backgroundTintList = ColorStateList.valueOf(theme.primary)
            setTextColor(ThemeColors.onColor(theme.primary))
            setOnClickListener { client.requestPermission() }
        }
        managerButton = MaterialButton(context).apply {
            text = getString(R.string.shizuku_open_manager)
            textSize = 14f
            cornerRadius = (OeShape.sm.value * density).toInt()
            backgroundTintList = ColorStateList.valueOf(ThemeColors.surfaceHigh(theme.surface, theme.text))
            setTextColor(theme.primary)
            setOnClickListener {
                if (client.snapshot().status() == ShizukuClientController.Status.NOT_INSTALLED) {
                    openUrl(AndroidShizukuTransport.DOWNLOAD_URL)
                } else {
                    openManager()
                }
            }
        }
        val close = MaterialButton(context).apply {
            text = getString(R.string.credentials_close)
            textSize = 14f
            cornerRadius = (OeShape.sm.value * density).toInt()
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(ThemeColors.readable(theme.textDim, theme.surface))
            setOnClickListener { dismiss() }
        }
        footer.addView(requestButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        footer.addView(managerButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(footer)
        content.addView(close)

        client = ShizukuClientController(AndroidShizukuTransport(context)) { state -> render(state) }

        return Dialog(context).apply {
            setContentView(content)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onStart() {
        super.onStart()
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        dialog?.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        client.start()
    }

    override fun onResume() {
        super.onResume()
        if (::client.isInitialized) client.refresh()
    }

    override fun onStop() {
        deadlineHandler.removeCallbacksAndMessages(null)
        deadlineRequestCode = -1
        if (::client.isInitialized) client.stop()
        super.onStop()
    }

    private fun render(state: ShizukuClientController.Snapshot) {
        val context = context ?: return
        val status = when (state.status()) {
            ShizukuClientController.Status.NOT_INSTALLED -> R.string.shizuku_state_missing
            ShizukuClientController.Status.BINDER_PENDING -> R.string.shizuku_state_pending
            ShizukuClientController.Status.UNAUTHORIZED -> R.string.shizuku_state_unauthorized
            ShizukuClientController.Status.REQUESTING -> R.string.shizuku_state_requesting
            ShizukuClientController.Status.DENIED -> R.string.shizuku_state_denied
            ShizukuClientController.Status.DENIED_REMEMBERED -> R.string.shizuku_state_denied_remembered
            ShizukuClientController.Status.AUTHORIZED -> R.string.shizuku_state_authorized
            ShizukuClientController.Status.DEAD -> R.string.shizuku_state_dead
            ShizukuClientController.Status.UNSUPPORTED -> R.string.shizuku_state_unsupported
            ShizukuClientController.Status.TIMED_OUT -> R.string.shizuku_state_timeout
            ShizukuClientController.Status.ERROR -> R.string.shizuku_state_error
            ShizukuClientController.Status.STOPPED -> R.string.shizuku_state_stopped
        }
        val facts = AvfHostFactsProbe.probe(context)
        statusText.text = buildString {
            append(getString(status))
            if (state.serverUid() >= 0) {
                append("\nServer UID: ${state.serverUid()} · API ${state.serverVersion()}")
            }
            append("\n\nMANAGE: ${AvfHostFactsProbe.permissionLabel(facts.manageVirtualMachinePermissionState())}")
            append("\nCUSTOM: ${AvfHostFactsProbe.permissionLabel(facts.useCustomVirtualMachinePermissionState())}")
            append("\n平台访问: ${AvfHostFactsProbe.platformSummary(facts.platformFacts())}")
        }
        requestButton.isEnabled = state.canRequestPermission()
        managerButton.text = getString(
            if (state.status() == ShizukuClientController.Status.NOT_INSTALLED) {
                R.string.shizuku_official_install
            } else {
                R.string.shizuku_open_manager
            },
        )
        if (context is AppCompatActivity) {
            // 夜间模式/强调色变化时按钮配色跟随（与 ThemeApplier 同一真源）。
            val theme = ThemeStore(context).resolve()
            requestButton.backgroundTintList = ColorStateList.valueOf(theme.primary)
            managerButton.backgroundTintList = ColorStateList.valueOf(ThemeColors.surfaceHigh(theme.surface, theme.text))
        }
        val requestCode = state.requestCode()
        if (requestCode != deadlineRequestCode) {
            deadlineHandler.removeCallbacksAndMessages(null)
            deadlineRequestCode = requestCode
            if (requestCode != -1) {
                deadlineHandler.postDelayed({ client.requestTimedOut(requestCode) }, 30_000L)
            }
        }
    }

    private fun openManager() {
        val context = context ?: return
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(AndroidShizukuTransport.MANAGER_PACKAGE)
            if (intent != null) startActivity(intent) else showOpenFailure()
        } catch (_: RuntimeException) {
            showOpenFailure()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: RuntimeException) {
            showOpenFailure()
        }
    }

    private fun showOpenFailure() {
        context?.let { Toast.makeText(it, R.string.shizuku_open_failed, Toast.LENGTH_LONG).show() }
    }
}
