package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/**
 * ACR 2026-11-13 方案 A (fabric-route doc D-1, Gate 1): the UML-TCP lane
 * channel factory's contract. The bridge's own 17-test suite locks the
 * listener/pump/classification behaviour; these tests lock the one thing this
 * round added — how a UML lane channel opens (loopback-only dial, bounded
 * connect, half-close-aware output) and that the bridge's existing outcome
 * patterns (COMPLETED / CHANNEL_FAILED / LIMIT_REJECTED) hold unchanged when
 * the lane factory is the UML one.
 */
public final class UmlTcpChannelFactoryTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    // --- the factory's own contract ------------------------------------------------

    @Test
    public void rejectsNonLoopbackEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> new UmlTcpChannelFactory(
                new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 3632), 1000));
    }

    @Test
    public void rejectsInvalidPortsAndTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> new UmlTcpChannelFactory(
                new InetSocketAddress(LOOPBACK, 0), 1000));
        assertThrows(IllegalArgumentException.class, () -> new UmlTcpChannelFactory(
                new InetSocketAddress(LOOPBACK, 65536), 1000));
        assertThrows(IllegalArgumentException.class, () -> new UmlTcpChannelFactory(
                new InetSocketAddress(LOOPBACK, 3632), 0));
        assertThrows(IllegalArgumentException.class, () -> new UmlTcpChannelFactory(
                new InetSocketAddress(LOOPBACK, 3632), -1));
    }

    @Test(timeout = 5000)
    public void echoesBytesThroughTheTcpLaneChannel() throws Exception {
        try (EchoServer server = new EchoServer()) {
            UmlTcpChannelFactory factory = UmlTcpChannelFactory.loopback(LOOPBACK, server.port());
            try (FastLaneChannel channel = factory.open()) {
                channel.outputStream().write("hello".getBytes(StandardCharsets.UTF_8));
                channel.outputStream().flush();
                byte[] response = channel.inputStream().readNBytes(5);
                assertEquals("hello", new String(response, StandardCharsets.UTF_8));
            }
        }
    }

    @Test(timeout = 5000)
    public void dialingADeadGuestFailsTheOpen() throws Exception {
        int deadPort;
        try (ServerSocket dead = new ServerSocket(0, 1, LOOPBACK)) {
            deadPort = dead.getLocalPort();
            // Bound, then closed: the port is (almost certainly) refused now.
        }
        UmlTcpChannelFactory factory = UmlTcpChannelFactory.loopback(LOOPBACK, deadPort);
        assertThrows(IOException.class, factory::open);
    }

    @Test(timeout = 5000)
    public void closingTheChannelClosesTheSocketBothWays() throws Exception {
        try (EchoServer server = new EchoServer()) {
            UmlTcpChannelFactory factory = UmlTcpChannelFactory.loopback(LOOPBACK, server.port());
            FastLaneChannel channel = factory.open();
            channel.close();
            // The accepted side sees EOF after the channel closed.
            waitFor(() -> server.acceptedEof(), 3000);
        }
    }

    // --- the bridge over the UML lane: the locked outcome patterns ------------------

    @Test(timeout = 10000)
    public void bridgeOverTheUmlLaneCompletesTheDistccFixture() throws Exception {
        byte[] request = DistccTcpVsockBridgeTest.DistccFixture.request();
        byte[] response = DistccTcpVsockBridgeTest.DistccFixture.response();
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        try (FixtureServer server = new FixtureServer(request, response);
                DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                        LOOPBACK, 0,
                        UmlTcpChannelFactory.loopback(LOOPBACK, server.port()),
                        4, 5000, 0, stats::add)) {
            bridge.start();
            try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                client.setSoTimeout(3000);
                client.getOutputStream().write(request);
                client.getOutputStream().flush();
                // The distcc shape: the client may half-close; the answer still
                // comes back — the lane's output close is a half-close, never a
                // whole-socket teardown.
                client.shutdownOutput();
                byte[] actual = client.getInputStream().readAllBytes();
                assertArrayEquals(response, actual);
            }
            waitFor(() -> stats.size() == 1, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.COMPLETED, stats.get(0).outcome());
            assertEquals(request.length, stats.get(0).bytesFromClient());
            assertEquals(response.length, stats.get(0).bytesToClient());
        }
    }

    @Test(timeout = 10000)
    public void bridgeReportsChannelFailedWhenTheGuestIsDown() throws Exception {
        int deadPort;
        try (ServerSocket dead = new ServerSocket(0, 1, LOOPBACK)) {
            deadPort = dead.getLocalPort();
        }
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0,
                UmlTcpChannelFactory.loopback(LOOPBACK, deadPort),
                4, 1000, 0, stats::add)) {
            bridge.start();
            try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                client.setSoTimeout(1000);
                UmlTcpChannelFactoryTest.assertClosed(client);
            }
            waitFor(() -> stats.size() == 1, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.CHANNEL_FAILED, stats.get(0).outcome());
        }
    }

    @Test(timeout = 10000)
    public void bridgeLimitStillRejectsExcessConnectionsOverTheUmlLane() throws Exception {
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        try (BlockingEchoServer server = new BlockingEchoServer();
                DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                        LOOPBACK, 0,
                        UmlTcpChannelFactory.loopback(LOOPBACK, server.port()),
                        1, 5000, 0, stats::add)) {
            bridge.start();
            try (Socket first = new Socket(LOOPBACK, bridge.port())) {
                first.setSoTimeout(3000);
                waitFor(() -> bridge.activeConnections() == 1, 3000);
                try (Socket second = new Socket(LOOPBACK, bridge.port())) {
                    second.setSoTimeout(3000);
                    UmlTcpChannelFactoryTest.assertClosed(second);
                }
                waitFor(() -> stats.stream().anyMatch(
                        item -> item.outcome()
                                == DistccTcpVsockBridge.Outcome.LIMIT_REJECTED), 3000);
            }
        }
    }

    @Test(timeout = 15000)
    public void concurrentLaneConnectionsKeepTheirOwnByteStreams() throws Exception {
        int jobs = 3;
        try (EchoServer server = new EchoServer();
                DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                        LOOPBACK, 0,
                        UmlTcpChannelFactory.loopback(LOOPBACK, server.port()),
                        jobs, 0, 5000)) {
            bridge.start();
            ExecutorService pool = Executors.newFixedThreadPool(jobs);
            try {
                CountDownLatch ready = new CountDownLatch(jobs);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < jobs; i++) {
                    final int job = i;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(3, TimeUnit.SECONDS));
                        String payload = "uml-job-" + job;
                        try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                            client.setSoTimeout(5000);
                            client.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
                            client.getOutputStream().flush();
                            return new String(
                                    client.getInputStream().readNBytes(payload.length()),
                                    StandardCharsets.UTF_8);
                        }
                    }));
                }
                assertTrue(ready.await(3, TimeUnit.SECONDS));
                start.countDown();
                for (int i = 0; i < jobs; i++) {
                    assertEquals("uml-job-" + i, futures.get(i).get(8, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    // --- fixtures -------------------------------------------------------------------

    private static void assertClosed(Socket client) throws IOException {
        try {
            assertEquals(-1, client.getInputStream().read());
        } catch (IOException expected) {
            // A reset is also a valid closed-client outcome.
        }
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue("condition not met within " + timeoutMs + "ms", condition.getAsBoolean());
    }

    /**
     * A loopback echo "guest": one accepted connection per job, bytes copied
     * back until EOF. This is the stand-in for the UML guest's distccd.
     */
    private static final class EchoServer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService pool = Executors.newCachedThreadPool();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<CountDownLatch> eofSeen =
                new AtomicReference<>(new CountDownLatch(1));

        EchoServer() throws IOException {
            listener = new ServerSocket(0, 8, LOOPBACK);
            Thread accept = new Thread(this::acceptLoop, "uml-lane-echo-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        boolean acceptedEof() {
            return eofSeen.get().getCount() == 0;
        }

        private void acceptLoop() {
            while (!closed.get()) {
                Socket socket;
                try {
                    socket = listener.accept();
                } catch (IOException failure) {
                    return;
                }
                pool.execute(() -> serve(socket));
            }
        }

        private void serve(Socket socket) {
            try {
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                input.transferTo(output);
            } catch (IOException ignored) {
                // The client's teardown ends the echo.
            } finally {
                closeQuietly(socket);
                eofSeen.get().countDown();
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Fixture teardown.
            }
        }

        @Override public void close() {
            closed.set(true);
            try {
                listener.close();
            } catch (IOException ignored) {
                // Fixture teardown.
            }
            pool.shutdownNow();
        }
    }

    /**
     * A loopback "distccd" fixture: reads the whole request (the client's
     * half-close is the terminator), answers the canned response once, closes.
     */
    private static final class FixtureServer implements AutoCloseable {
        private final ServerSocket listener;
        private final byte[] expectedRequest;
        private final byte[] response;
        private final ExecutorService pool = Executors.newSingleThreadExecutor();

        FixtureServer(byte[] expectedRequest, byte[] response) throws IOException {
            this.expectedRequest = expectedRequest;
            this.response = response;
            listener = new ServerSocket(0, 4, LOOPBACK);
            Thread accept = new Thread(this::acceptLoop, "uml-lane-fixture-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (true) {
                Socket socket;
                try {
                    socket = listener.accept();
                } catch (IOException failure) {
                    return;
                }
                pool.execute(() -> serve(socket));
            }
        }

        private void serve(Socket socket) {
            try {
                byte[] request = socket.getInputStream().readAllBytes();
                assertNotNull(request);
                if (java.util.Arrays.equals(expectedRequest, request)) {
                    OutputStream output = socket.getOutputStream();
                    output.write(response);
                    output.flush();
                }
            } catch (IOException ignored) {
                // Fixture teardown.
            } finally {
                closeQuietly(socket);
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Fixture teardown.
            }
        }

        @Override public void close() {
            try {
                listener.close();
            } catch (IOException ignored) {
                // Fixture teardown.
            }
            pool.shutdownNow();
        }
    }

    /** Opens a lane connection whose bytes never flow until the server is torn down. */
    private static final class BlockingEchoServer implements AutoCloseable {
        private final ServerSocket listener;
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ExecutorService pool = Executors.newCachedThreadPool();

        BlockingEchoServer() throws IOException {
            listener = new ServerSocket(0, 4, LOOPBACK);
            Thread accept = new Thread(this::acceptLoop, "uml-lane-blocking-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() {
            return listener.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed.get()) {
                Socket socket;
                try {
                    socket = listener.accept();
                } catch (IOException failure) {
                    return;
                }
                accepted.add(socket);
            }
        }

        @Override public void close() {
            closed.set(true);
            for (Socket socket : accepted) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Fixture teardown.
                }
            }
            try {
                listener.close();
            } catch (IOException ignored) {
                // Fixture teardown.
            }
            pool.shutdownNow();
        }
    }
}
