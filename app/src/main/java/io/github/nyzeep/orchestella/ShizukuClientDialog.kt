package io.github.nyzeep.orchestella

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import io.github.nyzeep.orchestella.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.ShizukuClientController
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier

/** Visible consent flow only; listeners and outstanding requests retire with this dialog. */
class ShizukuClientDialog : DialogFragment() {
    private lateinit var client: ShizukuClientController
    private lateinit var statusText: TextView
    private val deadlineHandler = Handler(Looper.getMainLooper())
    private var deadlineRequestCode = -1

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        statusText = TextView(context).apply { setTextIsSelectable(true) }
        content.addView(statusText)
        content.addView(TextView(context).apply {
            text = getString(R.string.shizuku_client_boundary)
            val gap = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, gap, 0, gap)
        })
        fun action(label: Int, click: () -> Unit) {
            content.addView(MaterialButton(context).apply {
                text = getString(label)
                ThemeApplier.setRole(this, ThemeApplier.Role.SECONDARY_ACTION)
                setOnClickListener { click() }
            })
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
        client = ShizukuClientController(AndroidShizukuTransport(context)) { state -> render(state) }
        return AlertDialog.Builder(context)
            .setTitle(R.string.shizuku_client_title)
            .setView(content)
            .setPositiveButton(R.string.shizuku_request_client, null)
            .setNeutralButton(R.string.shizuku_open_manager, null)
            .setNegativeButton(R.string.credentials_close, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        ThemeApplier.apply(alert)
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { client.requestPermission() }
        alert.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (client.snapshot().status() == ShizukuClientController.Status.NOT_INSTALLED) {
                openUrl(AndroidShizukuTransport.DOWNLOAD_URL)
            } else {
                openManager()
            }
        }
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
        }
        (dialog as? AlertDialog)?.apply {
            getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = state.canRequestPermission()
            getButton(AlertDialog.BUTTON_NEUTRAL)?.setText(
                if (state.status() == ShizukuClientController.Status.NOT_INSTALLED)
                    R.string.shizuku_official_install else R.string.shizuku_open_manager)
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
