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
import android.os.SystemClock
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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.capability.DeviceCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore
import io.github.nyzeep.orchestella.runtime.network.PortConflictDetector
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule
import io.github.nyzeep.orchestella.runtime.contract.Protocol
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.ui.InstanceDetailViewModel
import io.github.nyzeep.orchestella.ui.InstanceDetailViewModel.InstanceDetailUiState
import io.github.nyzeep.orchestella.ui.InstanceDetailViewModel.UpdateHardwareResult
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import java.io.IOException
import java.time.Clock
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        private const val PENDING_EXPORT_ID = "pending_export_instance_id"
        private const val EXIT_TIMEOUT_MS = 60_000L
    }

    private lateinit var viewModel: InstanceDetailViewModel
    private var hostProfile: HostHardwareProfile? = null
    private val operations = Executors.newSingleThreadScheduledExecutor()
    private var refreshTask: ScheduledFuture<*>? = null
    private var powerExitTask: ScheduledFuture<*>? = null
    private val powerOperationPending = AtomicBoolean(false)
    private val exportInProgress = AtomicBoolean(false)
    private var pendingExportInstanceId: String? = null
    @Volatile private var detailResumed = false
    @Volatile private var detailDestroyed = false

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

    // Secondary Navigation Tabs & Containers
    private lateinit var navToggleGroup: MaterialButtonToggleGroup
    private lateinit var containerOverview: View
    private lateinit var containerHardware: View
    private lateinit var containerStorage: View
    private lateinit var containerNetwork: View
    private lateinit var containerMaintenance: View

    // SSH Quick Access Card
    private lateinit var tvDetailSshCmd: TextView
    private lateinit var btnDetailSshCopy: MaterialButton
    private lateinit var btnDetailSshManage: MaterialButton

    // Slider mutable state
    private var selectedVcpu: Int = 2
    private var selectedRamMb: Int = 2048
    private val minRamMb = VmHardwareConfig.MIN_RAM_MEGABYTES // 512
    private val stepRamMb = 256

    private val safExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val instanceId = pendingExportInstanceId
        pendingExportInstanceId = null
        if (uri != null && instanceId != null && ::viewModel.isInitialized) {
            exportInProgress.set(true)
            viewModel.uiState?.let(::renderState)
            operations.execute {
                val message = try {
                    val instance = requireColdBackup(instanceId)
                    val adapter = AndroidSafExportAdapter(
                        AndroidSafExportAdapter.DocumentStreamOpener { documentUri ->
                            // Recheck after the chooser and immediately before opening the destination.
                            requireColdBackup(instanceId)
                            contentResolver.openOutputStream(documentUri)
                                ?: throw IOException("无法打开备份文件")
                        }, Clock.systemUTC()
                    )
                    adapter.exportToDocumentUri(instance, uri)
                    requireColdBackup(instanceId)
                    "实例冷备份导出成功"
                } catch (_: Exception) {
                    "备份未完成，请确认实例已完全关机且目标文件可写；不要使用未完成的备份。"
                }
                exportInProgress.set(false)
                runOnUiThread {
                    if (!detailDestroyed && detailResumed && !isFinishing) {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        viewModel.loadInstance(instanceId)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportInstanceId = savedInstanceState?.getString(PENDING_EXPORT_ID)
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
        applyTheme()

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
                if (!detailDestroyed && !isFinishing && detailResumed) {
                    if (state != null) renderState(state) else finish()
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
        renderState(initialState)
    }

    override fun onResume() {
        super.onResume()
        detailResumed = true
        if (!::viewModel.isInitialized) return
        val instanceId = viewModel.uiState?.instanceId ?: return
        refreshTask?.cancel(false)
        refreshTask = operations.scheduleWithFixedDelay({
            if (detailResumed && !detailDestroyed) viewModel.loadInstance(instanceId)
        }, 0, 1, TimeUnit.SECONDS)
    }

    override fun onPause() {
        detailResumed = false
        refreshTask?.cancel(false)
        refreshTask = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_EXPORT_ID, pendingExportInstanceId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        detailDestroyed = true
        refreshTask?.cancel(false)
        powerExitTask?.cancel(false)
        if (::viewModel.isInitialized) viewModel.onStateChanged = null
        operations.shutdownNow()
        super.onDestroy()
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

        // Secondary Navigation & Containers
        navToggleGroup = findViewById(R.id.detail_nav_toggle_group)
        containerOverview = findViewById(R.id.container_detail_overview)
        containerHardware = findViewById(R.id.container_detail_hardware)
        containerStorage = findViewById(R.id.container_detail_storage)
        containerNetwork = findViewById(R.id.container_detail_network)
        containerMaintenance = findViewById(R.id.container_detail_maintenance)

        // SSH Quick Access Card
        tvDetailSshCmd = findViewById(R.id.tv_detail_ssh_cmd)
        btnDetailSshCopy = findViewById(R.id.btn_detail_ssh_copy)
        btnDetailSshManage = findViewById(R.id.btn_detail_ssh_manage)
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
        val canModify = viewModel.uiState?.let {
            it.canModifyHardware && ExecutionSessionRegistry.get(it.instanceId) == null &&
                !InstanceLaunchGuard.isInFlight(it.instanceId) &&
                !powerOperationPending.get() && !exportInProgress.get()
        } == true
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
        val hasSession = ExecutionSessionRegistry.get(state.instanceId) != null
        val launching = InstanceLaunchGuard.isInFlight(state.instanceId)
        val busy = powerOperationPending.get() || exportInProgress.get()
        val inactive = !hasSession && !launching && !busy
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

        btnTerminal.isEnabled = state.canOpenTerminal && hasSession && !busy
        btnDesktop.isEnabled = hasSession && !busy
        btnDesktop.text = "桌面与开发工具"
        btnDesktop.contentDescription = "查看当前实例的可选功能安装、启动与真实状态"
        btnPower.isEnabled = !busy && (hasSession || (state.canPowerOn && !launching))
        if (powerOperationPending.get()) {
            statusBadge.text = "正在等待虚拟机退出"
        } else if (hasSession && state.state == InstanceLifecycle.State.STOPPED) {
            statusBadge.text = "运行会话尚未退出"
        } else if (launching && !hasSession) {
            statusBadge.text = "正在启动"
        }

        if (hasSession) {
            btnPower.text = getString(R.string.console_btn_power)
            btnPower.setIconResource(R.drawable.ic_power)
        } else {
            btnPower.text = getString(R.string.console_btn_start)
            btnPower.setIconResource(R.drawable.ic_power)
        }

        // Section 2: Hardware Lock
        if (state.canModifyHardware && inactive) {
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
        btnExpandDisk.isEnabled = state.canModifyHardware && inactive
        btnExportBackup.isEnabled = !busy && pendingExportInstanceId == null &&
            InstanceDetailActionRules.canColdBackup(state.state, hasSession, launching)

        // Section 4: Networking
        networkIpText.text = getString(R.string.console_network_ip, state.ipAddress)

        // Section 5: Danger Zone
        btnDangerClone.isEnabled = state.canClone && inactive
        btnDangerDelete.isEnabled = state.canDelete && inactive
        btnDangerReset.isEnabled = state.canResetState && inactive

        renderSshConnection(state)
    }

    private fun sshRules(instanceId: String): List<PortForwardingRule> =
        FilePortForwardingStore(filesDir.toPath().resolve("network")).loadRules(instanceId)

    private fun renderSshConnection(state: InstanceDetailUiState) {
        val command = try {
            InstanceDetailActionRules.sshCommand(state.defaultUser, sshRules(state.instanceId))
        } catch (_: Exception) {
            null
        }
        tvDetailSshCmd.text = command ?: "SSH 端口转发未配置，请先配置 TCP → 22 映射"
        btnDetailSshCopy.isEnabled = command != null
        btnDetailSshCopy.setOnClickListener {
            val latestCommand = try {
                val instance = viewModel.instanceStore.load(state.instanceId)
                InstanceDetailActionRules.sshCommand(instance.defaultUser(), sshRules(state.instanceId))
            } catch (_: Exception) {
                null
            }
            if (latestCommand == null) {
                renderSshConnection(state)
                Toast.makeText(this, "SSH 端口转发未配置，无法复制连接命令", Toast.LENGTH_SHORT).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SSH Command", latestCommand))
                Toast.makeText(this, "SSH 命令已复制", Toast.LENGTH_SHORT).show()
            }
        }
        btnDetailSshManage.setOnClickListener {
            val rule = try {
                InstanceDetailActionRules.sshRule(sshRules(state.instanceId))
            } catch (_: Exception) {
                null
            }
            if (rule == null) {
                showPortForwarding(state.instanceId)
            } else {
                io.github.nyzeep.orchestella.ssh.SshManagerDialog.show(
                    this,
                    defaultInstanceId = state.instanceId,
                    defaultHost = InstanceDetailActionRules.sshHost(rule),
                    defaultUser = state.defaultUser,
                    defaultPort = rule.hostPort()
                )
            }
        }
    }

    private fun showPortForwarding(instanceId: String) {
        try {
            val store = FilePortForwardingStore(filesDir.toPath().resolve("network"))
            val manager = PortForwardingManager(instanceId, store, PortConflictDetector())
            PortForwardingBottomSheet(this, instanceId, manager).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法读取端口转发配置，请稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requireColdBackup(instanceId: String): InstanceLifecycle.Instance {
        val instance = viewModel.instanceStore.load(instanceId)
        if (!InstanceDetailActionRules.canColdBackup(instance.state(),
                ExecutionSessionRegistry.get(instanceId) != null, InstanceLaunchGuard.isInFlight(instanceId))) {
            throw IllegalStateException("请先完全关机后再导出冷备份")
        }
        return instance
    }

    private fun setupActionListeners() {
        // Modular Secondary Navigation Tabs
        navToggleGroup.check(R.id.tab_detail_overview)
        navToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            containerOverview.visibility = if (checkedId == R.id.tab_detail_overview) View.VISIBLE else View.GONE
            containerHardware.visibility = if (checkedId == R.id.tab_detail_hardware) View.VISIBLE else View.GONE
            containerStorage.visibility = if (checkedId == R.id.tab_detail_storage) View.VISIBLE else View.GONE
            containerNetwork.visibility = if (checkedId == R.id.tab_detail_network) View.VISIBLE else View.GONE
            containerMaintenance.visibility = if (checkedId == R.id.tab_detail_maintenance) View.VISIBLE else View.GONE
        }

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
            if (ExecutionSessionRegistry.get(state.instanceId) != null) {
                startActivity(Intent(this, GuestFeaturesActivity::class.java)
                    .putExtra("instanceId", state.instanceId)
                    .putExtra("bridgeToken", state.instance.bridgeToken()))
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
            if (powerOperationPending.get() || exportInProgress.get()) return@setOnClickListener
            if (ExecutionSessionRegistry.get(state.instanceId) != null) {
                showPowerManagementDialog(state)
            } else if (state.canPowerOn && !InstanceLaunchGuard.isInFlight(state.instanceId)) {
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
            if (powerOperationPending.get() || exportInProgress.get() || pendingExportInstanceId != null) {
                return@setOnClickListener
            }
            try {
                requireColdBackup(state.instanceId)
                pendingExportInstanceId = state.instanceId
                renderState(state)
                safExportLauncher.launch("${state.instanceId}-backup.zip")
            } catch (_: Exception) {
                pendingExportInstanceId = null
                Toast.makeText(this, "请先完全关机后再导出冷备份", Toast.LENGTH_LONG).show()
            }
        }

        // Section 4: Port Forwarding
        btnManagePortForwarding.setOnClickListener {
            val state = viewModel.uiState ?: return@setOnClickListener
            showPortForwarding(state.instanceId)
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
                .show().also { ThemeApplier.apply(it) }
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
                .show().also { ThemeApplier.apply(it) }
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
                .show().also { ThemeApplier.apply(it) }
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
                    1 -> confirmForceStop(state)
                    2 -> restartInstance(state)
                }
            }
            .setNegativeButton(R.string.action_delete_cancel_btn, null)
            .show().also { ThemeApplier.apply(it) }
    }

    private fun gracefulPowerOff(state: InstanceDetailUiState) {
        requestPowerChange(state.instanceId, force = false, restart = false)
    }

    private fun confirmForceStop(state: InstanceDetailUiState) {
        MaterialAlertDialogBuilder(this)
            .setTitle("确认强制断电")
            .setMessage("强制断电会立即终止虚拟机，未保存的数据可能丢失，并可能损坏文件系统。确认后才会请求断电。")
            .setPositiveButton("强制断电") { _, _ ->
                requestPowerChange(state.instanceId, force = true, restart = false)
            }
            .setNegativeButton("取消", null)
            .show().also { ThemeApplier.apply(it) }
    }

    private fun restartInstance(state: InstanceDetailUiState) {
        requestPowerChange(state.instanceId, force = false, restart = true)
    }

    private fun requestPowerChange(instanceId: String, force: Boolean, restart: Boolean) {
        if (exportInProgress.get() || !powerOperationPending.compareAndSet(false, true)) return
        val requestedSession = ExecutionSessionRegistry.get(instanceId)
        viewModel.uiState?.let(::renderState)
        operations.execute {
            try {
                if (requestedSession == null || ExecutionSessionRegistry.get(instanceId) !== requestedSession) {
                    throw IOException("运行会话已变化")
                }
                val intent = if (force) PersistentTerminalSessionPool.StopIntent.FORCE
                    else PersistentTerminalSessionPool.StopIntent.GRACEFUL
                ExecutionSessionRegistry.requestStop(instanceId, requestedSession, intent) {
                    if (force) requestedSession.terminate()
                    else InstanceDetailActionRules.requestShutdown(requestedSession)
                }
                waitForActualExit(instanceId, requestedSession, restart, force)
                runOnUiThread {
                    if (detailResumed && !detailDestroyed && !isFinishing) {
                        Toast.makeText(this, if (force) "已请求强制断电，正在等待虚拟机退出"
                            else "关机请求已接受，正在等待虚拟机退出", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                finishPowerChange(instanceId, false,
                    if (force) "强制断电请求失败，实例状态未更改，请检查运行日志"
                    else "关机请求未被接受或连接失败，实例状态未更改，请检查运行日志")
            }
        }
    }

    private fun waitForActualExit(instanceId: String, requestedSession: ExecutionSession,
            restart: Boolean, force: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + EXIT_TIMEOUT_MS
        powerExitTask = operations.scheduleWithFixedDelay({
            if (detailDestroyed) {
                powerExitTask?.cancel(false)
            } else {
                try {
                    val session = ExecutionSessionRegistry.get(instanceId)
                    val instance = viewModel.instanceStore.load(instanceId)
                    when (InstanceDetailActionRules.exitDecision(instance.state(), session != null,
                        InstanceLaunchGuard.isInFlight(instanceId),
                        session != null && session !== requestedSession,
                        SystemClock.elapsedRealtime() >= deadline)) {
                        InstanceDetailActionRules.ExitDecision.READY -> finishPowerChange(instanceId, restart,
                            if (force) "虚拟机已退出" else "实例已关机")
                        InstanceDetailActionRules.ExitDecision.REPLACED -> finishPowerChange(instanceId, false,
                            "运行会话已变化，已取消本次操作")
                        InstanceDetailActionRules.ExitDecision.TIMED_OUT -> finishPowerChange(instanceId, false,
                            "停止未确认：虚拟机在 60 秒内未退出；实例仍保留，请查看运行日志")
                        InstanceDetailActionRules.ExitDecision.WAITING -> Unit
                    }
                } catch (_: Exception) {
                    finishPowerChange(instanceId, false, "无法确认虚拟机退出；未执行重启，请查看运行日志")
                }
            }
        }, 0, 250, TimeUnit.MILLISECONDS)
    }

    private fun finishPowerChange(instanceId: String, restart: Boolean, message: String) {
        powerExitTask?.cancel(false)
        powerExitTask = null
        runOnUiThread {
            if (detailDestroyed || isFinishing) return@runOnUiThread
            if (restart && detailResumed) {
                // Recheck on the UI handoff, so another launch cannot reattach this action to an old VM.
                val latest = try { viewModel.instanceStore.load(instanceId) } catch (_: Exception) { null }
                val ready = latest != null && InstanceDetailActionRules.exitDecision(latest.state(),
                    ExecutionSessionRegistry.get(instanceId) != null, InstanceLaunchGuard.isInFlight(instanceId),
                    false, false) == InstanceDetailActionRules.ExitDecision.READY
                powerOperationPending.set(false)
                if (ready) launchInstanceFromDetail(instanceId)
                else Toast.makeText(this, "实例尚未完全退出，重启已取消", Toast.LENGTH_LONG).show()
            } else {
                powerOperationPending.set(false)
                if (detailResumed) {
                    Toast.makeText(this, if (restart) "实例已关机；离开详情页后已取消自动启动" else message,
                        Toast.LENGTH_LONG).show()
                }
            }
            viewModel.loadInstance(instanceId)
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
            .show().also { ThemeApplier.apply(it) }
    }

    private fun showCredentialsDialog(instance: InstanceLifecycle.Instance) {
        val password = instance.guestPassword()
        val message = "主机名：${instance.hostname()}\n用户名：${instance.defaultUser()}\n密码：${password.ifBlank { "未记录，请在 Linux 中使用 passwd 设置" }}\n\n新实例使用独立随机凭据，请妥善保存。"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.credentials_title)
            .setMessage(message)
            .setPositiveButton(R.string.credentials_copy) { _, _ ->
                if (password.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GuestPassword", password))
                    Toast.makeText(this, R.string.credentials_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.credentials_close, null)
            .show().also { ThemeApplier.apply(it) }
    }

    private fun currentSupportsDesktop(): Boolean {
        // Phase 5 must verify actual guest frames and input before enabling this gate.
        return false
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

    private fun applyTheme() {
        try {
            val themeStore = io.github.nyzeep.orchestella.ui.theme.ThemeStore(this)
            val theme = themeStore.resolve()
            findViewById<View>(R.id.detail_root)?.setBackgroundColor(theme.background)
            toolbar.setTitleTextColor(theme.text)
            toolbar.navigationIcon?.setTint(theme.text)

            // Distro Title & ID
            findViewById<TextView>(R.id.detail_instance_title)?.setTextColor(theme.text)
            findViewById<TextView>(R.id.detail_instance_id)?.setTextColor(theme.textDim)

            // Frosted Cards with delicate hairline stroke
            val cardIds = listOf(
                R.id.card_header_dashboard,
                R.id.card_detail_ssh_connect,
                R.id.card_hardware_specs_editor,
                R.id.card_network_port_forwarding,
                R.id.card_storage_manager
            )
            cardIds.forEach { id ->
                findViewById<com.google.android.material.card.MaterialCardView>(id)?.let { card ->
                    card.setCardBackgroundColor(theme.surfaceTranslucent)
                    card.strokeColor = (theme.glow and 0x00FFFFFF) or 0x33000000
                    card.strokeWidth = (1 * resources.displayMetrics.density).toInt()
                }
            }

            // Navigation Tab Button Styling
            listOf(
                R.id.tab_detail_overview,
                R.id.tab_detail_hardware,
                R.id.tab_detail_storage,
                R.id.tab_detail_network,
                R.id.tab_detail_maintenance
            ).forEach { tabId ->
                findViewById<MaterialButton>(tabId)?.let { tabBtn ->
                    tabBtn.strokeColor = ColorStateList.valueOf((theme.glow and 0x00FFFFFF) or 0x33000000)
                    tabBtn.setTextColor(theme.text)
                }
            }

            // Danger Zone Card with subtle red stroke
            findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_danger_zone)?.let { card ->
                card.setCardBackgroundColor(theme.surfaceTranslucent)
                card.strokeColor = 0x40EF4444.toInt()
                card.strokeWidth = (1 * resources.displayMetrics.density).toInt()
            }
        } catch (e: Exception) {
            android.util.Log.w("InstanceDetailActivity", "Theme apply failed", e)
        }
    }
}

/** Decisions shared by the detail actions and their pure JVM regressions. */
internal object InstanceDetailActionRules {
    enum class ExitDecision { READY, WAITING, REPLACED, TIMED_OUT }

    fun canColdBackup(state: InstanceLifecycle.State, hasSession: Boolean, launching: Boolean): Boolean =
        !hasSession && !launching && state in setOf(InstanceLifecycle.State.STOPPED,
            InstanceLifecycle.State.CREATED, InstanceLifecycle.State.CRASHED, InstanceLifecycle.State.INTERRUPTED)

    fun exitDecision(state: InstanceLifecycle.State, hasSession: Boolean, launching: Boolean,
            sessionReplaced: Boolean, timedOut: Boolean): ExitDecision = when {
        sessionReplaced -> ExitDecision.REPLACED
        !hasSession && !launching && state == InstanceLifecycle.State.STOPPED -> ExitDecision.READY
        timedOut -> ExitDecision.TIMED_OUT
        else -> ExitDecision.WAITING
    }

    fun requestShutdown(session: ExecutionSession) {
        val client = session.bridgeClient() ?: throw IOException("运行会话没有控制连接")
        if (!client.isReady) throw IOException("控制连接尚未完成认证")
        if (!client.power(BridgeControl.PowerAction.POWEROFF).accepted()) {
            throw IOException("Linux 未接受关机请求")
        }
    }

    fun sshRule(rules: List<PortForwardingRule>): PortForwardingRule? = rules
        .filter { it.protocol() == Protocol.TCP && it.guestPort() == 22 }
        .minWithOrNull(compareBy<PortForwardingRule> {
            when (it.bindAddress()) {
                PortForwardingRule.LOOPBACK_ADDRESS -> 0
                PortForwardingRule.LAN_EXPOSED_ADDRESS -> 1
                else -> 2
            }
        }.thenBy { it.hostPort() }.thenBy { it.id() })

    fun sshHost(rule: PortForwardingRule): String =
        if (rule.bindAddress() == PortForwardingRule.LAN_EXPOSED_ADDRESS)
            PortForwardingRule.LOOPBACK_ADDRESS else rule.bindAddress()

    fun sshCommand(user: String, rules: List<PortForwardingRule>): String? {
        if (!user.matches(Regex("[a-z_][a-z0-9_-]{1,15}"))) return null
        val rule = sshRule(rules) ?: return null
        return "ssh -p ${rule.hostPort()} $user@${sshHost(rule)}"
    }
}
