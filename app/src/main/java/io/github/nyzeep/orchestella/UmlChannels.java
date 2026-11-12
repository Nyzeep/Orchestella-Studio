package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.DistccTcpVsockBridge;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * C lane: the {@link UmlChannel} factory — the one place that builds channels
 * and the one place that adapts a channel onto the launcher's
 * {@link UmlFaceLauncher.Channel} seam, so the device window wires the face
 * through the D-1 seam, never around it.
 *
 * <p>What exists today: {@link #stub()} is the recording stub (tests, wiring
 * rehearsals — no network, no bridge), and since the ACR 2026-11-13 方案 A
 * decision landed the D-1 bridge change (Gate 1), {@link #overTcpLane} is the
 * real distcc-over-TCP channel: it opens one lane channel per admitted
 * execution through the bridge's
 * {@code DistccTcpVsockBridge.ChannelFactory} — the UML-TCP lane
 * ({@code UmlTcpChannelFactory}; vsock=pVM / TCP=UML is the D-1 wire design)
 * — and pumps the admitted {@link FabricCompileItem} byte pipe through it.
 * The wire protocol itself is untouched: this channel speaks no protocol, it
 * only moves the job's bytes and maps its failures onto the fixed channel
 * contract.
 */
public final class UmlChannels {

    private UmlChannels() {
    }

    /**
     * The recording stub: an honest dry-run channel that follows the full
     * lifecycle contract (open/execute/close, fixed refusal details) and
     * records every admitted execution ({@code grantedWidth} plus the item)
     * for assertions. Failures are only the programmed ones — it never fails
     * on its own initiative.
     */
    public static UmlChannel stub() {
        return new Stub();
    }

    /**
     * The real D-1 channel (Gate 1): distcc over the fabric's UML-TCP access
     * point. One admitted execution = one lane connection: the factory (the
     * bridge's {@code ChannelFactory}, production
     * {@code UmlTcpChannelFactory.loopback(...)}) dials the UML guest's
     * distccd, the item's byte pipe is pumped through it in both directions,
     * and the connection closes with the job — the same one-connection-per-job
     * shape the bridge itself runs for the pVM lane.
     *
     * <p>Failure mapping (fixed details only): a dial or lane-side pump
     * failure answers {@link UmlChannel#EXECUTE_FAILED_DETAIL} with the cause,
     * the state stays open and the orchestrator owns the eviction; a job whose
     * <em>item side</em> went away (the submitter's client disconnected) is
     * <b>not</b> a channel failure — the pump ends quietly, the grant releases
     * normally and the face stays routable (a dead client must not evict a
     * healthy face); a lane-side failure after the response fully arrived is
     * the peer answering and hanging up (the bridge's own normal shape), not a
     * failure. A work item that is not a {@link FabricCompileItem} byte pipe
     * is a caller bug and fails fast with {@code IllegalArgumentException}.
     *
     * @param laneChannels the bridge's UML-TCP lane channel factory
     */
    public static UmlChannel overTcpLane(
            DistccTcpVsockBridge.ChannelFactory
                    laneChannels) {
        return new TcpLaneChannel(laneChannels);
    }

    /**
     * The fabric-slot adapter: wraps a {@link UmlChannel} as the launcher's
     * {@link UmlFaceLauncher.Channel} so the face dispatches through the seam.
     * A channel refusal or failure maps onto
     * {@link FabricOrchestrator.FaceException} — the orchestrator's own
     * failure contract (release grant, evict face) — carrying the channel's
     * fixed detail under a fixed {@code uml-channel:} prefix, never a
     * free-form transport message.
     */
    public static UmlFaceLauncher.Channel asFaceChannel(UmlChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return (grantedWidth, item) -> {
            try {
                channel.execute(grantedWidth, item);
            } catch (UmlChannel.UmlChannelException refusal) {
                throw new FabricOrchestrator.FaceException(
                        "uml-channel:" + refusal.detail(), refusal);
            }
        };
    }

    /**
     * The recording stub implementation. Threading: not thread-safe by
     * design — the stub serves single-threaded contract tests and rehearsals;
     * concurrency semantics belong to a real transport.
     */
    public static final class Stub implements UmlChannel {

        private State state = State.NEW;
        private UmlChannelException executeFailure;
        private final List<String> executions = new ArrayList<>();
        private int opens;
        private int closes;

        /**
         * Programs exactly the next {@code execute} to fail with this cause
         * (one-shot: a later successful call needs another open channel — the
         * failure leaves the state open, per the contract).
         */
        public Stub failingNextExecuteWith(Throwable cause) {
            this.executeFailure = new UmlChannelException(EXECUTE_FAILED_DETAIL, cause);
            return this;
        }

        /** Every admitted execution, as {@code "<grantedWidth>:<item>"}. */
        public List<String> executions() {
            return List.copyOf(executions);
        }

        public int opens() {
            return opens;
        }

        public int closes() {
            return closes;
        }

        @Override public void open() throws UmlChannelException {
            if (state == State.OPEN) {
                throw new UmlChannelException(ALREADY_OPEN_DETAIL);
            }
            if (state == State.CLOSED) {
                throw new UmlChannelException(CLOSED_DETAIL);
            }
            state = State.OPEN;
            opens++;
        }

        @Override public void execute(int grantedWidth, FabricOrchestrator.WorkItem item)
                throws UmlChannelException {
            if (state == State.NEW) {
                throw new UmlChannelException(NOT_OPEN_DETAIL);
            }
            if (state == State.CLOSED) {
                throw new UmlChannelException(CLOSED_DETAIL);
            }
            if (executeFailure != null) {
                UmlChannelException failure = executeFailure;
                executeFailure = null;
                throw failure;
            }
            executions.add(grantedWidth + ":" + (item == null ? "null" : "item"));
        }

        @Override public void close() {
            closes++;
            state = State.CLOSED;
        }

        @Override public State state() {
            return state;
        }
    }

    /**
     * The real D-1 channel over the bridge's UML-TCP lane. Lifecycle follows
     * the locked contract exactly; the transport work happens per
     * {@code execute} (one lane connection per admitted job). State guards are
     * synchronized; the byte pump runs outside the lock, so concurrent
     * executions are independent lane connections — the same per-connection
     * model the bridge and distccd live by.
     */
    private static final class TcpLaneChannel implements UmlChannel {

        private static final int BUFFER_BYTES = 16 * 1024;

        private final DistccTcpVsockBridge.ChannelFactory
                laneChannels;
        private State state = State.NEW;

        TcpLaneChannel(
                DistccTcpVsockBridge.ChannelFactory
                        laneChannels) {
            this.laneChannels = Objects.requireNonNull(laneChannels, "laneChannels");
        }

        @Override public synchronized void open() throws UmlChannelException {
            if (state == State.OPEN) {
                throw new UmlChannelException(ALREADY_OPEN_DETAIL);
            }
            if (state == State.CLOSED) {
                throw new UmlChannelException(CLOSED_DETAIL);
            }
            state = State.OPEN;
        }

        @Override public void execute(int grantedWidth, FabricOrchestrator.WorkItem item)
                throws UmlChannelException {
            synchronized (this) {
                if (state == State.NEW) {
                    throw new UmlChannelException(NOT_OPEN_DETAIL);
                }
                if (state == State.CLOSED) {
                    throw new UmlChannelException(CLOSED_DETAIL);
                }
            }
            if (!(item instanceof FabricCompileItem)) {
                throw new IllegalArgumentException(
                        "the UML-TCP lane pumps a FabricCompileItem byte pipe, got: "
                                + item.getClass().getName());
            }
            // The granted width is the orchestrator's admission bookkeeping; the
            // lane transport itself is one distcc connection per job and needs
            // no width — distccd serves its own connection.
            FastLaneChannel lane;
            try {
                lane = laneChannels.open();
            } catch (FastLaneException | IOException | RuntimeException dialFailure) {
                throw new UmlChannelException(EXECUTE_FAILED_DETAIL, dialFailure);
            }
            try {
                pump((FabricCompileItem) item, lane);
            } finally {
                closeQuietly(lane);
            }
        }

        @Override public synchronized void close() {
            state = State.CLOSED;
        }

        @Override public synchronized State state() {
            return state;
        }

        /**
         * The dual pump: request (item → lane) on a helper thread, response
         * (lane → item) on the caller thread — the bridge's per-connection
         * shape driven by {@code execute} instead of the accept loop. The join
         * has no timeout on purpose: a client that never half-closes is the
         * normal distcc shape (the bridge documents it), so the job — and the
         * grant — stay outstanding exactly as long as the connection does, the
         * same honesty the bridge's own active-connection accounting runs by.
         */
        private void pump(FabricCompileItem item, FastLaneChannel lane)
                throws UmlChannelException {
            Pump pump = new Pump(item, lane);
            Thread upstream = new Thread(pump::requestDirection, "uml-lane-request");
            upstream.setDaemon(true);
            upstream.start();
            pump.responseDirection();
            try {
                upstream.join();
            } catch (InterruptedException interrupted) {
                // The dispatch thread was interrupted: the job is dead from the
                // caller's side — cancel it (which unblocks both directions)
                // and let the terminal classification below decide.
                Thread.currentThread().interrupt();
                pump.cancel();
                try {
                    upstream.join();
                } catch (InterruptedException again) {
                    Thread.currentThread().interrupt();
                }
            }
            IOException laneFailure = pump.laneFailure();
            if (laneFailure != null) {
                throw new UmlChannelException(EXECUTE_FAILED_DETAIL, laneFailure);
            }
            // A cancelled job (the item's client went away) or a hang-up after
            // the response arrived is not a channel failure: return normally so
            // the grant releases and the face stays routable.
        }

        /**
         * One job's two pump directions. The terminal classifications live
         * here, mirroring the bridge's {@code Connection}: item-side I/O
         * failures mean the job's client is gone (cancellation, never a lane
         * failure); lane-side I/O failures are failures — unless the response
         * had already fully arrived (peer answered and hung up) or the job was
         * cancelled first. Whichever terminal cause happens first wins: a
         * cancellation that follows a recorded lane failure is its consequence,
         * never a competing classification.
         */
        private static final class Pump {
            private final FabricCompileItem item;
            private final FastLaneChannel lane;
            private final AtomicReference<IOException> laneFailure = new AtomicReference<>();
            private final AtomicBoolean cancelled = new AtomicBoolean();
            private final AtomicBoolean responseComplete = new AtomicBoolean();

            Pump(FabricCompileItem item, FastLaneChannel lane) {
                this.item = item;
                this.lane = lane;
            }

            void requestDirection() {
                byte[] buffer = new byte[BUFFER_BYTES];
                OutputStream laneOutput = lane.outputStream();
                InputStream request = item.request();
                try {
                    int read;
                    while ((read = request.read(buffer)) >= 0) {
                        try {
                            laneOutput.write(buffer, 0, read);
                            laneOutput.flush();
                        } catch (IOException laneSide) {
                            recordLaneFailure(laneSide);
                            return;
                        }
                    }
                    // Request EOF: the distcc half-close — the bridge closes its
                    // channel output there too. The response stays readable.
                    try {
                        laneOutput.close();
                    } catch (IOException ignored) {
                        // Best-effort half-close; the answer phase decides.
                    }
                } catch (IOException itemSide) {
                    // The job's client went away (or the submitter's pipe died):
                    // not a channel failure, but end both directions.
                    cancel();
                } finally {
                    closeQuietly(request);
                }
            }

            void responseDirection() {
                byte[] buffer = new byte[BUFFER_BYTES];
                OutputStream response = item.response();
                try {
                    InputStream laneInput = lane.inputStream();
                    int read;
                    while ((read = laneInput.read(buffer)) >= 0) {
                        try {
                            response.write(buffer, 0, read);
                            response.flush();
                        } catch (IOException itemSide) {
                            // The job's client went away: end both directions.
                            cancel();
                            return;
                        }
                    }
                    // Lane EOF: the face's distccd finished its answer.
                    responseComplete.set(true);
                } catch (IOException laneSide) {
                    recordLaneFailure(laneSide);
                } finally {
                    closeQuietly(response);
                }
            }

            /** The recorded lane-side failure, if any direction recorded one. */
            IOException laneFailure() {
                return laneFailure.get();
            }

            /** The job is dead from the item's side: end both directions. */
            void cancel() {
                if (laneFailure.get() != null) {
                    // A lane failure already owns the terminal outcome; this
                    // cancellation is its consequence, not a classification.
                    closeQuietly(item.request());
                    closeQuietly(item.response());
                    return;
                }
                cancelled.set(true);
                closeQuietly(item.request());
                closeQuietly(item.response());
                closeQuietly(lane);
            }

            private void recordLaneFailure(IOException failure) {
                if (cancelled.get() || responseComplete.get()) {
                    return; // the job was already terminal from the item side
                }
                laneFailure.compareAndSet(null, failure);
                closeQuietly(item.request());
                closeQuietly(item.response());
                closeQuietly(lane);
            }

            private static void closeQuietly(java.io.Closeable closeable) {
                try {
                    closeable.close();
                } catch (IOException ignored) {
                    // Best-effort teardown; the terminal outcome is already set.
                }
            }
        }

        private static void closeQuietly(FastLaneChannel channel) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Best-effort teardown; the job's outcome is already classified.
            }
        }
    }
}
