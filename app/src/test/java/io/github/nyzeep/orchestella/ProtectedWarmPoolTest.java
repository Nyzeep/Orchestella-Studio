package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.ProtectedWarmPool.Code;
import io.github.nyzeep.orchestella.ProtectedWarmPool.Config;
import io.github.nyzeep.orchestella.ProtectedWarmPool.Lease;
import io.github.nyzeep.orchestella.ProtectedWarmPool.PoolException;
import io.github.nyzeep.orchestella.ProtectedWarmPool.State;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Zero-device tests for the protected-lane warm pool over a fake platform API. */
public final class ProtectedWarmPoolTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void defaultCapacityIsZeroAndAcquireIsDisabled() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWarmPool pool = new ProtectedWarmPool(api, api::nextSpec, Config.defaults())) {
            assertEquals(State.IDLE, pool.state());
            PoolException failure = assertThrows(PoolException.class, pool::acquire);
            assertEquals(Code.DISABLED, failure.code());
            assertEquals(0, api.startCount);
        }
    }

    @Test
    public void releaseThenAcquireReusesTheSameSession() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWarmPool pool = enabledPool(api, Config.builder())) {
            Lease first = pool.acquire();
            assertSame(State.LEASED, pool.state());
            String id = first.session().instanceId();
            assertTrue(pool.release(first));
            assertEquals(State.READY, pool.state());
            Lease second = pool.acquire();
            assertEquals(id, second.session().instanceId());
            assertEquals(1, api.startCount);
            assertEquals(1, pool.stats().coldCreates());
            assertEquals(1, pool.stats().reuses());
            pool.release(second);
        }
    }

    @Test
    public void idleLeasePastTtlIsEvictedAndReplaced() throws Exception {
        FakeApi api = new FakeApi();
        Config config = Config.builder().capacity(1).memoryMb(1024L).poolMemoryLimitMb(4096L)
                .idleTtlMs(1_000L).build();
        try (ProtectedWarmPool pool = new ProtectedWarmPool(api, api::nextSpec, config, CLOCK)) {
            Lease lease = pool.acquire();
            pool.release(lease);
            CLOCK.advanceMillis(1_001L);
            Lease next = pool.acquire();
            assertEquals(2, api.startCount);
            assertEquals(1, pool.stats().evictions());
            assertEquals(1L, pool.stats().evictReasons().get(Code.TTL_EXPIRED).longValue());
            pool.release(next);
        }
    }

    @Test
    public void poolMemoryLimitRejectsBeforeCreating() throws Exception {
        FakeApi api = new FakeApi();
        Config config = Config.builder().capacity(1).poolMemoryLimitMb(512L).memoryMb(1024L).build();
        try (ProtectedWarmPool pool = new ProtectedWarmPool(api, api::nextSpec, config, CLOCK)) {
            PoolException failure = assertThrows(PoolException.class, pool::acquire);
            assertEquals(Code.BUDGET_REJECTED, failure.code());
            assertEquals(0, api.startCount);
            assertEquals(State.FAILED, pool.state());
        }
    }

    @Test
    public void sharedLaneAdmissionCanRejectThePool() throws Exception {
        FakeApi api = new FakeApi();
        Config config = Config.builder().capacity(1).memoryMb(1024L).poolMemoryLimitMb(4096L).build();
        try (ProtectedWarmPool pool = new ProtectedWarmPool(
                api, api::nextSpec, config, CLOCK, memoryMb -> false)) {
            PoolException failure = assertThrows(PoolException.class, pool::acquire);
            assertEquals(Code.BUDGET_REJECTED, failure.code());
            assertEquals(0, api.startCount);
        }
    }

    @Test
    public void helloFailureEvictsAndColdCreatesOnce() throws Exception {
        FakeApi api = new FakeApi();
        api.helloFailures = 1;
        try (ProtectedWarmPool pool = enabledPool(api, Config.builder())) {
            Lease lease = pool.acquire();
            assertEquals(2, api.startCount);
            assertEquals(1L, pool.stats().evictReasons().get(Code.HELLO_FAILED).longValue());
            assertNotNull(lease);
            pool.release(lease);
        }
    }

    @Test
    public void secondHelloFailureIsReportedAndStopsThePool() throws Exception {
        FakeApi api = new FakeApi();
        api.helloFailures = 2;
        try (ProtectedWarmPool pool = enabledPool(api, Config.builder())) {
            PoolException failure = assertThrows(PoolException.class, pool::acquire);
            assertEquals(Code.HELLO_FAILED, failure.code());
            assertEquals(State.FAILED, pool.state());
            assertEquals(2, api.startCount);
            assertTrue(api.stopped >= 1);
        }
    }

    @Test
    public void reuseHelloFailureGetsOneColdCreateThenReports() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWarmPool pool = enabledPool(api, Config.builder())) {
            Lease warm = pool.acquire();
            pool.release(warm);
            api.helloFailures = 2;
            PoolException failure = assertThrows(PoolException.class, pool::acquire);
            assertEquals(Code.HELLO_FAILED, failure.code());
            assertEquals(2, api.startCount);
        }
    }

    @Test
    public void deadWarmSessionIsEvictedAndReplaced() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWarmPool pool = enabledPool(api, Config.builder())) {
            Lease lease = pool.acquire();
            pool.release(lease);
            api.killRunning();
            Lease next = pool.acquire();
            assertEquals(2, api.startCount);
            assertEquals(1L, pool.stats().evictReasons().get(Code.PROCESS_DIED).longValue());
            pool.release(next);
        }
    }

    @Test
    public void duplicateReleaseIsIdempotent() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWarmPool pool = enabledPool(api, Config.builder())) {
            Lease lease = pool.acquire();
            assertTrue(pool.release(lease));
            assertFalse(pool.release(lease));
            assertEquals(State.READY, pool.state());
            assertEquals(0, pool.stats().evictions());
            Lease again = pool.acquire();
            assertEquals(1, api.startCount);
            pool.release(again);
        }
    }

    @Test
    public void shutdownWithOutstandingLeaseIsBoundedAndStopsTheVm() throws Exception {
        FakeApi api = new FakeApi();
        ProtectedWarmPool pool = enabledPool(api, Config.builder().drainTimeoutMs(50L));
        Lease lease = pool.acquire();
        assertEquals(ProtectedWarmPool.ShutdownResult.TIMED_OUT, pool.shutdown(50L));
        assertEquals(State.FAILED, pool.state());
        assertEquals(1L, pool.stats().evictReasons().get(Code.DRAIN_TIMEOUT).longValue());
        assertTrue(api.stopped >= 1);
        assertFalse(pool.release(lease));
    }

    @Test
    public void shutdownDuringCreateIsBoundedAndStopsTheNewVm() throws Exception {
        FakeApi api = new FakeApi();
        api.blockCreate = true;
        ProtectedWarmPool pool = enabledPool(api, Config.builder());
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread creator = new Thread(() -> {
            try {
                pool.acquire();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "warm-pool-creator");
        creator.start();
        assertTrue(api.startEntered.await(2, TimeUnit.SECONDS));
        assertEquals(ProtectedWarmPool.ShutdownResult.TIMED_OUT, pool.shutdown(10L));
        api.startRelease.countDown();
        creator.join(5_000L);
        assertTrue(failure.get() instanceof PoolException);
        assertEquals(Code.CLOSED, ((PoolException) failure.get()).code());
        assertTrue(api.stopped >= 1);
    }

    @Test
    public void unconfirmedStopLeavesThePoolFailed() throws Exception {
        FakeApi api = new FakeApi();
        api.stopResult = FastLaneStopResult.UNCONFIRMED;
        ProtectedWarmPool pool = enabledPool(api, Config.builder());
        Lease lease = pool.acquire();
        pool.release(lease);
        assertEquals(ProtectedWarmPool.ShutdownResult.STOP_UNCONFIRMED, pool.shutdown(50L));
        assertEquals(State.FAILED, pool.state());
        assertEquals(1L, pool.stats().evictReasons().get(Code.STOP_UNCONFIRMED).longValue());
    }

    @Test
    public void acquireWaitsBoundedWhenAnotherLeaseIsOut() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWarmPool pool = enabledPool(api, Config.builder())) {
            Lease held = pool.acquire();
            PoolException failure = assertThrows(PoolException.class, pool::acquire);
            assertEquals(Code.WAIT_TIMEOUT, failure.code());
            pool.release(held);
        }
    }

    @Test
    public void payloadReadyTimeoutStopsTheAttempt() throws Exception {
        FakeApi api = new FakeApi();
        api.ready = false;
        Config.Builder config = Config.builder().payloadReadyTimeoutMs(50L);
        try (ProtectedWarmPool pool = enabledPool(api, config)) {
            PoolException failure = assertThrows(PoolException.class, pool::acquire);
            assertEquals(Code.READY_TIMEOUT, failure.code());
            assertTrue(api.stopped >= 1);
            assertEquals(State.FAILED, pool.state());
        }
    }

    @Test
    public void helloHandshakeTimeoutIsBounded() throws Exception {
        FakeApi api = new FakeApi();
        api.blockControl = true;
        Config.Builder config = Config.builder().helloTimeoutMs(40L);
        try (ProtectedWarmPool pool = enabledPool(api, config)) {
            PoolException failure = assertThrows(PoolException.class, pool::acquire);
            assertEquals(Code.HELLO_FAILED, failure.code());
        }
    }

    @Test
    public void runtimeFailureDuringCreateIsCreateFailed() throws Exception {
        FakeApi api = new FakeApi();
        api.failCreate = true;
        try (ProtectedWarmPool pool = enabledPool(api, Config.builder())) {
            PoolException failure = assertThrows(PoolException.class, pool::acquire);
            assertEquals(Code.CREATE_FAILED, failure.code());
            assertEquals(State.FAILED, pool.state());
        }
    }

    @Test
    public void statsCarryOnlyCountersAndFixedCodes() throws Exception {
        FakeApi api = new FakeApi();
        try (ProtectedWarmPool pool = enabledPool(api, Config.builder())) {
            Lease lease = pool.acquire();
            pool.release(lease);
            ProtectedWarmPool.Stats stats = pool.stats();
            assertTrue(stats.coldCreates() >= 1);
            assertTrue(stats.createMillis() >= 0);
            assertTrue(stats.readyMillis() >= 0);
            for (Code reason : stats.evictReasons().keySet()) {
                assertTrue("unexpected reason " + reason,
                        reason == Code.TTL_EXPIRED || reason == Code.PROCESS_DIED
                                || reason == Code.HELLO_FAILED
                                || reason == Code.DRAIN_TIMEOUT
                                || reason == Code.STOP_UNCONFIRMED);
            }
        }
    }

    private static ProtectedWarmPool enabledPool(FakeApi api, Config.Builder builder) {
        return new ProtectedWarmPool(api, api::nextSpec, builder.capacity(1).memoryMb(1024L)
                .poolMemoryLimitMb(4096L).build(), CLOCK);
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
        int startCount;
        int stopped;
        int helloFailures;
        boolean ready = true;
        boolean blockControl;
        boolean failCreate;
        FastLaneStopResult stopResult = FastLaneStopResult.CONFIRMED;
        boolean blockCreate;
        final CountDownLatch startEntered = new CountDownLatch(1);
        final CountDownLatch startRelease = new CountDownLatch(1);

        FastLaneLaunchSpec nextSpec(String instanceId) {
            return new FastLaneLaunchSpec(instanceId, "libfastlane_payload.so", 1024,
                    FastLaneLaunchSpec.CpuTopology.ONE_CPU, 268_435_456L,
                    FastLaneLaunchSpec.DebugLevel.NONE, 5100, 5101, 5102, 5103, HASH);
        }

        @Override
        public FastLaneHandle start(FastLaneLaunchSpec spec) throws FastLaneException {
            if (blockCreate) {
                startEntered.countDown();
                try {
                    if (!startRelease.await(5, TimeUnit.SECONDS)) {
                        throw new FastLaneException(FastLaneException.VM_CREATE_FAILED,
                                "fake create timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new FastLaneException(FastLaneException.VM_CREATE_FAILED,
                            "fake create interrupted");
                }
            }
            if (failCreate) {
                throw new FastLaneException(FastLaneException.VM_CREATE_FAILED, "fake create failure");
            }
            startCount++;
            FakeHandle handle = new FakeHandle(spec.instanceId(), this);
            handles.add(handle);
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
        private int status = 1;

        FakeHandle(String id, FakeApi api) {
            this.id = id;
            this.api = api;
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
        public io.github.nyzeep.orchestella.runtime.fastlane.FastLaneExitInfo waitForExit() {
            return io.github.nyzeep.orchestella.runtime.fastlane.FastLaneExitInfo.normal(0L);
        }

        @Override
        public boolean payloadReady() {
            return api.ready;
        }

        @Override
        public FastLaneStopResult stopRuntime(long graceMs) {
            api.stopped++;
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
