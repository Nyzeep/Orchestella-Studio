package io.github.nyzeep.orchestella

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.capability.DeviceCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore
import io.github.nyzeep.orchestella.runtime.network.PortConflictDetector
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.ui.InstanceDetailViewModel
import io.github.nyzeep.orchestella.ui.InstanceDetailViewModel.InstanceDetailUiState
import io.github.nyzeep.orchestella.ui.InstanceDetailViewModel.UpdateHardwareResult
import java.nio.file.Files
import java.time.Clock
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Fullscreen Level-2 Instance Detail Console (Ticket 04).
 *
 * <p>Benchmarked against VMware Workstation Console and UTM Manager:
 * <ul>
 *   <li>Section 1: Status billboard & primary control action buttons (Terminal, Desktop, Power).</li>
 *   <li>Section 2: Hardware specs editor with strict runtime lock and safety zone indicators.</li>
 *   <li>Section 3: Storage physical/logical breakdown, sparse expansion, and SAF backup export.</li>
 *   <li>Section 4: Networking and dynamic port forwarding rule manager.</li>
 *   <li>Section 5: Danger Zone: Clone, Reset deadlock state, and 2-step verification delete.</li>
 * </ul>
 */
class InstanceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INSTANCE_ID = "extra_instance_id"
    }

    private lateinit var viewModel: InstanceDetailViewModel
    private var hostProfile: HostHardwareProfile? = null
    private val operations = Executors.newSingleThreadExecutor()

    // Views - Section 1
    private lateinit var toolbar: MaterialToolbar
    private lateinit var distroIcon: ImageView
    private lateinit var instanceTitle: TextView
    private lateinit var instanceIdText: TextView
    private lateinit var statusBadge: TextView
    private lateinit var specsSummary: TextView
    private lateinit var ipAddressText: TextView
    private lateinit var btnTerminal: MaterialButton
    private lateinit var btnDesktop: MaterialButton
    private lateinit var btnPower: MaterialButton
    private lateinit var btnGuestLog: MaterialButton
    private lateinit var btnAppLog: MaterialButton

    // Views - Section 2
    private lateinit var hwLockedHint: TextView
    private lateinit var vcpuLabel: TextView
    private lateinit var vcpuSeekBar: SeekBar
    private lateinit var ramLabel: TextView
    private lateinit var ramSeekBar: SeekBar
    private lateinit var ramSafetyZone: TextView
    private lateinit var btnSaveHardware: MaterialButton

    // Views - Section 3
    private lateinit var storagePhysicalText: TextView
    private lateinit var storageLogicalText: TextView
    private lateinit var btnExpandDisk: MaterialButton
    private lateinit var btnExportBackup: MaterialButton

    // Views - Section 4
    private lateinit var networkIpText: TextView
    private lateinit var btnManagePortForwarding: MaterialButton

    // Views - Section 5
    private lateinit var btnDangerClone: MaterialButton
    private lateinit var btnDangerReset: MaterialButton
    private lateinit var btnDangerDelete: MaterialButton

    // Slider mutable state
    private var selectedVcpu: Int = 2
    private var selectedRamMb: Int = 2048
    private val minRamMb = VmHardwareConfig.MIN_RAM_MEGABYTES // 512
    private val stepRamMb = 256

    private val safExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { destUri ->
            val state = viewModel.uiState ?: return@let
            operations.execute {
                try {
                    val adapter = AndroidSafExportAdapter(this, Clock.systemUTC())
                    adapter.exportToDocumentUri(state.instance, destUri)
                    runOnUiThread {
                        Toast.makeText(this, "实例备份导出成功", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instance_detail)

        // Edge-to-Edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = findViewById<View>(R.id.detail_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(
                statusBarInsets.left,
                statusBarInsets.top,
                statusBarInsets.right,
                navBarInsets.bottom
            )
            insets
        }

        bindViews()

        toolbar.setNavigationOnClickListener { finish() }

        // Initialize ViewModel & capabilities
        val instanceStore = InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"))
        hostProfile = HostHardwareDetector.detect(this)
        val supportsDesktop = currentSupportsDesktop()

        viewModel = InstanceDetailViewModel(
            instanceStore = instanceStore,
            hostProfile = hostProfile,
            supportsDesktop = supportsDesktop
        )

        viewModel.onStateChanged = { state ->
            runOnUiThread {
                if (state != null) {
                    renderState(state)
                } else {
                    finish()
                }
            }
        }

        setupActionListeners()

        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
        if (instanceId == null) {
            Toast.makeText(this, "未指定实例 ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val initialState = viewModel.loadInstance(instanceId)
        if (initialState == null) {
            Toast.makeText(this, "实例不存在或已删除", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initHardwareSliders(initialState)
    }

    override fun onResume() {
        super.onResume()
        val current = viewModel.uiState ?: return
        viewModel.loadInstance(current.instanceId)
    }

    override fun onDestroy() {
        super.onDestroy()
        operations.shutdown()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)

        // Section 1
        distroIcon = findViewById(R.id.detail_distro_icon)
        instanceTitle = findViewById(R.id.detail_instance_title)
        instanceIdText = findViewById(R.id.detail_instance_id)
        statusBadge = findViewById(R.id.detail_status_badge)
        specsSummary = findViewById(R.id.detail_specs_summary)
        ipAddressText = findViewById(R.id.detail_ip_address)
        btnTerminal = findViewById(R.id.btn_detail_terminal)
        btnDesktop = findViewById(R.id.btn_detail_desktop)
        btnPower = findViewById(R.id.btn_detail_power)
        btnGuestLog = findViewById(R.id.btn_detail_guest_log)
        btnAppLog = findViewById(R.id.btn_detail_app_log)

        // Section 2
        hwLockedHint = findViewById(R.id.tv_hw_locked_hint)
        vcpuLabel = findViewById(R.id.tv_detail_vcpu_label)
        vcpuSeekBar = findViewById(R.id.seek_detail_vcpu)
        ramLabel = findViewById(R.id.tv_detail_ram_label)
        ramSeekBar = findViewById(R.id.seek_detail_ram)
        ramSafetyZone = findViewById(R.id.tv_detail_ram_safety_zone)
        btnSaveHardware = findViewById(R.id.btn_save_hardware)

        // Section 3
        storagePhysicalText = findViewById(R.id.tv_storage_physical)
        storageLogicalText = findViewById(R.id.tv_storage_logical)
        btnExpandDisk = findViewById(R.id.btn_expand_disk)
        btnExportBackup = findViewById(R.id.btn_export_backup)

        // Section 4
        networkIpText = findViewById(R.id.tv_network_ip_detail)
        btnManagePortForwarding = findViewById(R.id.btn_manage_port_forwarding)

        // Section 5
        btnDangerClone = findViewById(R.id.btn_danger_clone)
        btnDangerReset = findViewById(R.id.btn_danger_reset)
        btnDangerDelete = findViewById(R.id.btn_danger_delete)
    }

    private fun initHardwareSliders(state: InstanceDetailUiState) {
        val profile = hostProfile ?: HostHardwareDetector.detect(this)
        val maxCores = Math.min(VmHardwareConfig.MAX_CPU_CORES, profile.totalCores())
        vcpuSeekBar.max = Math.max(0, maxCores - 1)
        selectedVcpu = Math.min(state.vCpu, maxCores)
        vcpuSeekBar.progress = Math.max(0, selectedVcpu - 1)
        vcpuLabel.text = getString(R.string.hardware_vcpu_label, selectedVcpu)

        val maxRamMb = Math.min(VmHardwareConfig.MAX_RAM_MEGABYTES, profile.totalRamMb())
        val ramSteps = Math.max(1, (maxRamMb - minRamMb) / stepRamMb)
        ramSeekBar.max = ramSteps
        selectedRamMb = Math.max(minRamMb, Math.min(state.ramMb, maxRamMb))
        ramSeekBar.progress = Math.max(0, (selectedRamMb - minRamMb) / stepRamMb)
        updateRamZoneUi(selectedRamMb, profile)

        vcpuSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedVcpu = progress + 1
                vcpuLabel.text = getString(R.string.hardware_vcpu_label, selectedVcpu)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        ramSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedRamMb = minRamMb + progress * stepRamMb
                updateRamZoneUi(selectedRamMb, profile)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateRamZoneUi(ramMb: Int, profile: HostHardwareProfile) {
        ramLabel.text = getString(R.string.hardware_ram_label, ramMb)
        val zone = profile.evaluateRamZone(ramMb)
        val canModify = viewModel.uiState?.canModifyHardware == true
        when (zone) {
            HostHardwareProfile.SafetyZone.GREEN -> {
                ramSafetyZone.text = getString(R.string.hardware_ram_zone_green)
                ramSafetyZone.setTextColor(getColor(R.color.zone_green))
                btnSaveHardware.isEnabled = canModify
            }
            HostHardwareProfile.SafetyZone.YELLOW -> {
                ramSafetyZone.text = getString(R.string.hardware_ram_zone_yellow)
                ramSafetyZone.setTextColor(getColor(R.color.zone_yellow))
                btnSaveHardware.isEnabled = canModify
            }
            HostHardwareProfile.SafetyZone.RED -> {
                ramSafetyZone.text = getString(R.string.hardware_ram_zone_red)
                ramSafetyZone.setTextColor(getColor(R.color.zone_red))
                btnSaveHardware.isEnabled = false
            }
        }
    }

    private fun renderState(state: InstanceDetailUiState) {
        // Section 1: Header
        distroIcon.setImageResource(
            DistroIconHelper.getIconRes(state.distroId)
        )
        instanceTitle.text = "${state.distroDisplayName} Linux ${state.distroVersion}"
        instanceIdText.text = "ID: ${state.instanceId} · ${state.defaultUser}@${state.hostname}"
        instanceIdText.setOnClickListener { showCredentialsDialog(state.instance) }
        statusBadge.text = state.stateBadgeText
        statusBadge.setTextColor(getColor(state.stateBadgeColorRes))

        if (state.hasCrashDiagnostic) {
            statusBadge.setOnClickListener { showCrashDiagnosticDialog(state.instance) }
        } else {
            statusBadge.setOnClickListener(null)
        }

        specsSummary.text = "${state.vCpu} vCPU · ${state.ramMb} MB · ${state.diskCapacityGb} GB"
        ipAddressText.text = "IP: ${state.ipAddress}"

        btnTerminal.isEnabled = state.canOpenTerminal
        btnTerminal.alpha = if (state.canOpenTerminal) 1.0f else 0.45f

        btnDesktop.isEnabled = state.canLaunchDesktop
        btnDesktop.alpha = if (state.canLaunchDesktop) 1.0f else 0.45f

        if (state.state == InstanceLifecycle.State.RUNNING) {
            btnPower.text = getString(R.string.console_btn_power)
            btnPower.setIconResource(R.drawable.ic_power)
        } else {
            btnPower.text = getString(R.string.console_btn_start)
            btnPower.setIconResource(R.drawable.ic_power)
        }

        // Section 2: Hardware Lock
        if (state.canModifyHardware) {
            hwLockedHint.visibility = View.GONE
            vcpuSeekBar.isEnabled = true
            ramSeekBar.isEnabled = true
            val profile = hostProfile ?: HostHardwareDetector.detect(this)
            val zone = profile.evaluateRamZone(selectedRamMb)
            btnSaveHardware.isEnabled = zone != HostHardwareProfile.SafetyZone.RED
        } else {
            hwLockedHint.visibility = View.VISIBLE
            hwLockedHint.text = state.hardwareModificationRejectionReason ?: getString(R.string.console_hw_running_hint)
            vcpuSeekBar.isEnabled = false
            ramSeekBar.isEnabled = false
            btnSaveHardware.isEnabled = false
        }

        // Section 3: Storage
        storagePhysicalText.text = getString(R.string.console_storage_physical, formatDiskSize(state.diskPhysicalBytes))
        storageLogicalText.text = getString(R.string.console_storage_logical, "${state.diskCapacityGb} GB")

        // Section 4: Networking
        networkIpText.text = getString(R.string.console_network_ip, state.ipAddress)

        // Section 5: Danger Zone
        btnDangerClone.isEnabled = state.canClone
        btnDangerClone.alpha = if (state.canClone) 1.0f else 0.45f

        btnDangerDelete.isEnabled = state.canDelete
        btnDangerDelete.alpha = if (state.canDelete) 1.0f else 0.45f
    }

    private fun setupActionListeners() {
        // Section 1 Buttons
        btnTerminal.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            if (state.canOpenTerminal) {
                val intent = Intent(this, TerminalActivity::class.java).apply {
                    putExtra("bridgeToken", state.instance.bridgeToken())
                    putExtra("instanceId", state.instanceId)
                    putExtra("hostname", state.hostname)
                    putExtra("defaultUser", state.defaultUser)
                }
                startActivity(intent)
            }
        }

        btnDesktop.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            if (state.canLaunchDesktop) {
                val intent = DesktopLaunchIntent.create(
                    this,
                    state.instanceId,
                    state.instance.bridgeToken(),
                    false
                )
                startActivity(intent)
            }
        }

        btnGuestLog.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            startActivity(
                Intent(this, LogViewerActivity::class.java).apply {
                    putExtra(LogViewerActivity.EXTRA_KIND, LogViewerActivity.KIND_GUEST)
                    putExtra(LogViewerActivity.EXTRA_INSTANCE_ID, state.instanceId)
                    putExtra(LogViewerActivity.EXTRA_TITLE, "Linux 运行日志 · " + state.hostname)
                }
            )
        }

        btnAppLog.setOnClickListener {
            startActivity(
                Intent(this, LogViewerActivity::class.java).apply {
                    putExtra(LogViewerActivity.EXTRA_KIND, LogViewerActivity.KIND_APP)
                    putExtra(LogViewerActivity.EXTRA_TITLE, "应用运行日志")
                }
            )
        }

        btnPower.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            if (state.state == InstanceLifecycle.State.RUNNING) {
                showPowerManagementDialog(state)
            } else if (state.canPowerOn) {
                launchInstanceFromDetail(state.instanceId)
            }
        }

        // Section 2: Save Hardware
        btnSaveHardware.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            operations.execute {
                val result = viewModel.updateHardwareConfig(selectedVcpu, selectedRamMb)
                runOnUiThread {
                    when (result) {
                        is UpdateHardwareResult.Success -> {
                            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        }
                        is UpdateHardwareResult.Rejected -> {
                            Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                        }
                        is UpdateHardwareResult.Error -> {
                            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        // Section 3: Expand Disk & Backup
        btnExpandDisk.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            if (!state.canModifyHardware) {
                Toast.makeText(this, R.string.console_hw_running_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val profile = hostProfile ?: HostHardwareDetector.detect(this)
            VmHardwareConfigDialog(this, state.instance, profile) { newConfig, expandedDiskBytes ->
                operations.execute {
                    val result = viewModel.updateHardwareConfig(
                        newConfig.cpuCores(),
                        newConfig.ramMegabytes(),
                        expandedDiskBytes
                    )
                    runOnUiThread {
                        when (result) {
                            is UpdateHardwareResult.Success -> Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                            is UpdateHardwareResult.Rejected -> Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
                            is UpdateHardwareResult.Error -> Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.show()
        }

        btnExportBackup.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            safExportLauncher.launch("${state.instanceId}-backup.zip")
        }

        // Section 4: Port Forwarding
        btnManagePortForwarding.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            try {
                val netPath = filesDir.toPath().resolve("network")
                Files.createDirectories(netPath)
                val store = FilePortForwardingStore(netPath)
                val manager = PortForwardingManager(state.instanceId, store, PortConflictDetector())
                PortForwardingBottomSheet(this, state.instanceId, manager).show()
            } catch (e: Exception) {
                Toast.makeText(this, "打开端口转发管理失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // Section 5: Danger Zone
        btnDangerClone.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            if (!state.canClone) {
                Toast.makeText(this, R.string.action_clone_running_forbidden, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_clone_confirm_title)
                .setMessage(getString(R.string.action_clone_confirm_message, state.instanceId))
                .setPositiveButton(R.string.action_clone_confirm_btn) { _, _ ->
                    operations.execute {
                        val cloned = viewModel.cloneInstance()
                        runOnUiThread {
                            if (cloned != null) {
                                Toast.makeText(this, getString(R.string.action_clone_success, cloned.id()), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, getString(R.string.action_clone_failed, "克隆执行受阻"), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.action_delete_cancel_btn, null)
                .show()
        }

        btnDangerReset.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.console_danger_reset)
                .setMessage(R.string.console_danger_reset_desc)
                .setPositiveButton("立即复位") { _, _ ->
                    operations.execute {
                        val reset = viewModel.resetDeadlockState()
                        runOnUiThread {
                            if (reset != null) {
                                Toast.makeText(this, "孤儿状态已重置为已停止", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "重置失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.action_delete_cancel_btn, null)
                .show()
        }

        btnDangerDelete.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            if (!state.canDelete) {
                Toast.makeText(this, R.string.action_delete_running_forbidden, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val inputEdit = EditText(this).apply {
                hint = InstanceDetailViewModel.DELETE_CONFIRM_PHRASE
                setSingleLine(true)
                val pad = (20 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.console_delete_dialog_title)
                .setMessage(R.string.console_delete_dialog_message_phrase)
                .setView(inputEdit)
                .setPositiveButton(R.string.console_delete_dialog_confirm) { _, _ ->
                    val entered = inputEdit.text.toString().trim()
                    if (entered != InstanceDetailViewModel.DELETE_CONFIRM_PHRASE) {
                        Toast.makeText(this, R.string.console_delete_dialog_mismatch, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    operations.execute {
                        val deleted = viewModel.requestDelete(entered)
                        runOnUiThread {
                            if (deleted) {
                                Toast.makeText(this, R.string.console_delete_success, Toast.LENGTH_SHORT).show()
                                finish()
                            } else {
                                Toast.makeText(this, getString(R.string.action_delete_failed, "删除拦截"), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.action_delete_cancel_btn, null)
                .show()
        }
    }

    private fun showPowerManagementDialog(state: InstanceDetailUiState) {
        val options = arrayOf(
            getString(R.string.console_btn_stop),
            getString(R.string.console_btn_force_stop),
            getString(R.string.console_btn_restart)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.console_btn_power)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> gracefulPowerOff(state)
                    1 -> forceStop(state)
                    2 -> restartInstance(state)
                }
            }
            .setNegativeButton(R.string.action_delete_cancel_btn, null)
            .show()
    }

    private fun gracefulPowerOff(state: InstanceDetailUiState) {
        operations.execute {
            val session = ExecutionSessionRegistry.get(state.instanceId)
            if (session != null) {
                try {
                    val client = BridgeClient(session.controlOutput(), session.controlInput())
                    client.power(BridgeControl.PowerAction.POWEROFF)
                } catch (_: Exception) {
                    session.close()
                }
            }
            runOnUiThread {
                Toast.makeText(this, "正在优雅关机...", Toast.LENGTH_SHORT).show()
                viewModel.loadInstance(state.instanceId)
            }
        }
    }

    private fun forceStop(state: InstanceDetailUiState) {
        operations.execute {
            val session = ExecutionSessionRegistry.get(state.instanceId)
            session?.close()
            ExecutionSessionRegistry.unregister(state.instanceId)
            OrchestellaForegroundService.stop(this)
            try {
                InstanceLaunchSequence.afterGuestExit(viewModel.controller, state.instanceId)
            } catch (_: Exception) {
            }
            runOnUiThread {
                Toast.makeText(this, "已强制断电", Toast.LENGTH_SHORT).show()
                viewModel.loadInstance(state.instanceId)
            }
        }
    }

    private fun restartInstance(state: InstanceDetailUiState) {
        operations.execute {
            val session = ExecutionSessionRegistry.get(state.instanceId)
            if (session != null) {
                try {
                    val client = BridgeClient(session.controlOutput(), session.controlInput())
                    client.power(BridgeControl.PowerAction.SHUTDOWN)
                } catch (_: Exception) {
                    session.close()
                }
            }
            runOnUiThread {
                Toast.makeText(this, "正在重启实例...", Toast.LENGTH_SHORT).show()
                launchInstanceFromDetail(state.instanceId)
            }
        }
    }

    private fun launchInstanceFromDetail(instanceId: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("action_launch_instance_id", instanceId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    private fun showCrashDiagnosticDialog(instance: InstanceLifecycle.Instance) {
        val diag = instance.crashDiagnostic() ?: return
        val message = buildString {
            appendLine(getString(R.string.crash_dialog_exit_code, diag.exitCode()))
            if (diag.logPath() != null) {
                appendLine(getString(R.string.crash_dialog_log_path, diag.logPath()))
            }
            if (diag.message() != null) {
                appendLine(getString(R.string.crash_dialog_message, diag.message()))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.credentials_close, null)
            .apply {
                if (diag.logPath() != null) {
                    setNeutralButton(R.string.crash_dialog_copy) { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("CrashLogPath", diag.logPath().toString()))
                        Toast.makeText(this@InstanceDetailActivity, "日志路径已复制", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showCredentialsDialog(instance: InstanceLifecycle.Instance) {
        val message = "默认主机名：${instance.hostname()}\n用户名：${instance.defaultUser()}\n密码：${instance.guestPassword()}\n\n提示：遵循工单 03 规范，默认主机名为 orchestella，超级用户凭据为 root / 1234。"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.credentials_title)
            .setMessage(message)
            .setPositiveButton(R.string.credentials_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GuestPassword", instance.guestPassword()))
                Toast.makeText(this, R.string.credentials_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.credentials_close, null)
            .show()
    }

    private fun currentSupportsDesktop(): Boolean {
        val assessment = DeviceCapabilityEvaluator.evaluate(deviceProfile())
        val readiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
        val matrix = if (assessment.canCreateFullLinuxInstance() && readiness.status() == AvfCapabilityEvaluator.AvfStatus.READY) {
            FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithGraphics()
        } else {
            FullLinuxContract.CapabilityMatrix.softwareVirtualizationWithGraphics()
        }
        return matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND)
    }

    private fun deviceProfile(): DeviceCapabilityEvaluator.DeviceProfile {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return DeviceCapabilityEvaluator.DeviceProfile(
            Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.toList(),
            memoryInfo.totalMem,
            isEmulator(),
        )
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.BRAND.startsWith("generic") ||
            Build.DEVICE.startsWith("generic") ||
            "google_sdk" == Build.PRODUCT

    private fun formatDiskSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            String.format(Locale.US, "%.1f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.0f MB", mb)
        }
    }
}
