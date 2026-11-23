package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import io.github.nyzeep.orchestella.runtime.distro.DistroCatalogRegistry
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DistroCreatabilityProjectionTest {
    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun umlStrategyAllowsOnlyGatePassingAlpineAndRefusesDebianAndArchWithExactGaps() {
        val filesDir = tempFolder.newFolder("files").toPath()
        val entries = DistroCreatabilityProjection.project(
            strategy = FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            filesDir = filesDir,
            isolatedQaEnabled = true,
            umlRootfsDownloadEnabled = true,
        )

        val alpine = entries.first { it.distroId == "alpine" }
        assertTrue(alpine.creatable)
        assertEquals(UmlKernelAssets.FastProfile.ROOTFS_TAR, alpine.rootfsFileName)
        assertEquals(
            UmlKernelAssets.FastProfile.pins()[UmlKernelAssets.FastProfile.ROOTFS_TAR],
            alpine.expectedSha256,
        )
        assertEquals(UmlKernelAssets.FastProfile.ROOTFS_TAR_SIZE_BYTES, alpine.expectedSizeBytes)
        assertEquals(
            DistroCreatabilityProjection.VerificationState.NOT_STAGED,
            alpine.verificationState,
        )

        val debian = entries.first { it.distroId == "debian" }
        assertFalse(debian.creatable)
        assertEquals("debian-13-rootfs.tar.xz", debian.rootfsFileName)
        assertTrue(
            debian.missingRequirements.contains(
                UmlDistroCapabilityCatalog.KernelRequirementGap.MISSING_CGROUP_PIDS,
            ),
        )
        assertTrue(
            debian.missingRequirements.contains(
                UmlDistroCapabilityCatalog.KernelRequirementGap.MISSING_BUSYBOX_INIT_CONTRACT,
            ),
        )

        val arch = entries.first { it.distroId == "arch" }
        assertFalse(arch.creatable)
        assertEquals("ArchLinuxARM-aarch64-latest.tar.gz", arch.rootfsFileName)
        assertTrue(
            arch.missingRequirements.contains(
                UmlDistroCapabilityCatalog.KernelRequirementGap.MISSING_SECURITY_LANDLOCK,
            ),
        )

        // Non-QA build refuses creation even for Alpine under USER_MODE_KERNEL.
        val nonQaAlpine = DistroCreatabilityProjection.project(
            strategy = FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            filesDir = filesDir,
            isolatedQaEnabled = false,
            umlRootfsDownloadEnabled = true,
        ).first { it.distroId == "alpine" }
        assertFalse(nonQaAlpine.creatable)
    }

    @Test
    fun softwareAndHardwareStrategiesProjectAllSevenCatalogDistrosWithDownloadVerification() {
        val filesDir = tempFolder.newFolder("qemu-files").toPath()
        val inputsDir = Files.createDirectories(filesDir.resolve("distro-inputs"))
        val ubuntuManifest = DistroCatalogRegistry.getInstance().require("ubuntu")
        Files.write(inputsDir.resolve(ubuntuManifest.rootfsFileName()), byteArrayOf(1, 2, 3))

        val entries = DistroCreatabilityProjection.project(
            strategy = FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
            filesDir = filesDir,
            isolatedQaEnabled = false,
            umlRootfsDownloadEnabled = true,
        )

        assertEquals(7, entries.size)
        assertTrue(entries.all { it.creatable })
        val ubuntu = entries.first { it.distroId == "ubuntu" }
        assertEquals(
            DistroCreatabilityProjection.VerificationState.SIZE_MISMATCH,
            ubuntu.verificationState,
        )
        assertEquals(ubuntuManifest.sha256(), ubuntu.expectedSha256)
    }

    @Test
    fun enforcesSingleSourcePinsAndDownloadDisableAndStrategyFallbackBoundaries() {
        val filesDir = tempFolder.newFolder("single-source").toPath()

        // 1. Download flag disabled blocks USER_MODE_KERNEL Alpine creation.
        val downloadDisabled = DistroCreatabilityProjection.evaluateForStrategy(
            strategy = FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            distroId = "alpine",
            filesDir = filesDir,
            isolatedQaEnabled = true,
            umlRootfsDownloadEnabled = false,
        )
        assertFalse(downloadDisabled.creatable)
        assertEquals(
            DistroCreatabilityProjection.VerificationState.DISABLED,
            downloadDisabled.verificationState,
        )
        assertTrue(downloadDisabled.refusalReason?.contains("disabled") == true)

        // 2. Non-UML-catalogued DistroCatalogRegistry distro under USER_MODE_KERNEL uses real manifest metadata.
        val ubuntuManifest = DistroCatalogRegistry.getInstance().require("ubuntu")
        val ubuntuUnderUml = DistroCreatabilityProjection.evaluateForStrategy(
            strategy = FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            distroId = "ubuntu",
            filesDir = filesDir,
            isolatedQaEnabled = true,
            umlRootfsDownloadEnabled = true,
        )
        assertFalse(ubuntuUnderUml.creatable)
        assertEquals(ubuntuManifest.rootfsFileName(), ubuntuUnderUml.rootfsFileName)
        assertEquals(ubuntuManifest.sha256(), ubuntuUnderUml.expectedSha256)
        assertEquals(ubuntuManifest.sizeBytes(), ubuntuUnderUml.expectedSizeBytes)

        // 3. Unregistered Alpine under SOFTWARE_VIRTUALIZATION fails closed without leaking UML summary.
        val alpineUnderQemu = DistroCreatabilityProjection.evaluateForStrategy(
            strategy = FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
            distroId = "alpine",
            filesDir = filesDir,
            isolatedQaEnabled = true,
            umlRootfsDownloadEnabled = true,
        )
        assertFalse(alpineUnderQemu.creatable)
        assertEquals("", alpineUnderQemu.rootfsFileName)
        assertTrue(alpineUnderQemu.refusalReason?.contains("DistroCatalogRegistry") == true)

        // 4. Single-source pin consistency across UmlDistroCapabilityCatalog, UmlKernelAssets, and DistroCatalogRegistry.
        val profile = UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64
        val alpineVerdict = UmlDistroCapabilityCatalog.evaluate("alpine", profile)
        assertEquals(UmlKernelAssets.FastProfile.ROOTFS_TAR, alpineVerdict.expectedFileName())
        assertEquals(
            UmlKernelAssets.FastProfile.pins()[UmlKernelAssets.FastProfile.ROOTFS_TAR],
            alpineVerdict.expectedSha256(),
        )
        assertEquals(UmlKernelAssets.FastProfile.ROOTFS_TAR_SIZE_BYTES, alpineVerdict.expectedSizeBytes())

        for (distroId in listOf("debian", "arch")) {
            val manifest = DistroCatalogRegistry.getInstance().require(distroId)
            val verdict = UmlDistroCapabilityCatalog.evaluate(distroId, profile)
            assertEquals(manifest.rootfsFileName(), verdict.expectedFileName())
            assertEquals(manifest.sha256(), verdict.expectedSha256())
            assertEquals(manifest.sizeBytes(), verdict.expectedSizeBytes())
        }
    }
}
