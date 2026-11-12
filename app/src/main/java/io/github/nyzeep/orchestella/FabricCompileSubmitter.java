package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.DistccTcpVsockBridge;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Gate 1 (ACR 2026-11-13 方案 A, fabric-route doc D-1): the first fabric
 * submitter — the product loopback listener whose accepted distcc connections
 * are dispatched into the {@link FabricOrchestrator} and executed by whichever
 * face wins the route. It follows the {@code WorkerPoolDistccDispatcher}
 * paradigm exactly: it is a {@link DistccTcpVsockBridge.ChannelFactory}, the
 * bridge runs the listener (loopback bind, connection cap, outcome
 * classification, stats — reused unchanged), and one accepted connection
 * becomes one channel.
 *
 * <p>The difference is what a channel <em>is</em>: here a channel is the
 * fabric. {@link #open()} builds one {@link FabricCompileItem} byte pipe (the
 * shared two-face work-item shape), dispatches it with parallelism 1 on the
 * submitter's dispatch executor, and answers the pipe's bridge-side view —
 * the bridge then pumps the guest's distcc bytes into the pipe while the
 * winning face's worker pumps the pipe through its own channel (the UML face
 * via the bridge's UML-TCP lane, the native face through its pool lease). The
 * dispatch is asynchronous on purpose: {@code dispatch} executes synchronously
 * by its locked contract, and the bridge's {@code open()} must return before
 * any bytes flow — the pipe is what lets both pumps run concurrently.
 *
 * <p><b>Refusal mapping (fixed codes only).</b> No orchestrator is bound (the
 * face is not armed) → {@link FastLaneException} with {@link #NOT_ARMED_REASON}
 * from {@code open()} itself — the bridge preserves the reason in its
 * connection stats. A bound orchestrator's route refusals (gate off, no face
 * available, wide-parallel policy refusal) and mid-flight face failures end
 * the pipe so the bridge classifies the connection {@code CHANNEL_FAILED};
 * the fabric-side reason is the orchestrator's own outcome contract, not a
 * restatement here. A refused job never leaks a face grant — the orchestrator
 * releases and (on failure) evicts by its own contract.
 *
 * <p><b>Lifecycle (the arm-disarm binding).</b> {@link #start()} binds and
 * starts the loopback listener; {@link #close()} stops it and stops accepting
 * dispatches — the wiring owner calls start when the face arms (with the
 * orchestrator bound) and close exactly where the face disarms, so the
 * listener never outlives the face it serves. Idempotent, like the bridge's
 * own close.
 */
public final class FabricCompileSubmitter
        implements DistccTcpVsockBridge.ChannelFactory, AutoCloseable {

    /** Fixed reason prefix; the fabric refusal class follows. */
    public static final String REASON_PREFIX = "FABRIC_";
    /** Fixed reason: dispatch was asked with no orchestrator bound (face not armed). */
    public static final String NOT_ARMED_REASON = REASON_PREFIX + "NOT_ARMED";

    private static final int PIPE_BYTES = 64 * 1024;
    private static final DistccTcpVsockBridge.StatsSink NO_STATS = stats -> {};

    private final Supplier<FabricOrchestrator> orchestrators;
    private final int requestedPort;
    private final int maxConnections;
    private final int idleTimeoutMs;
    private final DistccTcpVsockBridge.StatsSink statsSink;
    private final AtomicLong dispatchSequence = new AtomicLong();

    private final Object lock = new Object();
    private DistccTcpVsockBridge bridge;
    private ExecutorService dispatchExecutor;

    /**
     * @param orchestrators the bound orchestrator for the currently armed face,
     *        or {@code null} while none is armed — answered fresh per dispatch
     *        so an arm/disarm cycle swaps the fabric without rebuilding the
     *        listener
     * @param requestedPort the loopback listener's port ({@code 0} = ephemeral)
     * @param maxConnections the bridge's concurrent-connection cap (positive)
     * @param idleTimeoutMs the bridge's idle watchdog ({@code 0} disables it —
     *        a real distcc job can be silent for minutes mid-compile)
     * @param statsSink the bridge's per-connection stats sink (counters and
     *        fixed codes only; never payload bytes)
     */
    public FabricCompileSubmitter(
            Supplier<FabricOrchestrator> orchestrators,
            int requestedPort,
            int maxConnections,
            int idleTimeoutMs,
            DistccTcpVsockBridge.StatsSink statsSink) {
        this.orchestrators = Objects.requireNonNull(orchestrators, "orchestrators");
        if (requestedPort < 0 || requestedPort > 65535) {
            throw new IllegalArgumentException("port must be in [0, 65535]");
        }
        this.requestedPort = requestedPort;
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxConnections must be positive");
        }
        this.maxConnections = maxConnections;
        if (idleTimeoutMs < 0) {
            throw new IllegalArgumentException("idleTimeoutMs must not be negative");
        }
        this.idleTimeoutMs = idleTimeoutMs;
        this.statsSink = Objects.requireNonNull(statsSink, "statsSink");
    }

    /**
     * Binds and starts the loopback listener; idempotent while running. The
     * listener is a {@link DistccTcpVsockBridge} with this submitter as its
     * channel factory — the bridge's listener/pump/classification/stats are
     * reused unchanged (D-1 keeps the bridge the one loopback access point).
     */
    public void start(InetAddress loopback) throws IOException {
        Objects.requireNonNull(loopback, "loopback");
        synchronized (lock) {
            if (bridge != null) {
                return;
            }
            DistccTcpVsockBridge started = new DistccTcpVsockBridge(
                    loopback, requestedPort, this, maxConnections, 0, idleTimeoutMs, statsSink);
            started.start();
            dispatchExecutor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "fabric-submit-dispatch");
                thread.setDaemon(true);
                return thread;
            });
            bridge = started;
        }
    }

    /** The bound listener's port; only valid while started. */
    public int port() {
        synchronized (lock) {
            if (bridge == null) {
                throw new IllegalStateException("fabric submitter is not started");
            }
            return bridge.port();
        }
    }

    /** True while the listener is running. */
    public boolean isRunning() {
        synchronized (lock) {
            return bridge != null;
        }
    }

    /**
     * One accepted distcc connection in, one fabric dispatch out. The pipe's
     * bridge-side view is answered immediately; the dispatch runs on the
     * submitter's executor and its outcome ends the pipe (admitted jobs end
     * when the face's pump delivers both directions; refusals fail it).
     */
    @Override
    public FastLaneChannel open() throws FastLaneException, IOException {
        FabricOrchestrator orchestrator = orchestrators.get();
        if (orchestrator == null) {
            throw new FastLaneException(
                    NOT_ARMED_REASON, "no fabric orchestrator is bound; the face is not armed");
        }
        FabricCompilePipe pipe = new FabricCompilePipe();
        ExecutorService executor;
        synchronized (lock) {
            executor = dispatchExecutor;
        }
        if (executor == null || executor.isShutdown()) {
            throw new FastLaneException(
                    NOT_ARMED_REASON, "the fabric submitter is not started");
        }
        long dispatch = dispatchSequence.incrementAndGet();
        executor.execute(() -> {
            try {
                FabricOrchestrator.Outcome outcome = orchestrator.dispatch(pipe.item(), 1);
                if (!outcome.admitted()) {
                    // Gate off, route refusal or face failure: the job never
                    // executed; end the pipe so the bridge reports the failure.
                    pipe.refuse();
                }
            } catch (RuntimeException dispatchFailure) {
                pipe.refuse();
            }
        });
        return pipe.bridgeView();
    }

    /**
     * Stops the listener and the dispatch executor; idempotent. In-flight
     * bridge connections answer {@code BRIDGE_CLOSED} (the bridge's own
     * terminal classification); their dispatch tasks see the pipe end.
     */
    @Override
    public void close() {
        synchronized (lock) {
            DistccTcpVsockBridge stopping = bridge;
            bridge = null;
            ExecutorService stoppingExecutor = dispatchExecutor;
            dispatchExecutor = null;
            if (stopping != null) {
                stopping.close();
            }
            if (stoppingExecutor != null) {
                stoppingExecutor.shutdownNow();
            }
        }
    }

    /**
     * One dispatched job's byte pipe. The item (the face-side view) and the
     * channel (the bridge-side view) are the two ends of the same two
     * directions: bridge → face carries the job's request bytes, face →
     * bridge carries the answer.
     *
     * <p>The bridge-side streams are refusal-aware wrappers, not the raw
     * pipes: a refused job must reach the bridge as a transport failure
     * ({@code CHANNEL_FAILED}), never as a clean EOF. A JDK pipe whose writer
     * was closed answers a blocked read with -1 — indistinguishable from the
     * response being over — so the wrapper turns anything read after
     * {@link #refuse()} into an honest {@code IOException}.
     */
    private static final class FabricCompilePipe {
        private final PipedInputStream toFace;
        private final PipedOutputStream bridgeToFace;
        private final PipedInputStream toBridge;
        private final PipedOutputStream faceToBridge;
        private final AtomicBoolean refused = new AtomicBoolean();

        FabricCompilePipe() throws IOException {
            this.toFace = new PipedInputStream(PIPE_BYTES);
            this.bridgeToFace = new PipedOutputStream(toFace);
            this.toBridge = new PipedInputStream(PIPE_BYTES);
            this.faceToBridge = new PipedOutputStream(toBridge);
        }

        /** The face-worker view: the {@link FabricCompileItem} byte pipe. */
        FabricCompileItem item() {
            return new FabricCompileItem(toFace, faceToBridge);
        }

        /**
         * The bridge-side view the accepted connection pumps through. Closing
         * it closes the bridge's two ends; the face's pump sees its side end.
         */
        FastLaneChannel bridgeView() {
            return new FastLaneChannel() {
                @Override public InputStream inputStream() {
                    return new InputStream() {
                        @Override public int read() throws IOException {
                            int value = toBridge.read();
                            if (value < 0 && refused.get()) {
                                throw new IOException(REFUSED_READ);
                            }
                            return value;
                        }

                        @Override public int read(byte[] buffer, int offset, int length)
                                throws IOException {
                            int read = toBridge.read(buffer, offset, length);
                            if (read < 0 && refused.get()) {
                                throw new IOException(REFUSED_READ);
                            }
                            return read;
                        }

                        @Override public void close() {
                            closeQuietly(toBridge);
                        }
                    };
                }

                @Override public OutputStream outputStream() {
                    return new OutputStream() {
                        @Override public void write(int value) throws IOException {
                            checkRefused();
                            bridgeToFace.write(value);
                        }

                        @Override public void write(byte[] bytes, int offset, int length)
                                throws IOException {
                            checkRefused();
                            bridgeToFace.write(bytes, offset, length);
                        }

                        @Override public void flush() throws IOException {
                            checkRefused();
                            bridgeToFace.flush();
                        }

                        @Override public void close() {
                            closeQuietly(bridgeToFace);
                        }
                    };
                }

                @Override public void close() {
                    refuse();
                }
            };
        }

        /**
         * Ends every direction; a refused or already-dead job owns no bytes.
         * The refused flag goes up <b>before</b> the closes so any read the
         * teardown unblocks lands in the wrapper's refusal check.
         */
        void refuse() {
            refused.set(true);
            closeQuietly(toBridge);
            closeQuietly(bridgeToFace);
            closeQuietly(faceToBridge);
            closeQuietly(toFace);
        }

        private static final String REFUSED_READ =
                "fabric refused the dispatch: the job never reached a face";

        private void checkRefused() throws IOException {
            if (refused.get()) {
                throw new IOException(REFUSED_READ);
            }
        }

        private static void closeQuietly(java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Best-effort teardown; the terminal outcome is already set.
            }
        }
    }
}
