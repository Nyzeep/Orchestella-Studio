package io.github.nyzeep.orchestella

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule
import io.github.nyzeep.orchestella.runtime.contract.Protocol
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle.State
import io.github.nyzeep.orchestella.runtime.network.FilePortForwardingStore
import io.github.nyzeep.orchestella.ui.InstanceDetailViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.time.Clock

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

    @Test fun crashedAndInterruptedInstancesCanPowerOnAndResetSafelyWhenConfirmedStopped() {
        val storeDir = temporaryFolder.newFolder("dw09_instances").toPath()
        val store = InstanceLifecycle.FileStore(storeDir)
        val controller = InstanceLifecycle.Controller(store, Clock.systemUTC())
        val rootVol = temporaryFolder.newFile("dw09-root.raw").toPath()
        val rootBytes = "preserve-user-ext4-root-bytes".toByteArray(Charsets.UTF_8)
        Files.write(rootVol, rootBytes)

        val crashed = controller.create(InstanceLifecycle.DistroReference("alpine", "3.22.2"), rootVol)
        controller.beginProvisioning(crashed.id())
        controller.completeProvisioning(crashed.id())
        controller.start(crashed.id())
        controller.recordCrash(crashed.id(), 139, null, "SIGSEGV")

        val vm = InstanceDetailViewModel(instanceStore = store, controller = controller)
        val crashedUi = requireNotNull(vm.loadInstance(crashed.id()))
        assertEquals(State.CRASHED, crashedUi.state)
        assertTrue("DW-09: CRASHED detail view must allow power on", crashedUi.canPowerOn)
        assertTrue(crashedUi.canResetState)

        val crashedOutcome = OfflineInstanceStopReset.tryResetOffline(
            store = store,
            controller = controller,
            instanceId = crashed.id(),
            hasLiveSession = { false },
            isLaunchInFlight = { false },
            runtimeProbe = { RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED },
        )
        assertEquals(OfflineInstanceStopReset.Outcome.RESET_TO_STOPPED, crashedOutcome)
        assertEquals(State.STOPPED, store.load(crashed.id()).state())
        assertArrayEquals("root volume must never be deleted or modified", rootBytes, Files.readAllBytes(rootVol))

        controller.start(crashed.id())
        controller.reconcileAfterHostDeath(crashed.id(), State.INTERRUPTED)
        assertEquals(State.INTERRUPTED, store.load(crashed.id()).state())

        val interruptedOutcome = OfflineInstanceStopReset.tryResetOffline(
            store = store,
            controller = controller,
            instanceId = crashed.id(),
            hasLiveSession = { false },
            isLaunchInFlight = { false },
            runtimeProbe = { RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED },
        )
        assertEquals(OfflineInstanceStopReset.Outcome.RESET_TO_STOPPED, interruptedOutcome)
        assertEquals(State.STOPPED, store.load(crashed.id()).state())
        assertArrayEquals(rootBytes, Files.readAllBytes(rootVol))
    }

    @Test fun unconfirmedProbeOrActiveSlotRefusesOfflineResetAndPreservesCrashedState() {
        val storeDir = temporaryFolder.newFolder("dw09_unconfirmed").toPath()
        val store = InstanceLifecycle.FileStore(storeDir)
        val controller = InstanceLifecycle.Controller(store, Clock.systemUTC())
        val rootVol = temporaryFolder.newFile("dw09-unconfirmed.raw").toPath()
        val rootBytes = "keep-intact".toByteArray(Charsets.UTF_8)
        Files.write(rootVol, rootBytes)

        val instance = controller.create(InstanceLifecycle.DistroReference("debian", "13"), rootVol)
        controller.beginProvisioning(instance.id())
        controller.completeProvisioning(instance.id())
        controller.start(instance.id())
        controller.recordCrash(instance.id(), 1, null, "exit 1")

        // STOP_UNCONFIRMED from runtime/slot probe must refuse writing STOPPED.
        val unconfirmed = OfflineInstanceStopReset.tryResetOffline(
            store = store,
            controller = controller,
            instanceId = instance.id(),
            hasLiveSession = { false },
            isLaunchInFlight = { false },
            runtimeProbe = { RuntimeProcessSupervisor.StopResult.UNCONFIRMED },
        )
        assertEquals(OfflineInstanceStopReset.Outcome.REJECTED_UNCONFIRMED, unconfirmed)
        assertEquals(State.CRASHED, store.load(instance.id()).state())

        // In-flight launch guard must refuse reset without probing or mutating state.
        val inFlight = OfflineInstanceStopReset.tryResetOffline(
            store = store,
            controller = controller,
            instanceId = instance.id(),
            hasLiveSession = { false },
            isLaunchInFlight = { true },
            runtimeProbe = { throw AssertionError("must not reset while launch is in flight") },
        )
        assertEquals(OfflineInstanceStopReset.Outcome.REJECTED_UNCONFIRMED, inFlight)
        assertEquals(State.CRASHED, store.load(instance.id()).state())
        assertArrayEquals(rootBytes, Files.readAllBytes(rootVol))

        // CREATED / RUNNING without a live session cannot bypass via offline reset.
        val created = controller.create(InstanceLifecycle.DistroReference("alpine", "3.22.2"), rootVol)
        val createdOutcome = OfflineInstanceStopReset.tryResetOffline(
            store = store,
            controller = controller,
            instanceId = created.id(),
            hasLiveSession = { false },
            isLaunchInFlight = { false },
            runtimeProbe = { RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED },
        )
        assertEquals(OfflineInstanceStopReset.Outcome.REJECTED_INVALID_STATE, createdOutcome)
        assertEquals(State.CREATED, store.load(created.id()).state())
    }

    @Test fun staleOwnerFileWithExitedProcessAllowsResetWhileLiveOwnerOrBoundPortBlocks() {
        val privateRoot = temporaryFolder.newFolder("private_root").toPath()
        val instanceId = "inst-crashed-1"
        val derivedPort = io.github.nyzeep.orchestella.runtime.instance.GatewaySlotArbiter.derivedPort(instanceId)
        val slotsDir = privateRoot.resolve("net-slots")
        Files.createDirectories(slotsDir)
        val ownerFile = slotsDir.resolve("$derivedPort.owner")
        Files.write(
            ownerFile,
            "2\n$instanceId\n00000000-0000-0000-0000-000000000001\n4321\n99999\n1:2\n100\n111\n"
                .toByteArray(Charsets.US_ASCII),
        )

        // Bound port always blocks reset even if process probe wasn't consulted.
        assertTrue(
            OfflineInstanceStopReset.isGatewaySlotOccupied(
                privateRoot = privateRoot,
                instanceId = instanceId,
                portBoundProbe = { true },
                processAliveProbe = { _, _ -> false },
            ),
        )
        // Unbound port + live recorded PID blocks reset.
        assertTrue(
            OfflineInstanceStopReset.isGatewaySlotOccupied(
                privateRoot = privateRoot,
                instanceId = instanceId,
                portBoundProbe = { false },
                processAliveProbe = { pid, ticks -> pid == 4321 && ticks == 99999L },
            ),
        )
        // Stale owner left after crash (unbound port + exited PID) must NOT false-positive block reset.
        assertFalse(
            OfflineInstanceStopReset.isGatewaySlotOccupied(
                privateRoot = privateRoot,
                instanceId = instanceId,
                portBoundProbe = { false },
                processAliveProbe = { _, _ -> false },
            ),
        )
        // Malformed owner record for this instance fails closed.
        Files.write(ownerFile, "2\n$instanceId\nbad\n".toByteArray(Charsets.US_ASCII))
        assertTrue(
            OfflineInstanceStopReset.isGatewaySlotOccupied(
                privateRoot = privateRoot,
                instanceId = instanceId,
                portBoundProbe = { false },
                processAliveProbe = { _, _ -> false },
            ),
        )
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
