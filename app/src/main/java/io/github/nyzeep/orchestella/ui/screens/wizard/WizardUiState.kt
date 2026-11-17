package io.github.nyzeep.orchestella.ui.screens.wizard

import androidx.annotation.DrawableRes
import io.github.nyzeep.orchestella.DistroMirrorProvisioner
import io.github.nyzeep.orchestella.DistroResourceRequirements
import io.github.nyzeep.orchestella.ErrorCopy
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.InstanceCreationWizardModel
import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.distro.DistroManifest
import io.github.nyzeep.orchestella.runtime.engineavf.AvfLaunchAdmission

/**
 * UI-12 步骤 0：四步创建向导的**只读投影**。
 *
 * 模型层（[InstanceCreationWizardModel] / `WizardDraft`）保持业务逻辑不动；
 * 本文件把「UI 渲染需要的一切判定」收敛为一个不可变快照，Compose 视图只
 * 读它，绝不直接持有模型引用。任何 UI 判定绕过投影去摸模型，都等于重新
 * 打开旧 Activity 的十一条手工同步通道——契约测试（`WizardUiStateContractTest`）
 * 锁定这一点。
 *
 * 纯 JVM：不 import 任何 Android UI 类；`@DrawableRes` 只是常量标注。
 * Android 侧事实（StatFs、网络探测、AVF 探针、下载进度）由 `WizardController`
 * 采集后经 [WizardFacts] 注入。
 */
data class WizardUiState(
    val step: InstanceCreationWizardModel.CreationStep,
    val stepIndex: Int,
    val totalSteps: Int,
    val storage: StorageUi,
    val network: NetworkUi,
    val hardware: HardwareUi,
    val startup: StartupUi,
    val creation: CreationUi,
    val avf: AvfCardUi,
    val isCreating: Boolean,
    val canGoBack: Boolean,
    val shouldConfirmExit: Boolean,
    /** 键入防抖预检未落（旧按钮禁用语义）；上一次判定仍留在屏上。 */
    val storageCheckPending: Boolean,
) {

    /**
     * 每步的通行门（旧 Activity 的按钮 isEnabled 语义）：
     * 存储步要求预检通过（防抖检查未落时门保持关闭），硬件步要求分配安全，
     * 其余步无额外门槛；创建进行中一律阻断。
     */
    val canProceed: Boolean
        get() = when (step) {
            InstanceCreationWizardModel.CreationStep.STEP_STORAGE ->
                storage.gate == StorageGate.Ready && !storageCheckPending
            InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR -> true
            InstanceCreationWizardModel.CreationStep.STEP_HARDWARE -> hardware.badge !is HardwareBadge.Error
            InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS -> true
        } && !isCreating

    companion object {

        private const val BYTES_PER_GB: Double = 1024.0 * 1024.0 * 1024.0

        /**
         * 模型 + Android 事实 → UI 快照。唯一的投影入口；不写模型任何字段
         * （RAM 建议值等模型写入是 `WizardController` 的动作，不是投影）。
         */
        fun of(
            model: InstanceCreationWizardModel,
            facts: WizardFacts,
        ): WizardUiState {
            val storageUi = storageUi(model, facts)
            val avfUi = avfUi(model, facts)
            val hardwareUi = hardwareUi(model, facts, avfUi)
            return WizardUiState(
                step = model.currentStep,
                stepIndex = model.stepIndex,
                totalSteps = model.totalSteps,
                storage = storageUi,
                network = networkUi(model, facts),
                hardware = hardwareUi,
                startup = startupUi(model),
                creation = creationUi(model, facts),
                avf = avfUi,
                isCreating = model.isCreating,
                canGoBack = model.canGoBack(),
                shouldConfirmExit = model.shouldConfirmExit(),
                storageCheckPending = facts.storageCheckPending,
            )
        }

        // ---------- 存储步 ----------

        private fun storageUi(
            model: InstanceCreationWizardModel,
            facts: WizardFacts,
        ): StorageUi {
            val gate = storageGate(model, facts)
            val options = WizardDistroCatalog.official
            return StorageUi(
                distroOptions = options,
                selectedDistroId = model.distroId,
                selectedDistro = options.firstOrNull { it.id == model.distroId },
                availableGb = facts.availableBytes / BYTES_PER_GB,
                diskSizeGb = model.diskSizeGb,
                presets = InstanceCreationWizardModel.DISK_PRESET_GBS,
                selectedPresetGb = InstanceCreationWizardModel.DISK_PRESET_GBS
                    .firstOrNull { it == model.diskSizeGb },
                gate = gate,
                mountSharedDir = model.mountHostSharedDirectory,
                sharedDirPath = model.hostSharedDirectoryPath,
            )
        }

        private fun storageGate(
            model: InstanceCreationWizardModel,
            facts: WizardFacts,
        ): StorageGate {
            // 输入非正数/空：模型保持不动，通行门显式关闭（旧 afterTextChanged 语义）。
            if (facts.diskInputInvalid) return StorageGate.InvalidInput
            return when (
                val result = model.validateStorage(
                    facts.availableBytes,
                    model.requestedDiskBytes,
                    facts.existingCommittedBytes,
                )
            ) {
                is InstanceCreationWizardModel.StorageValidationResult.Valid -> StorageGate.Ready
                is InstanceCreationWizardModel.StorageValidationResult.InsufficientSpace ->
                    StorageGate.InsufficientSpace(
                        availableGb = result.availableBytes / BYTES_PER_GB,
                        requiredGb = result.requiredBytes / BYTES_PER_GB,
                        existingCommittedBytes = result.existingCommittedBytes,
                        // 已有实例承诺占用 → E-STORAGE-FULL 族受控文案（T4③ 受控注入腿）
                        error = if (result.existingCommittedBytes > 0L) {
                            ErrorCopy.resolve(
                                InstanceCreationWizardModel.storagePreflightRejection(
                                    WizardCopy.committedDetailGb(result.existingCommittedBytes),
                                ),
                            )
                        } else {
                            null
                        },
                    )
                is InstanceCreationWizardModel.StorageValidationResult.InvalidRequestedSize ->
                    StorageGate.InvalidInput
            }
        }

        // ---------- 网络步 ----------

        private fun networkUi(
            model: InstanceCreationWizardModel,
            facts: WizardFacts,
        ): NetworkUi {
            val path = DistroMirrorProvisioner.getTargetConfigPath(model.distroId)
            return NetworkUi(
                modeLine = facts.network.modeLine,
                ipLine = facts.network.ipLine,
                gatewayLine = facts.network.gatewayLine,
                dnsLine = facts.network.dnsLine,
                testResult = facts.network.testResult,
                speedTestSummary = facts.network.speedTestSummary,
                mirrorOptions = DistroMirrorProvisioner.MirrorSource.values()
                    .map { it.displayName() },
                selectedMirrorOrdinal = model.selectedMirror.ordinal,
                mirrorPreview = WizardCopy.mirrorPreview(
                    path = path,
                    urls = model.resolveMirrorUrls(),
                ),
            )
        }

        // ---------- 硬件步 ----------

        /**
         * RAM 滑杆区间（旧 bindRamHardware 的纯派生腿）：宿主预算 256MB 取整、
         * 镜像最低要求作下界、建议值必然落在区间内；宿主装不下最低要求时禁用。
         */
        private data class RamRange(
            val minMb: Int,
            val maxMb: Int,
            val stepMb: Int,
            val enabled: Boolean,
            val minimumBootMb: Int?,
        )

        private fun ramRange(model: InstanceCreationWizardModel): RamRange {
            val manifest = manifestOrNull(model)
            if (manifest == null) {
                return RamRange(minMb = 0, maxMb = 0, stepMb = 0, enabled = false, minimumBootMb = null)
            }
            val minimum = try {
                DistroResourceRequirements.minimumBootRamMb(manifest.sizeBytes())
            } catch (_: IllegalArgumentException) {
                return RamRange(minMb = 0, maxMb = 0, stepMb = 0, enabled = false, minimumBootMb = null)
            }
            if (minimum > VmHardwareConfig.MAX_RAM_MEGABYTES) {
                return RamRange(minMb = minimum, maxMb = minimum, stepMb = 0, enabled = false, minimumBootMb = minimum)
            }
            val safeMaximum = ((model.hardwareProfile.totalRamMb().toLong()
                - HostHardwareProfile.HOST_RESERVED_RAM_MB).coerceIn(
                    0L, VmHardwareConfig.MAX_RAM_MEGABYTES.toLong(),
                ) / 256L * 256L).toInt()
            val suggested = DistroResourceRequirements.suggestedWizardRamMb(
                manifest.sizeBytes(),
                model.hardwareConfig.ramMegabytes(),
                safeMaximum,
            )
            return RamRange(
                minMb = minimum,
                maxMb = maxOf(safeMaximum, minimum + 256, suggested),
                // 256MB 步进只在建议值与步进对齐时启用；未对齐的既有分配保持连续可调。
                stepMb = if (suggested % 256 == 0) 256 else 0,
                enabled = safeMaximum > minimum ||
                    (safeMaximum == minimum && suggested > minimum),
                minimumBootMb = minimum,
            )
        }

        private fun hardwareUi(
            model: InstanceCreationWizardModel,
            facts: WizardFacts,
            avfUi: AvfCardUi,
        ): HardwareUi {
            val ram = ramRange(model)
            val manifest = manifestOrNull(model)
            val minimum = ram.minimumBootMb
            val badge = hardwareBadge(model, manifest, minimum)
            val cpuAvfNotice = avfUi.severity == AvfSeverity.Green &&
                AvfLaunchAdmission.cpuSupport(model.hardwareConfig.cpuCores()) ==
                AvfLaunchAdmission.CpuSupport.NEEDS_SINGLE_VCPU
            return HardwareUi(
                cpuCores = model.hardwareConfig.cpuCores(),
                cpuMax = model.hardwareProfile.totalCores().coerceAtLeast(1),
                ramMb = model.hardwareConfig.ramMegabytes(),
                ramMinMb = ram.minMb,
                ramMaxMb = ram.maxMb,
                ramStepMb = ram.stepMb,
                ramEnabled = ram.enabled,
                minimumBootRamMb = minimum,
                cpuAvfNotice = cpuAvfNotice,
                badge = badge,
                needsAuthEntry = avfUi.needsAuthEntry,
            )
        }

        /**
         * 硬件徽标 = 旧 hardwareValidationMessage 的三段拒绝（清单缺失 → 最低
         * 内存 → 宿主安全红区）+ GREEN/YELLOW 安全区映射。RED 区与「最低内存
         * 不足」在旧代码里同样表现为红字错误文案，这里统一为 [HardwareBadge.Error]。
         */
        private fun hardwareBadge(
            model: InstanceCreationWizardModel,
            manifest: DistroManifest?,
            minimumBootMb: Int?,
        ): HardwareBadge {
            if (manifest == null) {
                return HardwareBadge.Error(WizardCopy.MANIFEST_MISSING)
            }
            try {
                DistroResourceRequirements.requireBootRam(manifest.sizeBytes(), model.hardwareConfig.ramMegabytes())
            } catch (rejected: IllegalArgumentException) {
                return HardwareBadge.Error(rejected.message ?: WizardCopy.MANIFEST_MISSING)
            }
            return when (model.safetyZone()) {
                HostHardwareProfile.SafetyZone.GREEN -> HardwareBadge.Green
                HostHardwareProfile.SafetyZone.YELLOW -> HardwareBadge.Yellow
                HostHardwareProfile.SafetyZone.RED -> HardwareBadge.Error(
                    WizardCopy.redZoneBelowMinimum(minimumBootMb ?: 0),
                )
            }
        }

        // ---------- 启动任务步 ----------

        private fun startupUi(model: InstanceCreationWizardModel): StartupUi {
            val debianOnly = model.distroId == "debian"
            val aptFamily = model.distroId in setOf("debian", "ubuntu")
            return StartupUi(
                username = model.guestUsernameDraft,
                hostname = model.guestHostnameDraft,
                sudo = model.guestSudoDraft,
                desktopEnabled = debianOnly && !model.isCreating,
                desktopSelected = model.desktopSelected,
                podmanEnabled = aptFamily && !model.isCreating,
                podmanSelected = model.podmanSelected,
                editorEnabled = aptFamily && !model.isCreating,
                editorSelected = model.editorSelected,
                autoUpdate = model.autoUpdateOnFirstBoot,
                updateCommand = model.resolveUpdateCommand(),
            )
        }

        // ---------- 创建过程 ----------

        private fun creationUi(
            model: InstanceCreationWizardModel,
            facts: WizardFacts,
        ): CreationUi {
            val phase = facts.creationPhase
                ?: if (model.isCreating) CreationPhase.KeptRunning else CreationPhase.Idle
            val status = facts.creationStatus
            return CreationUi(
                phase = phase,
                statusLine = status?.statusLine,
                progressPercent = status?.progressPercent,
                speedEtaLine = status?.speedEtaLine,
                mirrorLine = status?.mirrorLine,
            )
        }

        // ---------- AVF 卡 ----------

        private fun avfUi(
            model: InstanceCreationWizardModel,
            facts: WizardFacts,
        ): AvfCardUi {
            val facts = facts.avf ?: return AvfCardUi.hidden
            return AvfCardUi(
                severity = severityOf(facts.status),
                badge = facts.badge,
                summary = facts.summary,
                details = facts.details,
                expanded = facts.expanded,
                fallbackVisible = facts.fallbackVisible,
                // 授权入口从状态派生（旧 bindWizardAvfCard：仅 NEEDS_AUTHORIZATION 可见）
                needsAuthEntry = facts.status == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
            )
        }

        /** 状态 → 徽标严重度；映射与旧 bindWizardAvfCard 的取色一一对应。 */
        private fun severityOf(status: AvfCapabilityEvaluator.AvfStatus): AvfSeverity = when (status) {
            AvfCapabilityEvaluator.AvfStatus.READY -> AvfSeverity.Green
            AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION -> AvfSeverity.Yellow
            AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED -> AvfSeverity.Neutral
            AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN -> AvfSeverity.Yellow
            AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED -> AvfSeverity.Red
            AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE -> AvfSeverity.Red
            else -> AvfSeverity.Yellow
        }

        private fun manifestOrNull(model: InstanceCreationWizardModel): DistroManifest? =
            DistroCatalogRegistry.getInstance().get(model.distroId)
    }
}

// ---------------------------------------------------------------------------
// 子视图快照
// ---------------------------------------------------------------------------

/** AVF 徽标严重度；颜色映射由视图层承担（severity → 语义色 token）。 */
enum class AvfSeverity { Green, Yellow, Red, Neutral }

/** 创建过程的诚实相位；`KeptRunning` 是进程恢复后「状态已保留、无活进度」的显式形态。 */
enum class CreationPhase { Idle, Fetching, Provisioning, Failed, KeptRunning }

/** 网络连通性自检按钮的结果相位（按钮文案与颜色由视图层映射）。 */
enum class NetworkTestResult { Idle, Testing, Ok, Failed }

sealed interface StorageGate {
    /** 预检通过：可以下一步。 */
    data object Ready : StorageGate

    /** 自定义容量输入非法（空/非正数）：模型保持不动，门显式关闭。 */
    data object InvalidInput : StorageGate

    /**
     * 宿主空间不足：携带结构化数值供视图组文案；[error] 只在已有实例承诺
     * 占用被计入时携带（E-STORAGE-FULL 族受控注入腿）。
     */
    data class InsufficientSpace(
        val availableGb: Double,
        val requiredGb: Double,
        val existingCommittedBytes: Long,
        val error: ErrorCopy.Entry?,
    ) : StorageGate
}

sealed interface HardwareBadge {
    /** 宿主保留充裕。 */
    data object Green : HardwareBadge

    /** 接近预算上沿，可能引发宿主后台回收。 */
    data object Yellow : HardwareBadge

    /** 清单缺失 / 最低内存不足 / 宿主安全红区：一律阻断下一步与创建。 */
    data class Error(val message: String) : HardwareBadge
}

data class DistroOptionUi(
    val id: String,
    val name: String,
    val tag: String,
    val desc: String,
    @DrawableRes val iconRes: Int,
)

/** AVF 卡的视图快照；探针事实由控制器注入，投影做严重度与授权入口的派生。 */
data class WizardAvfFacts(
    val status: AvfCapabilityEvaluator.AvfStatus,
    val badge: String,
    val summary: String,
    val details: String,
    val expanded: Boolean = false,
    val fallbackVisible: Boolean = false,
)

data class AvfCardUi(
    val severity: AvfSeverity,
    val badge: String,
    val summary: String,
    val details: String,
    val expanded: Boolean,
    val fallbackVisible: Boolean,
    val needsAuthEntry: Boolean,
) {
    companion object {
        /** 探针事实缺席（预览/测试外注入）时的中性占位。 */
        val hidden = AvfCardUi(
            severity = AvfSeverity.Neutral,
            badge = "",
            summary = "",
            details = "",
            expanded = false,
            fallbackVisible = false,
            needsAuthEntry = false,
        )
    }
}

/** 网络诊断行的 Android 侧事实（由控制器探测后注入；投影不联网）。 */
data class WizardNetworkFacts(
    val modeLine: String = "",
    val ipLine: String = "",
    val gatewayLine: String = "",
    val dnsLine: String = "",
    val testResult: NetworkTestResult = NetworkTestResult.Idle,
    /** 测速结果块；null = 尚未测速（旧 txtWizardSpeedTestResults 隐藏态）。 */
    val speedTestSummary: String? = null,
)

/** 下载/装配进度的活事实；`progressPercent = null` 表示不确定进度。 */
data class CreationStatusFacts(
    val statusLine: String? = null,
    val progressPercent: Int? = null,
    val speedEtaLine: String? = null,
    val mirrorLine: String? = null,
)

/**
 * Android 侧事实的注入包；全部字段带默认值，纯 JVM 测试只填需要的腿。
 */
data class WizardFacts(
    val availableBytes: Long = 0L,
    val existingCommittedBytes: Long = 0L,
    /** 自定义容量输入非法（空/非正数）；模型保持不动，门显式关闭。 */
    val diskInputInvalid: Boolean = false,
    /** 键入防抖预检未落：上次判定留在屏上，通行门关闭。 */
    val storageCheckPending: Boolean = false,
    val network: WizardNetworkFacts = WizardNetworkFacts(),
    val avf: WizardAvfFacts? = null,
    /** 控制器持有的活相位；null 表示无活工作（恢复出 creating → KeptRunning）。 */
    val creationPhase: CreationPhase? = null,
    val creationStatus: CreationStatusFacts? = null,
)

data class StorageUi(
    val distroOptions: List<DistroOptionUi>,
    val selectedDistroId: String,
    val selectedDistro: DistroOptionUi?,
    val availableGb: Double,
    val diskSizeGb: Int,
    val presets: List<Int>,
    val selectedPresetGb: Int?,
    val gate: StorageGate,
    val mountSharedDir: Boolean,
    val sharedDirPath: String?,
)

data class NetworkUi(
    val modeLine: String,
    val ipLine: String,
    val gatewayLine: String,
    val dnsLine: String,
    val testResult: NetworkTestResult,
    val speedTestSummary: String?,
    val mirrorOptions: List<String>,
    val selectedMirrorOrdinal: Int,
    val mirrorPreview: String,
)

data class HardwareUi(
    val cpuCores: Int,
    val cpuMax: Int,
    val ramMb: Int,
    val ramMinMb: Int,
    val ramMaxMb: Int,
    val ramStepMb: Int,
    val ramEnabled: Boolean,
    val minimumBootRamMb: Int?,
    /** AVF 就绪但仅支持单 vCPU 时的专用提示（守卫测试锁定的可见性）。 */
    val cpuAvfNotice: Boolean,
    val badge: HardwareBadge,
    /** AVF 尚未授权时的「获取授权指引」入口可见性。 */
    val needsAuthEntry: Boolean,
)

data class StartupUi(
    val username: String,
    val hostname: String,
    val sudo: Boolean,
    val desktopEnabled: Boolean,
    val desktopSelected: Boolean,
    val podmanEnabled: Boolean,
    val podmanSelected: Boolean,
    val editorEnabled: Boolean,
    val editorSelected: Boolean,
    val autoUpdate: Boolean,
    val updateCommand: String,
)

data class CreationUi(
    val phase: CreationPhase,
    val statusLine: String?,
    val progressPercent: Int?,
    val speedEtaLine: String?,
    val mirrorLine: String?,
)

// ---------------------------------------------------------------------------
// 投影侧常量与目录
// ---------------------------------------------------------------------------

/**
 * 投影产出的既有文案字面量（从旧 Activity 原样迁移，保持文案零漂移）。
 * 这些是「模型判定派生的用户文案」，必须在 JVM 契约测试里可断言，所以
 * 不走 Android 资源；新 UI 自有的界面文案仍走 strings.xml。
 */
object WizardCopy {

    /** 旧 getManifest 的清单缺失文案。 */
    const val MANIFEST_MISSING: String =
        "无法确认该发行版的镜像清单及首次装配内存需求；请重新选择镜像。"

    /** 旧 updateHardwareLabels 的宿主安全红区文案。 */
    fun redZoneBelowMinimum(minimumBootMb: Int): String =
        "该镜像首次装配至少需要 $minimumBootMb MB；当前硬件分配超过宿主安全边界，请调整硬件或选择较小镜像。"

    /** E-STORAGE-FULL 受控注入腿的 detail 参数（旧Activity 同款）。 */
    fun committedDetailGb(existingCommittedBytes: Long): String =
        "已有实例承诺 ${existingCommittedBytes / (1024L * 1024L * 1024L)} GB"

    /** 镜像预览块（旧 updateMirrorPreview 同款两段结构）。 */
    fun mirrorPreview(path: String, urls: List<String>): String =
        "配置目标：$path\n解析节点：\n" + urls.joinToString("\n")
}

/**
 * 官方发行版目录（旧 Activity 内嵌 `officialDistros` 的数据化迁移）。
 * 纯数据：图标沿用既有 drawable 资产（不替换图标，只引用）。
 */
object WizardDistroCatalog {

    val official: List<DistroOptionUi> = listOf(
        DistroOptionUi(
            id = "ubuntu",
            name = "Ubuntu LTS",
            tag = "通用开发 · 云原生首选 · 24.04 LTS",
            desc = "工业标准、生态最广，预设多层 APT 源与 Docker 加速",
            iconRes = io.github.nyzeep.orchestella.R.drawable.ic_distro_ubuntu,
        ),
        DistroOptionUi(
            id = "debian",
            name = "Debian Stable",
            tag = "稳如磐石 · 经典基石 · Debian 13",
            desc = "全开源坚实底座，极高稳定性与完整 GNU 工具链",
            iconRes = io.github.nyzeep.orchestella.R.drawable.ic_distro_debian,
        ),
        DistroOptionUi(
            id = "fedora",
            name = "Fedora",
            tag = "前沿技术 · RPM 生态 · Fedora 43",
            desc = "集成最新内核与 DNF 现代工具链，Red Hat 社区前沿",
            iconRes = io.github.nyzeep.orchestella.R.drawable.ic_distro_fedora,
        ),
        DistroOptionUi(
            id = "arch",
            name = "Arch Linux",
            tag = "极客首选 · 滚动更新 · pacman",
            desc = "轻量 DIY，滚动更新最新软件包与强大的社区支持",
            iconRes = io.github.nyzeep.orchestella.R.drawable.ic_distro_arch,
        ),
        DistroOptionUi(
            id = "opensuse",
            name = "openSUSE Leap",
            tag = "严谨规范 · 版本化发布 · zypper",
            desc = "版本化稳定发布，zypper 依赖解析与企业级基线",
            iconRes = io.github.nyzeep.orchestella.R.drawable.ic_distro_opensuse,
        ),
        DistroOptionUi(
            id = "almalinux",
            name = "AlmaLinux",
            tag = "企业级 · RHEL 1:1 二进制兼容 · 9",
            desc = "稳定企业环境模拟，长期支持保障与 DNF 生态",
            iconRes = io.github.nyzeep.orchestella.R.drawable.ic_distro_almalinux,
        ),
        DistroOptionUi(
            id = "rocky",
            name = "Rocky Linux",
            tag = "生产级稳定 · 企业计算 · 9.6",
            desc = "CentOS 官方替代，生产级高可靠性与 RHEL 兼容",
            iconRes = io.github.nyzeep.orchestella.R.drawable.ic_distro_rocky,
        ),
    )
}
