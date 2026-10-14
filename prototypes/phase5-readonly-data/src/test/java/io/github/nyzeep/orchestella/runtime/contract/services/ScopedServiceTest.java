package io.github.nyzeep.orchestella.runtime.contract.services;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ScopedServiceTest {
    @Test public void serviceHealthTracksRealHttpAndOwnedLifetime() throws Exception {
        int port = freePort();
        AtomicInteger starts = new AtomicInteger();
        HttpService[] process = new HttpService[1];
        try (ServiceCredential credential = ServiceCredential.generate();
             ScopedService service = new ScopedService(EndpointPolicy.loopback("http", port), supplied -> {
                 starts.incrementAndGet();
                 assertSame(credential, supplied);
                 process[0] = new HttpService(port);
                 return process[0];
             }, credential, Duration.ofSeconds(2))) {
            assertEquals(ScopedService.State.STOPPED, service.snapshot().state());
            assertEquals(ScopedService.State.HEALTHY, service.start().state());
            assertEquals(ScopedService.State.HEALTHY, service.start().state());
            assertEquals(1, starts.get());
            process[0].status = 503;
            assertEquals(ScopedService.State.UNHEALTHY, service.probe().state());
            process[0].status = 200;
            assertEquals(ScopedService.State.HEALTHY, service.probe().state());
            process[0].close();
            assertEquals(ScopedService.State.FAILED, service.probe().state());
            service.stop();
            assertEquals(ScopedService.State.STOPPED, service.snapshot().state());
            assertEquals(ScopedService.State.HEALTHY, service.start().state());
            service.stop();
            assertThrows(IOException.class, () -> new Socket("127.0.0.1", port));
        }
    }

    @Test public void credentialsAreRandomExplicitAndRedactedAndRejectWeakInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceCredential.fromUserInput("password".toCharArray()));
        try (ServiceCredential first = ServiceCredential.generate(); ServiceCredential second = ServiceCredential.generate()) {
            assertThrows(SecurityException.class, () -> first.reveal(false));
            assertFalse(java.util.Arrays.equals(first.reveal(true), second.reveal(true)));
            assertEquals(64, first.reveal(true).length);
            assertEquals("ServiceCredential[redacted]", first.toString());
        }
    }

    @Test public void scopedLanPreservesHttpAuthAndRevokesOnCrashStopAndRestart() throws Exception {
        int port = freePort();
        HttpService[] process = new HttpService[1];
        try (ServiceCredential credential = ServiceCredential.generate();
             ScopedService service = new ScopedService(EndpointPolicy.loopback("http", port), supplied -> {
                 process[0] = new HttpService(port);
                 return process[0];
             }, credential, Duration.ofSeconds(1))) {
            assertThrows(IllegalStateException.class, () -> service.enableLan(0,
                    new LocalTcpRelay.LanLease(true, true, Instant.now().plusSeconds(5))));
            service.start();
            LocalTcpRelay.Binding lan = service.enableLan(0,
                    new LocalTcpRelay.LanLease(true, true, Instant.now().plusSeconds(5)));
            assertEquals(401, http(lan.port(), false));
            assertEquals(200, http(lan.port(), true));
            process[0].close();
            long until = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (service.lanBinding() != null && System.nanoTime() < until) { Thread.sleep(10); }
            assertNull(service.lanBinding());
            assertThrows(IOException.class, () -> new Socket("127.0.0.1", lan.port()));
            service.start();
            assertNull(service.lanBinding());
            LocalTcpRelay.Binding second = service.enableLan(0,
                    new LocalTcpRelay.LanLease(true, true, Instant.now().plusSeconds(5)));
            service.stop();
            assertThrows(IOException.class, () -> new Socket("127.0.0.1", second.port()));
            service.start();
            assertNull(service.lanBinding());
            service.disableLan();
        }
    }

    @Test public void occupiedPortAndAliveWithoutListenerCannotBecomeHealthy() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             ServiceCredential credential = ServiceCredential.generate();
             ScopedService service = new ScopedService(EndpointPolicy.loopback("http", occupied.getLocalPort()),
                     supplied -> { throw new AssertionError("occupied port must never launch"); }, credential, Duration.ofMillis(50))) {
            assertThrows(IOException.class, service::start);
            assertEquals(ScopedService.State.FAILED, service.snapshot().state());
        }
        int port = freePort();
        try (ServiceCredential credential = ServiceCredential.generate();
             ScopedService service = new ScopedService(EndpointPolicy.loopback("http", port), supplied -> {
                 ServerSocket unbound = new ServerSocket();
                 return new ScopedService.Handle() {
                     @Override public boolean isAlive() { return !unbound.isClosed(); }
                     @Override public void close() throws IOException { unbound.close(); }
                 };
             }, credential, Duration.ofMillis(50))) {
            assertEquals(ScopedService.State.STARTING, service.start().state());
            Thread.sleep(70);
            assertEquals(ScopedService.State.FAILED, service.probe().state());
        }
    }

    @Test public void failedCloseRetainsOwnershipForExplicitCleanupRetry() throws Exception {
        int port = freePort();
        AtomicInteger closes = new AtomicInteger();
        try (ServiceCredential credential = ServiceCredential.generate();
             ScopedService service = new ScopedService(EndpointPolicy.loopback("http", port), supplied -> {
                 HttpService process = new HttpService(port);
                 return new ScopedService.Handle() {
                     @Override public boolean isAlive() { return process.isAlive(); }
                     @Override public void close() throws IOException {
                         if (closes.getAndIncrement() == 0) { throw new IOException("synthetic close failure"); }
                         process.close();
                     }
                 };
             }, credential, Duration.ofSeconds(1))) {
            assertEquals(ScopedService.State.HEALTHY, service.start().state());
            assertThrows(IOException.class, service::stop);
            assertEquals(ScopedService.State.FAILED, service.snapshot().state());
            assertThrows(IOException.class, service::start);
            service.stop();
            assertEquals(ScopedService.State.STOPPED, service.snapshot().state());
            assertThrows(IOException.class, () -> new Socket("127.0.0.1", port));
        }
    }

    private static int http(int port, boolean authenticated) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) java.net.URI.create("http://127.0.0.1:" + port + "/")
                .toURL().openConnection(Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            if (authenticated) { connection.setRequestProperty("Authorization", "Bearer synthetic-local-test-auth"); }
            return connection.getResponseCode();
        } finally { connection.disconnect(); }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    static final class HttpService implements ScopedService.Handle {
        private final ServerSocket server;
        volatile int status = 200;
        HttpService(int port) throws IOException {
            server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(this::serve, "synthetic-owned-http-service");
            thread.setDaemon(true);
            thread.start();
        }
        private void serve() {
            while (!server.isClosed()) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(1000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                    String request = reader.readLine();
                    String line;
                    boolean authorized = false;
                    int headers = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty() && headers++ < 32) {
                        if (line.equals("Authorization: Bearer synthetic-local-test-auth")) { authorized = true; }
                    }
                    int responseStatus = request != null && (request.startsWith("GET /healthz ") || authorized) ? status : 401;
                    String response = "HTTP/1.1 " + responseStatus + " Synthetic\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK";
                    client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                } catch (IOException error) {
                    if (server.isClosed()) { break; }
                }
            }
        }
        @Override public boolean isAlive() { return !server.isClosed(); }
        @Override public void close() throws IOException { server.close(); }
    }
}
