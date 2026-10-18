package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class RuntimeProcessSupervisorTest {

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

        assertEquals("repeated stop keeps the first result", StopResult.UNCONFIRMED, supervisor.stop(20));
        assertEquals(1, process.forces.get());
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
        assertEquals(0, gateway.stops.get());
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
