package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.contract.StorageContract
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig
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
 * Isolated QA entry point for explicit execution-strategy metadata creation and
 * SAF archive round-trip verification (W3-00 / R-00).
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
    ): InstanceLifecycle.Instance {
        check(isEnabled()) {
            "Isolated QA execution entry is disabled outside QA builds (buildChannel=${BuildConfig.BUILD_CHANNEL})"
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
        val rootVolume = qaRootsDir.resolve("qa-uml-alpine-${UUID.randomUUID()}.raw")
        if (Files.notExists(rootVolume)) {
            Files.createFile(rootVolume)
        }
        val created = createInstance(
            controller = controller,
            distro = InstanceLifecycle.DistroReference("alpine", "3.22.2"),
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
