package io.github.nyzeep.orchestella

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import android.view.ViewGroup
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
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorMode
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorPolicy
import io.github.nyzeep.orchestella.ui.TouchTargets
import io.github.nyzeep.orchestella.ui.motion.OrchestellaMotion
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.capability.DeviceCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.distro.OfficialDebianCatalog
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.SurfaceOpacity
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import io.github.nyzeep.orchestella.ui.theme.ThemeColors
import io.github.nyzeep.orchestella.runtime.engineavf.AvfVirtualMachineEngine
import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfVirtualMachineApi
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.startup.DashboardSnapshot
import io.github.nyzeep.orchestella.startup.RuntimeVerification
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEventSink
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException
import io.github.nyzeep.orchestella.runtime.instance.SparseDiskResizer
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore
import io.github.nyzeep.orchestella.runtime.network.PortConflictDetector
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager
import java.io.IOException
import java.nio.file.Files
import java.time.Clock
import java.util.concurrent.Executors
import java.util.function.BooleanSupplier

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
    private lateinit var launchCoordinator: InstanceLaunchCoordinator
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
    private lateinit var updateCenter: io.github.nyzeep.orchestella.update.UpdateCenter
    private var aboutUpdateBadge: View? = null
    private var activeTheme: AppTheme? = null
    private var wizardModel: AvfAuthorizationWizardModel? = null
    private var pendingExportInstance: InstanceLifecycle.Instance? = null
    private var currentTabIndex = 0
    /** False until the first switchTab painted every tab; later switches repaint the changed pair. */
    private var tabsInitialized = false
    /**
     * T2 (2026-11-08) 动效守卫：每次带动画的页签切换自增一次；离场容器的延迟
     * GONE 定时器仅在世代未变、且该容器不再是当前页签时才落地。快速连点时，
     * 回切容器的 stateChangeEnter 会重置 alpha 并接管动画，旧退场定时器因世代
     * 不匹配而失效——与 InstanceDetailActivity 五页签的既有守卫同一写法，
     * 保证不留半透明残影。
     */
    private var tabSwitchGeneration = 0
    private var dashboardInstance: InstanceLifecycle.Instance? = null
    private var sshCommand: String? = null
    private var lastInstanceState = ""
    private var lastAssessment: DeviceCapabilityEvaluator.Assessment? = null
    private var runtimeStatusLine: String? = null
    private val dashboardHandler = Handler(Looper.getMainLooper())

    // Lane A（2026-11-13）：随行 UML face 的 Android 侧绑定。face 经装配槽的
    // spawner 注入重载构造（drain 线程消费内置 initramfs 的 §4 契约串），生命
    // 周期由 InstanceLaunchCoordinator 的 UmlFaceLane 端口驱动；日志沿
    // OrchestellaUml 家族 tag（UmlFaceKeepAliveService 既有 tag），不发明新 tag。
    private val umlFaceConsoleLog: (String) -> Unit = { line ->
        android.util.Log.i("OrchestellaUml", line)
    }
    private val umlFaceConsoleSink = UmlFaceConsoleSink(
        faceSource = { UmlFaceStatusRegistry.current()?.face },
        log = umlFaceConsoleLog,
    )
    private val umlConsoleSpawner = ConsoleDrainingUmlSpawner(umlFaceConsoleSink)
    // Gate 1（2026-11-13，编译桥 D-1 接线）：第四 flag（orchestella.uml.
    // compileBridge）为 off 时 wiring 全程惰性——arm 日志保持逐字节 channel=
    // unwired、无 submitter listener；为 on 时 arm 供给 UML-TCP lane 通道
    // （UmlChannels.overTcpLane 经装配槽 6 参重载入缝），arm 日志翻转为
    // channel=wired。launch 形态（fastProfile）与可路由（compileBridge）是
    // 两个独立 flag，互不推导。
    private val umlCompileBridge = UmlCompileBridgeWiring(
        enabled = BuildConfig.UML_COMPILE_BRIDGE,
        log = umlFaceConsoleLog,
    )
    private val umlFaceRideAlong = UmlFaceRideAlong(
        backend = { _, gate, channel ->
            if (channel != null) {
                AcceleratorLaneAssembly.umlFace(
                    gate,
                    UmlFaceKeepAliveService.environment(this),
                    // 内置 initramfs 探针形态：512M / host-1 单核（fast 形态语义宽 1）；
                    // 命令行即 fastProfileCommand 锁定输出，零 extraArgs。
                    UmlFaceLauncher.LaunchParams.defaults(),
                    UmlFaceKeepAliveService.keepAlive(this),
                    umlConsoleSpawner,
                    channel,
                )
            } else {
                AcceleratorLaneAssembly.umlFace(
                    gate,
                    UmlFaceKeepAliveService.environment(this),
                    // 内置 initramfs 探针形态：512M / host-1 单核（fast 形态语义宽 1）；
                    // 命令行即 fastProfileCommand 锁定输出，零 extraArgs。
                    UmlFaceLauncher.LaunchParams.defaults(),
                    UmlFaceKeepAliveService.keepAlive(this),
                    umlConsoleSpawner,
                )
            }
        },
        compileBridge = umlCompileBridge,
        log = umlFaceConsoleLog,
    )

    companion object {
        /**
         * 进程级一次性门（协同落点，2026-11-07 设备窗实测：VM 启动时弹权限窗打断）。
         * 通知权限改为进程首个首帧后请求一次；不再挂在每次 VM 启动路径上。
         */
        private val notificationPromptShownThisProcess = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private val refreshDashboard = object : Runnable {
        override fun run() {
            if (!::instanceStore.isInitialized || isFinishing || isDestroyed) return
            // 冷启动/主线程卫生（Lane C）：3 秒周期的实例列表与硬件/存储读数改在
            // operations executor 上取齐，UI 线程只渲染快照；读取失败按空列表继续。
            loadDashboardSnapshot(strict = false) { snapshot ->
                val theme = activeTheme ?: themeStore.resolve()
                renderGauges(theme, snapshot)
                if (snapshot.stateKey != lastInstanceState) {
                    renderInstances(snapshot)
                } else {
                    updateConsoleWithInstance(snapshot.primary, snapshot.ssh)
                }
                if (currentTabIndex == 2) renderHardwareSpecDetails()
                maybeReportFullyDrawn()
                maybePlayDashboardEntry()
            }
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
        // 冷启动主线程卫生（Lane C）：孤儿回收是实例目录遍历 + 可能的状态写盘，
        // 迁到既有 operations executor；后续 showInstances() 的快照任务在同一
        // 单线程队列上排队，仍看到回收后的状态（与原同步顺序一致）。
        operations.execute { reconcileOrphanInstances() }

        // InstanceLaunchCoordinator（2026-11-10 加深轮 T1）：启动编排收进深模块，构造注入
        // 资源句柄；Android 侧启动件（前台服务、AVF/QEMU/PVM 启动器、事实源、资源串）在此
        // 绑定，编排决策与世代门卫全部在 InstanceLaunchCoordinator 内。
        launchCoordinator = InstanceLaunchCoordinator(
            store = instanceStore,
            settings = launchSettings,
            foreground = launchForeground,
            ui = launchFeedback,
            copy = launchCopy,
            diagnostics = launchDiagnostics,
            cleanupBridge = InstanceLaunchCoordinator.CleanupBridge { instanceId ->
                QemuInstanceLauncher.takeLastCleanupResult(instanceId)
            },
            filesDir = filesDir,
            performanceBadge = { currentBadge(DeviceCapabilityEvaluator.evaluate(deviceProfile())) },
            preferAvf = BooleanSupplier {
                avfReadiness?.status() == AvfCapabilityEvaluator.AvfStatus.READY
            },
            avfLaunch = InstanceStrategyLauncher.AvfLaunch { params ->
                AvfInstanceLauncher(this).launch(params)
            },
            qemuLaunch = InstanceStrategyLauncher.QemuLaunch { params ->
                QemuInstanceLauncher(this).launch(params)
            },
            customVmBackend = InstanceLaunchCoordinator.CustomVmBackend { instanceId ->
                AcceleratorVmLauncher(
                    "accel-" + instanceId,
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
            pvmPayloadBackend = InstanceLaunchCoordinator.PvmPayloadBackend { instanceId ->
                // Built only when the verdict selected PVM_PAYLOAD,
                // which already implies the manifest parsed. An
                // absent payload at that point is a load failure,
                // never a silent QEMU-only run.
                PvmPayloadLaneStarter.createIfInstalled(
                    this,
                    "accel-pvm-" + instanceId,
                    AcceleratorLaunchDispatcher.GUEST_COMPILER_TRIPLE,
                    AcceleratorLaunchDispatcher.PAYLOAD_READY_TIMEOUT_MS,
                    AcceleratorLaunchDispatcher.STOP_GRACE_MS,
                ).orElseThrow {
                    LaneStartException(PvmPayloadLaneStarter.PAYLOAD_LOAD_FAILED)
                }
            },
            factsSource = InstanceLaunchCoordinator.FactsSource {
                AndroidAcceleratorFactProvider.create(this)
            },
            payloadManifestSource = InstanceLaunchCoordinator.PayloadManifestSource {
                AndroidAcceleratorFactProvider.readPayloadManifest(applicationContext)
            },
            laneEventSink = LaneEventSink { event ->
                android.util.Log.i(
                    "OrchestellaAccelerator",
                    "${event.name()} ${event.lane()} ${event.code()} ${event.durationMs()}ms",
                )
            },
            laneClock = LaneClock.system(),
            clock = Clock.systemUTC(),
            orphanReconciler = Runnable { reconcileOrphanInstances() },
            umlFaceLane = umlFaceRideAlong,
        )

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.about_button)?.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        updateCenter = io.github.nyzeep.orchestella.update.UpdateCoordinator.center(this)
        aboutUpdateBadge = findViewById(R.id.about_update_badge)
        aboutUpdateBadge?.let { ThemeApplier.setRole(it, ThemeApplier.Role.PRESERVE) }
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
        setupUmlRootfsCard()

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
        scheduleRuntimeVerification()
        requestNotificationPermissionAfterFirstFrame()
        handleLaunchIntent(intent)
    }

    /**
     * 协同落点（Lane C 落地 / Lane D 规格，2026-11-07）：通知权限从「每次 VM 启动时
     * 弹窗打断」提前到「进程首个首帧之后」请求一次。权限语义不变：仍走
     * [NotificationPermissionPrompt.requestIfNeeded]（API < 33 与已授权时为 no-op），
     * 只是不再占用启动/开机流程；用户拒绝后依赖系统的拒绝记忆，进程内不重复打扰。
     */
    private fun requestNotificationPermissionAfterFirstFrame() {
        findViewById<View>(R.id.root_container)?.post {
            if (isFinishing || isDestroyed) return@post
            if (!notificationPromptShownThisProcess.compareAndSet(false, true)) return@post
            NotificationPermissionPrompt.requestIfNeeded(this)
        }
    }

    /**
     * ReportFullyDrawn 接线（Lane D, 2026-11-10）：启动链路的「首帧数据齐备点」=
     * 第一份仪表盘快照在 UI 线程分发（实例列表/主实例/仪表读数渲染输入就绪）。
     * 设备实测锚点（2026-11-08 T5）：700ms 帧仍是默认占位态，~1.6s 完成回调
     * 填充——以该点上报，fully drawn 指标覆盖到真实数据呈现而非空骨架。
     * 实现走 root_container.post：上报排进本轮 traversal 之后，贴近首帧呈现；
     * 布尔门保证每次 Activity 实例只报一次；strict 失败路径（错误态同样是
     * 「可用」状态）也上报，指标不悬挂。
     */
    private var fullyDrawnReported = false

    private fun maybeReportFullyDrawn() {
        if (fullyDrawnReported || isFinishing || isDestroyed) return
        fullyDrawnReported = true
        findViewById<View>(R.id.root_container)?.post {
            if (isFinishing || isDestroyed) return@post
            try {
                reportFullyDrawn()
            } catch (failure: Exception) {
                // 个别 ROM 在窗口第一帧完成前上报会抛异常——指标接线永不影响功能路径。
                android.util.Log.w("MainActivity", "reportFullyDrawn rejected", failure)
            }
        }
    }

    /**
     * 仪表盘卡片差异化进场（Lane D, 2026-11-10）：控制台卡组（横幅/机器卡/
     * 仪表/动作区/TTY）在首个快照渲染落位时播一次 fade+scale-settle 级联，
     * 与实例列表的 rise 进场（OrchestellaMotion.playListEntryStagger）刻意
     * 区分词汇。守卫：每次 Activity 实例只在首个快照渲染尝试一次，且仅在
     * 控制台页签可见时真的播放——恢复到其他页签时不武装（页签到达由容器级
     * stateChangeEnter 覆盖，主题重绘保持瞬时，T2 契约），3 秒刷新循环因此
     * 永不重播。
     */
    private var dashboardEntryPlayed = false

    private fun maybePlayDashboardEntry() {
        if (dashboardEntryPlayed || isFinishing || isDestroyed) return
        dashboardEntryPlayed = true
        if (currentTabIndex != 0) return
        findViewById<ViewGroup>(R.id.container_tab_console)?.let(OrchestellaMotion::playDashboardEntry)
    }

    /**
     * 冷启动最大单点 IO：QEMU 运行时完整性校验（打开 APK ZipFile + 对
     * libdroidlayer_qemu.so 全量 SHA-256）。迁到 operations executor 并进程级
     * 记忆化；期间状态行先渲染其余内容，校验完成后补齐该行（见
     * [renderSystemStatus]）。判定语义不变，只改计算线程与首次渲染时机。
     */
    private fun scheduleRuntimeVerification() {
        RuntimeVerification.verifyAsync(
            compute = { RuntimeVerification.computeProduction(applicationContext) },
            executor = operations,
        ) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                runtimeStatusLine = when (result) {
                    is RuntimeVerification.Result.Verified -> getString(R.string.qemu_runtime_verified)
                    is RuntimeVerification.Result.Unavailable ->
                        getString(R.string.qemu_runtime_unavailable, result.reason)
                }
                lastAssessment?.let { renderSystemStatus(it) }
            }
        }
    }

    private fun setupNavigationTabs() {
        findViewById<View>(R.id.nav_tab_console)?.setOnClickListener { switchTab(0) }
        findViewById<View>(R.id.nav_tab_instances)?.setOnClickListener { switchTab(1) }
        findViewById<View>(R.id.nav_tab_hardware)?.setOnClickListener { switchTab(2) }
        findViewById<View>(R.id.nav_tab_distros)?.setOnClickListener { switchTab(3) }
        findViewById<View>(R.id.nav_tab_settings)?.setOnClickListener { switchTab(4) }
    }

    /**
     * @param forceAll repaint every tab even when its index did not change. The theme
     *   reapply path needs it; a user tap only changes two tabs (the one leaving and
     *   the one entering), so repainting the other three is wasted invalidation that
     *   made tab switches the worst scenario in the session-19 gfxinfo baseline.
     */
    private fun switchTab(tabIndex: Int, forceAll: Boolean = false) {
        val previousTabIndex = currentTabIndex
        currentTabIndex = tabIndex
        val theme = activeTheme ?: themeStore.resolve()
        val activeColor = theme.primary
        val inactiveBg = surfaceColor(theme)
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
        } catch (_: Exception) {
            // Decorative status-bar clock: a formatting failure just leaves the time
            // label untouched for this repaint.
        }

        // T2 (2026-11-08) Lane motion consumption: on a user-driven switch the
        // leaving container fades out (OrchestellaMotion.stateChangeExit) before
        // it is GONE-ed and the arriving one rises in (stateChangeEnter) — the
        // console/list state swap hook named in that KDoc. First paint and
        // theme repaints (forceAll) stay instant. The generation counter stops a
        // stale exit timer from GONE-ing a container that was switched back to
        // mid-fade, and the sweep below GONEs every other container so repeated
        // fast switches can never stack invisible-but-tappable tab layers
        // (same guard pattern as InstanceDetailActivity's five detail tabs).
        val animateSwitch = tabsInitialized && !forceAll && previousTabIndex != tabIndex
        if (animateSwitch) tabSwitchGeneration++
        val switchGeneration = tabSwitchGeneration
        if (animateSwitch) {
            tabs.forEachIndexed { index, (containerId, _, _) ->
                if (index != previousTabIndex && index != tabIndex) {
                    findViewById<View>(containerId)?.visibility = View.GONE
                }
            }
        }

        tabs.forEachIndexed { index, (containerId, navTabId, navElements) ->
            // Skip untouched tabs after the first paint; their colors already match.
            if (!forceAll && tabsInitialized && index != previousTabIndex && index != tabIndex) {
                return@forEachIndexed
            }
            val container = findViewById<View>(containerId)
            val navTab = findViewById<View>(navTabId)
            val icon = findViewById<ImageView>(navElements.first)
            val text = findViewById<TextView>(navElements.second)
            navTab?.isSelected = index == tabIndex
            navTab?.let { ThemeApplier.setRole(it, ThemeApplier.Role.NAVIGATION) }

            if (index == tabIndex) {
                container?.visibility = View.VISIBLE
                if (animateSwitch) {
                    container?.let(OrchestellaMotion::stateChangeEnter)
                }
                navTab?.setBackgroundColor(activeColor)
                icon?.imageTintList = android.content.res.ColorStateList.valueOf(ThemeColors.onColor(activeColor))
                text?.setTextColor(ThemeColors.onColor(activeColor))
                text?.paint?.isFakeBoldText = true
            } else {
                if (animateSwitch && container != null) {
                    OrchestellaMotion.stateChangeExit(container)
                    container.postDelayed({
                        if (switchGeneration == tabSwitchGeneration && index != currentTabIndex) {
                            container.visibility = View.GONE
                        }
                    }, OrchestellaMotion.DURATION_EXIT_MS + 16L)
                } else {
                    container?.visibility = View.GONE
                }
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
        tabsInitialized = true
    }

    private fun setupConsoleControls() {
        findViewById<View>(R.id.seg_pkvm)?.setOnClickListener {
            when (avfReadiness?.status()) {
                AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> {
                    showAvfAuthorizationWizard()
                }
                AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED -> {
                    // Session-18 W-B: unsupported → neutral compatibility
                    // explanation page, not a dead-end dialog.
                    startActivity(Intent(this, CompatExplanationActivity::class.java))
                }
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

        // Lane A（2026-11-13）：第三段「UML 实验」是状态+导航条的状态项，不是
        // 偏好选择器——与 seg_pkvm 的 AVF 模式同一语义（状态文案+诚实解释，
        // 就绪才进硬件页）。gate 即双门（构建 flag + 用户设置），两层分开说。
        findViewById<View>(R.id.seg_uml)?.setOnClickListener {
            when {
                !BuildConfig.ACCELERATOR_LANE_ENABLED -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("UML 实验编译面")
                        .setMessage("当前构建未包含加速车道组件，UML 实验编译面不可用。\n应用继续使用完整 QEMU 路径运行 Linux 实例。")
                        .setPositiveButton("了解", null)
                        .show()
                }
                !settingsManager.isAcceleratorLaneEnabled() -> {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("UML 实验编译面")
                        .setMessage("UML 实验编译面随加速车道总开关关闭而停用（你在设置中关闭）。\n应用继续使用完整 QEMU 路径运行 Linux 实例。")
                        .setPositiveButton("了解", null)
                        .show()
                }
                else -> switchTab(2)
            }
        }

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
        val themeRowIds = listOf(
            R.id.btn_theme_mascot, R.id.btn_theme_constructivist_red,
            R.id.btn_theme_constructivist_blue, R.id.btn_theme_constructivist_yellow,
        )
        listOf(
            Triple(R.id.btn_theme_mascot, ThemeCatalog.mascotLavender.id, R.id.badge_theme_mascot_active),
            Triple(R.id.btn_theme_constructivist_red, "constructivist-workstation", R.id.badge_theme_red_active),
            Triple(R.id.btn_theme_constructivist_blue, "constructivist-cobalt", R.id.badge_theme_blue_active),
            Triple(R.id.btn_theme_constructivist_yellow, "constructivist-amber", R.id.badge_theme_yellow_active)
        ).forEach { (rowId, themeId, _) ->
            findViewById<LinearLayout>(rowId)?.getChildAt(0)?.let {
                ThemeApplier.setRole(it, ThemeApplier.Role.PRESERVE)
                val preview = ThemeCatalog.all.first { it.id == themeId }
                it.setBackgroundColor(preview.primary)
            }
        }
        // W3 walk-through: the 46dp theme rows leave 6dp dead gaps, and the text
        // node alone is far under the 48dp minimum. Grow the rows' hit areas via a
        // container touch delegate instead of changing XML geometry, so the visual
        // rhythm stays byte-identical while every row's target reaches ≥48dp and
        // the gaps between rows stop eating taps.
        val themeRows = themeRowIds.mapNotNull { findViewById<View>(it) }
        val themeRowContainer = (themeRows.firstOrNull()?.parent as? ViewGroup)
        if (themeRowContainer != null && themeRows.size == themeRowIds.size) {
            themeRowContainer.post {
                TouchTargets.installStackedRowTargets(
                    themeRowContainer, themeRows, themeRowContainer.resources.displayMetrics.density)
            }
        }
        findViewById<View>(R.id.btn_create_linux_instance_top)?.setOnClickListener {
            switchTab(3)
        }
        findViewById<View>(R.id.btn_theme_mascot)?.setOnClickListener {
            themeStore.setThemeId(ThemeCatalog.mascotLavender.id)
            applyThemeToDashboard()
            switchTab(4)
            Toast.makeText(this, "已应用主题: 看板娘 · 淡紫工控", Toast.LENGTH_SHORT).show()
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

    /**
     * @param sshPrefetch 非 null 表示 SSH 规则已在后台快照中取过（command 可为 null=
     *   无规则），UI 线程不得再读规则文件；null 保持旧的自取路径，仅供未迁移调用方兜底。
     */
    private fun updateConsoleWithInstance(
        instance: InstanceLifecycle.Instance?,
        sshPrefetch: DashboardSnapshot.SshPrefetch? = null,
    ) {
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
                // Session-18 W-B (user-approved): a neutral entry point instead
                // of a verdict — the explanation lives on the second-level page.
                AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED ->
                    BadgeCopy.avfUnsupportedEntryLabel()
                AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN -> "AVF · 模式未验证"
                AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED -> "AVF · 接口受限"
                AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE -> "AVF · 接口缺失"
                else -> "AVF · 未就绪"
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.seg_qemu)?.text =
            if (session is QemuInstanceLauncher.Session) "QEMU · 正在使用" else "QEMU · 可用"
        // Lane A（2026-11-13）：UML face 的两层诚实语义分开说——「运行中」是
        // 容量事实（fast 形态宽 1 上账本），「编译通道未接」是可路由性事实
        // （fabric-route D-1 未落地，固定 CHANNEL_UNWIRED 守门）；不在运行
        // 窗口内一律「未启用」，不冒充可用。
        findViewById<com.google.android.material.button.MaterialButton>(R.id.seg_uml)?.text =
            if (UmlFaceStatusRegistry.runningWidth() > 0) "UML · 运行中(实验)·编译通道未接"
            else "UML · 未启用"
        sshCommand = instance?.let { target -> sshPrefetch?.command ?: loadSshCommand(target) }
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

    // -----------------------------------------------------------------------------------
    // Lane B (Phase B2): the distro-tab「UML rootfs (Alpine minirootfs)」readiness card.
    // The card's every decision comes from UmlRootfsCardState — this Activity only
    // binds. The ready state claims "verified + extracted" and nothing more: the
    // official Alpine minirootfs ships no /um-command.sh (research/uml-fastboot/
    // initramfs/init fail 29 missing_command), so this round's artifact cannot start
    // to READY over the external-root channel, and the compile channel is
    // CHANNEL_UNWIRED (Lane A's honest edge). Launch/compile usability is the
    // fabric-route round's design problem.
    // -----------------------------------------------------------------------------------

    /** The card's current render decision; mutated only on the UI thread. */
    @Volatile
    private var umlRootfsCardModel: UmlRootfsCardState.CardModel? = null

    /**
     * Bind the readiness card. Flag-off builds: the card stays GONE and no
     * listener is bound — zero entry. Flag-on builds: an off-main-thread
     * readiness probe (pure reads) decides the initial phase; the click
     * entry starts the provision on the same IO executor.
     */
    private fun setupUmlRootfsCard() {
        val card = findViewById<View>(R.id.card_uml_rootfs) ?: return
        if (!UmlRootfsCardState.visible(BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED)) {
            card.visibility = View.GONE
            return
        }
        umlRootfsCardModel = UmlRootfsCardState.notReady()
        renderUmlRootfsCard()
        operations.execute {
            try {
                val readiness = UmlRootfsProvisioner.readiness(filesDir.toPath())
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    umlRootfsCardModel = UmlRootfsCardState.initial(
                        BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED, readiness)
                    renderUmlRootfsCard()
                }
            } catch (probeFailure: Exception) {
                // A probe that cannot read stays honestly at NOT_READY; the
                // provision path surfaces the real failure on click.
                runOnUiThread { renderUmlRootfsCard() }
            }
        }
        findViewById<View>(R.id.btn_uml_rootfs_action)?.setOnClickListener {
            startUmlRootfsProvision()
        }
    }

    /** The card's only entry action: provision off the main thread, render every transition. */
    private fun startUmlRootfsProvision() {
        val model = umlRootfsCardModel ?: return
        if (model.phase() == UmlRootfsCardState.Phase.DOWNLOADING ||
            model.phase() == UmlRootfsCardState.Phase.READY
        ) {
            return
        }
        umlRootfsCardModel = UmlRootfsCardState.downloading(0, 0)
        renderUmlRootfsCard()
        operations.execute {
            try {
                // The production engine (same constructor pattern as the distro
                // download's existing consumer) under a progress tap, because
                // RootfsTransport passes the engine a null callback by contract.
                val engine = UmlRootfsProgressEngine(
                    io.github.nyzeep.orchestella.runtime.network.download.ResilientDownloadEngine(),
                ) { downloaded, total ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        umlRootfsCardModel = UmlRootfsCardState.downloading(downloaded, total)
                        renderUmlRootfsCard()
                    }
                }
                val receipt = UmlRootfsProvisioner.provision(engine, filesDir.toPath())
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    umlRootfsCardModel = UmlRootfsCardState.ready(receipt)
                    renderUmlRootfsCard()
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    umlRootfsCardModel = UmlRootfsCardState.failed(failure)
                    renderUmlRootfsCard()
                }
            }
        }
    }

    /** Bind one CardModel to the card views. No IO, no decisions — only the mapping. */
    private fun renderUmlRootfsCard() {
        val card = findViewById<View>(R.id.card_uml_rootfs) ?: return
        val model = umlRootfsCardModel ?: return
        if (!UmlRootfsCardState.visible(BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED)) {
            card.visibility = View.GONE
            return
        }
        when (model.phase()) {
            UmlRootfsCardState.Phase.DISABLED -> {
                card.visibility = View.GONE
                return
            }
            else -> card.visibility = View.VISIBLE
        }
        val status = findViewById<TextView>(R.id.txt_uml_rootfs_status)
        val button = findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btn_uml_rootfs_action)
        when (model.phase()) {
            UmlRootfsCardState.Phase.NOT_READY -> {
                status?.text = "未就绪 · 下载并解包后可供给 UML hostfs 通道"
                showUmlRootfsProgress(false, 0, show = false)
                button?.isEnabled = true
                button?.text = "下载并验证（约 3.8 MB）→"
            }
            UmlRootfsCardState.Phase.DOWNLOADING -> {
                val percent = model.progressPercent()
                status?.text = if (percent >= 0) {
                    "下载中 $percent%（下载后进行 sha256 pin 校验与内容自检）"
                } else {
                    "连接中…（网络类失败由引擎重试与镜像容灾，校验类失败将如实拒绝）"
                }
                showUmlRootfsProgress(indeterminate = percent < 0, percent = percent, show = true)
                button?.isEnabled = false
                button?.text = "下载中…"
            }
            UmlRootfsCardState.Phase.READY -> {
                val provenance = if (model.alreadyVerified()) {
                    "已就绪（重复入口命中，本次零网络流量）"
                } else {
                    "已就绪"
                }
                status?.text = "$provenance · 已验证+已解包 ${model.extractedEntries()} 项 · " +
                    "官方镜像不含 UML 启动脚本，本轮不可直接启动或编译"
                showUmlRootfsProgress(false, 0, show = false)
                button?.isEnabled = false
                button?.text = "已就绪"
            }
            UmlRootfsCardState.Phase.FAILED -> {
                status?.text = "失败：${model.failureReason()} · 下载/校验/解包任一层拒绝都如实呈现，可重试"
                showUmlRootfsProgress(false, 0, show = false)
                button?.isEnabled = true
                button?.text = "重试 →"
            }
            UmlRootfsCardState.Phase.DISABLED -> card.visibility = View.GONE
        }
    }

    /**
     * The card's progress bar. The indeterminate flag may only change while
     * the indicator is hidden — this helper re-hides, flips, then shows.
     */
    private fun showUmlRootfsProgress(indeterminate: Boolean, percent: Int, show: Boolean) {
        val progress = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(
            R.id.progress_uml_rootfs) ?: return
        if (progress.isIndeterminate != indeterminate) {
            progress.visibility = View.GONE
            progress.isIndeterminate = indeterminate
        }
        if (!indeterminate) {
            progress.setProgressCompat(percent, true)
        }
        progress.visibility = if (show) View.VISIBLE else View.GONE
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
            // 主线程卫生（Lane C）：实例元数据读取（属性文件 IO）迁 operations executor；
            // launchInstance 仍回主线程触发，保持其内部 UI 写入线程不变。
            val store = instanceStore
            operations.execute {
                val loaded = try {
                    store.load(targetLaunchId)
                } catch (failure: Exception) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        createStatus.text = "读取实例失败：${failure.message ?: failure.javaClass.simpleName}"
                        Toast.makeText(this@MainActivity, createStatus.text, Toast.LENGTH_LONG).show()
                    }
                    null
                }
                if (loaded != null) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        launchInstance(loaded)
                    }
                }
            }
        }
    }

    /**
     * Session 19 (T-B.2): the dashboard paints several surfaces by hand
     * (header pills, glow cards, distro cards, instance cards, tab strip).
     * ThemeApplier's walk repaints the tree, but those hand-painted colors
     * only refresh when applyThemeToDashboard runs; without this listener a
     * slider drag would not reach the home screen until the next resume.
     */
    private val surfaceOpacityListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ThemeStore.KEY_UI_SURFACE_OPACITY) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) applyThemeToDashboard()
                }
            }
        }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(surfaceOpacityListener)
    }

    override fun onStop() {
        getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(surfaceOpacityListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::themeStore.isInitialized) {
            applyThemeToDashboard()
        }
        refreshUpdateBadge()
        if (::updateCenter.isInitialized) {
            // Automatic, throttled, silent: the badge appears only when a newer release exists.
            io.github.nyzeep.orchestella.update.UpdateCoordinator.checkInBackground(
                this, BuildConfig.VERSION_NAME
            ) {
                if (!isFinishing && !isDestroyed) refreshUpdateBadge()
            }
        }
        if (::instanceStore.isInitialized) {
            avfReadiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
            refreshWizardEntry()
            // 孤儿回收是磁盘遍历 + 可能写盘，与 onCreate 一致迁后台；随后的
            // showInstances() 快照任务在同一单线程队列上排队，仍看到回收后的状态。
            operations.execute { reconcileOrphanInstances() }
            renderSystemStatus(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
            showInstances()
            if (currentTabIndex == 2) {
                renderHardwareSpecDetails()
            }
        }
        dashboardHandler.removeCallbacks(refreshDashboard)
        dashboardHandler.post(refreshDashboard)
    }

    /** The About entry carries the only upgrade marker in the app; nothing else may add one. */
    private fun refreshUpdateBadge() {
        val pending = if (::updateCenter.isInitialized) {
            updateCenter.pending(BuildConfig.VERSION_NAME)
        } else {
            null
        }
        aboutUpdateBadge?.let { badge ->
            val wasHidden = badge.visibility != View.VISIBLE
            badge.visibility = if (pending != null) View.VISIBLE else View.GONE
            // W4 走查修复：dot 出现时向无障碍用户宣告语义（徽标本体已带 contentDescription）。
            if (pending != null && wasHidden) {
                badge.announceForAccessibility(getString(R.string.about_update_badge_desc))
            }
        }
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

    /**
     * Whether the pVM compile-acceleration lane is ready right now, derived
     * from the same facts + policy source the launch dispatcher consults —
     * never from a static string. Session-18 W-B red line: when this returns
     * false, no acceleration wording may appear anywhere on the home screen.
     */
    private fun acceleratorLaneReady(): Boolean {
        if (!settingsManager.isAcceleratorLaneEnabled() || !BuildConfig.ACCELERATOR_LANE_ENABLED) {
            return false
        }
        return try {
            val facts = AndroidAcceleratorFactProvider.create(this).read()
            AcceleratorPolicy.decide(facts, AcceleratorLaunchDispatcher.GUEST_COMPILER_TRIPLE)
                .mode() == AcceleratorMode.QEMU_PLUS_ACCEL
        } catch (failure: RuntimeException) {
            false
        }
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

    /**
     * The one surface color for hand-painted dashboard chrome (session 19 T-B.2).
     * Composes the persisted percent with the theme surface; with a wallpaper
     * selected the scrim owns legibility, so the contrast floor is skipped.
     *
     * 主线程卫生：原实现每次调用 new 两个 ThemeStore（且 ThemeStore 构造还要走一次
     * ThemeApplier.install），读的是同一份偏好文件；改用 onCreate 已持有的字段。
     */
    private fun surfaceColor(theme: AppTheme): Int = SurfaceOpacity.resolveSurfaceColor(
        theme.surface,
        theme.background,
        theme.text,
        themeStore.surfaceOpacityPercent(),
        themeStore.backgroundUri() != null,
    )

    private fun applyThemeToDashboard() {
        try {
            val theme = themeStore.resolve()
            activeTheme = theme
            ThemeApplier.bind(this)
            listOf(
                R.id.badge_theme_mascot_active to ThemeCatalog.mascotLavender.id,
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
            val iconBtnTint = android.content.res.ColorStateList.valueOf(surfaceColor(theme))
            val iconContentTint = android.content.res.ColorStateList.valueOf(theme.text)
            listOf(R.id.ssh_manager_button, R.id.theme_toggle_button, R.id.about_button, R.id.settings_button).forEach { id ->
                findViewById<com.google.android.material.button.MaterialButton>(id)?.let { btn ->
                    btn.backgroundTintList = iconBtnTint
                    btn.iconTint = iconContentTint
                    btn.strokeColor = android.content.res.ColorStateList.valueOf((theme.glow and 0x00FFFFFF) or 0x33000000)
                    btn.strokeWidth = (1 * resources.displayMetrics.density).toInt()
                }
            }
            aboutUpdateBadge?.let { badge ->
                // W4 走查修复：dot 填充走语义 notice 色槽（不再随主题 accent 紫/红漂移），
                // 环仍取按钮自身 surface 色：每个主题下都可读，不变成第二个"未读通知"。
                val density = resources.displayMetrics.density
                val notice = getColor(R.color.oe_notice)
                badge.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(notice)
                    setStroke((2 * density).toInt(), surfaceColor(theme))
                }
            }

            findViewById<View>(R.id.system_status_card)?.let { card ->
                if (card is com.google.android.material.card.MaterialCardView) {
                    card.setCardBackgroundColor(surfaceColor(theme))
                    card.strokeColor = (theme.glow and 0x00FFFFFF) or 0x33000000
                    card.strokeWidth = (1 * resources.displayMetrics.density).toInt()
                    attachPressGlow(card, theme)
                } else {
                    card.background = buildGlowCardDrawable(theme)
                    attachPressGlow(card, theme)
                }
            }
            // The ribbon banner's XML shape survives ThemeApplier's generic walk on light
            // themes (session 17 leftover); paint it like the other glow cards explicitly.
            findViewById<View>(R.id.banner_active_machine)?.let { banner ->
                banner.background = buildGlowCardDrawable(theme)
            }
            val distroCards = listOf(
                R.id.card_create_linux_main,
                R.id.card_uml_rootfs,
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
            switchTab(currentTabIndex, forceAll = true)
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
                cardView.setCardBackgroundColor(surfaceColor(theme))
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
        val radius = themeStore.cornerRadiusDp() * resources.displayMetrics.density
        val outer = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            setStroke(
                (1.5f * resources.displayMetrics.density).toInt(),
                (theme.glow and 0x00FFFFFF or 0x40000000.toInt()),
            )
            setColor(android.graphics.Color.TRANSPARENT)
        }
        val inner = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = radius
            setColor(surfaceColor(theme))
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
                    // Session 19 (T-D): press-in 100 ms (short2) / release 150 ms
                    // (short3), now consumed via the OrchestellaMotion token mirror
                    // (Lane D) instead of bare literals.
                    v.animate().scaleX(1.015f).scaleY(1.015f)
                        .setDuration(OrchestellaMotion.DURATION_PRESS_IN_MS.toLong())
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL,
                android.view.MotionEvent.ACTION_HOVER_EXIT -> {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(OrchestellaMotion.DURATION_PRESS_OUT_MS.toLong())
                        .setInterpolator(android.view.animation.AccelerateInterpolator()).start()
                }
            }
            false
        }
    }

    /** 同步路径（主题应用/换页/手动刷新）：在主线程现取读数，行为与迁移前一致。 */
    private fun renderGauges(theme: AppTheme) {
        renderGauges(
            theme,
            HostHardwareDetector.detect(this),
            try { android.os.StatFs(filesDir.path).totalBytes } catch (_: Exception) { 0L },
            try { android.os.StatFs(filesDir.path).availableBytes } catch (_: Exception) { 0L },
        )
    }

    /** 周期刷新路径：使用后台快照预取的读数，UI 线程零磁盘/内核读。 */
    private fun renderGauges(theme: AppTheme, snapshot: DashboardSnapshot) {
        renderGauges(theme, snapshot.hardwareProfile, snapshot.storageTotalBytes, snapshot.storageFreeBytes)
    }

    private fun renderGauges(
        theme: AppTheme,
        profile: HostHardwareProfile,
        storageTotalBytes: Long,
        storageFreeBytes: Long,
    ) {
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

        val totalBytes = storageTotalBytes
        val freeBytes = storageFreeBytes
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
        lastAssessment = assessment
        val profile = HostHardwareDetector.detect(this)
        val cpuChip = findViewById<TextView>(R.id.system_cpu_chip)
        val virtBadge = findViewById<TextView>(R.id.system_virtualization_badge)
        val statusText = findViewById<TextView>(R.id.runtime_status)

        cpuChip?.text = getString(R.string.system_cpu_badge, profile.totalCores())
        virtBadge?.text = currentBadge(assessment)
        // Session-18 W-B: the accel badge mirrors the real lane state (facts +
        // policy, same source as dispatch) — "ready" only when the lane would
        // actually serve the launch.
        val laneReadyNow = acceleratorLaneReady()
        findViewById<TextView>(R.id.accel_lane_badge)?.apply {
            visibility =
                if (settingsManager.isAcceleratorLaneEnabled() && BuildConfig.ACCELERATOR_LANE_ENABLED) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            text = if (laneReadyNow) "pVM 编译加速 · 就绪" else "pVM 编译加速 · 未就绪"
        }

        statusText?.text = buildString {
            appendLine(getString(R.string.runtime_class_full_linux))
            if (assessment.canCreateFullLinuxInstance()) {
                appendLine(getString(R.string.support_floor_passed))
            } else {
                appendLine(getString(R.string.support_floor_refused, assessment.refusalReason()))
            }
            assessment.emulatorNotice()?.let(::appendLine)
            // 首次校验完成前该行为空，校验回调（scheduleRuntimeVerification）补齐。
            runtimeStatusLine?.let(::append)
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
            duration = OrchestellaMotion.DURATION_BREATH_CYCLE_MS.toLong()
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        animator.start()
        runningAnimators.add(animator)
    }

    /**
     * 冷启动/主线程卫生（Lane C）：实例列表读取（目录遍历 + 逐实例属性文件解析）
     * 与 SSH 规则、加速车道判定都在 operations executor 上取齐，UI 线程只渲染
     * [DashboardSnapshot]。渲染顺序经单线程队列保持，最终状态与原同步实现一致。
     */
    private fun showInstances() {
        loadDashboardSnapshot(strict = true) { snapshot -> renderInstances(snapshot) }
    }

    /**
     * 后台取数：一次快照涵盖实例列表、主实例、SSH 规则预取、加速车道判定与
     * 硬件/存储读数。
     *
     * 失败语义与原实现一致：strict=true（列表渲染路径）在 UI 线程清空卡片并展示
     * 既有失败文案，不回调；strict=false（3 秒周期刷新）按原 catch 行为以空列表
     * 快照继续回调，由调用方按原 diff 规则决定展示失败文案还是轻更新。
     */
    private fun loadDashboardSnapshot(strict: Boolean, onReady: (DashboardSnapshot) -> Unit) {
        val appContext = applicationContext
        val lastActiveId = if (::settingsManager.isInitialized) settingsManager.lastActiveInstanceId else null
        val store = instanceStore
        val instancesPath = filesDir.path
        operations.execute {
            val instances = try {
                store.list()
            } catch (_: IOException) {
                null
            }
            val primary = instances?.let { DashboardSnapshot.pickPrimary(it, lastActiveId) }
            val snapshot = DashboardSnapshot(
                instances = instances ?: emptyList(),
                stateKey = DashboardSnapshot.stateKeyOf(instances ?: emptyList()),
                listFailed = instances == null,
                primary = primary,
                ssh = primary?.let { DashboardSnapshot.SshPrefetch(loadSshCommand(it)) },
                acceleratorLaneReady = acceleratorLaneReady(),
                hardwareProfile = HostHardwareDetector.detect(appContext),
                storageTotalBytes = try { android.os.StatFs(instancesPath).totalBytes } catch (_: Exception) { 0L },
                storageFreeBytes = try { android.os.StatFs(instancesPath).availableBytes } catch (_: Exception) { 0L },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                maybeReportFullyDrawn()
                if (instances == null && strict) {
                    // Listing failure is not a creation failure: say what broke and what the user can do.
                    // 原实现在读取前已清空卡片；失败时同样先清再报错，不留幻影卡片。
                    clearRunningAnimators()
                    instanceList.removeAllViews()
                    createStatus.text = getString(R.string.instances_load_failed, "实例元数据不可读")
                    return@runOnUiThread
                }
                onReady(snapshot)
            }
        }
    }

    /** 单实例的 SSH 命令拼装（端口转发规则文件读取，现仅由后台快照调用）。 */
    private fun loadSshCommand(target: InstanceLifecycle.Instance): String? =
        try {
            val rule = FilePortForwardingStore(filesDir.toPath().resolve("network"))
                .loadRules(target.id()).firstOrNull {
                    it.guestPort() == 22 && it.protocol().wireName() == "tcp"
                }
            rule?.let {
                val host = if (it.isLanExposed()) "<手机IP>" else it.bindAddress()
                "ssh -p ${it.hostPort()} ${target.defaultUser()}@$host"
            }
        } catch (_: Exception) {
            null
        }

    private fun renderInstances(snapshot: DashboardSnapshot) {
        clearRunningAnimators()
        instanceList.removeAllViews()
        val instances = snapshot.instances

        lastInstanceState = snapshot.stateKey
        val emptyView = findViewById<TextView>(R.id.instances_empty)
        val countBadge = findViewById<TextView>(R.id.instances_count_badge)
        countBadge?.text = "${instances.size} 个实例"

        updateConsoleWithInstance(snapshot.primary, snapshot.ssh)

        if (instances.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            return
        } else {
            emptyView?.visibility = View.GONE
        }

        val supportsDesktop = false
        val strategyBadge = currentBadge(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
        // One probe per render, not per card: the facts/policy read is the
        // same source the launch dispatcher consults. Since the snapshot
        // migration the probe runs on the executor, not the UI thread.
        val laneReady = snapshot.acceleratorLaneReady
        val currentTheme = activeTheme ?: themeStore.resolve()

        val inflater = LayoutInflater.from(this)
        for (instance in instances) {
            val cardView = inflater.inflate(R.layout.layout_instance_card, instanceList, false)
            val session = ExecutionSessionRegistry.get(instance.id())
            val runningStrategy = when (session) {
                is QemuInstanceLauncher.Session ->
                    // Session-18 W-B (user-approved copy): when the pVM lane is
                    // really ready, say so; otherwise the plain software
                    // virtualization wording stays — no fake acceleration.
                    if (laneReady) BadgeCopy.derive(true, "") else "QEMU 软件虚拟化"
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
                btnPower?.setIconResource(R.drawable.ic_sym_power_settings_new)
                btnPower?.setOnClickListener {
                    showPowerDialog(instance)
                }
            } else {
                btnPower?.text = "开机"
                btnPower?.setIconResource(R.drawable.ic_sym_power_settings_new)
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

            if (cardView is com.google.android.material.card.MaterialCardView) {
                cardView.setCardBackgroundColor(surfaceColor(currentTheme))
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
        // Lane D (2026-11-10)：实例卡差异化进场（fade + 4% rise 级联）。只在完整
        // 重渲染后播放：3 秒周期里状态指纹未变走 updateConsoleWithInstance 轻更新
        // 路径，永不重播（2026-11-08 M5 验证点）；指纹变化（实例创建/状态迁移）或
        // 显式刷新（onResume/删除/克隆后 showInstances）按数据刷新时刻重播。级联
        // 无延迟 GONE/定时器，下一次重渲染直接移除动画中的卡片，无半透明滞留面。
        OrchestellaMotion.playListEntryStagger(instanceList)
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
                    try {
                        controller.reconcileAfterHostDeath(instance.id(), InstanceLifecycle.State.INTERRUPTED)
                    } catch (failure: Exception) {
                        // A failed per-instance reconcile must not abort the pass, but leaving it
                        // unlogged strands the instance in a ghost RUNNING state with no trail.
                        android.util.Log.w(
                            "MainActivity",
                            "orphan reconcile failed id=${instance.id()} type=${failure.javaClass.simpleName}"
                        )
                    }
                }
            }
        } catch (failure: Exception) {
            // Store-level failure (list/controller construction): skip the pass, keep a trail.
            android.util.Log.w("MainActivity", "orphan reconcile pass failed type=${failure.javaClass.simpleName}")
        }
    }

    private val launchSettings = object : InstanceLaunchCoordinator.LaunchSettings {
        override fun setLastActiveInstanceId(instanceId: String) {
            settingsManager.lastActiveInstanceId = instanceId
        }
        override fun batteryPolicy(): BatteryPolicy = settingsManager.batteryPolicy
        override fun isAcceleratorLaneEnabled(): Boolean = settingsManager.isAcceleratorLaneEnabled()
    }

    private val launchForeground = object : InstanceLaunchCoordinator.ForegroundServiceHandle {
        override fun start(instanceId: String, distroTitle: String, performanceBadge: String,
            batteryPolicy: BatteryPolicy, launchGeneration: String?) {
            OrchestellaForegroundService.start(this@MainActivity, instanceId, distroTitle,
                performanceBadge, batteryPolicy, launchGeneration)
        }
        override fun stop(instanceId: String, generation: String?) {
            OrchestellaForegroundService.stop(this@MainActivity, instanceId, generation)
        }
    }

    private val launchFeedback = object : InstanceLaunchCoordinator.LaunchUi {
        override fun runOnUi(update: Runnable) {
            runOnUiThread(update)
        }
        override fun resetCreateButtons() {
            setCreateButtonsEnabled(DeviceCapabilityEvaluator.evaluate(deviceProfile()).canCreateFullLinuxInstance())
        }
        override fun showStatus(message: String) {
            createStatus.text = message
        }
        override fun showAttached(instance: InstanceLifecycle.Instance) {
            createStatus.text = "已连接到运行中的实例"
            openTerminal(instance)
        }
        override fun showLaunchBusyStatus() {
            createStatus.text = "实例正在启动，请稍后再试"
        }
        override fun showLaunchSuccess(navigation: InstanceLaunchCoordinator.LaunchNavigation) {
            val intent = Intent(this@MainActivity, TerminalActivity::class.java)
                .putExtra("bridgeToken", navigation.bridgeToken)
                .putExtra("instanceId", navigation.instanceId)
                .putExtra("hostname", navigation.hostname)
                .putExtra("defaultUser", navigation.defaultUser)
            startActivity(intent)
        }
        override fun surfaceLaunchFailure(message: String) {
            createStatus.text = message
            Toast.makeText(this@MainActivity, createStatus.text, Toast.LENGTH_LONG).show()
        }
        override fun showInstances() {
            this@MainActivity.showInstances()
        }
    }

    private val launchCopy = object : InstanceLaunchCoordinator.LaunchCopy {
        override fun detailMessage(failure: Throwable): String = formatLaunchFailure(failure)
        override fun guestExitedStatus(): String = getString(R.string.launch_guest_exited)
        override fun launchStartedHardwareStatus(): String = getString(R.string.launch_started_hardware)
        override fun launchDegradedToSoftwareStatus(reason: String): String =
            getString(R.string.launch_degraded_to_software, reason)
    }

    private val launchDiagnostics = object : InstanceLaunchCoordinator.LaunchDiagnostics {
        override fun logAcceleratorSnapshot(line: String) {
            android.util.Log.i("OrchestellaAccelerator", line)
        }
        override fun logLaunchFailure(failureType: String) {
            android.util.Log.w("OrchestellaLaunch", "launch failed type=$failureType")
        }
    }

    private fun launchInstance(instance: InstanceLifecycle.Instance) {
        // 协同落点（2026-11-07）：通知权限已在首帧后请求过（requestNotificationPermissionAfterFirstFrame），
        // 不再在 VM 启动路径上弹窗打断。
        setCreateButtonsEnabled(false)
        createStatus.setText(R.string.launch_started)
        operations.execute {
            launchCoordinator.launch(instance)
        }
    }

    /**
     * 协同落点（Lane C 落地 / Lane D 规格，2026-11-07；2026-11-08 单源化收口）：
     * 启动失败不再直接展示「启动失败：IOException」这类异常类名，而是统一走
     * [ErrorCopy.detailMessage] 单源映射，输出「主文案（错误码 E-XXX）」副文本
     * 格式；原 [io.github.nyzeep.orchestella.startup.LaunchFailureCopy] 代码常量
     * 映射表已并入 ErrorCopy（见该对象的委托壳）。完整异常类名仍写 OrchestellaLaunch
     * 日志（见上方 runOnUiThread 中的 Log.w，行为不变）。
     */
    private fun formatLaunchFailure(failure: Throwable): String =
        ErrorCopy.detailMessage(this, failure)

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
}
