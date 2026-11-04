package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.WorkerPool.Code;
import io.github.nyzeep.orchestella.WorkerPool.Lease;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneExitInfo;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneHandle;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpec;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSession;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneStopResult;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Capabilities;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Limits;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.Fln1Payloads;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.Fln1TerminalCodec;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalFrame;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalFrameType;
import io.github.nyzeep.orchestella.runtime.fastlane.terminal.TerminalMux;
import java.io.ByteArrayInputStream;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * PVM-06 wiring contract: the pool-backed distcc dispatcher. Unit tests over
 * a fake pool pin the lease lifecycle (acquire on open, release exactly once
 * on close, no leak on refusal or opener failure, fixed WORKER_POOL_* codes);
 * integration tests run the same dispatcher over the real
 * {@link ProtectedWorkerPool} with a fake platform API, so the design-intent
 * path "distcc connection → pool → leased instance's distcc port" is proven
 * zero-device. Mirrors the ProtectedWorkerPoolTest fake style.
 */
public final class WorkerPoolDistccDispatcherTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    /** Public golden token, split so it is not read as a credential. */
    private static final String TOKEN =
            "00112233" + "44556677" + "8899aabb" + "ccddeeff";
    private static final String TRIPLE = "aarch64-unknown-linux-android";
    private static final int DISTCC_PORT = 5103;
    private static final long WAIT_MS = 5_000L;

    // --- lease lifecycle over a fake pool ---------------------------------------

    @Test
    public void openAcquiresALeaseAndCloseReleasesItExactlyOnce() throws Exception {
        FakePool pool = new FakePool();
        RecordingOpener opener = new RecordingOpener();
        WorkerPoolDistccDispatcher dispatcher = new WorkerPoolDistccDispatcher(pool, opener);

        FastLaneChannel channel = dispatcher.open();
        assertEquals(1, pool.acquireCalls);
        assertNotNull(opener.leases.get(0).session());
        channel.close();
        assertEquals(1, pool.released.size());
        // A second close is a no-op: the lease returns exactly once.
        channel.close();
        assertEquals(1, pool.released.size());
        assertTrue(pool.released.get(0).isReleased());
    }

    @Test
    public void outputStreamCloseIsAHalfCloseAndDoesNotReleaseTheLease() throws Exception {
        FakePool pool = new FakePool();
        WorkerPoolDistccDispatcher dispatcher =
                new WorkerPoolDistccDispatcher(pool, new RecordingOpener());

        FastLaneChannel channel = dispatcher.open();
        channel.outputStream().close();
        assertEquals(0, pool.released.size());
        channel.close();
        assertEquals(1, pool.released.size());
    }

    @Test
    public void poolRefusalBecomesAFixedReasonWithoutOpeningAnything() {
        FakePool pool = new FakePool();
        pool.acquireFailure = new WorkerPool.WorkerPoolException(
                Code.DISABLED, "capacity is zero");
        RecordingOpener opener = new RecordingOpener();
        WorkerPoolDistccDispatcher dispatcher = new WorkerPoolDistccDispatcher(pool, opener);

        FastLaneException failure = assertThrows(
                FastLaneException.class, dispatcher::open);
        assertEquals("WORKER_POOL_DISABLED", failure.reason());
        assertEquals(0, opener.leases.size());
        assertEquals(0, pool.released.size());
    }

    @Test
    public void everyPoolCodeMapsToABridgeShapedReason() {
        // The bridge normalises failure codes to [A-Z0-9_]{1,64}; every mapped
        // reason must survive that shape unchanged.
        for (Code code : Code.values()) {
            String reason = WorkerPoolDistccDispatcher.reasonCode(code);
            assertTrue(reason.startsWith("WORKER_POOL_"));
            assertTrue(reason.matches("[A-Z0-9_]{1,64}"));
        }
    }

    @Test
    public void openerFailureReleasesTheLeaseAndRethrowsUnchanged() {
        FakePool pool = new FakePool();
        FastLaneException handshakeFailure = new FastLaneException(
                "AXP1_AUTH_FAILED", "AXP1 handshake failed");
        WorkerPoolDistccDispatcher dispatcher = new WorkerPoolDistccDispatcher(
                pool, lease -> {
                    throw handshakeFailure;
                });

        FastLaneException thrown = assertThrows(FastLaneException.class, dispatcher::open);
        assertSame(handshakeFailure, thrown);
        assertEquals(1, pool.released.size());

        // The same holds for plain IOException paths.
        pool.released.clear();
        WorkerPoolDistccDispatcher ioDispatcher = new WorkerPoolDistccDispatcher(
                pool, lease -> {
                    throw new IOException("transport gone");
                });
        assertThrows(IOException.class, ioDispatcher::open);
        assertEquals(1, pool.released.size());
    }

    @Test
    public void explicitAcquireTimeoutIsPassedToThePool() throws Exception {
        FakePool pool = new FakePool();
        WorkerPoolDistccDispatcher dispatcher =
                new WorkerPoolDistccDispatcher(pool, new RecordingOpener(), 1_234L);
        dispatcher.open();
        assertEquals(1_234L, pool.lastTimeoutMs);

        WorkerPoolDistccDispatcher defaultDispatcher =
                new WorkerPoolDistccDispatcher(pool, new RecordingOpener());
        defaultDispatcher.open();
        assertEquals(-1L, pool.lastTimeoutMs);
    }

    // --- the same dispatcher over the real ProtectedWorkerPool -------------------

    @Test
    public void pooledDispatcherServesJobsOnLeasedInstancesAndReusesTheWarmOne() throws Exception {
        FakeApi api = new FakeApi();
        RecordingOpener opener = new RecordingOpener();
        try (ProtectedWorkerPool pool = new ProtectedWorkerPool(
                api, api::nextSpec,
                ProtectedWorkerPool.Config.builder().capacity(1).build(),
                CLOCK)) {
            WorkerPoolDistccDispatcher dispatcher = new WorkerPoolDistccDispatcher(pool, opener);

            FastLaneChannel first = dispatcher.open();
            first.close();
            FastLaneChannel second = dispatcher.open();
            second.close();

            assertEquals(1, api.startCount);
            assertEquals(1, pool.stats().coldCreates());
            assertEquals(1, pool.stats().reuses());
            String firstInstance = opener.instances.get(0);
            assertEquals(firstInstance, opener.instances.get(1));
            assertEquals(DISTCC_PORT, opener.ports.get(0).intValue());
            assertEquals(DISTCC_PORT, opener.ports.get(1).intValue());
        }
    }

    @Test
    public void pooledDispatcherRunsUpToCapacityAndQueuesTheRest() throws Exception {
        FakeApi api = new FakeApi();
        RecordingOpener opener = new RecordingOpener();
        try (ProtectedWorkerPool pool = new ProtectedWorkerPool(
                api, api::nextSpec,
                ProtectedWorkerPool.Config.builder().capacity(2).build(),
                CLOCK)) {
            WorkerPoolDistccDispatcher dispatcher = new WorkerPoolDistccDispatcher(pool, opener);
            List<FastLaneChannel> channels = new ArrayList<>();
            List<Thread> workers = new ArrayList<>();
            CountDownLatch twoOpened = new CountDownLatch(2);
            CountDownLatch allOpened = new CountDownLatch(4);
            AtomicInteger failures = new AtomicInteger();
            // Four concurrent distcc connections against two slots: two are
            // served, two wait in the bounded queue and are served on release.
            for (int i = 0; i < 4; i++) {
                Thread worker = new Thread(() -> {
                    try {
                        FastLaneChannel channel = dispatcher.open();
                        synchronized (channels) {
                            channels.add(channel);
                        }
                        twoOpened.countDown();
                        allOpened.countDown();
                    } catch (IOException | RuntimeException | FastLaneException failure) {
                        failures.incrementAndGet();
                    }
                }, "pooled-dispatch-" + i);
                worker.setDaemon(true);
                worker.start();
                workers.add(worker);
            }
            assertTrue(twoOpened.await(WAIT_MS, TimeUnit.MILLISECONDS));
            // Returning the first two leases is what lets the queued two in.
            synchronized (channels) {
                channels.get(0).close();
                channels.get(1).close();
            }
            assertTrue(allOpened.await(WAIT_MS, TimeUnit.MILLISECONDS));
            assertEquals(0, failures.get());
            assertEquals(2, api.startCount);
            assertEquals(2, pool.health().leasedSlots());

            for (FastLaneChannel channel : channels) {
                channel.close();
            }
            for (Thread worker : workers) {
                worker.join(WAIT_MS);
            }
            assertEquals(2, opener.instances.stream().distinct().count());
            assertEquals(0, pool.health().leasedSlots());
            assertEquals(2, pool.health().warmSlots());
        }
    }

    @Test
    public void disabledPoolRefusesDeterministicallyWithoutAnyVm() {
        FakeApi api = new FakeApi();
        ProtectedWorkerPool pool = new ProtectedWorkerPool(
                api, api::nextSpec, ProtectedWorkerPool.Config.defaults(), CLOCK);
        WorkerPoolDistccDispatcher dispatcher =
                new WorkerPoolDistccDispatcher(pool, new RecordingOpener());
        FastLaneException failure = assertThrows(FastLaneException.class, dispatcher::open);
        assertEquals("WORKER_POOL_DISABLED", failure.reason());
        assertEquals(0, api.startCount);
        pool.close();
    }

    @Test
    public void productionOpenerTargetsTheLeasedSessionsDistccPortAndNeverLeaks()
            throws Exception {
        FakeApi api = new FakeApi();
        // The distcc port refuses: the AXP1 handshake can never be reached, so
        // the opener fails after the vsock connect — the worst-case lease path.
        api.failPorts.add(DISTCC_PORT);
        try (ProtectedWorkerPool pool = new ProtectedWorkerPool(
                api, api::nextSpec,
                ProtectedWorkerPool.Config.builder().capacity(1).build(),
                CLOCK)) {
            WorkerPoolDistccDispatcher dispatcher = new WorkerPoolDistccDispatcher(
                    pool,
                    WorkerPoolDistccDispatcher.axp1OverLease(
                            TOKEN, capabilities(), TRIPLE, limits()));

            FastLaneException failure =
                    assertThrows(FastLaneException.class, dispatcher::open);
            assertEquals(FastLaneException.VSOCK_FAILED, failure.reason());
            // The lease went back before the exception left the dispatcher:
            // the slot is warm again, ready for the next job.
            assertEquals(1, pool.health().warmSlots());
            // The production opener reached for the leased session's distcc
            // port (5103), not the control port the pool's own HELLO uses.
            assertTrue(api.connectedPorts.contains(DISTCC_PORT));

            // The lease returned cleanly: the same slot serves the next job.
            api.failPorts.clear();
            RecordingOpener opener = new RecordingOpener();
            WorkerPoolDistccDispatcher working = new WorkerPoolDistccDispatcher(pool, opener);
            FastLaneChannel channel = working.open();
            channel.close();
            assertEquals(1, api.startCount);
            assertEquals(1, pool.stats().reuses());
        }
    }

    private static Axp1Capabilities capabilities() {
        return Axp1Capabilities.builder()
                .distcc(true)
                .compilerTriple(TRIPLE)
                .maxFrame(1_048_576)
                .build();
    }

    private static Axp1Limits limits() {
        return new Axp1Limits(3_000L, 4 * 1_048_576, 1_048_576);
    }

    // --- fakes -------------------------------------------------------------------

    /** Records the lease and hands out a stateless stub channel. */
    private static final class RecordingOpener
            implements WorkerPoolDistccDispatcher.LeaseChannelOpener {
        final List<Lease> leases = new ArrayList<>();
        final List<String> instances = new ArrayList<>();
        final List<Integer> ports = new ArrayList<>();

        @Override
        public FastLaneChannel open(Lease lease) {
            leases.add(lease);
            instances.add(lease.session().instanceId());
            ports.add(lease.session().spec().distccPort());
            return new StubChannel();
        }
    }

    private static final class StubChannel implements FastLaneChannel {
        @Override
        public InputStream inputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream outputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public void close() {
            // Nothing owned by this stub.
        }
    }

    /** Scripted pool: the acquire outcome is a field, releases are recorded. */
    private static final class FakePool implements WorkerPool {
        WorkerPool.WorkerPoolException acquireFailure;
        int acquireCalls;
        long lastTimeoutMs = -1L;
        final List<Lease> released = new ArrayList<>();
        private final StubApi api = new StubApi();
        private final FastLaneSession session = new FastLaneSession(
                api,
                new FakeHandle("device-a-pool-1"),
                new FastLaneLaunchSpec("device-a-pool-1", "libpvm_compiler_payload.so", 1024,
                        FastLaneLaunchSpec.CpuTopology.ONE_CPU, 1_048_576L,
                        FastLaneLaunchSpec.DebugLevel.NONE, 5100, 5101, 5102, DISTCC_PORT, HASH));

        @Override
        public Lease acquire(Job job, long timeoutMs) throws WorkerPool.WorkerPoolException {
            acquireCalls++;
            lastTimeoutMs = timeoutMs;
            if (acquireFailure != null) {
                throw acquireFailure;
            }
            return new Lease(this, job, session, "device-a-pool-1");
        }

        @Override
        public Lease acquire(Job job) throws WorkerPool.WorkerPoolException {
            return acquire(job, -1L);
        }

        @Override
        public boolean cancel(Job job) {
            return false;
        }

        @Override
        public boolean release(Lease lease) {
            if (lease.owner() != this || lease.isReleased()) {
                return false;
            }
            lease.markReleased();
            released.add(lease);
            return true;
        }

        @Override
        public HealthReport health() {
            return new HealthReport(0, 0, 0, 0, 0, 0, List.of());
        }

        @Override
        public ResizeResult resize(int capacity) {
            return new ResizeResult(0, false, null);
        }

        @Override
        public Stats stats() {
            throw new UnsupportedOperationException("not needed by the dispatcher");
        }

        @Override
        public ShutdownResult shutdown(long timeoutMs) {
            return ShutdownResult.DRAINED;
        }

        @Override
        public void close() {
            // Nothing owned by this fake.
        }
    }

    /** Fake platform boundary for the real-pool integration half: no device. */
    private static final class FakeApi implements FastLaneVirtualMachineApi {
        private final List<FakeHandle> handles = new ArrayList<>();
        final List<Integer> connectedPorts = new ArrayList<>();
        final List<Integer> failPorts = new ArrayList<>();
        int startCount;

        FastLaneLaunchSpec nextSpec(String instanceId, FastLaneLaunchSpec.CpuTopology topology) {
            return new FastLaneLaunchSpec(instanceId, "libpvm_compiler_payload.so", 1024,
                    topology, 1_048_576L, FastLaneLaunchSpec.DebugLevel.NONE,
                    5100, 5101, 5102, DISTCC_PORT, HASH);
        }

        @Override
        public FastLaneHandle start(FastLaneLaunchSpec spec) {
            startCount++;
            FakeHandle handle = new FakeHandle(spec.instanceId());
            handles.add(handle);
            return handle;
        }

        @Override
        public FastLaneChannel connect(FastLaneHandle handle, int port) throws FastLaneException {
            connectedPorts.add(port);
            if (failPorts.contains(port)) {
                throw new FastLaneException(FastLaneException.VSOCK_FAILED, "fake vsock refused");
            }
            if (port == 5100) {
                return new FakeControlChannel();
            }
            return new StubChannel();
        }
    }

    private static final class FakeHandle implements FastLaneHandle {
        private final String id;

        FakeHandle(String id) {
            this.id = id;
        }

        @Override
        public String instanceId() {
            return id;
        }

        @Override
        public int status() {
            return 1;
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
            return FastLaneStopResult.CONFIRMED;
        }

        @Override
        public void close() {
            // Nothing owned by this fake.
        }

        @Override
        public List<String> notes() {
            return List.of();
        }
    }

    private static final class StubApi implements FastLaneVirtualMachineApi {
        @Override
        public FastLaneHandle start(FastLaneLaunchSpec spec) {
            throw new AssertionError("the dispatcher never starts a VM itself");
        }

        @Override
        public FastLaneChannel connect(FastLaneHandle handle, int port) {
            throw new AssertionError("the dispatcher session never opens a channel here");
        }
    }

    /**
     * Control channel that answers one FLN1 HELLO with HELLO_OK in the same
     * thread (flush produces the guest reply); copied in shape from the
     * ProtectedWorkerPoolTest fake.
     */
    private static final class FakeControlChannel implements FastLaneChannel {
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final PipedOutputStream guestWrites = new PipedOutputStream();
        private final PipedInputStream guestReads;

        FakeControlChannel() throws FastLaneException {
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
            return new OutputStream() {
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
        }

        @Override
        public void close() throws IOException {
            guestReads.close();
            guestWrites.close();
        }
    }

    private static final MutableClock CLOCK = new MutableClock();

    private static final class MutableClock extends Clock {
        private long millis = 1_000_000L;

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
}
