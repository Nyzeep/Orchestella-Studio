package io.github.nyzeep.orchestella.runtime.contract.services;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded TCP byte relay to the exact IPv4 loopback target. Owns listeners and connections. */
public final class LocalTcpRelay implements AutoCloseable {
    public record Binding(String bindAddress, int port, Instant expiresAt) { }
    /** Authentication attests the upstream service, not authentication supplied by this byte relay. */
    public record LanLease(boolean userAuthorized, boolean authenticationConfigured, Instant deadline) { }
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "orchestella-relay-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> expiration;
    private long expiresNanos;
    private final int targetPort;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final ExecutorService pumps = new java.util.concurrent.ThreadPoolExecutor(32, 32, 0,
            TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "orchestella-local-relay-copy");
                thread.setDaemon(true);
                return thread;
            });
    private volatile ServerSocket listener;
    private Binding binding;
    private boolean closed;
    private String failure;

    public LocalTcpRelay(int targetPort) {
        if (targetPort < 1 || targetPort > 65535) {
            throw new IllegalArgumentException("Invalid loopback target port");
        }
        this.targetPort = targetPort;
    }

    public synchronized Binding startLoopback(int port) throws IOException {
        requireOpen();
        if (listener != null) {
            if ("127.0.0.1".equals(binding.bindAddress()) && (port == 0 || port == binding.port())) {
                return binding;
            }
            throw new IllegalStateException("Relay already listening");
        }
        return bind("127.0.0.1", port, null);
    }

    /** Never persisted; each bind requires a new explicit, at most ten-minute lease. */
    public synchronized Binding startLan(int port, LanLease lease) throws IOException {
        requireOpen();
        Objects.requireNonNull(lease, "lease");
        if (!lease.userAuthorized() || !lease.authenticationConfigured()) {
            throw new SecurityException("LAN requires user authorization and authenticated upstream");
        }
        Objects.requireNonNull(lease.deadline(), "deadline");
        Duration remaining = Duration.between(Instant.now(), lease.deadline());
        if (remaining.isNegative() || remaining.isZero() || remaining.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("LAN lease must expire within ten minutes");
        }
        if (listener != null) { throw new IllegalStateException("Relay already listening"); }
        expiresNanos = System.nanoTime() + remaining.toNanos();
        Binding result = bind("0.0.0.0", port, lease.deadline());
        long nanos = expiresNanos - System.nanoTime();
        if (nanos <= 0 || !Instant.now().isBefore(lease.deadline())) {
            stop();
            throw new IllegalArgumentException("LAN lease expired during bind");
        }
        ServerSocket owner = listener;
        expiration = deadlines.schedule(() -> {
            synchronized (this) {
                if (listener == owner) {
                    try { stop(); } catch (IOException error) { failure = "listener-close-failed"; }
                }
            }
        }, nanos, TimeUnit.NANOSECONDS);
        return result;
    }

    private Binding bind(String address, int port, Instant expiresAt) throws IOException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid listener port");
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getByName(address), port), 16);
        } catch (IOException error) {
            socket.close();
            failure = "listener-bind-failed";
            throw new IOException("Relay listener bind failed", error);
        }
        failure = null;
        listener = socket;
        binding = new Binding(address, socket.getLocalPort(), expiresAt);
        Thread acceptor = new Thread(() -> accept(socket), "orchestella-local-relay-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return binding;
    }

    public synchronized Binding binding() {
        if (binding != null && binding.expiresAt() != null && System.nanoTime() - expiresNanos >= 0) {
            try { stop(); } catch (IOException error) { failure = "listener-close-failed"; }
        }
        return binding;
    }
    public synchronized String failure() { return failure; }

    public synchronized void stop() throws IOException {
        if (expiration != null) { expiration.cancel(false); expiration = null; }
        ServerSocket old = listener;
        listener = null;
        binding = null;
        IOException failureToClose = null;
        if (old != null) {
            try { old.close(); } catch (IOException error) { failureToClose = error; }
        }
        for (Connection connection : connections) {
            connection.close();
        }
        if (failureToClose != null) {
            failure = "listener-close-failed";
            throw failureToClose;
        }
    }

    @Override public synchronized void close() throws IOException {
        closed = true;
        try { stop(); } finally { pumps.shutdownNow(); deadlines.shutdownNow(); }
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Relay is closed"); }
    }

    private void accept(ServerSocket owner) {
        while (listener == owner) {
            Socket client = null;
            Socket backend = null;
            try {
                client = owner.accept();
                if (connections.size() >= 16) {
                    client.close();
                    continue;
                }
                backend = new Socket();
                backend.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetPort), 750);
                client.setSoTimeout(500);
                backend.setSoTimeout(500);
                client.setTcpNoDelay(true);
                backend.setTcpNoDelay(true);
                Connection connection = new Connection(owner, client, backend);
                synchronized (this) {
                    if (binding() == null || listener != owner) {
                        connection.close();
                        continue;
                    }
                    connections.add(connection);
                    try {
                        pumps.execute(() -> copy(connection, connection.client, connection.backend));
                        pumps.execute(() -> copy(connection, connection.backend, connection.client));
                    } catch (java.util.concurrent.RejectedExecutionException busy) {
                        connection.close();
                    }
                }
            } catch (IOException | RuntimeException error) {
                closeSocket(client);
                closeSocket(backend);
                if (owner.isClosed()) { break; }
                if (client == null) {
                    synchronized (this) {
                        if (listener == owner) {
                            failure = "listener-accept-failed";
                            try { stop(); } catch (IOException ignored) { failure = "listener-close-failed"; }
                        }
                    }
                    break;
                }
                // A failed upstream closes this connection; it never queues or retries data.
            }
        }
    }

    private void copy(Connection connection, Socket from, Socket to) {
        long lastData = System.nanoTime();
        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();
            byte[] buffer = new byte[32 * 1024];
            while (listener == connection.owner && !from.isClosed()) {
                final int count;
                try { count = input.read(buffer); }
                catch (SocketTimeoutException timeout) {
                    if (System.nanoTime() - lastData > 30_000_000_000L) {
                        connection.close();
                        break;
                    }
                    continue;
                }
                if (count < 0) { to.shutdownOutput(); break; }
                if (count > 0) {
                    output.write(buffer, 0, count);
                    lastData = System.nanoTime();
                }
            }
        } catch (IOException error) {
            connection.close();
        } finally {
            if (connection.remaining.decrementAndGet() == 0) { connection.close(); }
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { /* Socket is already unusable. */ }
        }
    }

    private final class Connection {
        private final ServerSocket owner;
        private final Socket client;
        private final Socket backend;
        private final AtomicInteger remaining = new AtomicInteger(2);
        private Connection(ServerSocket owner, Socket client, Socket backend) {
            this.owner = owner;
            this.client = client;
            this.backend = backend;
        }
        private void close() {
            closeSocket(client);
            closeSocket(backend);
            connections.remove(this);
        }
    }
}
