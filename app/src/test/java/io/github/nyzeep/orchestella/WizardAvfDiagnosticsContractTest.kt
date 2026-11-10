package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.capability.AvfCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.capability.AvfPlatformFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-11 walkthrough W2 contract locks for the step-1 AVF diagnostic
 * block: the mode-facts kv tail (来源/actor/采样/ROM) is concatenated exactly
 * once, the sampling epoch is never shown raw, long permission names carry
 * break opportunities without changing the token, and the block's collapse
 * state machine starts folded with stale-callback-safe generations.
 * Pure JVM — string assembly only, probe/evaluator semantics untouched.
 */
class WizardAvfDiagnosticsContractTest {

    private val epochMillis = 1731300000000L

    /** Real mode-facts object, so the tail comes from the production summary(). */
    private fun modeFacts(): AvfCapabilityEvaluator.AvfModeFacts =
        AvfCapabilityEvaluator.AvfModeFacts(
            AvfCapabilityEvaluator.VmSupport.SUPPORTED,
            AvfCapabilityEvaluator.VmSupport.UNKNOWN,
            "VirtualMachineManager.getCapabilities", "app", epochMillis, "local-binding")

    private fun platformFacts(): AvfPlatformFacts =
        AvfPlatformFacts(
            AvfPlatformFacts.AccessState.PERMISSION_DENIED,
            AvfPlatformFacts.ExemptionState.INSTALLED,
            AvfCapabilityEvaluator.PermissionState.GRANTED,
            AvfCapabilityEvaluator.PermissionState.DENIED,
            modeFacts(),
            true)

    private fun assemble(platformSummary: String?): String = WizardAvfDiagnostics.factsSummary(
        avfFeatureDeclared = true,
        virtualizationProperty = "1",
        platformSummary = platformSummary,
        modeSummary = modeFacts().summary(),
        managePermissionLabel = "已授权",
        customPermissionLabel = "未授权",
        sampledAtFormatter = { millis -> "2024-11-11 06:00（本地时间）" })

    private fun countOf(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    @Test
    fun platformObservationCarriesKvTailExactlyOnceAndDropsRedundantModeLine() {
        val text = assemble(platformFacts().summary())
        for (key in listOf("来源=", "actor=", "采样=", "ROM=")) {
            assertEquals("kv key must appear exactly once: $key", 1, countOf(text, key))
        }
        assertFalse("redundant mode line must be folded into the platform summary",
            text.contains("VM 模式："))
        assertTrue(text.contains("平台访问："))
        assertFalse("no raw epoch may remain", text.contains(epochMillis.toString()))
    }

    @Test
    fun legacyPathWithoutPlatformObservationKeepsModeLineWithSingleTail() {
        val text = assemble(null)
        for (key in listOf("来源=", "actor=", "采样=", "ROM=")) {
            assertEquals("kv key must appear exactly once: $key", 1, countOf(text, key))
        }
        assertTrue(text.contains("VM 模式："))
        assertFalse(text.contains(epochMillis.toString()))
    }

    @Test
    fun samplingEpochIsRewrittenToHumanReadableLocalTime() {
        val formatted = WizardAvfDiagnostics.humanizeSampledAt("a；采样=$epochMillis；b") {
            "formatted(" + it + ")"
        }
        assertEquals("a；采样=formatted($epochMillis)；b", formatted)
        assertFalse(formatted.contains("采样=$epochMillis"))
    }

    @Test
    fun unparseableOrOverflowingSampledTokensAreLeftUnchanged() {
        val overflow = "9".repeat(24)
        val raw = "采样=abc；采样=$overflow"
        assertEquals(raw, WizardAvfDiagnostics.humanizeSampledAt(raw) { "never" })
    }

    @Test
    fun humanizeLeavesTextWithoutSampledTokensByteIdentical() {
        val text = "访问=可用；豁免=已安装；无采样字段"
        assertEquals(text, WizardAvfDiagnostics.humanizeSampledAt(text) { "never" })
    }

    @Test
    fun permissionNamesGetBreakOpportunitiesButKeepExactToken() {
        for (permission in listOf("MANAGE_VIRTUAL_MACHINE", "USE_CUSTOM_VIRTUAL_MACHINE")) {
            val breakable = WizardAvfDiagnostics.breakablePermissionName(permission)
            assertEquals("visible characters must stay the exact permission token",
                permission, breakable.replace(WizardAvfDiagnostics.BREAK_OPPORTUNITY, ""))
            // One break opportunity per underscore boundary, none before the first segment.
            assertEquals(permission.count { it == '_' },
                breakable.count { it == '\u200B' })
            assertTrue(breakable.endsWith("MACHINE"))
        }
    }

    @Test
    fun plainNameWithoutUnderscoresIsReturnedUnchanged() {
        assertEquals("AVF", WizardAvfDiagnostics.breakablePermissionName("AVF"))
    }

    @Test
    fun factsSummaryRendersPermissionRowsInBreakableForm() {
        val text = assemble(platformFacts().summary())
        assertTrue(text.contains(
            WizardAvfDiagnostics.breakablePermissionName("MANAGE_VIRTUAL_MACHINE") + "：已授权"))
        assertTrue(text.contains(
            WizardAvfDiagnostics.breakablePermissionName("USE_CUSTOM_VIRTUAL_MACHINE") + "：未授权"))
    }

    @Test
    fun summaryLineStatesTheConclusionForEveryStatus() {
        val statuses = listOf(
            AvfCapabilityEvaluator.AvfStatus.READY,
            AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
            AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED,
            AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN,
            AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED,
            AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE,
            AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED)
        for (status in statuses) {
            val line = WizardAvfDiagnostics.summaryLine(status)
            assertTrue("summary line must not be blank for $status", line.isNotBlank())
        }
        assertEquals("AVF 尚未授权——向导继续可用 QEMU 路径。",
            WizardAvfDiagnostics.summaryLine(AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION))
        assertTrue("ready line must not claim a QEMU fallback",
            !WizardAvfDiagnostics.summaryLine(AvfCapabilityEvaluator.AvfStatus.READY).contains("QEMU"))
        assertTrue("non-ready lines state the QEMU path",
            WizardAvfDiagnostics.summaryLine(AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED)
                .contains("QEMU"))
    }

    @Test
    fun collapseStartsFoldedAndToggleGenerationsIncreaseMonotonically() {
        var state = WizardAvfDiagnostics.CollapseState()
        assertFalse("W2(d): detail block must default to collapsed", state.expanded)
        assertEquals(0, state.generation)
        for (toggle in 1..5) {
            state = WizardAvfDiagnostics.toggled(state)
            assertEquals("generation must increase by one per toggle", toggle, state.generation)
            assertEquals("expanded flag must alternate", toggle % 2 == 1, state.expanded)
        }
    }

    @Test
    fun expandIndicatorFollowsSectionVocabulary() {
        assertEquals("▾", WizardAvfDiagnostics.expandIndicator(true))
        assertEquals("▸", WizardAvfDiagnostics.expandIndicator(false))
    }
}
