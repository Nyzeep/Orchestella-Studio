package io.github.nyzeep.droidlayer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.nyzeep.droidlayer.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.droidlayer.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle
import io.github.nyzeep.droidlayer.runtime.network.download.DownloadProgressCallback
import io.github.nyzeep.droidlayer.runtime.network.download.MirrorPriorityList
import io.github.nyzeep.droidlayer.runtime.network.download.ResilientDownloadEngine
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.Executors

/**
 * Three-step first launch Setup Wizard for Orchestella Studio (Ticket 53, US-28, US-29, spec-m4).
 *
 * <p>Delivers:
 * <ul>
 *   <li>Step 1 (STEP_DETECT): Architecture, multi-core CPU, RAM, disk, and AVF hardware readiness inspection.</li>
 *   <li>Step 2 (STEP_DISTRO): Distro choice between Alpine Linux (minimalist/container) and Debian (full ecosystem).</li>
 *   <li>Step 3 (STEP_CONFIG): Hardware allocation review & resilient breakpoint download via {@link ResilientDownloadEngine}.</li>
 * </ul>
 */
class SetupWizardActivity : AppCompatActivity() {

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private lateinit var settingsManager: SettingsManager
    private lateinit var wizardModel: SetupWizardModel

    // Header views
    private lateinit var txtStepBadge: TextView

    // Step containers
    private lateinit var step1Container: LinearLayout
    private lateinit var step2Container: LinearLayout
    private lateinit var step3Container: LinearLayout

    // Step 1 Views
    private lateinit var txtHwCpuArch: TextView
    private lateinit var txtHwCpuCores: TextView
    private lateinit var txtHwRamOverview: TextView
    private lateinit var txtHwStorage: TextView
    private lateinit var txtVirtStatusBadge: TextView
    private lateinit var txtVirtStatusDesc: TextView
    private lateinit var layoutVirtAuthActions: LinearLayout
    private lateinit var btnWizardAuth: MaterialButton
    private lateinit var btnWizardSkipAuth: MaterialButton
    private lateinit var btnStep1Next: MaterialButton

    // Step 2 Views
    private lateinit var cardDistroAlpine: MaterialCardView
    private lateinit var cardDistroDebian: MaterialCardView
    private lateinit var badgeSelectedAlpine: TextView
    private lateinit var badgeSelectedDebian: TextView
    private lateinit var btnStep2Prev: MaterialButton
    private lateinit var btnStep2Next: MaterialButton

    // Step 3 Views
    private lateinit var txtCfgVcpu: TextView
    private lateinit var txtCfgRam: TextView
    private lateinit var txtCfgDisk: TextView
    private lateinit var txtCfgGpu: TextView
    private lateinit var badgeCfgZone: TextView
    private lateinit var btnWizardInit: MaterialButton
    private lateinit var layoutDownloadProgress: LinearLayout
    private lateinit var txtDownloadStatus: TextView
    private lateinit var progressDownloadBar: LinearProgressIndicator
    private lateinit var txtDownloadSpeedEta: TextView
    private lateinit var txtDownloadMirror: TextView
    private lateinit var btnStep3Prev: MaterialButton

    private var avfReadiness: AvfCapabilityEvaluator.AvfReadiness? = null
    private var authWizardModel: AvfAuthorizationWizardModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Material You dynamic color extraction
        DynamicColors.applyIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        // Edge-to-Edge transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val rootContainer = findViewById<View>(R.id.wizard_root_container)
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
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

        settingsManager = SettingsManager(this)
        val hostProfile = HostHardwareDetector.detect(this)
        wizardModel = SetupWizardModel(hostProfile)

        initViews()
        setupListeners()
        bindStep1HardwareFacts()
        updateStepVisibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            backgroundExecutor.shutdown()
        }
    }

    private fun initViews() {
        txtStepBadge = findViewById(R.id.wizard_step_badge)

        step1Container = findViewById(R.id.step1_container)
        step2Container = findViewById(R.id.step2_container)
        step3Container = findViewById(R.id.step3_container)

        // Step 1
        txtHwCpuArch = findViewById(R.id.txt_hw_cpu_arch)
        txtHwCpuCores = findViewById(R.id.txt_hw_cpu_cores)
        txtHwRamOverview = findViewById(R.id.txt_hw_ram_overview)
        txtHwStorage = findViewById(R.id.txt_hw_storage)
        txtVirtStatusBadge = findViewById(R.id.txt_virt_status_badge)
        txtVirtStatusDesc = findViewById(R.id.txt_virt_status_desc)
        layoutVirtAuthActions = findViewById(R.id.layout_virt_auth_actions)
        btnWizardAuth = findViewById(R.id.btn_wizard_auth)
        btnWizardSkipAuth = findViewById(R.id.btn_wizard_skip_auth)
        btnStep1Next = findViewById(R.id.btn_step1_next)

        // Step 2
        cardDistroAlpine = findViewById(R.id.card_distro_alpine)
        cardDistroDebian = findViewById(R.id.card_distro_debian)
        badgeSelectedAlpine = findViewById(R.id.badge_selected_alpine)
        badgeSelectedDebian = findViewById(R.id.badge_selected_debian)
        btnStep2Prev = findViewById(R.id.btn_step2_prev)
        btnStep2Next = findViewById(R.id.btn_step2_next)

        // Step 3
        txtCfgVcpu = findViewById(R.id.txt_cfg_vcpu)
        txtCfgRam = findViewById(R.id.txt_cfg_ram)
        txtCfgDisk = findViewById(R.id.txt_cfg_disk)
        txtCfgGpu = findViewById(R.id.txt_cfg_gpu)
        badgeCfgZone = findViewById(R.id.badge_cfg_zone)
        btnWizardInit = findViewById(R.id.btn_wizard_init)
        layoutDownloadProgress = findViewById(R.id.layout_download_progress)
        txtDownloadStatus = findViewById(R.id.txt_download_status)
        progressDownloadBar = findViewById(R.id.progress_download_bar)
        txtDownloadSpeedEta = findViewById(R.id.txt_download_speed_eta)
        txtDownloadMirror = findViewById(R.id.txt_download_mirror)
        btnStep3Prev = findViewById(R.id.btn_step3_prev)
    }

    private fun setupListeners() {
        // Step 1 buttons
        btnWizardAuth.setOnClickListener { showAvfAuthorizationWizard() }
        btnWizardSkipAuth.setOnClickListener { advanceToStep(SetupWizardModel.Step.STEP_DISTRO) }
        btnStep1Next.setOnClickListener { advanceToStep(SetupWizardModel.Step.STEP_DISTRO) }

        // Step 2 Distro selection
        cardDistroAlpine.setOnClickListener { selectDistro(SetupWizardModel.DistroOption.ALPINE) }
        cardDistroDebian.setOnClickListener { selectDistro(SetupWizardModel.DistroOption.DEBIAN) }
        btnStep2Prev.setOnClickListener { retreatToStep(SetupWizardModel.Step.STEP_DETECT) }
        btnStep2Next.setOnClickListener { advanceToStep(SetupWizardModel.Step.STEP_CONFIG) }

        // Step 3 buttons
        btnStep3Prev.setOnClickListener {
            if (wizardModel.canGoBack()) {
                retreatToStep(SetupWizardModel.Step.STEP_DISTRO)
            }
        }
        btnWizardInit.setOnClickListener { startDownloadAndInitialize() }
    }

    private fun bindStep1HardwareFacts() {
        val profile = wizardModel.hardwareProfile
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        txtHwCpuArch.text = getString(R.string.wizard_cpu_arch, arch)
        txtHwCpuCores.text = getString(R.string.wizard_cpu_cores, profile.totalCores())
        txtHwRamOverview.text = getString(R.string.wizard_ram_overview, profile.availRamMb(), profile.totalRamMb())

        val statFs = StatFs(filesDir.path)
        val availableBytes = statFs.availableBytes
        val availableGb = availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        txtHwStorage.text = getString(R.string.wizard_storage_available, String.format("%.1f GB", availableGb))

        // Virtualization evaluation
        val facts = AvfHostFactsProbe.probe(this)
        val assessment = AvfCapabilityEvaluator.evaluate(facts)
        avfReadiness = assessment
        authWizardModel = AvfAuthorizationWizardModel.fromAssessment(assessment)

        when (assessment.status()) {
            AvfCapabilityEvaluator.AvfStatus.READY -> {
                txtVirtStatusBadge.setText(R.string.wizard_virt_avf_ready_badge)
                txtVirtStatusBadge.setTextColor(getColor(R.color.status_running))
                txtVirtStatusDesc.setText(R.string.wizard_virt_avf_ready_desc)
                layoutVirtAuthActions.visibility = View.GONE
            }
            AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> {
                txtVirtStatusBadge.setText(R.string.wizard_virt_avf_auth_badge)
                txtVirtStatusBadge.setTextColor(getColor(R.color.zone_yellow))
                txtVirtStatusDesc.setText(R.string.wizard_virt_avf_auth_desc)
                layoutVirtAuthActions.visibility = View.VISIBLE
            }
            else -> {
                txtVirtStatusBadge.setText(R.string.wizard_virt_qemu_badge)
                txtVirtStatusBadge.setTextColor(getColor(R.color.status_provisioning))
                txtVirtStatusDesc.setText(R.string.wizard_virt_qemu_desc)
                layoutVirtAuthActions.visibility = View.GONE
            }
        }
    }

    private fun selectDistro(option: SetupWizardModel.DistroOption) {
        wizardModel.selectDistro(option)
        val isAlpine = option == SetupWizardModel.DistroOption.ALPINE

        cardDistroAlpine.strokeWidth = if (isAlpine) dpToPx(2) else dpToPx(1)
        cardDistroAlpine.strokeColor = getColor(if (isAlpine) R.color.zone_green else android.R.color.darker_gray)
        badgeSelectedAlpine.visibility = if (isAlpine) View.VISIBLE else View.GONE

        cardDistroDebian.strokeWidth = if (!isAlpine) dpToPx(2) else dpToPx(1)
        cardDistroDebian.strokeColor = getColor(if (!isAlpine) R.color.zone_green else android.R.color.darker_gray)
        badgeSelectedDebian.visibility = if (!isAlpine) View.VISIBLE else View.GONE
    }

    private fun bindStep3ConfigFacts() {
        val config = wizardModel.hardwareConfig
        txtCfgVcpu.text = getString(R.string.wizard_config_vcpu_info, config.cpuCores())
        txtCfgRam.text = getString(R.string.wizard_config_ram_info, config.ramMegabytes())
        val diskGb = (config.diskCapacityBytes() / (1024L * 1024L * 1024L)).toInt()
        txtCfgDisk.text = getString(R.string.wizard_config_disk_info, diskGb)
        txtCfgGpu.text = getString(R.string.wizard_config_gpu_info)

        val zone = wizardModel.safetyZone()
        when (zone) {
            HostHardwareProfile.SafetyZone.GREEN -> {
                badgeCfgZone.setText(R.string.wizard_config_zone_green)
                badgeCfgZone.setTextColor(getColor(R.color.zone_green))
            }
            HostHardwareProfile.SafetyZone.YELLOW -> {
                badgeCfgZone.setText(R.string.hardware_ram_zone_yellow)
                badgeCfgZone.setTextColor(getColor(R.color.zone_yellow))
            }
            HostHardwareProfile.SafetyZone.RED -> {
                badgeCfgZone.setText(R.string.hardware_ram_zone_red)
                badgeCfgZone.setTextColor(getColor(R.color.zone_red))
            }
        }

        btnWizardInit.text = "🚀 立即初始化 ${wizardModel.selectedDistro.displayName}"
    }

    private fun advanceToStep(step: SetupWizardModel.Step) {
        wizardModel.goToStep(step)
        updateStepVisibility()
    }

    private fun retreatToStep(step: SetupWizardModel.Step) {
        wizardModel.goToStep(step)
        updateStepVisibility()
    }

    private fun updateStepVisibility() {
        val current = wizardModel.currentStep
        val stepName = when (current) {
            SetupWizardModel.Step.STEP_DETECT -> getString(R.string.wizard_step_1_name)
            SetupWizardModel.Step.STEP_DISTRO -> getString(R.string.wizard_step_2_name)
            SetupWizardModel.Step.STEP_CONFIG -> getString(R.string.wizard_step_3_name)
        }
        txtStepBadge.text = getString(R.string.wizard_step_indicator, wizardModel.stepIndex, wizardModel.totalSteps, stepName)

        step1Container.visibility = if (current == SetupWizardModel.Step.STEP_DETECT) View.VISIBLE else View.GONE
        step2Container.visibility = if (current == SetupWizardModel.Step.STEP_DISTRO) View.VISIBLE else View.GONE
        step3Container.visibility = if (current == SetupWizardModel.Step.STEP_CONFIG) View.VISIBLE else View.GONE

        if (current == SetupWizardModel.Step.STEP_DISTRO) {
            selectDistro(wizardModel.selectedDistro)
        } else if (current == SetupWizardModel.Step.STEP_CONFIG) {
            bindStep3ConfigFacts()
        }
    }

    private fun startDownloadAndInitialize() {
        val manifest = wizardModel.getSelectedManifest()
        val availableBytes = StatFs(filesDir.path).availableBytes
        val requiredBytes = DistroInstanceCreator.DEFAULT_ROOT_VOLUME_BYTES + manifest.sizeBytes() + 128L * 1024L * 1024L
        if (availableBytes < requiredBytes) {
            Toast.makeText(this, R.string.create_storage_low, Toast.LENGTH_LONG).show()
            return
        }

        wizardModel.setDownloading(true)
        btnStep3Prev.visibility = View.GONE
        btnWizardInit.visibility = View.GONE
        layoutDownloadProgress.visibility = View.VISIBLE
        progressDownloadBar.visibility = View.VISIBLE
        progressDownloadBar.isIndeterminate = true
        txtDownloadStatus.text = getString(R.string.wizard_download_status_fetching, wizardModel.selectedDistro.displayName)
        txtDownloadStatus.setTextColor(getColor(R.color.status_provisioning))
        txtDownloadSpeedEta.text = "正在连接就近镜像节点…"
        txtDownloadMirror.text = "镜像节点：--"

        val targetDir = filesDir.toPath().resolve("distro-inputs")
        val targetPath = targetDir.resolve(manifest.rootfsFileName())

        val mirrors = DistroMirrorCatalog.mirrorsFor(manifest)

        backgroundExecutor.execute {
            try {
                val engine = ResilientDownloadEngine()
                engine.download(mirrors, targetPath, manifest.sha256(), object : DownloadProgressCallback {
                    override fun onProgress(
                        downloadedBytes: Long,
                        totalBytes: Long,
                        speedMBps: Double,
                        etaSeconds: Long,
                        currentMirror: String
                    ) {
                        runOnUiThread {
                            if (totalBytes > 0) {
                                val percent = ((downloadedBytes * 100) / totalBytes).toInt()
                                progressDownloadBar.isIndeterminate = false
                                progressDownloadBar.progress = percent
                            } else {
                                progressDownloadBar.isIndeterminate = true
                            }
                            val downloadedStr = String.format("%.1f MB", downloadedBytes.toDouble() / (1024.0 * 1024.0))
                            val totalStr = if (totalBytes > 0) String.format("%.1f MB", totalBytes.toDouble() / (1024.0 * 1024.0)) else "--"
                            val etaStr = if (etaSeconds >= 0) "${etaSeconds}s" else "--"
                            txtDownloadSpeedEta.text = getString(
                                R.string.wizard_download_speed_info,
                                speedMBps,
                                downloadedStr,
                                totalStr,
                                etaStr
                            )
                            val hostName = try { URI(currentMirror).host ?: currentMirror } catch (_: Exception) { currentMirror }
                            txtDownloadMirror.text = getString(R.string.wizard_download_mirror_info, hostName)
                        }
                    }

                    override fun onRetry(currentMirror: String, attempt: Int, backoffMillis: Long, error: Throwable) {
                        runOnUiThread {
                            val hostName = try { URI(currentMirror).host ?: currentMirror } catch (_: Exception) { currentMirror }
                            txtDownloadSpeedEta.text = getString(R.string.wizard_download_retry_info, hostName, attempt)
                        }
                    }

                    override fun onMirrorFailover(failedMirror: String, nextMirror: String?, error: Throwable) {
                        runOnUiThread {
                            val nextHost = nextMirror?.let { try { URI(it).host ?: it } catch (_: Exception) { it } } ?: "无"
                            txtDownloadSpeedEta.text = getString(R.string.wizard_download_failover_info, nextHost)
                        }
                    }
                })

                // Provisioning root volume and instance metadata
                runOnUiThread {
                    txtDownloadStatus.setText(R.string.wizard_provisioning_info)
                    progressDownloadBar.isIndeterminate = true
                }

                val instanceStore = InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"))
                val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                val creator = DistroInstanceCreator(manifest, controller, filesDir.toPath())
                Files.newInputStream(targetPath).use { input ->
                    creator.create(input, manifest.sizeBytes(), wizardModel.hardwareConfig)
                }

                // Mark onboarding completed and transition to MainActivity
                settingsManager.isOnboardingCompleted = true
                wizardModel.markCompleted()

                runOnUiThread {
                    Toast.makeText(this, R.string.wizard_init_success, Toast.LENGTH_LONG).show()
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                android.util.Log.e("Orchestella", "Wizard initialization failed", e)
                runOnUiThread {
                    wizardModel.setDownloading(false)
                    btnStep3Prev.visibility = View.VISIBLE
                    btnWizardInit.visibility = View.VISIBLE
                    btnWizardInit.text = "重试初始化 ${wizardModel.selectedDistro.displayName}"
                    progressDownloadBar.visibility = View.GONE
                    txtDownloadStatus.text = "初始化失败：${e.message ?: e.javaClass.simpleName}"
                    txtDownloadStatus.setTextColor(getColor(R.color.status_crashed))
                    txtDownloadSpeedEta.text = "请检查网络连接后点击重试"
                    Toast.makeText(
                        this,
                        getString(R.string.wizard_init_failed, e.message ?: e.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // --- AVF Authorization Assistance Dialogs ---
    private fun showAvfAuthorizationWizard() {
        val model = authWizardModel ?: return
        authWizardModel = model.start()
        showShizukuStep()
    }

    private fun showShizukuStep() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wizard_title)
            .setMessage(R.string.wizard_shizuku_steps)
            .setPositiveButton(R.string.wizard_authorization_done) { _, _ -> recheckAuthorization() }
            .setNeutralButton(R.string.wizard_use_adb) { _, _ ->
                authWizardModel = authWizardModel?.chooseAdbFallback()
                showAdbStep()
            }
            .setNegativeButton(R.string.wizard_later) { _, _ ->
                authWizardModel = authWizardModel?.decline()
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
                authWizardModel = authWizardModel?.backToShizuku()
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
        val granted = facts.manageVirtualMachinePermission()
        authWizardModel = authWizardModel?.confirmAuthorized(granted)
        if (granted) {
            avfReadiness = AvfCapabilityEvaluator.evaluate(facts)
            bindStep1HardwareFacts()
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
