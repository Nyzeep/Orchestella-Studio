package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.StorageContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceRootfsProvisioner
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.BooleanSupplier

/**
 * Isolated QA entry point for explicit execution-strategy metadata creation,
 * distro capability gating, explicit root-volume replacement/migration, and
 * SAF archive round-trip verification (W3-00 / W3-02).
 *
 * <p>Ordinary builds leave [BuildConfig.IS_ISOLATED_QA_BUILD] as {@code false} and keep the
 * default instance creation flow untouched. Only builds explicitly assembled with
 * {@code -Porchestella.buildChannel=qa} may enter this seam.
 */
class IsolatedQaExecutionEntry(
    private val qaChannelEnabled: BooleanSupplier = BooleanSupplier { BuildConfig.IS_ISOLATED_QA_BUILD },
) {
    fun isEnabled(): Boolean = qaChannelEnabled.asBoolean

    @JvmOverloads
    @Throws(IOException::class)
    fun createInstance(
        controller: InstanceLifecycle.Controller,
        distro: InstanceLifecycle.DistroReference,
        rootVolume: Path,
        hardwareConfig: VmHardwareConfig,
        userConfig: InstanceUserConfig,
        strategy: FullLinuxContract.ExecutionStrategy,
        volumeFormat: StorageContract.RootVolumeFormat = StorageContract.RootVolumeFormat.RAW_SPARSE,
    ): InstanceLifecycle.Instance {
        check(isEnabled()) {
            "Isolated QA execution entry is disabled outside QA builds (buildChannel=${BuildConfig.BUILD_CHANNEL})"
        }
        if (strategy == FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL) {
            UmlDistroCapabilityCatalog.requireCreatable(
                distro.id(),
                UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64,
            )
        }
        val identity = ExecutionPolicyContract.InstanceExecutionIdentity.explicit(strategy, volumeFormat)
        return controller.create(distro, rootVolume, hardwareConfig, userConfig, identity)
    }

    /**
     * Ensures one isolated QA Alpine instance configured with [FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL]
     * and [StorageContract.RootVolumeFormat.RAW_SPARSE] exists and is ready to launch.
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun ensureEphemeralAlpineInstance(
        store: InstanceLifecycle.Store,
        controller: InstanceLifecycle.Controller,
        qaRootsDir: Path,
        requestedId: String? = null,
    ): InstanceLifecycle.Instance = ensurePersistentAlpineInstance(
        store = store,
        controller = controller,
        qaRootsDir = qaRootsDir,
        requestedId = requestedId,
    )

    /**
     * Ensures one persistent per-instance UML Alpine entry backed by its own {@code rootVolume}
     * path under {@code qaRootsDir}. Refuses unverified/incompatible distros fail-closed via
     * [UmlDistroCapabilityCatalog.requireCreatable].
     */
    @JvmOverloads
    @Throws(IOException::class)
    fun ensurePersistentAlpineInstance(
        store: InstanceLifecycle.Store,
        controller: InstanceLifecycle.Controller,
        qaRootsDir: Path,
        requestedId: String? = null,
        distroId: String = "alpine",
    ): InstanceLifecycle.Instance {
        check(isEnabled()) {
            "Isolated QA execution entry is disabled outside QA builds (buildChannel=${BuildConfig.BUILD_CHANNEL})"
        }
        val gateVerdict = UmlDistroCapabilityCatalog.requireCreatable(
            distroId,
            UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64,
        )
        val projection = DistroCreatabilityProjection.evaluateForStrategy(
            strategy = FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            distroId = distroId,
            filesDir = qaRootsDir.parent ?: qaRootsDir,
            isolatedQaEnabled = isEnabled(),
            umlRootfsDownloadEnabled = BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED,
        )
        check(projection.creatable) {
            projection.refusalReason ?: "Distro $distroId is not creatable for USER_MODE_KERNEL"
        }
        if (!requestedId.isNullOrBlank()) {
            val existing = store.load(requestedId)
            require(
                existing.executionIdentity().strategy().strategy() ==
                    FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            ) {
                "Requested instance $requestedId is not configured for USER_MODE_KERNEL"
            }
            if (existing.state() == InstanceLifecycle.State.CREATED) {
                controller.beginProvisioning(existing.id())
                return controller.completeProvisioning(existing.id())
            }
            return existing
        }
        val reusable = store.list().firstOrNull { candidate ->
            candidate.executionIdentity().strategy().strategy() ==
                FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL &&
                candidate.distro().id() == gateVerdict.distroId() &&
                DEFAULT_QA_HOSTNAME == candidate.hostname()
        }
        if (reusable != null) {
            if (reusable.rootVolume() != null && Files.notExists(reusable.rootVolume())) {
                reusable.rootVolume().parent?.let { Files.createDirectories(it) }
                Files.createFile(reusable.rootVolume())
            }
            if (reusable.state() == InstanceLifecycle.State.CREATED) {
                controller.beginProvisioning(reusable.id())
                return controller.completeProvisioning(reusable.id())
            }
            return reusable
        }
        Files.createDirectories(qaRootsDir)
        val rootVolume = qaRootsDir.resolve("qa-uml-${gateVerdict.distroId()}-${UUID.randomUUID()}.raw")
        if (Files.notExists(rootVolume)) {
            Files.createFile(rootVolume)
        }
        val created = createInstance(
            controller = controller,
            distro = InstanceLifecycle.DistroReference(gateVerdict.distroId(), gateVerdict.version()),
            rootVolume = rootVolume,
            hardwareConfig = VmHardwareConfig.builder()
                .cpuCores(1)
                .ramMegabytes(512)
                .diskCapacityBytes(StorageContract.DEFAULT_ROOT_VOLUME_BYTES)
                .build(),
            userConfig = InstanceUserConfig(
                InstanceUserConfig.DEFAULT_ROOT_USER,
                "orchestellaqa",
                false,
                DEFAULT_QA_HOSTNAME,
            ),
            strategy = FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            volumeFormat = StorageContract.RootVolumeFormat.RAW_SPARSE,
        )
        controller.beginProvisioning(created.id())
        return controller.completeProvisioning(created.id())
    }

    /**
     * Explicit root-volume replacement / migration entry (W3-02 / R-02).
     * Never called implicitly on ordinary launches; preserves the old volume if provisioning fails.
     */
    @Throws(IOException::class)
    fun replaceInstanceRootVolumeExplicitly(
        store: InstanceLifecycle.Store,
        controller: InstanceLifecycle.Controller,
        provisioner: UmlInstanceRootfsProvisioner,
        reservationRegistry: UmlRootReservationRegistry,
        instanceId: String,
        seedInput: UmlInstanceRootfsProvisioner.SeedInput,
        migrationReason: String,
        kernelPath: String,
        stubExePath: String,
        runtimeDir: Path,
    ): UmlInstanceRootfsProvisioner.ProvisionReceipt {
        check(isEnabled()) {
            "Isolated QA execution entry is disabled outside QA builds (buildChannel=${BuildConfig.BUILD_CHANNEL})"
        }
        val initial = store.load(instanceId)
        val generation = "migrate-${UUID.randomUUID()}"
        val reservation = reservationRegistry.acquire(initial.rootVolume(), instanceId, generation)
        var retainReservation = false
        try {
            val instance = synchronized(store.stateMonitor()) {
                val locked = store.load(instanceId)
                require(locked.rootVolume() == initial.rootVolume()) {
                    "Root volume path changed while acquiring lock for $instanceId"
                }
                require(
                    locked.executionIdentity().strategy().strategy() ==
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                ) {
                    "Instance $instanceId is not configured for USER_MODE_KERNEL"
                }
                check(
                    locked.state() == InstanceLifecycle.State.STOPPED ||
                        locked.state() == InstanceLifecycle.State.CREATED,
                ) {
                    "Cannot replace root volume while instance $instanceId is in state ${locked.state()}"
                }
                locked
            }
            val request = UmlInstanceRootfsProvisioner.ProvisionRequest(
                instanceId,
                generation,
                instance.rootVolume(),
                instance.hardwareConfig().diskCapacityBytes(),
                instance.hostname(),
                seedInput,
                UmlInstanceRootfsProvisioner.ProvisionMode.REPLACE_EXPLICIT,
                migrationReason,
                kernelPath,
                stubExePath,
                runtimeDir,
                store.stateMonitor(),
                { _, _ -> true },
                { receipt ->
                    controller.recordProvisioning(
                        instanceId,
                        InstanceLifecycle.ProvisioningEvidence(
                            InstanceLifecycle.PROVISIONING_MARKER_VERSION,
                            receipt.rootfsSha256(),
                            receipt.provisionedAtMillis(),
                        ),
                    )
                },
                UmlInstanceRootfsProvisioner.DEFAULT_PROVISION_TIMEOUT_MS,
                1_000L,
                2_000L,
                2_000L,
            )
            return provisioner.provisionWithHeldReservation(request, reservation)
        } catch (failure: UmlInstanceRootfsProvisioner.ProvisionException) {
            if (UmlInstanceRootfsProvisioner.REASON_GUEST_STOP_UNCONFIRMED == failure.reason()) {
                retainReservation = true
            }
            throw failure
        } finally {
            if (!retainReservation) {
                reservationRegistry.release(reservation)
            }
        }
    }

    companion object {
        const val DEFAULT_QA_HOSTNAME: String = "qa-uml-alpine"
    }

    @Throws(IOException::class)
    fun roundTripViaArchive(
        controller: InstanceLifecycle.Controller,
        sourceInstanceId: String,
        targetRootVolume: Path,
        importedInstanceId: String? = null,
    ): InstanceLifecycle.Instance {
        check(isEnabled()) {
            "Isolated QA execution entry is disabled outside QA builds (buildChannel=${BuildConfig.BUILD_CHANNEL})"
        }
        val archiveOut = ByteArrayOutputStream()
        val exported = controller.exportToArchive(sourceInstanceId, archiveOut)
        val restored = controller.importFromArchive(
            ByteArrayInputStream(archiveOut.toByteArray()),
            targetRootVolume,
            importedInstanceId,
        )
        check(restored.executionIdentity() == exported.executionIdentity()) {
            "SAF archive round-trip altered execution identity: expected=${exported.executionIdentity()}, actual=${restored.executionIdentity()}"
        }
        return restored
    }
}
