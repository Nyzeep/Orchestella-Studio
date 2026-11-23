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
import java.nio.file.Path
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
