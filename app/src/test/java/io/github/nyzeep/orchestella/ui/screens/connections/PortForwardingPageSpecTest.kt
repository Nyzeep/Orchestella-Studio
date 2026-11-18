package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.PortForwardingPresenter
import io.github.nyzeep.orchestella.runtime.contract.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Port-forwarding page contract: the empty-state invariant carried over from
 * the deleted sheet, plus the presenter-to-Compose state mapping.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PortForwardingPageSpecTest {

    private fun model(
        id: String = "rule-1",
        protocol: Protocol = Protocol.TCP,
        hostPort: Int = 8080,
        guestPort: Int = 80,
        description: String = "Web service",
        enabled: Boolean = true,
    ) = PortForwardingPresenter.RuleItemModel(
        id,
        protocol,
        hostPort,
        guestPort,
        description,
        enabled,
    )

    @Test
    fun emptyStateIsVisibleOnlyWithoutRules() {
        assertTrue(PortForwardingPageSpec.emptyStateVisible(0))
        for (count in 1..64) {
            assertFalse("empty state must collapse for $count rules", PortForwardingPageSpec.emptyStateVisible(count))
        }
    }

    @Test
    fun hostValidationMessagesTargetTheHostField() {
        assertEquals(
            PortForwardingErrorField.HOST,
            PortForwardingPageSpec.errorField("Host port must be between 1024 and 65535."),
        )
        assertEquals(
            PortForwardingErrorField.HOST,
            PortForwardingPageSpec.errorField("Host port 8080/TCP is already mapped by another rule."),
        )
    }

    @Test
    fun guestValidationMessagesTargetTheGuestField() {
        assertEquals(
            PortForwardingErrorField.GUEST,
            PortForwardingPageSpec.errorField("Guest port must be between 1 and 65535."),
        )
    }

    @Test
    fun unrelatedMessagesFallBackToThePageLevel() {
        assertEquals(
            PortForwardingErrorField.GENERAL,
            PortForwardingPageSpec.errorField("当前网关只支持显式 TCP 本机映射"),
        )
        assertEquals(
            PortForwardingErrorField.GENERAL,
            PortForwardingPageSpec.errorField(null),
        )
    }

    @Test
    fun presenterRulesBecomeRowsAndToggleFollowsTheLiveEngine() {
        val state = PortForwardingPageState(hasLiveEngine = false)
        state.displayRules(listOf(model()))
        val row = state.state.rules.single()
        assertEquals("8080", row.hostPort.toString())
        assertEquals("80", row.guestPort.toString())
        assertEquals("TCP", row.protocol)
        assertTrue(row.enabled)
        assertFalse("saved-only rules cannot be toggled", row.canToggle)

        state.setHasLiveEngine(true)
        state.displayRules(listOf(model()))
        assertTrue(state.state.rules.single().canToggle)
    }

    @Test
    fun validationErrorClearsTheOtherFieldAndFormResetClearsEverything() {
        val state = PortForwardingPageState()
        state.showValidationError("Host port cannot be empty.")
        assertEquals("Host port cannot be empty.", state.state.hostPortError)
        assertNull(state.state.guestPortError)

        state.showValidationError("Guest port cannot be empty.")
        assertNull(state.state.hostPortError)
        assertEquals("Guest port cannot be empty.", state.state.guestPortError)

        state.onHostPortChange("8080")
        state.onGuestPortChange("80")
        state.onDescriptionChange("web")
        state.clearInputForm()
        assertEquals("", state.state.hostPort)
        assertEquals("", state.state.guestPort)
        assertEquals("", state.state.description)
        assertNull(state.state.hostPortError)
        assertNull(state.state.guestPortError)
    }
}
