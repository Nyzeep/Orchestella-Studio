package io.github.nyzeep.orchestella.runtime.network;

import java.io.IOException;

/**
 * Optional diagnostic sink for {@link SocketPortForwarder} failures.
 *
 * <p>Every method has a {@code default} empty implementation, so an implementation may
 * subscribe to exactly the events it cares about (the same philosophy as
 * {@code download.DownloadProgressCallback}). {@link #NOOP} is a ready-made sink for
 * callers that need no diagnostics.
 *
 * <p><strong>Observer callbacks are best-effort only.</strong> The forwarder invokes them
 * from its accept loop and relay threads; an observer that throws must never break the
 * accept loop or a relay. {@link SocketPortForwarder} therefore insulates every callback
 * and swallows {@link RuntimeException}. Implementations must not block: callbacks run on
 * forwarding threads and may be suppressed by the per-key throttle.
 */
public interface PortForwardingObserver {

    /** Sink that discards every event. */
    PortForwardingObserver NOOP = new PortForwardingObserver() {};

    /**
     * The listener could not accept a connection, or it was stopped because it kept failing.
     *
     * @param ruleId rule whose listener failed
     * @param hostPort host port the listener was bound to
     * @param cause the accept failure
     * @param fatal {@code true} when the listener gave up and closed itself
     */
    default void onAcceptFailure(String ruleId, int hostPort, IOException cause, boolean fatal) {}

    /**
     * A client connection could not be relayed to {@code 127.0.0.1}:{@code guestPort}
     * (guest service not listening, refused, or the handler crashed).
     *
     * @param ruleId rule that accepted the client
     * @param guestPort target guest port
     * @param cause the failure
     */
    default void onClientFailure(String ruleId, int guestPort, IOException cause) {}

    /**
     * One relay direction ended abnormally: not a clean peer EOF and not a local close.
     *
     * @param ruleId rule that owns the connection
     * @param direction {@code "c2s"} (client to guest) or {@code "s2c"} (guest to client)
     * @param cause the failure
     */
    default void onRelayFailure(String ruleId, String direction, IOException cause) {}

    /**
     * {@code addRule} replaced an already active listener with the same id; the previous
     * listener was closed so its socket cannot leak.
     *
     * @param ruleId replaced rule id
     * @param hostPort host port of the new listener
     */
    default void onListenerReplaced(String ruleId, int hostPort) {}

    /**
     * Emitted once per rule when the forwarder is closed, so suppressed duplicates stay
     * accountable without logging every occurrence.
     *
     * @param ruleId rule the counters belong to
     * @param suppressedEvents events dropped by throttling
     * @param failures failures observed for that rule
     */
    default void onSummary(String ruleId, long suppressedEvents, long failures) {}
}
