package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale

/**
 * Single source of truth projecting distribution download status, input verification,
 * provisioning seed readiness, and strategy-scoped creatability (W3-02 / R-02).
 *
 * <p>Avoids silent fallback or divergent hardcoded file names: every entry reads directly
 * from [DistroCatalogRegistry], [UmlKernelAssets], [UmlRootfsProvisioner], and
 * [UmlDistroCapabilityCatalog].
 */
object DistroCreatabilityProjection {
    enum class VerificationState {
        DISABLED,
        NOT_STAGED,
        SIZE_MISMATCH,
        CHECKSUM_MISMATCH,
        UNEXTRACTED,
        VERIFIED_READY,
    }

    data class CreatableDistroStatus(
        val distroId: String,
        val version: String,
        val strategy: FullLinuxContract.ExecutionStrategy,
        val rootfsFileName: String,
        val expectedSha256: String,
        val expectedSizeBytes: Long,
        val verificationState: VerificationState,
        val creatable: Boolean,
        val missingRequirements: List<UmlDistroCapabilityCatalog.KernelRequirementGap>,
        val refusalReason: String?,
    )

    @JvmStatic
    @JvmOverloads
    fun project(
        strategy: FullLinuxContract.ExecutionStrategy,
        filesDir: Path,
        isolatedQaEnabled: Boolean = BuildConfig.IS_ISOLATED_QA_BUILD,
        umlRootfsDownloadEnabled: Boolean = BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED,
        catalog: DistroCatalogRegistry = DistroCatalogRegistry.getInstance(),
        kernelProfile: UmlDistroCapabilityCatalog.KernelProfile =
            UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64,
    ): List<CreatableDistroStatus> {
        if (strategy == FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL) {
            return projectForUml(filesDir, isolatedQaEnabled, umlRootfsDownloadEnabled, kernelProfile)
        }
        return catalog.listAll().map { manifest ->
            val inputPath = filesDir.resolve("distro-inputs").resolve(manifest.rootfsFileName())
            val verification = verifyInputFile(inputPath, manifest.sizeBytes(), manifest.sha256())
            CreatableDistroStatus(
                distroId = manifest.id(),
                version = manifest.version(),
                strategy = strategy,
                rootfsFileName = manifest.rootfsFileName(),
                expectedSha256 = manifest.sha256(),
                expectedSizeBytes = manifest.sizeBytes(),
                verificationState = verification,
                creatable = true,
                missingRequirements = emptyList(),
                refusalReason = null,
            )
        }
    }

    @JvmStatic
    @JvmOverloads
    fun evaluateForStrategy(
        strategy: FullLinuxContract.ExecutionStrategy,
        distroId: String,
        filesDir: Path,
        isolatedQaEnabled: Boolean = BuildConfig.IS_ISOLATED_QA_BUILD,
        umlRootfsDownloadEnabled: Boolean = BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED,
        catalog: DistroCatalogRegistry = DistroCatalogRegistry.getInstance(),
        kernelProfile: UmlDistroCapabilityCatalog.KernelProfile =
            UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64,
    ): CreatableDistroStatus {
        val normalized = distroId.trim().lowercase(Locale.ROOT)
        val existing = project(
            strategy = strategy,
            filesDir = filesDir,
            isolatedQaEnabled = isolatedQaEnabled,
            umlRootfsDownloadEnabled = umlRootfsDownloadEnabled,
            catalog = catalog,
            kernelProfile = kernelProfile,
        ).firstOrNull { it.distroId == normalized }
        if (existing != null) {
            return existing
        }
        if (strategy != FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL) {
            return CreatableDistroStatus(
                distroId = normalized,
                version = "unknown",
                strategy = strategy,
                rootfsFileName = "",
                expectedSha256 = "",
                expectedSizeBytes = -1L,
                verificationState = VerificationState.NOT_STAGED,
                creatable = false,
                missingRequirements = emptyList(),
                refusalReason = "Distro '$normalized' is not registered in DistroCatalogRegistry for strategy $strategy.",
            )
        }
        val catalogManifest = catalog.get(normalized)
        val umlVerdict = UmlDistroCapabilityCatalog.evaluate(
            normalized,
            kernelProfile,
        )
        val verificationState = if (catalogManifest != null) {
            verifyInputFile(
                filesDir.resolve("distro-inputs").resolve(catalogManifest.rootfsFileName()),
                catalogManifest.sizeBytes(),
                catalogManifest.sha256(),
            )
        } else {
            VerificationState.NOT_STAGED
        }
        val creatable = umlVerdict.isCreatable() && isolatedQaEnabled
        val refusalReason = when {
            !umlVerdict.isCreatable() -> umlVerdict.summary()
            !isolatedQaEnabled -> "USER_MODE_KERNEL creation is gated to isolated QA builds."
            else -> null
        }
        return CreatableDistroStatus(
            distroId = umlVerdict.distroId(),
            version = catalogManifest?.version() ?: umlVerdict.version(),
            strategy = strategy,
            rootfsFileName = catalogManifest?.rootfsFileName() ?: umlVerdict.expectedFileName().orEmpty(),
            expectedSha256 = catalogManifest?.sha256() ?: umlVerdict.expectedSha256().orEmpty(),
            expectedSizeBytes = catalogManifest?.sizeBytes() ?: umlVerdict.expectedSizeBytes(),
            verificationState = verificationState,
            creatable = creatable,
            missingRequirements = umlVerdict.missingRequirements(),
            refusalReason = refusalReason,
        )
    }

    private fun projectForUml(
        filesDir: Path,
        isolatedQaEnabled: Boolean,
        umlRootfsDownloadEnabled: Boolean,
        kernelProfile: UmlDistroCapabilityCatalog.KernelProfile,
    ): List<CreatableDistroStatus> {
        val alpineEntry = UmlKernelAssets.RootfsDownload.Entry.alpineMinirootfsAarch64()
        val alpineTar = filesDir
            .resolve(UmlRootfsProvisioner.UML_ROOTFS_DIR_NAME)
            .resolve(alpineEntry.fileName())
        val alpineVerification = if (!umlRootfsDownloadEnabled) {
            VerificationState.DISABLED
        } else {
            val readiness = try {
                UmlRootfsProvisioner.readiness(alpineEntry, filesDir, true)
            } catch (_: IOException) {
                UmlRootfsProvisioner.ReadinessReport.notReady()
            }
            if (readiness.ready()) {
                VerificationState.VERIFIED_READY
            } else {
                val fileCheck = verifyInputFile(
                    alpineTar,
                    alpineEntry.sizeBytes(),
                    alpineEntry.sha256Pin(),
                )
                if (fileCheck == VerificationState.VERIFIED_READY) {
                    VerificationState.UNEXTRACTED
                } else {
                    fileCheck
                }
            }
        }

        return UmlDistroCapabilityCatalog
            .listKnownForProfile(kernelProfile)
            .map { verdict ->
                val isAlpine = verdict.distroId() == "alpine"
                val fileName = if (isAlpine) alpineEntry.fileName() else (verdict.expectedFileName() ?: "")
                val expectedSha = if (isAlpine) alpineEntry.sha256Pin() else (verdict.expectedSha256() ?: "")
                val expectedSize = if (isAlpine) alpineEntry.sizeBytes() else verdict.expectedSizeBytes()
                val verificationState = if (isAlpine) {
                    alpineVerification
                } else {
                    try {
                        when (UmlKernelAssets.probeStagedDistroSeed(filesDir, verdict).status()) {
                            UmlKernelAssets.StagedSeedStatus.VERIFIED -> VerificationState.VERIFIED_READY
                            UmlKernelAssets.StagedSeedStatus.SIZE_MISMATCH -> VerificationState.SIZE_MISMATCH
                            UmlKernelAssets.StagedSeedStatus.CHECKSUM_MISMATCH -> VerificationState.CHECKSUM_MISMATCH
                            UmlKernelAssets.StagedSeedStatus.NOT_STAGED -> VerificationState.NOT_STAGED
                        }
                    } catch (_: IOException) {
                        VerificationState.NOT_STAGED
                    }
                }
                val creatable = verdict.isCreatable()
                    && isolatedQaEnabled
                    && (!isAlpine || umlRootfsDownloadEnabled)
                val refusalReason = when {
                    !verdict.isCreatable() -> verdict.summary()
                    !isolatedQaEnabled -> "USER_MODE_KERNEL creation is gated to isolated QA builds."
                    isAlpine && !umlRootfsDownloadEnabled -> "UML rootfs provisioning is disabled by build flag."
                    else -> null
                }
                CreatableDistroStatus(
                    distroId = verdict.distroId(),
                    version = verdict.version(),
                    strategy = FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                    rootfsFileName = fileName,
                    expectedSha256 = expectedSha,
                    expectedSizeBytes = expectedSize,
                    verificationState = verificationState,
                    creatable = creatable,
                    missingRequirements = verdict.missingRequirements(),
                    refusalReason = refusalReason,
                )
            }
    }

    private fun verifyInputFile(
        file: Path,
        expectedSizeBytes: Long,
        expectedSha256: String,
    ): VerificationState {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return VerificationState.NOT_STAGED
        }
        return try {
            if (expectedSizeBytes > 0L && Files.size(file) != expectedSizeBytes) {
                return VerificationState.SIZE_MISMATCH
            }
            val actualSha = UmlKernelAssets.sha256Hex(file.toFile())
            if (actualSha.equals(expectedSha256, ignoreCase = true)) {
                VerificationState.VERIFIED_READY
            } else {
                VerificationState.CHECKSUM_MISMATCH
            }
        } catch (_: IOException) {
            VerificationState.NOT_STAGED
        }
    }
}
