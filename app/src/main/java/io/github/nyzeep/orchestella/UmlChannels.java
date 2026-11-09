package io.github.nyzeep.orchestella;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * C lane: the {@link UmlChannel} factory — the one place that builds channels
 * and the one place that adapts a channel onto the launcher's
 * {@link UmlFaceLauncher.Channel} seam, so the device window wires the face
 * through the D-1 seam, never around it.
 *
 * <p>What exists today vs. what waits for the ACR: {@link #stub()} is the
 * recording stub (tests, wiring rehearsals — no network, no bridge); the real
 * distcc-over-TCP channel is the ACR-bound D-1 bridge change
 * ({@code DistccTcpVsockBridge} lane-marker channel) and is deliberately
 * absent here — a factory method for it would be a placeholder pretending a
 * transport exists. When the ACR lands the bridge change, its factory method
 * comes with it.
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
}
