package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
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

/**
 * Loopback TCP to vsock byte bridge for the distcc cross-lane path.
 *
 * <p>The QEMU guest runs {@code distcc} against {@code 127.0.0.1:<port>}; each
 * accepted TCP connection opens one Fast Lane vsock channel and splices bytes
 * in both directions. This class never parses the distcc protocol; the guest
 * payload runs {@code distccd --inetd} on the other end.
 *
 * <p>Security: binding is restricted to a loopback address. The bridge is not
 * a LAN port-forward and has no authentication of its own; the per-VM token
 * lives in the payload/control channel, not in this byte pipe.
 */
public final class DistccTcpVsockBridge implements Closeable {
    public interface ChannelFactory {
        FastLaneChannel open() throws FastLaneException, IOException;
    }

    private static final int BUFFER_BYTES = 16 * 1024;

    private final InetAddress bindAddress;
    private final int requestedPort;
    private final ChannelFactory channelFactory;
    private final int maxConnections;
    private final int socketReadTimeoutMs;
    private final Object lock = new Object();
    private final Set<Connection> active = new HashSet<>();

    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;
    private int boundPort = -1;

    public DistccTcpVsockBridge(
            InetAddress bindAddress,
            int requestedPort,
            ChannelFactory channelFactory,
            int maxConnections,
            int socketReadTimeoutMs) {
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
        Thread thread;
        ServerSocket socket;
        synchronized (this) {
            if (!running && server == null) {
                return;
            }
            running = false;
            socket = server;
            server = null;
            thread = acceptThread;
            acceptThread = null;
        }
        closeQuietly(socket);
        if (thread != null) {
            thread.interrupt();
        }
        synchronized (lock) {
            closing = new ArrayList<>(active);
            active.clear();
        }
        for (Connection connection : closing) {
            connection.close();
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
                    continue;
                }
            }
            Connection connection = new Connection(client);
            synchronized (lock) {
                if (!running) {
                    connection.close();
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

    private final class Connection implements Closeable {
        private final Socket client;
        private final Object closeLock = new Object();
        private FastLaneChannel channel;
        private Thread worker;
        private boolean closed;

        private Connection(Socket client) {
            this.client = client;
        }

        private void run() {
            try {
                if (socketReadTimeoutMs > 0) {
                    client.setSoTimeout(socketReadTimeoutMs);
                }
                channel = channelFactory.open();
                Thread upstream = new Thread(
                        () -> pump(channel.inputStream(), outputQuietly(client), true),
                        "fastlane-distcc-up");
                upstream.setDaemon(true);
                upstream.start();
                pump(inputQuietly(client), channel.outputStream(), false);
            } catch (FastLaneException | IOException | RuntimeException failure) {
                // One failed connection must not stop the listener.
            } finally {
                close();
            }
        }

        private void pump(InputStream input, OutputStream output, boolean closeOutput) {
            byte[] buffer = new byte[BUFFER_BYTES];
            try {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    output.flush();
                }
            } catch (IOException failure) {
                // Peer closed or the bridge is stopping.
            } finally {
                if (closeOutput) {
                    closeQuietly(output);
                }
                close();
            }
        }

        @Override
        public void close() {
            synchronized (closeLock) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            closeQuietly(client);
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // Best-effort: the payload/VM owner is authoritative.
                }
            }
            synchronized (lock) {
                active.remove(this);
            }
            Thread thread = worker;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt();
            }
        }
    }

    private static InputStream inputQuietly(Socket socket) {
        try {
            return socket.getInputStream();
        } catch (IOException failure) {
            return InputStream.nullInputStream();
        }
    }

    private static OutputStream outputQuietly(Socket socket) {
        try {
            return socket.getOutputStream();
        } catch (IOException failure) {
            return OutputStream.nullOutputStream();
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
