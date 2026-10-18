package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class RuntimeProcessSupervisorTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(timeout = 5_000)
    public void destroyAndGatewayStopAreConfirmedOnce() {
        FakeProcess process = new FakeProcess(false, false);
        FakeGateway gateway = new FakeGateway(true, true);
        AtomicInteger channels = new AtomicInteger();
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, channels::incrementAndGet);

        assertEquals(StopResult.CONFIRMED, supervisor.stop(200));
        assertEquals(1, process.destroys.get());
        assertEquals(0, process.forces.get());
        assertEquals(1, channels.get());
        assertEquals(1, gateway.stops.get());
        assertEquals("confirmed", supervisor.diagnostics().stage());
        assertEquals(1, supervisor.diagnostics().exitCode());
    }

    @Test(timeout = 5_000)
    public void processThatIgnoresDestroyIsForcedAfterGrace() {
        FakeProcess process = new FakeProcess(true, false);
        FakeGateway gateway = new FakeGateway(false, true);
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, () -> { });

        long started = System.nanoTime();
        assertEquals(StopResult.CONFIRMED, supervisor.stop(50));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue("grace must be observed before force", elapsedMs >= 40);
        assertEquals(1, process.destroys.get());
        assertEquals(1, process.forces.get());
        assertTrue(supervisor.diagnostics().forced());
        assertEquals("confirmed", supervisor.diagnostics().stage());
    }

    @Test(timeout = 5_000)
    public void unconfirmedProcessKeepsGatewayAliveAndReturnsStableResult() {
        FakeProcess process = new FakeProcess(true, true);
        FakeGateway gateway = new FakeGateway(true, true);
        AtomicInteger channels = new AtomicInteger();
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, channels::incrementAndGet);

        assertEquals(StopResult.UNCONFIRMED, supervisor.stop(20));
        assertEquals("force-wait", supervisor.diagnostics().stage());
        assertEquals(1, process.forces.get());
        assertEquals("an unconfirmed process must not release the gateway", 0, gateway.stops.get());
        assertEquals("channels are still released to avoid socket leaks", 1, channels.get());

        assertEquals("an unconfirmed stop is retryable and keeps returning UNCONFIRMED",
                StopResult.UNCONFIRMED, supervisor.stop(20));
        assertEquals("the retry attempts another bounded force", 2, process.forces.get());
        assertEquals("the gateway stays reserved while QEMU may be alive", 0, gateway.stops.get());
        assertEquals("channels are closed once", 1, channels.get());
        assertTrue(supervisor.isRuntimeAlive());
    }

    @Test(timeout = 5_000)
    public void alreadyStoppedRuntimeClosesChannelsWithoutDestroying() {
        FakeProcess process = new FakeProcess(false, false);
        process.alive = false;
        process.exited.countDown();
        FakeGateway gateway = new FakeGateway(false, true);
        AtomicInteger channels = new AtomicInteger();
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, channels::incrementAndGet);

        assertEquals(StopResult.ALREADY_STOPPED, supervisor.stop(20));
        assertEquals(0, process.destroys.get());
        assertEquals("a dead gateway is still asked to clean its LIVE entry", 1, gateway.stops.get());
        assertEquals(1, channels.get());
        assertFalse(supervisor.isRuntimeAlive());
    }

    @Test(timeout = 5_000)
    public void concurrentStopRunsOnceAndBothCallersSeeTheSameResult() throws Exception {
        FakeProcess process = new FakeProcess(false, false);
        FakeGateway gateway = new FakeGateway(true, true);
        AtomicInteger channels = new AtomicInteger();
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, channels::incrementAndGet);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<StopResult> first = threads.submit(() -> {
                start.await();
                return supervisor.stop(200);
            });
            Future<StopResult> second = threads.submit(() -> {
                start.await();
                return supervisor.stop(200);
            });
            start.countDown();
            assertEquals(StopResult.CONFIRMED, first.get(4, TimeUnit.SECONDS));
            assertEquals(StopResult.CONFIRMED, second.get(4, TimeUnit.SECONDS));
            assertEquals(1, process.destroys.get());
            assertEquals(1, channels.get());
            assertEquals(1, gateway.stops.get());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test(timeout = 5_000)
    public void gatewayStopFailureIsUnconfirmed() {
        FakeProcess process = new FakeProcess(false, false);
        FakeGateway gateway = new FakeGateway(true, false);
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, () -> { });

        assertEquals(StopResult.UNCONFIRMED, supervisor.stop(100));
        assertEquals("gateway", supervisor.diagnostics().stage());
        assertEquals(1, gateway.stops.get());
    }

    @Test(timeout = 5_000)
    public void unconfirmedStopIsRetryableAfterTheProcessDies() {
        FakeProcess process = new FakeProcess(true, true);
        FakeGateway gateway = new FakeGateway(true, true);
        AtomicInteger channels = new AtomicInteger();
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, channels::incrementAndGet);

        assertEquals(StopResult.UNCONFIRMED, supervisor.stop(20));
        process.die();

        assertEquals("a retry must confirm a process that died after the timeout",
                StopResult.CONFIRMED, supervisor.stop(20));
        assertEquals(1, gateway.stops.get());
        assertEquals(1, channels.get());
    }

    @Test(timeout = 5_000)
    public void alreadyStoppedProcessStillCleansALiveGateway() {
        FakeProcess process = new FakeProcess(false, false);
        process.alive = false;
        process.exited.countDown();
        FakeGateway gateway = new FakeGateway(true, true);
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, () -> { });

        assertEquals("a dead process with a live gateway is a real stop, not ALREADY_STOPPED",
                StopResult.CONFIRMED, supervisor.stop(20));
        assertEquals("a dead process must not leave a live gateway behind", 1, gateway.stops.get());
    }

    @Test(timeout = 5_000)
    public void channelCloseFailureIsUnconfirmed() {
        FakeProcess process = new FakeProcess(false, false);
        FakeGateway gateway = new FakeGateway(false, true);
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, () -> { throw new IllegalStateException("synthetic channel failure"); });

        assertEquals(StopResult.UNCONFIRMED, supervisor.stop(20));
        assertEquals("channels", supervisor.diagnostics().stage());
    }

    @Test(timeout = 5_000)
    public void interruptedGracefulWaitEscalatesAndPreservesTheInterrupt() throws Exception {
        FakeProcess process = new FakeProcess(true, false);
        FakeGateway gateway = new FakeGateway(false, true);
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, () -> { });
        Thread.currentThread().interrupt();
        try {
            assertEquals(StopResult.CONFIRMED, supervisor.stop(0));
            assertTrue("the supervisor must preserve interrupt status",
                    Thread.currentThread().isInterrupted());
            assertEquals(1, process.forces.get());
        } finally {
            Thread.interrupted();
        }
    }

    @Test(timeout = 5_000)
    public void alreadyDeadRuntimeWithGatewayCleanupFailureReportsGatewayStage() {
        FakeProcess process = new FakeProcess(false, false);
        process.alive = false;
        process.exited.countDown();
        FakeGateway gateway = new FakeGateway(false, false);
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, () -> { });

        assertEquals(StopResult.UNCONFIRMED, supervisor.stop(20));
        assertEquals("a successful channel close must not mask the failed gateway cleanup",
                "gateway", supervisor.diagnostics().stage());
        assertEquals("the dead gateway is still asked to clean its LIVE entry", 1, gateway.stops.get());
    }

    @Test(timeout = 5_000)
    public void channelCloseDeadlineBoundsStopAndStillStopsTheGateway() throws Exception {
        FakeProcess process = new FakeProcess(false, false);
        FakeGateway gateway = new FakeGateway(true, true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RuntimeProcessSupervisor supervisor = new RuntimeProcessSupervisor(
                process, gateway, () -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                }, 2000L, 100L);

        long started = System.nanoTime();
        StopResult result = supervisor.stop(50);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue("the cleanup worker must have started", entered.await(1, TimeUnit.SECONDS));
        assertEquals("an unfinished channel close cannot be confirmed", StopResult.UNCONFIRMED, result);
        assertEquals("channels", supervisor.diagnostics().stage());
        assertEquals("the gateway stop must not be skipped when channels time out", 1, gateway.stops.get());
        assertTrue("stop must return within the channels deadline, took " + elapsedMs + " ms",
                elapsedMs < 1500);
        release.countDown();
    }

    @Test(timeout = 20_000)
    public void stopReleasesServiceForwardsLocallyButIndividualCloseStillUnexposes() throws Exception {
        String instanceId = "t4-forwards";
        Process process = TestProcesses.longRunning();
        File apiSocket = temporaryFolder.newFile("t4-forward-api.sock");
        FakeForwardingApi api = new FakeForwardingApi();
        NetworkGatewayDaemon.ForwardingEngine engine =
                new NetworkGatewayDaemon.ForwardingEngine(api, process::isAlive);
        NetworkGatewayDaemon.Running gateway = new NetworkGatewayDaemon.Running(
                "/tmp/net.yaml", 24197, process, instanceId, apiSocket, engine);
        NetworkGatewayDaemon.LIVE.put(instanceId, gateway);
        QemuInstanceLauncher.Session session = new QemuInstanceLauncher.Session(
                "t4-ctl", "t4-term", null, null, null, handleFor(process), instanceId);
        try {
            List<ExecutionSession.ServiceForward> forwards = new ArrayList<>();
            // Ephemeral ports can collide with an already-open forward; retry those instead
            // of weakening the 64-forward window this test is meant to bound.
            for (int attempt = 0; forwards.size() < 64 && attempt < 512; attempt++) {
                try {
                    forwards.add(session.openServiceForward(18080));
                } catch (IOException collision) {
                    // A collision retries through the same production path.
                }
            }
            assertEquals(64, forwards.size());
            assertEquals(64, session.trackedServiceForwardCount());

            int unexposesBeforeIndividualClose = api.unexposes.get();
            forwards.get(0).close();
            assertEquals("closing one forward through the API must still unexpose it",
                    unexposesBeforeIndividualClose + 1, api.unexposes.get());
            assertFalse(forwards.get(0).isActive());
            assertEquals(63, session.trackedServiceForwardCount());

            int unexposesBeforeStop = api.unexposes.get();
            int exposesBeforeStop = api.exposes.get();
            long started = System.nanoTime();
            assertEquals(StopResult.CONFIRMED, session.stopRuntime(200));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertEquals("the stop fast path must release forwards without remote unexpose",
                    unexposesBeforeStop, api.unexposes.get());
            assertEquals(exposesBeforeStop, api.exposes.get());
            assertEquals("every forward must be released from the session", 0,
                    session.trackedServiceForwardCount());
            for (ExecutionSession.ServiceForward forward : forwards) {
                assertFalse(forward.isActive());
            }
            assertTrue("64 forwards must stop inside the bounded window, took " + elapsedMs + " ms",
                    elapsedMs < 3000);
        } finally {
            NetworkGatewayDaemon.LIVE.remove(instanceId, gateway);
            process.destroyForcibly();
        }
    }

    private static QemuProcessLauncher.ProcessHandle handleFor(Process process) {
        return new QemuProcessLauncher.ProcessHandle() {
            @Override public int waitFor() throws InterruptedException { return process.waitFor(); }
            @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                return process.waitFor(timeout, unit);
            }
            @Override public void destroy() { process.destroy(); }
            @Override public void destroyForcibly() { process.destroyForcibly(); }
            @Override public boolean isAlive() { return process.isAlive(); }
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static final class FakeForwardingApi implements NetworkGatewayDaemon.Api {
        final Map<String, String> mappings = new HashMap<>();
        final AtomicInteger exposes = new AtomicInteger();
        final AtomicInteger unexposes = new AtomicInteger();

        @Override public String request(String method, String endpoint, String body) {
            if (method.equals("GET")) {
                StringBuilder json = new StringBuilder("[");
                for (Map.Entry<String, String> entry : mappings.entrySet()) {
                    if (json.length() > 1) json.append(',');
                    json.append("{\"local\":\"").append(entry.getKey()).append("\",\"remote\":\"")
                            .append(entry.getValue()).append("\",\"protocol\":\"tcp\"}");
                }
                return json.append(']').toString();
            }
            String local = body.split("\"local\":\"")[1].split("\"")[0];
            String remote = body.split("\"remote\":\"")[1].split("\"")[0];
            if (endpoint.equals("/services/forwarder/expose")) {
                exposes.incrementAndGet();
                mappings.put(local, remote);
            } else {
                unexposes.incrementAndGet();
                mappings.remove(local);
            }
            return "";
        }
    }

    private static final class FakeProcess implements QemuProcessLauncher.ProcessHandle {
        final AtomicInteger destroys = new AtomicInteger();
        final AtomicInteger forces = new AtomicInteger();
        final CountDownLatch exited = new CountDownLatch(1);
        final boolean ignoreDestroy;
        final boolean ignoreForcible;
        volatile boolean alive = true;

        FakeProcess(boolean ignoreDestroy, boolean ignoreForcible) {
            this.ignoreDestroy = ignoreDestroy;
            this.ignoreForcible = ignoreForcible;
        }

        @Override public int waitFor() throws InterruptedException {
            exited.await();
            return 1;
        }

        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exited.await(timeout, unit);
        }

        @Override public void destroy() {
            destroys.incrementAndGet();
            if (!ignoreDestroy) die();
        }

        @Override public void destroyForcibly() {
            forces.incrementAndGet();
            if (!ignoreForcible) die();
        }

        @Override public boolean isAlive() { return alive; }

        private void die() {
            alive = false;
            exited.countDown();
        }
    }

    private static final class FakeGateway implements RuntimeGateway {
        final AtomicInteger stops = new AtomicInteger();
        final boolean aliveOnEntry;
        final boolean stopResult;
        volatile boolean alive;

        FakeGateway(boolean aliveOnEntry, boolean stopResult) {
            this.aliveOnEntry = aliveOnEntry;
            this.stopResult = stopResult;
            this.alive = aliveOnEntry;
        }

        @Override public boolean isAlive() { return alive; }

        @Override public boolean stop(long graceMs) {
            stops.incrementAndGet();
            if (stopResult) alive = false;
            return stopResult;
        }
    }
}
