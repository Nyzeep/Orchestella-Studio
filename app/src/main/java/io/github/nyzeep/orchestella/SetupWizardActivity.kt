package io.github.nyzeep.orchestella

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.distro.OfficialAlpineCatalog
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.network.download.DownloadProgressCallback
import io.github.nyzeep.orchestella.runtime.network.download.ResilientDownloadEngine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.Executors

/**
 * Industrial-grade 4-step Instance Creation Wizard for Orchestella Studio (Ticket 01).
 *
 * <p>Flow:
 * <ul>
 *   <li>Step 1 (STEP_STORAGE): Virtual disk capacity presets (4G, 8G, 16G, 32G) & custom GB input,
 *       StatFs host storage reserve preflight check (>= 2GB buffer), host shared directory mount option.</li>
 *   <li>Step 2 (STEP_NETWORK_MIRROR): Network connectivity diagnosis board & Mirror software source dropdown
 *       (Official Upstream, Tsinghua TUNA, USTC, Aliyun).</li>
 *   <li>Step 3 (STEP_HARDWARE): vCPU and RAM manual bounds allocation with dynamic safety zone badge.</li>
 *   <li>Step 4 (STEP_STARTUP_TASKS): Optional first-boot automatic software update script injection
 *       and final resilient download + provisioning.</li>
 * </ul>
 */
class SetupWizardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TARGET_DISTRO = "extra_target_distro"
    }

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private lateinit var settingsManager: SettingsManager
    private lateinit var hostProfile: HostHardwareProfile
    private lateinit var wizardModel: InstanceCreationWizardModel

    // Header views
    private lateinit var txtWizardTitle: TextView
    private lateinit var txtStepBadge: TextView

    // Step containers
    private lateinit var step1Container: LinearLayout
    private lateinit var step2Container: LinearLayout
    private lateinit var step3Container: LinearLayout
    private lateinit var step4Container: LinearLayout

    // Step 1 Views (Storage)
    private lateinit var btnDistroAlpine: MaterialButton
    private lateinit var btnDistroDebian: MaterialButton
    private lateinit var txtHwStorage: TextView
    private lateinit var btnDisk4g: MaterialButton
    private lateinit var btnDisk8g: MaterialButton
    private lateinit var btnDisk16g: MaterialButton
    private lateinit var btnDisk32g: MaterialButton
    private lateinit var editCustomDiskGb: TextInputEditText
    private lateinit var txtStorageWarning: TextView
    private lateinit var chkMountSharedDir: MaterialCheckBox
    private lateinit var btnStep1Next: MaterialButton

    // Step 2 Views (Network & Mirror)
    private lateinit var txtNetMode: TextView
    private lateinit var txtNetIp: TextView
    private lateinit var txtNetDns: TextView
    private lateinit var btnTestNetwork: MaterialButton
    private lateinit var txtNetTestResult: TextView
    private lateinit var spinnerMirrorSource: Spinner
    private lateinit var txtMirrorPreview: TextView
    private lateinit var btnStep2Prev: MaterialButton
    private lateinit var btnStep2Next: MaterialButton

    // Step 3 Views (Hardware)
    private lateinit var txtCfgVcpu: TextView
    private lateinit var sliderVcpu: Slider
    private lateinit var txtCfgRam: TextView
    private lateinit var sliderRam: Slider
    private lateinit var badgeCfgZone: TextView
    private lateinit var btnStep3Prev: MaterialButton
    private lateinit var btnStep3Next: MaterialButton

    // Step 4 Views (Startup Tasks & Creation)
    private lateinit var chkAutoUpdate: MaterialCheckBox
    private lateinit var txtUpdateCommandDesc: TextView
    private lateinit var txtUpdateCommandPreview: TextView
    private lateinit var txtCreationSummary: TextView
    private lateinit var btnWizardInit: MaterialButton
    private lateinit var layoutDownloadProgress: LinearLayout
    private lateinit var txtDownloadStatus: TextView
    private lateinit var progressDownloadBar: LinearProgressIndicator
    private lateinit var txtDownloadSpeedEta: TextView
    private lateinit var txtDownloadMirror: TextView
    private lateinit var btnStep4Prev: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
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
        hostProfile = HostHardwareDetector.detect(this)

        val targetDistro = intent.getStringExtra(EXTRA_TARGET_DISTRO)?.lowercase() ?: "alpine"
        wizardModel = InstanceCreationWizardModel(
            distroId = targetDistro,
            distroVersion = if (targetDistro == "debian") "13" else "3.24.1",
            hardwareProfile = hostProfile
        )

        initViews()
        setupListeners()
        bindStep1Storage()
        bindStep2Network()
        bindStep3Hardware()
        bindStep4StartupTasks()
        updateStepVisibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            backgroundExecutor.shutdown()
        }
    }

    private fun initViews() {
        txtWizardTitle = findViewById(R.id.wizard_title)
        txtStepBadge = findViewById(R.id.wizard_step_badge)

        step1Container = findViewById(R.id.step1_container)
        step2Container = findViewById(R.id.step2_container)
        step3Container = findViewById(R.id.step3_container)
        step4Container = findViewById(R.id.step4_container)

        // Step 1
        btnDistroAlpine = findViewById(R.id.btn_distro_alpine)
        btnDistroDebian = findViewById(R.id.btn_distro_debian)
        txtHwStorage = findViewById(R.id.txt_hw_storage)
        btnDisk4g = findViewById(R.id.btn_disk_4g)
        btnDisk8g = findViewById(R.id.btn_disk_8g)
        btnDisk16g = findViewById(R.id.btn_disk_16g)
        btnDisk32g = findViewById(R.id.btn_disk_32g)
        editCustomDiskGb = findViewById(R.id.edit_custom_disk_gb)
        txtStorageWarning = findViewById(R.id.txt_storage_warning)
        chkMountSharedDir = findViewById(R.id.chk_mount_shared_dir)
        btnStep1Next = findViewById(R.id.btn_step1_next)

        // Step 2
        txtNetMode = findViewById(R.id.txt_net_mode)
        txtNetIp = findViewById(R.id.txt_net_ip)
        txtNetDns = findViewById(R.id.txt_net_dns)
        btnTestNetwork = findViewById(R.id.btn_test_network)
        txtNetTestResult = findViewById(R.id.txt_net_test_result)
        spinnerMirrorSource = findViewById(R.id.spinner_mirror_source)
        txtMirrorPreview = findViewById(R.id.txt_mirror_preview)
        btnStep2Prev = findViewById(R.id.btn_step2_prev)
        btnStep2Next = findViewById(R.id.btn_step2_next)

        // Step 3
        txtCfgVcpu = findViewById(R.id.txt_cfg_vcpu)
        sliderVcpu = findViewById(R.id.slider_vcpu)
        txtCfgRam = findViewById(R.id.txt_cfg_ram)
        sliderRam = findViewById(R.id.slider_ram)
        badgeCfgZone = findViewById(R.id.badge_cfg_zone)
        btnStep3Prev = findViewById(R.id.btn_step3_prev)
        btnStep3Next = findViewById(R.id.btn_step3_next)

        // Step 4
        chkAutoUpdate = findViewById(R.id.chk_auto_update)
        txtUpdateCommandDesc = findViewById(R.id.txt_update_command_desc)
        txtUpdateCommandPreview = findViewById(R.id.txt_update_command_preview)
        txtCreationSummary = findViewById(R.id.txt_creation_summary)
        btnWizardInit = findViewById(R.id.btn_wizard_init)
        layoutDownloadProgress = findViewById(R.id.layout_download_progress)
        txtDownloadStatus = findViewById(R.id.txt_download_status)
        progressDownloadBar = findViewById(R.id.progress_download_bar)
        txtDownloadSpeedEta = findViewById(R.id.txt_download_speed_eta)
        txtDownloadMirror = findViewById(R.id.txt_download_mirror)
        btnStep4Prev = findViewById(R.id.btn_step4_prev)
    }

    private fun setupListeners() {
        // Step 1 Distro Switch
        btnDistroAlpine.setOnClickListener { switchDistro("alpine") }
        btnDistroDebian.setOnClickListener { switchDistro("debian") }

        // Step 1 Presets
        btnDisk4g.setOnClickListener { selectDiskPreset(4) }
        btnDisk8g.setOnClickListener { selectDiskPreset(8) }
        btnDisk16g.setOnClickListener { selectDiskPreset(16) }
        btnDisk32g.setOnClickListener { selectDiskPreset(32) }

        // Step 1 Custom Input
        editCustomDiskGb.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val gb = s?.toString()?.toIntOrNull()
                if (gb != null && gb > 0) {
                    wizardModel.setCustomDiskSizeGb(gb)
                    highlightPresetButton(gb)
                    validateAndRefreshStorage()
                } else if (s.isNullOrEmpty()) {
                    btnStep1Next.isEnabled = false
                }
            }
        })

        chkMountSharedDir.setOnCheckedChangeListener { _, isChecked ->
            wizardModel.setMountHostSharedDirectory(isChecked)
        }

        btnStep1Next.setOnClickListener {
            if (wizardModel.canGoNext()) {
                wizardModel.nextStep()
                updateStepVisibility()
            }
        }

        // Step 2 Listeners
        btnTestNetwork.setOnClickListener { testNetworkConnectivity() }
        btnStep2Prev.setOnClickListener {
            if (wizardModel.canGoBack()) {
                wizardModel.previousStep()
                updateStepVisibility()
            }
        }
        btnStep2Next.setOnClickListener {
            if (wizardModel.canGoNext()) {
                wizardModel.nextStep()
                updateStepVisibility()
            }
        }

        // Step 3 Listeners
        sliderVcpu.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                wizardModel.updateCpuCores(value.toInt())
                updateHardwareLabels()
            }
        }
        sliderRam.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                wizardModel.updateRamMegabytes(value.toInt())
                updateHardwareLabels()
            }
        }
        btnStep3Prev.setOnClickListener {
            if (wizardModel.canGoBack()) {
                wizardModel.previousStep()
                updateStepVisibility()
            }
        }
        btnStep3Next.setOnClickListener {
            if (wizardModel.canGoNext()) {
                wizardModel.nextStep()
                updateStepVisibility()
            }
        }

        // Step 4 Listeners
        chkAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            wizardModel.setAutoUpdateOnFirstBoot(isChecked)
            updateStartupTaskPreview()
        }
        btnStep4Prev.setOnClickListener {
            if (wizardModel.canGoBack()) {
                wizardModel.previousStep()
                updateStepVisibility()
            }
        }
        btnWizardInit.setOnClickListener { startDownloadAndInitialize() }
    }

    private fun switchDistro(distroId: String) {
        val currentDistro = wizardModel.distroId
        if (currentDistro == distroId) return

        wizardModel = InstanceCreationWizardModel(
            distroId = distroId,
            distroVersion = if (distroId == "debian") "13" else "3.24.1",
            hardwareProfile = hostProfile,
            initialStep = wizardModel.currentStep
        )
        refreshDistroPillStyles()
        updateMirrorPreview()
        updateStartupTaskPreview()
        updateCreationSummary()
    }

    private fun refreshDistroPillStyles() {
        val isAlpine = wizardModel.distroId == "alpine"
        if (isAlpine) {
            btnDistroAlpine.setIconResource(android.R.drawable.checkbox_on_background)
            btnDistroDebian.icon = null
        } else {
            btnDistroAlpine.icon = null
            btnDistroDebian.setIconResource(android.R.drawable.checkbox_on_background)
        }
    }

    private fun selectDiskPreset(gb: Int) {
        wizardModel.selectDiskPresetGb(gb)
        editCustomDiskGb.setText(gb.toString())
        highlightPresetButton(gb)
        validateAndRefreshStorage()
    }

    private fun highlightPresetButton(selectedGb: Int) {
        val buttons = listOf(
            btnDisk4g to 4,
            btnDisk8g to 8,
            btnDisk16g to 16,
            btnDisk32g to 32
        )
        for ((btn, gb) in buttons) {
            if (gb == selectedGb) {
                btn.strokeWidth = dpToPx(2)
            } else {
                btn.strokeWidth = dpToPx(1)
            }
        }
    }

    private fun bindStep1Storage() {
        refreshDistroPillStyles()
        highlightPresetButton(wizardModel.diskSizeGb)
        validateAndRefreshStorage()
    }

    private fun validateAndRefreshStorage() {
        val statFs = StatFs(filesDir.path)
        val availableBytes = statFs.availableBytes
        val availableGb = availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        txtHwStorage.text = getString(R.string.wizard_storage_available, String.format("%.1f GB", availableGb))

        val result = wizardModel.validateStorage(availableBytes, wizardModel.requestedDiskBytes)
        when (result) {
            is InstanceCreationWizardModel.StorageValidationResult.Valid -> {
                txtStorageWarning.visibility = View.GONE
                btnStep1Next.isEnabled = true
            }
            is InstanceCreationWizardModel.StorageValidationResult.InsufficientSpace -> {
                val reqGb = result.requiredBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                txtStorageWarning.text = getString(R.string.wizard_storage_insufficient_warn, availableGb, reqGb)
                txtStorageWarning.visibility = View.VISIBLE
                btnStep1Next.isEnabled = false
            }
            is InstanceCreationWizardModel.StorageValidationResult.InvalidRequestedSize -> {
                txtStorageWarning.text = result.reason
                txtStorageWarning.visibility = View.VISIBLE
                btnStep1Next.isEnabled = false
            }
        }
    }

    private fun bindStep2Network() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isWifi: Boolean
        val isCellular: Boolean
        val isConnected: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            val info = cm?.activeNetworkInfo
            isWifi = info?.type == ConnectivityManager.TYPE_WIFI
            isCellular = info?.type == ConnectivityManager.TYPE_MOBILE
            isConnected = info?.isConnected == true
        }

        val mode = when {
            isWifi -> "Wi-Fi (无线局域网)"
            isCellular -> "移动蜂窝网络 (Mobile LTE/5G)"
            isConnected -> "以太网 / 专线联网"
            else -> "离线 (Offline)"
        }
        txtNetMode.text = getString(R.string.wizard_net_mode, mode)
        txtNetIp.text = getString(R.string.wizard_net_ip, getLocalIpAddress() ?: "127.0.0.1")
        txtNetDns.text = getString(R.string.wizard_net_dns, "10.0.2.3, 8.8.8.8")

        // Setup Mirror Spinner
        val sources = DistroMirrorProvisioner.MirrorSource.values()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources.map { it.displayName() }
        )
        spinnerMirrorSource.adapter = adapter
        spinnerMirrorSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                wizardModel.selectMirror(sources[position])
                updateMirrorPreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        updateMirrorPreview()
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun testNetworkConnectivity() {
        txtNetTestResult.text = getString(R.string.wizard_net_testing)
        txtNetTestResult.setTextColor(getColor(R.color.status_provisioning))
        backgroundExecutor.execute {
            var ok = false
            try {
                val url = URL("https://mirrors.tuna.tsinghua.edu.cn")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "HEAD"
                val code = conn.responseCode
                ok = code in 200..399
            } catch (_: Exception) {
                ok = false
            }
            runOnUiThread {
                if (ok) {
                    txtNetTestResult.text = getString(R.string.wizard_net_test_ok)
                    txtNetTestResult.setTextColor(getColor(R.color.zone_green))
                } else {
                    txtNetTestResult.text = getString(R.string.wizard_net_test_failed)
                    txtNetTestResult.setTextColor(getColor(R.color.zone_red))
                }
            }
        }
    }

    private fun updateMirrorPreview() {
        val path = DistroMirrorProvisioner.getTargetConfigPath(wizardModel.distroId)
        val urls = wizardModel.resolveMirrorUrls()
        txtMirrorPreview.text = "配置目标：$path\n解析节点：\n" + urls.joinToString("\n")
    }

    private fun bindStep3Hardware() {
        val profile = hostProfile
        val maxCores = profile.totalCores().toFloat().coerceAtLeast(1f)
        sliderVcpu.valueFrom = 1f
        sliderVcpu.valueTo = maxCores
        val initialCores = wizardModel.hardwareConfig.cpuCores().toFloat().coerceIn(1f, maxCores)
        sliderVcpu.value = initialCores

        val maxRam = (profile.totalRamMb() - 2048).toFloat().coerceAtLeast(1024f)
        sliderRam.valueFrom = 512f
        sliderRam.valueTo = maxRam
        sliderRam.stepSize = 256f
        val initialRam = wizardModel.hardwareConfig.ramMegabytes().toFloat().coerceIn(512f, maxRam)
        sliderRam.value = initialRam

        updateHardwareLabels()
    }

    private fun updateHardwareLabels() {
        txtCfgVcpu.text = getString(R.string.wizard_config_vcpu_info, wizardModel.hardwareConfig.cpuCores())
        txtCfgRam.text = getString(R.string.wizard_config_ram_info, wizardModel.hardwareConfig.ramMegabytes())

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
    }

    private fun bindStep4StartupTasks() {
        chkAutoUpdate.isChecked = wizardModel.autoUpdateOnFirstBoot
        updateStartupTaskPreview()
        updateCreationSummary()
    }

    private fun updateStartupTaskPreview() {
        val cmd = wizardModel.resolveUpdateCommand()
        txtUpdateCommandPreview.text = cmd
        txtUpdateCommandDesc.text = getString(R.string.wizard_startup_auto_update_desc, cmd)
    }

    private fun updateCreationSummary() {
        val distroName = if (wizardModel.distroId == "debian") "Debian GNU/Linux 13" else "Alpine Linux 3.24"
        val diskGb = wizardModel.diskSizeGb
        val vcpu = wizardModel.hardwareConfig.cpuCores()
        val ram = wizardModel.hardwareConfig.ramMegabytes()
        val mirror = wizardModel.selectedMirror.displayName()
        val updateText = if (wizardModel.autoUpdateOnFirstBoot) "已启用 (后台静默自动更新)" else "已禁用 (保持镜像原状)"
        val sharedText = if (wizardModel.mountHostSharedDirectory) "已挂载 (/mnt/shared)" else "未挂载"

        txtCreationSummary.text = """
            • 目标发行版：$distroName
            • 虚拟磁盘容量：$diskGb GB 稀疏磁盘
            • 宿主共享目录：$sharedText
            • 硬件资源规格：$vcpu vCPU · $ram MB RAM
            • 镜像软件源：$mirror
            • 首次引导更新：$updateText
        """.trimIndent()

        btnWizardInit.text = "🚀 立即创建 $distroName"
    }

    private fun updateStepVisibility() {
        val current = wizardModel.currentStep
        val stepName = when (current) {
            InstanceCreationWizardModel.CreationStep.STEP_STORAGE -> getString(R.string.wizard_step_storage_name)
            InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR -> getString(R.string.wizard_step_network_name)
            InstanceCreationWizardModel.CreationStep.STEP_HARDWARE -> getString(R.string.wizard_step_hardware_name)
            InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS -> getString(R.string.wizard_step_startup_name)
        }
        txtStepBadge.text = getString(R.string.wizard_step_indicator, wizardModel.stepIndex, wizardModel.totalSteps, stepName)

        step1Container.visibility = if (current == InstanceCreationWizardModel.CreationStep.STEP_STORAGE) View.VISIBLE else View.GONE
        step2Container.visibility = if (current == InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR) View.VISIBLE else View.GONE
        step3Container.visibility = if (current == InstanceCreationWizardModel.CreationStep.STEP_HARDWARE) View.VISIBLE else View.GONE
        step4Container.visibility = if (current == InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS) View.VISIBLE else View.GONE

        if (current == InstanceCreationWizardModel.CreationStep.STEP_STORAGE) {
            validateAndRefreshStorage()
        } else if (current == InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS) {
            updateCreationSummary()
        }
    }

    private fun getManifest(): DistroManifest {
        return if (wizardModel.distroId == "debian") {
            OfficialDebianCatalog.debian()
        } else {
            OfficialAlpineCatalog.alpine()
        }
    }

    private fun startDownloadAndInitialize() {
        val manifest = getManifest()
        val availableBytes = StatFs(filesDir.path).availableBytes
        val requiredBytes = wizardModel.requestedDiskBytes + manifest.sizeBytes() + 128L * 1024L * 1024L
        if (availableBytes < requiredBytes) {
            Toast.makeText(this, R.string.create_storage_low, Toast.LENGTH_LONG).show()
            return
        }

        wizardModel.setCreating(true)
        btnStep4Prev.visibility = View.GONE
        btnWizardInit.visibility = View.GONE
        layoutDownloadProgress.visibility = View.VISIBLE
        progressDownloadBar.visibility = View.VISIBLE
        progressDownloadBar.isIndeterminate = true
        txtDownloadStatus.text = getString(R.string.wizard_download_status_fetching, manifest.id().replaceFirstChar { it.uppercase() })
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

                val instancesDir = filesDir.toPath().resolve("instances")
                val instanceStore = InstanceLifecycle.FileStore(instancesDir)
                val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                val creator = DistroInstanceCreator(
                    manifest,
                    controller,
                    filesDir.toPath(),
                    wizardModel.requestedDiskBytes
                )

                val createdInstance = Files.newInputStream(targetPath).use { input ->
                    creator.create(input, manifest.sizeBytes(), wizardModel.hardwareConfig)
                }

                // Save injected mirror configuration and firstboot script into instance store
                val mirrorConfigContent = DistroMirrorProvisioner.generateMirrorConfig(
                    wizardModel.distroId,
                    wizardModel.distroVersion,
                    wizardModel.selectedMirror
                )
                val mirrorConfigFile = instancesDir.resolve("${createdInstance.id()}.mirror")
                Files.write(mirrorConfigFile, mirrorConfigContent.toByteArray(StandardCharsets.UTF_8))

                if (wizardModel.autoUpdateOnFirstBoot) {
                    val scriptContent = wizardModel.generateFirstBootScript(wizardModel.distroId)
                    val firstbootFile = instancesDir.resolve("${createdInstance.id()}.firstboot")
                    Files.write(firstbootFile, scriptContent.toByteArray(StandardCharsets.UTF_8))
                }

                // Mark onboarding completed
                settingsManager.isOnboardingCompleted = true

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
                    wizardModel.setCreating(false)
                    btnStep4Prev.visibility = View.VISIBLE
                    btnWizardInit.visibility = View.VISIBLE
                    btnWizardInit.text = "重试创建与部署"
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
