package io.github.nyzeep.orchestella.runtime.contract.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/**
 * Contract tests for {@link LocalTcpRelay}: loopback-only first bind, the LAN authorization and
 * authentication gate, the ten-minute lease ceiling with automatic expiry, byte forwarding to the
 * exact upstream port, and listener teardown on close.
 */
public final class LocalTcpRelayTest {
    private static final String LOOPBACK = "127.0.0.1";

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 16, InetAddress.getByName(LOOPBACK))) {
            return probe.getLocalPort();
        }
    }

    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK, port), 300);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    private static void awaitTrue(String label, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("timed out waiting for " + label);
    }

    private static void readFully(Socket socket, byte[] expected) throws IOException {
        InputStream input = socket.getInputStream();
        byte[] actual = new byte[expected.length];
        int offset = 0;
        while (offset < actual.length) {
            int count = input.read(actual, offset, actual.length - offset);
            if (count < 0) {
                fail("relay closed the stream after " + offset + " of " + actual.length + " bytes");
            }
            offset += count;
        }
        assertEquals(new String(expected, UTF_8), new String(actual, UTF_8));
    }

    @Test
    public void constructorRejectsOutOfRangeTargetPorts() throws IOException {
        for (int port : new int[] {0, -1, 65536, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows("target port " + port, IllegalArgumentException.class, () -> new LocalTcpRelay(port));
        }
        // The boundary values are admissible even though nothing listens on them yet.
        for (int port : new int[] {1, 65535}) {
            LocalTcpRelay relay = new LocalTcpRelay(port);
            try {
                assertNull(relay.binding());
            } finally {
                relay.close();
            }
        }
    }

    @Test
    public void startLoopbackBindsOnlyLoopbackAndIsIdempotentForTheSameRequest() throws Exception {
        int target = freePort();
        LocalTcpRelay relay = new LocalTcpRelay(target);
        try {
            assertNull("no listener exists before the first bind", relay.binding());
            LocalTcpRelay.Binding binding = relay.startLoopback(0);
            assertEquals(LOOPBACK, binding.bindAddress());
            assertTrue(binding.port() > 0);
            assertNull("loopback never carries an expiry", binding.expiresAt());
            assertTrue("the loopback listener must accept local connections", canConnect(binding.port()));

            assertEquals(binding, relay.startLoopback(0));
            assertEquals(binding, relay.startLoopback(binding.port()));
            assertThrows(IllegalStateException.class, () -> relay.startLoopback(freePort()));
        } finally {
            relay.close();
        }
    }

    @Test
    public void bytesAreRelayedToTheExactLoopbackTarget() throws Exception {
        try (EchoServer upstream = new EchoServer()) {
            LocalTcpRelay relay = new LocalTcpRelay(upstream.port());
            try {
                int port = relay.startLoopback(0).port();
                byte[] payload = ("relay-oracle-" + "x".repeat(4096)).getBytes(UTF_8);
                try (Socket client = new Socket()) {
                    client.setSoTimeout(10_000);
                    client.connect(new InetSocketAddress(LOOPBACK, port), 2000);
                    OutputStream output = client.getOutputStream();
                    output.write(payload);
                    output.flush();
                    readFully(client, payload);
                }
            } finally {
                relay.close();
            }
        }
    }

    @Test
    public void lanRefusesToStartWithoutAuthorizationOrConfiguredAuthentication() throws Exception {
        int target = freePort();
        LocalTcpRelay relay = new LocalTcpRelay(target);
        Instant deadline = Instant.now().plus(Duration.ofMinutes(1));
        try {
            LocalTcpRelay.LanLease[] refused = {
                new LocalTcpRelay.LanLease(false, true, deadline),
                new LocalTcpRelay.LanLease(true, false, deadline),
                new LocalTcpRelay.LanLease(false, false, deadline),
            };
            for (LocalTcpRelay.LanLease lease : refused) {
                assertThrows(lease.toString(), SecurityException.class, () -> relay.startLan(0, lease));
                assertNull("a refused LAN lease must not bind anything", relay.binding());
            }
            assertThrows(NullPointerException.class, () -> relay.startLan(0, null));
        } finally {
            relay.close();
        }
    }

    @Test
    public void lanLeaseMustExpireWithinTenMinutes() throws Exception {
        int target = freePort();
        LocalTcpRelay relay = new LocalTcpRelay(target);
        try {
            assertThrows(NullPointerException.class,
                    () -> relay.startLan(0, new LocalTcpRelay.LanLease(true, true, null)));
            assertThrows(IllegalArgumentException.class,
                    () -> relay.startLan(0, new LocalTcpRelay.LanLease(true, true, Instant.now())));
            assertThrows(IllegalArgumentException.class,
                    () -> relay.startLan(0, new LocalTcpRelay.LanLease(true, true,
                            Instant.now().minus(Duration.ofSeconds(1)))));
            assertThrows(IllegalArgumentException.class,
                    () -> relay.startLan(0, new LocalTcpRelay.LanLease(true, true,
                            Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5))));
            assertNull(relay.binding());
            assertNull("a rejected lease is not a bind failure", relay.failure());

            // Just inside the ceiling is admitted and keeps the caller's deadline verbatim.
            Instant deadline = Instant.now().plus(Duration.ofMinutes(9));
            LocalTcpRelay.Binding binding = relay.startLan(0, new LocalTcpRelay.LanLease(true, true, deadline));
            assertEquals("0.0.0.0", binding.bindAddress());
            assertEquals(deadline, binding.expiresAt());
            assertTrue(binding.port() > 0);
            relay.stop();
            assertNull(relay.binding());
        } finally {
            relay.close();
        }
    }

    @Test
    public void anExpiredLeaseClosesTheLanListenerByItself() throws Exception {
        int target = freePort();
        LocalTcpRelay relay = new LocalTcpRelay(target);
        try {
            int port = relay.startLan(0, new LocalTcpRelay.LanLease(true, true,
                    Instant.now().plus(Duration.ofMillis(1500)))).port();
            assertTrue("the LAN listener must be reachable while the lease is live", canConnect(port));
            awaitTrue("the lease to close the listener", () -> !canConnect(port));
            assertNull("binding must disappear once the lease expires", relay.binding());
        } finally {
            relay.close();
        }
    }

    @Test
    public void closeStopsTheListenerAndRefusesFurtherUse() throws Exception {
        int target = freePort();
        LocalTcpRelay relay = new LocalTcpRelay(target);
        int port = relay.startLoopback(0).port();
        assertTrue(canConnect(port));
        relay.close();
        assertNull(relay.binding());
        awaitTrue("the closed listener to stop accepting", () -> !canConnect(port));
        assertThrows(IllegalStateException.class, () -> relay.startLoopback(0));
        assertThrows(IllegalStateException.class,
                () -> relay.startLan(0, new LocalTcpRelay.LanLease(true, true,
                        Instant.now().plus(Duration.ofMinutes(1)))));
        // Closing again stays a no-op, and stop() after close is harmless.
        relay.close();
        relay.stop();
        assertNull(relay.binding());
    }

    @Test
    public void anUnreachableUpstreamClosesTheRelayedConnection() throws Exception {
        int closedTarget = freePort();
        LocalTcpRelay relay = new LocalTcpRelay(closedTarget);
        try {
            int port = relay.startLoopback(0).port();
            try (Socket client = new Socket()) {
                client.setSoTimeout(10_000);
                client.connect(new InetSocketAddress(LOOPBACK, port), 2000);
                int first = client.getInputStream().read();
                assertEquals("the relay must drop a client whose upstream refused", -1, first);
            }
        } finally {
            relay.close();
        }
    }

    @Test
    public void aBindFailureIsReportedAndLeavesTheRelayUsable() throws Exception {
        int target = freePort();
        LocalTcpRelay relay = new LocalTcpRelay(target);
        try (ServerSocket occupier = new ServerSocket(0)) {
            int taken = occupier.getLocalPort();
            assertThrows(IOException.class, () -> relay.startLoopback(taken));
            assertEquals("listener-bind-failed", relay.failure());
            assertNull(relay.binding());
            // The relay is not left half-bound: a later free port still works.
            LocalTcpRelay.Binding recovered = relay.startLoopback(0);
            assertTrue(recovered.port() > 0);
            assertNull(relay.failure());
        } finally {
            relay.close();
        }
    }

    /** Minimal loopback echo service standing in for the externally launched service. */
    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread acceptor;

        private EchoServer() throws IOException {
            server = new ServerSocket(0, 16, InetAddress.getByName(LOOPBACK));
            acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        InputStream input = socket.getInputStream();
                        OutputStream output = socket.getOutputStream();
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) >= 0) {
                            output.write(buffer, 0, count);
                            output.flush();
                        }
                    } catch (IOException stopped) {
                        return;
                    }
                }
            }, "local-relay-echo");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
