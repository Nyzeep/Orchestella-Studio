package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;

import io.github.nyzeep.orchestella.ProductionStageRecorder.Failure;
import io.github.nyzeep.orchestella.ProductionStageRecorder.Stage;
import io.github.nyzeep.orchestella.ProductionStageRecorder.StopFact;
import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Zero-device wiring contract for the ticket 27 observers on existing routes:
 * the supervisor's independent stop facts, the bridge serving route's gate
 * signals, the AVF failure mapping and the stale-generation cancellation.
 */
public final class LaunchStageWiringTest {

    /** Collects observation calls as "kind:value" strings. */
    private static final class RecordingObserver implements LaunchStageObserver {
        final List<String> calls = new ArrayList<>();
        boolean explode;

        private void record(String entry) {
            calls.add(entry);
            if (explode) throw new IllegalStateException("synthetic observer failure");
        }

        @Override public void onGate(Stage stage) { record("gate:" + stage.wireName()); }

        @Override public void onGateAuthenticated(Stage stage) {
            record("gate-auth:" + stage.wireName());
        }

        @Override public void onFallback(Failure failure) { record("fallback:" + failure.name()); }

        @Override public void onFailure(Failure failure) { record("failure:" + failure.name()); }

        @Override public void onCancel() { record("cancel"); }

        @Override public void onStopFact(StopFact fact) { record("stop-fact:" + fact.wireName()); }

        @Override public void onFinish() { record("finish"); }
    }

    /** Local process fake; dies on the first destroy unless told to ignore it. */
    private static final class FakeProcess implements QemuProcessLauncher.ProcessHandle {
        private final CountDownLatch exited = new CountDownLatch(1);
        private final boolean ignoreDestroy;
        volatile boolean alive = true;

        FakeProcess(boolean ignoreDestroy) { this.ignoreDestroy = ignoreDestroy; }

        @Override public int waitFor() throws InterruptedException {
            exited.await();
            return 1;
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override public void destroy() { if (!ignoreDestroy) die(); }

        @Override public void destroyForcibly() { if (!ignoreDestroy) die(); }

        @Override public boolean isAlive() { return alive; }

        @Override public long pid() { return -1L; }

        private void die() {
            alive = false;
            exited.countDown();
        }
    }

    /** Local gateway fake; stop() always confirms. */
    private static final class FakeGateway implements RuntimeGateway {
        volatile boolean alive = true;

        FakeGateway() { }

        FakeGateway(boolean aliveOnEntry) { this.alive = aliveOnEntry; }

        @Override public boolean isAlive() { return alive; }

        @Override public boolean stop(long graceMs) {
            alive = false;
            return true;
        }
    }

    /**
     * Mirrors the production mapping in
     * {@code QemuInstanceLauncher.Session.SupervisorStopFacts}: the VM fact is
     * recorded from the owner's verified exit, and the resources fact only
     * once channels and gateway both reported release.
     */
    private static RuntimeProcessSupervisor.StopObserver supervisorObserver(
            RecordingObserver observer) {
        return new RuntimeProcessSupervisor.StopObserver() {
            private boolean channelsOk;
            private boolean gatewayOk;

            @Override public void onVmStoppedObserved() {
                observer.onStopFact(StopFact.OWNED_VM_STOPPED);
            }

            @Override public void onChannelsReleased(boolean released) {
                channelsOk = released;
                maybeRecordResources();
            }

            @Override public void onGatewayReleased(boolean released) {
                gatewayOk = released;
                maybeRecordResources();
            }

            private void maybeRecordResources() {
                if (channelsOk && gatewayOk) {
                    observer.onStopFact(StopFact.OWNED_RESOURCES_RELEASED);
                }
            }
        };
    }

    private static List<String> factsOnly(List<String> calls) {
        List<String> facts = new ArrayList<>();
        for (String call : calls) {
            if (call.startsWith("stop-fact:")) facts.add(call);
        }
        return facts;
    }

    // ------------------------------------------------- supervisor stop facts

    @Test(timeout = 5_000)
    public void confirmedStopObservesTheOwnerExitAndResourceReleaseFacts() {
        FakeProcess process = new FakeProcess(false);
        FakeGateway gateway = new FakeGateway();
        RecordingObserver observer = new RecordingObserver();
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(process, gateway,
                () -> { }, supervisorObserver(observer));

        assertEquals(StopResult.CONFIRMED, supervisor.stop(200));
        assertEquals(List.of(
                "stop-fact:owned_vm_stopped",
                "stop-fact:owned_resources_released"), factsOnly(observer.calls));
    }

    @Test(timeout = 5_000)
    public void failedChannelCleanupKeepsTheResourcesFactHonest() {
        FakeProcess process = new FakeProcess(false);
        FakeGateway gateway = new FakeGateway();
        RecordingObserver observer = new RecordingObserver();
        AtomicInteger channelAttempts = new AtomicInteger();
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(process, gateway,
                () -> {
                    channelAttempts.incrementAndGet();
                    throw new IllegalStateException("synthetic channel close failure");
                },
                supervisorObserver(observer));

        assertEquals(StopResult.UNCONFIRMED, supervisor.stop(200));
        assertEquals("the VM stopped but resources are not confirmed released",
                List.of("stop-fact:owned_vm_stopped"), factsOnly(observer.calls));
        assertEquals(1, channelAttempts.get());
    }

    @Test(timeout = 5_000)
    public void alreadyStoppedRuntimeStillObservesVerifiedFacts() {
        FakeProcess process = new FakeProcess(false);
        process.alive = false;
        process.exited.countDown();
        FakeGateway gateway = new FakeGateway(false);
        RecordingObserver observer = new RecordingObserver();
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(process, gateway,
                () -> { }, supervisorObserver(observer));

        assertEquals(StopResult.ALREADY_STOPPED, supervisor.stop(200));
        assertEquals(List.of(
                "stop-fact:owned_vm_stopped",
                "stop-fact:owned_resources_released"), factsOnly(observer.calls));
    }

    @Test(timeout = 5_000)
    public void throwingObserverNeverChangesTheStopOutcome() {
        FakeProcess process = new FakeProcess(false);
        FakeGateway gateway = new FakeGateway();
        RecordingObserver observer = new RecordingObserver();
        observer.explode = true;
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(process, gateway,
                () -> { }, supervisorObserver(observer));

        assertEquals("observation failures must not affect the owner stop",
                StopResult.CONFIRMED, supervisor.stop(200));
        assertEquals("confirmed", supervisor.diagnostics().stage());
    }

    // -------------------------------------------------- bridge serving route

    @Test
    public void bridgeRouteObservesConnectedAuthenticatedAndCommandGates() {
        RecordingObserver observer = new RecordingObserver();
        BridgeConnector.notifyChannelConnected(observer);
        BridgeConnector.notifyAuthenticated(observer, true);
        BridgeConnector.notifyGuestCommand(observer, true);
        assertEquals(List.of(
                "gate:vsock_connected",
                "gate-auth:agent_authenticated",
                "gate-auth:guest_command_ok"), observer.calls);
    }

    @Test
    public void bridgeRejectionsKeepRealFailureReasons() {
        RecordingObserver observer = new RecordingObserver();
        BridgeConnector.notifyAuthenticated(observer, false);
        BridgeConnector.notifyGuestCommand(observer, false);
        assertEquals(List.of(
                "failure:READINESS_FAILED",
                "failure:GUEST_COMMAND_FAILED"), observer.calls);
    }

    @Test
    public void throwingBridgeObserverIsSwallowedAndServingContinues() {
        RecordingObserver observer = new RecordingObserver();
        observer.explode = true;
        BridgeConnector.notifyChannelConnected(observer);
        BridgeConnector.notifyAuthenticated(observer, true);
        BridgeConnector.notifyGuestCommand(observer, false);
        assertEquals(3, observer.calls.size());
    }

    @Test
    public void nullBridgeObserverIsANoOp() {
        BridgeConnector.notifyChannelConnected(null);
        BridgeConnector.notifyAuthenticated(null, false);
        BridgeConnector.notifyGuestCommand(null, true);
    }

    // ----------------------------------------------------- AVF failure mapping

    @Test
    public void avfEngineReasonsMapOntoTheBoundedFailureVocabulary() {
        assertEquals(Failure.PERMISSION_DENIED,
                AvfInstanceLauncher.mapFailure(AvfEngineException.PERMISSION_DENIED));
        assertEquals(Failure.CREATE_FAILED,
                AvfInstanceLauncher.mapFailure(AvfEngineException.VM_CREATE_FAILED));
        assertEquals(Failure.READINESS_FAILED,
                AvfInstanceLauncher.mapFailure(AvfEngineException.VSOCK_CONNECT_FAILED));
        assertEquals(Failure.STOP_FAILED,
                AvfInstanceLauncher.mapFailure(AvfEngineException.VM_STOP_UNCONFIRMED));
        assertEquals(Failure.API_BLOCKED,
                AvfInstanceLauncher.mapFailure(AvfEngineException.HARDWARE_UNSUPPORTED));
        assertEquals(Failure.API_BLOCKED,
                AvfInstanceLauncher.mapFailure(AvfEngineException.VM_MANAGER_UNAVAILABLE));
        assertEquals(Failure.API_BLOCKED,
                AvfInstanceLauncher.mapFailure(AvfEngineException.INVALID_LAUNCH_CONFIG));
        assertEquals("unknown reasons stay honestly unknown",
                Failure.UNKNOWN, AvfInstanceLauncher.mapFailure("SOMETHING_ELSE"));
        assertEquals(Failure.UNKNOWN, AvfInstanceLauncher.mapFailure(null));
    }

    // -------------------------------------------------- stale generation cancel

    @Test
    public void staleExitCancelsTheSupersededTraceInsteadOfCompletingIt() throws Exception {
        RecordingObserver observer = new RecordingObserver();
        PlainTraceSession session = new PlainTraceSession(observer);
        // Not registered in the session registry: generation == null -> STALE.
        InstanceLaunchSequence.ExitResult result = InstanceLaunchSequence.awaitGuestExit(
                newStaleController(), "stage-trace-stale-instance", session, () -> { });
        assertEquals(InstanceLaunchSequence.ExitStatus.STALE, result.status());
        assertEquals(List.of("cancel"), observer.calls);
    }

    private InstanceLifecycle.Controller newStaleController() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("stage-trace-stale");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        return new InstanceLifecycle.Controller(store, java.time.Clock.systemUTC());
    }

    /** Minimal session carrying only a trace observer; never waited on. */
    private static final class PlainTraceSession implements ExecutionSession {
        private final LaunchStageObserver observer;

        PlainTraceSession(LaunchStageObserver observer) { this.observer = observer; }

        @Override public LaunchStageObserver launchStageObserver() { return observer; }

        @Override public int waitFor() { return 0; }

        @Override public java.io.OutputStream terminalInput() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override public java.io.InputStream terminalOutput() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }

        @Override public java.io.OutputStream controlInput() {
            return new java.io.ByteArrayOutputStream();
        }

        @Override public java.io.InputStream controlOutput() {
            return new java.io.ByteArrayInputStream(new byte[0]);
        }

        @Override public void close() { }
    }
}
