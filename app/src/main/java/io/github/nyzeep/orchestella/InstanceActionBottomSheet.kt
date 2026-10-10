package io.github.nyzeep.orchestella

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle

/**
 * Context action bottom sheet for virtual machine instances (Ticket 52 / M4).
 *
 * <p>Supports desktop launching, terminal shell, hardware tuning, SAF export,
 * credential management, and cascade physical deletion with strict runtime circuit-breakers.
 */
class InstanceActionBottomSheet(
    private val context: Context,
    private val instance: InstanceLifecycle.Instance,
    private val uiModel: InstanceCardUiModel,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onLaunchDesktop(instance: InstanceLifecycle.Instance)
        fun onOpenTerminal(instance: InstanceLifecycle.Instance)
        fun onConfigureHardware(instance: InstanceLifecycle.Instance)
        fun onExportBackup(instance: InstanceLifecycle.Instance)
        fun onManagePortForwarding(instance: InstanceLifecycle.Instance)
        fun onShowCredentials(instance: InstanceLifecycle.Instance)
        fun onClone(instance: InstanceLifecycle.Instance)
        fun onDelete(instance: InstanceLifecycle.Instance)
    }

    fun show() {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_instance_actions_sheet, null)
        dialog.setContentView(view)

        // Header
        val distroIcon = view.findViewById<ImageView>(R.id.sheet_distro_icon)
        val title = view.findViewById<TextView>(R.id.sheet_title)
        val subtitle = view.findViewById<TextView>(R.id.sheet_subtitle)
        val badge = view.findViewById<TextView>(R.id.sheet_status_badge)

        val iconRes = DistroIconHelper.getIconRes(uiModel.distroId)
        distroIcon.setImageResource(iconRes)
        title.text = "${uiModel.distroDisplayName} Linux ${uiModel.distroVersion}"
        subtitle.text = "ID: ${uiModel.shortId} · ${uiModel.hardwareSpecsText}"
        badge.text = uiModel.stateBadgeText
        badge.setTextColor(context.getColor(uiModel.stateBadgeColorRes))

        // Action 1: Launch Desktop
        val actionDesktop = view.findViewById<View>(R.id.sheet_action_desktop)
        actionDesktop.setOnClickListener {
            dialog.dismiss()
            callbacks.onLaunchDesktop(instance)
        }

        // Action 2: Open Terminal
        val actionTerminal = view.findViewById<View>(R.id.sheet_action_terminal)
        actionTerminal.setOnClickListener {
            dialog.dismiss()
            callbacks.onOpenTerminal(instance)
        }

        // Action 3: Hardware Config (Guarded: only when stopped/created)
        val actionHardware = view.findViewById<View>(R.id.sheet_action_hardware)
        val textHardware = view.findViewById<TextView>(R.id.sheet_text_hardware)
        val hintHardware = view.findViewById<TextView>(R.id.sheet_hint_hardware)
        if (!uiModel.canConfigureHardware) {
            actionHardware.alpha = 0.5f
            hintHardware.text = context.getString(R.string.action_config_hardware_disabled_hint)
            actionHardware.setOnClickListener {
                Toast.makeText(context, R.string.action_config_hardware_disabled_hint, Toast.LENGTH_SHORT).show()
            }
        } else {
            actionHardware.alpha = 1.0f
            actionHardware.setOnClickListener {
                dialog.dismiss()
                callbacks.onConfigureHardware(instance)
            }
        }

        // Action 4: Export Backup
        val actionExport = view.findViewById<View>(R.id.sheet_action_export)
        actionExport.setOnClickListener {
            dialog.dismiss()
            callbacks.onExportBackup(instance)
        }

        // Action: Port Forwarding
        val actionPortForwarding = view.findViewById<View>(R.id.sheet_action_port_forwarding)
        actionPortForwarding.setOnClickListener {
            dialog.dismiss()
            callbacks.onManagePortForwarding(instance)
        }

        // Action 5: Credentials
        val actionCredentials = view.findViewById<View>(R.id.sheet_action_credentials)
        actionCredentials.setOnClickListener {
            dialog.dismiss()
            callbacks.onShowCredentials(instance)
        }

        // Action 6: Clone Instance (Guarded: only when stopped/created)
        val actionClone = view.findViewById<View>(R.id.sheet_action_clone)
        val hintClone = view.findViewById<TextView>(R.id.sheet_hint_clone)
        if (!uiModel.canClone) {
            actionClone.alpha = 0.5f
            hintClone.text = context.getString(R.string.action_clone_running_forbidden)
            actionClone.setOnClickListener {
                Toast.makeText(context, R.string.action_clone_running_forbidden, Toast.LENGTH_SHORT).show()
            }
        } else {
            actionClone.alpha = 1.0f
            actionClone.setOnClickListener {
                dialog.dismiss()
                callbacks.onClone(instance)
            }
        }

        // Action 7: Delete Instance (Guarded: strictly forbidden while running)
        val actionDelete = view.findViewById<View>(R.id.sheet_action_delete)
        val hintDelete = view.findViewById<TextView>(R.id.sheet_hint_delete)
        if (!uiModel.canDelete) {
            actionDelete.alpha = 0.4f
            hintDelete.text = context.getString(R.string.action_delete_running_forbidden)
            actionDelete.setOnClickListener {
                Toast.makeText(context, R.string.action_delete_running_forbidden, Toast.LENGTH_SHORT).show()
            }
        } else {
            actionDelete.alpha = 1.0f
            actionDelete.setOnClickListener {
                dialog.dismiss()
                callbacks.onDelete(instance)
            }
        }

        dialog.show()
    }
}
