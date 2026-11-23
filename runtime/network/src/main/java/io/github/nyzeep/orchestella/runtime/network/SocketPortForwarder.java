package io.github.nyzeep.orchestella.runtime.network;

import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM-level live TCP socket port forwarder implementing {@link PortForwardingEngine}.
 *
 * <p>Serves dynamic port forwarding rules by opening host-side {@link ServerSocket} listeners
 * and relaying bidirectional TCP byte streams to target guest endpoints.
 * Hot-removal immediately unbinds and closes the host port (Ticket 29 AC-1, AC-2).
 *
 * <p>Failures are reported to an optional {@link PortForwardingObserver}. Reporting must never
 * change forwarding behaviour: every callback is insulated ({@link #notify(Runnable)}), and
 * repeats of the same {@code ruleId + event + cause} class are throttled to at most one event
 * per second ({@value #THROTTLE_NANOS} ns) while a {@link PortForwardingObserver#onSummary} at
 * {@link #close()} accounts for the suppressed ones. Nothing is reported per connection or per
 * relayed buffer.
 */
public final class SocketPortForwarder implements PortForwardingEngine, Closeable {
    @FunctionalInterface
    public interface GuestConnector {
        Socket connect(PortForwardingRule rule) throws IOException;
    }

    private static final long THROTTLE_NANOS = 1_000_000_000L;
    private static final long INITIAL_BACKOFF_MILLIS = 50L;
    private static final long MAX_BACKOFF_MILLIS = 1000L;
    private static final int MAX_CONSECUTIVE_ACCEPT_FAILURES = 10;
    private static final int RELAY_BUFFER_BYTES = 4096;

    /** Per-rule totals reported once at {@link #close()}. */
    private static final class Counters {
        final AtomicLong suppressed = new AtomicLong();
        final AtomicLong failures = new AtomicLong();
    }

    private final Map<String, ActiveListener> listeners = new ConcurrentHashMap<>();
    private final Map<String, Counters> counters = new ConcurrentHashMap<>();
    /** One slot per throttle key: {@code [0] = last emit nanoTime} ({@code Long.MIN_VALUE} = never). */
    private final Map<String, long[]> throttle = new ConcurrentHashMap<>();
    private final PortForwardingObserver observer;
    private final GuestConnector connector;
    private final Semaphore connectionSlots;
    private final int idleTimeoutMillis;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "orchestella-port-forwarder");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;
    private volatile boolean summaryEmitted;

    private static final class ActiveListener {
        final ServerSocket serverSocket;
        final Set<Socket> clients = ConcurrentHashMap.newKeySet();
        volatile PortForwardingRule rule;

        ActiveListener(ServerSocket serverSocket, PortForwardingRule rule) {
            this.serverSocket = serverSocket;
            this.rule = rule;
        }

        void close() {
            closeQuietly(serverSocket);
            for (Socket client : clients) closeQuietly(client);
        }
    }

    public SocketPortForwarder() {
        this(PortForwardingObserver.NOOP);
    }

    /** Reports forwarding failures to {@code observer}; {@code null} behaves like NOOP. */
    public SocketPortForwarder(PortForwardingObserver observer) {
        this(rule -> new Socket(PortForwardingRule.LOOPBACK_ADDRESS, rule.guestPort()),
                Integer.MAX_VALUE, 0, observer);
    }

    public SocketPortForwarder(GuestConnector connector, int maxConnections, int idleTimeoutMillis) {
        this(connector, maxConnections, idleTimeoutMillis, PortForwardingObserver.NOOP);
    }

    private SocketPortForwarder(GuestConnector connector, int maxConnections, int idleTimeoutMillis,
            PortForwardingObserver observer) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.connectionSlots = new Semaphore(maxConnections);
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.observer = observer == null ? PortForwardingObserver.NOOP : observer;
    }

    @Override
    public synchronized void addRule(PortForwardingRule rule) throws IOException {
        Objects.requireNonNull(rule, "rule");
        validateRule(rule);
        if (!running) {
            throw new IOException("SocketPortForwarder is closed");
        }

        ActiveListener previous = listeners.get(rule.id());
        if (previous != null && !previous.serverSocket.isClosed()
                && previous.rule.hasSameListener(rule)) {
            previous.rule = rule;
            notify(() -> observer.onListenerReplaced(rule.id(), rule.hostPort()));
            return;
        }

        // Keep an existing listener usable until the new endpoint actually binds.
        InetAddress bindAddr = InetAddress.getByName(rule.bindAddress());
        ServerSocket serverSocket = new ServerSocket();
        try {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindAddr, rule.hostPort()), 50);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(serverSocket);
            throw failure;
        }
        ActiveListener listener = new ActiveListener(serverSocket, rule);
        listeners.put(rule.id(), listener);
        counters.computeIfAbsent(rule.id(), id -> new Counters());
        if (previous != null) {
            previous.close();
            notify(() -> observer.onListenerReplaced(rule.id(), rule.hostPort()));
        }

        executor.submit(() -> acceptLoop(listener));
    }

    @Override
    public synchronized void removeRule(PortForwardingRule rule) {
        Objects.requireNonNull(rule, "rule");
        ActiveListener listener = listeners.get(rule.id());
        if (listener != null && listener.rule.equals(rule) && listeners.remove(rule.id(), listener)) {
            listener.close();
        }
    }

    public synchronized void replaceRule(PortForwardingRule previous, PortForwardingRule replacement)
            throws IOException {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(replacement, "replacement");
        validateRule(replacement);
        if (!previous.id().equals(replacement.id()) || !isApplied(previous)) {
            throw new IOException("the previous forwarding listener is not active");
        }
        addRule(replacement);
    }

    private static void validateRule(PortForwardingRule rule) throws IOException {
        if (rule.protocol() != Protocol.TCP) {
            throw new IOException("SocketPortForwarder supports TCP forwarding rules only");
        }
        if (PortForwardingRule.isReservedHostPort(rule.hostPort())) {
            throw new IOException("host port is reserved for the compiler bridge");
        }
    }

    public boolean isApplied(PortForwardingRule rule) {
        ActiveListener active = listeners.get(rule.id());
        return running && active != null && !active.serverSocket.isClosed() && active.rule.equals(rule);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void close() {
        running = false;
        for (ActiveListener listener : listeners.values()) {
            listener.close();
        }
        listeners.clear();
        executor.shutdownNow();
        if (!summaryEmitted) {
            summaryEmitted = true;
            for (Map.Entry<String, Counters> entry : counters.entrySet()) {
                String ruleId = entry.getKey();
                Counters ruleCounters = entry.getValue();
                long suppressedEvents = ruleCounters.suppressed.get();
                long failures = ruleCounters.failures.get();
                notify(() -> observer.onSummary(ruleId, suppressedEvents, failures));
            }
            throttle.clear();
        }
    }

    private void acceptLoop(ActiveListener listener) {
        int consecutiveFailures = 0;
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
        while (running && !listener.serverSocket.isClosed()) {
            try {
                Socket clientSocket = listener.serverSocket.accept();
                consecutiveFailures = 0;
                backoffMillis = INITIAL_BACKOFF_MILLIS;
                if (!connectionSlots.tryAcquire()) {
                    closeQuietly(clientSocket);
                    continue;
                }
                listener.clients.add(clientSocket);
                if (listener.serverSocket.isClosed()) {
                    releaseClient(listener, clientSocket);
                    continue;
                }
                try {
                    executor.submit(() -> handleClient(clientSocket, listener, listener.rule));
                } catch (RejectedExecutionException stopping) {
                    releaseClient(listener, clientSocket);
                }
            } catch (SocketException closedByUs) {
                // Hot removal closes the server socket: accept fails with SocketException on a
                // socket we just closed. That is the normal path (AC-2) and stays silent.
                break;
            } catch (IOException failure) {
                if (listener.serverSocket.isClosed()) {
                    break;
                }
                consecutiveFailures++;
                boolean fatal = consecutiveFailures >= MAX_CONSECUTIVE_ACCEPT_FAILURES;
                recordFailure(listener.rule.id());
                // A fatal event must always be delivered, so throttling only applies otherwise.
                if (fatal || shouldEmit(listener.rule.id(), "accept", failure)) {
                    notify(() -> observer.onAcceptFailure(
                            listener.rule.id(), listener.rule.hostPort(), failure, fatal));
                }
                if (fatal) {
                    // Ten consecutive accept failures mean the listener cannot recover; closing it
                    // ends the busy loop that used to burn a core forever.
                    listener.close();
                    listeners.remove(listener.rule.id(), listener);
                    break;
                }
                if (!backoff(backoffMillis)) {
                    break;
                }
                backoffMillis = Math.min(backoffMillis * 2, MAX_BACKOFF_MILLIS);
            }
        }
    }

    /** Sleeps between accept retries; returns false when the thread was asked to stop. */
    private static boolean backoff(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void handleClient(Socket clientSocket, ActiveListener listener, PortForwardingRule rule) {
        try (clientSocket) {
            try (Socket targetSocket = connector.connect(rule)) {
                clientSocket.setSoTimeout(idleTimeoutMillis);
                targetSocket.setSoTimeout(idleTimeoutMillis);
                Thread upload = new Thread(
                        () -> relay(clientSocket, targetSocket, rule, "c2s"), "forward-c2s");
                upload.setDaemon(true);
                upload.start();
                relay(targetSocket, clientSocket, rule, "s2c");
                upload.join();
            }
        } catch (IOException failure) {
            // Guest service not listening/refused: per-connection failure, never listener-fatal.
            recordFailure(rule.id());
            if (shouldEmit(rule.id(), "client", failure)) {
                notify(() -> observer.onClientFailure(rule.id(), rule.guestPort(), failure));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            // A crash is not a network condition: report it, then let the pool see it.
            recordFailure(rule.id());
            IOException reported = new IOException("client-handler-crashed", failure);
            if (shouldEmit(rule.id(), "client-crash", reported)) {
                notify(() -> observer.onClientFailure(rule.id(), rule.guestPort(), reported));
            }
            throw failure;
        } finally {
            releaseClient(listener, clientSocket);
        }
    }

    private void releaseClient(ActiveListener listener, Socket client) {
        closeQuietly(client);
        listener.clients.remove(client);
        connectionSlots.release();
    }

    private static void closeQuietly(Closeable resource) {
        try {
            resource.close();
        } catch (IOException ignored) {
            // Continue closing other owned sockets.
        }
    }

    /**
     * Copies bytes one way. A local close or a clean peer EOF ends the relay silently; any other
     * {@link IOException} is recorded under a throttled key instead of being dropped.
     */
    private void relay(Socket source, Socket dest, PortForwardingRule rule, String direction) {
        try {
            InputStream in = source.getInputStream();
            OutputStream out = dest.getOutputStream();
            byte[] buf = new byte[RELAY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
            dest.shutdownOutput();
        } catch (IOException failure) {
            if (running && !source.isClosed() && !dest.isClosed()) {
                recordFailure(rule.id());
                if (shouldEmit(rule.id(), "relay-" + direction, failure)) {
                    notify(() -> observer.onRelayFailure(rule.id(), direction, failure));
                }
            }
            closeQuietly(source);
            closeQuietly(dest);
        }
    }

    private void recordFailure(String ruleId) {
        counters.computeIfAbsent(ruleId, id -> new Counters()).failures.incrementAndGet();
    }

    /**
     * Returns true when this failure key has not been reported within the throttle window; the
     * first occurrence of a key always passes. Throttled occurrences are counted for the summary.
     */
    private boolean shouldEmit(String ruleId, String eventKind, IOException cause) {
        String key = ruleId + "|" + eventKind + "|" + cause.getClass().getSimpleName();
        long[] slot = throttle.computeIfAbsent(key, unused -> new long[] {Long.MIN_VALUE});
        long now = System.nanoTime();
        synchronized (slot) {
            long last = slot[0];
            if (last != Long.MIN_VALUE && now - last < THROTTLE_NANOS) {
                counters.computeIfAbsent(ruleId, id -> new Counters()).suppressed.incrementAndGet();
                return false;
            }
            slot[0] = now;
            return true;
        }
    }

    /**
     * The only place an observer is called. An observer that throws must never break the accept
     * loop or a relay, so {@link RuntimeException} is swallowed here and nowhere else.
     */
    private void notify(Runnable event) {
        try {
            event.run();
        } catch (RuntimeException ignored) {
            // Best-effort diagnostics: a broken observer cannot be allowed to stop forwarding.
        }
    }
}
