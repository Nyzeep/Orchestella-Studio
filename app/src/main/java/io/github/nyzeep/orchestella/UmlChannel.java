package io.github.nyzeep.orchestella;

/**
 * C lane: the UML face ↔ host compile channel seam (fabric-route doc D-1).
 * Interface-first: the real channel — distcc over the fabric's TCP access
 * point on the UML face — is a bridge change that goes through the ACR before
 * it is built ({@code DistccTcpVsockBridge} gaining the lane-marker channel,
 * vsock=pVM face / TCP=UML face); this seam is the face-level contract that
 * implementation will plug into, and {@link UmlChannels#stub()} lets tests and
 * device-window wiring rehearsals run without any network or bridge change.
 *
 * <p><b>ACR boundary (naming/responsibility overlap, deliberately untouched).</b>
 * {@code FastLaneChannel} and {@code DistccTcpVsockBridge}
 * ({@code runtime/fastlane-distcc/}) are the ACR-governed marker channel: the
 * byte-transport protocol (AXP1) with lane marking (vsock=pVM, TCP=UML). This
 * interface is one layer above: the UML face's compile-execution seam — an
 * open/execute/close lifecycle with fixed refusal details — that a marked
 * channel would carry as its payload. Nothing here re-states, re-implements or
 * wraps marker semantics, and no byte under {@code runtime/fastlane-distcc}
 * moves because of this class: the D-1 wire change remains ACR-bound, and when
 * it lands, its UML-face side implements this interface over the bridge
 * instead of the launcher reaching into the bridge directly.
 *
 * <p><b>Lifecycle contract (locked by {@code UmlChannelContractTest}).</b>
 * A channel is {@link State#NEW} until {@link #open} moves it to
 * {@link State#OPEN}; {@link #close} moves it to {@link State#CLOSED} and is
 * idempotent. {@code execute} is refused with the fixed {@link #NOT_OPEN_DETAIL}
 * before {@code open} and the fixed {@link #CLOSED_DETAIL} after {@code close};
 * a second {@code open} is refused with {@link #ALREADY_OPEN_DETAIL} — a
 * closed channel does not reopen, the face's restart path builds a new one
 * (the same honesty the launcher's fixed codes live by). An execution failure
 * throws with the fixed {@link #EXECUTE_FAILED_DETAIL} and keeps the state
 * open — eviction is the orchestrator's call ({@code FabricOrchestrator}
 * evicts a face on any {@link FabricOrchestrator.FaceException}), not the
 * channel's.
 */
public interface UmlChannel extends AutoCloseable {

    /** Lifecycle states; {@link #CLOSED} is terminal — no reopen. */
    enum State { NEW, OPEN, CLOSED }

    /** Fixed refusal detail: {@code execute} before {@code open}. */
    String NOT_OPEN_DETAIL = "uml-channel-not-open";
    /** Fixed refusal detail: {@code open} on an already-open channel. */
    String ALREADY_OPEN_DETAIL = "uml-channel-already-open";
    /** Fixed refusal detail: {@code execute} after {@code close}, or reopen. */
    String CLOSED_DETAIL = "uml-channel-closed";
    /** Fixed detail: the execution itself failed; the cause carries the why. */
    String EXECUTE_FAILED_DETAIL = "uml-channel-execute-failed";

    /**
     * Brings the channel up. From {@link State#NEW} only: an already-open
     * channel answers {@link #ALREADY_OPEN_DETAIL}, a closed one
     * {@link #CLOSED_DETAIL} (no reopen).
     */
    void open() throws UmlChannelException;

    /**
     * Executes one admitted compile work item at the granted width. Only
     * valid while {@link State#OPEN}; every other state answers its fixed
     * detail. A failure throws with {@link #EXECUTE_FAILED_DETAIL} — the state
     * stays open, the orchestrator owns eviction.
     */
    void execute(int grantedWidth, FabricOrchestrator.WorkItem item) throws UmlChannelException;

    /**
     * Tears the channel down; idempotent (closing a closed channel is a
     * no-op, like the keep-alive's release semantics).
     */
    @Override void close();

    /** The current lifecycle state. */
    State state();

    /**
     * The channel's one failure type: a fixed detail code plus the cause.
     * Any failure shape (refused state, transport gone, guest dead) collapses
     * here, the way {@link FabricOrchestrator.FaceException} collapses the
     * worker-side shapes — the adapter maps one to the other.
     */
    final class UmlChannelException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String detail;

        public UmlChannelException(String detail) {
            super(detail);
            this.detail = java.util.Objects.requireNonNull(detail, "detail");
        }

        public UmlChannelException(String detail, Throwable cause) {
            super(detail, cause);
            this.detail = java.util.Objects.requireNonNull(detail, "detail");
        }

        /** The fixed detail code naming the refusal or failure class. */
        public String detail() {
            return detail;
        }
    }
}
