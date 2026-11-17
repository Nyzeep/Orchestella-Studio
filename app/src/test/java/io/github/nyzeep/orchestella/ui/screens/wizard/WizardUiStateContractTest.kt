package io.github.nyzeep.orchestella.ui.screens.wizard

import io.github.nyzeep.orchestella.DistroResourceRequirements
import io.github.nyzeep.orchestella.ErrorCopy
import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.InstanceCreationWizardModel
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * UI-12 步骤 0 的接缝契约：`WizardUiState` 是模型层（`InstanceCreationWizardModel`）
 * 的**只读投影**——UI 渲染所需的一切判定（四步映射、每步通行门、预设高亮、
 * 镜像序、可选功能可用性、RAM 滑杆区间、AVF 严重度、创建相位）都必须从
 * `WizardUiState.of(model, facts)` 推导，不允许视图层绕过投影直接读写模型。
 *
 * 模型行为本身已由 `InstanceCreationWizardModelTest` 等既有用例锁定；本文件只锁
 * 「投影不改变、不遗漏、不编造」这一层契约（纯 JVM，不触 Android 类）。
 */
class WizardUiStateContractTest {

    private lateinit var profile: HostHardwareProfile
    private lateinit var model: InstanceCreationWizardModel

    @Before
    fun setUp() {
        // 8 核 / 8GB 总内存 / 4GB 可用：与既有模型用例同一构造，行为可预期。
        profile = HostHardwareProfile(
            8,
            8L * GB,
            4L * GB,
            512L * MB,
            false,
        )
        val manifest = DistroCatalogRegistry.getInstance().get("ubuntu")
        model = InstanceCreationWizardModel(
            distroId = manifest?.id() ?: "ubuntu",
            distroVersion = manifest?.version() ?: "24.04",
            hardwareProfile = profile,
        )
    }

    // ---------- 四步映射 ----------

    @Test
    fun fourStepsMirrorModel_oneToOne() {
        val state = WizardUiState.of(model, WizardFacts())

        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_STORAGE, state.step)
        assertEquals(model.stepIndex, state.stepIndex)
        assertEquals(4, state.totalSteps)

        model.nextStep()
        val second = WizardUiState.of(model, WizardFacts())
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_NETWORK_MIRROR, second.step)
        assertEquals(2, second.stepIndex)

        model.nextStep(); model.nextStep()
        val fourth = WizardUiState.of(model, WizardFacts())
        assertEquals(InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS, fourth.step)
        assertEquals(4, fourth.stepIndex)
    }

    // ---------- 返回 / 退出判定 ----------

    @Test
    fun canGoBackMirrorsModel() {
        val first = WizardUiState.of(model, WizardFacts())
        assertFalse(first.canGoBack)

        model.nextStep(); model.nextStep()
        val third = WizardUiState.of(model, WizardFacts())
        assertTrue(third.canGoBack)
    }

    @Test
    fun canGoBackBlockedWhileCreating() {
        model.nextStep()
        model.setCreating(true)
        val state = WizardUiState.of(model, WizardFacts())
        assertFalse(state.canGoBack)
        assertFalse(state.canProceed)
    }

    @Test
    fun shouldConfirmExitMirrorsModel() {
        val idle = WizardUiState.of(model, WizardFacts())
        assertFalse(idle.shouldConfirmExit)

        model.setCreating(true)
        val creating = WizardUiState.of(model, WizardFacts())
        assertTrue(creating.shouldConfirmExit)
    }

    // ---------- 每步通行门 ----------

    @Test
    fun canProceedStorageReadyWhenHostSpaceSufficient() {
        val facts = WizardFacts(
            availableBytes = 128L * GB,
            existingCommittedBytes = 0L,
        )
        val state = WizardUiState.of(model, facts)
        assertEquals(StorageGate.Ready, state.storage.gate)
        assertTrue(state.canProceed)
    }

    @Test
    fun canProceedStorageBlockedWhenSpaceInsufficient() {
        // 需要 8GB 磁盘 + 2GB 宿主保留；给 4GB 可用 → 必然拦截。
        val facts = WizardFacts(
            availableBytes = 4L * GB,
            existingCommittedBytes = 0L,
        )
        val state = WizardUiState.of(model, facts)
        val gate = state.storage.gate
        assertTrue(gate is StorageGate.InsufficientSpace)
        assertFalse(state.canProceed)
        // requiredBytes = 请求 + 承诺 + 2GB 保留（模型契约，T4③）
        val required = (gate as StorageGate.InsufficientSpace).requiredGb
        assertEquals((8L * GB + InstanceCreationWizardModel.RESERVED_HOST_BYTES) / GB.toDouble(), required, 1e-6)
    }

    @Test
    fun insufficientSpaceWithCommittedCarriesErrorCopyEntry() {
        val facts = WizardFacts(
            availableBytes = 8L * GB,
            existingCommittedBytes = 64L * GB,
        )
        val state = WizardUiState.of(model, facts)
        val gate = state.storage.gate as StorageGate.InsufficientSpace
        assertEquals(64L * GB, gate.existingCommittedBytes)
        // 已有实例承诺占用 → E-STORAGE-FULL 族文案（受控注入腿，T4③）
        assertNotNull(gate.error)
        assertEquals(ErrorCopy.CODE_STORAGE_FULL, gate.error!!.code)
        assertFalse(state.canProceed)
    }

    @Test
    fun canProceedStorageBlockedOnInvalidInput() {
        val facts = WizardFacts(
            availableBytes = 128L * GB,
            diskInputInvalid = true,
        )
        val state = WizardUiState.of(model, facts)
        assertEquals(StorageGate.InvalidInput, state.storage.gate)
        assertFalse(state.canProceed)
    }

    @Test
    fun canProceedHardwareBlockedWhenAllocationUnsafe() {
        model.nextStep(); model.nextStep()
        // 把 RAM 压到镜像最低要求之下 → DistroResourceRequirements.requireBootRam 拒绝
        model.updateRamMegabytes(VmHardwareTestBridge.MIN_RAM_MEGABYTES)
        val state = WizardUiState.of(model, WizardFacts())
        val badge = state.hardware.badge
        assertTrue(badge is HardwareBadge.Error)
        assertFalse(state.canProceed)
    }

    @Test
    fun canProceedHardwareReadyWithinSafeZone() {
        model.nextStep(); model.nextStep()
        model.updateCpuCores(2)
        model.updateRamMegabytes(2048)
        val state = WizardUiState.of(model, WizardFacts())
        assertTrue(state.hardware.badge !is HardwareBadge.Error)
        assertTrue(state.canProceed)
    }

    @Test
    fun canProceedNetworkStepHasNoExtraGate() {
        model.nextStep()
        val state = WizardUiState.of(model, WizardFacts())
        assertTrue(state.canProceed)
    }

    // ---------- 磁盘预设高亮 ----------

    @Test
    fun presetsAreModelOwned() {
        val state = WizardUiState.of(model, WizardFacts())
        assertEquals(InstanceCreationWizardModel.DISK_PRESET_GBS, state.storage.presets)
    }

    @Test
    fun presetSelectionFollowsDiskSize() {
        model.setCustomDiskSizeGb(8)
        val preset = WizardUiState.of(model, WizardFacts(availableBytes = 128L * GB))
        assertEquals(8, preset.storage.selectedPresetGb)

        model.setCustomDiskSizeGb(6)
        val custom = WizardUiState.of(model, WizardFacts(availableBytes = 128L * GB))
        assertNull(custom.storage.selectedPresetGb)
        assertEquals(6, custom.storage.diskSizeGb)
    }

    // ---------- 镜像序 ----------

    @Test
    fun mirrorOptionsFollowMirrorSourceOrder() {
        val state = WizardUiState.of(model, WizardFacts())
        val expected = DistroMirrorProvisionerBridge.sources().map { it.ordinal to it.displayName() }
        assertEquals(expected.map { it.first }, state.network.mirrorOptions.mapIndexed { i, _ -> i })
        assertEquals(expected.map { it.second }, state.network.mirrorOptions)
        assertEquals(model.selectedMirror.ordinal, state.network.selectedMirrorOrdinal)

        model.selectMirror(DistroMirrorProvisionerBridge.sources().last())
        val switched = WizardUiState.of(model, WizardFacts())
        assertEquals(DistroMirrorProvisionerBridge.sources().lastIndex, switched.network.selectedMirrorOrdinal)
    }

    // ---------- 发行版选项与可选功能可用性 ----------

    @Test
    fun distroOptionsCarrySelection() {
        val state = WizardUiState.of(model, WizardFacts())
        assertEquals("ubuntu", state.storage.selectedDistroId)
        assertNotNull(state.storage.selectedDistro)
        assertEquals(model.distroId, state.storage.selectedDistro!!.id)
        assertEquals(7, state.storage.distroOptions.size)
    }

    @Test
    fun optionalProfilesAvailabilityFollowsDistro() {
        // ubuntu：podman/editor 可选，desktop 不可选
        val ubuntu = WizardUiState.of(model, WizardFacts())
        assertFalse(ubuntu.startup.desktopEnabled)
        assertTrue(ubuntu.startup.podmanEnabled)
        assertTrue(ubuntu.startup.editorEnabled)

        // debian：三者全部可选
        val debianManifest = DistroCatalogRegistry.getInstance().get("debian")
        val debian = InstanceCreationWizardModel(
            distroId = "debian",
            distroVersion = debianManifest?.version() ?: "13",
            hardwareProfile = profile,
        )
        val debianState = WizardUiState.of(debian, WizardFacts())
        assertTrue(debianState.startup.desktopEnabled)
        assertTrue(debianState.startup.podmanEnabled)
        assertTrue(debianState.startup.editorEnabled)
    }

    // ---------- RAM 滑杆区间 ----------

    @Test
    fun ramRangeFollowsHostBudgetAndManifestMinimum() {
        val state = WizardUiState.of(model, WizardFacts())
        val manifest = DistroCatalogRegistry.getInstance().get("ubuntu")!!
        val minimum = DistroResourceRequirements.minimumBootRamMb(manifest.sizeBytes())
        val safeMaximum = ((profile.totalRamMb().toLong() - HostHardwareProfile.HOST_RESERVED_RAM_MB)
            .coerceIn(0L, VmHardwareTestBridge.MAX_RAM_MEGABYTES.toLong()) / 256L * 256L).toInt()

        assertEquals(minimum, state.hardware.ramMinMb)
        assertTrue(state.hardware.ramMaxMb >= maxOf(safeMaximum, minimum))
        // 建议值已被模型接收（bindRamHardware 的模型写入由控制器做；投影反映模型值）
        assertEquals(model.hardwareConfig.ramMegabytes(), state.hardware.ramMb)
        assertTrue(state.hardware.ramEnabled == (safeMaximum > minimum))
    }

    // ---------- AVF 严重度映射 ----------

    @Test
    fun avfSeverityMapsFromStatus() {
        val ready = WizardUiState.of(model, WizardFacts(avf = avfFacts(status = AvfTestBridge.statusReady())))
        assertEquals(AvfSeverity.Green, ready.avf.severity)
        assertFalse(ready.avf.needsAuthEntry)

        val needsAuth = WizardUiState.of(model, WizardFacts(avf = avfFacts(AvfTestBridge.statusNeedsAuthorization())))
        assertEquals(AvfSeverity.Yellow, needsAuth.avf.severity)
        assertTrue(needsAuth.avf.needsAuthEntry)

        val unsupported = WizardUiState.of(model, WizardFacts(avf = avfFacts(AvfTestBridge.statusModeUnsupported())))
        assertEquals(AvfSeverity.Neutral, unsupported.avf.severity)
        assertFalse(unsupported.avf.needsAuthEntry)
    }

    // ---------- 创建相位 ----------

    @Test
    fun creationPhaseIdleWhenModelIdleAndNoLivePhase() {
        val state = WizardUiState.of(model, WizardFacts())
        assertEquals(CreationPhase.Idle, state.creation.phase)
    }

    @Test
    fun creationPhaseKeptRunningWhenRestoredCreatingWithoutLiveWork() {
        // 旧 renderDraftState 语义：恢复出 creating=true 但没有活下载 → 诚实展示
        // 「已保留创建中状态」，绝不画假进度。
        model.setCreating(true)
        val state = WizardUiState.of(model, WizardFacts())
        assertEquals(CreationPhase.KeptRunning, state.creation.phase)
        assertNull(state.creation.progressPercent)
    }

    @Test
    fun creationPhasePassesLivePhaseThrough() {
        model.setCreating(true)
        val state = WizardUiState.of(
            model,
            WizardFacts(creationPhase = CreationPhase.Fetching),
        )
        assertEquals(CreationPhase.Fetching, state.creation.phase)
        assertTrue(state.canProceed.not())
    }

    // ---------- 投影只读 ----------

    @Test
    fun projectionCarriesModelValuesWithoutMutation() {
        val before = model.hardwareConfig.ramMegabytes()
        val state = WizardUiState.of(model, WizardFacts(availableBytes = 128L * GB))
        assertEquals(before, state.hardware.ramMb)
        assertEquals(model.diskSizeGb, state.storage.diskSizeGb)
        assertEquals(model.guestUsernameDraft, state.startup.username)
        assertEquals(model.guestHostnameDraft, state.startup.hostname)
        assertEquals(model.guestSudoDraft, state.startup.sudo)
        assertEquals(model.autoUpdateOnFirstBoot, state.startup.autoUpdate)
        assertEquals(model.mountHostSharedDirectory, state.storage.mountSharedDir)
        assertEquals(model.resolveUpdateCommand(), state.startup.updateCommand)
    }

    // ---------- fixtures ----------

    private fun avfFacts(
        status: io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator.AvfStatus,
    ) = WizardAvfFacts(
        status = status,
        badge = "test-badge",
        summary = "test-summary",
        details = "test-details",
        fallbackVisible = false,
    )

    companion object {
        private const val GB = 1024L * 1024L * 1024L
        private const val MB = 1024L * 1024L
    }
}

/**
 * 桥接对象：把各模型/常量层的静态入口集中在一处，测试可读性优先。
 * 全部是既有公共 API 的别名，不引入新语义。
 */
private object VmHardwareTestBridge {
    val MIN_RAM_MEGABYTES: Int =
        io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.MIN_RAM_MEGABYTES
    val MAX_RAM_MEGABYTES: Int =
        io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.MAX_RAM_MEGABYTES
}

private object AvfTestBridge {
    fun statusReady() = io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator.AvfStatus.READY
    fun statusNeedsAuthorization() = io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION
    fun statusModeUnsupported() = io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED
}

private object DistroMirrorProvisionerBridge {
    fun sources() = io.github.nyzeep.orchestella.DistroMirrorProvisioner.MirrorSource.values()
}
