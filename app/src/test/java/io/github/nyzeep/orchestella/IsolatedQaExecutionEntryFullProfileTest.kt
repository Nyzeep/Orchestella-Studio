package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog
import io.github.nyzeep.orchestella.runtime.engineuml.UmlKernelPayloadContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IsolatedQaExecutionEntryFullProfileTest {
    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    @Test
    fun selectKernelPayloadRejectsNonQaBuilds() {
        val entry = IsolatedQaExecutionEntry { false }
        val libDir = tmp.newFolder("libs")
        assertThrows(IllegalStateException::class.java) {
            entry.selectKernelPayload(
                UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
                libDir,
                emptyMap<String, String>(),
                emptyMap<String, String>(),
            )
        }
    }

    @Test
    fun selectKernelPayloadSeparatesFullAndFastProfilesExplicitly() {
        val entry = IsolatedQaExecutionEntry { true }
        val libDir = tmp.newFolder("libs")
        val fastKernel = File(libDir, "libuml_kernel.so").apply {
            writeText("fast-k")
            setExecutable(true)
        }
        val fastStub = File(libDir, "libuml_stub.so").apply {
            writeText("fast-s")
            setExecutable(true)
        }
        val fastPins = mapOf(
            "libuml_kernel.so" to UmlKernelAssets.sha256Hex(fastKernel),
            "libuml_stub.so" to UmlKernelAssets.sha256Hex(fastStub),
        )
        val expectedFullPins = mapOf(
            "libuml_kernel_full.so" to "a".repeat(64),
            "libuml_stub_full.so" to "b".repeat(64),
        )

        val mismatch = entry.selectKernelPayload(
            UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64,
            libDir,
            fastPins,
            expectedFullPins,
        )
        assertFalse(mismatch.ok())
        assertEquals(UmlKernelPayloadContract.FailureCode.PROFILE_MISMATCH, mismatch.failureCode())

        val fastOk = entry.selectKernelPayload(
            UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64,
            libDir,
            fastPins,
            fastPins,
        )
        assertTrue(fastOk.ok())
        assertEquals(fastKernel.absolutePath, fastOk.kernelPath())
        assertEquals(fastStub.absolutePath, fastOk.stubPath())
    }
}
