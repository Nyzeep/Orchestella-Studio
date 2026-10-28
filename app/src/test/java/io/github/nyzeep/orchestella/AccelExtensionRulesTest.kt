package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM tests for the two-package accelerator decision rules. The Android
 * resolver shell (PM queries, package-context asset reads) is exercised on
 * device; these tests pin the decision surface itself.
 */
class AccelExtensionRulesTest {

    @Test
    fun extensionPackageNameAppendsSuffix() {
        assertEquals(
            "io.github.nyzeep.orchestella.accel",
            AccelExtensionRules.extensionPackageName("io.github.nyzeep.orchestella"),
        )
        assertEquals(
            "io.github.nyzeep.orchestella.qa.dvpvm.accel",
            AccelExtensionRules.extensionPackageName("io.github.nyzeep.orchestella.qa.dvpvm"),
        )
    }

    @Test
    fun extensionPackageNameRejectsBlank() {
        assertThrows(IllegalArgumentException::class.java) {
            AccelExtensionRules.extensionPackageName(" ")
        }
    }

    @Test
    fun missingExtensionIsReportedHonestly() {
        val decision = AccelExtensionRules.evaluate(false, null, 503L, null)
        assertEquals(AccelExtensionRules.Status.EXTENSION_MISSING, decision.status)
        assert(decision.detail.contains("未安装")) { decision.detail }
    }

    @Test
    fun versionMismatchIsRefusedWithBothVersions() {
        val decision = AccelExtensionRules.evaluate(true, 502L, 503L, "clang")
        assertEquals(AccelExtensionRules.Status.VERSION_MISMATCH, decision.status)
        assert(decision.detail.contains("502")) { decision.detail }
        assert(decision.detail.contains("503")) { decision.detail }
    }

    @Test
    fun missingManifestIsRefusedSeparately() {
        val decision = AccelExtensionRules.evaluate(true, 503L, 503L, null)
        assertEquals(AccelExtensionRules.Status.MANIFEST_UNREADABLE, decision.status)
    }

    @Test
    fun fakeEngineIsRefusedFailClosed() {
        val decision = AccelExtensionRules.evaluate(true, 503L, 503L, "fake")
        assertEquals(AccelExtensionRules.Status.ENGINE_REJECTED, decision.status)
        assert(decision.detail.contains("fake")) { decision.detail }
    }

    @Test
    fun emptyEngineIsRefusedFailClosed() {
        // Pre-field manifest: "unknown" is not "clang".
        val decision = AccelExtensionRules.evaluate(true, 503L, 503L, "")
        assertEquals(AccelExtensionRules.Status.ENGINE_REJECTED, decision.status)
    }

    @Test
    fun readyExtensionIsAdmitted() {
        val decision = AccelExtensionRules.evaluate(true, 503L, 503L, "clang")
        assertEquals(AccelExtensionRules.Status.OK, decision.status)
    }
}
