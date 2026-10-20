package io.github.nyzeep.orchestella

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import io.github.nyzeep.orchestella.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.engineavf.AvfLaunchAdmission
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
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
        private const val STATE_DRAFT = "creation_draft_v1"
    }

    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private lateinit var settingsManager: SettingsManager
    private lateinit var hostProfile: HostHardwareProfile
    private lateinit var wizardModel: InstanceCreationWizardModel
    private var draftNotice: String? = null
    private var applyingDraftViews = false

    // Distro descriptor for custom spinner
    data class DistroItem(
        val id: String,
        val displayName: String,
        val tag: String,
        val desc: String,
        val iconRes: Int
    )

    private val officialDistros = listOf(
        DistroItem("ubuntu", "Ubuntu LTS", "🐧 通用开发 · 云原生首选 · 24.04 LTS", "工业标准、生态最广，预设多层 APT 源与 Docker 加速", R.drawable.ic_distro_ubuntu),
        DistroItem("debian", "Debian Stable", "🌀 稳如磐石 · 经典基石 · Debian 13", "全开源坚实底座，极高稳定性与完整 GNU 工具链", R.drawable.ic_distro_debian),
        DistroItem("fedora", "Fedora", "🎩 前沿技术 · RPM 生态 · Fedora 43", "集成最新内核与 DNF 现代工具链，Red Hat 社区前沿", R.drawable.ic_distro_fedora),
        DistroItem("arch", "Arch Linux", "🏹 极客首选 · 滚动更新 · pacman", "轻量 DIY，滚动更新最新软件包与强大的社区支持", R.drawable.ic_distro_arch),
        DistroItem("opensuse", "openSUSE Leap", "🦎 严谨规范 · 版本化发布 · zypper", "版本化稳定发布，zypper 依赖解析与企业级基线", R.drawable.ic_distro_opensuse),
        DistroItem("almalinux", "AlmaLinux", "💼 企业级 · RHEL 1:1 二进制兼容 · 9", "稳定企业环境模拟，长期支持保障与 DNF 生态", R.drawable.ic_distro_almalinux),
        DistroItem("rocky", "Rocky Linux", "🏔️ 生产级稳定 · 企业计算 · 9.6", "CentOS 官方替代，生产级高可靠性与 RHEL 兼容", R.drawable.ic_distro_rocky)
    )

    // Header views
    private lateinit var txtWizardTitle: TextView
    private lateinit var txtStepBadge: TextView
    private lateinit var btnWizardSkip: MaterialButton

    // Step containers
    private lateinit var step1Container: LinearLayout
    private lateinit var step2Container: LinearLayout
    private lateinit var step3Container: LinearLayout
    private lateinit var step4Container: LinearLayout

    // Step 1 Views (AVF Detection Card)
    private lateinit var txtWizardAvfBadge: TextView
    private lateinit var txtWizardAvfDetails: TextView
    private lateinit var layoutWizardAvfFallback: LinearLayout

    // Step 1 Views (Distro Spinner & Preview)
    private lateinit var spinnerDistroSelect: Spinner
    private lateinit var imgDistroIconPreview: ImageView
    private lateinit var txtDistroNamePreview: TextView
    private lateinit var txtDistroTagPreview: TextView
    private lateinit var txtDistroDescPreview: TextView

    // Step 1 Views (Storage)
    private lateinit var txtHwStorage: TextView
    private lateinit var btnDisk4g: MaterialButton
    private lateinit var btnDisk8g: MaterialButton
    private lateinit var btnDisk16g: MaterialButton
    private lateinit var btnDisk32g: MaterialButton
    private lateinit var editCustomDiskGb: TextInputEditText
    private lateinit var txtStorageWarning: TextView
    private lateinit var chkMountSharedDir: MaterialCheckBox
    private lateinit var btnPickSharedDir: MaterialButton
    private lateinit var txtSharedFolderPath: TextView
    private lateinit var btnStep1Next: MaterialButton

    // Step 2 Views (Network & Mirror)
    private lateinit var txtNetMode: TextView
    private lateinit var txtNetIp: TextView
    private lateinit var txtNetGateway: TextView
    private lateinit var txtNetDns: TextView
    private lateinit var btnTestNetwork: MaterialButton
    private lateinit var txtNetTestResult: TextView
    private lateinit var spinnerMirrorSource: Spinner
    private lateinit var btnWizardSpeedTest: MaterialButton
    private lateinit var txtWizardSpeedTestResults: TextView
    private lateinit var txtMirrorPreview: TextView
    private lateinit var btnStep2Prev: MaterialButton
    private lateinit var btnStep2Next: MaterialButton

    // Step 3 Views (Hardware)
    private lateinit var txtCfgVcpu: TextView
    private lateinit var sliderVcpu: Slider
    private lateinit var btnVcpuMinus: MaterialButton
    private lateinit var btnVcpuPlus: MaterialButton
    private lateinit var txtCfgRam: TextView
    private lateinit var sliderRam: Slider
    private lateinit var badgeCfgZone: TextView
    private lateinit var btnAvfAuthWizard: MaterialButton
    private lateinit var btnStep3Prev: MaterialButton
    private lateinit var btnStep3Next: MaterialButton

    // Step 4 Views (Startup Tasks & Creation)
    private lateinit var chkDesktop: MaterialCheckBox
    private lateinit var chkPodman: MaterialCheckBox
    private lateinit var chkEditor: MaterialCheckBox
    private lateinit var editGuestUser: TextInputEditText
    private lateinit var editGuestHostname: TextInputEditText
    private lateinit var chkGuestSudo: MaterialCheckBox
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

    private var authWizardModel: AvfAuthorizationWizardModel? = null
    private var avfReadiness: AvfCapabilityEvaluator.AvfReadiness? = null

    private val pickSharedDirLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val displayPath = uri.lastPathSegment ?: uri.path ?: uri.toString()
            wizardModel.setMountHostSharedDirectory(true)
            wizardModel.setHostSharedDirectoryPath(displayPath)
            chkMountSharedDir.isChecked = true
            txtSharedFolderPath.text = getString(R.string.wizard_shared_folder_selected, displayPath)
            txtSharedFolderPath.visibility = View.VISIBLE
            btnPickSharedDir.visibility = View.VISIBLE
        } else {
            if (wizardModel.hostSharedDirectoryPath == null) {
                chkMountSharedDir.isChecked = false
                txtSharedFolderPath.visibility = View.GONE
                btnPickSharedDir.visibility = View.GONE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)

        // Edge-to-Edge transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val rootContainer = findViewById<View>(R.id.wizard_root_container)
        val density = resources.displayMetrics.density
        val baseHorizontalPad = (16 * density).toInt()
        val baseBottomPad = (28 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(
                baseHorizontalPad + statusBarInsets.left,
                statusBarInsets.top + (12 * density).toInt(),
                baseHorizontalPad + statusBarInsets.right,
                navBarInsets.bottom + baseBottomPad
            )
            insets
        }

        settingsManager = SettingsManager(this)
        hostProfile = HostHardwareDetector.detect(this)

        val targetDistro = intent.getStringExtra(EXTRA_TARGET_DISTRO)?.lowercase() ?: "ubuntu"
        val initialManifest = DistroCatalogRegistry.getInstance().get(targetDistro)
            ?: DistroCatalogRegistry.getInstance().get("ubuntu")
        wizardModel = InstanceCreationWizardModel(
            distroId = initialManifest?.id() ?: "ubuntu",
            distroVersion = initialManifest?.version() ?: "24.04",
            hardwareProfile = hostProfile
        )
        restoreDraft(savedInstanceState)

        initViews()
        setupListeners()
        bindWizardAvfCard()
        setupDistroSpinner()
        bindStep1Storage()
        bindStep2Network()
        bindStep3Hardware()
        bindStep4StartupTasks()
        bindDraftViews()
        ThemeApplier.bind(this)
    }

    private fun restoreDraft(state: Bundle?) {
        if (state == null || !state.containsKey(STATE_DRAFT)) return
        try {
            val saved = requireNotNull(state.getBundle(STATE_DRAFT))
            val draft = saved.keySet().associateWith { key ->
                requireNotNull(saved.getString(key))
            }
            val versions = officialDistros.mapNotNull { item ->
                DistroCatalogRegistry.getInstance().get(item.id)?.let { item.id to it.version() }
            }.toMap()
            wizardModel = InstanceCreationWizardModel.restoreDraft(draft, hostProfile, versions) { id ->
                DistroResourceRequirements.minimumBootRamMb(
                    requireNotNull(DistroCatalogRegistry.getInstance().get(id)).sizeBytes())
            }
        } catch (_: Exception) {
            // A stale field must not turn an in-flight operation into an automatic retry.
            val wasCreating = try { state.getBundle(STATE_DRAFT)?.getString("creating") == "true" }
                catch (_: Exception) { true }
            if (wasCreating) {
                wizardModel.goToStep(InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS)
                wizardModel.setCreating(true)
            }
            // Do not log the Bundle or raw editable values.
            draftNotice = "草稿无法恢复或设备条件已变化，请重新确认发行版和硬件配置。"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureIdentityDraft()
        val draft = Bundle()
        wizardModel.saveDraft().forEach { (key, value) -> draft.putString(key, value) }
        outState.putBundle(STATE_DRAFT, draft)
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // Let Android restore unrelated view state, then bind owned controls from the validated model.
        applyingDraftViews = true
        try { super.onRestoreInstanceState(savedInstanceState) }
        finally { applyingDraftViews = false }
        bindDraftViews()
    }

    private fun captureIdentityDraft() {
        if (wizardModel.isCreating || !::editGuestUser.isInitialized || !::editGuestHostname.isInitialized) return
        wizardModel.updateGuestIdentityDraft(editGuestUser.text?.toString().orEmpty(),
            editGuestHostname.text?.toString().orEmpty(), chkGuestSudo.isChecked)
        wizardModel.setOptionalProfiles(chkDesktop.isChecked, chkPodman.isChecked, chkEditor.isChecked)
    }

    private fun bindDraftViews() {
        applyingDraftViews = true
        try {
            editCustomDiskGb.setText(wizardModel.diskSizeGb.toString())
            spinnerDistroSelect.setSelection(officialDistros.indexOfFirst { it.id == wizardModel.distroId }.coerceAtLeast(0))
            spinnerMirrorSource.setSelection(wizardModel.selectedMirror.ordinal)
            sliderVcpu.value = wizardModel.hardwareConfig.cpuCores().toFloat()
            sliderRam.value = wizardModel.hardwareConfig.ramMegabytes().toFloat()
            editGuestUser.setText(wizardModel.guestUsernameDraft)
            editGuestHostname.setText(wizardModel.guestHostnameDraft)
            chkGuestSudo.isChecked = wizardModel.guestSudoDraft
            chkDesktop.isChecked = wizardModel.desktopSelected
            chkPodman.isChecked = wizardModel.podmanSelected
            chkEditor.isChecked = wizardModel.editorSelected
            chkAutoUpdate.isChecked = wizardModel.autoUpdateOnFirstBoot
        } finally { applyingDraftViews = false }
        highlightPresetButton(wizardModel.diskSizeGb)
        officialDistros.firstOrNull { it.id == wizardModel.distroId }?.let(::updateDistroPreview)
        updateMirrorPreview()
        updateStartupTaskPreview()
        updateHardwareLabels()
        updateStepVisibility()
        updateCreationSummary()
        renderDraftState()
    }

    private fun renderDraftState() {
        draftNotice?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        draftNotice = null
        if (wizardModel.isCreating) {
            btnWizardInit.visibility = View.GONE
            btnStep4Prev.visibility = View.GONE
            layoutDownloadProgress.visibility = View.VISIBLE
            progressDownloadBar.visibility = View.GONE
            txtDownloadStatus.text = "已保留创建中状态，不会重复下载或创建。请返回实例列表确认任务结果。"
            txtDownloadSpeedEta.text = ""
            txtDownloadMirror.text = ""
        }
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
        btnWizardSkip = findViewById(R.id.btn_wizard_skip)
        btnWizardSkip.text = "取消创建"
        btnWizardSkip.contentDescription = "取消创建并返回"
        ThemeApplier.setRole(btnWizardSkip, ThemeApplier.Role.SECONDARY_ACTION)

        step1Container = findViewById(R.id.step1_container)
        step2Container = findViewById(R.id.step2_container)
        step3Container = findViewById(R.id.step3_container)
        step4Container = findViewById(R.id.step4_container)

        // Step 1 - AVF Detection Card
        txtWizardAvfBadge = findViewById(R.id.txt_wizard_avf_badge)
        txtWizardAvfDetails = findViewById(R.id.txt_wizard_avf_details)
        layoutWizardAvfFallback = findViewById(R.id.layout_wizard_avf_fallback)

        // Step 1 - Distro Selection & Preview
        spinnerDistroSelect = findViewById(R.id.spinner_distro_select)
        imgDistroIconPreview = findViewById(R.id.img_distro_icon_preview)
        txtDistroNamePreview = findViewById(R.id.txt_distro_name_preview)
        txtDistroTagPreview = findViewById(R.id.txt_distro_tag_preview)
        txtDistroDescPreview = findViewById(R.id.txt_distro_desc_preview)
        txtHwStorage = findViewById(R.id.txt_hw_storage)
        btnDisk4g = findViewById(R.id.btn_disk_4g)
        btnDisk8g = findViewById(R.id.btn_disk_8g)
        btnDisk16g = findViewById(R.id.btn_disk_16g)
        btnDisk32g = findViewById(R.id.btn_disk_32g)
        listOf(btnDisk4g, btnDisk8g, btnDisk16g, btnDisk32g).forEach {
            ThemeApplier.setRole(it, ThemeApplier.Role.SECONDARY_ACTION)
        }
        editCustomDiskGb = findViewById(R.id.edit_custom_disk_gb)
        txtStorageWarning = findViewById(R.id.txt_storage_warning)
        chkMountSharedDir = findViewById(R.id.chk_mount_shared_dir)
        btnPickSharedDir = findViewById(R.id.btn_pick_shared_dir)
        txtSharedFolderPath = findViewById(R.id.txt_shared_folder_path)
        chkMountSharedDir.isEnabled = false
        chkMountSharedDir.text = "共享目录通道尚未接通，可通过 SSH / SFTP 传输文件"
        btnPickSharedDir.visibility = View.GONE
        txtSharedFolderPath.visibility = View.GONE
        btnStep1Next = findViewById(R.id.btn_step1_next)

        // Step 2
        txtNetMode = findViewById(R.id.txt_net_mode)
        txtNetIp = findViewById(R.id.txt_net_ip)
        txtNetGateway = findViewById(R.id.txt_net_gateway)
        txtNetDns = findViewById(R.id.txt_net_dns)
        btnTestNetwork = findViewById(R.id.btn_test_network)
        txtNetTestResult = findViewById(R.id.txt_net_test_result)
        spinnerMirrorSource = findViewById(R.id.spinner_mirror_source)
        btnWizardSpeedTest = findViewById(R.id.btn_wizard_speed_test)
        txtWizardSpeedTestResults = findViewById(R.id.txt_wizard_speed_test_results)
        txtMirrorPreview = findViewById(R.id.txt_mirror_preview)
        btnStep2Prev = findViewById(R.id.btn_step2_prev)
        btnStep2Next = findViewById(R.id.btn_step2_next)

        // Step 3
        txtCfgVcpu = findViewById(R.id.txt_cfg_vcpu)
        sliderVcpu = findViewById(R.id.slider_vcpu)
        btnVcpuMinus = findViewById(R.id.btn_vcpu_minus)
        btnVcpuPlus = findViewById(R.id.btn_vcpu_plus)
        txtCfgRam = findViewById(R.id.txt_cfg_ram)
        sliderRam = findViewById(R.id.slider_ram)
        badgeCfgZone = findViewById(R.id.badge_cfg_zone)
        btnAvfAuthWizard = findViewById(R.id.btn_avf_auth_wizard)
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

        listOf(btnStep1Next, btnStep2Next, btnStep3Next, btnWizardInit).forEach {
            ThemeApplier.setRole(it, ThemeApplier.Role.PRIMARY_ACTION)
        }
        listOf(btnStep2Prev, btnStep3Prev, btnStep4Prev, btnPickSharedDir,
            btnTestNetwork, btnWizardSpeedTest, btnVcpuMinus, btnVcpuPlus, btnAvfAuthWizard).forEach {
            ThemeApplier.setRole(it, ThemeApplier.Role.SECONDARY_ACTION)
        }
    }

    private fun setupListeners() {
        btnWizardSkip.setOnClickListener { finish() }

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
                if (applyingDraftViews || wizardModel.isCreating) return
                val gb = s?.toString()?.toIntOrNull()
                if (gb != null && gb > 0) {
                    wizardModel.setCustomDiskSizeGb(gb)
                    highlightPresetButton(gb)
                    validateAndRefreshStorage()
                    updateCreationSummary()
                } else if (s.isNullOrEmpty()) {
                    btnStep1Next.isEnabled = false
                }
            }
        })

        chkMountSharedDir.setOnCheckedChangeListener { _, isChecked ->
            if (applyingDraftViews || wizardModel.isCreating) return@setOnCheckedChangeListener
            wizardModel.setMountHostSharedDirectory(isChecked)
            if (isChecked) {
                if (wizardModel.hostSharedDirectoryPath == null) {
                    pickSharedDirLauncher.launch(null)
                } else {
                    btnPickSharedDir.visibility = View.VISIBLE
                    txtSharedFolderPath.visibility = View.VISIBLE
                }
            } else {
                wizardModel.setHostSharedDirectoryPath(null)
                btnPickSharedDir.visibility = View.GONE
                txtSharedFolderPath.visibility = View.GONE
            }
        }
        btnPickSharedDir.setOnClickListener {
            pickSharedDirLauncher.launch(null)
        }

        btnStep1Next.setOnClickListener {
            if (!wizardModel.isCreating) {
                officialDistros.getOrNull(spinnerDistroSelect.selectedItemPosition)?.let { switchDistro(it.id) }
            }
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
        btnVcpuMinus.setOnClickListener {
            val newVal = (sliderVcpu.value - 1f).coerceAtLeast(sliderVcpu.valueFrom)
            sliderVcpu.value = newVal
            wizardModel.updateCpuCores(newVal.toInt())
            updateHardwareLabels()
        }
        btnVcpuPlus.setOnClickListener {
            val newVal = (sliderVcpu.value + 1f).coerceAtMost(sliderVcpu.valueTo)
            sliderVcpu.value = newVal
            wizardModel.updateCpuCores(newVal.toInt())
            updateHardwareLabels()
        }
        btnAvfAuthWizard.setOnClickListener {
            showAvfAuthorizationWizard()
        }
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
            val hardwareError = hardwareValidationMessage()
            if (hardwareError != null) {
                Toast.makeText(this, hardwareError, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (wizardModel.canGoNext()) {
                wizardModel.nextStep()
                updateStepVisibility()
            }
        }

        // Step 4 Listeners
        chkAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            if (applyingDraftViews || wizardModel.isCreating) return@setOnCheckedChangeListener
            wizardModel.setAutoUpdateOnFirstBoot(isChecked)
            updateStartupTaskPreview()
            updateCreationSummary()
        }
        btnStep4Prev.setOnClickListener {
            if (wizardModel.canGoBack()) {
                wizardModel.previousStep()
                updateStepVisibility()
            }
        }
        btnWizardInit.setOnClickListener { startDownloadAndInitialize() }
    }

    private fun bindWizardAvfCard(
        facts: AvfCapabilityEvaluator.AvfHostFacts = AvfHostFactsProbe.probe(this)
    ) {
        val readiness = AvfCapabilityEvaluator.evaluate(facts)
        avfReadiness = readiness
        authWizardModel = AvfAuthorizationWizardModel.fromAssessment(readiness)
        btnAvfAuthWizard.visibility =
            if (readiness.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION)
                View.VISIBLE else View.GONE
        val factsSummary = """
            AVF feature：${if (facts.virtualizationFrameworkFeature()) "已声明" else "未声明"}
            虚拟化属性：${facts.virtualizationProperty()?.takeIf { it.isNotBlank() } ?: "无法读取或未提供"}
            VM 模式：${AvfHostFactsProbe.modeSummary(facts.modeFacts())}
            平台访问：${AvfHostFactsProbe.platformSummary(facts.platformFacts())}
            MANAGE_VIRTUAL_MACHINE：${AvfHostFactsProbe.permissionLabel(facts.manageVirtualMachinePermissionState())}
            USE_CUSTOM_VIRTUAL_MACHINE：${AvfHostFactsProbe.permissionLabel(facts.useCustomVirtualMachinePermissionState())}
        """.trimIndent()
        val launchNotice = getString(R.string.avf_preflight_boundary)

        when (readiness.status()) {
            AvfCapabilityEvaluator.AvfStatus.READY -> {
                txtWizardAvfBadge.text = "硬件虚拟化权限预检满足"
                txtWizardAvfBadge.setTextColor(getColor(R.color.zone_green))
                txtWizardAvfDetails.text = "$factsSummary\n声明与权限条件满足。\n$launchNotice"
                layoutWizardAvfFallback.visibility = View.GONE
            }
            AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> {
                txtWizardAvfBadge.text = "AVF 尚未授权"
                txtWizardAvfBadge.setTextColor(getColor(R.color.zone_yellow))
                txtWizardAvfDetails.text = "$factsSummary\n${readiness.reasons().joinToString("；")}\n$launchNotice"
                layoutWizardAvfFallback.visibility = View.VISIBLE
            }
            AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED -> {
                txtWizardAvfBadge.text = "AVF 模式不支持"
                txtWizardAvfBadge.setTextColor(getColor(R.color.zone_red))
                txtWizardAvfDetails.text = "$factsSummary\n${readiness.reasons().joinToString("；")}\n授权不会改变 VM 模式；当前使用 QEMU。\n$launchNotice"
                layoutWizardAvfFallback.visibility = View.VISIBLE
            }
            AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN -> {
                txtWizardAvfBadge.text = "AVF 模式未验证"
                txtWizardAvfBadge.setTextColor(getColor(R.color.zone_yellow))
                txtWizardAvfDetails.text = "$factsSummary\n${readiness.reasons().joinToString("；")}\n授权不会改变 VM 模式；当前使用 QEMU。\n$launchNotice"
                layoutWizardAvfFallback.visibility = View.VISIBLE
            }
            AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED -> {
                txtWizardAvfBadge.text = "AVF 平台接口受限"
                txtWizardAvfBadge.setTextColor(getColor(R.color.zone_red))
                txtWizardAvfDetails.text = "$factsSummary\n${readiness.reasons().joinToString("；")}\n这是系统隐藏 API 限制，不是权限问题；当前使用 QEMU。\n$launchNotice"
                layoutWizardAvfFallback.visibility = View.VISIBLE
            }
            AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE -> {
                txtWizardAvfBadge.text = "AVF 平台接口缺失"
                txtWizardAvfBadge.setTextColor(getColor(R.color.zone_red))
                txtWizardAvfDetails.text = "$factsSummary\n${readiness.reasons().joinToString("；")}\n当前系统未提供该平台接口；当前使用 QEMU。\n$launchNotice"
                layoutWizardAvfFallback.visibility = View.VISIBLE
            }
            AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED -> {
                txtWizardAvfBadge.text = "AVF 预检未满足"
                txtWizardAvfBadge.setTextColor(getColor(R.color.zone_yellow))
                val reason = readiness.reasons().joinToString("；")
                txtWizardAvfDetails.text = "$factsSummary\n预检原因：$reason\n启动路径：QEMU\n$launchNotice"
                layoutWizardAvfFallback.visibility = View.VISIBLE
            }
            else -> {
                txtWizardAvfBadge.text = "AVF 预检未满足"
                txtWizardAvfBadge.setTextColor(getColor(R.color.zone_yellow))
                txtWizardAvfDetails.text = "$factsSummary\n$launchNotice"
                layoutWizardAvfFallback.visibility = View.VISIBLE
            }
        }
    }

    private fun setupDistroSpinner() {
        val adapter = object : ArrayAdapter<DistroItem>(
            this,
            R.layout.item_distro_spinner,
            officialDistros
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createDistroItemView(position, convertView, parent)
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createDistroItemView(position, convertView, parent)
            }

            private fun createDistroItemView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(
                    R.layout.item_distro_spinner, parent, false
                )
                val item = getItem(position) ?: return view
                view.findViewById<ImageView>(R.id.item_distro_icon).setImageResource(item.iconRes)
                view.findViewById<TextView>(R.id.item_distro_name).text = item.displayName
                view.findViewById<TextView>(R.id.item_distro_tag).text = item.tag
                return view
            }
        }
        spinnerDistroSelect.adapter = adapter

        val initialIdx = officialDistros.indexOfFirst { it.id.equals(wizardModel.distroId, ignoreCase = true) }
        val targetIdx = if (initialIdx >= 0) initialIdx else 0
        spinnerDistroSelect.setSelection(targetIdx)
        updateDistroPreview(officialDistros[targetIdx])

        spinnerDistroSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingDraftViews || wizardModel.isCreating) return
                val item = officialDistros[position]
                updateDistroPreview(item)
                switchDistro(item.id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateDistroPreview(item: DistroItem) {
        imgDistroIconPreview.setImageResource(item.iconRes)
        txtDistroNamePreview.text = item.displayName
        txtDistroTagPreview.text = item.tag
        txtDistroDescPreview.text = item.desc
    }

    private fun switchDistro(distroId: String) {
        if (wizardModel.isCreating) return
        val currentDistro = wizardModel.distroId
        if (currentDistro == distroId) return
        captureIdentityDraft()

        val manifest = DistroCatalogRegistry.getInstance().get(distroId)
        val version = manifest?.version() ?: "latest"
        val previousModel = wizardModel
        wizardModel = InstanceCreationWizardModel(
            distroId = distroId,
            distroVersion = version,
            hardwareProfile = hostProfile,
            initialStep = previousModel.currentStep
        ).also {
            it.setCustomDiskSizeGb(previousModel.diskSizeGb)
            it.updateCpuCores(previousModel.hardwareConfig.cpuCores())
            it.updateRamMegabytes(previousModel.hardwareConfig.ramMegabytes())
            it.setUserConfig(previousModel.userConfig)
            it.updateGuestIdentityDraft(previousModel.guestUsernameDraft, previousModel.guestHostnameDraft,
                previousModel.guestSudoDraft)
            it.setOptionalProfiles(previousModel.desktopSelected, previousModel.podmanSelected, previousModel.editorSelected)
            it.selectMirror(previousModel.selectedMirror)
            it.setAutoUpdateOnFirstBoot(previousModel.autoUpdateOnFirstBoot)
            it.setMountHostSharedDirectory(previousModel.mountHostSharedDirectory)
            it.setHostSharedDirectoryPath(previousModel.hostSharedDirectoryPath)
        }
        bindRamHardware()
        val targetIdx = officialDistros.indexOfFirst { it.id.equals(distroId, ignoreCase = true) }
        if (targetIdx >= 0 && spinnerDistroSelect.selectedItemPosition != targetIdx) {
            spinnerDistroSelect.setSelection(targetIdx)
            updateDistroPreview(officialDistros[targetIdx])
        }
        updateMirrorPreview()
        updateStartupTaskPreview()
        updateCreationSummary()
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
        val theme = ThemeStore(this).resolve()
        for ((btn, gb) in buttons) {
            btn.isSelected = gb == selectedGb
            btn.strokeWidth = dpToPx(if (btn.isSelected) 2 else 1)
            ThemeApplier.apply(btn, theme)
        }
    }

    private fun bindStep1Storage() {
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
        var isWifi = false
        var isCellular = false
        var isVpn = false
        var isEthernet = false
        var isConnected = false
        var dnsList = "未提供"
        var gateway = "未提供"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val net = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(net)
                isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                isEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

                val linkProps = net?.let { cm.getLinkProperties(it) }
                if (linkProps != null) {
                    val detectedDns = linkProps.dnsServers.mapNotNull { it.hostAddress }.joinToString(", ")
                    if (detectedDns.isNotBlank()) {
                        dnsList = detectedDns
                    }
                    val detectedGw = linkProps.routes.asSequence()
                        .filter { it.isDefaultRoute }
                        .mapNotNull { it.gateway?.takeUnless { address -> address.isAnyLocalAddress }?.hostAddress }
                        .firstOrNull()
                    if (!detectedGw.isNullOrBlank()) {
                        gateway = detectedGw
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm?.activeNetworkInfo
                isWifi = info?.type == ConnectivityManager.TYPE_WIFI
                isCellular = info?.type == ConnectivityManager.TYPE_MOBILE
                isConnected = info?.isConnected == true
            }
        } catch (_: Exception) {
            // Defensively fallback if network state permission is restricted or throws SecurityException
        }

        val mode = when {
            isVpn -> "VPN (当前默认网络)"
            isWifi -> "Wi-Fi (无线局域网)"
            isCellular -> "移动蜂窝网络"
            isEthernet -> "以太网"
            isConnected -> "其他网络 (系统声明可访问互联网)"
            else -> "离线或无法读取网络状态"
        }
        txtNetMode.text = getString(R.string.wizard_net_mode, mode)
        txtNetIp.text = getString(R.string.wizard_net_ip, getLocalIpAddress() ?: "未提供")
        txtNetGateway.text = getString(R.string.wizard_net_gateway, gateway)
        txtNetDns.text = getString(R.string.wizard_net_dns, dnsList)

        // Setup Mirror Spinner
        val sources = DistroMirrorProvisioner.MirrorSource.values()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources.map { it.displayName() }
        )
        spinnerMirrorSource.adapter = adapter
        spinnerMirrorSource.setSelection(wizardModel.selectedMirror.ordinal)
        spinnerMirrorSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingDraftViews || wizardModel.isCreating) return
                wizardModel.selectMirror(sources[position])
                updateMirrorPreview()
                updateCreationSummary()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Setup Speed Test Button
        btnWizardSpeedTest.setOnClickListener {
            txtWizardSpeedTestResults.visibility = View.VISIBLE
            txtWizardSpeedTestResults.text = "⚡ 正在对主流云镜像源进行并发测速..."
            btnWizardSpeedTest.isEnabled = false
            backgroundExecutor.execute {
                val candidates = MirrorSpeedTester.defaultSystemCandidates()
                val results = MirrorSpeedTester.probeAll(candidates, 3000)
                val fastest = MirrorSpeedTester.selectFastest(results, candidates.first())
                runOnUiThread {
                    btnWizardSpeedTest.isEnabled = true
                    if (fastest != null) {
                        val fastestResult = results.firstOrNull { it.candidate() == fastest && it.isSuccess }
                        val latencyInfo = if (fastestResult != null) " (${fastestResult.latencyMs()}ms)" else ""
                        txtWizardSpeedTestResults.text = "✅ 测速完成！最优推荐节点：${fastest.name()}$latencyInfo\n已优先锁定多层级容灾回退架构。"
                        spinnerMirrorSource.setSelection(DistroMirrorProvisioner.MirrorSource.MULTI_TIER.ordinal)
                        wizardModel.selectMirror(DistroMirrorProvisioner.MirrorSource.MULTI_TIER)
                        updateMirrorPreview()
                    } else {
                        txtWizardSpeedTestResults.text = "⚠️ 测速超时，已默认选用多层级容灾回退架构。"
                    }
                }
            }
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
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val net = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm?.activeNetwork else null
                val caps = net?.let { cm?.getNetworkCapabilities(it) }
                ok = if (caps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                } else {
                    @Suppress("DEPRECATION")
                    cm?.activeNetworkInfo?.isConnected == true
                }
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

        bindRamHardware()

        val facts = AvfHostFactsProbe.probe(this)
        val readiness = AvfCapabilityEvaluator.evaluate(facts)
        avfReadiness = readiness
        if (readiness.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION) {
            btnAvfAuthWizard.visibility = View.VISIBLE
            authWizardModel = AvfAuthorizationWizardModel.fromAssessment(readiness)
        } else {
            btnAvfAuthWizard.visibility = View.GONE
        }

        updateHardwareLabels()
    }

    private fun bindRamHardware() {
        val manifestSize = try {
            getManifest().sizeBytes()
        } catch (_: IllegalArgumentException) {
            sliderRam.isEnabled = false
            sliderRam.visibility = View.GONE
            updateHardwareLabels()
            return
        }
        val minimumRam = try {
            DistroResourceRequirements.minimumBootRamMb(manifestSize)
        } catch (_: IllegalArgumentException) {
            sliderRam.isEnabled = false
            sliderRam.visibility = View.GONE
            updateHardwareLabels()
            return
        }
        if (minimumRam > VmHardwareConfig.MAX_RAM_MEGABYTES) {
            sliderRam.isEnabled = false
            sliderRam.visibility = View.GONE
            updateHardwareLabels()
            return
        }
        val safeMaximumRam = ((hostProfile.totalRamMb().toLong()
            - HostHardwareProfile.HOST_RESERVED_RAM_MB).coerceIn(
                0L, VmHardwareConfig.MAX_RAM_MEGABYTES.toLong()
            ) / 256L * 256L).toInt()
        val suggestedRam = DistroResourceRequirements.suggestedWizardRamMb(
            manifestSize, wizardModel.hardwareConfig.ramMegabytes(), safeMaximumRam
        )
        wizardModel.updateRamMegabytes(suggestedRam)
        // A disabled slider still needs a non-empty range when the host cannot fit the minimum.
        sliderRam.stepSize = 0f
        sliderRam.valueTo = maxOf(safeMaximumRam, minimumRam + 256, suggestedRam).toFloat()
        sliderRam.valueFrom = minimumRam.toFloat()
        sliderRam.value = suggestedRam.toFloat()
        // Preserve an existing allocation that was not made with this slider's 256 MB steps.
        sliderRam.stepSize = if (suggestedRam % 256 == 0) 256f else 0f
        sliderRam.isEnabled = safeMaximumRam > minimumRam ||
            (safeMaximumRam == minimumRam && suggestedRam > minimumRam)
        sliderRam.visibility = View.VISIBLE
        updateHardwareLabels()
    }

    private fun hardwareValidationMessage(): String? {
        val manifest = try {
            getManifest()
        } catch (rejected: IllegalArgumentException) {
            return rejected.message
        }
        try {
            DistroResourceRequirements.requireBootRam(
                manifest.sizeBytes(), wizardModel.hardwareConfig.ramMegabytes()
            )
        } catch (rejected: IllegalArgumentException) {
            return rejected.message
        }
        if (wizardModel.safetyZone() == HostHardwareProfile.SafetyZone.RED) {
            val minimumRam = DistroResourceRequirements.minimumBootRamMb(manifest.sizeBytes())
            return "该镜像首次装配至少需要 $minimumRam MB；当前硬件分配超过宿主安全边界，请调整硬件或选择较小镜像。"
        }
        return null
    }

    private fun updateHardwareLabels() {
        if (::txtCreationSummary.isInitialized) updateCreationSummary()
        val vcpuInfo = getString(R.string.wizard_config_vcpu_info, wizardModel.hardwareConfig.cpuCores())
        txtCfgVcpu.text =
            if (avfReadiness?.status() == AvfCapabilityEvaluator.AvfStatus.READY &&
                AvfLaunchAdmission.cpuSupport(wizardModel.hardwareConfig.cpuCores()) ==
                AvfLaunchAdmission.CpuSupport.NEEDS_SINGLE_VCPU
            ) {
                vcpuInfo + "\n" + getString(R.string.wizard_config_vcpu_avf_notice)
            } else {
                vcpuInfo
            }
        txtCfgRam.text = getString(R.string.wizard_config_ram_info, wizardModel.hardwareConfig.ramMegabytes())

        val hardwareError = hardwareValidationMessage()
        btnStep3Next.isEnabled = hardwareError == null && !wizardModel.isCreating
        btnWizardInit.isEnabled = hardwareError == null && !wizardModel.isCreating
        if (hardwareError != null) {
            badgeCfgZone.text = hardwareError
            badgeCfgZone.setTextColor(getColor(R.color.zone_red))
            return
        }
        val minimumRam = DistroResourceRequirements.minimumBootRamMb(getManifest().sizeBytes())
        txtCfgRam.append("\n该镜像首次装配至少需要 $minimumRam MB。")
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
        val choices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        choices.addView(TextView(this).apply {
            text = "Linux 用户与主机名\n桌面和开发服务需要普通用户；密码沿用本实例随机生成的凭据，可在实例详情查看。"
        })
        editGuestUser = TextInputEditText(this).apply {
            hint = "Linux 用户名"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(wizardModel.guestUsernameDraft)
        }
        editGuestHostname = TextInputEditText(this).apply {
            hint = "主机名"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(wizardModel.guestHostnameDraft)
        }
        chkGuestSudo = MaterialCheckBox(this).apply {
            text = "允许此用户使用 sudo"
            isChecked = wizardModel.guestSudoDraft
        }
        choices.addView(editGuestUser)
        choices.addView(editGuestHostname)
        choices.addView(chkGuestSudo)
        choices.addView(TextView(this).apply {
            text = "图形与开发环境（默认仅终端）\n可选功能在基础 Linux 启动后下载并安装，实际进度在实例的“桌面与开发工具”页查看。实验功能尚未取得本设备运行验收。"
        })
        chkDesktop = MaterialCheckBox(this).apply {
            text = "labwc 轻量 Wayland · Debian 13 · 实验 CPU 拷贝通路\n需联网安装桌面、字体与会话组件；建议 2 GB 以上 guest RAM、至少 2 GB 额外磁盘预算。实际下载量由签名仓库计划确定。"
        }
        chkPodman = MaterialCheckBox(this).apply { text = "Rootless Podman · 需普通用户与内核探针验证（另需下载容器镜像）" }
        chkEditor = MaterialCheckBox(this).apply { text = "code-server 本机开发服务 · ARM64 固定包约 224 MiB，另需依赖和工作空间" }
        choices.addView(chkDesktop); choices.addView(chkPodman); choices.addView(chkEditor)
        step4Container.addView(choices, 0)
        updateOptionalProfiles()
        chkAutoUpdate.text = "额外系统升级（可选）"
        chkAutoUpdate.isChecked = wizardModel.autoUpdateOnFirstBoot
        val identityWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (applyingDraftViews || wizardModel.isCreating) return
                captureIdentityDraft()
                updateCreationSummary()
            }
        }
        editGuestUser.addTextChangedListener(identityWatcher)
        editGuestHostname.addTextChangedListener(identityWatcher)
        chkGuestSudo.setOnCheckedChangeListener { _, _ ->
            if (!applyingDraftViews && !wizardModel.isCreating) {
                captureIdentityDraft()
                updateCreationSummary()
            }
        }
        listOf(chkDesktop, chkPodman, chkEditor).forEach { checkbox ->
            checkbox.setOnCheckedChangeListener { _, _ ->
                if (!applyingDraftViews && !wizardModel.isCreating) {
                    captureIdentityDraft()
                    updateCreationSummary()
                }
            }
        }
        updateStartupTaskPreview()
        updateCreationSummary()
    }

    private fun updateStartupTaskPreview() {
        val cmd = wizardModel.resolveUpdateCommand()
        txtUpdateCommandPreview.text = if (wizardModel.autoUpdateOnFirstBoot) cmd else "额外系统升级已关闭；基础环境仍需安装与验证。"
        txtUpdateCommandDesc.text = "基础环境在首次启动时安装并验证，失败可重试。此复选项仅控制额外系统升级；开启后执行下方升级指令。"
    }

    private fun updateOptionalProfiles() {
        if (!::chkDesktop.isInitialized) return
        val wasApplying = applyingDraftViews
        applyingDraftViews = true
        try {
            chkDesktop.isEnabled = wizardModel.distroId == "debian" && !wizardModel.isCreating
            val supported = wizardModel.distroId in setOf("debian", "ubuntu") && !wizardModel.isCreating
            chkPodman.isEnabled = supported
            chkEditor.isEnabled = supported
            chkDesktop.isChecked = wizardModel.desktopSelected
            chkPodman.isChecked = wizardModel.podmanSelected
            chkEditor.isChecked = wizardModel.editorSelected
        } finally { applyingDraftViews = wasApplying }
    }

    private fun updateCreationSummary() {
        updateOptionalProfiles()
        val distroName = DistroIconHelper.getDisplayName(wizardModel.distroId)
        val diskGb = wizardModel.diskSizeGb
        val vcpu = wizardModel.hardwareConfig.cpuCores()
        val ram = wizardModel.hardwareConfig.ramMegabytes()
        val mirror = wizardModel.selectedMirror.displayName()
        val updateText = if (wizardModel.autoUpdateOnFirstBoot) "基础环境安装与验证，额外系统升级开启" else "基础环境安装与验证，额外系统升级关闭"
        val sharedText = if (wizardModel.mountHostSharedDirectory) "已挂载 (/mnt/shared)" else "未挂载"

        txtCreationSummary.text = """
            • 目标发行版：$distroName
            • 虚拟磁盘容量：$diskGb GB 稀疏磁盘
            • 宿主共享目录：$sharedText
            • 硬件资源规格：$vcpu vCPU · $ram MB RAM
            • 镜像软件源：$mirror
            • Linux 用户：${wizardModel.guestUsernameDraft} · 主机名：${wizardModel.guestHostnameDraft}
            • sudo：${if (wizardModel.guestSudoDraft) "允许" else "关闭"}
            • 可选功能：${listOfNotNull(if (wizardModel.desktopSelected) "桌面" else null, if (wizardModel.podmanSelected) "Podman" else null, if (wizardModel.editorSelected) "code-server" else null).joinToString("、").ifEmpty { "仅终端" }}
            • 首次启动任务：$updateText
            • 安装失败：可重试
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
        }
        updateCreationSummary()
    }

    private fun getManifest(): DistroManifest {
        return DistroCatalogRegistry.getInstance().get(wizardModel.distroId)
            ?: throw IllegalArgumentException("无法确认该发行版的镜像清单及首次装配内存需求；请重新选择镜像。")
    }

    private fun startDownloadAndInitialize() {
        if (wizardModel.isCreating) return
        val username = editGuestUser.text?.toString()?.trim().orEmpty()
        val hostname = editGuestHostname.text?.toString()?.trim().orEmpty()
        editGuestUser.error = null
        editGuestHostname.error = null
        if (!io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig.isValidUsername(username)) {
            editGuestUser.error = "用户名须以小写字母或下划线开头，仅包含小写字母、数字、下划线和连字符。"
            editGuestUser.requestFocus()
            return
        }
        if (hostname.isBlank()) {
            editGuestHostname.error = "请填写主机名。"
            editGuestHostname.requestFocus()
            return
        }
        val userConfig = try {
            io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig(
                username, wizardModel.userConfig.password(), chkGuestSudo.isChecked, hostname)
        } catch (invalid: IllegalArgumentException) {
            editGuestHostname.error = "主机名须为有效的字母、数字或连字符标签。"
            editGuestHostname.requestFocus()
            return
        }
        if ((chkDesktop.isChecked || chkPodman.isChecked || chkEditor.isChecked) && userConfig.username() == "root") {
            editGuestUser.error = "所选桌面和开发服务需要普通用户，请填写新的 Linux 用户名。"
            editGuestUser.requestFocus()
            return
        }
        wizardModel.setUserConfig(userConfig)
        val hardwareError = hardwareValidationMessage()
        if (hardwareError != null) {
            wizardModel.goToStep(InstanceCreationWizardModel.CreationStep.STEP_HARDWARE)
            updateStepVisibility()
            updateHardwareLabels()
            Toast.makeText(this, hardwareError, Toast.LENGTH_LONG).show()
            return
        }
        val manifest = getManifest()
        val availableBytes = StatFs(filesDir.path).availableBytes
        val requiredBytes = wizardModel.requestedDiskBytes + manifest.sizeBytes() + 128L * 1024L * 1024L
        if (availableBytes < requiredBytes) {
            Toast.makeText(this, R.string.create_storage_low, Toast.LENGTH_LONG).show()
            return
        }

        if (!wizardModel.tryBeginCreation()) return
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
        val selectedProfiles = buildSet {
            if (chkDesktop.isChecked) add("desktop-labwc")
            if (chkPodman.isChecked) add("podman")
            if (chkEditor.isChecked) add("code-server")
        }

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
                    creator.create(input, manifest.sizeBytes(), wizardModel.hardwareConfig, wizardModel.userConfig)
                }

                GuestFeatureSelections.save(this, createdInstance.id(), selectedProfiles)

                // Save injected mirror configuration and firstboot script into instance store via FirstBootProvisionPipeline
                val payload = FirstBootProvisionPipeline.create(
                    wizardModel.distroId,
                    manifest.version(),
                    wizardModel.selectedMirror,
                    wizardModel.autoUpdateOnFirstBoot
                )
                if (payload.hasMirrorConfig()) {
                    val mirrorConfigFile = instancesDir.resolve("${createdInstance.id()}.mirror")
                    Files.write(mirrorConfigFile, payload.mirrorConfigFileContent())
                }
                if (payload.hasFirstBootScript()) {
                    val firstbootFile = instancesDir.resolve("${createdInstance.id()}.firstboot")
                    Files.write(firstbootFile, payload.firstBootScript())
                }

                // Mark onboarding completed if not already set
                if (!settingsManager.isOnboardingCompleted) {
                    settingsManager.isOnboardingCompleted = true
                }

                runOnUiThread {
                    Toast.makeText(this, "实例已创建。首次启动将安装并验证基础环境。", Toast.LENGTH_LONG).show()
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

    // --- AVF Authorization Assistance Dialogs (ADR-0003 & Ticket 53) ---
    private fun showAvfAuthorizationWizard() {
        avfReadiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
        val model = AvfAuthorizationWizardModel.fromAssessment(avfReadiness!!)
        if (!model.canStart()) return
        authWizardModel = model.start()
        showShizukuStep()
    }

    private fun showShizukuStep() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wizard_title)
            .setMessage(R.string.wizard_shizuku_steps)
            .setPositiveButton(R.string.shizuku_client_entry) { _, _ ->
                ShizukuClientDialog().show(supportFragmentManager, "shizuku-client")
            }
            .setNeutralButton(R.string.wizard_use_adb) { _, _ ->
                authWizardModel = authWizardModel?.chooseAdbFallback()
                showAdbStep()
            }
            .setNegativeButton(R.string.wizard_recheck_permissions) { _, _ -> recheckAuthorization() }
            .show().also(ThemeApplier::apply)
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
            .show().also(ThemeApplier::apply)
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
        val granted = facts.hasBothVirtualMachinePermissions()
        authWizardModel = authWizardModel?.confirmAuthorized(
            facts.manageVirtualMachinePermission(), facts.useCustomVirtualMachinePermission())
        // Refresh the card and authorization entry from this same read, without rebinding
        // CPU/RAM controls or changing the user's selected hardware configuration.
        bindWizardAvfCard(facts)
        if (granted) {
            AlertDialog.Builder(this)
                .setTitle(R.string.wizard_authorized_success_title)
                .setMessage(R.string.wizard_authorized_success_msg)
                .setPositiveButton(R.string.credentials_close, null)
                .show().also(ThemeApplier::apply)
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.wizard_vendor_failure_title)
                .setMessage(R.string.wizard_vendor_failure_steps)
                .setPositiveButton(R.string.credentials_close, null)
                .show().also(ThemeApplier::apply)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
