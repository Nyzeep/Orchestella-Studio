package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resolves DNS on the host and connects with a bounded timeout. The guest never
 * resolves names. Loopback, link-local, multicast, and wildcard targets are
 * rejected unless explicitly enabled, so a guest cannot use the proxy to reach
 * host-local services.
 *
 * <p>DNS itself is run on a small daemon pool because {@link
 * InetAddress#getAllByName} has no timeout. The caller waits at most the connect
 * timeout; a resolver that ignores interruption can occupy one of the four pool
 * threads, but can never occupy an unbounded number of them.
 */
public final class DnsTargetConnector implements TargetConnector {
    private static final int MAX_RESOLVER_THREADS = 4;
    private static final int MAX_QUEUED_RESOLUTIONS = 16;
    private static final ExecutorService RESOLVERS = new ThreadPoolExecutor(
            MAX_RESOLVER_THREADS,
            MAX_RESOLVER_THREADS,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_RESOLUTIONS),
            daemonFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowLoopbackTargets;
    private final HostResolver resolver;

    public DnsTargetConnector(boolean allowLoopbackTargets) {
        this(allowLoopbackTargets, InetAddress::getAllByName);
    }

    DnsTargetConnector(boolean allowLoopbackTargets, HostResolver resolver) {
        this.allowLoopbackTargets = allowLoopbackTargets;
        this.resolver = resolver;
    }

    @Override
    public Socket connect(String host, int port, int timeoutMs) throws ProxyException {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        final InetAddress[] addresses = resolve(host, timeoutMs);
        if (addresses.length == 0) {
            throw new ProxyException(ProxyFailure.PROXY_DNS_FAILED, "host resolution failed");
        }

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

    private InetAddress[] resolve(String host, int timeoutMs) throws ProxyException {
        final Future<InetAddress[]> future;
        try {
            future = RESOLVERS.submit(() -> resolver.resolve(host));
        } catch (RejectedExecutionException failure) {
            throw new ProxyException(ProxyFailure.PROXY_DNS_FAILED, "resolver is saturated");
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            future.cancel(true);
            throw new ProxyException(ProxyFailure.PROXY_TIMEOUT, "host resolution timed out");
        } catch (InterruptedException failure) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProxyException(ProxyFailure.PROXY_CANCELLED, "host resolution interrupted");
        } catch (CancellationException failure) {
            throw new ProxyException(ProxyFailure.PROXY_CANCELLED, "host resolution cancelled");
        } catch (ExecutionException failure) {
            throw new ProxyException(ProxyFailure.PROXY_DNS_FAILED, "host resolution failed");
        }
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

    private static ThreadFactory daemonFactory() {
        return runnable -> {
            final Thread thread = new Thread(runnable, "fastlane-proxy-dns");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort; the caller only needs the failure classification.
        }
    }
}
