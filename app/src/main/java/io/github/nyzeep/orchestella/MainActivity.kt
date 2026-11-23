package io.github.nyzeep.orchestella

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.doOnAttach
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorMode
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorPolicy
import io.github.nyzeep.orchestella.ui.design.components.OeDialogAction
import io.github.nyzeep.orchestella.ui.design.components.OeDialogHost
import io.github.nyzeep.orchestella.ui.design.components.OeDialogSpec
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassDeviceCap
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassGateVerdict
import io.github.nyzeep.orchestella.ui.oe3.glass.GlassMode
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassDeviceCap
import io.github.nyzeep.orchestella.ui.oe3.glass.LocalOe3GlassLowRam
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassFrameGate
import io.github.nyzeep.orchestella.ui.oe3.glass.Oe3GlassStrategy
import io.github.nyzeep.orchestella.ui.oe3.motion.Oe3MotionSystem
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardController
import io.github.nyzeep.orchestella.ui.screens.wizard.WizardScreen
import io.github.nyzeep.orchestella.ui.shell.OeBackStack
import io.github.nyzeep.orchestella.ui.shell.LocalOeBackStack
import io.github.nyzeep.orchestella.ui.shell.Oe3App
import io.github.nyzeep.orchestella.ui.shell.Oe3Feature
import io.github.nyzeep.orchestella.ui.shell.Oe3NavStyle
import io.github.nyzeep.orchestella.ui.shell.Oe3Pages
import io.github.nyzeep.orchestella.ui.shell.Oe3ShellController
import io.github.nyzeep.orchestella.ui.shell.OeDestination
import io.github.nyzeep.orchestella.ui.shell.Oe3Tab
import io.github.nyzeep.orchestella.ui.shell.OeAppBackgroundSpec
import io.github.nyzeep.orchestella.ui.shell.StackEntry
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import io.github.nyzeep.orchestella.ui.theme.ThemeStore
import io.github.nyzeep.orchestella.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.capability.DeviceCapabilityEvaluator
import io.github.nyzeep.orchestella.ui.screens.common.StorageFacts
import io.github.nyzeep.orchestella.ui.screens.config.ConfigDestination
import io.github.nyzeep.orchestella.ui.screens.config.ConfigOptions
import io.github.nyzeep.orchestella.ui.screens.config.ConfigPreferences
import io.github.nyzeep.orchestella.ui.screens.config.ConfigRoute
import io.github.nyzeep.orchestella.ui.screens.config.ConfigScreen
import io.github.nyzeep.orchestella.ui.screens.config.ConfigScreenState
import io.github.nyzeep.orchestella.ui.screens.config.SharedPreferencesConfigStore
import io.github.nyzeep.orchestella.ui.screens.connections.ConnectionsPage
import io.github.nyzeep.orchestella.ui.screens.connections.PortForwardingPage
import io.github.nyzeep.orchestella.ui.screens.connections.SshManagerPage
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardActions
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardScreen
import io.github.nyzeep.orchestella.ui.screens.dashboard.DashboardUiState
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosActions
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosScreen
import io.github.nyzeep.orchestella.ui.screens.distros.DistrosUiState
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareActions
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareBatteryFacts
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareDeviceFacts
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareEngineFacts
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareScreen
import io.github.nyzeep.orchestella.ui.screens.hardware.HardwareUiState
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesActions
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesProgressUi
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesScreen
import io.github.nyzeep.orchestella.ui.screens.instances.InstancesUiState
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeAccentScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeBackgroundScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemePresetScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioHandle
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeStudioScreen
import io.github.nyzeep.orchestella.ui.screens.theme.ThemeTerminalScreen
import io.github.nyzeep.orchestella.ui.screens.theme.rememberThemeStudioHandle
import io.github.nyzeep.orchestella.update.UpdateCenter
import io.github.nyzeep.orchestella.update.UpdateCoordinator
import io.github.nyzeep.orchestella.startup.DashboardSnapshot
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEventSink
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException
import io.github.nyzeep.orchestella.runtime.engineavf.AvfVirtualMachineEngine
import io.github.nyzeep.orchestella.runtime.engineavf.ReflectiveAvfVirtualMachineApi
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore
import io.github.nyzeep.orchestella.runtime.network.PortConflictDetector
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager
import java.io.IOException
import java.time.Clock
import java.util.concurrent.Executors
import java.util.function.BooleanSupplier

private val LocalMainThemeStudioHandle = staticCompositionLocalOf<ThemeStudioHandle?> { null }

/**
 * Oe3 shell host (ticket UI3-12): the Activity owns the Compose host, the
 * persisted shell preferences and the shared launch infrastructure; the five
 * pages consume [DashboardSnapshot]-driven state and route every action back
 * to the existing Activities, dialogs and SAF flows. There is no legacy XML
 * shell anymore — this class holds no `findViewById` chrome of its own.
 */
class MainActivity : AppCompatActivity() {
    private val operations: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var instanceStore: InstanceLifecycle.FileStore
    private lateinit var launchCoordinator: InstanceLaunchCoordinator
    private lateinit var settingsManager: SettingsManager
    private lateinit var themeStore: ThemeStore
    private lateinit var updateCenter: UpdateCenter
    private lateinit var configPreferences: ConfigPreferences

    private var avfReadiness: AvfCapabilityEvaluator.AvfReadiness? = null
    private var wizardModel: AvfAuthorizationWizardModel? = null
    private var pendingExportInstance: InstanceLifecycle.Instance? = null
    private var oe3Shell: Oe3ShellController? = null
    private var oe3ComposeView: ComposeView? = null

    // UI-12 向导：壳内导航栈由 Activity 持有（旋转不丢；进程级栈恢复由 UI-13
    // 提升进 Oe3ShellController 时收口），向导引擎随 Activity 存续。
    private var oe3BackStack: OeBackStack? = null
    private var wizardController: WizardController? = null
    private var fullyDrawnReported = false

    // ==================== Oe3 页面状态（Compose 壳层的渲染输入） ====================
    // Activity 持有快照与动作，Compose 只读；所有页面的数据仍来自既有
    // DashboardSnapshot 周期与既有动作入口，不另立数据源。

    /** 壳层主题与呈现偏好快照；ThemeStore 变更经 onStart 注册的监听刷新。 */
    private val oe3ThemeState = mutableStateOf<AppTheme?>(null)
    private val oe3PrefsState = mutableStateOf(Oe3ShellPrefs())

    /** UI-06：性能门的设备级降档缓存（持久化值的活动副本，降档时即时生效）。 */
    private val oe3GlassDeviceCapState = mutableStateOf(GlassDeviceCap.Unlimited)

    /** UI-06：性能门每进程只采样一次；启动后由快照刷新按需武装。 */
    private var oe3GlassGateResumed = false
    private var oe3GlassGateCompleted = false
    private var oe3GlassGateLevel: Oe3GlassLevel? = null

    /**
     * UI-06（R-03 生命周期加固）：进行中的帧采样监听器。帧门 60 帧采样完成时
     * 在回调线程摘除并置空；[onDestroy] 再做一次幂等解绑兜底——Activity 销毁
     * 时若监听器仍在（采样未完成），滞留的 FrameMetrics 回调会引用已失效的
     * window/view，必须先解绑。
     */
    private var oe3GlassGateListener: android.view.Window.OnFrameMetricsAvailableListener? = null

    /** UI-06：设备是否为低内存（isLowRamDevice）；onCreate 探测一次。 */
    private val oe3GlassLowRamDevice by lazy {
        (getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice ?: false
    }

    /** 五页状态；null 期间壳层渲染占位页（首帧快照落地前短暂存在）。 */
    private val oe3DashboardState = mutableStateOf<DashboardUiState?>(null)
    private val oe3InstancesState = mutableStateOf<InstancesUiState?>(null)
    private val oe3InstancesRefreshingState = mutableStateOf(false)
    private val oe3HardwareState = mutableStateOf<HardwareUiState?>(null)
    private val oe3ConfigState = mutableStateOf<ConfigScreenState?>(null)

    /** 创建/导入等异步进度的文字（launchFeedback 链路的 Compose 侧出口）。 */
    private val oe3ProgressText = mutableStateOf("")

    /** UML rootfs 卡状态机输出；state 承载让发行版页随下载进度重组。 */
    private var umlRootfsCardModel: UmlRootfsCardState.CardModel? by mutableStateOf(null)

    /** 硬件页的宿主剖面与存储事实（快照周期里在 executor 侧更新）。 */
    @Volatile
    private var oe3HardwareProfile: HostHardwareProfile? = null
    @Volatile
    private var oe3Storage: StorageFacts = StorageFacts.None
    private val oe3HostUiFacts = mutableStateOf(HostUiFacts.empty())

    /**
     * 电池读数缓存（UI-08）。onStart 先同步读一次（首帧即有值），之后由
     * `ACTION_BATTERY_CHANGED` 广播刷新；硬件页的圆环与温度只消费这里的事实。
     * 布尔门让 onStart/onStop 的注册与注销各只发生一次。
     */
    private var batteryFacts = HardwareBatteryFacts()
    private var powerReceiverRegistered = false

    private var stopAnimatorScaleObserver: (() -> Unit)? = null

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
    // Gate 2（2026-11-13，umnet 候选 a）：net supervisor 包装层包住 drain spawner。
    // compileBridge flag 为 off 时逐字节透传原命令（既有行为）；为 on 且
    // nativeLibraryDir 两库（libuml_net.so/libuml_passt.so，stageUmlNetHelper
    // staged）在位时重写为 umnet supervisor 调用并附 PASST_ANDROID_NO_ISOLATION=1；
    // 为 on 而库缺时 spawn 抛指名 IOException——launcher 的 SPAWN_FAILED 固定码
    // 接住，绝不静默降级冒充可路由。console 链不破：drain 仍归
    // ConsoleDrainingUmlSpawner，umnet stdout=内核 console（fd 透传）。
    private val umlConsoleSpawner = UmlNetSupervisorSpawner(
        delegate = ConsoleDrainingUmlSpawner(umlFaceConsoleSink),
        enabled = BuildConfig.UML_COMPILE_BRIDGE,
        nativeLibraryDir = { java.io.File(applicationInfo.nativeLibraryDir) },
    )
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
            // Gate 2 E2E（launcher v2 wiring，2026-11-13）：wired arm（compileBridge
            // flag on 且 D-1 lane 通道已开）且 rootfs 解包树就绪时，face 命令行经既有
            // extraArgs 缝追加外置根三参数（umroot=<解包树绝对路径> /
            // umrootfstype=hostfs / um.net=1）——fastProfileCommand 之后逐字追加，
            // initramfs init switch_root 进注入的 v2 启动器（passt DHCP 出网 +
            // distccd 驻留）。其余形态（flag off / lane 未开 / rootfs 未就绪或未装）
            // 零 extraArgs，探针形态逐字节不变——unwired face 的 EOF 收割契约依赖
            // 探针自终止，绝不把 unwired face 变成长驻内核。512M / host-1 单核
            // （fast 形态语义宽 1）保持 defaults 不变。
            val defaults = UmlFaceLauncher.LaunchParams.defaults()
            val launchParams = UmlFaceLauncher.LaunchParams(
                defaults.memMb(),
                defaults.ncpus(),
                if (channel != null) umlRootfsExtraArgs() else UmlRootfsExtraArgs.NONE,
            )
            if (channel != null) {
                AcceleratorLaneAssembly.umlFace(
                    gate,
                    UmlFaceKeepAliveService.environment(this),
                    launchParams,
                    UmlFaceKeepAliveService.keepAlive(this),
                    umlConsoleSpawner,
                    channel,
                )
            } else {
                AcceleratorLaneAssembly.umlFace(
                    gate,
                    UmlFaceKeepAliveService.environment(this),
                    launchParams,
                    UmlFaceKeepAliveService.keepAlive(this),
                    umlConsoleSpawner,
                )
            }
        },
        compileBridge = umlCompileBridge,
        log = umlFaceConsoleLog,
    )

    private fun umlRootfsExtraArgs(): List<String> {
        return try {
            UmlRootfsExtraArgs.forArm(
                BuildConfig.UML_COMPILE_BRIDGE,
                UmlRootfsProvisioner.readiness(filesDir.toPath()),
            )
        } catch (_: java.io.IOException) {
            UmlRootfsExtraArgs.NONE
        }
    }

    companion object {
        /**
         * 进程级一次性门（协同落点，2026-11-07 设备窗实测：VM 启动时弹权限窗打断）。
         * 通知权限改为进程首个首帧后请求一次；不再挂在每次 VM 启动路径上。
         */
        private val notificationPromptShownThisProcess = java.util.concurrent.atomic.AtomicBoolean(false)

        /** UI-12：向导目的地键与草稿恢复键（旧 SetupWizardActivity STATE_DRAFT 的壳内对应）。 */
        private const val WIZARD_DESTINATION_KEY = "creation-wizard"

        /** 主题工坊根页的栈内标识（子页 key 见 `ThemeStudioPage`）。 */
        private const val THEME_STUDIO_DESTINATION_KEY = "theme-studio"
        private const val STATE_WIZARD_DRAFT = "creation_draft_v1"
        private const val STATE_SHELL_STACK = "shell_stack_v1"
    }

    private val dashboardHandler = Handler(Looper.getMainLooper())

    /** 3 秒快照周期：五页状态的唯一数据钟（与旧壳同一取数语义）。 */
    private val refreshDashboard = object : Runnable {
        override fun run() {
            if (!::instanceStore.isInitialized || isFinishing || isDestroyed) return
            // 冷启动/主线程卫生（Lane C）：快照在 operations executor 上取齐，
            // UI 线程只消费；读取失败按空列表继续。
            loadDashboardSnapshot { snapshot ->
                updateOe3Pages(snapshot)
                maybeReportFullyDrawn()
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
                        // ZD-W2：与配置页同一动作同文案，复用 config_export_success。
                        Toast.makeText(this, R.string.config_export_success, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    // 异常详情只进日志，不进用户文案（voice.md §5）。
                    android.util.Log.w("MainActivity", "backup export failed", e)
                    runOnUiThread {
                        Toast.makeText(this, R.string.toast_backup_export_failed, Toast.LENGTH_LONG).show()
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
                    setCreateStatusLine(getString(R.string.import_instance_progress))
                }
                try {
                    val adapter = AndroidSafImportAdapter(this, Clock.systemUTC())
                    val controller = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
                    val storageDir = filesDir.toPath().resolve("instances")
                    val imported = adapter.importArchiveToStorage(srcUri, storageDir, controller)
                    runOnUiThread {
                        setCreateStatusLine("")
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.import_instance_success, imported.id()),
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshInstanceListAfterMutation()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        setCreateStatusLine(getString(R.string.import_instance_failed, e.message))
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

    // UI3-12：主题工坊的壁纸/终端背景 SAF 选择（与 SettingsSubActivity 同一
    // 契约：仅接受用户手选的 content://，先留权再入库）。
    private val backgroundPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        if (themeStore.persistBackgroundPermission(this, uri)) {
            themeStore.setBackgroundUri(uri.toString())
            refreshOe3ShellSnapshot()
            Toast.makeText(this, R.string.toast_background_updated, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_background_permission_lost, Toast.LENGTH_LONG).show()
        }
    }

    private val terminalBackgroundPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        if (themeStore.persistBackgroundPermission(this, uri)) {
            themeStore.setTerminalBackgroundUri(uri.toString())
            Toast.makeText(this, R.string.toast_terminal_background_updated, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_background_permission_lost, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsManager = SettingsManager(this)
        themeStore = ThemeStore(this)

        // Android 15/17 Edge-to-Edge display
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // UI3-12: the Compose shell is the only interface; the shell switch
        // stays a named seam (Oe3Feature) instead of a scattered literal.
        check(Oe3Feature.enabled())
        ensureSharedInfrastructure()
        mountOe3Shell(savedInstanceState)
        handleLaunchIntent(intent)
    }

    /**
     * UI3-12：Compose 壳层为唯一界面。Theme Studio 的呈现偏好（纯黑/玻璃/
     * 底栏样式/等宽字体）在这里读出并传入 [Oe3App]——读取放壳层桥（Activity），
     * Oe3App 保持状态无知，兑现 UI3-13 文案的「全局生效」。五页状态与动作
     * 也由本类持有：数据全部来自既有 DashboardSnapshot 周期与既有动作入口
     * （Activity / 对话框 / SAF），壳层只做路由，不重写任何既有功能。
     */
    private fun mountOe3Shell(savedInstanceState: Bundle?) {
        val controller = Oe3ShellController(
            Oe3ShellController.restore(savedInstanceState?.getString(Oe3ShellController.STATE_KEY)),
        )
        oe3Shell = controller
        val backStack = OeBackStack()
        oe3BackStack = backStack
        restoreShellStack(backStack, savedInstanceState)
        refreshOe3ShellSnapshot()

        val initialTheme = oe3ThemeState.value ?: themeStore.resolve()
        val initialPureBlack = initialTheme.isDark && themeStore.uiPureBlack()
        val composeView = ComposeView(this).apply {
            setBackgroundColor(if (initialPureBlack) android.graphics.Color.BLACK else initialTheme.background)
            setContent {
                val theme = oe3ThemeState.value ?: themeStore.resolve()
                val prefs = oe3PrefsState.value
                val oe3Pages = remember { buildOe3Pages() }
                val onTabSelected = remember(controller) { controller::select }
                val themeStudioHandle = if (backStack.containsKeyOrLeaving(THEME_STUDIO_DESTINATION_KEY)) {
                    rememberThemeStudioHandle(themeStore)
                } else {
                    null
                }
                DisposableEffect(theme.isDark) {
                    val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                    insetsController.isAppearanceLightStatusBars = !theme.isDark
                    insetsController.isAppearanceLightNavigationBars = !theme.isDark
                    onDispose {}
                }
                // UI-06 玻璃闸运行期信号：低内存设备与设备档位缓存经 CompositionLocal
                // 下发（预览/测试默认不降档）。「始终」模式绕过降档缓存——用户显式
                // 意志优先于性能门，硬闸（省电/低内存/API）仍在策略层生效。
                val effectiveCap =
                    if (prefs.glassMode == GlassMode.Always) {
                        GlassDeviceCap.Unlimited
                    } else {
                        oe3GlassDeviceCapState.value
                    }
                CompositionLocalProvider(
                    LocalOe3GlassLowRam provides oe3GlassLowRamDevice,
                    LocalOe3GlassDeviceCap provides effectiveCap,
                    LocalMainThemeStudioHandle provides themeStudioHandle,
                ) {
                    Oe3App(
                        selectedTab = controller.selectedTab,
                        onTabSelected = onTabSelected,
                        theme = theme,
                        textScale = prefs.textScale,
                        reduceMotion = prefs.reduceMotion,
                        pureBlack = theme.isDark && prefs.pureBlack,
                        glassLevel = prefs.glassLevel,
                        glassEnabled = prefs.glassEnabled,
                        navStyle = prefs.navStyle,
                        monoBody = prefs.monoBody,
                        cornerRadiusDp = prefs.cornerRadiusDp,
                        surfaceAlpha = prefs.surfaceAlpha,
                        backgroundUri = prefs.backgroundUri,
                        backStack = backStack,
                        pages = oe3Pages,
                    )
                }
            }
        }
        oe3ComposeView = composeView

        // 返回键：非首 tab 回工作站；工作站上放行给系统（退出）。
        // 二级页（含主题工坊）由 OeBackStackHost 的 BackHandler 先消费。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller.back()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        composeView.doOnAttach { startGlassFrameGateIfNeeded() }
        setContentView(composeView)

        // 协同落点（Lane C 落地 / Lane D 规格，2026-11-07）：通知权限请求挂到
        // Compose 宿主首帧之后，语义与旧壳路径一致。
        composeView.post {
            if (isFinishing || isDestroyed) return@post
            if (!notificationPromptShownThisProcess.compareAndSet(false, true)) return@post
            NotificationPermissionPrompt.requestIfNeeded(this)
        }
        initUmlRootfsCardState()
        dashboardHandler.post(refreshDashboard)
    }

    private fun restoreShellStack(stack: OeBackStack, saved: Bundle?) {
        val draft = saved?.getBundle(STATE_WIZARD_DRAFT)?.let { bundle ->
            bundle.keySet().mapNotNull { key -> bundle.getString(key)?.let { key to it } }.toMap()
        }
        val wizard = draft?.let {
            WizardController(
                context = applicationContext,
                targetDistroId = it["distro"] ?: "ubuntu",
                callbacks = wizardCallbacks(),
                savedDraft = it,
            ).also { controller -> wizardController = controller }
        }
        val savedStack = saved?.getBundle(STATE_SHELL_STACK)
        if (savedStack == null) {
            // Versions that saved only the wizard draft restore the wizard directly.
            wizard?.let { stack.restore(listOf(StackEntry(1L, wizardDestination(it, stack))), 2L) }
            return
        }
        val entries = BundleCompat.getParcelableArrayList(savedStack, "entries", Bundle::class.java)
            .orEmpty().mapNotNull { entry ->
                val key = entry.getString("key") ?: return@mapNotNull null
                val argument = entry.getString("argument")
                val destination = when (key) {
                    WIZARD_DESTINATION_KEY -> wizard?.let { wizardDestination(it, stack) }
                    THEME_STUDIO_DESTINATION_KEY -> themeStudioDestination()
                    ConnectionsPage.SSH_KEY -> sshDestination(stack)
                    ConnectionsPage.PORT_FORWARDING_KEY -> argument?.let {
                        try {
                            portForwardingDestination(stack, it)
                        } catch (_: IOException) {
                            null
                        }
                    }
                    else -> ThemeStudioPage.entries.firstOrNull { it.key == key }
                        ?.let(::themeStudioSubDestination)
                } ?: return@mapNotNull null
                StackEntry(entry.getLong("id"), destination, argument)
            }
        stack.restore(entries, savedStack.getLong("nextEntryId", 1L))
    }

    /** 壳层快照：主题与 Theme Studio 呈现偏好的唯一读取点（含运行时呈现量）。 */
    private fun refreshOe3ShellSnapshot() {
        val resolvedTheme = themeStore.resolve()
        oe3ThemeState.value = resolvedTheme
        // UI3-10 玻璃层的运行时闸：动效被系统关闭或省电模式时退到实色 scrim 面。
        val animatorsEnabled = !Oe3MotionSystem.isReducedMotion(this)
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        // UI-06 玻璃模式：off/auto/always 三档；auto 按设备能力解析渲染档位，
        // 并尊重旧档位键的显式关闭（从未写过旧键才给自动玻璃）。
        val glassMode = GlassMode.fromKey(themeStore.uiGlassMode())
        oe3GlassDeviceCapState.value = GlassDeviceCap.fromKey(themeStore.uiGlassDeviceCap())
        oe3PrefsState.value = Oe3ShellPrefs(
            pureBlack = resolvedTheme.isDark && themeStore.uiPureBlack(),
            glassLevel = Oe3GlassStrategy.resolveLevel(
                mode = glassMode,
                chosenLevelKey = themeStore.uiGlassLevelKeyOrNull(),
            ),
            glassMode = glassMode,
            navStyle = Oe3NavStyle.fromKey(themeStore.uiNavStyle()),
            monoBody = themeStore.uiFontChoice() == "mono",
            textScale = themeStore.textScaleFactor(),
            reduceMotion = !animatorsEnabled,
            glassEnabled = animatorsEnabled && powerManager?.isPowerSaveMode != true,
            // 默认 16dp 是遗留的按钮 token（见 ThemeStore.cornerRadiusDpOrNull）：传给
            // Compose 壳层时按 null 处理，卡片才会落在 shape.md 的 24dp 阶梯上。
            cornerRadiusDp = themeStore.cornerRadiusDpOrNull(),
            surfaceAlpha = OeAppBackgroundSpec.surfaceAlpha(themeStore.uiSurfaceOpacityPercent()),
            backgroundUri = themeStore.backgroundUri(),
        )
        startGlassFrameGateIfNeeded()
    }

    /**
     * UI-06 运行时性能门控（W2-05 R-03 重构）：玻璃 L1 真实渲染时，首帧起连续
     * 60 帧采样**帧耗时**（`FrameMetrics.TOTAL_DURATION`）；P90 > 预算×1.25 或
     * 真滞留帧率 > 2% → 降档为 L0 并持久化到本设备（ThemeStore 设备缓存）。
     * 已降档/玻璃关闭/省电/低内存/API < 31 时不动采样——避免把与玻璃无关的
     * 卡顿误记到玻璃头上。「始终」模式在 CompositionLocal 侧绕过降档缓存，
     * 但采样照常跳过（没有可降的模糊层）。
     *
     * W2-05 两处加固：
     * - 预算优先取 API 31+ `FrameMetrics.DEADLINE`（本采样仅在 SDK ≥ 31 武装，
     *   调用安全）：该指标返回本帧可用预算时长（纳秒）＝FrameDeadline −
     *   IntendedVsync，系统已把刷新率与 SF 松弛换算好（实际预算可 > 1/刷新率）；
     *   读不到（≤0）时回退 1000/刷新率。门在**首帧回调**时才构建（预算既定）。
     * - 第三参按平台契约更名 `dropCountSinceLastInvocation` 并纳入漏报诊断：
     *   帧突发时平台会丢弃部分已入队回调，只在该计数里报告，不能当事件类型用。
     *   裁决日志区分三口径：`UserMode`（GlassMode Off/Auto/Always）、
     *   `DeviceCap`（降档前后）、`EffectiveTier`（resolveTier 结果）。
     */
    private fun startGlassFrameGateIfNeeded() {
        val prefs = oe3PrefsState.value
        val decor = window.decorView
        val eligible = oe3GlassGateResumed && !oe3GlassGateCompleted && prefs.glassMode == GlassMode.Auto &&
            decor.isHardwareAccelerated && Oe3GlassStrategy.resolveTier(
                level = prefs.glassLevel,
                enabled = prefs.glassEnabled,
                apiLevel = Build.VERSION.SDK_INT,
                isLowRamDevice = oe3GlassLowRamDevice,
                deviceCap = oe3GlassDeviceCapState.value,
                reducedMotion = prefs.reduceMotion,
            ).samplesBackdrop
        if (!eligible || oe3GlassGateLevel != prefs.glassLevel) stopGlassFrameGate()
        if (!eligible || oe3GlassGateListener != null) return
        val display = decor.display ?: return

        val glassMode = prefs.glassMode
        val refreshRate = display.refreshRate.takeIf { it > 0f } ?: 60f
        val refreshBudgetMs = 1000.0 / refreshRate.toDouble()

        // 回调只会在传入的 handler（主线程）派发，以下状态单线程可见即可。
        var totalMissedReports = 0
        var gate: Oe3GlassFrameGate? = null
        var gateBudgetMs = refreshBudgetMs

        fun onGateVerdict(verdict: GlassGateVerdict) {
            oe3GlassGateCompleted = true
            stopGlassFrameGate()
            val capBefore = oe3GlassDeviceCapState.value
            if (verdict.downgrade) {
                oe3GlassDeviceCapState.value = GlassDeviceCap.L0
                themeStore.setUiGlassDeviceCap(GlassDeviceCap.L0.key)
            }
            val capAfter = oe3GlassDeviceCapState.value
            // EffectiveTier：与 Oe3GlassSurface 同源的 resolveTier 输入，降档
            // 后的落地档位（日志/设备窗可对照，非持久化依据）。
            val tier = Oe3GlassStrategy.resolveTier(
                level = prefs.glassLevel,
                enabled = prefs.glassEnabled,
                apiLevel = Build.VERSION.SDK_INT,
                isLowRamDevice = oe3GlassLowRamDevice,
                deviceCap = capAfter,
                reducedMotion = prefs.reduceMotion,
            )
            android.util.Log.i(
                "Oe3Glass",
                "帧门裁决 UserMode=$glassMode DeviceCap=$capBefore->$capAfter " +
                    "EffectiveTier=$tier p90=${verdict.p90Ms}ms " +
                    "droppedRatio=${verdict.droppedRatio} budgetMs=$gateBudgetMs " +
                    "refreshRate=$refreshRate missedFrames=$totalMissedReports",
            )
        }

        fun buildGateFromFirstFrame(frameMetrics: android.view.FrameMetrics): Oe3GlassFrameGate {
            // 首帧回调时预算既定再喂该帧（后续帧直接喂既有门）：优先取 API 31+
            // FrameMetrics.DEADLINE。AOSP 口径（core FrameMetrics.java DURATIONS
            // 表 + getMetric 的 end−start 换算）：该指标 = FrameDeadline −
            // IntendedVsync，是**本帧可用预算时长（纳秒）**——刷新率与 SF 松弛
            // 已由系统换算进来。读点内联 SDK 守卫：版本闸在外层方法，lint 的
            // NewApi 数据流不保证跨局部函数传播，就地自证。
            val deadlineNs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                frameMetrics.getMetric(android.view.FrameMetrics.DEADLINE)
            } else {
                0L
            }
            // OEM 口径异常防线（W2-05 审查）：预算只在刷新率预算的合理倍带内
            // 采信——SF 多缓冲可放宽到数个 interval，但绝不会是 0.25× 以下或
            // 8× 以上（后者是「被当作绝对时间戳」的典型畸形值）。越带即回退
            // 刷新率读数，失效方向恒为 fail-safe（退回已知好口径），不让畸形
            // 预算放大或缩小降档判据。
            val candidate = if (deadlineNs > 0) deadlineNs / 1_000_000.0 else refreshBudgetMs
            val budget = if (candidate in (refreshBudgetMs * 0.25)..(refreshBudgetMs * 8.0)) {
                candidate
            } else {
                refreshBudgetMs
            }
            gateBudgetMs = budget
            return Oe3GlassFrameGate(budgetMs = budget) { verdict -> onGateVerdict(verdict) }
        }

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val listener = object : android.view.Window.OnFrameMetricsAvailableListener {
            override fun onFrameMetricsAvailable(
                window: android.view.Window,
                frameMetrics: android.view.FrameMetrics,
                dropCountSinceLastInvocation: Int,
            ) {
                if (oe3GlassGateListener !== this) return
                // 漏报诊断（R-03）：帧突发下平台会跳过部分回调，被跳过的帧数
                // 只经本参累计——采样窗可能由更少的回调覆盖，日志必须可区分。
                totalMissedReports += dropCountSinceLastInvocation
                val active = gate ?: buildGateFromFirstFrame(frameMetrics).also { gate = it }
                val durationMs = frameMetrics.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000.0
                if (durationMs > 0) active.onFrame(durationMs)
            }
        }
        oe3GlassGateListener = listener
        oe3GlassGateLevel = prefs.glassLevel
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }

    private fun stopGlassFrameGate() {
        val listener = oe3GlassGateListener ?: return
        oe3GlassGateListener = null
        oe3GlassGateLevel = null
        try {
            window.removeOnFrameMetricsAvailableListener(listener)
        } catch (_: IllegalArgumentException) {
            // Some devices deliver queued callbacks after platform removal.
        }
    }

    private fun buildOe3Pages(): Oe3Pages = Oe3Pages(
        workstation = {
            oe3DashboardState.value?.let { DashboardScreen(it, oe3DashboardActions) }
        },
        instances = {
            oe3InstancesState.value?.let { InstancesScreen(it, oe3InstancesActions) }
        },
        hardware = {
            oe3HardwareState.value?.let { HardwareScreen(it, oe3HardwareActions) }
        },
        distros = {
            val model = umlRootfsCardModel
            DistrosScreen(
                DistrosUiState.from(
                    umlModel = model,
                    compileBridgeWired = BuildConfig.UML_COMPILE_BRIDGE,
                ),
                oe3DistrosActions,
            )
        },
        config = {
            oe3ConfigState.value?.let {
                ConfigScreen(it, oe3ConfigNavigate, ::persistOe3ConfigOptions)
            }
        },
    )

    /**
     * 主题工坊（v4 UI-10）：根页与四个子页都作为二级页推入同一个 [OeBackStack]，
     * 返回即逐层 pop。页面自身不带 `Oe3Theme`，统一消费壳层主题，避免应用内
     * 字号被乘两次。
     */
    private fun themeStudioDestination(): OeDestination = OeDestination(
        key = THEME_STUDIO_DESTINATION_KEY,
        title = getString(R.string.ui3_theme_title),
        content = { ThemeStudioRootRoute() },
    )

    @Composable
    private fun ThemeStudioRootRoute() {
        val stack = requireNotNull(LocalOeBackStack.current) {
            "theme studio must run inside the shell back stack"
        }
        val handle = LocalMainThemeStudioHandle.current ?: rememberThemeStudioHandle(themeStore)
        ThemeStudioScreen(
            state = handle.state,
            onEvent = handle.onEvent,
            reduceMotion = oe3PrefsState.value.reduceMotion,
            onBack = { stack.pop() },
            onOpenPresets = { stack.push(themeStudioSubDestination(ThemeStudioPage.Presets)) },
            onOpenAccent = { stack.push(themeStudioSubDestination(ThemeStudioPage.Accent)) },
            onOpenBackground = { stack.push(themeStudioSubDestination(ThemeStudioPage.Background)) },
            onOpenTerminal = { stack.push(themeStudioSubDestination(ThemeStudioPage.Terminal)) },
        )
    }

    private fun themeStudioSubDestination(page: ThemeStudioPage): OeDestination = OeDestination(
        key = page.key,
        title = getString(page.titleRes),
        content = { ThemeStudioSubRoute(page) },
    )

    @Composable
    private fun ThemeStudioSubRoute(page: ThemeStudioPage) {
        val stack = requireNotNull(LocalOeBackStack.current) {
            "theme studio pages must run inside the shell back stack"
        }
        val handle = LocalMainThemeStudioHandle.current ?: rememberThemeStudioHandle(themeStore)
        val onBack: () -> Unit = { stack.pop() }
        when (page) {
            ThemeStudioPage.Presets -> ThemePresetScreen(
                state = handle.state,
                onEvent = handle.onEvent,
                onBack = onBack,
            )

            ThemeStudioPage.Accent -> ThemeAccentScreen(
                state = handle.state,
                onEvent = handle.onEvent,
                onBack = onBack,
            )

            ThemeStudioPage.Background -> ThemeBackgroundScreen(
                state = handle.state,
                onEvent = handle.onEvent,
                onBack = onBack,
                onPickBackground = { backgroundPicker.launch(arrayOf("image/*")) },
            )

            ThemeStudioPage.Terminal -> ThemeTerminalScreen(
                state = handle.state,
                onEvent = handle.onEvent,
                onBack = onBack,
                onPickTerminalBackground = { terminalBackgroundPicker.launch(arrayOf("image/*")) },
            )
        }
    }

    /** 主题工坊子页的路由标识与标题；key 稳定，供栈内判等。 */
    private enum class ThemeStudioPage(val key: String, @StringRes val titleRes: Int) {
        Presets("theme-studio.presets", R.string.ui3_theme_page_presets),
        Accent("theme-studio.accent", R.string.ui3_theme_page_accent),
        Background("theme-studio.background", R.string.ui3_theme_page_background),
        Terminal("theme-studio.terminal", R.string.ui3_theme_page_terminal),
    }

    // ==================== Oe3 五页动作（全部路由到既有入口） ====================

    private val oe3DashboardActions by lazy {
        DashboardActions(
            openTerminal = { id ->
                instanceById(id) { instance ->
                    if (instance == null) {
                        toastInstanceMissing()
                    } else if (instance.state() == InstanceLifecycle.State.RUNNING) {
                        openTerminal(instance)
                    } else {
                        launchInstance(instance)
                    }
                }
            },
            // 工作站的创建入口 = 发行版装配目录（旧壳空态 CTA 同语义），不新造向导分叉。
            createInstance = { oe3Shell?.select(Oe3Tab.Distros) },
            openInstance = { id -> openInstanceDetail(id) },
            // 存储读数与磁盘事实在硬件页；没有独立的存储设置页，路由不编造。
            openStorageSettings = { oe3Shell?.select(Oe3Tab.Hardware) },
        )
    }

    private val oe3InstancesActions by lazy {
        InstancesActions(
            openTerminal = { id ->
                instanceById(id) { instance ->
                    if (instance == null) {
                        toastInstanceMissing()
                    } else if (instance.state() == InstanceLifecycle.State.RUNNING) {
                        openTerminal(instance)
                    } else {
                        launchInstance(instance)
                    }
                }
            },
            launch = { id ->
                instanceById(id) { instance ->
                    if (instance == null) toastInstanceMissing() else launchInstance(instance)
                }
            },
            // 产品尚无独立的挂起恢复通道（无 InstancePowerController）；
            // 挂起实例路由到实例详情页管理，不在壳层发明新启动语义。
            resume = { id -> openInstanceDetail(id) },
            openInstance = { id -> openInstanceDetail(id) },
            createInstance = { oe3Shell?.select(Oe3Tab.Distros) },
            importArchive = { oe3ImportArchive() },
            refresh = {
                oe3InstancesRefreshingState.value = true
                loadDashboardSnapshot { snapshot -> updateOe3Pages(snapshot) }
            },
            powerOff = { id ->
                instanceById(id) { instance ->
                    if (instance == null) toastInstanceMissing() else showPowerDialog(instance)
                }
            },
            openDesktop = { id ->
                instanceById(id) { instance ->
                    if (instance == null) toastInstanceMissing() else launchDesktop(instance)
                }
            },
            openLogs = { id -> openInstanceLogs(id) },
            exportInstance = { id ->
                instanceById(id) { instance ->
                    if (instance == null) toastInstanceMissing() else exportStoppedInstance(instance)
                }
            },
            deleteInstance = { id ->
                instanceById(id) { instance ->
                    if (instance == null) toastInstanceMissing() else confirmDeleteInstance(instance)
                }
            },
        )
    }

    private val oe3HardwareActions by lazy {
        HardwareActions(
            refresh = { refreshOe3Hardware() },
            requestAvfAuthorization = { showAvfAuthorizationWizard() },
        )
    }

    private val oe3DistrosActions by lazy {
        DistrosActions(
            // 发行版 CTA 复用既有装配向导入口（旧壳 create_* 同一 target）。
            install = { id -> launchCreationWizard(id) },
            provisionUmlRootfs = { startUmlRootfsProvision() },
        )
    }

    private val oe3ConfigNavigate: (ConfigRoute) -> Unit = { route -> navigateOe3Config(route) }

    /** 配置目录的路由分发；目标全部是既有 Activity / 对话框 / SAF 通道。 */
    private fun navigateOe3Config(route: ConfigRoute) {
        when (val destination = route.destination) {
            is ConfigDestination.SettingsSection ->
                startActivity(SettingsSubActivity.intent(this, destination.section))
            ConfigDestination.ThemeStudio -> {
                // 已在工坊内（根页或任一子页）时不重复压栈，避免返回两层同一根。
                val stack = oe3BackStack
                if (stack != null &&
                    stack.top?.key?.startsWith("theme-studio") != true
                ) {
                    stack.push(themeStudioDestination())
                }
            }
            ConfigDestination.SshManager -> pushOe3SshManager()
            ConfigDestination.PortForwarding -> openOe3PortForwarding()
            ConfigDestination.Logs -> openRuntimeLogs()
            ConfigDestination.Backup -> showOe3BackupDialog()
            ConfigDestination.Import -> oe3ImportArchive()
            ConfigDestination.Export -> chooseAndExportOe3Instance()
            ConfigDestination.UpdateCheck, ConfigDestination.AboutPage ->
                startActivity(Intent(this, AboutActivity::class.java))
            ConfigDestination.Licenses -> showOe3LicensesDialog()
        }
    }

    private fun persistOe3ConfigOptions(next: ConfigOptions) {
        val previous = configPreferences.readOptions()
        oe3ConfigState.value = oe3ConfigState.value?.copy(
            options = configPreferences.writeOptions(previous, next),
        )
    }

    // ==================== Oe3 数据供给（复用既有快照周期） ====================

    /**
     * onCreate 需要的共享设施：与旧壳同一批对象（instanceStore /
     * launchCoordinator / updateCenter / AVF 判定 / ConfigPreferences），
     * 全部沿用既有构造，不新增数据源。
     */
    private fun ensureSharedInfrastructure() {
        if (!::instanceStore.isInitialized) {
            instanceStore = InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"))
            // 冷启动主线程卫生（Lane C）：孤儿回收在 executor 上执行。
            operations.execute { reconcileOrphanInstances() }
        }
        if (!::launchCoordinator.isInitialized) {
            launchCoordinator = buildLaunchCoordinator()
        }
        if (!::updateCenter.isInitialized) {
            updateCenter = UpdateCoordinator.center(this)
        }
        if (avfReadiness == null) {
            avfReadiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
            wizardModel = AvfAuthorizationWizardModel.fromAssessment(avfReadiness!!)
        }
        if (!::configPreferences.isInitialized) {
            configPreferences = ConfigPreferences(SharedPreferencesConfigStore(this))
        }
    }

    /**
     * 快照到五页状态的唯一折算点。3 秒周期、onResume、拉手刷新共用；
     * 每一页的输入都来自快照里已有的读数与既有判定，不做二次探测。
     */
    private fun updateOe3Pages(snapshot: DashboardSnapshot) {
        val badge = currentBadge(DeviceCapabilityEvaluator.evaluate(deviceProfile()))
        oe3HardwareProfile = snapshot.hardwareProfile
        oe3Storage = StorageFacts.from(snapshot)

        val primaryCard = snapshot.primary?.let {
            buildInstanceUiModel(it, badge, snapshot.acceleratorLaneReady)
        }
        oe3DashboardState.value = DashboardUiState.from(
            snapshot = snapshot,
            card = primaryCard,
            storage = oe3Storage,
            engineLabel = badge,
        )
        val cards = snapshot.instances.map {
            buildInstanceUiModel(it, badge, snapshot.acceleratorLaneReady)
        }
        oe3InstancesState.value = InstancesUiState.from(
            cards = cards,
            engineLabel = badge,
            progress = InstancesProgressUi.of(oe3ProgressText.value, fraction = null),
            isRefreshing = oe3InstancesRefreshingState.value,
        )
        refreshOe3HardwareState()
        refreshOe3ConfigState()
        oe3InstancesRefreshingState.value = false
    }

    private fun refreshOe3Hardware() {
        operations.execute {
            val facts = HostUiFacts.probe(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                oe3HostUiFacts.value = facts
                refreshOe3HardwareState()
                Toast.makeText(this, R.string.toast_hardware_refreshed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 硬件页状态折算；事实缺失时模型层按「—」占位，不编造读数。 */
    private fun refreshOe3HardwareState() {
        val profile = oe3HardwareProfile ?: HostHardwareDetector.detect(this).also {
            oe3HardwareProfile = it
        }
        val readiness = avfReadiness
            ?: AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this)).also {
                avfReadiness = it
            }
        val engines = HardwareEngineFacts(
            avfStatus = readiness.status(),
            avfReasons = readiness.reasons(),
            qemuAvailable = DeviceCapabilityEvaluator.evaluate(deviceProfile())
                .canCreateFullLinuxInstance(),
            umlRunningWidth = UmlFaceStatusRegistry.runningWidth(),
            umlChannelWired = UmlFaceStatusRegistry.channelWired(),
        )
        val facts = oe3HostUiFacts.value
        val device = HardwareDeviceFacts(
            model = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }
                .joinToString(" ").ifBlank { Build.MODEL },
            androidRelease = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.filter { it.isNotBlank() } ?: emptyList(),
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL?.takeIf { it.isNotBlank() } else null,
            kernelVersion = System.getProperty("os.version")?.takeIf { it.isNotBlank() },
            screenSpec = facts.screenSpec,
            gpuRenderer = facts.gpuRenderer,
            selinuxStatus = facts.selinuxStatus,
        )
        oe3HardwareState.value = HardwareUiState.from(
            device = device,
            hardware = profile,
            storage = oe3Storage,
            engines = engines,
            battery = batteryFacts,
        )
    }

    private fun refreshOe3ConfigState() {
        val pending = updateCenter.pending(BuildConfig.VERSION_NAME)
        oe3ConfigState.value = ConfigScreenState(
            versionName = BuildConfig.VERSION_NAME,
            engineSummary = engineSummaryLine(),
            updateAvailable = pending?.version(),
            updateNote = pending?.summary(),
            options = configPreferences.readOptions(),
        )
    }

    /** 引擎与授权行的一句话判定（配置页与详情页共用同一套判定）。 */
    private fun engineSummaryLine(): String {
        val readiness = avfReadiness
            ?: AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this)).also {
                avfReadiness = it
            }
        return when (readiness.status()) {
            AvfCapabilityEvaluator.AvfStatus.READY -> getString(R.string.config_engine_ready)
            AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION ->
                getString(R.string.config_engine_needs_authorization)
            AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED ->
                getString(R.string.config_engine_mode_unsupported)
            AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN ->
                getString(R.string.config_engine_mode_unknown)
            AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED ->
                getString(R.string.config_engine_hidden_api_blocked)
            AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE ->
                getString(R.string.config_engine_reflection_unavailable)
            AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED ->
                getString(R.string.config_engine_unsupported)
            else -> getString(R.string.config_engine_unknown)
        }
    }

    // ==================== Oe3 动作的既有入口粘合 ====================

    /** 单实例异步读取：与快照任务共用 operations executor，不阻塞主线程。 */
    private fun instanceById(id: String, onReady: (InstanceLifecycle.Instance?) -> Unit) {
        operations.execute {
            val loaded = try {
                instanceStore.load(id)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                onReady(loaded)
            }
        }
    }

    private fun toastInstanceMissing() {
        Toast.makeText(this, R.string.toast_instance_missing, Toast.LENGTH_SHORT).show()
    }

    private fun openInstanceDetail(id: String) {
        startActivity(
            Intent(this, InstanceDetailActivity::class.java)
                .putExtra(InstanceDetailActivity.EXTRA_INSTANCE_ID, id),
        )
    }

    /** 实例日志：运行中实例走 GUEST 通道，其余走 App 通道（旧壳同一语义）。 */
    private fun openInstanceLogs(id: String) {
        instanceById(id) { instance ->
            startActivity(
                Intent(this, LogViewerActivity::class.java).apply {
                    putExtra(
                        LogViewerActivity.EXTRA_KIND,
                        if (instance?.state() == InstanceLifecycle.State.RUNNING) {
                            LogViewerActivity.KIND_GUEST
                        } else {
                            LogViewerActivity.KIND_APP
                        },
                    )
                    instance?.let { putExtra(LogViewerActivity.EXTRA_INSTANCE_ID, it.id()) }
                },
            )
        }
    }

    /** 配置页「日志」行：运行中实例走 GUEST，否则 App 日志（同引擎摘要语义）。 */
    private fun openRuntimeLogs() {
        operations.execute {
            val running = try {
                instanceStore.list().firstOrNull { it.state() == InstanceLifecycle.State.RUNNING }
            } catch (_: IOException) {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                startActivity(
                    Intent(this, LogViewerActivity::class.java).apply {
                        putExtra(
                            LogViewerActivity.EXTRA_KIND,
                            if (running != null) LogViewerActivity.KIND_GUEST else LogViewerActivity.KIND_APP,
                        )
                        running?.let { putExtra(LogViewerActivity.EXTRA_INSTANCE_ID, it.id()) }
                    },
                )
            }
        }
    }

    private fun oe3ImportArchive() {
        safImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun chooseAndExportOe3Instance() {
        operations.execute {
            val instances = try {
                instanceStore.list()
            } catch (_: IOException) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    instances.isEmpty() -> Toast.makeText(
                        this, R.string.config_no_instances, Toast.LENGTH_SHORT,
                    ).show()
                    instances.size == 1 -> exportStoppedInstance(instances.first())
                    else -> {
                        val labels = instances
                            .map { "${it.hostname()} · ${it.id()}" }
                            .toTypedArray()
                        OeDialogHost.attach(this@MainActivity).show(
                            OeDialogSpec(
                                title = getString(R.string.config_choose_instance),
                                items = labels.toList(),
                                onItemSelected = { which ->
                                    exportStoppedInstance(instances[which])
                                },
                                negative = OeDialogAction(getString(R.string.config_dialog_cancel)),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun showOe3BackupDialog() {
        OeDialogHost.attach(this).show(
            OeDialogSpec(
                title = getString(R.string.config_backup_dialog_title),
                message = getString(R.string.config_backup_dialog_message),
                items = listOf(
                    getString(R.string.config_backup_export),
                    getString(R.string.config_backup_import),
                ),
                onItemSelected = { which ->
                    if (which == 0) chooseAndExportOe3Instance() else oe3ImportArchive()
                },
                negative = OeDialogAction(getString(R.string.config_dialog_cancel)),
            ),
        )
    }

    private fun showOe3LicensesDialog() {
        val notice = try {
            assets.open("third-party-notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: IOException) {
            getString(R.string.config_licenses_unavailable)
        }
        OeDialogHost.attach(this).show(
            OeDialogSpec(
                title = getString(R.string.config_licenses_title),
                longText = notice,
                positive = OeDialogAction(getString(R.string.config_dialog_ok)),
            ),
        )
    }

    private fun openOe3PortForwarding() {
        operations.execute {
            val instances = try {
                instanceStore.list()
            } catch (_: IOException) {
                emptyList()
            }
            val target = instances.firstOrNull { it.state() == InstanceLifecycle.State.RUNNING }
                ?: instances.firstOrNull { it.id() == settingsManager.lastActiveInstanceId }
                ?: instances.firstOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (target == null) {
                    Toast.makeText(this, R.string.config_no_instances, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                pushOe3PortForwarding(target)
            }
        }
    }

    /**
     * SSH 与端口转发是「列表 + 表单」类页面（spec §2.3），壳内一律 push
     * 二级页；旧对话框/底部表已删除。返回由栈统一 pop。
     */
    private fun pushOe3SshManager() {
        val stack = oe3BackStack ?: return
        if (stack.top?.key == ConnectionsPage.SSH_KEY) return
        stack.push(sshDestination(stack))
    }

    private fun sshDestination(stack: OeBackStack) = OeDestination(
        key = ConnectionsPage.SSH_KEY,
        title = getString(R.string.connections_ssh_title),
        content = { SshManagerPage(onBack = { stack.pop() }) },
    )

    private fun pushOe3PortForwarding(instance: InstanceLifecycle.Instance) {
        val stack = oe3BackStack ?: return
        if (stack.top?.key == ConnectionsPage.PORT_FORWARDING_KEY) return
        try {
            stack.push(portForwardingDestination(stack, instance.id()), argument = instance.id())
        } catch (e: Exception) {
            // 异常详情只进日志，不进用户文案（voice.md §5）。
            android.util.Log.w("MainActivity", "open port forwarding failed", e)
            Toast.makeText(this, R.string.toast_port_forwarding_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun portForwardingDestination(stack: OeBackStack, instanceId: String): OeDestination {
        val manager = PortForwardingManager.forInstance(instanceId, filesDir.toPath().resolve("network"))
        return OeDestination(
            key = ConnectionsPage.PORT_FORWARDING_KEY,
            title = getString(R.string.connections_port_forwarding_title),
            content = {
                PortForwardingPage(instanceId = instanceId, manager = manager, onBack = { stack.pop() })
            },
        )
    }

    /** 既有冷备份闸门（UI3-12 收敛为 ColdBackupGuard 单源）。 */
    private fun requireColdBackup(instanceId: String): InstanceLifecycle.Instance =
        ColdBackupGuard.requireExportable(instanceId, instanceStore, "请先关闭实例，再导出完整冷备份")

    private fun exportStoppedInstance(instance: InstanceLifecycle.Instance) {
        try {
            pendingExportInstance = requireColdBackup(instance.id())
            safExportLauncher.launch("${instance.id()}-backup.zip")
        } catch (e: IOException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    // ==================== 快照与单实例展示事实 ====================

    /**
     * 后台取数：一次快照涵盖实例列表、主实例、SSH 规则预取、加速车道判定与
     * 硬件/存储读数。读取失败按空列表快照继续回调（页面按空态/失败文案呈现）。
     */
    private fun loadDashboardSnapshot(onReady: (DashboardSnapshot) -> Unit) {
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

    /** 单实例 UI 模型推导（原旧壳循环内联逻辑原位迁移，Oe3 两页共用）。 */
    private fun buildInstanceUiModel(
        instance: InstanceLifecycle.Instance,
        strategyBadge: String,
        laneReady: Boolean,
    ): InstanceCardUiModel {
        val session = ExecutionSessionRegistry.get(instance.id())
        val runningStrategy = when (session) {
            is QemuInstanceLauncher.Session ->
                // Session-18 W-B (user-approved copy): when the pVM lane is
                // really ready, say so; otherwise the plain software
                // virtualization wording stays — no fake acceleration.
                if (laneReady) BadgeCopy.derive(true, "") else "QEMU 软件虚拟化"
            is AvfInstanceLauncher.AvfSession -> "AVF 硬件虚拟化"
            else -> session?.capabilityMatrix()?.performanceBadge() ?: strategyBadge
        }
        val guestIp = (session as? QemuInstanceLauncher.Session)?.let { NetworkGatewayDaemon.GUEST_IP }
        // G-04：卡片/sheet 的桌面能力位与三档入口共用 DesktopEntryPolicy 单源判定。
        return InstanceCardUiModel.from(instance, runningStrategy, supportsDesktop = DesktopEntryPolicy.supportsDesktop(), guestIp)
    }

    // ==================== 电源 / 终端 / 删除（既有对话框链） ====================

    private fun showPowerDialog(instance: InstanceLifecycle.Instance) {
        val options = arrayOf("正常关机", "强制断电（可能丢失未写入的数据）")
        val host = OeDialogHost.attach(this)
        host.show(
            OeDialogSpec(
                title = "电源管理 · ${instance.hostname()}",
                items = options.toList(),
                onItemSelected = { which ->
                    if (which == 1) {
                        host.show(
                            OeDialogSpec(
                                title = "强制关闭实例",
                                message = "这会直接停止虚拟机。建议先在 Linux 中保存文件并正常关机。",
                                positive = OeDialogAction("确认断电", destructive = true) {
                                    requestInstanceStop(instance, true)
                                },
                                negative = OeDialogAction("取消"),
                            ),
                        )
                    } else {
                        requestInstanceStop(instance, false)
                    }
                },
                negative = OeDialogAction("取消"),
            ),
        )
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
                    setCreateStatusLine("关机请求已发送，等待实例退出")
                }
            } catch (e: Exception) {
                // 异常详情只进日志，不进用户文案（voice.md §5；ZD-W2 wave4 P3 漂移）。
                android.util.Log.w("MainActivity", "instance stop request failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.toast_operation_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isHeadless(@Suppress("UNUSED_PARAMETER") instance: InstanceLifecycle.Instance): Boolean {
        // G-04：headless 兜底判定与三档入口 enabled 同源（DesktopEntryPolicy）——
        // 生产通道尚不携带 guest 图形帧与输入（Phase 5 开发中，未真机验收），
        // 通道未就绪时入口禁用，这里保留同一判定驱动的 headless 引导弹窗。
        return !DesktopEntryPolicy.supportsDesktop()
    }

    private fun showHeadlessDesktopDialog(instance: InstanceLifecycle.Instance) {
        OeDialogHost.attach(this).show(
            OeDialogSpec(
                title = "图形桌面通道尚未就绪",
                message = "当前基础 Linux 环境可在终端使用。App 的真实 guest 图形帧与输入通道仍在独立 Phase 5 开发中，安装桌面软件包后也需要该通道才能在 App 内交互。",
                positive = OeDialogAction(getString(R.string.instance_open_terminal)) {
                    openTerminal(instance)
                },
                negative = OeDialogAction(getString(R.string.dialog_close)),
            ),
        )
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

    private fun confirmDeleteInstance(instance: InstanceLifecycle.Instance) {
        if (!InstanceActionSafety.canDelete(instance.state())) {
            Toast.makeText(this, R.string.action_delete_running_forbidden, Toast.LENGTH_SHORT).show()
            return
        }

        OeDialogHost.attach(this).show(
            OeDialogSpec(
                title = getString(R.string.action_delete_confirm_title),
                message = getString(R.string.action_delete_confirm_message, instance.id()),
                positive = OeDialogAction(
                    getString(R.string.action_delete_confirm_btn),
                    destructive = true,
                ) {
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
                                refreshInstanceListAfterMutation()
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
                },
                negative = OeDialogAction(getString(R.string.action_delete_cancel_btn)),
            ),
        )
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

    // ==================== UML rootfs 卡（状态机原样，视图改由发行版页消费） ====================

    /** 发行版页的 UML 组初始状态；探测在 executor 上做，点击入口走既有状态机。 */
    private fun initUmlRootfsCardState() {
        if (!UmlRootfsCardState.visible(BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED)) {
            umlRootfsCardModel = null
            return
        }
        umlRootfsCardModel = UmlRootfsCardState.notReady()
        operations.execute {
            try {
                val readiness = UmlRootfsProvisioner.readiness(filesDir.toPath())
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    umlRootfsCardModel = UmlRootfsCardState.initial(
                        BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED, readiness)
                }
            } catch (probeFailure: Exception) {
                // A probe that cannot read stays honestly at NOT_READY; the
                // provision path surfaces the real failure on click.
            }
        }
    }

    /** The card's only entry action: provision off the main thread, state per transition. */
    private fun startUmlRootfsProvision() {
        val model = umlRootfsCardModel ?: return
        if (model.phase() == UmlRootfsCardState.Phase.DOWNLOADING ||
            model.phase() == UmlRootfsCardState.Phase.READY
        ) {
            return
        }
        umlRootfsCardModel = UmlRootfsCardState.downloading(0, 0)
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
                    }
                }
                val receipt = UmlRootfsProvisioner.provision(engine, filesDir.toPath())
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    umlRootfsCardModel = UmlRootfsCardState.ready(receipt)
                }
            } catch (failure: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    umlRootfsCardModel = UmlRootfsCardState.failed(failure)
                }
            }
        }
    }

    // ==================== 装配向导与启动 intent ====================

    /**
     * UI-12：向导入口从「启动 SetupWizardActivity」改为壳内导航目的地
     * （push [OeDestination]）。创建进行中的引擎复用（不重复起下载）；
     * 空闲重进 = 新引擎（与旧「每次启动新 Activity」语义一致）。
     */
    private fun launchCreationWizard(distroId: String) {
        val stack = oe3BackStack ?: return
        val existing = wizardController
        if (existing == null || !existing.state.value.isCreating) {
            existing?.dispose()
            wizardController = WizardController(
                context = applicationContext,
                targetDistroId = distroId,
                callbacks = wizardCallbacks(),
            ).also { wizardController = it }
        }
        val active = requireNotNull(wizardController)
        if (stack.top?.key != WIZARD_DESTINATION_KEY) {
            stack.push(wizardDestination(active, stack))
        }
    }

    private fun wizardCallbacks(): WizardController.Callbacks = WizardController.Callbacks(
        onCreated = { onWizardCreated() },
        onShowShizukuClient = {
            ShizukuClientDialog().show(supportFragmentManager, "shizuku-client")
        },
    )

    private fun wizardDestination(
        controller: WizardController,
        stack: OeBackStack,
    ): OeDestination = OeDestination(
        key = WIZARD_DESTINATION_KEY,
        title = getString(R.string.wizard_page_title),
        content = {
            WizardScreen(controller = controller, onExit = { stack.pop() })
        },
    )

    /** 创建成功：壳层回到实例页并刷新（旧 startActivity(MainActivity, CLEAR_TOP) 语义）。 */
    private fun onWizardCreated() {
        wizardController?.dispose()
        wizardController = null
        oe3BackStack?.popAll()
        oe3Shell?.select(Oe3Tab.Instances)
        oe3InstancesRefreshingState.value = true
        loadDashboardSnapshot { snapshot -> updateOe3Pages(snapshot) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        // W3-01 / R-01：仅 `-Porchestella.buildChannel=qa` 构建允许经独立 QA 意图拉起
        // USER_MODE_KERNEL Alpine 临时闭环实例，不影响正式渠道与既有启动路由。
        if (BuildConfig.IS_ISOLATED_QA_BUILD &&
            intent?.getBooleanExtra("qa_launch_standalone_uml_alpine", false) == true &&
            ::instanceStore.isInitialized
        ) {
            intent.removeExtra("qa_launch_standalone_uml_alpine")
            val requestedId = intent.getStringExtra("action_launch_instance_id")
            if (requestedId != null) {
                intent.removeExtra("action_launch_instance_id")
            }
            val store = instanceStore
            val qaRootsDir = filesDir.toPath().resolve("qa-uml-roots")
            operations.execute {
                val controller = InstanceLifecycle.Controller(store, Clock.systemUTC())
                val prepared = try {
                    IsolatedQaExecutionEntry().ensureEphemeralAlpineInstance(
                        store = store,
                        controller = controller,
                        qaRootsDir = qaRootsDir,
                        requestedId = requestedId,
                    )
                } catch (failure: Exception) {
                    android.util.Log.w("MainActivity", "QA standalone UML preparation failed", failure)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) toastInstanceMissing()
                    }
                    return@execute
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    launchInstance(prepared)
                }
            }
            return
        }
        // UI3-12：Oe3 恢复 action_launch_instance_id（旧壳同语义：读实例并直接
        // 启动）；instanceStore 未初始化的守卫保留，避免装配槽未就绪时崩溃。
        val targetLaunchId = intent?.getStringExtra("action_launch_instance_id")
        if (targetLaunchId != null && ::instanceStore.isInitialized) {
            intent.removeExtra("action_launch_instance_id")
            // 主线程卫生（Lane C）：实例元数据读取迁 operations executor；
            // launchInstance 仍回主线程触发，保持其内部 UI 写入线程不变。
            val store = instanceStore
            operations.execute {
                val loaded = try {
                    store.load(targetLaunchId)
                } catch (failure: Exception) {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        // ZD-W2：用户文案中性化（voice.md §5），异常详情写日志不进 UI；
                        // toast 与状态条仍共用同一份全文（583ec531 语义，不漂移成短句）。
                        android.util.Log.w("MainActivity", "instance read failed for launch intent", failure)
                        val message = getString(R.string.toast_instance_read_failed)
                        setCreateStatusLine(message)
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
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

    // ==================== 生命周期 ====================

    /**
     * Session 19 (T-B.2): the shell reads persisted theme prefs live now; this
     * listener keeps the shell snapshot (and the legacy hand-painted chrome in
     * other activities) in sync with any ThemeStore write.
     */
    private val surfaceOpacityListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) refreshOe3ShellSnapshot()
            }
        }

    override fun onStart() {
        super.onStart()
        getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(surfaceOpacityListener)
        stopAnimatorScaleObserver = Oe3MotionSystem.observeAnimatorScale(this) {
            if (!isFinishing && !isDestroyed) refreshOe3ShellSnapshot()
        }
        startPowerMonitoring()
    }

    override fun onStop() {
        stopPowerMonitoring()
        stopAnimatorScaleObserver?.invoke()
        stopAnimatorScaleObserver = null
        getSharedPreferences(ThemeStore.PREFS_NAME, Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(surfaceOpacityListener)
        super.onStop()
    }

    /**
     * 电池广播带着粘性读数，直接更新硬件页缓存。剖面尚未就绪时由首份快照带上读数，
     * 避免在广播处理里同步探测宿主。省电模式变更另外刷新壳层的玻璃能力。
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> refreshOe3ShellSnapshot()
                Intent.ACTION_BATTERY_CHANGED -> {
                    batteryFacts = BatteryFactProvider.factsFromIntent(
                        intent = intent,
                        capacityPercent = BatteryFactProvider.capacityPercent(this@MainActivity),
                    )
                    if (oe3HardwareProfile != null) refreshOe3HardwareState()
                }
            }
        }
    }

    /** 首份硬件页状态需要电池读数，注册省电和电池广播前先同步读取一次。 */
    private fun startPowerMonitoring() {
        if (powerReceiverRegistered) return
        batteryFacts = BatteryFactProvider.read(this)
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        powerReceiverRegistered = true
    }

    private fun stopPowerMonitoring() {
        if (!powerReceiverRegistered) return
        unregisterReceiver(powerReceiver)
        powerReceiverRegistered = false
    }

    override fun onResume() {
        super.onResume()
        oe3GlassGateResumed = true
        // Theme Studio 在壳内做的偏好改动随快照周期生效；返回前台先补一轮判定。
        refreshOe3ShellSnapshot()
        if (::updateCenter.isInitialized) {
            refreshOe3ConfigState()
            UpdateCoordinator.checkInBackground(
                this, BuildConfig.VERSION_NAME,
            ) {
                if (!isFinishing && !isDestroyed) refreshOe3ConfigState()
            }
        }
        if (::instanceStore.isInitialized) {
            avfReadiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
            operations.execute { reconcileOrphanInstances() }
            refreshOe3Hardware()
            loadDashboardSnapshot { snapshot -> updateOe3Pages(snapshot) }
        }
        dashboardHandler.removeCallbacks(refreshDashboard)
        dashboardHandler.post(refreshDashboard)
    }

    override fun onPause() {
        oe3GlassGateResumed = false
        stopGlassFrameGate()
        dashboardHandler.removeCallbacks(refreshDashboard)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        oe3Shell?.let { outState.putString(Oe3ShellController.STATE_KEY, it.selectedTab.key) }
        oe3BackStack?.let { stack ->
            val entries = stack.transition.entries.mapTo(ArrayList()) { entry ->
                Bundle().apply {
                    putLong("id", entry.id)
                    putString("key", entry.destination.key)
                    putString("argument", entry.argument)
                }
            }
            outState.putBundle(STATE_SHELL_STACK, Bundle().apply {
                putParcelableArrayList("entries", entries)
                putLong("nextEntryId", stack.nextEntryId)
            })
        }
        // UI-12：向导草稿随 Activity 状态持久（旧 SetupWizardActivity STATE_DRAFT 语义）。
        wizardController?.let { controller ->
            val draft = Bundle()
            controller.saveDraft().forEach { (key, value) -> draft.putString(key, value) }
            outState.putBundle(STATE_WIZARD_DRAFT, draft)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        stopGlassFrameGate()
        dashboardHandler.removeCallbacks(refreshDashboard)
        super.onDestroy()
        if (isFinishing) operations.shutdown()
    }

    /**
     * ReportFullyDrawn 接线（Lane D, 2026-11-10）：启动链路的「首帧数据齐备点」=
     * 第一份仪表盘快照在 UI 线程分发。布尔门保证每次 Activity 实例只报一次；
     * strict 失败路径（错误态同样是「可用」状态）也上报，指标不悬挂。
     */
    private fun maybeReportFullyDrawn() {
        if (fullyDrawnReported || isFinishing || isDestroyed) return
        fullyDrawnReported = true
        oe3ComposeView?.post {
            if (isFinishing || isDestroyed) return@post
            try {
                reportFullyDrawn()
            } catch (failure: Exception) {
                // 个别 ROM 在窗口第一帧完成前上报会抛异常——指标接线永不影响功能路径。
                android.util.Log.w("MainActivity", "reportFullyDrawn rejected", failure)
            }
        }
    }

    // ==================== 判定与徽章（引擎 chip 事实源） ====================

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
     * false, no acceleration wording may appear anywhere on the pages.
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

    // ==================== AVF 授权向导（既有对话框链） ====================

    private fun showAvfAuthorizationWizard() {
        avfReadiness = AvfCapabilityEvaluator.evaluate(AvfHostFactsProbe.probe(this))
        val current = AvfAuthorizationWizardModel.fromAssessment(avfReadiness!!)
        if (!current.canStart()) return
        wizardModel = current.start()
        showShizukuStep()
    }

    private fun showShizukuStep() {
        OeDialogHost.attach(this).show(
            OeDialogSpec(
                title = getString(R.string.wizard_title),
                message = getString(R.string.wizard_shizuku_steps),
                positive = OeDialogAction(getString(R.string.shizuku_client_entry)) {
                    ShizukuClientDialog().show(supportFragmentManager, "shizuku-client")
                },
                neutral = OeDialogAction(getString(R.string.wizard_use_adb)) {
                    wizardModel = wizardModel!!.chooseAdbFallback()
                    showAdbStep()
                },
                negative = OeDialogAction(getString(R.string.wizard_recheck_permissions)) {
                    recheckAuthorization()
                },
            ),
        )
    }

    private fun showAdbStep() {
        OeDialogHost.attach(this).show(
            OeDialogSpec(
                title = getString(R.string.wizard_title),
                message = getString(
                    R.string.wizard_adb_steps,
                    AvfAuthorizationWizardModel.adbGrantCommand(packageName)
                ),
                positive = OeDialogAction(getString(R.string.wizard_authorization_done)) {
                    recheckAuthorization()
                },
                neutral = OeDialogAction(getString(R.string.wizard_copy_command)) {
                    copyAdbCommand()
                    showAdbStep()
                },
                negative = OeDialogAction(getString(R.string.wizard_back_to_shizuku)) {
                    wizardModel = wizardModel!!.backToShizuku()
                    showShizukuStep()
                },
            ),
        )
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
        refreshOe3HardwareState()
        refreshOe3ConfigState()
        if (grantedNow) {
            OeDialogHost.attach(this).show(
                OeDialogSpec(
                    title = getString(R.string.wizard_authorized_success_title),
                    message = getString(R.string.wizard_authorized_success_msg),
                    positive = OeDialogAction(getString(R.string.credentials_close)),
                ),
            )
        } else {
            OeDialogHost.attach(this).show(
                OeDialogSpec(
                    title = getString(R.string.wizard_vendor_failure_title),
                    message = getString(R.string.wizard_vendor_failure_steps),
                    positive = OeDialogAction(getString(R.string.credentials_close)),
                ),
            )
        }
    }

    // ==================== 进度文字（实例页横幅的唯一出口） ====================

    /** 创建/导入/停止等异步进度的统一文字出口；实例页进度横幅消费同一份。 */
    private fun setCreateStatusLine(text: CharSequence) {
        oe3ProgressText.value = text.toString()
    }

    /** 实例列表在共享流程（导入/删除等）后的刷新出口：走快照周期。 */
    private fun refreshInstanceListAfterMutation() {
        loadDashboardSnapshot { snapshot -> updateOe3Pages(snapshot) }
    }

    // ==================== 启动编排（既有深模块，Android 侧绑定在此） ====================

    /**
     * 启动编排的唯一构造点：Android 侧启动件（前台服务、AVF/QEMU/PVM 启动器、
     * 事实源、资源串）在此绑定，编排决策与世代门卫全部在
     * InstanceLaunchCoordinator 内。
     */
    private fun buildLaunchCoordinator(): InstanceLaunchCoordinator = InstanceLaunchCoordinator(
        store = instanceStore,
        settings = launchSettings,
        foreground = launchForeground,
        ui = launchFeedback,
        copy = launchCopy,
        diagnostics = launchDiagnostics,
        cleanupBridge = InstanceLaunchCoordinator.CleanupBridge { instanceId ->
            UmlInstanceLauncher.takeLastCleanupResult(instanceId)
                ?: QemuInstanceLauncher.takeLastCleanupResult(instanceId)
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
        isolatedQaEnabled = BooleanSupplier { BuildConfig.IS_ISOLATED_QA_BUILD },
        standaloneUmlLaunch = if (BuildConfig.IS_ISOLATED_QA_BUILD) {
            InstanceStrategyLauncher.UserModeKernelLaunch { params ->
                UmlInstanceLauncher(this, instanceStore).launch(params)
            }
        } else {
            null
        },
    )

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
        // Compose 壳层没有「创建按钮组」；启动可玩性由快照数据驱动，复位为空操作。
        override fun resetCreateButtons() = Unit
        override fun showStatus(message: String) {
            setCreateStatusLine(message)
        }
        override fun showAttached(instance: InstanceLifecycle.Instance) {
            setCreateStatusLine("已连接到运行中的实例")
            openTerminal(instance)
        }
        override fun showLaunchBusyStatus() {
            setCreateStatusLine("实例正在启动，请稍后再试")
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
            setCreateStatusLine(message)
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
        override fun showInstances() {
            loadDashboardSnapshot { snapshot -> updateOe3Pages(snapshot) }
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
        // 协同落点（2026-11-07）：通知权限已在首帧后请求过
        // （requestNotificationPermissionAfterFirstFrame），不再在 VM 启动路径上
        // 弹窗打断。
        setCreateStatusLine(getString(R.string.launch_started))
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
}

/**
 * Theme Studio 呈现偏好的壳层快照（UI3-12）：MainActivity 从 ThemeStore 读出
 * 后经 [refreshOe3ShellSnapshot] 传给 [Oe3App]，Oe3App 保持状态无知。四个偏好
 * 字段与 ThemeStore 的 UI3-13 键一一对应，值域归一化在壳层完成（未知值回落
 * 默认）；textScale/reduceMotion/glassEnabled 是运行时呈现量（字号偏好 +
 * 系统动效/省电探测），随快照一起刷新，壳内改偏好即时生效（复核 P2 修订）。
 */
private data class Oe3ShellPrefs(
    val pureBlack: Boolean = false,
    val glassLevel: Oe3GlassLevel = Oe3GlassLevel.None,
    /** UI-06：off/auto/always 三档玻璃模式；CompositionLocal 侧据此决定是否绕过降档缓存。 */
    val glassMode: GlassMode = GlassMode.Auto,
    val navStyle: Oe3NavStyle = Oe3NavStyle.Floating,
    val monoBody: Boolean = false,
    val textScale: Float = 1f,
    val reduceMotion: Boolean = false,
    val glassEnabled: Boolean = true,
    /** UI-02：圆角偏好真正下发到 Oe3Theme（此前 Oe3App 丢掉了它）。 */
    val cornerRadiusDp: Int? = null,
    /** UI-02：界面表面不透明度，0.55..1.0，供玻璃与半透明表面消费。 */
    val surfaceAlpha: Float = 1f,
    /** UI-02：壳层背景三态的壁纸输入（SAF content://）。 */
    val backgroundUri: String? = null,
)
