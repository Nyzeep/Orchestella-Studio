package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudgetLedger;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEvent;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectCode;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectedException;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRequest;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStartException;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStarter;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneState;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** AX-01: dual-lane admission, start/stop ordering, crash and idempotency. */
public final class DualLaneCoordinatorTest {
    private static final LaneBudget BUDGET = new LaneBudget(8, 8192L, 128L, true);
    private static final LaneRequest QEMU_REQUEST = new LaneRequest(LaneId.QEMU, 4, 4096L, 32L);
    private static final LaneRequest ACCEL_REQUEST = new LaneRequest(LaneId.ACCEL, 2, 2048L, 16L);

    @Test
    public void startIssuesBothLaneStartsBeforeAnyReadinessWait() {
        Fixture fixture = new Fixture();
        fixture.start();

        assertEquals(1, fixture.qemuStarts);
        assertEquals(1, fixture.accelStarts);
        int qemuStart = fixture.calls.indexOf("start_QEMU");
        int accelStart = fixture.calls.indexOf("start_ACCEL");
        assertTrue(qemuStart >= 0 && accelStart >= 0);
        assertTrue(qemuStart < fixture.calls.indexOf("ready_QEMU"));
        assertTrue(accelStart < fixture.calls.indexOf("ready_QEMU"));
    }

    @Test
    public void qemuReadyEmitsLaneReady() {
        Fixture fixture = new Fixture();
        fixture.start();

        assertEquals(LaneState.READY, fixture.coordinator.state(LaneId.QEMU));
        assertTrue(fixture.hasEvent(LaneEvent.LANE_READY, LaneId.QEMU));
    }

    @Test
    public void acceleratorReadyIsObservedWithDeadline() {
        Fixture fixture = new Fixture();
        fixture.accel.ready = true;
        fixture.start();

        assertTrue(fixture.coordinator.awaitAcceleratorReady(1_000L));
        assertEquals(LaneState.READY, fixture.coordinator.state(LaneId.ACCEL));
        assertTrue(fixture.hasEvent(LaneEvent.LANE_READY, LaneId.ACCEL));
    }

    @Test
    public void acceleratorTimeoutFallsBackWithoutTouchingQemu() {
        Fixture fixture = new Fixture();
        fixture.accel.ready = false;
        fixture.start();

        assertFalse(fixture.coordinator.awaitAcceleratorReady(500L));
        assertEquals(LaneState.STARTING, fixture.coordinator.state(LaneId.ACCEL));
        assertEquals(LaneState.READY, fixture.coordinator.state(LaneId.QEMU));
        assertEquals(0, fixture.qemu.stopCalls);
    }

    @Test
    public void stopIsQemuFirstThenAccel() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.stop();

        assertEquals(List.of(LaneId.QEMU, LaneId.ACCEL), fixture.stopOrder);
    }

    @Test
    public void qemuUnconfirmedStillStopsAccelAndKeepsItsLease() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.qemu.stopOutcome = LaneStopOutcome.UNCONFIRMED;

        DualLaneCoordinator.StopReport report = fixture.stop();

        assertEquals(LaneStopOutcome.UNCONFIRMED, report.qemu());
        assertEquals(LaneStopOutcome.CONFIRMED, report.accel());
        assertTrue(fixture.coordinator.hasLease(LaneId.QEMU));
        assertFalse(fixture.coordinator.hasLease(LaneId.ACCEL));
        assertEquals(LaneState.UNCONFIRMED, fixture.coordinator.state(LaneId.QEMU));
    }

    @Test
    public void accelCrashMarksLostAndLeavesQemuReady() {
        Fixture fixture = new Fixture();
        fixture.accel.ready = true;
        fixture.start();
        fixture.coordinator.awaitAcceleratorReady(1_000L);
        fixture.accel.alive = false;
        fixture.accel.ready = false;

        assertFalse(fixture.coordinator.awaitAcceleratorReady(10L));
        assertEquals(LaneState.LOST, fixture.coordinator.state(LaneId.ACCEL));
        assertEquals(LaneState.READY, fixture.coordinator.state(LaneId.QEMU));
        assertTrue(fixture.hasEvent(LaneEvent.LANE_LOST, LaneId.ACCEL));
    }

    @Test
    public void qemuCrashDuringStartStopsAccel() {
        Fixture fixture = new Fixture();
        fixture.qemu.alive = false;
        fixture.qemu.ready = false;
        fixture.start();

        assertEquals(LaneState.LOST, fixture.coordinator.state(LaneId.QEMU));
        assertEquals(1, fixture.accel.stopCalls);
        assertTrue(fixture.hasEvent(LaneEvent.LANE_LOST, LaneId.QEMU));
    }

    @Test
    public void acceleratorStartIsIssuedBeforeQemuReadyIsObserved() {
        Fixture fixture = new Fixture();
        fixture.qemu.ready = false;

        fixture.start();

        int accelStart = fixture.calls.indexOf("start_ACCEL");
        int firstQemuReadinessProbe = fixture.calls.indexOf("ready_QEMU");
        assertTrue(accelStart >= 0);
        assertTrue(firstQemuReadinessProbe >= 0);
        assertTrue(accelStart < firstQemuReadinessProbe);
    }

    @Test(timeout = 5_000)
    public void cancelReturnsPromptlyWhileStartWaitsForQemuReadiness() throws Exception {
        Fixture fixture = new Fixture(
                LaneClock.system(), new DualLaneCoordinator.Config(30_000L, 100L));
        fixture.qemu.ready = false;
        fixture.accel.ready = true;
        CountDownLatch bothStarted = new CountDownLatch(2);
        fixture.qemuStarter = () -> {
            fixture.qemuStarts++;
            fixture.calls.add("start_QEMU");
            bothStarted.countDown();
            return fixture.qemu;
        };
        fixture.accelStarter = () -> {
            fixture.accelStarts++;
            fixture.calls.add("start_ACCEL");
            bothStarted.countDown();
            return fixture.accel;
        };
        Thread worker = new Thread(fixture.coordinator::startAll, "startAll-worker");
        worker.setDaemon(true);
        worker.start();
        assertTrue(bothStarted.await(2, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        fixture.coordinator.cancel();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue("cancel blocked for " + elapsedMs + " ms", elapsedMs < 200L);
        worker.join(2_000L);
        assertFalse(worker.isAlive());
        assertEquals(1, fixture.qemu.stopCalls);
        assertEquals(1, fixture.accel.stopCalls);
        assertEquals(0L, fixture.coordinator.ledger().snapshot().ramMiB());
    }

    @Test(timeout = 5_000)
    public void cancelReturnsPromptlyWhileWaitingForAcceleratorReadiness() throws Exception {
        Fixture fixture = new Fixture(
                LaneClock.system(), new DualLaneCoordinator.Config(30_000L, 100L));
        fixture.accel.ready = false;
        fixture.startInBackground();
        assertTrue(fixture.accelStarted.await(2, TimeUnit.SECONDS));

        java.util.concurrent.atomic.AtomicBoolean ready =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        fixture.accel.readyProbe = fixture.awaitProbe::countDown;
        Thread worker = new Thread(() -> ready.set(
                fixture.coordinator.awaitAcceleratorReady(30_000L)), "await-accel-worker");
        worker.setDaemon(true);
        worker.start();
        assertTrue(fixture.awaitProbe.await(2, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        fixture.coordinator.cancel();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue("cancel blocked for " + elapsedMs + " ms", elapsedMs < 200L);
        worker.join(2_000L);
        assertFalse(worker.isAlive());
        assertFalse(ready.get());
        assertEquals(1, fixture.qemu.stopCalls);
        assertEquals(1, fixture.accel.stopCalls);
        assertEquals(0L, fixture.coordinator.ledger().snapshot().ramMiB());
    }

    @Test
    public void repeatedStartIsIdempotent() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.coordinator.startAll();

        assertEquals(1, fixture.qemuStarts);
        assertEquals(1, fixture.accelStarts);
    }

    @Test
    public void repeatedStopIsIdempotent() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.stop();
        DualLaneCoordinator.StopReport second = fixture.stop();

        assertEquals(1, fixture.qemu.stopCalls);
        assertEquals(1, fixture.accel.stopCalls);
        assertEquals(LaneStopOutcome.ALREADY_STOPPED, second.qemu());
        assertEquals(LaneStopOutcome.ALREADY_STOPPED, second.accel());
    }

    @Test
    public void concurrentStopStopsEachLaneOnce() throws Exception {
        Fixture fixture = new Fixture();
        fixture.start();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                go.await();
                fixture.stop();
                return null;
            });
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS));

        assertEquals(1, fixture.qemu.stopCalls);
        assertEquals(1, fixture.accel.stopCalls);
    }

    @Test
    public void cpuBudgetRejectionStartsNothing() {
        Fixture fixture = new Fixture(
                new LaneBudget(4, 8192L, 128L, true),
                new LaneRequest(LaneId.QEMU, 8, 4096L, 32L));

        DualLaneCoordinator.StartReport report = fixture.start();

        assertEquals(LaneRejectCode.CPU_EXCEEDED, report.rejectCode());
        assertEquals(0, fixture.qemuStarts);
        assertEquals(0, fixture.accelStarts);
        assertEquals(LaneState.STOPPED, fixture.coordinator.state(LaneId.QEMU));
        assertTrue(fixture.hasEvent(LaneEvent.BUDGET_REJECTED, LaneId.QEMU));
    }

    @Test
    public void ramBudgetRejectionStartsNothing() {
        Fixture fixture = new Fixture(
                new LaneBudget(8, 4096L, 128L, true),
                new LaneRequest(LaneId.QEMU, 4, 8192L, 32L));

        DualLaneCoordinator.StartReport report = fixture.start();

        assertEquals(LaneRejectCode.RAM_EXCEEDED, report.rejectCode());
        assertEquals(0, fixture.qemuStarts);
    }

    @Test
    public void diskBudgetRejectionStartsNothing() {
        Fixture fixture = new Fixture(
                new LaneBudget(8, 8192L, 16L, true),
                new LaneRequest(LaneId.QEMU, 4, 4096L, 32L));

        DualLaneCoordinator.StartReport report = fixture.start();

        assertEquals(LaneRejectCode.DISK_EXCEEDED, report.rejectCode());
        assertEquals(0, fixture.qemuStarts);
    }

    @Test
    public void acceleratorUnavailableStillStartsWithQemu() {
        Fixture fixture = new Fixture(new LaneBudget(8, 8192L, 128L, false));
        DualLaneCoordinator.StartReport report = fixture.start();

        assertEquals(LaneRejectCode.ACCELERATOR_UNAVAILABLE, report.rejectCode());
        assertEquals(1, fixture.qemuStarts);
        assertEquals(LaneState.READY, fixture.coordinator.state(LaneId.QEMU));
        assertEquals(LaneState.DISABLED, fixture.coordinator.state(LaneId.ACCEL));
        assertTrue(fixture.hasEvent(LaneEvent.BUDGET_REJECTED, LaneId.ACCEL));
    }

    @Test
    public void cancelDuringStartStopsQemuAndSkipsAccel() {
        Fixture fixture = new Fixture();
        fixture.qemuStarter = () -> {
            fixture.qemuStarts++;
            fixture.calls.add("start_QEMU");
            fixture.coordinator.cancel();
            return fixture.qemu;
        };

        fixture.start();

        assertEquals(0, fixture.accelStarts);
        assertEquals(1, fixture.qemu.stopCalls);
        assertEquals(LaneState.STOPPED, fixture.coordinator.state(LaneId.QEMU));
    }

    @Test
    public void qemuStartFailureReleasesBudgetAndSkipsAccel() {
        Fixture fixture = new Fixture();
        fixture.qemuStarter = () -> {
            fixture.qemuStarts++;
            throw new LaneStartException("QEMU_START_FAILED");
        };

        DualLaneCoordinator.StartReport report = fixture.start();

        assertEquals(LaneState.FAILED, report.qemu());
        assertEquals(0, fixture.accelStarts);
        assertEquals(0L, fixture.coordinator.ledger().snapshot().ramMiB());
    }

    @Test
    public void accelStartFailureKeepsQemuReady() {
        Fixture fixture = new Fixture();
        fixture.accelStarter = () -> {
            fixture.accelStarts++;
            throw new LaneStartException("ACCEL_START_FAILED");
        };

        fixture.start();

        assertEquals(LaneState.READY, fixture.coordinator.state(LaneId.QEMU));
        assertEquals(LaneState.LOST, fixture.coordinator.state(LaneId.ACCEL));
        assertTrue(fixture.hasEvent(LaneEvent.LANE_LOST, LaneId.ACCEL));
    }

    @Test
    public void eventsContainOnlyFixedNamesCodesAndDurations() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.stop();

        assertFalse(fixture.events.isEmpty());
        for (LaneEvent event : fixture.events) {
            assertTrue(event.name().matches("[a-z_]+"));
            assertTrue(event.code().matches("[A-Z_]+"));
            assertTrue(event.durationMs() >= 0L);
            assertFalse(event.toString().contains("/"));
            assertFalse(event.toString().contains("token"));
        }
    }

    @Test
    public void poolAdmissionSharesTheSameLedger() {
        Fixture fixture = new Fixture();
        fixture.start();
        ProtectedWarmPool.Admission admission = fixture.coordinator.admission();
        LaneBudgetLedger.Snapshot used = fixture.coordinator.ledger().snapshot();

        long remaining = BUDGET.ramMiB() - used.ramMiB();
        assertTrue(admission.admit(remaining));
        assertFalse(admission.admit(remaining + 1L));
    }

    @Test
    public void unconfirmedStopCanBeConfirmedOnALaterAttempt() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.qemu.stopOutcome = LaneStopOutcome.UNCONFIRMED;
        fixture.stop();
        assertTrue(fixture.coordinator.hasLease(LaneId.QEMU));

        fixture.qemu.stopOutcome = LaneStopOutcome.CONFIRMED;
        DualLaneCoordinator.StopReport report = fixture.stop();

        assertEquals(LaneStopOutcome.CONFIRMED, report.qemu());
        assertFalse(fixture.coordinator.hasLease(LaneId.QEMU));
        assertEquals(0L, fixture.coordinator.ledger().snapshot().ramMiB());
    }

    @Test
    public void invalidRequestIsRejectedAsInvalid() {
        LaneBudgetLedger ledger = new LaneBudgetLedger(BUDGET);
        try {
            ledger.reserve(new LaneRequest(LaneId.QEMU, 0, 4096L, 32L));
            throw new AssertionError("expected rejection");
        } catch (LaneRejectedException expected) {
            assertEquals(LaneRejectCode.INVALID_REQUEST, expected.code());
        }
    }

    @Test
    public void qemuReadyTimeoutLeavesStartingWithoutBlockingAccel() {
        Fixture fixture = new Fixture();
        fixture.qemu.ready = false;
        fixture.accel.ready = false;
        fixture.start();

        assertEquals(LaneState.STARTING, fixture.coordinator.state(LaneId.QEMU));
        assertEquals(1, fixture.accelStarts);
        assertFalse(fixture.coordinator.awaitAcceleratorReady(1_000L));
    }

    @Test
    public void awaitAcceleratorReadyIsFalseAfterAConfirmedStop() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.stop();

        assertFalse(fixture.coordinator.awaitAcceleratorReady(1_000L));
    }

    @Test
    public void stopStillAttemptsAccelWhenQemuStopThrows() {
        Fixture fixture = new Fixture();
        fixture.start();
        fixture.qemu.stopThrows = true;

        DualLaneCoordinator.StopReport report = fixture.stop();

        assertEquals(LaneStopOutcome.UNCONFIRMED, report.qemu());
        assertEquals(LaneStopOutcome.CONFIRMED, report.accel());
        assertEquals(1, fixture.accel.stopCalls);
    }

    private static final class Fixture {
        final LaneClock clock;
        final CountDownLatch accelStarted = new CountDownLatch(1);
        final CountDownLatch awaitProbe = new CountDownLatch(1);
        final List<LaneEvent> events = new ArrayList<>();
        final List<LaneId> stopOrder = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final FakeLane qemu = new FakeLane(LaneId.QEMU, stopOrder, calls);
        final FakeLane accel = new FakeLane(LaneId.ACCEL, stopOrder, calls);
        final DualLaneCoordinator coordinator;
        LaneStarter qemuStarter;
        LaneStarter accelStarter;
        int qemuStarts;
        int accelStarts;

        Fixture() {
            this(BUDGET, QEMU_REQUEST);
        }

        Fixture(LaneBudget budget) {
            this(budget, QEMU_REQUEST);
        }

        Fixture(LaneBudget budget, LaneRequest qemuRequest) {
            this(budget, qemuRequest, new FakeClock(), new DualLaneCoordinator.Config(100L, 100L));
        }

        Fixture(LaneClock clock, DualLaneCoordinator.Config config) {
            this(BUDGET, QEMU_REQUEST, clock, config);
        }

        Fixture(
                LaneBudget budget,
                LaneRequest qemuRequest,
                LaneClock clock,
                DualLaneCoordinator.Config config) {
            this.clock = clock;
            this.coordinator = new DualLaneCoordinator(
                    budget,
                    qemuRequest,
                    ACCEL_REQUEST,
                    () -> qemuStarter.start(),
                    () -> accelStarter.start(),
                    config,
                    clock,
                    events::add);
            this.qemuStarter = this::startQemu;
            this.accelStarter = this::startAccel;
        }

        FakeLane startQemu() {
            qemuStarts++;
            calls.add("start_QEMU");
            return qemu;
        }

        FakeLane startAccel() {
            accelStarts++;
            calls.add("start_ACCEL");
            accelStarted.countDown();
            return accel;
        }

        DualLaneCoordinator.StartReport start() {
            return coordinator.startAll();
        }

        void startInBackground() {
            Thread worker = new Thread(coordinator::startAll, "startAll-background");
            worker.setDaemon(true);
            worker.start();
        }

        DualLaneCoordinator.StopReport stop() {
            return coordinator.stopAll(100L);
        }

        boolean hasEvent(String name, LaneId lane) {
            for (LaneEvent event : events) {
                if (event.name().equals(name) && event.lane() == lane) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class FakeClock implements LaneClock {
        long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(long millis) {
            nanos += Math.max(0L, millis) * 1_000_000L;
        }
    }

    private static final class FakeLane implements LaneRuntime {
        final LaneId laneId;
        final List<LaneId> stopOrder;
        final List<String> calls;
        boolean alive = true;
        boolean ready = true;
        int stopCalls;
        LaneStopOutcome stopOutcome = LaneStopOutcome.CONFIRMED;
        boolean stopThrows;
        volatile Runnable readyProbe = () -> {};

        FakeLane(LaneId laneId, List<LaneId> stopOrder, List<String> calls) {
            this.laneId = laneId;
            this.stopOrder = stopOrder;
            this.calls = calls;
        }

        @Override
        public LaneId laneId() {
            return laneId;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public boolean isReady() {
            calls.add("ready_" + laneId.name());
            readyProbe.run();
            return ready;
        }

        @Override
        public LaneStopOutcome stop(long graceMs) {
            stopCalls++;
            stopOrder.add(laneId);
            if (stopThrows) {
                throw new IllegalStateException("stop failed");
            }
            return stopOutcome;
        }
    }
}
