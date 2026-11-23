package io.github.nyzeep.orchestella.ui.screens.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.PortForwardingPresenter
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule
import io.github.nyzeep.orchestella.runtime.network.PortForwardingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Port-forwarding page contract: the empty-state invariant carried over from
 * the deleted sheet, plus the presenter-to-Compose state mapping.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PortForwardingPageSpecTest {

    @get:Rule val folder = TemporaryFolder()

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
        assertEquals(
            PortForwardingErrorField.HOST,
            PortForwardingPageSpec.errorField("更换宿主端口后才能更新当前监听的 Guest 端口"),
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
        val state = PortForwardingPageState()
        val manager = PortForwardingManager.forInstance("sample", folder.root.toPath())
        val rule = PortForwardingRule.builder()
            .hostPort(8080).guestPort(80).description("Web service").build()
        manager.addRule(rule)
        PortForwardingPresenter("sample", manager).apply {
            attachView(state)
            loadRules()
        }
        val row = state.state.rules.single()
        assertEquals(8080, row.hostPort)
        assertEquals(80, row.guestPort)
        assertEquals("TCP", row.protocol)
        assertFalse(row.enabled)
        assertFalse("saved-only rules cannot be toggled", row.canToggle)

        val engine = object : PortForwardingPresenter.LiveEngine {
            override fun addRule(rule: PortForwardingRule) = Unit
            override fun removeRule(rule: PortForwardingRule) = Unit
            override fun refresh() = Unit
            override fun isApplied(candidate: PortForwardingRule) = candidate == rule
            override fun isRunning() = true
        }
        PortForwardingPresenter("sample", manager, engine).apply {
            attachView(state)
            loadRules()
        }
        assertTrue(state.state.rules.single().canToggle)
        assertTrue(state.state.rules.single().enabled)
        assertTrue(state.state.hasLiveEngine)

        state.editRule(state.state.rules.single())
        assertEquals(rule.id(), state.state.editingRuleId)
        assertEquals("Web service", state.state.description)
        assertEquals("8080", state.state.hostPort)
        assertEquals("80", state.state.guestPort)
        state.clearInputForm()
        assertNull(state.state.editingRuleId)
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

    @Test
    fun generalErrorClearsOnInputChangeAndDisplayRulesAndReservedHostPortRoutesToHost() {
        assertEquals(
            PortForwardingErrorField.HOST,
            PortForwardingPageSpec.errorField("Host port 3632 is reserved for the compiler bridge."),
        )

        val state = PortForwardingPageState()
        state.showValidationError("当前会话只支持本机 TCP 转发")
        assertEquals("当前会话只支持本机 TCP 转发", state.state.generalError)

        state.onHostPortChange("8080")
        assertNull(state.state.generalError)

        state.showValidationError("此规则当前无法编辑")
        assertEquals("此规则当前无法编辑", state.state.generalError)

        state.onGuestPortChange("80")
        assertNull(state.state.generalError)

        state.showValidationError("此规则当前无法编辑")
        state.displayRules(emptyList())
        assertNull(state.state.generalError)
    }
}
