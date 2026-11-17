package io.github.nyzeep.orchestella.ui.screens.wizard

import android.net.Uri
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.github.nyzeep.orchestella.DistroMirrorProvisioner
import io.github.nyzeep.orchestella.InstanceCreationWizardModel

/**
 * 向导金样/预览的**设计态**夹具（UI-12）：手写 [WizardUiState]，不跑探测链。
 * 数值取真机走查的典型样本（可用 112.3 GB / 8GB 盘 / 2 vCPU / 2048 MB）。
 */
object WizardPreviewStates {

    private val mirrorOptions = DistroMirrorProvisioner.MirrorSource.values().map { it.displayName() }

    private fun base(step: InstanceCreationWizardModel.CreationStep): WizardUiState = WizardUiState(
        step = step,
        stepIndex = when (step) {
            InstanceCreationWizardModel.CreationStep.STEP_STORAGE -> 1
            InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR -> 2
            InstanceCreationWizardModel.CreationStep.STEP_HARDWARE -> 3
            InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS -> 4
        },
        totalSteps = 4,
        storage = StorageUi(
            distroOptions = WizardDistroCatalog.official,
            selectedDistroId = "ubuntu",
            selectedDistro = WizardDistroCatalog.official.first(),
            availableGb = 112.3,
            diskSizeGb = 8,
            presets = InstanceCreationWizardModel.DISK_PRESET_GBS,
            selectedPresetGb = 8,
            gate = StorageGate.Ready,
            mountSharedDir = false,
            sharedDirPath = null,
        ),
        network = NetworkUi(
            modeLine = "网络连接：Wi-Fi (无线局域网)",
            ipLine = "宿主 IP：192.168.1.24",
            gatewayLine = "默认网关：192.168.1.1",
            dnsLine = "DNS 服务器：192.168.1.1, 223.5.5.5",
            testResult = NetworkTestResult.Idle,
            speedTestSummary = null,
            mirrorOptions = mirrorOptions,
            selectedMirrorOrdinal = 0,
            mirrorPreview = "配置目标：/etc/apt/sources.list\n解析节点：\n" +
                "https://mirrors.tuna.tsinghua.edu.cn\nhttps://mirrors.ustc.edu.cn",
        ),
        hardware = HardwareUi(
            cpuCores = 2,
            cpuMax = 8,
            ramMb = 2048,
            ramMinMb = 768,
            ramMaxMb = 6144,
            ramStepMb = 256,
            ramEnabled = true,
            minimumBootRamMb = 768,
            cpuAvfNotice = false,
            badge = HardwareBadge.Green,
            needsAuthEntry = false,
        ),
        startup = StartupUi(
            username = "dev",
            hostname = "orchestella",
            sudo = true,
            desktopEnabled = false,
            desktopSelected = false,
            podmanEnabled = true,
            podmanSelected = false,
            editorEnabled = true,
            editorSelected = true,
            autoUpdate = false,
            updateCommand = "DEBIAN_FRONTEND=noninteractive apt-get update && apt-get upgrade -y",
        ),
        creation = CreationUi(CreationPhase.Idle, null, null, null, null),
        avf = AvfCardUi(
            severity = AvfSeverity.Yellow,
            badge = "AVF 尚未授权",
            summary = "AVF 尚未授权——向导继续可用 QEMU 路径。",
            details = "AVF feature：已声明\n虚拟化属性：已声明\n" +
                "MANAGE_VIRTUAL_MACHINE：未授权\nUSE_CUSTOM_VIRTUAL_MACHINE：未授权",
            expanded = false,
            fallbackVisible = true,
            needsAuthEntry = false,
        ),
        isCreating = false,
        canGoBack = step != InstanceCreationWizardModel.CreationStep.STEP_STORAGE,
        shouldConfirmExit = false,
        storageCheckPending = false,
    )

    val storage: WizardUiState get() = base(InstanceCreationWizardModel.CreationStep.STEP_STORAGE)

    val network: WizardUiState get() = base(InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR)

    val hardware: WizardUiState get() = base(InstanceCreationWizardModel.CreationStep.STEP_HARDWARE)

    val startup: WizardUiState get() = base(InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS)

    /** 创建进行中的诚实相位：Fetching + 真实进度。 */
    val creating: WizardUiState get() = base(InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS).copy(
        isCreating = true,
        canGoBack = false,
        shouldConfirmExit = true,
        startup = base(InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS).startup.copy(
            desktopEnabled = false,
            podmanEnabled = false,
            editorEnabled = false,
        ),
        creation = CreationUi(
            phase = CreationPhase.Fetching,
            statusLine = "正在拉取 Ubuntu rootfs 镜像…",
            progressPercent = 34,
            speedEtaLine = "5.1 MB/s · 已下载 15.7 MB / 30.0 MB · 预计剩余 3s",
            mirrorLine = "镜像节点：mirrors.tuna.tsinghua.edu.cn",
        ),
    )

    /** 进程恢复后的 KeptRunning：状态保留、无活进度、绝不画假进度条。 */
    val keptRunning: WizardUiState get() = creating.copy(
        creation = CreationUi(CreationPhase.KeptRunning, null, null, null, null),
    )

    /** 宿主空间不足（含已承诺占用的 E-STORAGE-FULL 族文案）。 */
    val storageInsufficient: WizardUiState get() = storage.copy(
        storage = storage.storage.copy(
            gate = StorageGate.InsufficientSpace(
                availableGb = 4.2,
                requiredGb = 10.0,
                existingCommittedBytes = 64L * 1024 * 1024 * 1024,
                error = io.github.nyzeep.orchestella.ErrorCopy.Entry(
                    io.github.nyzeep.orchestella.R.string.error_storage_full,
                    io.github.nyzeep.orchestella.ErrorCopy.CODE_STORAGE_FULL,
                    io.github.nyzeep.orchestella.R.string.error_hint_check_storage,
                ),
            ),
        ),
    )
}

/**
 * 截图/预览桩：全部命令 no-op，状态固定为注入的 [WizardUiState]。
 * 让 [WizardScreen] 在不触探测链（StatFs / 网络 / AVF）的情况下渲染设计态。
 */
class WizardScreenModelStub(
    state: WizardUiState,
    diskInputText: String = "8",
) : WizardScreenModel {

    private val _state = mutableStateOf(state)
    override val state: State<WizardUiState> = _state

    override val identityError: State<WizardController.IdentityError?> =
        mutableStateOf(null)

    override val authDialog: State<WizardController.AuthDialog> =
        mutableStateOf(WizardController.AuthDialog.None)

    override val diskInputText: String = diskInputText

    override fun selectDistro(distroId: String) = Unit
    override fun selectDiskPreset(gb: Int) = Unit
    override fun onCustomDiskInput(raw: String) = Unit
    override fun onSharedDirToggled(checked: Boolean) = Unit
    override fun onSharedDirPicked(uri: Uri?) = Unit
    override fun revalidateStorage() = Unit
    override fun selectMirror(ordinal: Int) = Unit
    override fun runMirrorSpeedTest() = Unit
    override fun testNetworkConnectivity() = Unit
    override fun onCpuCoresChange(cores: Int) = Unit
    override fun onRamMbChange(mb: Int) = Unit
    override fun toggleAvfDetails() = Unit
    override fun openAvfAuthorization() = Unit
    override fun onIdentityChange(username: String, hostname: String) = Unit
    override fun onSudoChange(enabled: Boolean) = Unit
    override fun onOptionalProfileChange(desktop: Boolean, podman: Boolean, editor: Boolean) = Unit
    override fun onAutoUpdateChange(enabled: Boolean) = Unit
    override fun clearIdentityError() = Unit
    override fun startCreation() = Unit
    override fun goBack() = Unit
    override fun goNext() = Unit
    override fun authUseShizukuClient() = Unit
    override fun authChooseAdbFallback() = Unit
    override fun authBackToShizuku() = Unit
    override fun authCopyCommand() = Unit
    override fun authDone() = Unit
    override fun dismissAuthDialog() = Unit
    override fun consumeDraftNotice(): String? = null
}
