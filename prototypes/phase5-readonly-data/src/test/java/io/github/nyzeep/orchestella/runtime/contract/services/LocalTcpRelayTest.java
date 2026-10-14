package io.github.nyzeep.orchestella.runtime.contract.services;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LocalTcpRelayTest {
    @SuppressWarnings("try") // Explicit close tests the public terminal-state boundary.
    @Test public void actualBidirectionalBytesAndHalfCloseStopAtOwnedListener() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             LocalTcpRelay relay = new LocalTcpRelay(upstream.getLocalPort())) {
            LocalTcpRelay.Binding binding = relay.startLoopback(0);
            assertEquals("127.0.0.1", binding.bindAddress());
            assertEquals(binding, relay.startLoopback(0));
            try (Socket client = new Socket("127.0.0.1", binding.port()); Socket backend = upstream.accept()) {
                client.setSoTimeout(2000);
                backend.setSoTimeout(2000);
                client.getOutputStream().write("authenticated-request".getBytes(StandardCharsets.UTF_8));
                client.shutdownOutput();
                assertEquals("authenticated-request", new String(backend.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                backend.getOutputStream().write("response".getBytes(StandardCharsets.UTF_8));
                backend.shutdownOutput();
                assertEquals("response", new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            int port = binding.port();
            relay.stop();
            assertThrows(IOException.class, () -> connect(port));
            relay.close();
            assertThrows(IllegalStateException.class, () -> relay.startLoopback(0));
        }
    }

    @Test public void lanNeedsFreshAuthorizationAuthenticationAndFiniteLease() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             LocalTcpRelay relay = new LocalTcpRelay(upstream.getLocalPort())) {
            Instant deadline = Instant.now().plusMillis(300);
            assertThrows(SecurityException.class, () -> relay.startLan(0,
                    new LocalTcpRelay.LanLease(false, true, deadline)));
            assertThrows(SecurityException.class, () -> relay.startLan(0,
                    new LocalTcpRelay.LanLease(true, false, deadline)));
            assertThrows(IllegalArgumentException.class, () -> relay.startLan(0,
                    new LocalTcpRelay.LanLease(true, true, Instant.now().minusSeconds(1))));
            assertNull(relay.binding());
            LocalTcpRelay.Binding lan = relay.startLan(0,
                    new LocalTcpRelay.LanLease(true, true, Instant.now().plusMillis(300)));
            assertEquals("0.0.0.0", lan.bindAddress());
            connect(lan.port());
            long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (relay.binding() != null && System.nanoTime() < until) { Thread.sleep(10); }
            assertNull("deadline removes actual listener", relay.binding());
            assertThrows(IOException.class, () -> connect(lan.port()));
            LocalTcpRelay.Binding local = relay.startLoopback(0);
            assertEquals("127.0.0.1", local.bindAddress());
            assertNull(local.expiresAt());
        }
    }

    @Test public void conflictsFailAndStopClosesActiveConnections() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             ServerSocket occupied = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             LocalTcpRelay relay = new LocalTcpRelay(upstream.getLocalPort())) {
            assertThrows(IOException.class, () -> relay.startLoopback(occupied.getLocalPort()));
            assertNull(relay.binding());
            int port = relay.startLoopback(0).port();
            try (Socket client = new Socket("127.0.0.1", port); Socket backend = upstream.accept()) {
                client.setSoTimeout(2000);
                backend.setSoTimeout(2000);
                relay.stop();
                assertEquals(-1, client.getInputStream().read());
                assertEquals(-1, backend.getInputStream().read());
            }
            assertThrows(IOException.class, () -> connect(port));
        }
    }

    @Test public void overTenMinuteLeaseCannotBindOrDisplaceAnExistingListener() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             LocalTcpRelay relay = new LocalTcpRelay(upstream.getLocalPort())) {
            assertThrows(IllegalArgumentException.class, () -> relay.startLan(0,
                    new LocalTcpRelay.LanLease(true, true, Instant.now().plusSeconds(601))));
            assertNull(relay.binding());
            LocalTcpRelay.Binding local = relay.startLoopback(0);
            assertThrows(IllegalArgumentException.class, () -> relay.startLan(0,
                    new LocalTcpRelay.LanLease(true, true, Instant.now().plusSeconds(1800))));
            assertEquals(local, relay.binding());
            connect(local.port());
        }
    }

    private static void connect(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
        }
    }
}
