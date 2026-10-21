package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Resolves DNS on the host and connects with a bounded timeout. The guest never
 * resolves names. Loopback, link-local, multicast, and wildcard targets are
 * rejected unless explicitly enabled, so a guest cannot use the proxy to reach
 * host-local services.
 */
public final class DnsTargetConnector implements TargetConnector {
    private final boolean allowLoopbackTargets;

    public DnsTargetConnector(boolean allowLoopbackTargets) {
        this.allowLoopbackTargets = allowLoopbackTargets;
    }

    @Override
    public Socket connect(String host, int port, int timeoutMs) throws ProxyException {
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException failure) {
            throw new ProxyException(ProxyFailure.PROXY_DNS_FAILED, "host resolution failed");
        }
        if (addresses.length == 0) {
            throw new ProxyException(ProxyFailure.PROXY_DNS_FAILED, "host resolution failed");
        }

        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        ProxyFailure lastFailure = ProxyFailure.PROXY_CONNECT_FAILED;
        for (InetAddress address : addresses) {
            if (!isPermitted(address)) {
                lastFailure = ProxyFailure.PROXY_CONNECT_FAILED;
                continue;
            }
            final int remainingMs = (int) Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            final Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(address, port), remainingMs);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (SocketTimeoutException failure) {
                closeQuietly(socket);
                lastFailure = ProxyFailure.PROXY_TIMEOUT;
            } catch (ConnectException failure) {
                closeQuietly(socket);
                lastFailure = ProxyFailure.PROXY_CONNECT_FAILED;
            } catch (IOException failure) {
                closeQuietly(socket);
                lastFailure = ProxyFailure.PROXY_CONNECT_FAILED;
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        throw new ProxyException(lastFailure, "target connection failed");
    }

    private boolean isPermitted(InetAddress address) {
        if (address.isMulticastAddress() || address.isAnyLocalAddress()) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()) {
            return allowLoopbackTargets;
        }
        return true;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort; the caller only needs the failure classification.
        }
    }
}
