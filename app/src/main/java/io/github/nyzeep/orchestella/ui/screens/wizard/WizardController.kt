package io.github.nyzeep.orchestella.ui.screens.wizard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.widget.Toast
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.nyzeep.orchestella.AvfHostFactsProbe
import io.github.nyzeep.orchestella.BadgeCopy
import io.github.nyzeep.orchestella.DistroInstanceCreator
import io.github.nyzeep.orchestella.DistroMirrorCatalog
import io.github.nyzeep.orchestella.DistroMirrorProvisioner
import io.github.nyzeep.orchestella.DistroResourceRequirements
import io.github.nyzeep.orchestella.ErrorCopy
import io.github.nyzeep.orchestella.FirstBootProvisionPipeline
import io.github.nyzeep.orchestella.GuestFeatureSelections
import io.github.nyzeep.orchestella.HostHardwareDetector
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.InstanceCreationWizardModel
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.MirrorSpeedTester
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.SettingsManager
import io.github.nyzeep.orchestella.WizardAvfDiagnostics
import io.github.nyzeep.orchestella.WizardDraft
import io.github.nyzeep.orchestella.WizardDraftFields
import io.github.nyzeep.orchestella.runtime.capability.AvfAuthorizationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig
import io.github.nyzeep.orchestella.runtime.network.download.DownloadProgressCallback
import io.github.nyzeep.orchestella.runtime.network.download.MirrorHealthProbe
import io.github.nyzeep.orchestella.runtime.network.download.ResilientDownloadEngine
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.util.Locale
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 四步创建向导的编排层（UI-12）：持有 [InstanceCreationWizardModel] 与
 * Android 侧事实（StatFs / 网络 / AVF / 下载进度），把模型 + 事实折算成
 * [WizardUiState] 只读投影供 Compose 渲染；所有用户输入以命令进入，经
 * 模型自身的 mutator 落账——模型层业务逻辑零改动。
 *
 * 与旧 `SetupWizardActivity` 的逐段对应：
 *  - onCreate/bind* → 属性初始化 + [init]（探测 + RAM 建议值 + 初始渲染）；
 *  - renderDraftFields/captureDraftFromViews 的双向 widget 同步 → 消失
 *    （Compose 单向流，模型恒为真值，不再有 widget 与模型的漂移）；
 *  - startDownloadAndInitialize 流水线 → [startCreation]（IO 协程）；
 *  - 5 处 android.app.AlertDialog → [AuthDialog] 相机 + 退出确认（视图层
 *    Compose AlertDialog，32dp）；
 *  - onSaveInstanceState 草稿 → [saveDraft] / [restore]（语义不变）。
 *
 * 旧代码的既有文案字面量原样保留（防文案漂移）；新增界面文案走 strings.xml。
 * 事实变量一律走 Compose 快照状态：下载回调在引擎线程写入，重组在主线程读取。
 */
class WizardController(
    private val context: Context,
    targetDistroId: String,
    private val callbacks: Callbacks,
    savedDraft: Map<String, String>? = null,
) : WizardScreenModel {

    /** 宿主接线点：由 MainActivity 提供（壳内导航回跳与既有系统对话框复用）。 */
    data class Callbacks(
        /** 创建成功：壳层 popAll + 切到实例页并刷新（旧 startActivity(MainActivity) 语义）。 */
        val onCreated: () -> Unit,
        /** 复用既有 ShizukuClientDialog（DialogFragment，能力授权客户端入口）。 */
        val onShowShizukuClient: () -> Unit,
    )

    /** AVF 授权引导的对话框相位（旧 5 处平台 AlertDialog 中 4 处的去向）。 */
    sealed interface AuthDialog {
        data object None : AuthDialog
        data object ShizukuSteps : AuthDialog
        data class AdbSteps(val command: String) : AuthDialog
        data object AuthorizedSuccess : AuthDialog
        data object VendorFailure : AuthDialog
    }

    /** 创建门禁的身份校验结论（旧 editGuestUser.error / editGuestHostname.error）。 */
    enum class IdentityError { Username, HostnameBlank, HostnameInvalid, RootConflict }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settingsManager = SettingsManager(context)
    private val hostProfile: HostHardwareProfile = HostHardwareDetector.detect(context)

    // ---------------- 事实（快照状态；下载回调线程可写） ----------------

    private var lastAvailableBytes by mutableStateOf(0L)
    private var lastCommittedBytes by mutableStateOf(0L)
    private var storageCheckPending by mutableStateOf(false)
    private var diskInputInvalid by mutableStateOf(false)
    private var networkFacts by mutableStateOf(WizardNetworkFacts())
    private var speedTestSummary by mutableStateOf<String?>(null)
    private var liveCreationPhase by mutableStateOf<CreationPhase?>(null)
    private var liveCreationStatus by mutableStateOf<CreationStatusFacts?>(null)
    private var avfFacts by mutableStateOf<WizardAvfFacts?>(null)
    private var draftNotice by mutableStateOf<String?>(null)

    /** 创建门禁的身份校验结论（视图按字段渲染行内错误）。 */
    private val _identityError = mutableStateOf<IdentityError?>(null)
    override val identityError: State<IdentityError?> get() = _identityError

    /** AVF 详情折叠态（旧 avfDetailCollapse；动画由视图层 AnimatedVisibility 承担）。 */
    var avfDetailExpanded: Boolean by mutableStateOf(false)
        private set

    /**
     * 自定义磁盘输入的原文（旧 editCustomDiskGb 的文本腿）。模型只收合法值；
     * 非法输入以 gate=InvalidInput 显式关门前门，文本保留在输入框。
     */
    override var diskInputText: String by mutableStateOf("")
        private set

    private var authWizardModel: AvfAuthorizationWizardModel? = null
    private var avfReadiness: AvfCapabilityEvaluator.AvfReadiness? = null
    private var storageCheckJob: Job? = null

    // ---------------- 模型与投影 ----------------

    private var model: InstanceCreationWizardModel = createInitialModel(targetDistroId)

    private val _state = mutableStateOf(WizardUiState.of(model, currentFacts()))
    override val state: State<WizardUiState> = _state

    private val _authDialog = mutableStateOf<AuthDialog>(AuthDialog.None)
    override val authDialog: State<AuthDialog> = _authDialog

    init {
        if (savedDraft != null) {
            restoreFromDraft(savedDraft)
        }
        diskInputText = model.diskSizeGb.toString()
        refreshAvfCard()
        refreshRamSuggestion()
        probeNetwork()
        revalidateStorage()
        render()
    }

    fun dispose() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /** 模型 + 事实 → 投影。所有命令路径的收尾都走这里。 */
    private fun render() {
        _state.value = WizardUiState.of(model, currentFacts())
    }

    private fun currentFacts(): WizardFacts = WizardFacts(
        availableBytes = lastAvailableBytes,
        existingCommittedBytes = lastCommittedBytes,
        diskInputInvalid = diskInputInvalid,
        storageCheckPending = storageCheckPending,
        network = networkFacts.copy(speedTestSummary = speedTestSummary),
        avf = avfFacts,
        creationPhase = liveCreationPhase,
        creationStatus = liveCreationStatus,
    )

    // ------------------------------------------------------------------
    // 命令：发行版与存储步
    // ------------------------------------------------------------------

    /** 旧 spinner 的 onItemSelected：发行版切换 = 重建模型 + 草稿骑乘。 */
    override fun selectDistro(distroId: String) {
        if (model.isCreating || model.distroId == distroId) return
        val manifest = DistroCatalogRegistry.getInstance().get(distroId)
        val version = manifest?.version() ?: "latest"
        val previous = model
        model = InstanceCreationWizardModel(
            distroId = distroId,
            distroVersion = version,
            hardwareProfile = hostProfile,
            initialStep = previous.currentStep,
        )
        // 凭据连续性：随机生成的密码不在草稿字段里（userConfig 由模型持有）。
        model.setUserConfig(previous.userConfig)
        WizardDraft.fromModel(previous).applyTo(model)
        refreshRamSuggestion()
        revalidateStorage()
        render()
    }

    override fun selectDiskPreset(gb: Int) {
        if (model.isCreating) return
        model.selectDiskPresetGb(gb)
        diskInputText = gb.toString()
        diskInputInvalid = false
        revalidateStorage()
    }

    /**
     * 自定义容量键入（旧 TextWatcher 语义）：非法输入模型不动、门显式关闭；
     * 合法输入进模型并调度 300ms 防抖预检（检查未落时门保持关闭）。
     */
    override fun onCustomDiskInput(raw: String) {
        if (model.isCreating) return
        diskInputText = raw
        val gb = WizardDraftFields.diskSizeGb.parse(raw)
        if (gb != null) {
            diskInputInvalid = false
            model.setCustomDiskSizeGb(gb)
            scheduleDebouncedStorageValidation()
        } else {
            diskInputInvalid = true
            storageCheckPending = false
        }
        render()
    }

    // ------------------------------------------------------------------
    // 命令：共享目录
    // ------------------------------------------------------------------

    /** 旧 chkMountSharedDir 监听：取消勾选清路径；勾选且无路径 → 拉 SAF 选择器。 */
    override fun onSharedDirToggled(checked: Boolean) {
        if (model.isCreating) return
        if (!checked) model.setHostSharedDirectoryPath(null)
        model.setMountHostSharedDirectory(checked)
        // 勾选且无既往路径时由视图层发起 SAF 选择器（launcher 在组合内）。
        render()
    }

    /** 旧 OpenDocumentTree 回调（含取消腿与 takePersistableUriPermission 尽力语义）。 */
    override fun onSharedDirPicked(uri: Uri?) {
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // 授权持久化尽力而为；授权丢失在挂载期暴露，可重新选择。
            }
            val displayPath = uri.lastPathSegment ?: uri.path ?: uri.toString()
            model.setMountHostSharedDirectory(true)
            model.setHostSharedDirectoryPath(displayPath)
        } else if (model.hostSharedDirectoryPath == null) {
            // 无既往路径的取消：勾选框与模型重新对齐。
            model.setMountHostSharedDirectory(false)
        }
        render()
    }

    // ------------------------------------------------------------------
    // 命令：网络步
    // ------------------------------------------------------------------

    override fun selectMirror(ordinal: Int) {
        if (model.isCreating) return
        val sources = DistroMirrorProvisioner.MirrorSource.values()
        if (ordinal !in sources.indices) return
        model.selectMirror(sources[ordinal])
        render()
    }

    /** 旧 btnWizardSpeedTest：并发测速 → 最优节点回填 MULTI_TIER。 */
    override fun runMirrorSpeedTest() {
        if (model.isCreating) return
        speedTestSummary = "正在对主流云镜像源进行并发测速..."
        networkFacts = networkFacts.copy(testResult = NetworkTestResult.Testing)
        render()
        scope.launch {
            val fastest: Pair<String, Long?>? = withContext(Dispatchers.IO) {
                val candidates = MirrorSpeedTester.defaultSystemCandidates()
                if (candidates.isNotEmpty()) {
                    val results = MirrorSpeedTester.probeAll(candidates, 3000)
                    val winner = MirrorSpeedTester.selectFastest(results, candidates.first())
                    if (winner != null) {
                        val winnerResult = results.firstOrNull { it.candidate() == winner && it.isSuccess }
                        winner.name() to winnerResult?.latencyMs()
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            if (fastest != null) {
                val latency = fastest.second?.let { " (${it}ms)" } ?: ""
                speedTestSummary = "测速完成！最优推荐节点：${fastest.first}$latency\n已优先锁定多层级容灾回退架构。"
                model.selectMirror(DistroMirrorProvisioner.MirrorSource.MULTI_TIER)
            } else {
                speedTestSummary = "测速超时，已默认选用多层级容灾回退架构。"
            }
            networkFacts = networkFacts.copy(testResult = NetworkTestResult.Idle)
            render()
        }
    }

    /** 旧 btnTestNetwork：系统声明连通性自检（后台）。 */
    override fun testNetworkConnectivity() {
        networkFacts = networkFacts.copy(testResult = NetworkTestResult.Testing)
        render()
        scope.launch {
            val ok = withContext(Dispatchers.IO) { systemReportsValidatedInternet() }
            networkFacts = networkFacts.copy(testResult = if (ok) NetworkTestResult.Ok else NetworkTestResult.Failed)
            render()
        }
    }

    // ------------------------------------------------------------------
    // 命令：硬件步
    // ------------------------------------------------------------------

    override fun onCpuCoresChange(cores: Int) {
        if (model.isCreating) return
        model.updateCpuCores(cores)
        render()
    }

    override fun onRamMbChange(mb: Int) {
        if (model.isCreating) return
        model.updateRamMegabytes(mb)
        render()
    }

    override fun toggleAvfDetails() {
        avfDetailExpanded = !avfDetailExpanded
        render()
    }

    // ------------------------------------------------------------------
    // 命令：启动任务步
    // ------------------------------------------------------------------

    override fun onIdentityChange(username: String, hostname: String) {
        if (model.isCreating) return
        model.updateGuestIdentityDraft(username, hostname, model.guestSudoDraft)
        render()
    }

    override fun onSudoChange(enabled: Boolean) {
        if (model.isCreating) return
        model.updateGuestIdentityDraft(model.guestUsernameDraft, model.guestHostnameDraft, enabled)
        render()
    }

    override fun onOptionalProfileChange(desktop: Boolean, podman: Boolean, editor: Boolean) {
        if (model.isCreating) return
        model.setOptionalProfiles(desktop, podman, editor)
        render()
    }

    override fun onAutoUpdateChange(enabled: Boolean) {
        if (model.isCreating) return
        model.setAutoUpdateOnFirstBoot(enabled)
        render()
    }

    // ------------------------------------------------------------------
    // 命令：步进与创建
    // ------------------------------------------------------------------

    /** 上一步（底部操作条；返回键的逐级回退同路）。 */
    override fun goBack() {
        if (model.canGoBack()) {
            model.previousStep()
            render()
        }
    }

    /**
     * 下一步。存储步先落账发行版切换（旧 btnStep1Next 的双跳语义）；
     * 硬件步先验安全门（旧 btnStep3Next）。
     */
    override fun goNext() {
        if (!model.canGoNext()) return
        when (model.currentStep) {
            InstanceCreationWizardModel.CreationStep.STEP_STORAGE -> {
                WizardDistroCatalog.official.firstOrNull { it.id == model.distroId }
                    ?.let { selectDistro(it.id) }
            }
            InstanceCreationWizardModel.CreationStep.STEP_HARDWARE -> {
                val hardwareError = hardwareValidationMessage()
                if (hardwareError != null) {
                    toast(hardwareError)
                    return
                }
            }
            else -> Unit
        }
        if (model.canGoNext()) {
            model.nextStep()
            onStepChanged()
        }
    }

    /** 跳转到硬件步（创建门禁发现硬件错误时的旧 goToStep 语义）。 */
    fun jumpToHardwareStep() {
        model.goToStep(InstanceCreationWizardModel.CreationStep.STEP_HARDWARE)
        onStepChanged()
    }

    private fun onStepChanged() {
        if (model.currentStep == InstanceCreationWizardModel.CreationStep.STEP_STORAGE) {
            revalidateStorage()
        }
        render()
    }

    /**
     * 创建入口（旧 startDownloadAndInitialize 1:1）：身份校验 → 可选功能与
     * root 冲突 → 硬件门 → 最终存储门（含已承诺占用）→ tryBeginCreation →
     * 后台下载与装配。
     */
    override fun startCreation() {
        if (model.isCreating) return
        val username = model.guestUsernameDraft.trim()
        val hostname = model.guestHostnameDraft.trim()
        if (!InstanceUserConfig.isValidUsername(username)) {
            _identityError.value = IdentityError.Username
            render()
            return
        }
        if (hostname.isBlank()) {
            _identityError.value = IdentityError.HostnameBlank
            render()
            return
        }
        val userConfig = try {
            InstanceUserConfig(username, model.userConfig.password(), model.guestSudoDraft, hostname)
        } catch (invalid: IllegalArgumentException) {
            _identityError.value = IdentityError.HostnameInvalid
            render()
            return
        }
        if ((model.desktopSelected || model.podmanSelected || model.editorSelected) &&
            userConfig.username() == "root"
        ) {
            _identityError.value = IdentityError.RootConflict
            render()
            return
        }
        model.setUserConfig(userConfig)
        _identityError.value = null

        val hardwareError = hardwareValidationMessage()
        if (hardwareError != null) {
            jumpToHardwareStep()
            toast(hardwareError)
            return
        }
        val manifest = manifest()
        if (manifest == null) {
            toast(WizardCopy.MANIFEST_MISSING)
            return
        }
        val availableBytes = StatFs(context.filesDir.path).availableBytes
        val existingCommittedBytes = existingCommittedCapacityBytes()
        val requiredBytes = model.requestedDiskBytes + manifest.sizeBytes() +
            128L * 1024L * 1024L + existingCommittedBytes
        if (availableBytes < requiredBytes) {
            if (existingCommittedBytes > 0L) {
                val detail = ErrorCopy.detailMessage(
                    context,
                    InstanceCreationWizardModel.storagePreflightRejection(
                        WizardCopy.committedDetailGb(existingCommittedBytes),
                    ),
                )
                toast("$detail ${context.getString(R.string.create_storage_low)}")
            } else {
                toast(R.string.create_storage_low)
            }
            return
        }
        if (!model.tryBeginCreation()) return
        liveCreationPhase = CreationPhase.Fetching
        liveCreationStatus = CreationStatusFacts(
            statusLine = context.getString(
                R.string.wizard_download_status_fetching,
                manifest.id().replaceFirstChar { it.uppercase() },
            ),
            progressPercent = null,
            speedEtaLine = context.getString(R.string.wizard_download_connecting),
            mirrorLine = context.getString(R.string.wizard_download_mirror_pending),
        )
        render()
        launchCreationPipeline(manifest)
    }

    private fun launchCreationPipeline(manifest: DistroManifest) {
        val selectedProfiles = buildSet {
            if (model.desktopSelected) add("desktop-labwc")
            if (model.podmanSelected) add("podman")
            if (model.editorSelected) add("code-server")
        }
        scope.launch {
            try {
                val targetPath = context.filesDir.toPath().resolve("distro-inputs")
                    .resolve(manifest.rootfsFileName())
                val mirrors = DistroMirrorCatalog.mirrorsFor(manifest)
                val engine = ResilientDownloadEngine()
                // 先排序后拉第一字节：目录是 CN-first，VPN 用户不该陪死节点耗时间。
                val ranked = withContext(Dispatchers.IO) { MirrorHealthProbe.rankMirrors(mirrors) }
                liveCreationStatus = liveCreationStatus?.copy(
                    speedEtaLine = context.getString(R.string.wizard_download_probing, mirrors.size),
                    mirrorLine = "镜像节点：${ranked.firstOrNull() ?: "--"}",
                )
                render()
                withContext(Dispatchers.IO) {
                    engine.download(ranked, targetPath, manifest.sha256(), progressCallback())
                }
                liveCreationPhase = CreationPhase.Provisioning
                liveCreationStatus = liveCreationStatus?.copy(
                    statusLine = context.getString(R.string.wizard_provisioning_info),
                    progressPercent = null,
                )
                render()
                provisionInstance(manifest, targetPath, selectedProfiles)
                if (!settingsManager.isOnboardingCompleted()) {
                    settingsManager.setOnboardingCompleted(true)
                }
                liveCreationPhase = null
                liveCreationStatus = null
                toast(context.getString(R.string.wizard_created_toast))
                callbacks.onCreated()
            } catch (failure: Exception) {
                android.util.Log.e("Orchestella", "Wizard initialization failed", failure)
                model.setCreating(false)
                liveCreationPhase = CreationPhase.Failed
                val detail = ErrorCopy.detailMessage(context, failure)
                liveCreationStatus = CreationStatusFacts(
                    statusLine = context.getString(R.string.wizard_init_failed_human, detail),
                    progressPercent = null,
                    speedEtaLine = context.getString(ErrorCopy.resolve(failure).hintRes),
                    mirrorLine = null,
                )
                toast(context.getString(R.string.wizard_init_failed_human, detail))
                render()
            }
        }
    }

    private fun progressCallback(): DownloadProgressCallback = object : DownloadProgressCallback {
        override fun onProgress(
            downloadedBytes: Long,
            totalBytes: Long,
            speedMBps: Double,
            etaSeconds: Long,
            currentMirror: String,
        ) {
            val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else null
            val downloadedStr = String.format(Locale.US, "%.1f MB", downloadedBytes.toDouble() / (1024.0 * 1024.0))
            val totalStr = if (totalBytes > 0) {
                String.format(Locale.US, "%.1f MB", totalBytes.toDouble() / (1024.0 * 1024.0))
            } else {
                "--"
            }
            val etaStr = if (etaSeconds >= 0) "${etaSeconds}s" else "--"
            val hostName = mirrorHost(currentMirror)
            liveCreationStatus = liveCreationStatus?.copy(
                progressPercent = percent,
                speedEtaLine = context.getString(
                    R.string.wizard_download_speed_info, speedMBps, downloadedStr, totalStr, etaStr,
                ),
                mirrorLine = context.getString(R.string.wizard_download_mirror_info, hostName),
            )
            render()
        }

        override fun onRetry(currentMirror: String, attempt: Int, backoffMillis: Long, error: Throwable) {
            liveCreationStatus = liveCreationStatus?.copy(
                speedEtaLine = context.getString(
                    R.string.wizard_download_retry_info, mirrorHost(currentMirror), attempt,
                ),
            )
            render()
        }

        override fun onMirrorFailover(failedMirror: String, nextMirror: String?, error: Throwable) {
            val nextHost = nextMirror?.let { mirrorHost(it) } ?: "无"
            liveCreationStatus = liveCreationStatus?.copy(
                speedEtaLine = context.getString(R.string.wizard_download_failover_info, nextHost),
            )
            render()
        }
    }

    private fun mirrorHost(mirror: String): String = try {
        URI(mirror).host ?: mirror
    } catch (_: Exception) {
        mirror
    }

    /** 下载完成后的装配腿：实例元数据 + 功能档案 + 镜像/首启脚本注入。 */
    private fun provisionInstance(
        manifest: DistroManifest,
        targetPath: java.nio.file.Path,
        selectedProfiles: Set<String>,
    ): InstanceLifecycle.Instance {
        val instancesDir = context.filesDir.toPath().resolve("instances")
        val instanceStore = InstanceLifecycle.FileStore(instancesDir)
        val lifecycleController = InstanceLifecycle.Controller(instanceStore, Clock.systemUTC())
        val creator = DistroInstanceCreator(
            manifest,
            lifecycleController,
            context.filesDir.toPath(),
            model.requestedDiskBytes,
        )
        val createdInstance = Files.newInputStream(targetPath).use { input ->
            creator.create(input, manifest.sizeBytes(), model.hardwareConfig, model.userConfig)
        }
        GuestFeatureSelections.save(context, createdInstance.id(), selectedProfiles)
        val payload = FirstBootProvisionPipeline.create(
            model.distroId,
            manifest.version(),
            model.selectedMirror,
            model.autoUpdateOnFirstBoot,
        )
        if (payload.hasMirrorConfig()) {
            Files.write(
                instancesDir.resolve("${createdInstance.id()}.mirror"),
                payload.mirrorConfigFileContent(),
            )
        }
        if (payload.hasFirstBootScript()) {
            Files.write(
                instancesDir.resolve("${createdInstance.id()}.firstboot"),
                payload.firstBootScript(),
            )
        }
        return createdInstance
    }

    // ------------------------------------------------------------------
    // AVF 授权引导（旧 4 处对话框的状态机）
    // ------------------------------------------------------------------

    /** 硬件步「获取授权指引」入口（旧 showAvfAuthorizationWizard）。 */
    override fun openAvfAuthorization() {
        refreshAvfCard()
        val readiness = avfReadiness ?: return
        val wizard = AvfAuthorizationWizardModel.fromAssessment(readiness)
        if (!wizard.canStart()) return
        authWizardModel = wizard.start()
        _authDialog.value = AuthDialog.ShizukuSteps
    }

    override fun authUseShizukuClient() {
        callbacks.onShowShizukuClient()
    }

    override fun authChooseAdbFallback() {
        authWizardModel = authWizardModel?.chooseAdbFallback()
        _authDialog.value = AuthDialog.AdbSteps(adbCommand())
    }

    override fun authBackToShizuku() {
        authWizardModel = authWizardModel?.backToShizuku()
        _authDialog.value = AuthDialog.ShizukuSteps
    }

    override fun authCopyCommand() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(context.getString(R.string.wizard_copy_command), adbCommand()),
        )
        toast(R.string.wizard_command_copied)
    }

    override fun authDone() {
        recheckAuthorization()
    }

    override fun dismissAuthDialog() {
        _authDialog.value = AuthDialog.None
    }

    /** 旧 recheckAuthorization：同一次探测刷新卡与授权入口，成功/失败各自弹确认。 */
    private fun recheckAuthorization() {
        val facts = AvfHostFactsProbe.probe(context)
        val granted = facts.hasBothVirtualMachinePermissions()
        authWizardModel = authWizardModel?.confirmAuthorized(
            facts.manageVirtualMachinePermission(),
            facts.useCustomVirtualMachinePermission(),
        )
        refreshAvfCard(facts)
        render()
        _authDialog.value = if (granted) AuthDialog.AuthorizedSuccess else AuthDialog.VendorFailure
    }

    private fun adbCommand(): String = AvfAuthorizationWizardModel.adbGrantCommand(context.packageName)

    // ------------------------------------------------------------------
    // 事实采集
    // ------------------------------------------------------------------

    /**
     * T4③ 预检补强：已存在实例承诺的虚拟磁盘容量（稀疏盘可长到标称值）。
     * 读取失败不阻断创建流程——退回不计入，最终创建门禁兜底。
     */
    private fun existingCommittedCapacityBytes(): Long = try {
        val instancesDir = context.filesDir.toPath().resolve("instances")
        val store = InstanceLifecycle.FileStore(instancesDir)
        InstanceCreationWizardModel.committedCapacityBytes(
            store.list().map { instance -> instance.hardwareConfig()?.diskCapacityBytes() ?: 0L },
        )
    } catch (_: Exception) {
        0L
    }

    /** 同步预检（预设点击 / 步骤重入）。 */
    override fun revalidateStorage() {
        lastAvailableBytes = try {
            StatFs(context.filesDir.path).availableBytes
        } catch (_: Exception) {
            0L
        }
        lastCommittedBytes = existingCommittedCapacityBytes()
        render()
    }

    /**
     * 键入路径的 300ms 防抖预检（旧 scheduleDebouncedStorageValidation）：
     * 每串键入只做一次 StatFs + 实例目录扫描；检查未落时门保持关闭——
     * 未经验证的请求还不是已证明的请求。
     */
    private fun scheduleDebouncedStorageValidation() {
        storageCheckJob?.cancel()
        storageCheckPending = true
        render()
        storageCheckJob = scope.launch {
            delay(STORAGE_CHECK_DEBOUNCE_MS)
            val available = withContext(Dispatchers.IO) {
                try {
                    StatFs(context.filesDir.path).availableBytes
                } catch (_: Exception) {
                    0L
                }
            }
            val committed = withContext(Dispatchers.IO) { existingCommittedCapacityBytes() }
            lastAvailableBytes = available
            lastCommittedBytes = committed
            storageCheckPending = false
            render()
        }
    }

    /** 旧 bindStep2Network：连接方式 / IP / 网关 / DNS 探测（启动时一次）。 */
    private fun probeNetwork() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
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
                    if (detectedDns.isNotBlank()) dnsList = detectedDns
                    val detectedGw = linkProps.routes.asSequence()
                        .filter { it.isDefaultRoute }
                        .mapNotNull { it.gateway?.takeUnless { address -> address.isAnyLocalAddress }?.hostAddress }
                        .firstOrNull()
                    if (!detectedGw.isNullOrBlank()) gateway = detectedGw
                }
            } else {
                @Suppress("DEPRECATION")
                val info = cm?.activeNetworkInfo
                isWifi = info?.type == ConnectivityManager.TYPE_WIFI
                isCellular = info?.type == ConnectivityManager.TYPE_MOBILE
                isConnected = info?.isConnected == true
            }
        } catch (_: Exception) {
            // 网络状态权限受限时的防御回退；显式连通性测试给出用户可见结论。
        }
        val mode = when {
            isVpn -> "VPN (当前默认网络)"
            isWifi -> "Wi-Fi (无线局域网)"
            isCellular -> "移动蜂窝网络"
            isEthernet -> "以太网"
            isConnected -> "其他网络 (系统声明可访问互联网)"
            else -> "离线或无法读取网络状态"
        }
        networkFacts = WizardNetworkFacts(
            modeLine = context.getString(R.string.wizard_net_mode, mode),
            ipLine = context.getString(R.string.wizard_net_ip, localIpAddress() ?: "未提供"),
            gatewayLine = context.getString(R.string.wizard_net_gateway, gateway),
            dnsLine = context.getString(R.string.wizard_net_dns, dnsList),
        )
    }

    /** 旧 getLocalIpAddress：防御式枚举，失败即 null。 */
    private fun localIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { iface -> iface.inetAddresses.asSequence() }
            .firstOrNull { addr -> !addr.isLoopbackAddress && addr is Inet4Address }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** 旧 testNetworkConnectivity 的系统判定腿（后台执行）。 */
    private fun systemReportsValidatedInternet(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm?.activeNetwork else null
        val caps = net?.let { cm?.getNetworkCapabilities(it) }
        if (caps != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm?.activeNetworkInfo?.isConnected == true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 旧 bindWizardAvfCard 1:1：探针 → 评估 → 摘要/徽标/详情装配。
     * W2(a)：平台摘要已内联 mode-facts kv 尾，仅在存在平台观察时传入。
     */
    private fun refreshAvfCard(
        facts: AvfCapabilityEvaluator.AvfHostFacts = AvfHostFactsProbe.probe(context),
    ) {
        val readiness = AvfCapabilityEvaluator.evaluate(facts)
        avfReadiness = readiness
        authWizardModel = AvfAuthorizationWizardModel.fromAssessment(readiness)
        val hasPlatformObservation = facts.platformFacts() != null
        val factsSummary = WizardAvfDiagnostics.factsSummary(
            avfFeatureDeclared = facts.virtualizationFrameworkFeature(),
            virtualizationProperty = facts.virtualizationProperty(),
            platformSummary = if (hasPlatformObservation) {
                AvfHostFactsProbe.platformSummary(facts.platformFacts())
            } else {
                null
            },
            modeSummary = AvfHostFactsProbe.modeSummary(facts.modeFacts()),
            managePermissionLabel = AvfHostFactsProbe.permissionLabel(facts.manageVirtualMachinePermissionState()),
            customPermissionLabel = AvfHostFactsProbe.permissionLabel(facts.useCustomVirtualMachinePermissionState()),
            sampledAtFormatter = ::formatSampledAtLocal,
        )
        val launchNotice = context.getString(R.string.avf_preflight_boundary)
        val reasons = readiness.reasons().joinToString("；")
        val (badge, details, fallbackVisible) = when (readiness.status()) {
            AvfCapabilityEvaluator.AvfStatus.READY -> Triple(
                "硬件虚拟化权限预检满足",
                "$factsSummary\n声明与权限条件满足。\n$launchNotice",
                false,
            )
            AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> Triple(
                "AVF 尚未授权",
                "$factsSummary\n$reasons\n$launchNotice",
                true,
            )
            AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED -> Triple(
                BadgeCopy.avfUnsupportedEntryLabel(),
                "$factsSummary\n此功能依赖设备的硬件虚拟化能力，你的设备上不可用（不影响其他功能）。当前使用 QEMU。\n$launchNotice",
                true,
            )
            AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN -> Triple(
                "AVF 模式未验证",
                "$factsSummary\n$reasons\n授权不会改变 VM 模式；当前使用 QEMU。\n$launchNotice",
                true,
            )
            AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED -> Triple(
                "AVF 平台接口受限",
                "$factsSummary\n$reasons\n这是系统隐藏 API 限制，不是权限问题；当前使用 QEMU。\n$launchNotice",
                true,
            )
            AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE -> Triple(
                "AVF 平台接口缺失",
                "$factsSummary\n$reasons\n当前系统未提供该平台接口；当前使用 QEMU。\n$launchNotice",
                true,
            )
            AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED -> Triple(
                "AVF 预检未满足",
                "$factsSummary\n预检原因：$reasons\n启动路径：QEMU\n$launchNotice",
                true,
            )
            else -> Triple(
                "AVF 预检未满足",
                "$factsSummary\n$launchNotice",
                true,
            )
        }
        avfFacts = WizardAvfFacts(
            status = readiness.status(),
            badge = badge,
            summary = WizardAvfDiagnostics.summaryLine(readiness.status()),
            details = details,
            expanded = avfDetailExpanded,
            fallbackVisible = fallbackVisible,
        )
    }

    /** W2(b)：采样=毫秒 时间戳改本地时间显示（仅显示层，探针值不改写）。 */
    private fun formatSampledAtLocal(millis: Long): String = try {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis)) + "（本地时间）"
    } catch (_: Exception) {
        "$millis（时间戳）"
    }

    /** 旧 bindRamHardware 的模型写入腿：发行版切换/初始绑定后的建议值落账。 */
    private fun refreshRamSuggestion() {
        val manifest = manifest() ?: return
        val minimumRam = try {
            DistroResourceRequirements.minimumBootRamMb(manifest.sizeBytes())
        } catch (_: IllegalArgumentException) {
            return
        }
        if (minimumRam > VmHardwareConfig.MAX_RAM_MEGABYTES) return
        val safeMaximumRam = ((hostProfile.totalRamMb().toLong()
            - HostHardwareProfile.HOST_RESERVED_RAM_MB).coerceIn(
                0L, VmHardwareConfig.MAX_RAM_MEGABYTES.toLong(),
            ) / 256L * 256L).toInt()
        val suggestedRam = DistroResourceRequirements.suggestedWizardRamMb(
            manifest.sizeBytes(), model.hardwareConfig.ramMegabytes(), safeMaximumRam,
        )
        model.updateRamMegabytes(suggestedRam)
    }

    private fun hardwareValidationMessage(): String? {
        val manifest = manifest() ?: return WizardCopy.MANIFEST_MISSING
        try {
            DistroResourceRequirements.requireBootRam(
                manifest.sizeBytes(), model.hardwareConfig.ramMegabytes(),
            )
        } catch (rejected: IllegalArgumentException) {
            return rejected.message
        }
        if (model.safetyZone() == HostHardwareProfile.SafetyZone.RED) {
            val minimumRam = DistroResourceRequirements.minimumBootRamMb(manifest.sizeBytes())
            return WizardCopy.redZoneBelowMinimum(minimumRam)
        }
        return null
    }

    private fun manifest(): DistroManifest? =
        DistroCatalogRegistry.getInstance().get(model.distroId)

    private fun createInitialModel(distroId: String): InstanceCreationWizardModel {
        val targetManifest = DistroCatalogRegistry.getInstance().get(distroId.lowercase())
            ?: DistroCatalogRegistry.getInstance().get("ubuntu")
        return InstanceCreationWizardModel(
            distroId = targetManifest?.id() ?: "ubuntu",
            distroVersion = targetManifest?.version() ?: "24.04",
            hardwareProfile = hostProfile,
        )
    }

    private fun toast(resId: Int) = toast(context.getString(resId))

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------
    // 草稿（进程死亡恢复，旧 onSaveInstanceState/restoreDraft 语义）
    // ------------------------------------------------------------------

    fun saveDraft(): Map<String, String> = model.saveDraft()

    val distroId: String get() = model.distroId

    /**
     * 旧 restoreDraft：只恢复安全选择，清单/预算变化即弃草稿并明说；
     * 恢复失败且创建中 → 保留创建中状态（不重复下载，诚实展示）。
     */
    private fun restoreFromDraft(draft: Map<String, String>) {
        try {
            val versions = WizardDistroCatalog.official.mapNotNull { item ->
                DistroCatalogRegistry.getInstance().get(item.id)?.let { item.id to it.version() }
            }.toMap()
            model = InstanceCreationWizardModel.restoreDraft(draft, hostProfile, versions) { id ->
                DistroResourceRequirements.minimumBootRamMb(
                    requireNotNull(DistroCatalogRegistry.getInstance().get(id)).sizeBytes(),
                )
            }
        } catch (_: Exception) {
            val wasCreating = try {
                draft["creating"] == "true"
            } catch (_: Exception) {
                true
            }
            if (wasCreating) {
                model.goToStep(InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS)
                model.setCreating(true)
            }
            // 不打印 Bundle 或原始输入。
            draftNotice = "草稿无法恢复或设备条件已变化，请重新确认发行版和硬件配置。"
        }
    }

    /** 身份输入变更时清除行内错误（旧 error = null 语义的输入侧）。 */
    override fun clearIdentityError() {
        _identityError.value = null
    }

    /** 恢复提示的消费点：首次渲染后由视图层取走（Toast 一次）。 */
    override fun consumeDraftNotice(): String? {
        val notice = draftNotice
        draftNotice = null
        return notice
    }

    companion object {
        /** grand-optimization-20261109 §8 risk 4：键入防抖窗宽（原样迁移）。 */
        const val STORAGE_CHECK_DEBOUNCE_MS = 300L
    }
}
