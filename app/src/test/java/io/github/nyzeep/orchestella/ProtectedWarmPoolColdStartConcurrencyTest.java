package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.ProtectedWarmPool.Code;
import io.github.nyzeep.orchestella.ProtectedWarmPool.Config;
import io.github.nyzeep.orchestella.ProtectedWarmPool.Lease;
import io.github.nyzeep.orchestella.ProtectedWarmPool.PoolException;
import io.github.nyzeep.orchestella.ProtectedWarmPool.ShutdownResult;
import io.github.nyzeep.orchestella.ProtectedWarmPool.State;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;

/**
 * P1-5 regression: a slot's VM cold start must not hold the pool-wide lock, so
 * {@code state()}, {@code stats()} and {@code release()} stay responsive while
 * {@code api.start} is running, and a second acquire waits on the CREATING slot
 * instead of starting a second VM.
 */
public final class ProtectedWarmPoolColdStartConcurrencyTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long PROBE_TIMEOUT_MS = 2_000L;

    @Test
    public void coldStartDoesNotBlockStateStatsAndRelease() throws Exception {
        BlockingApi api = new BlockingApi();
        Config config = baseConfig().build();
        ProtectedWarmPool pool = new ProtectedWarmPool(api, api::nextSpec, config);
        try {
            LeaseCreate creator = startCreator(pool);
            assertTrue("cold create reached api.start", api.startEntered.await(5, TimeUnit.SECONDS));

            assertEquals(
                    "state() must answer while the VM cold-starts",
                    State.CREATING,
                    runWithin(PROBE_TIMEOUT_MS, pool::state));
            assertEquals(
                    "stats() must answer while the VM cold-starts",
                    1L,
                    runWithin(PROBE_TIMEOUT_MS, () -> pool.stats().coldCreates()).longValue());
            assertFalse(
                    "release() must answer while the VM cold-starts",
                    runWithin(PROBE_TIMEOUT_MS, () -> pool.release(null)));

            PoolException busy = assertThrows(PoolException.class, pool::acquire);
            assertEquals(
                    "a zero wait budget fails fast on a cold-creating slot",
                    Code.WAIT_TIMEOUT,
                    busy.code());
            assertEquals("no second VM was started while the slot is CREATING", 1, api.startEntries.get());

            api.startRelease.countDown();
            creator.join(PROBE_TIMEOUT_MS);
            assertEquals("creator finished without a pool failure", null, creator.failure.get());
            Lease lease = creator.lease.get();
            assertNotNull(lease);
            assertEquals(State.LEASED, runWithin(PROBE_TIMEOUT_MS, pool::state));
            assertEquals("still exactly one VM create", 1, api.startEntries.get());
            assertTrue(pool.release(lease));
        } finally {
            pool.close();
        }
    }

    @Test
    public void secondAcquireWaitsForTheColdCreateThenReusesWithoutSecondCreate() throws Exception {
        BlockingApi api = new BlockingApi();
        Config config = baseConfig().maxWaitMs(10_000L).build();
        ProtectedWarmPool pool = new ProtectedWarmPool(api, api::nextSpec, config);
        try {
            LeaseCreate creator = startCreator(pool);
            assertTrue("cold create reached api.start", api.startEntered.await(5, TimeUnit.SECONDS));

            LeaseCreate waiter = new LeaseCreate(pool, "cold-start-waiter");
            waiter.start();
            Thread.sleep(150L);
            assertTrue("waiter is still waiting for the CREATING slot", waiter.isAlive());
            assertEquals("no second VM was started while the slot is CREATING", 1, api.startEntries.get());

            api.startRelease.countDown();
            creator.join(PROBE_TIMEOUT_MS);
            assertEquals("creator finished without a pool failure", null, creator.failure.get());
            Lease created = creator.lease.get();
            assertNotNull(created);
            assertTrue(pool.release(created));
            waiter.join(PROBE_TIMEOUT_MS);
            assertFalse("waiter finished", waiter.isAlive());
            assertEquals("waiter saw no pool failure", null, waiter.failure.get());
            Lease reused = waiter.lease.get();
            assertNotNull(created);
            assertNotNull(reused);
            assertEquals(
                    "the waiter reuses the freshly created VM instead of starting another",
                    created.session().instanceId(),
                    reused.session().instanceId());
            assertEquals("exactly one VM create for two acquires", 1, api.startEntries.get());
            assertEquals(1L, pool.stats().reuses());
            assertTrue(pool.release(reused));
        } finally {
            pool.close();
        }
    }

    @Test
    public void failedColdCreateSettlesFailedAndWakesTheWaitingAcquire() throws Exception {
        BlockingApi api = new BlockingApi();
        Config config = baseConfig().maxWaitMs(10_000L).build();
        ProtectedWarmPool pool = new ProtectedWarmPool(api, api::nextSpec, config);
        try {
            LeaseCreate creator = startCreator(pool);
            assertTrue("cold create reached api.start", api.startEntered.await(5, TimeUnit.SECONDS));

            LeaseCreate waiter = new LeaseCreate(pool, "cold-start-waiter");
            waiter.start();
            Thread.sleep(150L);
            assertTrue("waiter is waiting on the CREATING slot", waiter.isAlive());

            api.failStart = true;
            api.startRelease.countDown();
            creator.join(PROBE_TIMEOUT_MS);
            waiter.join(10_000L);
            assertTrue("creator finished", !creator.isAlive());
            assertTrue("waiter finished without hanging", !waiter.isAlive());

            PoolException creatorFailure = (PoolException) creator.failure.get();
            PoolException waiterFailure = (PoolException) waiter.failure.get();
            assertNotNull(creatorFailure);
            assertNotNull("the waiter was woken and attempted its own create", waiterFailure);
            assertEquals(Code.CREATE_FAILED, creatorFailure.code());
            assertEquals(Code.CREATE_FAILED, waiterFailure.code());
            assertEquals(State.FAILED, runWithin(PROBE_TIMEOUT_MS, pool::state));
        } finally {
            pool.close();
        }
    }

    @Test
    public void shutdownWhileColdStartInFlightNeverTouchesTheUnpublishedVm() throws Exception {
        BlockingApi api = new BlockingApi();
        Config config = baseConfig().build();
        ProtectedWarmPool pool = new ProtectedWarmPool(api, api::nextSpec, config);
        LeaseCreate creator = startCreator(pool);
        try {
            assertTrue("cold create reached api.start", api.startEntered.await(5, TimeUnit.SECONDS));

            assertEquals(
                    "bounded shutdown reports TIMED_OUT while the create episode is in flight",
                    ShutdownResult.TIMED_OUT,
                    pool.shutdown(50L));
            assertEquals(
                    "shutdown never stops the still-unpublished session",
                    0,
                    api.stopped.get());

            api.startRelease.countDown();
            creator.join(PROBE_TIMEOUT_MS);
            assertTrue(creator.failure.get() instanceof PoolException);
            assertEquals(Code.CLOSED, ((PoolException) creator.failure.get()).code());
            assertEquals(
                    "the create owner stopped its own VM and settled the slot",
                    State.FAILED,
                    runWithin(PROBE_TIMEOUT_MS, pool::state));
            assertTrue(api.stopped.get() >= 1);
        } finally {
            pool.close();
        }
    }

    // ---- helpers ----

    private static Config.Builder baseConfig() {
        return Config.builder().capacity(1).memoryMb(1024L).poolMemoryLimitMb(4096L);
    }

    private LeaseCreate startCreator(ProtectedWarmPool pool) {
        LeaseCreate creator = new LeaseCreate(pool, "cold-start-creator");
        creator.start();
        return creator;
    }

    /**
     * Runs {@code action} on a daemon thread and fails with a clear message when
     * it does not finish in time: a probe stuck alive means the call is still
     * blocked behind the cold start.
     */
    private static <T> T runWithin(long timeoutMs, Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread probe = new Thread(() -> {
            try {
                result.set(action.get());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "cold-start-probe");
        probe.setDaemon(true);
        probe.start();
        probe.join(timeoutMs);
        if (probe.isAlive()) {
            throw new AssertionError(
                    "probe did not finish within " + timeoutMs + "ms; the call is blocked");
        }
        if (failure.get() != null) {
            throw new AssertionError("probe failed", failure.get());
        }
        return result.get();
    }

    private static final class LeaseCreate extends Thread {
        private final ProtectedWarmPool pool;
        private final AtomicReference<Lease> lease = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private LeaseCreate(ProtectedWarmPool pool, String name) {
            super(name);
            setDaemon(true);
            this.pool = pool;
        }

        @Override
        public void run() {
            try {
                lease.set(pool.acquire());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }
    }

    /** Fake platform boundary: no device, no binder, no real vsock. */
    private static final class BlockingApi implements FastLaneVirtualMachineApi {
        final CountDownLatch startEntered = new CountDownLatch(1);
        final CountDownLatch startRelease = new CountDownLatch(1);
        final AtomicInteger startEntries = new AtomicInteger();
        final AtomicInteger startCount = new AtomicInteger();
        final AtomicInteger stopped = new AtomicInteger();
        volatile boolean failStart;

        FastLaneLaunchSpec nextSpec(String instanceId) {
            return new FastLaneLaunchSpec(instanceId, "libfastlane_payload.so", 1024,
                    FastLaneLaunchSpec.CpuTopology.ONE_CPU, 268_435_456L,
                    FastLaneLaunchSpec.DebugLevel.NONE, 5100, 5101, 5102, 5103, HASH);
        }

        @Override
        public FastLaneHandle start(FastLaneLaunchSpec spec) throws FastLaneException {
            startEntries.incrementAndGet();
            startEntered.countDown();
            if (!failStart) {
                try {
                    if (!startRelease.await(10, TimeUnit.SECONDS)) {
                        throw new FastLaneException(
                                FastLaneException.VM_CREATE_FAILED, "fake create timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new FastLaneException(
                            FastLaneException.VM_CREATE_FAILED, "fake create interrupted");
                }
            }
            if (failStart) {
                throw new FastLaneException(FastLaneException.VM_CREATE_FAILED, "fake create failure");
            }
            startCount.incrementAndGet();
            return new FakeHandle(spec.instanceId(), this);
        }

        @Override
        public FastLaneChannel connect(FastLaneHandle handle, int port) throws FastLaneException {
            if (port == 5100) {
                return new FakeControlChannel();
            }
            throw new FastLaneException(FastLaneException.VSOCK_FAILED, "unexpected port");
        }
    }

    private static final class FakeHandle implements FastLaneHandle {
        private final String id;
        private final BlockingApi api;
        private int status = 1;

        private FakeHandle(String id, BlockingApi api) {
            this.id = id;
            this.api = api;
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
            return true;
        }

        @Override
        public FastLaneStopResult stopRuntime(long graceMs) {
            api.stopped.incrementAndGet();
            status = 0;
            return FastLaneStopResult.CONFIRMED;
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
     * thread: flush() produces the guest reply, so no extra test thread is
     * needed.
     */
    private static final class FakeControlChannel implements FastLaneChannel {
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

        private FakeControlChannel() throws FastLaneException {
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
            Fln1TerminalCodec guest = new Fln1TerminalCodec(TerminalMux.Role.GUEST);
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
}
