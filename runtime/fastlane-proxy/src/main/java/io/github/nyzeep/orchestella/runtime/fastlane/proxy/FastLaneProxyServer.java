package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Host-side SOCKS5/HTTP CONNECT proxy. The loopback listener is for host-local
 * clients and tests; the guest path uses {@link #serveChannel(FastLaneChannel)}
 * with one host-opened vsock channel per tunnel. DNS resolution and target
 * connections happen here, never in the guest.
 */
public final class FastLaneProxyServer implements Closeable {
    private final ProxyConfig config;
    private final TargetConnector connector;
    private final Set<ProxyTunnel> tunnels = ConcurrentHashMap.newKeySet();
    private final LongAdder upstreamBytes = new LongAdder();
    private final LongAdder downstreamBytes = new LongAdder();
    private final AtomicInteger acceptedConnections = new AtomicInteger();
    private final AtomicInteger rejectedConnections = new AtomicInteger();
    private final AtomicInteger failures = new AtomicInteger();

    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread;
    private ScheduledExecutorService watchdog;

    public FastLaneProxyServer(ProxyConfig config) {
        this(config, new DnsTargetConnector(config.allowLoopbackTargets()));
    }

    public FastLaneProxyServer(ProxyConfig config, TargetConnector connector) {
        this.config = Objects.requireNonNull(config, "config");
        this.connector = Objects.requireNonNull(connector, "connector");
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        final ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(config.bindAddress(), config.port()));
        server = socket;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "fastlane-proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "fastlane-proxy-idle");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.scheduleAtFixedRate(this::closeIdleTunnels, 1, 1, TimeUnit.SECONDS);
    }

    public int port() {
        final ServerSocket socket = server;
        return socket == null ? -1 : socket.getLocalPort();
    }

    public int activeConnections() {
        return tunnels.size();
    }

    public int acceptedConnections() {
        return acceptedConnections.get();
    }

    public int rejectedConnections() {
        return rejectedConnections.get();
    }

    public int failureCount() {
        return failures.get();
    }

    public long bytesClientToTarget() {
        return upstreamBytes.sum();
    }

    public long bytesTargetToClient() {
        return downstreamBytes.sum();
    }

    private void acceptLoop() {
        final ServerSocket socket = server;
        while (running && socket != null) {
            final Socket client;
            try {
                client = socket.accept();
            } catch (IOException failure) {
                return;
            }
            acceptedConnections.incrementAndGet();
            if (tunnels.size() >= config.maxConnections()) {
                rejectedConnections.incrementAndGet();
                closeQuietly(client);
                continue;
            }
            final Thread worker = new Thread(() -> handleSocket(client), "fastlane-proxy-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handleSocket(Socket client) {
        try {
            client.setSoTimeout(config.idleTimeoutMs());
            client.setTcpNoDelay(true);
            final PushbackInputStream input =
                    new PushbackInputStream(client.getInputStream(), 1);
            final int first = input.read();
            if (first < 0) {
                closeQuietly(client);
                return;
            }
            input.unread(first);
            final boolean socks = first == 0x05;
            final ProxyTarget target;
            try {
                target = socks
                        ? Socks5Protocol.readRequest(input, client.getOutputStream())
                        : HttpConnectProtocol.readRequest(input);
            } catch (ProxyException failure) {
                failures.incrementAndGet();
                if (!socks) {
                    writeHttpFailure(client.getOutputStream(), failure.failure());
                }
                closeQuietly(client);
                return;
            }

            final Socket targetSocket;
            try {
                targetSocket = connector.connect(
                        target.host(), target.port(), config.connectTimeoutMs());
            } catch (ProxyException failure) {
                failures.incrementAndGet();
                if (socks) {
                    writeSocksFailure(client.getOutputStream(), failure.failure());
                } else {
                    writeHttpFailure(client.getOutputStream(), failure.failure());
                }
                closeQuietly(client);
                return;
            }

            if (socks) {
                Socks5Protocol.writeSuccess(
                        client.getOutputStream(),
                        targetSocket.getLocalAddress(),
                        targetSocket.getLocalPort());
            } else {
                HttpConnectProtocol.writeEstablished(client.getOutputStream());
            }

            final ProxyTunnel tunnel = ProxyTunnel.forSocket(
                    client,
                    targetSocket,
                    config.idleTimeoutMs(),
                    () -> tunnels.removeIf(ProxyTunnel::isClosed),
                    upstreamBytes,
                    downstreamBytes);
            if (!registerTunnel(tunnel)) {
                return;
            }
            tunnel.start();
        } catch (IOException failure) {
            closeQuietly(client);
        }
    }

    /**
     * Serves one guest tunnel. The caller owns the channel and should run this on
     * a dedicated thread; the call blocks until the tunnel closes.
     */
    public void serveChannel(FastLaneChannel channel) {
        Objects.requireNonNull(channel, "channel");
        if (!running || tunnels.size() >= config.maxConnections()) {
            closeQuietly(channel);
            return;
        }
        try {
            final PushbackInputStream input =
                    new PushbackInputStream(channel.inputStream(), 1);
            final OutputStream output = channel.outputStream();
            final int first = input.read();
            if (first < 0) {
                closeQuietly(channel);
                return;
            }
            input.unread(first);
            final boolean socks = first == 0x05;
            final ProxyTarget target;
            try {
                target = socks
                        ? Socks5Protocol.readRequest(input, output)
                        : HttpConnectProtocol.readRequest(input);
            } catch (ProxyException failure) {
                failures.incrementAndGet();
                if (!socks) {
                    writeHttpFailure(output, failure.failure());
                }
                closeQuietly(channel);
                return;
            }

            final Socket targetSocket;
            try {
                targetSocket = connector.connect(
                        target.host(), target.port(), config.connectTimeoutMs());
            } catch (ProxyException failure) {
                failures.incrementAndGet();
                if (socks) {
                    writeSocksFailure(output, failure.failure());
                } else {
                    writeHttpFailure(output, failure.failure());
                }
                closeQuietly(channel);
                return;
            }

            if (socks) {
                Socks5Protocol.writeSuccess(
                        output, targetSocket.getLocalAddress(), targetSocket.getLocalPort());
            } else {
                HttpConnectProtocol.writeEstablished(output);
            }

            final ProxyTunnel tunnel = ProxyTunnel.forChannel(
                    channel,
                    targetSocket,
                    config.idleTimeoutMs(),
                    () -> tunnels.removeIf(ProxyTunnel::isClosed),
                    upstreamBytes,
                    downstreamBytes);
            if (!registerTunnel(tunnel)) {
                return;
            }
            tunnel.start();
            tunnel.await();
        } catch (IOException failure) {
            closeQuietly(channel);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeQuietly(channel);
        }
    }

    private synchronized boolean registerTunnel(ProxyTunnel tunnel) {
        if (!running) {
            tunnel.close();
            return false;
        }
        tunnels.add(tunnel);
        return true;
    }

    private void closeIdleTunnels() {
        final long now = System.nanoTime();
        final long limit = config.idleTimeoutMs() * 1_000_000L;
        for (ProxyTunnel tunnel : tunnels) {
            if (now - tunnel.lastActivityNanos() > limit) {
                tunnel.close();
            }
        }
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        closeQuietly(server);
        server = null;
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        for (ProxyTunnel tunnel : tunnels) {
            tunnel.close();
        }
        tunnels.clear();
        final Thread thread = acceptThread;
        acceptThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void writeSocksFailure(OutputStream output, ProxyFailure failure) {
        try {
            Socks5Protocol.writeFailure(output, failure);
        } catch (IOException ignored) {
            // The client is already gone.
        }
    }

    private static void writeHttpFailure(OutputStream output, ProxyFailure failure) {
        try {
            HttpConnectProtocol.writeFailure(output, failure);
        } catch (IOException ignored) {
            // The client is already gone.
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort teardown.
        }
    }
}
