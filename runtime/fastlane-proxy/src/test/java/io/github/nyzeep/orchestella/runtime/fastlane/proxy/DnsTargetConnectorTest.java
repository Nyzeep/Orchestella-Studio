package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class DnsTargetConnectorTest {
    @Test
    public void connectsToAnAllowedLocalTarget() throws Exception {
        try (ServerSocket server = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            final DnsTargetConnector connector = new DnsTargetConnector(true);
            try (Socket socket = connector.connect("127.0.0.1", server.getLocalPort(), 1000)) {
                assertTrue(socket.isConnected());
            }
        }
    }

    @Test
    public void rejectsLoopbackWhenDisabled() throws Exception {
        // The port must be open, otherwise the test would pass even if the
        // loopback policy were removed and the connect simply failed.
        try (ServerSocket server = new ServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"))) {
            final DnsTargetConnector connector = new DnsTargetConnector(false);
            final ProxyException failure = assertThrows(ProxyException.class,
                    () -> connector.connect("127.0.0.1", server.getLocalPort(), 1000));
            assertEquals(ProxyFailure.PROXY_CONNECT_FAILED, failure.failure());
        }
    }

    @Test
    public void dnsFailureIsTyped() {
        final DnsTargetConnector connector = new DnsTargetConnector(false, host -> {
            throw new UnknownHostException("test");
        });
        final ProxyException failure = assertThrows(ProxyException.class,
                () -> connector.connect("example.invalid", 80, 1000));
        assertEquals(ProxyFailure.PROXY_DNS_FAILED, failure.failure());
    }

    @Test
    public void dnsTimeoutIsTypedAndBounded() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final DnsTargetConnector connector = new DnsTargetConnector(false, host -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new InetAddress[] {InetAddress.getByName("192.0.2.1")};
        });
        final long started = System.nanoTime();
        try {
            final ProxyException failure = assertThrows(ProxyException.class,
                    () -> connector.connect("slow.invalid", 80, 100));
            assertEquals(ProxyFailure.PROXY_TIMEOUT, failure.failure());
            final long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMs < 2000);
        } finally {
            release.countDown();
        }
    }

    @Test
    public void emptyResolutionIsDnsFailure() {
        final DnsTargetConnector connector =
                new DnsTargetConnector(false, host -> new InetAddress[0]);
        final ProxyException failure = assertThrows(ProxyException.class,
                () -> connector.connect("empty.invalid", 80, 1000));
        assertEquals(ProxyFailure.PROXY_DNS_FAILED, failure.failure());
    }
}
