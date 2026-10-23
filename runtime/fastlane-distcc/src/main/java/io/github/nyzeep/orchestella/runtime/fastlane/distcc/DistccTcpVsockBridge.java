package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Exception;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loopback TCP to vsock byte bridge for the distcc cross-lane path.
 *
 * <p>The QEMU guest runs {@code distcc} against {@code 127.0.0.1:<port>}; each
 * accepted TCP connection opens one Fast Lane vsock channel and splices bytes
 * in both directions. This class never parses the distcc protocol; the guest
 * payload runs {@code distccd --inetd} on the other end.
 *
 * <p>Semantics:
 * <ul>
 *   <li>The listener binds a loopback address only.</li>
 *   <li>At most {@code maxConnections} jobs are bridged concurrently; excess
 *       connections are closed and reported as {@link Outcome#LIMIT_REJECTED}.</li>
 *   <li>A connection with no bytes in either direction for
 *       {@code idleTimeoutMs} is closed as {@link Outcome#IDLE_TIMEOUT}.
 *       Zero disables the watchdog.</li>
 *   <li>When the client half-closes its request, the channel output is closed
 *       so the payload's {@code distccd --inetd} sees stdin EOF; the response
 *       is still drained back to the client. The bridge never parses or
 *       rewrites distcc bytes.</li>
 *   <li>{@link #close()} is idempotent; after it {@link #port()} throws
 *       {@link IllegalStateException} until {@link #start()} binds again.</li>
 * </ul>
 *
 * <p>Security: this is not a LAN port-forward and has no authentication of its
 * own; the per-VM token lives in the payload/control channel, not in this byte
 * pipe. The bridge must not be exposed beyond loopback.
 *
 * <p>The optional {@link StatsSink} receives counters only (outcome, byte
 * counts, duration). It must never receive source or object bytes.
 */
public final class DistccTcpVsockBridge implements Closeable {
    public interface ChannelFactory {
        FastLaneChannel open() throws FastLaneException, IOException;
    }

    /** Terminal classification of one TCP connection. */
    public enum Outcome {
        /** Both directions reached EOF after a normal request/response. */
        COMPLETED,
        /** The channel could not be opened or failed mid-stream. */
        CHANNEL_FAILED,
        /** No bytes moved in either direction before the idle deadline. */
        IDLE_TIMEOUT,
        /** The connection limit was reached before a channel was opened. */
        LIMIT_REJECTED,
        /** The bridge was closed while the connection was active. */
        BRIDGE_CLOSED
    }

    /** Counters for one finished connection. Never carries payload bytes. */
    public static final class ConnectionStats {
        /** No failure code for a completed or non-channel outcome. */
        public static final String NO_FAILURE = "NONE";

        private final Outcome outcome;
        private final long bytesFromClient;
        private final long bytesToClient;
        private final long durationMillis;
        private final String failureCode;

        public ConnectionStats(
                Outcome outcome, long bytesFromClient, long bytesToClient, long durationMillis) {
            this(outcome, bytesFromClient, bytesToClient, durationMillis, NO_FAILURE);
        }

        public ConnectionStats(
                Outcome outcome,
                long bytesFromClient,
                long bytesToClient,
                long durationMillis,
                String failureCode) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.bytesFromClient = bytesFromClient;
            this.bytesToClient = bytesToClient;
            this.durationMillis = durationMillis;
            this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        }

        public Outcome outcome() {
            return outcome;
        }

        public long bytesFromClient() {
            return bytesFromClient;
        }

        public long bytesToClient() {
            return bytesToClient;
        }

        public long durationMillis() {
            return durationMillis;
        }

        /** Fixed AX-06 reason such as COMPILER_MISMATCH, or NONE. */
        public String failureCode() {
            return failureCode;
        }
    }

    @FunctionalInterface
    public interface StatsSink {
        void onConnectionClosed(ConnectionStats stats);
    }

    private static final int BUFFER_BYTES = 16 * 1024;
    private static final StatsSink NO_STATS = stats -> {};

    private final InetAddress bindAddress;
    private final int requestedPort;
    private final ChannelFactory channelFactory;
    private final int maxConnections;
    private final int socketReadTimeoutMs;
    private final int idleTimeoutMs;
    private final StatsSink statsSink;
    private final Object lock = new Object();
    private final Set<Connection> active = new HashSet<>();

    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;
    private Thread watchdogThread;
    private int boundPort = -1;

    /**
     * Convenience constructor: the last argument is the connection idle
     * timeout, not a socket read timeout. A distcc client normally sends one
     * request and then waits for the response, so a plain read timeout would
     * abort every real compilation.
     */
    public DistccTcpVsockBridge(
            InetAddress bindAddress,
            int requestedPort,
            ChannelFactory channelFactory,
            int maxConnections,
            int idleTimeoutMs) {
        this(bindAddress, requestedPort, channelFactory, maxConnections, 0, idleTimeoutMs, NO_STATS);
    }

    /**
     * Convenience constructor with explicit socket read and idle timeouts.
     * Pass a socket read timeout of zero for real distcc jobs: the client sends
     * one request and then waits for the response.
     */
    public DistccTcpVsockBridge(
            InetAddress bindAddress,
            int requestedPort,
            ChannelFactory channelFactory,
            int maxConnections,
            int socketReadTimeoutMs,
            int idleTimeoutMs) {
        this(
                bindAddress,
                requestedPort,
                channelFactory,
                maxConnections,
                socketReadTimeoutMs,
                idleTimeoutMs,
                NO_STATS);
    }

    public DistccTcpVsockBridge(
            InetAddress bindAddress,
            int requestedPort,
            ChannelFactory channelFactory,
            int maxConnections,
            int socketReadTimeoutMs,
            int idleTimeoutMs,
            StatsSink statsSink) {
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        if (!bindAddress.isLoopbackAddress()) {
            throw new IllegalArgumentException("distcc bridge must bind to a loopback address");
        }
        if (requestedPort < 0 || requestedPort > 65535) {
            throw new IllegalArgumentException("port must be in [0, 65535]");
        }
        this.requestedPort = requestedPort;
        this.channelFactory = Objects.requireNonNull(channelFactory, "channelFactory");
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxConnections must be positive");
        }
        this.maxConnections = maxConnections;
        if (socketReadTimeoutMs < 0) {
            throw new IllegalArgumentException("socketReadTimeoutMs must not be negative");
        }
        this.socketReadTimeoutMs = socketReadTimeoutMs;
        if (idleTimeoutMs < 0) {
            throw new IllegalArgumentException("idleTimeoutMs must not be negative");
        }
        this.idleTimeoutMs = idleTimeoutMs;
        this.statsSink = Objects.requireNonNull(statsSink, "statsSink");
    }

    /** Binds and starts the accept loop; idempotent while running. */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bindAddress, requestedPort));
        server = socket;
        boundPort = socket.getLocalPort();
        running = true;
        acceptThread = new Thread(this::acceptLoop, "fastlane-distcc-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        if (idleTimeoutMs > 0) {
            watchdogThread = new Thread(this::watchdogLoop, "fastlane-distcc-idle");
            watchdogThread.setDaemon(true);
            watchdogThread.start();
        }
    }

    public synchronized int port() {
        if (boundPort < 0) {
            throw new IllegalStateException("bridge is not started");
        }
        return boundPort;
    }

    public int activeConnections() {
        synchronized (lock) {
            return active.size();
        }
    }

    @Override
    public void close() {
        List<Connection> closing;
        Thread accept;
        Thread watchdog;
        ServerSocket socket;
        synchronized (this) {
            if (!running && server == null) {
                return;
            }
            running = false;
            socket = server;
            server = null;
            boundPort = -1;
            accept = acceptThread;
            acceptThread = null;
            watchdog = watchdogThread;
            watchdogThread = null;
        }
        closeQuietly(socket);
        interrupt(accept);
        interrupt(watchdog);
        synchronized (lock) {
            closing = new ArrayList<>(active);
        }
        for (Connection connection : closing) {
            connection.finish(Outcome.BRIDGE_CLOSED);
        }
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket socket;
            synchronized (this) {
                socket = server;
            }
            if (socket == null) {
                return;
            }
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException failure) {
                if (!running) {
                    return;
                }
                continue;
            }
            if (!running) {
                closeQuietly(client);
                return;
            }
            synchronized (lock) {
                if (active.size() >= maxConnections) {
                    closeQuietly(client);
                    publish(new ConnectionStats(Outcome.LIMIT_REJECTED, 0, 0, 0));
                    continue;
                }
            }
            Connection connection = new Connection(client);
            synchronized (lock) {
                if (!running) {
                    connection.finish(Outcome.BRIDGE_CLOSED);
                    return;
                }
                active.add(connection);
            }
            Thread worker = new Thread(connection::run, "fastlane-distcc-conn");
            worker.setDaemon(true);
            connection.worker = worker;
            worker.start();
        }
    }

    private void watchdogLoop() {
        long idleNanos = TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs);
        long sleepMillis = Math.max(1, Math.min(50, idleTimeoutMs / 4));
        while (running) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            List<Connection> snapshot;
            synchronized (lock) {
                snapshot = new ArrayList<>(active);
            }
            long now = System.nanoTime();
            for (Connection connection : snapshot) {
                if (now - connection.lastActivityNanos.get() >= idleNanos) {
                    connection.finish(Outcome.IDLE_TIMEOUT);
                }
            }
        }
    }

    private void publish(ConnectionStats stats) {
        try {
            statsSink.onConnectionClosed(stats);
        } catch (RuntimeException ignored) {
            // A broken observer must not stop the listener.
        }
    }

    private final class Connection {
        private final Socket client;
        private final long startedNanos = System.nanoTime();
        private final AtomicLong lastActivityNanos = new AtomicLong(startedNanos);
        private final AtomicLong bytesFromClient = new AtomicLong();
        private final AtomicLong bytesToClient = new AtomicLong();
        private final AtomicReference<Outcome> outcome = new AtomicReference<>();
        private final CountDownLatch bothDirectionsClosed = new CountDownLatch(2);
        private volatile FastLaneChannel channel;
        private volatile Thread worker;

        private Connection(Socket client) {
            this.client = client;
        }

        private void run() {
            try {
                if (socketReadTimeoutMs > 0) {
                    client.setSoTimeout(socketReadTimeoutMs);
                }
                channel = channelFactory.open();
                Thread upstream = new Thread(this::pumpToClient, "fastlane-distcc-up");
                upstream.setDaemon(true);
                upstream.start();
                pumpToChannel();
            } catch (FastLaneException failure) {
                finish(Outcome.CHANNEL_FAILED, failure.reason());
            } catch (IOException | RuntimeException failure) {
                finish(Outcome.CHANNEL_FAILED);
            }
        }

        private void pumpToChannel() {
            try {
                OutputStream channelOutput = channel.outputStream();
                copy(client.getInputStream(), channelOutput, bytesFromClient);
                closeQuietly(channelOutput);
            } catch (Axp1Exception failure) {
                finish(Outcome.CHANNEL_FAILED, Axp1DistccChannelFactory.reasonCode(failure.code()));
            } catch (IOException failure) {
                finish(Outcome.CHANNEL_FAILED);
            } finally {
                directionClosed();
            }
        }

        private void pumpToClient() {
            try {
                copy(channel.inputStream(), client.getOutputStream(), bytesToClient);
                shutdownClientOutput();
            } catch (Axp1Exception failure) {
                finish(Outcome.CHANNEL_FAILED, Axp1DistccChannelFactory.reasonCode(failure.code()));
            } catch (IOException failure) {
                finish(Outcome.CHANNEL_FAILED);
            } finally {
                directionClosed();
            }
        }

        private void directionClosed() {
            bothDirectionsClosed.countDown();
            if (bothDirectionsClosed.getCount() == 0) {
                finish(Outcome.COMPLETED);
            }
        }

        private void copy(InputStream input, OutputStream output, AtomicLong counter)
                throws IOException {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                output.flush();
                counter.addAndGet(read);
                lastActivityNanos.set(System.nanoTime());
            }
        }

        private void shutdownClientOutput() {
            try {
                client.shutdownOutput();
            } catch (IOException ignored) {
                // Already closed or reset by the peer.
            }
        }

        /** Idempotent terminal transition; closes both sides and reports once. */
        private void finish(Outcome terminal) {
            finish(terminal, ConnectionStats.NO_FAILURE);
        }

        private void finish(Outcome terminal, String failureCode) {
            if (!outcome.compareAndSet(null, terminal)) {
                return;
            }
            // Remove before closing the socket so a peer that observes EOF
            // never races the capacity check in the accept loop.
            synchronized (lock) {
                active.remove(this);
            }
            closeQuietly(client);
            FastLaneChannel opened = channel;
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException ignored) {
                    // Best-effort: the payload/VM owner is authoritative.
                }
            }
            Thread thread = worker;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
            publish(new ConnectionStats(
                    terminal,
                    bytesFromClient.get(),
                    bytesToClient.get(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                    failureCode));
        }
    }

    private static void interrupt(Thread thread) {
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort teardown.
        }
    }
}
