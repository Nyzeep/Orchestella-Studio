package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Immutable proxy limits. The bind address is restricted to IPv4 loopback so a
 * Fast Lane proxy can never become a LAN listener by accident.
 */
public final class ProxyConfig {
    public static final int DEFAULT_MAX_CONNECTIONS = 16;
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
    public static final int DEFAULT_IDLE_TIMEOUT_MS = 300_000;
    public static final int DEFAULT_BUFFER_BYTES = 16 * 1024;

    private final InetAddress bindAddress;
    private final int port;
    private final int maxConnections;
    private final int connectTimeoutMs;
    private final int idleTimeoutMs;
    private final int bufferBytes;
    private final boolean allowLoopbackTargets;

    public ProxyConfig(
            InetAddress bindAddress,
            int port,
            int maxConnections,
            int connectTimeoutMs,
            int idleTimeoutMs,
            int bufferBytes,
            boolean allowLoopbackTargets) {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (!(bindAddress instanceof Inet4Address) || !bindAddress.isLoopbackAddress()) {
            throw new IllegalArgumentException("proxy must bind to an IPv4 loopback address");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in [0, 65535]");
        }
        if (maxConnections <= 0 || maxConnections > 1024) {
            throw new IllegalArgumentException("maxConnections must be in [1, 1024]");
        }
        if (connectTimeoutMs <= 0 || connectTimeoutMs > 120_000) {
            throw new IllegalArgumentException("connectTimeoutMs must be in [1, 120000]");
        }
        if (idleTimeoutMs <= 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be positive");
        }
        if (bufferBytes < 1024 || bufferBytes > 1024 * 1024) {
            throw new IllegalArgumentException("bufferBytes must be in [1024, 1048576]");
        }
        this.bindAddress = bindAddress;
        this.port = port;
        this.maxConnections = maxConnections;
        this.connectTimeoutMs = connectTimeoutMs;
        this.idleTimeoutMs = idleTimeoutMs;
        this.bufferBytes = bufferBytes;
        this.allowLoopbackTargets = allowLoopbackTargets;
    }

    public static ProxyConfig loopback(int port) {
        try {
            return new ProxyConfig(
                    InetAddress.getByName("127.0.0.1"),
                    port,
                    DEFAULT_MAX_CONNECTIONS,
                    DEFAULT_CONNECT_TIMEOUT_MS,
                    DEFAULT_IDLE_TIMEOUT_MS,
                    DEFAULT_BUFFER_BYTES,
                    false);
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("loopback address is unavailable", impossible);
        }
    }

    public InetAddress bindAddress() {
        return bindAddress;
    }

    public int port() {
        return port;
    }

    public int maxConnections() {
        return maxConnections;
    }

    public int connectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int idleTimeoutMs() {
        return idleTimeoutMs;
    }

    public int bufferBytes() {
        return bufferBytes;
    }

    public boolean allowLoopbackTargets() {
        return allowLoopbackTargets;
    }
}
