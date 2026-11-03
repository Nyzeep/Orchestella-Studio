package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.WorkerPool.Admission;
import io.github.nyzeep.orchestella.WorkerPool.Code;
import io.github.nyzeep.orchestella.WorkerPool.HealthProbe;
import io.github.nyzeep.orchestella.WorkerPool.Lease;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneExitInfo;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneHandle;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpec;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneStopResult;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.Fln1Payloads;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.Fln1TerminalCodec;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalFrame;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalFrameType;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalMux;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudgetLedger;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectCode;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Zero-device tests for the PVM-06 worker pool over a fake platform API and a
 * fake clock: capacity 0/1/N, bounded queue, crash rebuild with backoff and
 * cap, cancellation, budget rejection, probe-driven health and the single
 * owner stop. Mirrors the PL-01 ProtectedWarmPoolTest style.
 */
public final class ProtectedWorkerPoolTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long WAIT_MS = 5_000L;

    /** Job identity only; the pool never inspects the payload name. */
    private record TestJob(String name) implements WorkerPool.Job {
    }

    @Test
    public void defaultCapacityIsZeroNeverCreatesAndRefusesDeterministically() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = new ProtectedWorkerPool(api, api::nextSpec,
                ProtectedWorkerPool.Config.defaults(), CLOCK)) {
            WorkerPool.WorkerPoolException failure =
                    assertThrows(WorkerPool.WorkerPoolException.class, () -> pool.acquire(new TestJob("a")));
            assertEquals(Code.DISABLED, failure.code());
            assertNull(failure.reason());
            assertEquals(0, api.startCount);
            assertEquals(0, pool.stats().queueDepth());
            assertEquals(0, pool.stats().enqueuedTotal());
            WorkerPool.HealthReport health = pool.health();
            assertEquals(0, health.capacity());
            assertEquals(0, health.slots().size());
        }
    }

    @Test
    public void capacityOneServesAndReusesTheWarmInstance() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1))) {
            TestJob job = new TestJob("first");
            Lease first = pool.acquire(job);
            assertEquals(job, first.job());
            String id = first.session().instanceId();
            assertTrue(pool.release(first));
            Lease second = pool.acquire(new TestJob("second"));
            assertEquals(id, second.session().instanceId());
            assertEquals(1, api.startCount);
            assertEquals(1, pool.stats().coldCreates());
            assertEquals(1, pool.stats().reuses());
            assertEquals(2, pool.stats().dispatchedTotal());
            assertTrue(pool.release(second));
        }
    }

    @Test
    public void capacityNServesConcurrentJobsAndCreatesLazilyUpToN() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(3))) {
            List<Lease> leases = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                leases.add(pool.acquire(new TestJob("job-" + i)));
            }
            assertEquals(3, api.startCount);
            assertEquals(3, pool.stats().activeInstances());
            assertEquals(3, pool.stats().leasedSlots());
            List<String> ids = new ArrayList<>();
            for (Lease lease : leases) {
                ids.add(lease.session().instanceId());
                assertTrue(pool.release(lease));
            }
            assertEquals(3, ids.stream().distinct().count());
            assertEquals(3, pool.stats().coldCreates());
        }
    }

    @Test
    public void queueFullIsRejectedWithPoolSaturated() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1))) {
            Lease held = pool.acquire(new TestJob("held"));
            Holder first = acquireAndHold(pool, new TestJob("q1"));
            assertTrue(awaitQueueDepth(pool, 1));
            Holder second = acquireAndHold(pool, new TestJob("q2"));
            assertTrue(awaitQueueDepth(pool, 2));
            // Default queue bound is 2 x N = 2; the third job never enters.
            WorkerPool.WorkerPoolException failure = assertThrows(
                    WorkerPool.WorkerPoolException.class,
                    () -> pool.acquire(new TestJob("q3"), WAIT_MS));
            assertEquals(Code.POOL_SATURATED, failure.code());
            assertEquals(1, pool.stats().saturatedTotal());
            assertEquals(2, pool.stats().queueDepth());

            assertTrue(pool.release(held));
            assertTrue(first.acquired.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(pool.release(first.lease.get()));
            assertTrue(second.acquired.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(pool.release(second.lease.get()));
        }
    }

    @Test
    public void queueWaitTimesOutWithJobTimeout() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1))) {
            Lease held = pool.acquire(new TestJob("held"));
            WorkerPool.WorkerPoolException failure = assertThrows(
                    WorkerPool.WorkerPoolException.class,
                    () -> pool.acquire(new TestJob("slow"), 120L));
            assertEquals(Code.JOB_TIMEOUT, failure.code());
            assertEquals(1, pool.stats().timedOutTotal());
            assertTrue(pool.release(held));
        }
    }

    @Test
    public void cancelledQueuedJobWakesWithCancelled() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1))) {
            Lease held = pool.acquire(new TestJob("held"));
            TestJob job = new TestJob("doomed");
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread waiter = acquireAsync(pool, job, failure, done);
            waiter.start();
            assertTrue(awaitQueueDepth(pool, 1));
            assertTrue(pool.cancel(job));
            assertTrue(done.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertTrue(failure.get() instanceof WorkerPool.WorkerPoolException);
            assertEquals(Code.CANCELLED,
                    ((WorkerPool.WorkerPoolException) failure.get()).code());
            assertFalse(pool.cancel(job));
            assertEquals(1, pool.stats().cancelledTotal());
            assertTrue(pool.release(held));
        }
    }

    @Test
    public void fifoOrderServesTheLongestQueuedJobFirst() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1))) {
            Lease held = pool.acquire(new TestJob("held"));
            List<String> servedOrder = new java.util.concurrent.CopyOnWriteArrayList<>();
            CountDownLatch bothDone = new CountDownLatch(2);
            Thread first = acquireReleaseAsync(pool, new TestJob("A"), servedOrder, bothDone);
            Thread second = acquireReleaseAsync(pool, new TestJob("B"), servedOrder, bothDone);
            first.start();
            assertTrue(awaitQueueDepth(pool, 1));
            second.start();
            assertTrue(awaitQueueDepth(pool, 2));
            assertTrue(pool.release(held));
            assertTrue(bothDone.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertEquals(List.of("A", "B"), servedOrder);
            // Both jobs were served from the one warm instance.
            assertEquals(1, api.startCount);
            assertEquals(2, pool.stats().reuses());
        }
    }

    @Test
    public void crashedInstanceIsEvictedAndRebuiltOnNextAcquire() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1))) {
            Lease lease = pool.acquire(new TestJob("before"));
            String deadId = lease.session().instanceId();
            assertTrue(pool.release(lease));
            api.killRunning();
            Lease next = pool.acquire(new TestJob("after"));
            assertFalse(deadId.equals(next.session().instanceId()));
            assertEquals(2, api.startCount);
            assertEquals(1L, pool.stats().evictReasons().get(Code.PROCESS_DIED).longValue());
            assertTrue(pool.release(next));
        }
    }

    @Test
    public void rebuildFailureBacksOffThenGivesUpAtTheCap() throws Exception {
        FakeApi api = new FakeApi();
        ProtectedWorkerPool.Config config = ProtectedWorkerPool.Config.builder()
                .capacity(1)
                .rebuildFailureCap(3)
                .rebuildBackoffBaseMs(1_000L)
                .build();
        ProtectedWorkerPool pool = new ProtectedWorkerPool(api, api::nextSpec, config, CLOCK);
        Lease lease = pool.acquire(new TestJob("first"));
        api.killRunning();
        api.failCreate = true;
        assertTrue(pool.release(lease));

        assertEquals(Code.CREATE_FAILED, acquireCode(pool, new TestJob("retry-1")));
        assertEquals(1, pool.stats().rebuildFailures());
        // Inside the 1 s backoff window the rebuild is suppressed outright.
        assertEquals(Code.REBUILD_BACKOFF, acquireCode(pool, new TestJob("retry-2")));
        assertEquals(1, api.startCount);

        CLOCK.advanceMillis(1_000L);
        assertEquals(Code.CREATE_FAILED, acquireCode(pool, new TestJob("retry-3")));
        CLOCK.advanceMillis(2_000L);
        assertEquals(Code.CREATE_FAILED, acquireCode(pool, new TestJob("retry-4")));
        assertEquals(3, pool.stats().rebuildFailures());

        // Cap reached: the slot is suspended and no further create is tried.
        assertEquals(Code.REBUILD_GIVE_UP, acquireCode(pool, new TestJob("retry-5")));
        assertEquals(1, api.startCount);
        assertEquals(1, pool.stats().suspensions());
        assertEquals(1, pool.health().suspendedSlots());

        // A deliberate resize(0)+resize(1) re-arms a fresh slot.
        assertTrue(pool.resize(0).accepted());
        assertTrue(pool.resize(1).accepted());
        api.failCreate = false;
        Lease revived = pool.acquire(new TestJob("revived"));
        assertNotNull(revived);
        assertEquals(2, api.startCount);
        assertTrue(pool.release(revived));
        pool.close();
    }

    @Test
    public void budgetRejectionCarriesTheFixedReason() throws Exception {
        FakeApi api = new FakeApi();
        Admission refusing = (totalMemoryMb, totalCpuCores) ->
                Admission.Decision.rejected(LaneRejectCode.RAM_EXCEEDED);
        ProtectedWorkerPool pool = new ProtectedWorkerPool(
                api, api::nextSpec,
                ProtectedWorkerPool.Config.builder().capacity(1).build(),
                CLOCK, refusing);
        WorkerPool.WorkerPoolException failure = assertThrows(
                WorkerPool.WorkerPoolException.class, () -> pool.acquire(new TestJob("a")));
        assertEquals(Code.BUDGET_REJECTED, failure.code());
        assertEquals(LaneRejectCode.RAM_EXCEEDED, failure.reason());
        assertEquals(0, api.startCount);
        pool.close();
    }

    @Test
    public void ledgerBackedAdmissionCountsVcpuAndMemory() throws Exception {
        FakeApi api = new FakeApi();
        // One QEMU lane reservation leaves 1 vCPU; each pool instance costs 1.
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(2, 8192L, 128L, true));
        ledger.reserve(new LaneRequest(LaneId.QEMU, 1, 1024L, 32L));
        try (ProtectedWorkerPool pool = new ProtectedWorkerPool(
                api, api::nextSpec,
                ProtectedWorkerPool.Config.builder().capacity(2)
                        .memoryMbPerInstance(1024L)
                        .cpuPerInstance(1)
                        .build(),
                CLOCK, Admission.ledgerBacked(ledger))) {
            Lease first = pool.acquire(new TestJob("fits"));
            assertNotNull(first);
            assertEquals(1, api.startCount);
            // A second instance would need 2 pool vCPUs on top of QEMU's one.
            WorkerPool.WorkerPoolException failure = assertThrows(
                    WorkerPool.WorkerPoolException.class, () -> pool.acquire(new TestJob("extra")));
            assertEquals(Code.BUDGET_REJECTED, failure.code());
            assertEquals(LaneRejectCode.CPU_EXCEEDED, failure.reason());
            assertEquals(1, api.startCount);
            assertTrue(pool.release(first));
        }
    }

    @Test
    public void resizeIsBoundedByTheBudgetLedger() throws Exception {
        FakeApi api = new FakeApi();
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(4, 1024L, 128L, true));
        try (ProtectedWorkerPool pool = new ProtectedWorkerPool(
                api, api::nextSpec,
                ProtectedWorkerPool.Config.builder()
                        .memoryMbPerInstance(1024L)
                        .build(),
                CLOCK, Admission.ledgerBacked(ledger))) {
            WorkerPool.ResizeResult grown = pool.resize(1);
            assertTrue(grown.accepted());
            assertEquals(1, grown.appliedCapacity());
            // Two instances would need 2048 MiB on a 1024 MiB budget.
            WorkerPool.ResizeResult refused = pool.resize(2);
            assertFalse(refused.accepted());
            assertEquals(LaneRejectCode.RAM_EXCEEDED, refused.rejectReason());
            assertEquals(1, pool.stats().capacity());
            assertEquals(0, api.startCount);
        }
    }

    @Test
    public void unconfirmedStopSuspendsTheSlotAndKeepsTheBudget() throws Exception {
        FakeApi api = new FakeApi();
        api.stopResult = FastLaneStopResult.UNCONFIRMED;
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1))) {
            Lease lease = pool.acquire(new TestJob("first"));
            api.helloFailures = 1;
            assertTrue(pool.release(lease));
            // The reuse HELLO fails; the eviction stop is unconfirmed; the
            // inner pool's one replacement create still succeeds.
            Lease next = pool.acquire(new TestJob("second"));
            assertEquals(2, api.startCount);
            assertEquals(1, api.stopped);
            assertEquals(1L, pool.stats().evictReasons().get(Code.STOP_UNCONFIRMED).longValue());
            assertTrue(pool.release(next));

            // The slot stays suspended: no rebuild on top of an unconfirmed
            // VM, and the unconfirmed instance keeps its budget entry next to
            // the replacement (conservative count).
            WorkerPool.WorkerPoolException failure = assertThrows(
                    WorkerPool.WorkerPoolException.class, () -> pool.acquire(new TestJob("third")));
            assertEquals(Code.REBUILD_GIVE_UP, failure.code());
            assertEquals(2, api.startCount);
            assertEquals(2, pool.stats().activeInstances());
            assertEquals(1, pool.health().suspendedSlots());
        }
    }

    @Test
    public void shutdownStopsEachInstanceExactlyOnceThroughTheOwner() throws Exception {
        FakeApi api = new FakeApi();
        ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(2));
        Lease lease = pool.acquire(new TestJob("held"));
        assertTrue(pool.release(lease));
        assertEquals(WorkerPool.ShutdownResult.DRAINED, pool.shutdown(500L));
        assertEquals(1, api.startCount);
        assertEquals(1, api.stopped);
        assertEquals(Integer.valueOf(1), api.stopCallsPerInstance.get(0));
        WorkerPool.WorkerPoolException failure = assertThrows(
                WorkerPool.WorkerPoolException.class, () -> pool.acquire(new TestJob("late")));
        assertEquals(Code.CLOSED, failure.code());
    }

    @Test
    public void shutdownWithOutstandingLeaseIsBoundedAndReleasesAreNoOps() throws Exception {
        FakeApi api = new FakeApi();
        ProtectedWorkerPool pool = pool(api,
                ProtectedWorkerPool.Config.builder().capacity(1).drainTimeoutMs(50L));
        Lease lease = pool.acquire(new TestJob("held"));
        assertEquals(WorkerPool.ShutdownResult.TIMED_OUT, pool.shutdown(50L));
        assertTrue(api.stopped >= 1);
        assertFalse(pool.release(lease));
    }

    @Test
    public void closeCancelsQueuedJobsAndReportsUnconfirmedStops() throws Exception {
        FakeApi api = new FakeApi();
        api.stopResult = FastLaneStopResult.UNCONFIRMED;
        ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1));
        Lease held = pool.acquire(new TestJob("held"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread waiter = acquireAsync(pool, new TestJob("queued"), failure, done);
        waiter.start();
        assertTrue(awaitQueueDepth(pool, 1));
        assertEquals(WorkerPool.ShutdownResult.STOP_UNCONFIRMED, pool.shutdown(50L));
        assertTrue(done.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue(failure.get() instanceof WorkerPool.WorkerPoolException);
        assertEquals(Code.CLOSED, ((WorkerPool.WorkerPoolException) failure.get()).code());
        assertTrue(api.stopped >= 1);
        assertFalse(pool.release(held));
        pool.close();
    }

    @Test
    public void healthProbeFailureTakesInstanceOfflineAndRebuilds() throws Exception {
        FakeApi api = new FakeApi();
        AtomicReference<Boolean> verdict = new AtomicReference<>(Boolean.TRUE);
        HealthProbe probe = session -> verdict.get();
        ProtectedWorkerPool.Config config = ProtectedWorkerPool.Config.builder()
                .capacity(1)
                .rebuildBackoffBaseMs(0L)
                .build();
        try (ProtectedWorkerPool pool = new ProtectedWorkerPool(
                api, api::nextSpec, config, CLOCK, Admission.allowAll(), probe)) {
            Lease lease = pool.acquire(new TestJob("warm"));
            assertTrue(pool.release(lease));

            WorkerPool.HealthReport healthy = pool.health();
            assertEquals(1, healthy.warmSlots());
            assertEquals(Boolean.TRUE, healthy.slots().get(0).alive());
            assertEquals(0, pool.stats().probeFailures());

            verdict.set(Boolean.FALSE);
            WorkerPool.HealthReport sick = pool.health();
            assertEquals(0, sick.warmSlots());
            assertEquals(Boolean.FALSE, sick.slots().get(0).alive());
            assertEquals(1, pool.stats().probeFailures());
            assertEquals(1L, pool.stats().evictReasons().get(Code.PROBE_FAILED).longValue());

            Lease rebuilt = pool.acquire(new TestJob("rebuilt"));
            assertEquals(2, api.startCount);
            assertTrue(pool.release(rebuilt));
        }
    }

    @Test
    public void repeatedProbeFailuresSuspendTheSlotAtTheCap() throws Exception {
        FakeApi api = new FakeApi();
        HealthProbe probe = session -> false;
        ProtectedWorkerPool.Config config = ProtectedWorkerPool.Config.builder()
                .capacity(1)
                .rebuildFailureCap(3)
                .rebuildBackoffBaseMs(0L)
                .build();
        ProtectedWorkerPool pool = new ProtectedWorkerPool(
                api, api::nextSpec, config, CLOCK, Admission.allowAll(), probe);
        // Round 1: warm, probed sick, rebuilt.
        assertTrue(pool.release(pool.acquire(new TestJob("r1-warm"))));
        pool.health();
        assertTrue(pool.release(pool.acquire(new TestJob("r1-rebuild"))));
        // Round 2: warm again, probed sick, rebuilt.
        assertTrue(pool.release(pool.acquire(new TestJob("r2-warm"))));
        pool.health();
        assertTrue(pool.release(pool.acquire(new TestJob("r2-rebuild"))));
        // Round 3: probed sick a third time -> cap reached, slot suspended.
        assertTrue(pool.release(pool.acquire(new TestJob("r3-warm"))));
        pool.health();
        assertEquals(Code.REBUILD_GIVE_UP, acquireCode(pool, new TestJob("r3-dead")));
        assertEquals(1, pool.health().suspendedSlots());
        assertEquals(3, pool.stats().probeFailures());
        assertEquals(3, pool.stats().coldCreates());
        pool.close();
    }

    @Test
    public void matchHostTopologyIsAnExperimentalOptIn() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> ProtectedWorkerPool.Config.builder()
                        .topology(FastLaneLaunchSpec.CpuTopology.MATCH_HOST).build());
        FakeApi api = new FakeApi();
        ProtectedWorkerPool.Config config = ProtectedWorkerPool.Config.builder()
                .capacity(1)
                .topology(FastLaneLaunchSpec.CpuTopology.MATCH_HOST)
                .allowMatchHost(true)
                .build();
        try (ProtectedWorkerPool pool = new ProtectedWorkerPool(api, api::nextSpec, config, CLOCK)) {
            Lease lease = pool.acquire(new TestJob("a"));
            assertEquals(FastLaneLaunchSpec.CpuTopology.MATCH_HOST, api.lastTopology);
            assertTrue(pool.release(lease));
        }
    }

    @Test
    public void defaultTopologyIsOneCpu() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1))) {
            Lease lease = pool.acquire(new TestJob("a"));
            assertEquals(FastLaneLaunchSpec.CpuTopology.ONE_CPU, api.lastTopology);
            assertEquals(FastLaneLaunchSpec.CpuTopology.ONE_CPU, lease.session().spec().cpuTopology());
            assertTrue(pool.release(lease));
        }
    }

    @Test
    public void statsCarryOnlyCountersAndFixedCodes() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWorkerPool pool = pool(api, ProtectedWorkerPool.Config.builder().capacity(1))) {
            Lease lease = pool.acquire(new TestJob("a"));
            assertTrue(pool.release(lease));
            Lease reused = pool.acquire(new TestJob("a2"));
            assertTrue(pool.release(reused));
            api.killRunning();
            Lease next = pool.acquire(new TestJob("b"));
            assertTrue(pool.release(next));
            WorkerPool.Stats stats = pool.stats();
            assertTrue(stats.queueWaitMillisTotal() >= 0);
            assertTrue(stats.queueWaitMillisMax() >= 0);
            assertEquals(2, stats.coldCreates());
            assertEquals(1, stats.reuses());
            assertEquals(1L, stats.evictReasons().get(Code.PROCESS_DIED).longValue());
            for (Code reason : stats.evictReasons().keySet()) {
                assertTrue("unexpected reason " + reason,
                        reason == Code.TTL_EXPIRED || reason == Code.PROCESS_DIED
                                || reason == Code.HELLO_FAILED
                                || reason == Code.PROBE_FAILED
                                || reason == Code.DRAIN_TIMEOUT
                                || reason == Code.STOP_UNCONFIRMED);
            }
        }
    }

    private static Code acquireCode(ProtectedWorkerPool pool, WorkerPool.Job job) {
        try {
            pool.acquire(job);
            throw new AssertionError("expected a WorkerPoolException for " + job);
        } catch (WorkerPool.WorkerPoolException expected) {
            return expected.code();
        }
    }

    private static ProtectedWorkerPool pool(FakeApi api, ProtectedWorkerPool.Config.Builder builder) {
        return new ProtectedWorkerPool(api, api::nextSpec, builder.build(), CLOCK);
    }

    private static Thread acquireAsync(
            ProtectedWorkerPool pool,
            WorkerPool.Job job,
            AtomicReference<Throwable> failure,
            CountDownLatch done) {
        Thread thread = new Thread(() -> {
            try {
                pool.acquire(job, WAIT_MS);
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        }, "worker-pool-acquire");
        thread.setDaemon(true);
        return thread;
    }

    private static Thread acquireReleaseAsync(
            ProtectedWorkerPool pool,
            WorkerPool.Job job,
            List<String> servedOrder,
            CountDownLatch done) {
        Thread thread = new Thread(() -> {
            try {
                Lease lease = pool.acquire(job, WAIT_MS);
                servedOrder.add(((TestJob) lease.job()).name());
                pool.release(lease);
            } catch (Throwable thrown) {
                servedOrder.add("FAILED:" + thrown);
            } finally {
                done.countDown();
            }
        }, "worker-pool-acquire");
        thread.setDaemon(true);
        return thread;
    }

    /** Starts a thread that acquires and keeps the lease until released. */
    private static Holder acquireAndHold(ProtectedWorkerPool pool, WorkerPool.Job job) {
        Holder holder = new Holder();
        Thread thread = new Thread(() -> {
            try {
                Lease lease = pool.acquire(job, WAIT_MS);
                holder.lease.set(lease);
            } catch (Throwable thrown) {
                holder.failure.set(thrown);
            } finally {
                holder.acquired.countDown();
            }
        }, "worker-pool-holder");
        thread.setDaemon(true);
        thread.start();
        return holder;
    }

    private static final class Holder {
        final CountDownLatch acquired = new CountDownLatch(1);
        final AtomicReference<Lease> lease = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
    }

    private static boolean awaitQueueDepth(ProtectedWorkerPool pool, int depth) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (System.nanoTime() < deadline) {
            if (pool.stats().queueDepth() == depth) {
                return true;
            }
            Thread.sleep(5L);
        }
        return false;
    }

    private static final MutableClock CLOCK = new MutableClock();

    private static final class MutableClock extends Clock {
        private long millis = 1_000_000L;

        void advanceMillis(long delta) {
            millis += delta;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    /** Fake platform boundary: no device, no binder, no real vsock. */
    private static final class FakeApi implements FastLaneVirtualMachineApi {
        private final List<FakeHandle> handles = new ArrayList<>();
        final List<Integer> stopCallsPerInstance = new ArrayList<>();
        int startCount;
        int stopped;
        int helloFailures;
        boolean ready = true;
        boolean blockControl;
        boolean failCreate;
        FastLaneStopResult stopResult = FastLaneStopResult.CONFIRMED;
        FastLaneLaunchSpec.CpuTopology lastTopology = FastLaneLaunchSpec.CpuTopology.ONE_CPU;

        FastLaneLaunchSpec nextSpec(String instanceId, FastLaneLaunchSpec.CpuTopology topology) {
            lastTopology = topology;
            return new FastLaneLaunchSpec(instanceId, "libfastlane_payload.so", 1024,
                    topology, 268_435_456L,
                    FastLaneLaunchSpec.DebugLevel.NONE, 5100, 5101, 5102, 5103, HASH);
        }

        @Override
        public FastLaneHandle start(FastLaneLaunchSpec spec) throws FastLaneException {
            if (failCreate) {
                throw new FastLaneException(FastLaneException.VM_CREATE_FAILED, "fake create failure");
            }
            startCount++;
            FakeHandle handle = new FakeHandle(spec.instanceId(), this, startCount - 1);
            handles.add(handle);
            stopCallsPerInstance.add(0);
            return handle;
        }

        @Override
        public FastLaneChannel connect(FastLaneHandle handle, int port) throws FastLaneException {
            if (handles.stream().noneMatch(candidate -> candidate.status() == 1)) {
                throw new FastLaneException(FastLaneException.VSOCK_FAILED, "no running vm");
            }
            if (port == 5100) {
                return new FakeControlChannel(this);
            }
            return new FakeEchoChannel();
        }

        void killRunning() {
            for (FakeHandle handle : handles) {
                handle.kill();
            }
        }
    }

    private static final class FakeHandle implements FastLaneHandle {
        private final String id;
        private final FakeApi api;
        private final int index;
        private int status = 1;

        FakeHandle(String id, FakeApi api, int index) {
            this.id = id;
            this.api = api;
            this.index = index;
        }

        void kill() {
            status = 0;
        }

        @Override
        public String instanceId() {
            return id;
        }

        @Override
        public int status() {
            return status;
        }

        @Override
        public FastLaneExitInfo waitForExit() {
            return FastLaneExitInfo.normal(0L);
        }

        @Override
        public boolean payloadReady() {
            return api.ready;
        }

        @Override
        public FastLaneStopResult stopRuntime(long graceMs) {
            api.stopped++;
            api.stopCallsPerInstance.set(index, api.stopCallsPerInstance.get(index) + 1);
            if (api.stopResult == FastLaneStopResult.CONFIRMED) {
                status = 0;
            }
            return api.stopResult;
        }

        @Override
        public void close() {
            status = 0;
        }

        @Override
        public List<String> notes() {
            return List.of();
        }
    }

    /**
     * Control channel that answers one FLN1 HELLO with HELLO_OK in the same
     * thread: flush() produces the guest reply, so no test thread or sleep is
     * needed.
     */
    private static final class FakeControlChannel implements FastLaneChannel {
        private final FakeApi api;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final PipedOutputStream guestWrites = new PipedOutputStream();
        private final PipedInputStream guestReads;
        private final OutputStream hostWrites = new OutputStream() {
            @Override
            public void write(int value) {
                request.write(value);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
                request.write(bytes, offset, length);
            }

            @Override
            public void flush() throws IOException {
                emitReply();
            }
        };

        FakeControlChannel(FakeApi api) throws FastLaneException {
            this.api = api;
            try {
                guestReads = new PipedInputStream(guestWrites, 4096);
            } catch (IOException failure) {
                throw new FastLaneException(FastLaneException.VSOCK_FAILED, "fake pipe", failure);
            }
        }

        private void emitReply() throws IOException {
            byte[] bytes = request.toByteArray();
            request.reset();
            if (bytes.length < Fln1TerminalCodec.HEADER_BYTES) {
                return;
            }
            if (bytes[5] != Fln1TerminalCodec.OP_HELLO) {
                return;
            }
            if (api.blockControl) {
                return;
            }
            Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
            if (api.helloFailures > 0) {
                api.helloFailures--;
                guest.write(guestWrites, TerminalFrame.of(TerminalFrameType.ERROR, 0,
                        Fln1Payloads.encodeError(new Fln1Payloads.Error(7, "no"))));
                guestWrites.flush();
                return;
            }
            guest.write(guestWrites, TerminalFrame.of(TerminalFrameType.HELLO, 0,
                    Fln1Payloads.encodeHelloOk(
                            new Fln1Payloads.HelloOk(1, Fln1Payloads.CAP_TERMINAL, 65536, 262144))));
            guestWrites.flush();
        }

        @Override
        public InputStream inputStream() {
            return guestReads;
        }

        @Override
        public OutputStream outputStream() {
            return hostWrites;
        }

        @Override
        public void close() throws IOException {
            guestReads.close();
            guestWrites.close();
        }
    }

    private static final class FakeEchoChannel implements FastLaneChannel {
        private final PipedOutputStream writes = new PipedOutputStream();
        private final PipedInputStream reads;

        FakeEchoChannel() {
            try {
                reads = new PipedInputStream(writes, 4096);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public InputStream inputStream() {
            return reads;
        }

        @Override
        public OutputStream outputStream() {
            return writes;
        }

        @Override
        public void close() throws IOException {
            reads.close();
            writes.close();
        }
    }
}
