package io.github.nyzeep.orchestella

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.color.DynamicColors
import io.github.nyzeep.orchestella.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.capability.DeviceCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.SparseDiskResizer
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore
import io.github.nyzeep.orchestella.runtime.network.PortConflictDetector
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager
import io.github.nyzeep.orchestella.runtime.network.download.ChecksumMismatchException
import io.github.nyzeep.orchestella.runtime.network.download.DownloadProgressCallback
import io.github.nyzeep.orchestella.runtime.network.download.ResilientDownloadEngine
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Clock
import java.util.concurrent.Executors

/**
 * Material 3 Expressive Dashboard for Orchestella Studio (Ticket 52 / M4).
 *
 * <p>Delivers:
 * <ul>
 *   <li>Android 15/17 Edge-to-Edge display with dynamic Material You wallpaper coloring.</li>
 *   <li>Host physical hardware status card with vCPU, RAM, and virtualization badges.</li>
 *   <li>UTM-style floating instance cards with cyclical alpha breathing animation for RUNNING state.</li>
 *   <li>Context action bottom sheet with desktop, terminal, hardware config, backup, and physical delete.</li>
 * </ul>
 */
class MainActivity : AppCompatActivity() {
    private val operations = Executors.newSingleThreadExecutor()
    private val runningAnimators = mutableListOf<Animator>()
    private lateinit var instanceStore: InstanceLifecycle.FileStore
    private lateinit var settingsManager: SettingsManager
    private lateinit var settingsButton: View
    private lateinit var refusalBanner: Button
    private lateinit var createUbuntuButton: Button
    private lateinit var createDebianButton: Button
    private lateinit var upgradeAuthorizationButton: Button
    private lateinit var createProgress: ProgressBar
    private lateinit var createStatus: TextView
    private lateinit var instanceList: LinearLayout

    private var avfReadiness: AvfCapabilityEvaluator.AvfReadiness? = null
    private var wizardModel: AvfAuthorizationWizardModel? = null
    private var pendingExportInstance: InstanceLifecycle.Instance? = null

    private val safExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { destUri ->
            val instance = pendingExportInstance ?: return@let
            operations.execute {
                try {
                    val adapter = AndroidSafExportAdapter(this, Clock.systemUTC())
                    adapter.exportToDocumentUri(instance, destUri)
                    runOnUiThread {
                        Toast.makeText(this, "实例备份导出成功", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } finally {
                    pendingExportInstance = null
                }
            }
        }
    }

    private val safImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { srcUri ->
            operations.execute {
                runOnUiThread {
                    createProgress.visibility = View.VISIBLE
                    createStatus.text = getString(R.string.import_instance_progress)
                }
                try {
                    val adapter = AndroidSafImportAdapter(this, Clock.systemUTC())
                    val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                    val storageDir = filesDir.toPath().resolve("instances")
                    val imported = adapter.importArchiveToStorage(srcUri, storageDir, controller)
                    runOnUiThread {
                        createProgress.visibility = View.GONE
                        createStatus.text = ""
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.import_instance_success, imported.id()),
                            Toast.LENGTH_SHORT
                        ).show()
                        showInstances()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        createProgress.visibility = View.GONE
                        createStatus.text = getString(R.string.import_instance_failed, e.message)
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.import_instance_failed, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Material You dynamic color extraction
        DynamicColors.applyIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 15/17 Edge-to-Edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val rootContainer = findViewById<View>(R.id.root_container)
        val density = resources.displayMetrics.density
        val baseHorizontalPad = (16 * density).toInt()
        val baseBottomPad = (24 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(
                baseHorizontalPad + statusBarInsets.left,
                statusBarInsets.top + (8 * density).toInt(),
                baseHorizontalPad + statusBarInsets.right,
                navBarInsets.bottom + baseBottomPad
            )
            insets
        }

        settingsManager = SettingsManager(this)
        if (!settingsManager.isOnboardingCompleted) {
            val intent = Intent(this, SetupWizardActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        val assessment = DeviceCapabilityEvaluator.evaluate(deviceProfile())
        avfReadiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
        wizardModel = AvfAuthorizationWizardModel.fromAssessment(avfReadiness!!)

        renderSystemStatus(assessment)

        settingsButton = findViewById(R.id.settings_button)
        refusalBanner = findViewById(R.id.refusal_banner)
        createUbuntuButton = findViewById(R.id.create_ubuntu)
        createDebianButton = findViewById(R.id.create_debian)
        createProgress = findViewById(R.id.create_progress)
        createStatus = findViewById(R.id.create_status)
        instanceList = findViewById(R.id.instance_list)
        upgradeAuthorizationButton = findViewById(R.id.upgrade_authorization)
        instanceStore = InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"))
        reconcileOrphanInstances()

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        val canCreate = assessment.canCreateFullLinuxInstance()
        setCreateButtonsEnabled(canCreate)
        if (!canCreate) {
            refusalBanner.visibility = View.VISIBLE
            refusalBanner.setOnClickListener { showRefusalDetailsDialog(assessment) }
        } else {
            refusalBanner.visibility = View.GONE
        }

        findViewById<View>(R.id.card_create_linux_main)?.setOnClickListener { launchCreationWizard("ubuntu") }
        findViewById<View>(R.id.btn_create_linux_instance)?.setOnClickListener { launchCreationWizard("ubuntu") }
        createUbuntuButton.setOnClickListener { launchCreationWizard("ubuntu") }
        createDebianButton.setOnClickListener { launchCreationWizard("debian") }
        findViewById<View>(R.id.card_create_ubuntu)?.setOnClickListener { launchCreationWizard("ubuntu") }
        findViewById<View>(R.id.card_create_debian)?.setOnClickListener { launchCreationWizard("debian") }
        findViewById<View>(R.id.card_create_fedora)?.setOnClickListener { launchCreationWizard("fedora") }
        findViewById<View>(R.id.create_fedora)?.setOnClickListener { launchCreationWizard("fedora") }
        findViewById<View>(R.id.card_create_arch)?.setOnClickListener { launchCreationWizard("arch") }
        findViewById<View>(R.id.create_arch)?.setOnClickListener { launchCreationWizard("arch") }
        findViewById<View>(R.id.card_create_opensuse)?.setOnClickListener { launchCreationWizard("opensuse") }
        findViewById<View>(R.id.create_opensuse)?.setOnClickListener { launchCreationWizard("opensuse") }
        findViewById<View>(R.id.card_create_almalinux)?.setOnClickListener { launchCreationWizard("almalinux") }
        findViewById<View>(R.id.create_almalinux)?.setOnClickListener { launchCreationWizard("almalinux") }
        findViewById<View>(R.id.card_create_rocky)?.setOnClickListener { launchCreationWizard("rocky") }
        findViewById<View>(R.id.create_rocky)?.setOnClickListener { launchCreationWizard("rocky") }

        upgradeAuthorizationButton.setOnClickListener { showAvfAuthorizationWizard() }
        findViewById<View>(R.id.btn_import_instance)?.setOnClickListener {
            safImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        refreshWizardEntry()
        showInstances()
        handleLaunchIntent(intent)
    }

    private fun launchCreationWizard(distroId: String) {
        val intent = Intent(this, SetupWizardActivity::class.java).apply {
            putExtra(SetupWizardActivity.EXTRA_TARGET_DISTRO, distroId)
        }
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        val targetLaunchId = intent?.getStringExtra("action_launch_instance_id")
        if (targetLaunchId != null && ::instanceStore.isInitialized) {
            intent.removeExtra("action_launch_instance_id")
            try {
                val instance = instanceStore.load(targetLaunchId)
                if (instance != null) {
                    launchInstance(instance)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::instanceStore.isInitialized) {
            reconcileOrphanInstances()
            renderSystemStatus(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
            showInstances()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clearRunningAnimators()
        if (isFinishing) operations.shutdown()
    }

    private fun setCreateButtonsEnabled(enabled: Boolean) {
        if (::createUbuntuButton.isInitialized) createUbuntuButton.isEnabled = enabled
        if (::createDebianButton.isInitialized) createDebianButton.isEnabled = enabled
        findViewById<View>(R.id.create_fedora)?.isEnabled = enabled
        findViewById<View>(R.id.create_arch)?.isEnabled = enabled
        findViewById<View>(R.id.create_opensuse)?.isEnabled = enabled
        findViewById<View>(R.id.create_almalinux)?.isEnabled = enabled
        findViewById<View>(R.id.create_rocky)?.isEnabled = enabled
    }

    private fun currentBadge(assessment: DeviceCapabilityEvaluator.Assessment): String {
        val readiness = avfReadiness
        if (assessment.canCreateFullLinuxInstance() && readiness != null &&
            readiness.status() == AvfCapabilityEvaluator.AvfStatus.READY
        ) {
            return readiness.performanceBadge()
        }
        return assessment.performanceBadge()
    }

    private fun currentCapabilityMatrix(): FullLinuxContract.CapabilityMatrix {
        val assessment = DeviceCapabilityEvaluator.evaluate(deviceProfile())
        val readiness = avfReadiness
        if (assessment.canCreateFullLinuxInstance() && readiness != null &&
            readiness.status() == AvfCapabilityEvaluator.AvfStatus.READY
        ) {
            return FullLinuxContract.CapabilityMatrix.hardwareVirtualizationWithGraphics()
        }
        return FullLinuxContract.CapabilityMatrix.softwareVirtualizationWithGraphics()
    }

    private fun refreshWizardEntry() {
        val readiness = avfReadiness ?: return
        if (!::upgradeAuthorizationButton.isInitialized) return
        upgradeAuthorizationButton.visibility =
            if (readiness.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun showAvfAuthorizationWizard() {
        wizardModel = wizardModel!!.start()
        showShizukuStep()
    }

    private fun showShizukuStep() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wizard_title)
            .setMessage(R.string.wizard_shizuku_steps)
            .setPositiveButton(R.string.wizard_authorization_done) { _, _ -> recheckAuthorization() }
            .setNeutralButton(R.string.wizard_use_adb) { _, _ ->
                wizardModel = wizardModel!!.chooseAdbFallback()
                showAdbStep()
            }
            .setNegativeButton(R.string.wizard_later) { _, _ ->
                wizardModel = wizardModel!!.decline()
            }
            .show()
    }

    private fun showAdbStep() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wizard_title)
            .setMessage(
                getString(
                    R.string.wizard_adb_steps,
                    AvfAuthorizationWizardModel.adbGrantCommand(packageName)
                )
            )
            .setPositiveButton(R.string.wizard_authorization_done) { _, _ -> recheckAuthorization() }
            .setNeutralButton(R.string.wizard_copy_command) { _, _ ->
                copyAdbCommand()
                showAdbStep()
            }
            .setNegativeButton(R.string.wizard_back_to_shizuku) { _, _ ->
                wizardModel = wizardModel!!.backToShizuku()
                showShizukuStep()
            }
            .show()
    }

    private fun copyAdbCommand() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.wizard_copy_command),
                AvfAuthorizationWizardModel.adbGrantCommand(packageName)
            )
        )
        Toast.makeText(this, R.string.wizard_command_copied, Toast.LENGTH_SHORT).show()
    }

    private fun recheckAuthorization() {
        val facts = AvfHostFactsProbe.probe(this)
        val grantedNow = facts.manageVirtualMachinePermission()
        wizardModel = wizardModel!!.confirmAuthorized(grantedNow)
        if (grantedNow) {
            avfReadiness = AvfCapabilityEvaluator.evaluate(facts)
            refreshWizardEntry()
            rerenderSystemStatus()
            AlertDialog.Builder(this)
                .setTitle(R.string.wizard_authorized_success_title)
                .setMessage(R.string.wizard_authorized_success_msg)
                .setPositiveButton(R.string.credentials_close, null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.wizard_vendor_failure_title)
                .setMessage(R.string.wizard_vendor_failure_steps)
                .setPositiveButton(R.string.credentials_close, null)
                .show()
        }
    }

    private fun renderSystemStatus(assessment: DeviceCapabilityEvaluator.Assessment) {
        val profile = HostHardwareDetector.detect(this)
        val cpuChip = findViewById<TextView>(R.id.system_cpu_chip)
        val ramChip = findViewById<TextView>(R.id.system_ram_chip)
        val virtBadge = findViewById<TextView>(R.id.system_virtualization_badge)
        val statusText = findViewById<TextView>(R.id.runtime_status)

        cpuChip?.text = getString(R.string.system_cpu_badge, profile.totalCores())
        ramChip?.text = getString(R.string.system_ram_badge, profile.availRamMb(), profile.totalRamMb())
        virtBadge?.text = currentBadge(assessment)

        statusText?.text = buildString {
            appendLine(getString(R.string.runtime_class_full_linux))
            if (assessment.canCreateFullLinuxInstance()) {
                appendLine(getString(R.string.support_floor_passed))
            } else {
                appendLine(getString(R.string.support_floor_refused, assessment.refusalReason()))
            }
            assessment.emulatorNotice()?.let(::appendLine)
            append(verifiedQemuStatus())
        }
    }

    private fun rerenderSystemStatus() {
        renderSystemStatus(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
    }

    private fun clearRunningAnimators() {
        runningAnimators.forEach { it.cancel() }
        runningAnimators.clear()
    }

    private fun startBreathingAnimation(badgeView: View) {
        val animator = ObjectAnimator.ofFloat(badgeView, View.ALPHA, 1.0f, 0.35f).apply {
            duration = 1000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        animator.start()
        runningAnimators.add(animator)
    }

    private fun showInstances() {
        clearRunningAnimators()
        instanceList.removeAllViews()
        val instances = try {
            instanceStore.list()
        } catch (_: IOException) {
            createStatus.text = getString(R.string.create_failed, "instance metadata unavailable")
            return
        }

        val emptyView = findViewById<TextView>(R.id.instances_empty)
        val countBadge = findViewById<TextView>(R.id.instances_count_badge)
        countBadge?.text = "${instances.size} 个实例"

        if (instances.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        } else {
            emptyView?.visibility = View.GONE
        }

        val capabilityMatrix = currentCapabilityMatrix()
        val supportsDesktop = capabilityMatrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND)
        val strategyBadge = currentBadge(DeviceCapabilityEvaluator.evaluate(deviceProfile()))

        val inflater = LayoutInflater.from(this)
        for (instance in instances) {
            val cardView = inflater.inflate(R.layout.layout_instance_card, instanceList, false)
            val uiModel = InstanceCardUiModel.from(instance, strategyBadge, supportsDesktop)

            val distroIcon = cardView.findViewById<ImageView>(R.id.card_distro_icon)
            val distroTitle = cardView.findViewById<TextView>(R.id.card_distro_title)
            val instanceIdText = cardView.findViewById<TextView>(R.id.card_instance_id)
            val statusBadge = cardView.findViewById<TextView>(R.id.card_status_badge)
            val hardwareSpecs = cardView.findViewById<TextView>(R.id.card_hardware_specs)
            val ipAddress = cardView.findViewById<TextView>(R.id.card_ip_address)
            val btnPower = cardView.findViewById<com.google.android.material.button.MaterialButton>(R.id.card_btn_power)

            // Distro Icon & Info
            distroIcon?.setImageResource(
                DistroIconHelper.getIconRes(uiModel.distroId)
            )
            distroTitle?.text = "${uiModel.distroDisplayName} Linux ${uiModel.distroVersion}"
            instanceIdText?.text = getString(R.string.instance_card_id_prefix, uiModel.shortId)

            // State Badge & Breathing animation
            statusBadge?.text = uiModel.stateBadgeText
            statusBadge?.setTextColor(getColor(uiModel.stateBadgeColorRes))
            if (uiModel.isBreathing) {
                startBreathingAnimation(statusBadge)
            }

            // Hardware Specs & Virtual IP Address
            hardwareSpecs?.text = uiModel.hardwareSpecsText
            ipAddress?.text = uiModel.ipAddress

            // Quick Power Action Button
            if (instance.state() == InstanceLifecycle.State.RUNNING) {
                btnPower?.text = "关机"
                btnPower?.setIconResource(R.drawable.ic_power)
                btnPower?.setOnClickListener {
                    showPowerDialog(instance)
                }
            } else {
                btnPower?.text = "开机"
                btnPower?.setIconResource(R.drawable.ic_power)
                btnPower?.setOnClickListener {
                    launchInstance(instance)
                }
            }

            // Material scale-up transition to InstanceDetailActivity
            cardView.setOnClickListener {
                val intent = Intent(this, InstanceDetailActivity::class.java).apply {
                    putExtra(InstanceDetailActivity.EXTRA_INSTANCE_ID, instance.id())
                }
                val options = ActivityOptionsCompat.makeScaleUpAnimation(
                    cardView, 0, 0, cardView.width.coerceAtLeast(1), cardView.height.coerceAtLeast(1)
                )
                startActivity(intent, options.toBundle())
            }

            cardView.setOnLongClickListener {
                showInstanceActionBottomSheet(instance, uiModel)
                true
            }

            instanceList.addView(cardView)
        }
    }

    private fun showPowerDialog(instance: InstanceLifecycle.Instance) {
        val options = arrayOf("正常关机 (Graceful Shutdown)", "强制断电 (Force Kill)")
        AlertDialog.Builder(this)
            .setTitle("电源管理 · ${instance.id().take(8)}")
            .setItems(options) { _, which ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                        controller.recordGracefulStop(instance.id())
                        OrchestellaForegroundService.stop(this@MainActivity)
                        withContext(Dispatchers.Main) {
                            showInstances()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun isHeadless(instance: InstanceLifecycle.Instance): Boolean {
        return false
    }

    private fun showHeadlessDesktopDialog(instance: InstanceLifecycle.Instance) {
        AlertDialog.Builder(this)
            .setTitle(R.string.desktop_headless_title)
            .setMessage(R.string.desktop_headless_message)
            .setPositiveButton(R.string.instance_open_terminal) { _, _ ->
                openTerminal(instance)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun openTerminal(instance: InstanceLifecycle.Instance) {
        val intent = Intent(this@MainActivity, TerminalActivity::class.java)
            .putExtra("bridgeToken", instance.bridgeToken())
            .putExtra("instanceId", instance.id())
            .putExtra("hostname", instance.hostname())
            .putExtra("defaultUser", instance.defaultUser())
        startActivity(intent)
    }

    private fun launchDesktop(instance: InstanceLifecycle.Instance) {
        if (isHeadless(instance)) {
            showHeadlessDesktopDialog(instance)
            return
        }
        val intent = DesktopLaunchIntent.create(
            this@MainActivity,
            instance.id(),
            instance.bridgeToken(),
            false
        )
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
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.credentials_close, null)
            .apply {
                if (diag.logPath() != null) {
                    setNeutralButton(R.string.crash_dialog_copy) { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("CrashLogPath", diag.logPath().toString()))
                        Toast.makeText(this@MainActivity, "日志路径已复制", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun showInstanceActionBottomSheet(instance: InstanceLifecycle.Instance, uiModel: InstanceCardUiModel) {
        val sheet = InstanceActionBottomSheet(this, instance, uiModel, object : InstanceActionBottomSheet.Callbacks {
            override fun onLaunchDesktop(instance: InstanceLifecycle.Instance) {
                if (instance.state() == InstanceLifecycle.State.RUNNING) {
                    launchDesktop(instance)
                } else {
                    launchInstance(instance)
                }
            }

            override fun onOpenTerminal(instance: InstanceLifecycle.Instance) {
                if (instance.state() == InstanceLifecycle.State.RUNNING) {
                    openTerminal(instance)
                } else {
                    launchInstance(instance)
                }
            }

            override fun onConfigureHardware(instance: InstanceLifecycle.Instance) {
                val hostProfile = HostHardwareDetector.detect(this@MainActivity)
                VmHardwareConfigDialog(this@MainActivity, instance, hostProfile) { newConfig, expandedDiskBytes ->
                    operations.execute {
                        try {
                            val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                            val result = InstanceHardwareConfigEditor.apply(
                                instance,
                                newConfig,
                                expandedDiskBytes,
                                controller,
                                hostProfile,
                                null
                            )
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, result.noticeMessage, Toast.LENGTH_SHORT).show()
                                showInstances()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, getString(R.string.hardware_update_failed, e.message), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }.show()
            }

            override fun onExportBackup(instance: InstanceLifecycle.Instance) {
                pendingExportInstance = instance
                safExportLauncher.launch("${instance.id()}-backup.zip")
            }

            override fun onManagePortForwarding(instance: InstanceLifecycle.Instance) {
                try {
                    val netPath = filesDir.toPath().resolve("network")
                    Files.createDirectories(netPath)
                    val store = FilePortForwardingStore(netPath)
                    val manager = PortForwardingManager(instance.id(), store, PortConflictDetector())
                    PortForwardingBottomSheet(this@MainActivity, instance.id(), manager).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "打开端口转发管理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onShowCredentials(instance: InstanceLifecycle.Instance) {
                showCredentialsDialog(instance)
            }

            override fun onClone(instance: InstanceLifecycle.Instance) {
                confirmCloneInstance(instance)
            }

            override fun onDelete(instance: InstanceLifecycle.Instance) {
                confirmDeleteInstance(instance)
            }
        })
        sheet.show()
    }

    private fun confirmCloneInstance(instance: InstanceLifecycle.Instance) {
        if (!InstanceActionSafety.canClone(instance.state())) {
            Toast.makeText(this, R.string.action_clone_running_forbidden, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.action_clone_confirm_title)
            .setMessage(getString(R.string.action_clone_confirm_message, instance.id()))
            .setPositiveButton(R.string.action_clone_confirm_btn) { _, _ ->
                operations.execute {
                    try {
                        val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                        val cloned = controller.cloneInstance(instance.id())
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.action_clone_success, cloned.id()),
                                Toast.LENGTH_SHORT
                            ).show()
                            showInstances()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.action_clone_failed, e.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_delete_cancel_btn, null)
            .show()
    }

    private fun confirmDeleteInstance(instance: InstanceLifecycle.Instance) {
        if (!InstanceActionSafety.canDelete(instance.state())) {
            Toast.makeText(this, R.string.action_delete_running_forbidden, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete_confirm_title)
            .setMessage(getString(R.string.action_delete_confirm_message, instance.id()))
            .setPositiveButton(R.string.action_delete_confirm_btn) { _, _ ->
                operations.execute {
                    try {
                        val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                        controller.delete(instance.id())
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.action_delete_success, instance.id()),
                                Toast.LENGTH_SHORT
                            ).show()
                            showInstances()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.action_delete_failed, e.message),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_delete_cancel_btn, null)
            .show()
    }

    private fun reconcileOrphanInstances() {
        if (!::instanceStore.isInitialized) return
        try {
            val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
            for (instance in instanceStore.list()) {
                if (instance.state() == InstanceLifecycle.State.RUNNING && ExecutionSessionRegistry.get(instance.id()) == null) {
                    controller.reconcileAfterHostDeath(instance.id(), InstanceLifecycle.State.STOPPED)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun launchInstance(instance: InstanceLifecycle.Instance) {
        setCreateButtonsEnabled(false)
        createStatus.setText(R.string.launch_started)
        operations.execute {
            reconcileOrphanInstances()
            val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
            var running = false
            try {
                InstanceLaunchSequence.beforeLaunch(controller, instance)
                running = true
                val distroTitle = instance.distro().id().replaceFirstChar { it.uppercase() } + " " + instance.distro().version()
                val badge = currentBadge(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
                settingsManager.lastActiveInstanceId = instance.id()
                OrchestellaForegroundService.start(this, instance.id(), distroTitle, badge, settingsManager.batteryPolicy)
                val rootfsFileName = DistroCatalogRegistry.getInstance().get(instance.distro().id())?.rootfsFileName()
                    ?: if (instance.distro().id() == "alpine") "alpine-minirootfs-3.24.1-aarch64.tar.gz"
                    else "${instance.distro().id()}-rootfs.tar.xz"
                val mirrorConfigFile = filesDir.resolve("instances").resolve("${instance.id()}.mirror")
                val firstbootFile = filesDir.resolve("instances").resolve("${instance.id()}.firstboot")
                val mirrorConfigName = DistroMirrorProvisioner.getConfigFileName(instance.distro().id())
                val mirrorBytes = if (mirrorConfigFile.exists()) Files.readAllBytes(mirrorConfigFile.toPath()) else null
                val firstbootBytes = if (firstbootFile.exists()) {
                    val bytes = Files.readAllBytes(firstbootFile.toPath())
                    firstbootFile.delete()
                    bytes
                } else null

                val provisionPayload = FirstBootProvisionPipeline.ProvisionPayload(
                    mirrorConfigName,
                    mirrorBytes,
                    firstbootBytes
                )

                val parameters = InstanceStrategyLauncher.LaunchParameters(
                    instance.id(),
                    instance.bridgeToken(),
                    rootfsFileName,
                    instance.rootVolume().toString(),
                    filesDir.resolve("runtime").resolve(instance.id()).absolutePath,
                    filesDir.resolve("distro-inputs").absolutePath,
                    instance.guestPassword(),
                    instance.hardwareConfig(),
                    provisionPayload
                )
                val preferAvf =
                    avfReadiness?.status() == AvfCapabilityEvaluator.AvfStatus.READY
                val strategyLauncher = InstanceStrategyLauncher(
                    { params -> AvfInstanceLauncher(this).launch(params) },
                    { params -> QemuInstanceLauncher(this).launch(params) },
                    { failure ->
                        AvfDegradationLog(
                            filesDir.resolve("avf-degradations.txt").toPath(),
                        ).record(failure)
                    })
                val outcome = strategyLauncher.launch(parameters, preferAvf)
                val session = outcome.session
                Thread {
                    try {
                        session.waitFor()
                    } catch (ignored: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (failure: Exception) {
                        runOnUiThread {
                            createStatus.text = getString(R.string.launch_failed, failure.toString())
                        }
                    } finally {
                        ExecutionSessionRegistry.unregister(instance.id())
                        OrchestellaForegroundService.stop(this)
                        try {
                            InstanceLaunchSequence.afterGuestExit(controller, instance.id())
                        } catch (ignored: Exception) {
                        }
                        runOnUiThread {
                            createStatus.setText(R.string.launch_guest_exited)
                            setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                            showInstances()
                        }
                    }
                }.apply { isDaemon = true }.start()
                runOnUiThread {
                    if (outcome.hardwareAccelerated()) {
                        createStatus.setText(R.string.launch_started_hardware)
                    } else if (outcome.degradationReason != null) {
                        createStatus.text = getString(
                            R.string.launch_degraded_to_software, outcome.degradationReason
                        )
                    }
                    val intent = Intent(this, TerminalActivity::class.java)
                        .putExtra("bridgeToken", instance.bridgeToken())
                        .putExtra("instanceId", instance.id())
                        .putExtra("hostname", instance.hostname())
                        .putExtra("defaultUser", instance.defaultUser())
                    startActivity(intent)
                }
            } catch (failure: Throwable) {
                ExecutionSessionRegistry.unregister(instance.id())
                OrchestellaForegroundService.stop(this)
                if (running) {
                    try {
                        InstanceLaunchSequence.afterGuestExit(controller, instance.id())
                    } catch (ignored: Throwable) {
                        // best-effort revert of the RUNNING slot; the launch failure is reported
                    }
                }
                runOnUiThread {
                    createStatus.text = getString(R.string.launch_failed, failure.toString())
                    setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                    showInstances()
                }
            }
        }
    }

    private fun createDistro(manifest: DistroManifest) {
        val distroDisplayName = manifest.id().replaceFirstChar { it.uppercase() }
        val availableBytes = StatFs(filesDir.path).availableBytes
        if (availableBytes < DistroInstanceCreator.DEFAULT_ROOT_VOLUME_BYTES + manifest.sizeBytes() + 128L * 1024L * 1024L) {
            createStatus.setText(R.string.create_storage_low)
            return
        }
        setCreateButtonsEnabled(false)
        createProgress.visibility = View.VISIBLE
        createProgress.isIndeterminate = true
        createStatus.text = getString(R.string.create_downloading_distro, distroDisplayName)

        val cachedFile = filesDir.toPath()
            .resolve("distro-inputs")
            .resolve(manifest.rootfsFileName())

        operations.execute {
            val result = try {
                ResilientDownloadEngine().download(
                    DistroMirrorCatalog.mirrorsFor(manifest),
                    cachedFile,
                    manifest.sha256(),
                    object : DownloadProgressCallback {
                        override fun onProgress(
                            downloadedBytes: Long,
                            totalBytes: Long,
                            speedMBps: Double,
                            etaSeconds: Long,
                            currentMirror: String
                        ) {
                            runOnUiThread {
                                renderCreateDownloadProgress(
                                    downloadedBytes,
                                    totalBytes,
                                    speedMBps,
                                    etaSeconds,
                                    currentMirror,
                                )
                            }
                        }

                        override fun onRetry(currentMirror: String, attempt: Int, backoffMillis: Long, error: Throwable) {
                            runOnUiThread {
                                createStatus.text =
                                    getString(R.string.create_download_retry_info, mirrorHost(currentMirror), attempt)
                            }
                        }

                        override fun onMirrorFailover(failedMirror: String, nextMirror: String?, error: Throwable) {
                            runOnUiThread {
                                val nextHost = nextMirror?.let(::mirrorHost) ?: "--"
                                createStatus.text = getString(R.string.create_download_failover_info, nextHost)
                            }
                        }
                    })

                runOnUiThread {
                    createProgress.isIndeterminate = true
                    createStatus.setText(R.string.create_provisioning_info)
                }

                Files.newInputStream(cachedFile).use { input ->
                    DistroInstanceCreator(
                        manifest,
                        InstanceLifecycle.Controller(instanceStore, Clock.systemUTC()),
                        filesDir.toPath(),
                    ).create(input, manifest.sizeBytes())
                }
                getString(R.string.create_success)
            } catch (rejected: DistroManifest.InputRejectedException) {
                android.util.Log.e("Orchestella", "createDistro rejected", rejected)
                getString(R.string.create_invalid_input_distro, distroDisplayName, rejected.reason())
            } catch (checksum: ChecksumMismatchException) {
                android.util.Log.e("Orchestella", "createDistro checksum mismatch", checksum)
                getString(R.string.create_checksum_failed_distro, distroDisplayName)
            } catch (io: IOException) {
                android.util.Log.e("Orchestella", "createDistro IO error", io)
                getString(R.string.create_network_failed_distro, distroDisplayName)
            } catch (e: Exception) {
                android.util.Log.e("Orchestella", "createDistro failed", e)
                getString(R.string.create_failed, "${e.javaClass.simpleName}: ${e.message}")
            }
            runOnUiThread {
                createProgress.visibility = View.GONE
                setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                createStatus.text = result
                showInstances()
            }
        }
    }

    private fun renderCreateDownloadProgress(
        downloadedBytes: Long,
        totalBytes: Long,
        speedMBps: Double,
        etaSeconds: Long,
        currentMirror: String,
    ) {
        if (totalBytes > 0) {
            createProgress.isIndeterminate = false
            createProgress.progress = ((downloadedBytes * 100) / totalBytes).toInt()
        } else {
            createProgress.isIndeterminate = true
        }
        val downloadedStr = String.format("%.1f MB", downloadedBytes / (1024.0 * 1024.0))
        val totalStr = if (totalBytes > 0) String.format("%.1f MB", totalBytes / (1024.0 * 1024.0)) else "--"
        val etaStr = if (etaSeconds >= 0) "${etaSeconds}s" else "--"
        createStatus.text = getString(
            R.string.create_download_progress_info,
            speedMBps,
            downloadedStr,
            totalStr,
            etaStr,
            mirrorHost(currentMirror),
        )
    }

    private fun mirrorHost(mirrorUrl: String): String =
        try {
            URI(mirrorUrl).host ?: mirrorUrl
        } catch (_: Exception) {
            mirrorUrl
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

    private fun verifiedQemuStatus(): String =
        try {
            assets.open(RuntimeArtifactManifest.SCHEMA_FILE_NAME).use { input ->
                val manifest = RuntimeArtifactManifest.parse(input)
                val nativeLibraryDirectory = applicationInfo.nativeLibraryDir
                    ?: return getString(R.string.qemu_runtime_unavailable, "nativeLibraryDir unavailable")
                manifest.requireVerifiedInstalledQemu(Paths.get(nativeLibraryDirectory))
                getString(R.string.qemu_runtime_verified)
            }
        } catch (rejection: RuntimeArtifactManifest.IntegrityRejectedException) {
            getString(R.string.qemu_runtime_unavailable, rejection.reason())
        } catch (_: IOException) {
            getString(R.string.qemu_runtime_unavailable, "runtime manifest unavailable")
        }

    private fun showCredentialsDialog(instance: InstanceLifecycle.Instance) {
        val message = "默认主机名：${instance.hostname()}\n用户名：${instance.defaultUser()}\n密码：${instance.guestPassword()}\n\n提示：遵循工单 03 规范，默认主机名为 orchestella，超级用户为 root / 1234。"
        AlertDialog.Builder(this)
            .setTitle(R.string.credentials_title)
            .setMessage(message)
            .setPositiveButton(R.string.credentials_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("GuestPassword", instance.guestPassword()))
                Toast.makeText(this, R.string.credentials_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.credentials_change) { _, _ ->
                showChangePasswordDialog(instance)
            }
            .setNegativeButton(R.string.credentials_close, null)
            .show()
    }

    private fun showChangePasswordDialog(instance: InstanceLifecycle.Instance) {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.credentials_change_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.credentials_change_title)
            .setView(container)
            .setPositiveButton(R.string.credentials_change) { _, _ ->
                val newPass = input.text.toString().trim()
                if (newPass.length < 8 || newPass.contains(" ") || newPass.contains("\t") || newPass.contains("\n")) {
                    Toast.makeText(this, R.string.credentials_invalid, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                operations.execute {
                    try {
                        val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                        controller.updateGuestPassword(instance.id(), newPass)
                        runOnUiThread {
                            Toast.makeText(this, R.string.credentials_updated, Toast.LENGTH_SHORT).show()
                            showInstances()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, e.message ?: "修改失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.credentials_close, null)
            .show()
    }

    private fun showRefusalDetailsDialog(assessment: DeviceCapabilityEvaluator.Assessment) {
        AlertDialog.Builder(this)
            .setTitle(R.string.refusal_dialog_title)
            .setMessage(getString(R.string.refusal_dialog_message, assessment.refusalReason()))
            .setPositiveButton(R.string.credentials_close, null)
            .show()
    }

    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val bootCheckbox = android.widget.CheckBox(this).apply {
            text = getString(R.string.settings_boot_autostart)
            isChecked = settingsManager.isBootAutoStartEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isBootAutoStartEnabled = isChecked
            }
        }
        val bootDesc = TextView(this).apply {
            text = getString(R.string.settings_boot_autostart_desc)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(20, 0, 0, 16)
        }

        val updateCheckbox = android.widget.CheckBox(this).apply {
            text = getString(R.string.settings_update_check)
            isChecked = settingsManager.isUpdateCheckEnabled
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.isUpdateCheckEnabled = isChecked
            }
        }
        val updateDesc = TextView(this).apply {
            text = getString(R.string.settings_update_check_desc)
            setTextAppearance(android.R.style.TextAppearance_Material_Caption)
            setPadding(20, 0, 0, 16)
        }

        val batteryLabel = TextView(this).apply {
            text = getString(R.string.settings_battery_policy)
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
            setPadding(0, 8, 0, 8)
        }
        val batteryGroup = android.widget.RadioGroup(this)
        val balancedRadio = android.widget.RadioButton(this).apply {
            text = getString(R.string.settings_battery_balanced)
            isChecked = settingsManager.batteryPolicy == BatteryPolicy.BALANCED
        }
        val perfRadio = android.widget.RadioButton(this).apply {
            text = getString(R.string.settings_battery_performance)
            isChecked = settingsManager.batteryPolicy == BatteryPolicy.PERFORMANCE
        }
        batteryGroup.addView(balancedRadio)
        batteryGroup.addView(perfRadio)
        batteryGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == balancedRadio.id) {
                settingsManager.batteryPolicy = BatteryPolicy.BALANCED
            } else if (checkedId == perfRadio.id) {
                settingsManager.batteryPolicy = BatteryPolicy.PERFORMANCE
            }
        }

        val checkUpdateBtn = Button(this).apply {
            text = getString(R.string.settings_check_update_now)
            setOnClickListener { checkForUpdateManual() }
        }

        val phantomGuideBtn = Button(this).apply {
            text = getString(R.string.settings_phantom_guide)
            setOnClickListener { PhantomProcessKillerGuide.showDialog(this@MainActivity) }
        }

        val wizardBtn = Button(this).apply {
            text = "首次启动向导 (设置引导)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SetupWizardActivity::class.java))
            }
        }

        container.addView(bootCheckbox)
        container.addView(bootDesc)
        container.addView(updateCheckbox)
        container.addView(updateDesc)
        container.addView(batteryLabel)
        container.addView(batteryGroup)
        container.addView(checkUpdateBtn)
        container.addView(phantomGuideBtn)
        container.addView(wizardBtn)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(container)
            .setPositiveButton(R.string.settings_close, null)
            .show()
    }

    private fun checkForUpdateManual() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        operations.execute {
            val checker = GitHubUpdateChecker(settingsManager)
            val versionName = try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                "0.1.0-dev"
            }
            val result = checker.checkForUpdate(versionName)
            runOnUiThread {
                when (result.status()) {
                    GitHubUpdateChecker.Status.DISABLED -> {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.settings_update_check)
                            .setMessage(R.string.update_disabled_msg)
                            .setPositiveButton(R.string.credentials_close, null)
                            .show()
                    }
                    GitHubUpdateChecker.Status.UP_TO_DATE -> {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.settings_update_check)
                            .setMessage(getString(R.string.update_up_to_date, result.latestVersion() ?: versionName))
                            .setPositiveButton(R.string.credentials_close, null)
                            .show()
                    }
                    GitHubUpdateChecker.Status.UPDATE_AVAILABLE -> {
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.update_available_title, result.latestVersion()))
                            .setMessage(getString(R.string.update_available_msg, result.releaseNotes()))
                            .setPositiveButton(R.string.update_open_browser) { _, _ ->
                                if (result.releaseUrl() != null) {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        android.net.Uri.parse(result.releaseUrl())
                                    )
                                    startActivity(intent)
                                }
                            }
                            .setNegativeButton(R.string.credentials_close, null)
                            .show()
                    }
                    GitHubUpdateChecker.Status.ERROR -> {
                        Toast.makeText(this, result.errorMessage() ?: "检查失败", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
