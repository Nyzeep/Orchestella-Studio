package io.github.nyzeep.orchestella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DW-07: Pure-JVM unit tests for [GuestFeaturesRecoveryPolicy].
 */
class GuestFeaturesRecoveryPolicyTest {

    @Test
    fun needsRecovery_trueForFailedOrRecoveryRequiredOrInterruptedOrBusy() {
        assertTrue(GuestFeaturesRecoveryPolicy.needsRecovery(state = "failed", phase = "install", reason = "apt-failed"))
        assertTrue(GuestFeaturesRecoveryPolicy.needsRecovery(state = "installing", phase = "recovery-required", reason = "none"))
        assertTrue(GuestFeaturesRecoveryPolicy.needsRecovery(state = "planned", phase = "none", reason = "interrupted"))
        assertTrue(GuestFeaturesRecoveryPolicy.needsRecovery(state = "installing", phase = "packages", reason = "busy"))
    }

    @Test
    fun needsRecovery_falseForNormalHealthyStates() {
        assertFalse(GuestFeaturesRecoveryPolicy.needsRecovery(state = "installed", phase = "installed", reason = "none"))
        assertFalse(GuestFeaturesRecoveryPolicy.needsRecovery(state = "ready", phase = "ready", reason = "none"))
        assertFalse(GuestFeaturesRecoveryPolicy.needsRecovery(state = "planned", phase = "none", reason = "none"))
    }

    @Test
    fun installButtonLabel_switchesOnlyWhenRecoveryRequired() {
        assertEquals("清理并重试安装", GuestFeaturesRecoveryPolicy.installButtonLabel(true))
        assertEquals("安装/重试", GuestFeaturesRecoveryPolicy.installButtonLabel(false))
    }

    @Test
    fun formatStatus_appendsRecoveryHintAndPreservesIdentityHint() {
        val (recoveryText, needsRecovery) = GuestFeaturesRecoveryPolicy.formatStatus(
            state = "failed",
            phase = "recovery-required",
            reason = "interrupted",
        )
        assertTrue(needsRecovery)
        assertTrue(recoveryText.contains("状态：failed"))
        assertTrue(recoveryText.contains("阶段：recovery-required"))
        assertTrue(recoveryText.contains("原因：interrupted"))
        assertTrue(recoveryText.contains("清理并重试安装"))

        val (identityText, identityRecovery) = GuestFeaturesRecoveryPolicy.formatStatus(
            state = "failed",
            phase = "identity",
            reason = "ordinary-user-missing",
        )
        assertTrue(identityRecovery)
        assertTrue(identityText.contains("普通 Linux 用户"))
        assertTrue(identityText.contains("清理并重试安装"))

        val (normalText, normalRecovery) = GuestFeaturesRecoveryPolicy.formatStatus(
            state = "ready",
            phase = "ready",
            reason = "none",
        )
        assertFalse(normalRecovery)
        assertFalse(normalText.contains("清理并重试安装"))
        assertEquals("状态：ready\n阶段：ready\n原因：none", normalText)
    }
}
