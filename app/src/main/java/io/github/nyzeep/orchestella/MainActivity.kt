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
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.capability.DeviceCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException
import io.github.nyzeep.orchestella.runtime.engineavf.AvfVirtualMachineEngine
import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfVirtualMachineApi
import io.github.nyzeep.orchestella.runtime.engineqemu.RuntimeArtifactManifest
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock
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
    private lateinit var themeStore: ThemeStore
    private var activeTheme: AppTheme? = null
    private var wizardModel: AvfAuthorizationWizardModel? = null
    private var pendingExportInstance: InstanceLifecycle.Instance? = null
    private var currentTabIndex = 0
    private var dashboardInstance: InstanceLifecycle.Instance? = null
    private var sshCommand: String? = null
    private var lastInstanceState = ""
    private val dashboardHandler = Handler(Looper.getMainLooper())
    private val refreshDashboard = object : Runnable {
        override fun run() {
            if (!::instanceStore.isInitialized || isFinishing || isDestroyed) return
            val theme = activeTheme ?: themeStore.resolve()
            renderGauges(theme)
            val instances = try { instanceStore.list() } catch (_: IOException) { emptyList() }
            val state = instances.joinToString { "${it.id()}:${it.state()}" }
            if (state != lastInstanceState) {
                showInstances()
            } else {
                updateConsoleWithInstance(dashboardInstance)
            }
            if (currentTabIndex == 2) renderHardwareSpecDetails()
            dashboardHandler.postDelayed(this, 3000L)
        }
    }

    private val safExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { destUri ->
            val instance = pendingExportInstance ?: return@let
            operations.execute {
                try {
                    val current = requireColdBackup(instance.id())
                    val adapter = AndroidSafExportAdapter(
                        AndroidSafExportAdapter.DocumentStreamOpener {
                            requireColdBackup(instance.id())
                            contentResolver.openOutputStream(it) ?: throw IOException("无法写入备份文件")
                        }, Clock.systemUTC())
                    adapter.exportToDocumentUri(current, destUri)
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
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsManager = SettingsManager(this)
        themeStore = ThemeStore(this)

        // Android 15/17 Edge-to-Edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        applyThemeToDashboard()
        val rootContainer = findViewById<View>(R.id.root_container)
        val density = resources.displayMetrics.density
        val baseHorizontalPad = 0
        val baseBottomPad = 0
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
        findViewById<View>(R.id.about_button)?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<View>(R.id.ssh_manager_button)?.setOnClickListener {
            io.github.nyzeep.orchestella.ssh.SshManagerDialog.show(this)
        }
        findViewById<View>(R.id.theme_toggle_button)?.setOnClickListener {
            showThemeSelectionDialog()
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
        findViewById<View>(R.id.card_distro_debian)?.setOnClickListener { launchCreationWizard("debian") }
        findViewById<View>(R.id.card_distro_fedora)?.setOnClickListener { launchCreationWizard("fedora") }
        findViewById<View>(R.id.card_distro_arch)?.setOnClickListener { launchCreationWizard("arch") }
        findViewById<View>(R.id.card_distro_opensuse)?.setOnClickListener { launchCreationWizard("opensuse") }
        findViewById<View>(R.id.card_distro_almalinux)?.setOnClickListener { launchCreationWizard("almalinux") }
        findViewById<View>(R.id.card_distro_rocky)?.setOnClickListener { launchCreationWizard("rocky") }

        createUbuntuButton.setOnClickListener { launchCreationWizard("ubuntu") }
        createDebianButton.setOnClickListener { launchCreationWizard("debian") }
        findViewById<View>(R.id.create_fedora)?.setOnClickListener { launchCreationWizard("fedora") }
        findViewById<View>(R.id.create_arch)?.setOnClickListener { launchCreationWizard("arch") }
        findViewById<View>(R.id.create_opensuse)?.setOnClickListener { launchCreationWizard("opensuse") }
        findViewById<View>(R.id.create_almalinux)?.setOnClickListener { launchCreationWizard("almalinux") }
        findViewById<View>(R.id.create_rocky)?.setOnClickListener { launchCreationWizard("rocky") }

        upgradeAuthorizationButton.setOnClickListener { showAvfAuthorizationWizard() }
        findViewById<View>(R.id.btn_import_instance)?.setOnClickListener {
            safImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        refreshWizardEntry()
        setupNavigationTabs()
        setupConsoleControls()
        setupHardwareTab()
        setupSettingsTab()
        showInstances()
        switchTab(savedInstanceState?.getInt("dashboard_tab", 0) ?: 0)
        ThemeApplier.bind(this)
        handleLaunchIntent(intent)
    }

    private fun setupNavigationTabs() {
        findViewById<View>(R.id.nav_tab_console)?.setOnClickListener { switchTab(0) }
        findViewById<View>(R.id.nav_tab_instances)?.setOnClickListener { switchTab(1) }
        findViewById<View>(R.id.nav_tab_hardware)?.setOnClickListener { switchTab(2) }
        findViewById<View>(R.id.nav_tab_distros)?.setOnClickListener { switchTab(3) }
        findViewById<View>(R.id.nav_tab_settings)?.setOnClickListener { switchTab(4) }
    }

    private fun switchTab(tabIndex: Int) {
        currentTabIndex = tabIndex
        val theme = activeTheme ?: themeStore.resolve()
        val activeColor = theme.primary
        val inactiveBg = theme.surface
        val inactiveText = theme.textDim

        val tabs = listOf(
            Triple(R.id.container_tab_console, R.id.nav_tab_console, Pair(R.id.nav_icon_console, R.id.nav_text_console)),
            Triple(R.id.container_tab_instances, R.id.nav_tab_instances, Pair(R.id.nav_icon_instances, R.id.nav_text_instances)),
            Triple(R.id.container_tab_hardware, R.id.nav_tab_hardware, Pair(R.id.nav_icon_hardware, R.id.nav_text_hardware)),
            Triple(R.id.container_tab_distros, R.id.nav_tab_distros, Pair(R.id.nav_icon_distros, R.id.nav_text_distros)),
            Triple(R.id.container_tab_settings, R.id.nav_tab_settings, Pair(R.id.nav_icon_settings, R.id.nav_text_settings)),
        )

        val indexLabels = listOf("01/K", "02/I", "03/H", "04/D", "05/S")
        val titleLabels = listOf(
            "ORCHESTELLA",
            "实例管理",
            "设备状态",
            "Linux 发行版",
            "设置与主题"
        )

        findViewById<TextView>(R.id.masthead_index_text)?.text = indexLabels.getOrElse(tabIndex) { "01/K" }
        findViewById<TextView>(R.id.dashboard_title)?.text = titleLabels.getOrElse(tabIndex) { "ORCHESTELLA // pKVM" }

        findViewById<View>(R.id.masthead_index_box)?.setBackgroundColor(activeColor)
        findViewById<TextView>(R.id.tv_status_time)?.setBackgroundColor(activeColor)
        findViewById<View>(R.id.indicator_status_pulse)?.setBackgroundColor(activeColor)
        findViewById<TextView>(R.id.tv_status_time)?.setTextColor(ThemeColors.onColor(activeColor))
        findViewById<TextView>(R.id.masthead_index_text)?.setTextColor(ThemeColors.onColor(activeColor))

        try {
            val nowTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            findViewById<TextView>(R.id.tv_status_time)?.text = "[ $nowTime ]"
        } catch (_: Exception) {}

        tabs.forEachIndexed { index, (containerId, navTabId, navElements) ->
            val container = findViewById<View>(containerId)
            val navTab = findViewById<View>(navTabId)
            val icon = findViewById<ImageView>(navElements.first)
            val text = findViewById<TextView>(navElements.second)
            navTab?.isSelected = index == tabIndex
            navTab?.let { ThemeApplier.setRole(it, ThemeApplier.Role.NAVIGATION) }

            if (index == tabIndex) {
                container?.visibility = View.VISIBLE
                navTab?.setBackgroundColor(activeColor)
                icon?.imageTintList = android.content.res.ColorStateList.valueOf(ThemeColors.onColor(activeColor))
                text?.setTextColor(ThemeColors.onColor(activeColor))
                text?.paint?.isFakeBoldText = true
            } else {
                container?.visibility = View.GONE
                navTab?.setBackgroundColor(inactiveBg)
                icon?.imageTintList = android.content.res.ColorStateList.valueOf(inactiveText)
                text?.setTextColor(inactiveText)
                text?.paint?.isFakeBoldText = false
            }
        }

        if (tabIndex == 2) {
            renderHardwareSpecDetails()
            renderGauges(theme)
        }
    }

    private fun setupConsoleControls() {
        findViewById<View>(R.id.seg_pkvm)?.setOnClickListener {
            when (avfReadiness?.status()) {
                AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> {
                    showAvfAuthorizationWizard()
                }
                AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED,
                AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN,
                AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED,
                AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE -> {
                    val reasons = avfReadiness?.reasons()?.joinToString("\n") ?: "无法读取当前系统的虚拟机模式"
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("AVF 模式检测")
                        .setMessage("当前设备状态：\n$reasons\n\n授权不会改变 VM 模式或平台接口；应用继续使用 QEMU 完整 Linux 路径。")
                        .setPositiveButton("了解", null)
                        .show()
                }
                AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED -> {
                    val reasons = avfReadiness?.reasons()?.joinToString("\n") ?: "设备未声明虚拟化框架支持"
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("AVF 硬件加速检测")
                        .setMessage("当前设备状态：\n$reasons\n\n应用已切换至完整 QEMU 软件仿真引擎，支持所有 Linux 实例及图形桌面运行。")
                        .setPositiveButton("了解", null)
                        .show()
                }
                else -> switchTab(2)
            }
        }
        findViewById<View>(R.id.seg_qemu)?.setOnClickListener { switchTab(2) }

        findViewById<View>(R.id.copy_ssh_btn)?.setOnClickListener {
            val command = sshCommand
            if (command == null) {
                dashboardInstance?.let(::openPortForwarding) ?: switchTab(3)
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SSH", command))
                Toast.makeText(this, "已复制已配置的 SSH 映射指令", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.act_terminal)?.setOnClickListener {
            val instances = try { instanceStore.list() } catch (_: Exception) { emptyList() }
            val running = instances.firstOrNull { it.state() == InstanceLifecycle.State.RUNNING }
            if (running != null) {
                openTerminal(running)
            } else if (instances.isNotEmpty()) {
                val target = instances.firstOrNull { it.id() == settingsManager.lastActiveInstanceId } ?: instances.first()
                Toast.makeText(this, "正在启动 ${target.hostname()}...", Toast.LENGTH_SHORT).show()
                launchInstance(target)
            } else {
                Toast.makeText(this, "尚未创建任何实例，请先在发行版矩阵中装配", Toast.LENGTH_SHORT).show()
                switchTab(3)
            }
        }

        findViewById<View>(R.id.btn_fullscreen_terminal)?.setOnClickListener { openRuntimeLogs() }

        findViewById<View>(R.id.act_port_forwarding)?.setOnClickListener {
            val instances = try { instanceStore.list() } catch (_: Exception) { emptyList() }
            val target = instances.firstOrNull { it.state() == InstanceLifecycle.State.RUNNING }
                ?: instances.firstOrNull { it.id() == settingsManager.lastActiveInstanceId }
                ?: instances.firstOrNull()
            if (target != null) {
                openPortForwarding(target)
            } else {
                Toast.makeText(this, "无可用实例配置端口转发", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.act_restart_kernel)?.setOnClickListener { openRuntimeLogs() }

        findViewById<View>(R.id.act_snapshot)?.setOnClickListener {
            val instances = try { instanceStore.list() } catch (_: Exception) { emptyList() }
            val target = instances.firstOrNull { it.state() == InstanceLifecycle.State.RUNNING }
                ?: instances.firstOrNull { it.id() == settingsManager.lastActiveInstanceId }
                ?: instances.firstOrNull()
            if (target != null) {
                exportStoppedInstance(target)
            } else {
                Toast.makeText(this, "无可用实例进行存档", Toast.LENGTH_SHORT).show()
            }
        }

    }

    private fun openRuntimeLogs() {
        val instance = dashboardInstance
        startActivity(Intent(this, LogViewerActivity::class.java).apply {
            putExtra(LogViewerActivity.EXTRA_KIND,
                if (instance != null) LogViewerActivity.KIND_GUEST else LogViewerActivity.KIND_APP)
            instance?.let { putExtra(LogViewerActivity.EXTRA_INSTANCE_ID, it.id()) }
        })
    }

    private fun requireColdBackup(instanceId: String): InstanceLifecycle.Instance {
        val current = instanceStore.load(instanceId) ?: throw IOException("实例已不存在")
        if (current.state() == InstanceLifecycle.State.RUNNING ||
            current.state() == InstanceLifecycle.State.PROVISIONING ||
            current.state() == InstanceLifecycle.State.SUSPENDED ||
            ExecutionSessionRegistry.get(instanceId) != null || InstanceLaunchGuard.isInFlight(instanceId)) {
            throw IOException("请先关闭实例，再导出完整冷备份")
        }
        return current
    }

    private fun exportStoppedInstance(instance: InstanceLifecycle.Instance) {
        try {
            pendingExportInstance = requireColdBackup(instance.id())
            safExportLauncher.launch("${instance.id()}-backup.zip")
        } catch (e: IOException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun setupHardwareTab() {
        findViewById<View>(R.id.btn_hw_refresh)?.setOnClickListener {
            renderHardwareSpecDetails()
            val theme = activeTheme ?: themeStore.resolve()
            renderGauges(theme)
            Toast.makeText(this, "硬件遥测状态已刷新", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSettingsTab() {
        listOf(
            Triple(R.id.btn_theme_constructivist_red, "constructivist-workstation", R.id.badge_theme_red_active),
            Triple(R.id.btn_theme_constructivist_blue, "constructivist-cobalt", R.id.badge_theme_blue_active),
            Triple(R.id.btn_theme_constructivist_yellow, "constructivist-amber", R.id.badge_theme_yellow_active)
        ).forEach { (rowId, themeId, _) ->
            findViewById<LinearLayout>(rowId)?.getChildAt(0)?.let {
                ThemeApplier.setRole(it, ThemeApplier.Role.PRESERVE)
                val preview = io.github.nyzeep.orchestella.ui.theme.ThemeCatalog.all.first { it.id == themeId }
                it.setBackgroundColor(preview.primary)
            }
        }
        findViewById<View>(R.id.btn_create_linux_instance_top)?.setOnClickListener {
            switchTab(3)
        }
        findViewById<View>(R.id.btn_theme_constructivist_red)?.setOnClickListener {
            themeStore.setThemeId("constructivist-workstation")
            applyThemeToDashboard()
            switchTab(4)
            Toast.makeText(this, "已应用主题: 构成主义工控 · 红", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_theme_constructivist_blue)?.setOnClickListener {
            themeStore.setThemeId("constructivist-cobalt")
            applyThemeToDashboard()
            switchTab(4)
            Toast.makeText(this, "已应用主题: 钴蓝张力 · 蓝", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_theme_constructivist_yellow)?.setOnClickListener {
            themeStore.setThemeId("constructivist-amber")
            applyThemeToDashboard()
            switchTab(4)
            Toast.makeText(this, "已应用主题: 镉黄警示 · 黄", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btn_settings_open_ssh)?.setOnClickListener {
            io.github.nyzeep.orchestella.ssh.SshManagerDialog.show(this)
        }
        findViewById<View>(R.id.btn_settings_open_system)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btn_settings_open_about)?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun renderHardwareSpecDetails() {
        val profile = HostHardwareDetector.detect(this)
        val facts = AvfHostFactsProbe.probe(this)

        findViewById<TextView>(R.id.hw_detail_platform_declared)?.text =
            if (facts.virtualizationFrameworkFeature() || "true".equals(facts.virtualizationProperty(), ignoreCase = true))
                "SUPPORTED (FEATURE_VIRTUALIZATION=true)"
            else "NOT DECLARED (FEATURE_VIRTUALIZATION=false)"

        findViewById<TextView>(R.id.hw_detail_avf_permission)?.text =
            "MANAGE：${AvfHostFactsProbe.permissionLabel(facts.manageVirtualMachinePermissionState())}\n" +
                "CUSTOM：${AvfHostFactsProbe.permissionLabel(facts.useCustomVirtualMachinePermissionState())}\n" +
                "平台访问：${AvfHostFactsProbe.platformSummary(facts.platformFacts())}\n" +
                getString(R.string.avf_preflight_boundary)

        val kvmFile = java.io.File("/dev/kvm")
        findViewById<TextView>(R.id.hw_detail_kvm_node)?.text = when {
            kvmFile.canRead() && kvmFile.canWrite() -> "可读写 /dev/kvm（仍需后端运行验证）"
            kvmFile.exists() -> "/dev/kvm 存在，App 无读写权限"
            else -> "当前 App 未发现 /dev/kvm"
        }

        findViewById<TextView>(R.id.hw_detail_cpu_arch)?.text =
            "${System.getProperty("os.arch")} (${Build.SUPPORTED_ABIS.joinToString(", ")})"

        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "ARM-v8"
        findViewById<TextView>(R.id.hw_detail_cpu_model)?.text =
            "${Build.HARDWARE} / ${Build.BOARD} ($soc)"

        findViewById<TextView>(R.id.hw_detail_cpu_cores)?.text =
            "${profile.totalCores()} Cores (SMP Architecture, Multi-threaded)"

        val totalRamMb = profile.totalRamMb()
        val availRamMb = profile.availRamMb()
        findViewById<TextView>(R.id.hw_detail_ram_specs)?.text =
            "物理 RAM: ${totalRamMb} MB / 可用: ${availRamMb} MB"

        findViewById<TextView>(R.id.hw_detail_ram_reserve)?.text =
            "LowMem阈值: ${profile.thresholdRamMb()} MB / 紧张状态: ${if (profile.isLowMemory()) "YES" else "NO"}"

        val stat = StatFs(filesDir.path)
        val totalGb = stat.totalBytes / (1024L * 1024L * 1024L)
        val freeGb = stat.availableBytes / (1024L * 1024L * 1024L)
        findViewById<TextView>(R.id.hw_detail_storage_specs)?.text =
            "应用沙箱存储: ${totalGb} GB (剩余: ${freeGb} GB, 块大小: ${stat.blockSizeLong} B)"

        findViewById<TextView>(R.id.hw_detail_os_release)?.text =
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, Build ${Build.DISPLAY})"

        findViewById<TextView>(R.id.hw_detail_kernel_release)?.text =
            "Linux Kernel ${System.getProperty("os.version") ?: "unknown"}"

        findViewById<TextView>(R.id.hw_detail_device_model)?.text =
            "${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND} / ${Build.DEVICE})"
    }

    private fun openPortForwarding(instance: InstanceLifecycle.Instance) {
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

    private fun updateConsoleWithInstance(instance: InstanceLifecycle.Instance?) {
        dashboardInstance = instance
        val theme = activeTheme ?: themeStore.resolve()
        val session = instance?.let { ExecutionSessionRegistry.get(it.id()) }
        val running = instance?.state() == InstanceLifecycle.State.RUNNING && session != null
        val engine = when (session) {
            is QemuInstanceLauncher.Session -> "QEMU TCG · 软件虚拟化"
            is AvfInstanceLauncher.AvfSession -> "AVF · 硬件虚拟化"
            else -> if (instance != null) "启动时自动选择可用引擎" else "创建 Linux 实例后可用"
        }
        findViewById<TextView>(R.id.tv_active_mach_name)?.text = instance?.let {
            "${it.hostname()} · ${it.distro().id()} ${it.distro().version()}"
        } ?: "尚未创建 Linux 实例"
        findViewById<TextView>(R.id.tv_active_fps_ping)?.apply {
            text = if (running) "运行中" else instance?.state()?.name ?: "未配置"
            setTextColor(if (running) theme.accent else theme.textDim)
        }
        findViewById<TextView>(R.id.tv_engine_subtext)?.text = engine
        findViewById<TextView>(R.id.header_sys_badge)?.text = if (running) "RUNNING" else "IDLE"
        findViewById<TextView>(R.id.tv_masthead_latency)?.text = if (running) "在线" else "待机"
        findViewById<TextView>(R.id.dashboard_subtitle)?.text =
            if (running) engine else "无 Root Linux 工作站"
        findViewById<com.google.android.material.button.MaterialButton>(R.id.seg_pkvm)?.text =
            if (session is AvfInstanceLauncher.AvfSession) {
                "AVF · 正在使用"
            } else when (avfReadiness?.status()) {
                AvfCapabilityEvaluator.AvfStatus.READY -> "AVF · 预检通过"
                AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> "AVF · 待授权"
                AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED -> "AVF · 模式不支持"
                AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN -> "AVF · 模式未验证"
                AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED -> "AVF · 接口受限"
                AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE -> "AVF · 接口缺失"
                else -> "AVF · 未就绪"
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.seg_qemu)?.text =
            if (session is QemuInstanceLauncher.Session) "QEMU · 正在使用" else "QEMU · 可用"
        sshCommand = instance?.let { target ->
            try {
                val rule = FilePortForwardingStore(filesDir.toPath().resolve("network"))
                    .loadRules(target.id()).firstOrNull {
                        it.guestPort() == 22 && it.protocol().wireName() == "tcp"
                    }
                rule?.let {
                    val host = if (it.isLanExposed()) "<手机IP>" else it.bindAddress()
                    "ssh -p ${it.hostPort()} ${target.defaultUser()}@$host"
                }
            } catch (_: Exception) { null }
        }
        findViewById<TextView>(R.id.ssh_string)?.text = sshCommand ?: "尚未配置 SSH 端口映射"
        findViewById<com.google.android.material.button.MaterialButton>(R.id.copy_ssh_btn)?.text =
            if (sshCommand == null) "配置" else "复制"
        val guestStatus = if (running) {
            GuestRuntimeStatus.latest(ExecutionSessionRegistry.getLiveSession(instance.id())?.transcript)
        } else null
        val operationNotice = if (::createStatus.isInitialized) createStatus.text.toString() else ""
        findViewById<TextView>(R.id.console_tty_text)?.text = when {
            instance == null -> "选择发行版创建实例，随后在真实终端中使用 Linux。"
            running -> guestStatus?.description() ?: "Linux 正在引导，打开终端查看装配与启动进度。"
            operationNotice.isNotBlank() -> operationNotice
            else -> "实例数据已保留。打开终端会启动该实例；冷备份需在关机后导出。"
        }
        findViewById<TextView>(R.id.vm_allocation_summary)?.text = instance?.let {
            "VM 配置 · ${it.hardwareConfig().cpuCores()} vCPU / ${it.hardwareConfig().ramMegabytes()} MB RAM"
        } ?: "尚无 VM 资源分配"
        findViewById<TextView>(R.id.network_runtime_summary)?.text =
            if (running) "出站 NAT · 入站访问需显式配置端口映射" else "网络随实例启动 · 默认无 LAN 暴露"
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
            } catch (failure: Exception) {
                createStatus.text = "读取实例失败：${failure.message ?: failure.javaClass.simpleName}"
                Toast.makeText(this, createStatus.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::themeStore.isInitialized) {
            applyThemeToDashboard()
        }
        if (::instanceStore.isInitialized) {
            avfReadiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
            refreshWizardEntry()
            reconcileOrphanInstances()
            renderSystemStatus(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
            showInstances()
            if (currentTabIndex == 2) {
                renderHardwareSpecDetails()
            }
        }
        dashboardHandler.removeCallbacks(refreshDashboard)
        dashboardHandler.post(refreshDashboard)
    }

    override fun onPause() {
        dashboardHandler.removeCallbacks(refreshDashboard)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("dashboard_tab", currentTabIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        dashboardHandler.removeCallbacks(refreshDashboard)
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
        avfReadiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
        val current = AvfAuthorizationWizardModel.fromAssessment(avfReadiness!!)
        if (!current.canStart()) return
        wizardModel = current.start()
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
                wizardModel = wizardModel!!.chooseAdbFallback()
                showAdbStep()
            }
            .setNegativeButton(R.string.wizard_recheck_permissions) { _, _ -> recheckAuthorization() }
            .show().also { ThemeApplier.apply(it) }
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
            .show().also { ThemeApplier.apply(it) }
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
        val grantedNow = facts.hasBothVirtualMachinePermissions()
        wizardModel = wizardModel!!.confirmAuthorized(
            facts.manageVirtualMachinePermission(), facts.useCustomVirtualMachinePermission())
        avfReadiness = AvfCapabilityEvaluator.evaluate(facts)
        refreshWizardEntry()
        if (grantedNow) {
            avfReadiness = AvfCapabilityEvaluator.evaluate(facts)
            refreshWizardEntry()
            rerenderSystemStatus()
            AlertDialog.Builder(this)
                .setTitle(R.string.wizard_authorized_success_title)
                .setMessage(R.string.wizard_authorized_success_msg)
                .setPositiveButton(R.string.credentials_close, null)
                .show().also { ThemeApplier.apply(it) }
        } else {
            AlertDialog.Builder(this)
                .setTitle(R.string.wizard_vendor_failure_title)
                .setMessage(R.string.wizard_vendor_failure_steps)
                .setPositiveButton(R.string.credentials_close, null)
                .show().also { ThemeApplier.apply(it) }
        }
    }

    private fun applyThemeToDashboard() {
        try {
            val theme = themeStore.resolve()
            activeTheme = theme
            ThemeApplier.bind(this)
            listOf(
                R.id.badge_theme_red_active to "constructivist-workstation",
                R.id.badge_theme_blue_active to "constructivist-cobalt",
                R.id.badge_theme_yellow_active to "constructivist-amber"
            ).forEach { (id, themeId) ->
                findViewById<View>(id)?.visibility = if (themeStore.themeId() == themeId) View.VISIBLE else View.GONE
            }

            // Typography & Chrome
            findViewById<TextView>(R.id.dashboard_title)?.setTextColor(theme.text)
            findViewById<TextView>(R.id.dashboard_subtitle)?.setTextColor(theme.textDim)
            findViewById<TextView>(R.id.instances_title)?.setTextColor(theme.text)
            findViewById<TextView>(R.id.instances_empty)?.setTextColor(theme.textDim)

            // Header Action Buttons
            val iconBtnTint = android.content.res.ColorStateList.valueOf(theme.surfaceTranslucent)
            val iconContentTint = android.content.res.ColorStateList.valueOf(theme.text)
            listOf(R.id.ssh_manager_button, R.id.theme_toggle_button, R.id.about_button, R.id.settings_button).forEach { id ->
                findViewById<com.google.android.material.button.MaterialButton>(id)?.let { btn ->
                    btn.backgroundTintList = iconBtnTint
                    btn.iconTint = iconContentTint
                    btn.strokeColor = android.content.res.ColorStateList.valueOf((theme.glow and 0x00FFFFFF) or 0x33000000)
                    btn.strokeWidth = (1 * resources.displayMetrics.density).toInt()
                }
            }

            findViewById<View>(R.id.system_status_card)?.let { card ->
                if (card is com.google.android.material.card.MaterialCardView) {
                    card.setCardBackgroundColor(theme.surfaceTranslucent)
                    card.strokeColor = (theme.glow and 0x00FFFFFF) or 0x33000000
                    card.strokeWidth = (1 * resources.displayMetrics.density).toInt()
                    attachPressGlow(card, theme)
                } else {
                    card.background = buildGlowCardDrawable(theme)
                    attachPressGlow(card, theme)
                }
            }
            val distroCards = listOf(
                R.id.card_create_linux_main,
                R.id.card_distro_debian,
                R.id.card_distro_fedora,
                R.id.card_distro_arch,
                R.id.card_distro_opensuse,
                R.id.card_distro_almalinux,
                R.id.card_distro_rocky
            )
            for (cardId in distroCards) {
                findViewById<View>(cardId)?.let { c ->
                    c.background = buildGlowCardDrawable(theme)
                    attachPressGlow(c, theme)
                }
            }
            renderGauges(theme)
            applyThemeToInstances(theme)
            switchTab(currentTabIndex)
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "theme apply failed", e)
        }
    }

    private fun showThemeSelectionDialog() {
        val themes = io.github.nyzeep.orchestella.ui.theme.ThemeCatalog.all
        val currentId = themeStore.resolve().id
        val names = themes.map { theme ->
            if (theme.id == currentId) "● ${theme.selectionLabel}" else "○ ${theme.selectionLabel}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("美学主题 · 选择视觉风格")
            .setItems(names) { _, which ->
                val selected = themes[which]
                themeStore.setThemeId(selected.id)
                applyThemeToDashboard()
                showInstances()
                Toast.makeText(this, "已应用主题: ${selected.selectionLabel}", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("主题高级设置") { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            .setNegativeButton("取消", null)
            .show().also { ThemeApplier.apply(it) }
    }

    private fun applyThemeToInstances(theme: AppTheme) {
        val list = findViewById<LinearLayout>(R.id.instance_list) ?: return
        for (i in 0 until list.childCount) {
            val cardView = list.getChildAt(i)
            if (cardView is com.google.android.material.card.MaterialCardView) {
                cardView.setCardBackgroundColor(theme.surfaceTranslucent)
                cardView.strokeColor = (theme.glow and 0x00FFFFFF) or 0x30000000
                cardView.strokeWidth = (1 * resources.displayMetrics.density).toInt()
                cardView.findViewById<TextView>(R.id.card_distro_title)?.setTextColor(theme.text)
                cardView.findViewById<TextView>(R.id.card_instance_id)?.setTextColor(theme.textDim)
                cardView.findViewById<TextView>(R.id.card_hardware_specs)?.setTextColor(theme.textDim)
                cardView.findViewById<TextView>(R.id.card_ip_address)?.setTextColor(theme.textDim)
            }
        }
    }

    private fun buildGlowCardDrawable(theme: AppTheme): android.graphics.drawable.Drawable {
        val outer = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 0f
            setStroke(
                (1.5f * resources.displayMetrics.density).toInt(),
                (theme.glow and 0x00FFFFFF or 0x40000000.toInt()),
            )
            setColor(android.graphics.Color.TRANSPARENT)
        }
        val inner = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 0f
            setColor(theme.surfaceTranslucent)
        }
        val inset = (2f * resources.displayMetrics.density).toInt()
        return android.graphics.drawable.LayerDrawable(arrayOf(outer, inner)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun attachPressGlow(view: View, theme: AppTheme) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_HOVER_ENTER -> {
                    v.animate().scaleX(1.015f).scaleY(1.015f).setDuration(90).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL,
                android.view.MotionEvent.ACTION_HOVER_EXIT -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            false
        }
    }

    private fun renderGauges(theme: AppTheme) {
        val density = resources.displayMetrics.density
        val memory = findViewById<io.github.nyzeep.orchestella.ui.RingProgressView>(R.id.ring_memory)
        val storage = findViewById<io.github.nyzeep.orchestella.ui.RingProgressView>(R.id.ring_storage)
        val battery = findViewById<io.github.nyzeep.orchestella.ui.RingProgressView>(R.id.ring_battery)
        val cpuRing = findViewById<io.github.nyzeep.orchestella.ui.RingProgressView>(R.id.cpu_gauge_ring)
        val ramRing = findViewById<io.github.nyzeep.orchestella.ui.RingProgressView>(R.id.ram_gauge_ring)
        memory?.configure(theme.primary, theme.text, theme.textDim, density)
        storage?.configure(theme.accent, theme.text, theme.textDim, density)
        battery?.configure(theme.glow, theme.text, theme.textDim, density)
        cpuRing?.configure(theme.primary, theme.text, theme.textDim, density)
        ramRing?.configure(theme.accent, theme.text, theme.textDim, density)

        val profile = HostHardwareDetector.detect(this)
        val totalRamMb = profile.totalRamMb().coerceAtLeast(1)
        val availRamMb = profile.availRamMb().coerceIn(0, totalRamMb)
        val usedRam = totalRamMb - availRamMb
        memory?.setProgress(usedRam.toFloat() / totalRamMb,
            "${usedRam / 1024}G", "内存")
        ramRing?.setProgress(usedRam.toFloat() / totalRamMb,
            "${((usedRam.toFloat() / totalRamMb) * 100).toInt()}%", "RAM")
        cpuRing?.setProgress(1f, "${profile.totalCores()}", "核心")
        findViewById<TextView>(R.id.cpu_percent)?.text = "${profile.totalCores()}"
        findViewById<TextView>(R.id.tv_cpu_core_specs)?.text =
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        findViewById<TextView>(R.id.ram_metric)?.text =
            String.format(java.util.Locale.getDefault(), "%.1fG", usedRam / 1024.0)
        findViewById<TextView>(R.id.ram_cap_text)?.text =
            String.format(java.util.Locale.getDefault(), "已用 / 共 %.1f GB", totalRamMb / 1024.0)
        findViewById<TextView>(R.id.tv_ram_free_swap)?.text =
            String.format(java.util.Locale.getDefault(), "可用 %.1f GB · 每 3 秒刷新", availRamMb / 1024.0)

        val stat = android.os.StatFs(filesDir.path)
        val totalBytes = stat.totalBytes
        val freeBytes = stat.availableBytes
        val usedBytes = totalBytes - freeBytes
        storage?.setProgress(usedBytes.toFloat() / totalBytes.coerceAtLeast(1),
            "${usedBytes / (1024L * 1024L * 1024L)}G", "存储")

        val intent = registerForBattery()
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) {
            val pct = level.toFloat() / scale
            battery?.setProgress(pct, "${(pct * 100).toInt()}%", "电池")
            findViewById<TextView>(R.id.tv_status_bat)?.text = "BAT:${(pct * 100).toInt()}%"
        } else {
            battery?.setProgress(0f, "--", "电池")
            findViewById<TextView>(R.id.tv_status_bat)?.text = "BAT:--"
        }
        val temperature = intent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        findViewById<TextView>(R.id.tv_telemetry_temp)?.text =
            if (temperature != null && temperature != Int.MIN_VALUE)
                String.format(java.util.Locale.getDefault(), "电池 %.1f°C", temperature / 10.0)
            else "电池温度不可用"
        findViewById<TextView>(R.id.disk_iops)?.text =
            String.format(java.util.Locale.getDefault(), "存储余量 %.1f GB", freeBytes / (1024.0 * 1024 * 1024))
        findViewById<TextView>(R.id.tv_status_time)?.text = "[ ${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))} ]"
        val network = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = network.activeNetwork?.let(network::getNetworkCapabilities)
        val transport = when {
            capabilities == null -> "OFFLINE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
            else -> "在线"
        }
        findViewById<TextView>(R.id.tv_status_net)?.text = "NET:$transport"
    }

    private fun registerForBattery(): android.content.Intent? {
        return registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
    }

    private fun renderSystemStatus(assessment: DeviceCapabilityEvaluator.Assessment) {
        val profile = HostHardwareDetector.detect(this)
        val cpuChip = findViewById<TextView>(R.id.system_cpu_chip)
        val virtBadge = findViewById<TextView>(R.id.system_virtualization_badge)
        val statusText = findViewById<TextView>(R.id.runtime_status)

        cpuChip?.text = getString(R.string.system_cpu_badge, profile.totalCores())
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

        lastInstanceState = instances.joinToString { "${it.id()}:${it.state()}" }
        val emptyView = findViewById<TextView>(R.id.instances_empty)
        val countBadge = findViewById<TextView>(R.id.instances_count_badge)
        countBadge?.text = "${instances.size} 个实例"

        val runningInstance = instances.firstOrNull { it.state() == InstanceLifecycle.State.RUNNING }
        val primaryInstance = runningInstance ?: instances.firstOrNull { it.id() == settingsManager.lastActiveInstanceId } ?: instances.firstOrNull()
        updateConsoleWithInstance(primaryInstance)

        if (instances.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        } else {
            emptyView?.visibility = View.GONE
        }

        val supportsDesktop = false
        val strategyBadge = currentBadge(DeviceCapabilityEvaluator.evaluate(deviceProfile()))

        val inflater = LayoutInflater.from(this)
        for (instance in instances) {
            val cardView = inflater.inflate(R.layout.layout_instance_card, instanceList, false)
            val session = ExecutionSessionRegistry.get(instance.id())
            val runningStrategy = when (session) {
                is QemuInstanceLauncher.Session -> "QEMU 软件虚拟化"
                is AvfInstanceLauncher.AvfSession -> "AVF 硬件虚拟化"
                else -> strategyBadge
            }
            val guestIp = (session as? QemuInstanceLauncher.Session)?.let { NetworkGatewayDaemon.GUEST_IP }
            val uiModel = InstanceCardUiModel.from(instance, runningStrategy, supportsDesktop, guestIp)

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

            val currentTheme = activeTheme ?: themeStore.resolve()
            if (cardView is com.google.android.material.card.MaterialCardView) {
                cardView.setCardBackgroundColor(currentTheme.surfaceTranslucent)
                cardView.strokeColor = (currentTheme.glow and 0x00FFFFFF) or 0x30000000
                cardView.strokeWidth = (1 * resources.displayMetrics.density).toInt()
                distroTitle?.setTextColor(currentTheme.text)
                instanceIdText?.setTextColor(currentTheme.textDim)
                hardwareSpecs?.setTextColor(currentTheme.textDim)
                ipAddress?.setTextColor(currentTheme.textDim)
                attachPressGlow(cardView, currentTheme)
            }

            instanceList.addView(cardView)
            ThemeApplier.apply(cardView, currentTheme)
        }
    }

    private fun showPowerDialog(instance: InstanceLifecycle.Instance) {
        val options = arrayOf("正常关机", "强制断电（可能丢失未写入的数据）")
        AlertDialog.Builder(this)
            .setTitle("电源管理 · ${instance.hostname()}")
            .setItems(options) { _, which ->
                if (which == 1) {
                    AlertDialog.Builder(this)
                        .setTitle("强制关闭实例")
                        .setMessage("这会直接停止虚拟机。建议先在 Linux 中保存文件并正常关机。")
                        .setPositiveButton("确认断电") { _, _ -> requestInstanceStop(instance, true) }
                        .setNegativeButton("取消", null)
                        .show().also { ThemeApplier.apply(it) }
                } else {
                    requestInstanceStop(instance, false)
                }
            }
            .setNegativeButton("取消", null)
            .show().also { ThemeApplier.apply(it) }
    }

    private fun requestInstanceStop(instance: InstanceLifecycle.Instance, force: Boolean) {
        val requestedSession = ExecutionSessionRegistry.get(instance.id())
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = requestedSession ?: throw IOException("实例已离线")
                if (ExecutionSessionRegistry.get(instance.id()) !== session) throw IOException("运行会话已变化")
                val intent = if (force) PersistentTerminalSessionPool.StopIntent.FORCE
                    else PersistentTerminalSessionPool.StopIntent.GRACEFUL
                ExecutionSessionRegistry.requestStop(instance.id(), session, intent) {
                    if (force) {
                        session.terminate()
                    } else {
                        val client = session.bridgeClient()
                            ?: throw IOException("控制通道尚未就绪，请在终端执行 poweroff")
                        if (!client.isReady) throw IOException("Linux 控制通道仍在初始化，请稍后再试")
                        val result = client.power(io.github.nyzeep.orchestella.runtime.contract.BridgeControl.PowerAction.SHUTDOWN)
                        if (!result.accepted()) throw IOException("Linux 拒绝关机请求")
                    }
                }
                withContext(Dispatchers.Main) {
                    createStatus.text = "关机请求已发送，等待实例退出"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "操作失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isHeadless(instance: InstanceLifecycle.Instance): Boolean {
        // The production channel does not yet carry guest frames and input.
        return true
    }

    private fun showHeadlessDesktopDialog(instance: InstanceLifecycle.Instance) {
        AlertDialog.Builder(this)
            .setTitle("图形桌面通道尚未就绪")
            .setMessage("当前基础 Linux 环境可在终端使用。App 的真实 guest 图形帧与输入通道仍在独立 Phase 5 开发中，安装桌面软件包后也需要该通道才能在 App 内交互。")
            .setPositiveButton(R.string.instance_open_terminal) { _, _ ->
                openTerminal(instance)
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show().also { ThemeApplier.apply(it) }
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
            .show().also { ThemeApplier.apply(it) }
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
                exportStoppedInstance(instance)
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
            .show().also { ThemeApplier.apply(it) }
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
            .show().also { ThemeApplier.apply(it) }
    }

    private fun reconcileOrphanInstances() {
        if (!::instanceStore.isInitialized) return
        try {
            val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
            for (instance in instanceStore.list()) {
                if (InstanceLaunchGuard.isInFlight(instance.id())) {
                    continue
                }
                if (instance.state() == InstanceLifecycle.State.RUNNING && ExecutionSessionRegistry.get(instance.id()) == null) {
                    controller.reconcileAfterHostDeath(instance.id(), InstanceLifecycle.State.INTERRUPTED)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun launchInstance(instance: InstanceLifecycle.Instance) {
        setCreateButtonsEnabled(false)
        createStatus.setText(R.string.launch_started)
        operations.execute {
            // A live session means the VM is already running: launching again would fork a
            // second QEMU that fails to bind the instance's sockets AND unregister the live
            // session in the failure path (observed on-device as "无法输入指令"). Attach instead.
            if (ExecutionSessionRegistry.get(instance.id()) != null) {
                runOnUiThread {
                    setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                    createStatus.text = "已连接到运行中的实例"
                    openTerminal(instance)
                }
                return@execute
            }
            reconcileOrphanInstances()
            val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
            var running = false
            var launchGeneration: String? = null
            var acceleratorCoordinator: DualLaneCoordinator? = null
            try {
                DistroResourceRequirements.requireBootRam(instance.distro().id(), instance.hardwareConfig().ramMegabytes())
                launchGeneration = InstanceLaunchGuard.begin(instance.id())
                if (launchGeneration == null) {
                    runOnUiThread {
                        createStatus.text = "实例正在启动，请稍后再试"
                        setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                    }
                    return@execute
                }
                InstanceLaunchSequence.beforeLaunch(controller, instance)
                running = true
                val distroTitle = instance.distro().id().replaceFirstChar { it.uppercase() } + " " + instance.distro().version()
                val badge = currentBadge(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
                settingsManager.lastActiveInstanceId = instance.id()
                OrchestellaForegroundService.start(this, instance.id(), distroTitle, badge,
                    settingsManager.batteryPolicy, launchGeneration)
                val rootfsFileName = DistroCatalogRegistry.getInstance().get(instance.distro().id())?.rootfsFileName()
                    ?: if (instance.distro().id() == "alpine") "alpine-minirootfs-3.24.1-aarch64.tar.gz"
                    else "${instance.distro().id()}-rootfs.tar.xz"
                val mirrorConfigFile = filesDir.resolve("instances").resolve("${instance.id()}.mirror")
                val firstbootFile = filesDir.resolve("instances").resolve("${instance.id()}.firstboot")
                val mirrorConfigName = DistroMirrorProvisioner.getConfigFileName(instance.distro().id())
                val mirrorBytes = if (mirrorConfigFile.exists()) Files.readAllBytes(mirrorConfigFile.toPath()) else null
                val firstbootBytes = if (firstbootFile.exists()) {
                    Files.readAllBytes(firstbootFile.toPath())
                } else FirstBootProvisionPipeline.generateScriptForDistro(instance.distro().id())
                    .toByteArray(Charsets.UTF_8)

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
                    provisionPayload,
                    instance.hostname(),
                    instance.defaultUser(),
                    instance.hasSudo()
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
                val dispatchResult = AcceleratorLaunchDispatcher.dispatch(
                    BuildConfig.ACCELERATOR_LANE_ENABLED,
                    parameters,
                    { params -> QemuInstanceLauncher(this).launch(params) },
                    {
                        AcceleratorVmLauncher(
                            "accel-" + instance.id(),
                            filesDir.resolve("accelerator-image").resolve("kernel").toPath(),
                            filesDir.resolve("accelerator-image").resolve("initrd").toPath(),
                            filesDir.resolve("accelerator-image").resolve("rootfs.ext4").toPath(),
                            AcceleratorLaunchDispatcher.ACCEL_REQUEST.ramMiB,
                        ) {
                            AcceleratorVmLauncher.engine(
                                AvfVirtualMachineEngine(
                                    ReflectiveAvfVirtualMachineApi(
                                        this, classLoader, AvfHiddenApiExemption::installOnce,
                                    ),
                                ),
                            )
                        }
                    },
                    AndroidAcceleratorFactProvider.create(this),
                    { ExecutionSessionRegistry.get(instance.id()) },
                    "",
                    LaneClock.system(),
                    { event ->
                        android.util.Log.i(
                            "OrchestellaAccelerator",
                            "${event.name()} ${event.lane()} ${event.code()} ${event.durationMs()}ms",
                        )
                    },
                ) { strategyLauncher.launch(parameters, preferAvf) }
                acceleratorCoordinator = dispatchResult.coordinator
                if (dispatchResult.coordinator != null) {
                    android.util.Log.i("OrchestellaAccelerator", dispatchResult.decisionJson)
                }
                val outcome = dispatchResult.outcome
                val session = outcome.session
                val sessionGeneration = ExecutionSessionRegistry.generation(instance.id(), session)
                    ?: throw IOException("运行会话已变化")
                Thread {
                    try {
                        val exit = InstanceLaunchSequence.awaitGuestExit(controller, instance.id(), session) {
                            OrchestellaForegroundService.stop(this, instance.id(), sessionGeneration)
                        }
                        // The guest session is over (or no longer ours): the
                        // accelerator lane must not outlive the launch that
                        // armed it. A WAIT_FAILED keeps both lanes untouched.
                        if (exit.status() != InstanceLaunchSequence.ExitStatus.WAIT_FAILED) {
                            acceleratorCoordinator?.close()
                        }
                        if (exit.status() != InstanceLaunchSequence.ExitStatus.STALE) runOnUiThread {
                            if (!InstanceLaunchGuard.isLatest(instance.id(), sessionGeneration)) return@runOnUiThread
                            createStatus.text = when (exit.status()) {
                                InstanceLaunchSequence.ExitStatus.NORMAL -> getString(R.string.launch_guest_exited)
                                InstanceLaunchSequence.ExitStatus.CRASHED -> "实例异常退出（退出码 ${exit.exitCode()}），可查看诊断并冷恢复"
                                InstanceLaunchSequence.ExitStatus.STOP_UNCONFIRMED ->
                                    "停止未确认：运行时未在时限内停止，实例仍保留，请检查运行日志"
                                else -> "无法确认虚拟机退出，已保留运行会话，请检查运行状态"
                            }
                            setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
                            showInstances()
                        }
                    } catch (failure: Exception) {
                        // The watcher only throws after the guest was observed
                        // exiting, so the QEMU runtime is already gone; the
                        // accelerator lane must not survive it unattended.
                        acceleratorCoordinator?.close()
                        runOnUiThread {
                            if (!InstanceLaunchGuard.isLatest(instance.id(), sessionGeneration)) return@runOnUiThread
                            createStatus.text = getString(R.string.launch_failed, failure.javaClass.simpleName)
                        }
                    }
                }.apply { isDaemon = true }.start()
                OrchestellaForegroundService.start(this, instance.id(), distroTitle,
                    if (outcome.hardwareAccelerated()) "AVF 硬件虚拟化" else "QEMU 软件虚拟化",
                    settingsManager.batteryPolicy, sessionGeneration)
                runOnUiThread {
                    if (!InstanceLaunchGuard.isLatest(instance.id(), sessionGeneration)
                        || ExecutionSessionRegistry.get(instance.id()) !== session) return@runOnUiThread
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
                val failedGeneration = launchGeneration
                // A failed launch may have left a runtime whose stop was not
                // confirmed; keep RUNNING metadata and the foreground binding in
                // that case instead of pretending the instance crashed.
                acceleratorCoordinator?.close()
                val cleanupResult = QemuInstanceLauncher.takeLastCleanupResult(instance.id())
                try {
                    InstanceLaunchSequence.afterLaunchFailure(
                        controller, instance.id(), failedGeneration, running, cleanupResult
                    ) {
                        OrchestellaForegroundService.stop(this, instance.id(), failedGeneration)
                    }
                } catch (_: Exception) {
                    // A metadata/observer failure is not evidence of VM death; retain its owner.
                }
                runOnUiThread {
                    if (failedGeneration != null && !InstanceLaunchGuard.isLatest(instance.id(), failedGeneration)) return@runOnUiThread
                    createStatus.text = getString(R.string.launch_failed, failure.javaClass.simpleName)
                    Toast.makeText(this, createStatus.text, Toast.LENGTH_LONG).show()
                    android.util.Log.w("OrchestellaLaunch", "launch failed type=${failure.javaClass.simpleName}")
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
            PackagedAssetStreams.openAsset(this, RuntimeArtifactManifest.SCHEMA_FILE_NAME).use { input ->
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
        val password = instance.guestPassword()
        val message = "主机名：${instance.hostname()}\n用户名：${instance.defaultUser()}\n密码：${password.ifBlank { "未记录，请在 Linux 中使用 passwd 设置" }}\n\n新实例使用独立随机凭据。修改密码后请妥善保存。"
        AlertDialog.Builder(this)
            .setTitle(R.string.credentials_title)
            .setMessage(message)
            .setPositiveButton(R.string.credentials_copy) { _, _ ->
                if (password.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GuestPassword", password))
                    Toast.makeText(this, R.string.credentials_copied, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.credentials_change) { _, _ ->
                showChangePasswordDialog(instance)
            }
            .setNegativeButton(R.string.credentials_close, null)
            .show().also { ThemeApplier.apply(it) }
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
            .show().also { ThemeApplier.apply(it) }
    }

    private fun showRefusalDetailsDialog(assessment: DeviceCapabilityEvaluator.Assessment) {
        AlertDialog.Builder(this)
            .setTitle(R.string.refusal_dialog_title)
            .setMessage(getString(R.string.refusal_dialog_message, assessment.refusalReason()))
            .setPositiveButton(R.string.credentials_close, null)
            .show().also { ThemeApplier.apply(it) }
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
            text = getString(R.string.settings_update_check_desc) + "\n" +
                GitHubUpdateChecker.channelForVersion(BuildConfig.VERSION_NAME).label()
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
            .show().also { ThemeApplier.apply(it) }
    }

    private fun checkForUpdateManual() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        operations.execute {
            val checker = GitHubUpdateChecker(settingsManager)
            val versionName = BuildConfig.VERSION_NAME
            val updateChannel = GitHubUpdateChecker.channelForVersion(versionName).label()
            val result = checker.checkForUpdate(versionName)
            runOnUiThread {
                when (result.status()) {
                    GitHubUpdateChecker.Status.DISABLED -> {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.settings_update_check)
                            .setMessage("$updateChannel\n" + getString(R.string.update_disabled_msg))
                            .setPositiveButton(R.string.credentials_close, null)
                            .show().also { ThemeApplier.apply(it) }
                    }
                    GitHubUpdateChecker.Status.UP_TO_DATE -> {
                        AlertDialog.Builder(this)
                            .setTitle(R.string.settings_update_check)
                            .setMessage("$updateChannel\n" + getString(R.string.update_up_to_date, result.latestVersion() ?: versionName))
                            .setPositiveButton(R.string.credentials_close, null)
                            .show().also { ThemeApplier.apply(it) }
                    }
                    GitHubUpdateChecker.Status.UPDATE_AVAILABLE -> {
                        AlertDialog.Builder(this)
                            .setTitle(getString(R.string.update_available_title, result.latestVersion()))
                            .setMessage("$updateChannel\n" + getString(R.string.update_available_msg, result.releaseNotes()))
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
                            .show().also { ThemeApplier.apply(it) }
                    }
                    GitHubUpdateChecker.Status.ERROR -> {
                        Toast.makeText(this, "$updateChannel：" + (result.errorMessage() ?: "检查失败"), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
