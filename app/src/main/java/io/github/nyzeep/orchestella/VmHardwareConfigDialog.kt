package io.github.nyzeep.orchestella

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle

/**
 * VMware-style hardware customization dialog (Ticket 52 / M4).
 *
 * <p>Integrates with {@link HostHardwareDetector} and {@link HostHardwareProfile}
 * to dynamically present green/yellow/red safety zones for vCPU, RAM, and sparse disk expansion.
 */
class VmHardwareConfigDialog(
    private val context: Context,
    private val instance: InstanceLifecycle.Instance,
    private val hostProfile: HostHardwareProfile,
    private val onSaved: (newConfig: VmHardwareConfig, expandedDiskBytes: Long?) -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_vm_hardware_config_dialog, null)
        dialog.setContentView(view)

        val currentConfig = instance.hardwareConfig() ?: VmHardwareConfig.defaultConfig()

        // 1. vCPU Cores
        val maxCores = Math.min(VmHardwareConfig.MAX_CPU_CORES, hostProfile.totalCores())
        val vcpuLabel = view.findViewById<TextView>(R.id.hw_vcpu_label)
        val vcpuRecBadge = view.findViewById<TextView>(R.id.hw_vcpu_rec_badge)
        val vcpuSeekbar = view.findViewById<SeekBar>(R.id.hw_vcpu_seekbar)

        vcpuRecBadge.text = context.getString(R.string.hardware_vcpu_recommended, hostProfile.recommendedCpuCores())
        vcpuSeekbar.max = Math.max(0, maxCores - 1)
        var selectedCores = Math.min(currentConfig.cpuCores(), maxCores)
        vcpuSeekbar.progress = Math.max(0, selectedCores - 1)
        vcpuLabel.text = context.getString(R.string.hardware_vcpu_label, selectedCores)

        vcpuSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedCores = progress + 1
                vcpuLabel.text = context.getString(R.string.hardware_vcpu_label, selectedCores)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 2. RAM Size
        val stepMb = 256
        val minRamMb = VmHardwareConfig.MIN_RAM_MEGABYTES // 512
        val maxRamMb = Math.min(VmHardwareConfig.MAX_RAM_MEGABYTES, hostProfile.totalRamMb())
        val ramSteps = Math.max(1, (maxRamMb - minRamMb) / stepMb)

        val ramLabel = view.findViewById<TextView>(R.id.hw_ram_label)
        val ramRecBadge = view.findViewById<TextView>(R.id.hw_ram_rec_badge)
        val ramZoneStatus = view.findViewById<TextView>(R.id.hw_ram_zone_status)
        val ramSeekbar = view.findViewById<SeekBar>(R.id.hw_ram_seekbar)
        val btnSave = view.findViewById<Button>(R.id.hw_btn_save)

        val recRam = hostProfile.recommendedRamMegabytesAligned(stepMb)
        ramRecBadge.text = context.getString(R.string.hardware_ram_recommended, recRam)

        ramSeekbar.max = ramSteps
        var selectedRamMb = Math.max(minRamMb, Math.min(currentConfig.ramMegabytes(), maxRamMb))
        ramSeekbar.progress = Math.max(0, (selectedRamMb - minRamMb) / stepMb)

        fun updateRamZoneUi(ramMb: Int) {
            val zone = hostProfile.evaluateRamZone(ramMb)
            ramLabel.text = context.getString(R.string.hardware_ram_label, ramMb)
            when (zone) {
                HostHardwareProfile.SafetyZone.GREEN -> {
                    ramZoneStatus.text = context.getString(R.string.hardware_ram_zone_green)
                    ramZoneStatus.setTextColor(context.getColor(R.color.zone_green))
                    ramZoneStatus.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.zone_green_bg))
                    btnSave.isEnabled = true
                }
                HostHardwareProfile.SafetyZone.YELLOW -> {
                    ramZoneStatus.text = context.getString(R.string.hardware_ram_zone_yellow)
                    ramZoneStatus.setTextColor(context.getColor(R.color.zone_yellow))
                    ramZoneStatus.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.zone_yellow_bg))
                    btnSave.isEnabled = true
                }
                HostHardwareProfile.SafetyZone.RED -> {
                    ramZoneStatus.text = context.getString(R.string.hardware_ram_zone_red)
                    ramZoneStatus.setTextColor(context.getColor(R.color.zone_red))
                    ramZoneStatus.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.zone_red_bg))
                    btnSave.isEnabled = false // Block saving in dangerous red zone
                }
            }
        }
        updateRamZoneUi(selectedRamMb)

        ramSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedRamMb = minRamMb + progress * stepMb
                updateRamZoneUi(selectedRamMb)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Disk Expansion
        val currentDiskGb = (currentConfig.diskCapacityBytes() / (1024L * 1024L * 1024L)).toInt()
        val maxDiskGb = 64
        val diskLabel = view.findViewById<TextView>(R.id.hw_disk_label)
        val diskStatus = view.findViewById<TextView>(R.id.hw_disk_status)
        val diskSeekbar = view.findViewById<SeekBar>(R.id.hw_disk_seekbar)

        val diskSteps = Math.max(0, maxDiskGb - currentDiskGb)
        diskSeekbar.max = diskSteps
        diskSeekbar.progress = 0
        var selectedDiskGb = currentDiskGb

        diskLabel.text = context.getString(R.string.hardware_disk_label, currentDiskGb)
        diskStatus.text = "当前容量: ${currentDiskGb} GB"

        diskSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedDiskGb = currentDiskGb + progress
                diskLabel.text = context.getString(R.string.hardware_disk_label, selectedDiskGb)
                diskStatus.text = if (selectedDiskGb > currentDiskGb) {
                    "将扩容至: ${selectedDiskGb} GB (+${selectedDiskGb - currentDiskGb} GB)"
                } else {
                    "保持当前容量: ${currentDiskGb} GB"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 4. GPU 3D Switch
        val gpuSwitch = view.findViewById<MaterialSwitch>(R.id.hw_gpu_switch)
        gpuSwitch.isChecked = currentConfig.gpuAccelerationEnabled()

        // 5. Buttons
        val btnCancel = view.findViewById<Button>(R.id.hw_btn_cancel)
        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val expandedDiskBytes = if (selectedDiskGb > currentDiskGb) {
                selectedDiskGb.toLong() * 1024L * 1024L * 1024L
            } else {
                null
            }
            val newConfig = VmHardwareConfig.builder()
                .cpuCores(selectedCores)
                .ramMegabytes(selectedRamMb)
                .diskCapacityBytes(expandedDiskBytes ?: currentConfig.diskCapacityBytes())
                .gpuAccelerationEnabled(gpuSwitch.isChecked)
                .extraKernelArguments(currentConfig.extraKernelArguments())
                .build()

            dialog.dismiss()
            onSaved(newConfig, expandedDiskBytes)
        }

        dialog.show()
    }
}
