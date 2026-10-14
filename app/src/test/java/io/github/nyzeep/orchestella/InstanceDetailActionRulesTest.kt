package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule
import io.github.nyzeep.orchestella.runtime.contract.Protocol
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.State
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class InstanceDetailActionRulesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun sshUsesPersistedTcpGuest22MappingInsteadOfInventingAPort() {
        val store = FilePortForwardingStore(temporaryFolder.newFolder().toPath())
        val rules = listOf(rule("udp", Protocol.UDP, 2222, 22),
            rule("web", Protocol.TCP, 8080, 80), rule("ssh", Protocol.TCP, 4022, 22))
        store.saveRules("synthetic-instance", rules)
        assertEquals("ssh -p 4022 dev@127.0.0.1",
            InstanceDetailActionRules.sshCommand("dev", store.loadRules("synthetic-instance")))
    }

    @Test fun missingOrUnrelatedMappingHasNoCopyableSshCommand() {
        assertNull(InstanceDetailActionRules.sshCommand("root", emptyList()))
        assertNull(InstanceDetailActionRules.sshCommand("root", listOf(
            rule("udp", Protocol.UDP, 2222, 22), rule("other", Protocol.TCP, 2222, 2222))))
        assertNull(InstanceDetailActionRules.sshCommand("root;touch-file", listOf(rule("ssh", Protocol.TCP, 4022, 22))))
    }

    @Test fun sshPrefersConfiguredLoopbackAndDoesNotConnectToWildcardAddress() {
        val lan = rule("lan", Protocol.TCP, 5022, 22, "0.0.0.0")
        assertEquals("ssh -p 5022 root@127.0.0.1", InstanceDetailActionRules.sshCommand("root", listOf(lan)))
        val local = rule("local", Protocol.TCP, 6022, 22)
        assertEquals("ssh -p 6022 root@127.0.0.1", InstanceDetailActionRules.sshCommand("root", listOf(lan, local)))
    }

    @Test fun coldBackupRejectsRunningSuspendedProvisioningAndHiddenLiveSessions() {
        for (state in listOf(State.RUNNING, State.SUSPENDED, State.PROVISIONING)) {
            assertFalse(InstanceDetailActionRules.canColdBackup(state, false, false))
        }
        for (state in listOf(State.STOPPED, State.CREATED, State.CRASHED, State.INTERRUPTED)) {
            assertTrue(InstanceDetailActionRules.canColdBackup(state, false, false))
            assertFalse("live registry overrides stale cold metadata",
                InstanceDetailActionRules.canColdBackup(state, true, false))
            assertFalse("launch guard blocks export before registration",
                InstanceDetailActionRules.canColdBackup(state, false, true))
        }
    }

    @Test fun restartWaitsForBothRealSessionRemovalAndStoppedMetadata() {
        val waiting = InstanceDetailActionRules.ExitDecision.WAITING
        assertEquals(waiting, InstanceDetailActionRules.exitDecision(State.STOPPED, true, false, false, false))
        assertEquals(waiting, InstanceDetailActionRules.exitDecision(State.RUNNING, false, false, false, false))
        assertEquals(waiting, InstanceDetailActionRules.exitDecision(State.STOPPED, false, true, false, false))
        assertEquals(InstanceDetailActionRules.ExitDecision.READY,
            InstanceDetailActionRules.exitDecision(State.STOPPED, false, false, false, false))
    }

    @Test fun timeoutOrReplacementCannotTriggerRestart() {
        assertEquals(InstanceDetailActionRules.ExitDecision.TIMED_OUT,
            InstanceDetailActionRules.exitDecision(State.RUNNING, true, false, false, true))
        assertEquals(InstanceDetailActionRules.ExitDecision.TIMED_OUT,
            InstanceDetailActionRules.exitDecision(State.CRASHED, false, false, false, true))
        assertEquals(InstanceDetailActionRules.ExitDecision.REPLACED,
            InstanceDetailActionRules.exitDecision(State.STOPPED, true, false, true, false))
    }

    @Test fun shutdownReusesAuthenticatedSessionClientAndHonorsAcceptance() {
        val session = FakeSession("ORCHESTELLA/OK power\n")
        InstanceDetailActionRules.requestShutdown(session)
        assertEquals(1, session.clientAccesses)
        assertTrue(session.requests.toString("US-ASCII").endsWith("ORCHESTELLA/POWER poweroff\n"))
        assertFalse(session.closed)
        assertFalse(session.terminated)
    }

    @Test fun rejectedDisconnectedOrUnauthenticatedShutdownCannotCloseOrTerminateVm() {
        for (response in listOf("ORCHESTELLA/ERROR unsupported-power\n", "")) {
            val session = FakeSession(response)
            expectIOException { InstanceDetailActionRules.requestShutdown(session) }
            assertFalse(session.closed)
            assertFalse(session.terminated)
            assertEquals(1, session.clientAccesses)
        }
        val unauthenticated = FakeSession("ORCHESTELLA/OK power\n", authenticated = false)
        expectIOException { InstanceDetailActionRules.requestShutdown(unauthenticated) }
        assertEquals(0, unauthenticated.requests.size())
        assertFalse(unauthenticated.closed)
        assertFalse(unauthenticated.terminated)
    }

    private fun rule(id: String, protocol: Protocol, host: Int, guest: Int, bind: String = "127.0.0.1") =
        PortForwardingRule.builder().id(id).protocol(protocol).hostPort(host).guestPort(guest)
            .bindAddress(bind).allowLanExposure(bind != "127.0.0.1").build()

    private fun expectIOException(action: () -> Unit) {
        try {
            action()
            fail("unaccepted shutdown must fail")
        } catch (_: IOException) { }
    }

    private class FakeSession(response: String, authenticated: Boolean = true) : ExecutionSession {
        val requests = ByteArrayOutputStream()
        var clientAccesses = 0
        var closed = false
        var terminated = false
        private val sharedClient = BridgeClient(ByteArrayInputStream(
            ((if (authenticated) "ORCHESTELLA/READY 1\n" else "") + response).toByteArray(Charsets.US_ASCII)), requests)
        init {
            if (authenticated) assertTrue(sharedClient.handshake("0".repeat(64)).accepted())
        }
        override fun bridgeClient(): BridgeClient = sharedClient.also { clientAccesses++ }
        override fun controlInput(): OutputStream = throw AssertionError("must not create a second control reader")
        override fun controlOutput(): InputStream = throw AssertionError("must not create a second control reader")
        override fun terminalInput(): OutputStream = throw AssertionError("terminal is unrelated")
        override fun terminalOutput(): InputStream = throw AssertionError("terminal is unrelated")
        override fun waitFor(): Int = throw AssertionError("only the existing exit watcher waits for the VM")
        override fun close() { closed = true }
        override fun terminate() { terminated = true }
    }
}
