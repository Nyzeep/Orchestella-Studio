package io.github.nyzeep.orchestella;

import android.net.LocalSocket;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Named owner of one session's control-channel two-phase protocol: lazy
 * connect, bounded recovery of a failed transport, exclusive serving claim
 * and control-side retirement. Extracted verbatim from
 * {@link QemuInstanceLauncher.Session}; the session keeps the shell and its
 * public API, this object owns the protocol and the monitor that guards it.
 *
 * <p><strong>Lock contract (ADR-0020 section 7 discipline).</strong> This
 * object owns exactly one monitor, {@code controlLock}, guarding exactly the
 * control-plane state ({@code controlSocket}, {@code controlClosed},
 * {@code bridgeClient}, {@code isBridgeServing}). It never acquires the
 * terminal monitor, the owning session's monitor, a supervisor lock or a
 * {@link PersistentTerminalSessionPool} transition lock. The load-bearing
 * clause lives in {@link #recoverControlChannel()}: the failed reader is
 * joined with <em>no monitor held</em>, so a caller that owns any other
 * monitor can never deadlock against this protocol through it. That clause
 * is pinned by the contract tests in
 * {@code ControlChannelLifecycleContractTest} — above all
 * {@code recoverJoinsTheReaderOutsideTheControlMonitor}, which probes the
 * control monitor from another thread while a recovery is parked in the join —
 * not by field choreography.
 */
final class ControlChannelLifecycle {
    /** Opens one abstract-socket channel by name (bounded retry, interrupt-translating). */
    interface ChannelConnector {
        LocalSocket connect(String socketName) throws IOException;
    }

    /** Pool-backed liveness probe of the owning session's own terminal holder. */
    interface TerminalPresence {
        boolean hasLiveTerminal();
    }

    private final Object controlLock = new Object();
    private final String controlSocketName;
    private final ChannelConnector connector;
    private final TerminalPresence terminalPresence;
    private LocalSocket controlSocket;
    private boolean controlClosed;
    private BridgeClient bridgeClient;
    private final AtomicBoolean isBridgeServing = new AtomicBoolean(false);

    ControlChannelLifecycle(String controlSocketName, ChannelConnector connector,
            TerminalPresence terminalPresence) {
        this.controlSocketName = controlSocketName;
        this.connector = connector;
        this.terminalPresence = terminalPresence;
    }

    /**
     * The cached control client, connecting lazily on first use. Null when the
     * channel is retired or the transport could not be opened; a failed open
     * keeps the cache empty so a later call retries instead of serving a
     * broken client.
     */
    BridgeClient bridgeClient() {
        synchronized (controlLock) {
            if (controlClosed) return null;
            if (bridgeClient == null) {
                try {
                    bridgeClient = new BridgeClient(controlOutput(), controlInput());
                } catch (IOException e) {
                    return null;
                }
            }
            return bridgeClient;
        }
    }

    /** The control stream the host writes; connects the socket lazily. */
    OutputStream controlInput() throws IOException {
        synchronized (controlLock) {
            return control().getOutputStream();
        }
    }

    /** The control stream the host reads; connects the socket lazily. */
    InputStream controlOutput() throws IOException {
        synchronized (controlLock) {
            return control().getInputStream();
        }
    }

    /**
     * Retires a failed control transport after its reader has ended, without
     * affecting the VM or the terminal. Call once on a worker thread for an
     * explicit UI re-entry. Three phases:
     *
     * <ol>
     * <li><strong>Snapshot</strong> (control monitor held): the cached client
     * is snapshotted and the retirement predicates are evaluated — a retired
     * channel, an empty cache, a transport that has not failed, or a terminal
     * holder that is missing, foreign or dead all reject without touching
     * anything.</li>
     * <li><strong>Join</strong> (no monitor held — the interface contract):
     * wait, bounded, for the failed reader thread to end. Never hold a session
     * lock while joining the old reader; the failed transport must be fully
     * retired before another can read. Interruption preserves the interrupt
     * flag and the cache.</li>
     * <li><strong>Re-check and retire</strong> (control monitor held): the
     * predicates are re-evaluated against current state and the snapshot is
     * compared against the current cache ({@code bridgeClient != failedClient})
     * so a concurrent replacement is never clobbered; only then is the control
     * socket closed, the cache cleared and the serving claim reset so a fresh
     * owner can claim.</li>
     * </ol>
     *
     * @return true means a fresh control client can be obtained and must
     *         authenticate again; it does not mean control is ready.
     */
    boolean recoverControlChannel() {
        BridgeClient failedClient;
        synchronized (controlLock) {
            failedClient = bridgeClient;
            if (controlClosed || failedClient == null
                    || !"failed".equals(failedClient.readinessState()) || !terminalPresence.hasLiveTerminal()) {
                return false;
            }
        }
        // Never hold a session lock while joining the old reader. The
        // failed transport must be fully retired before another can read.
        try {
            if (!failedClient.awaitDisconnectedReader(5000L)) return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        synchronized (controlLock) {
            if (controlClosed || bridgeClient != failedClient
                    || !"failed".equals(failedClient.readinessState()) || !terminalPresence.hasLiveTerminal()) {
                return false;
            }
            if (controlSocket != null) {
                try {
                    controlSocket.close();
                } catch (IOException unavailable) {
                    return false;
                }
                controlSocket = null;
            }
            bridgeClient = null;
            isBridgeServing.set(false);
            return true;
        }
    }

    /**
     * Atomically marks that a bridge serving thread has started. True means
     * the caller runs the loop; a retired channel or a failed transport
     * rejects the claim without consuming it.
     */
    boolean markBridgeServing() {
        synchronized (controlLock) {
            if (controlClosed || (bridgeClient != null
                    && "failed".equals(bridgeClient.readinessState()))) return false;
            return isBridgeServing.compareAndSet(false, true);
        }
    }

    /**
     * Control-side retirement, run from the supervisor-owned channel cleanup:
     * the channel stops serving connects and the socket is closed once, under
     * the control monitor. The terminal side of retirement is the owning
     * session's, under its own monitor.
     */
    void retireControlChannel() {
        synchronized (controlLock) {
            controlClosed = true;
            if (controlSocket != null) {
                closeQuietly(controlSocket);
                controlSocket = null;
            }
        }
    }

    private LocalSocket control() throws IOException {
        synchronized (controlLock) {
            if (controlClosed) throw new IOException("Control channel is closed");
            if (controlSocket == null) {
                controlSocket = connector.connect(controlSocketName);
            }
            return controlSocket;
        }
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // channel cleanup is best-effort at teardown
        }
    }

    // ------------------------------------------------------------------
    // Test seams. Package-private on purpose: the recovery contract tests
    // construct intermediate cache states the public API cannot reach (a
    // failed transport whose reader has not exited, a mid-handshake client)
    // and read the cache without triggering lazy creation. They carry no
    // production caller and change no production path.
    // ------------------------------------------------------------------

    /** Test seam: the raw cached client without lazy creation. */
    BridgeClient cachedClientForTest() {
        synchronized (controlLock) {
            return bridgeClient;
        }
    }

    /**
     * Test seam: install a cached client directly. Constructs the intermediate
     * cache states the public API cannot reach — a failed transport whose
     * reader has not exited, a client mid-handshake — exactly the states
     * production code assigns internally. Never called by production code.
     */
    void installClientForTest(BridgeClient client) {
        synchronized (controlLock) {
            bridgeClient = client;
        }
    }
}
