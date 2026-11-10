package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig

/**
 * Single-source value object for every user-editable field of the 4-step
 * creation wizard (candidate 4).
 *
 * <p>Before this object, each field was hand-carried between model and views
 * in six scattered places (bind/render, per-field listener, capture, distro
 * carry-over, save/restore). Now the flow is strictly one-way:
 *
 * ```
 * views --read()--> WizardDraft --applyTo(model)--> model
 * model --fromModel()--> WizardDraft --render--> views
 * ```
 *
 * <p>A new field is registered exactly once per layer:
 * <ul>
 *   <li>[WizardDraft] property + [WizardDraftFields] row — value semantics
 *       (parse/clamp) and model application, JVM-contract-tested here;</li>
 *   <li>one [io.github.nyzeep.orchestella.SetupWizardActivity] widget row —
 *       pure view wiring (readView/renderView).</li>
 * </ul>
 *
 * <p>The versioned persistence map stays in
 * [InstanceCreationWizardModel.saveDraft]/restoreDraft — that seam is already
 * contract-tested and is not duplicated here.
 */
data class WizardDraft(
    val step: InstanceCreationWizardModel.CreationStep =
        InstanceCreationWizardModel.CreationStep.STEP_STORAGE,
    val distroId: String = DEFAULT_DISTRO_ID,
    val diskSizeGb: Int = DEFAULT_DISK_GB,
    val cpuCores: Int = DEFAULT_CPU_CORES,
    val ramMb: Int = DEFAULT_RAM_MB,
    val mirrorOrdinal: Int = DEFAULT_MIRROR_ORDINAL,
    val guestUsername: String = "",
    val guestHostname: String = "",
    val guestSudo: Boolean = false,
    val desktopSelected: Boolean = false,
    val podmanSelected: Boolean = false,
    val editorSelected: Boolean = false,
    val autoUpdateOnFirstBoot: Boolean = false,
    val mountSharedDir: Boolean = false,
    val sharedDirPath: String? = null,
    val creating: Boolean = false
) {

    /**
     * Clamp-only normalization. Guarantees [applyTo] can never trip a model
     * `require`/index failure. Deliberately does NOT clamp the disk upper
     * bound: an oversized request must reach validateStorage intact so the
     * E-STORAGE-FULL-family copy can explain itself; restoreDraft re-admits
     * the 2..64 range at restore time. Identity text is carried verbatim —
     * incomplete drafts are preserved, credentials are never stored here.
     */
    fun normalized(): WizardDraft = copy(
        diskSizeGb = diskSizeGb.coerceAtLeast(MIN_DISK_GB),
        cpuCores = cpuCores.coerceIn(VmHardwareConfig.MIN_CPU_CORES, VmHardwareConfig.MAX_CPU_CORES),
        ramMb = ramMb.coerceIn(VmHardwareConfig.MIN_RAM_MEGABYTES, VmHardwareConfig.MAX_RAM_MEGABYTES),
        mirrorOrdinal = if (mirrorOrdinal in MirrorSourceValues.indices) mirrorOrdinal
        else DEFAULT_MIRROR_ORDINAL
    )

    /**
     * Draft → model. Every write goes through the model's own mutators, so
     * clamping, distro gating and hardware-config syncing stay model-owned.
     *
     * <p>Two fields are deliberately never written:
     * <ul>
     *   <li>distroId — switching distro rebuilds the model (manifest version,
     *       per-distro profile gating) via the dedicated switch path;</li>
     *   <li>creating — creation state is model-owned lifecycle; a draft can
     *       observe it but must never flip it.</li>
     * </ul>
     */
    fun applyTo(model: InstanceCreationWizardModel) {
        val draft = normalized()
        model.setCustomDiskSizeGb(draft.diskSizeGb)
        model.updateCpuCores(draft.cpuCores)
        model.updateRamMegabytes(draft.ramMb)
        model.selectMirror(MirrorSourceValues[draft.mirrorOrdinal])
        model.setAutoUpdateOnFirstBoot(draft.autoUpdateOnFirstBoot)
        model.updateGuestIdentityDraft(draft.guestUsername, draft.guestHostname, draft.guestSudo)
        model.setOptionalProfiles(draft.desktopSelected, draft.podmanSelected, draft.editorSelected)
        model.setMountHostSharedDirectory(draft.mountSharedDir)
        model.setHostSharedDirectoryPath(draft.sharedDirPath)
        model.goToStep(draft.step)
    }

    companion object {
        const val DEFAULT_DISTRO_ID = "ubuntu"
        const val DEFAULT_DISK_GB = 8
        /** Neutral floor; host-recommended defaults come from [fromModel]. */
        val DEFAULT_CPU_CORES: Int = VmHardwareConfig.MIN_CPU_CORES
        const val DEFAULT_RAM_MB = 1024
        const val DEFAULT_MIRROR_ORDINAL = 0

        /** Draft admission floor, mirroring the model's restore range (2..64 GB). */
        val MIN_DISK_GB: Int = (VmHardwareConfig.MIN_DISK_CAPACITY_BYTES / BYTES_PER_GB).toInt()

        private const val BYTES_PER_GB: Long = 1024L * 1024L * 1024L

        private val MirrorSourceValues = DistroMirrorProvisioner.MirrorSource.values()

        /** Model → draft: the render side's single entry point. */
        fun fromModel(model: InstanceCreationWizardModel): WizardDraft = WizardDraft(
            step = model.currentStep,
            distroId = model.distroId,
            diskSizeGb = model.diskSizeGb,
            cpuCores = model.hardwareConfig.cpuCores(),
            ramMb = model.hardwareConfig.ramMegabytes(),
            mirrorOrdinal = model.selectedMirror.ordinal,
            guestUsername = model.guestUsernameDraft,
            guestHostname = model.guestHostnameDraft,
            guestSudo = model.guestSudoDraft,
            desktopSelected = model.desktopSelected,
            podmanSelected = model.podmanSelected,
            editorSelected = model.editorSelected,
            autoUpdateOnFirstBoot = model.autoUpdateOnFirstBoot,
            mountSharedDir = model.mountHostSharedDirectory,
            sharedDirPath = model.hostSharedDirectoryPath,
            creating = model.isCreating
        )
    }
}

/**
 * One named field lane: draft accessor pair plus raw-widget-text parse. Pure
 * JVM; the activity binds each lane to its widget pair in a single row.
 * `parse` returning null means "raw input not admissible — keep the current
 * draft value", which keeps invalid keystrokes from ever reaching the model.
 */
class WizardField<T>(
    val name: String,
    val get: (WizardDraft) -> T,
    val set: (WizardDraft, T) -> WizardDraft,
    val parse: (String?) -> T?
) {
    /** Folds one raw widget text into the draft; null parse keeps [draft] as-is. */
    fun parseInto(draft: WizardDraft, raw: String?): WizardDraft =
        parse(raw)?.let { set(draft, it) } ?: draft
}

/** The field registry: the one place a field's value semantics are declared. */
object WizardDraftFields {

    val distroId = WizardField<String>(
        name = "distroId",
        get = { it.distroId },
        set = { d, v -> d.copy(distroId = v) },
        parse = { it?.trim()?.takeIf { raw -> raw.isNotEmpty() } }
    )

    val diskSizeGb = WizardField<Int>(
        name = "diskSizeGb",
        get = { it.diskSizeGb },
        set = { d, v -> d.copy(diskSizeGb = v) },
        // > 0 only: non-numeric or non-positive input is not admissible and
        // leaves the draft (and the model) untouched.
        parse = { it?.trim()?.toIntOrNull()?.takeIf { gb -> gb > 0 } }
    )

    val cpuCores = WizardField<Int>(
        name = "cpuCores",
        get = { it.cpuCores },
        set = { d, v -> d.copy(cpuCores = v) },
        parse = { it?.trim()?.toIntOrNull() }
    )

    val ramMb = WizardField<Int>(
        name = "ramMb",
        get = { it.ramMb },
        set = { d, v -> d.copy(ramMb = v) },
        parse = { it?.trim()?.toIntOrNull() }
    )

    val mirrorOrdinal = WizardField<Int>(
        name = "mirrorOrdinal",
        get = { it.mirrorOrdinal },
        set = { d, v -> d.copy(mirrorOrdinal = v) },
        parse = { it?.trim()?.toIntOrNull()?.takeIf { ordinal ->
            ordinal in DistroMirrorProvisioner.MirrorSource.values().indices
        } }
    )

    val guestUsername = WizardField<String>(
        name = "guestUsername",
        get = { it.guestUsername },
        set = { d, v -> d.copy(guestUsername = v) },
        // Any text, including incomplete input: identity drafts are preserved.
        parse = { it }
    )

    val guestHostname = WizardField<String>(
        name = "guestHostname",
        get = { it.guestHostname },
        set = { d, v -> d.copy(guestHostname = v) },
        parse = { it }
    )

    private fun booleanField(
        name: String,
        get: (WizardDraft) -> Boolean,
        set: (WizardDraft, Boolean) -> WizardDraft
    ) = WizardField<Boolean>(
        name = name,
        get = get,
        set = set,
        parse = { raw -> when (raw) {
            "true" -> true
            "false" -> false
            else -> null
        } }
    )

    val guestSudo = booleanField("guestSudo", { it.guestSudo }, { d, v -> d.copy(guestSudo = v) })

    val desktopSelected = booleanField("desktopSelected", { it.desktopSelected },
        { d, v -> d.copy(desktopSelected = v) })

    val podmanSelected = booleanField("podmanSelected", { it.podmanSelected },
        { d, v -> d.copy(podmanSelected = v) })

    val editorSelected = booleanField("editorSelected", { it.editorSelected },
        { d, v -> d.copy(editorSelected = v) })

    val autoUpdateOnFirstBoot = booleanField("autoUpdateOnFirstBoot", { it.autoUpdateOnFirstBoot },
        { d, v -> d.copy(autoUpdateOnFirstBoot = v) })

    val mountSharedDir = booleanField("mountSharedDir", { it.mountSharedDir },
        { d, v -> d.copy(mountSharedDir = v) })

    /**
     * Render-only lane: the path is chosen through the folder picker, never
     * typed, so `parse` always declines and read keeps the model's value.
     */
    val sharedDirPath = WizardField<String?>(
        name = "sharedDirPath",
        get = { it.sharedDirPath },
        set = { d, v -> d.copy(sharedDirPath = v) },
        parse = { null }
    )

    /** All registered lanes, in declaration order. */
    val all: List<WizardField<*>> = listOf(
        distroId, diskSizeGb, cpuCores, ramMb, mirrorOrdinal,
        guestUsername, guestHostname, guestSudo, desktopSelected, podmanSelected,
        editorSelected, autoUpdateOnFirstBoot, mountSharedDir, sharedDirPath
    )
}
