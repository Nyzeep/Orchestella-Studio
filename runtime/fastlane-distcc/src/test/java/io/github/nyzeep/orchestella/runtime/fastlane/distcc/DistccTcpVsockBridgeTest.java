package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;

public final class DistccTcpVsockBridgeTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test(timeout = 5000)
    public void echoesBytesThroughTheVsockChannel() throws Exception {
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, EchoChannel::new, 4, 2000)) {
            bridge.start();
            try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                client.setSoTimeout(2000);
                client.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                byte[] response = client.getInputStream().readNBytes(5);
                assertEquals("hello", new String(response, StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    public void rejectsNonLoopbackBind() {
        assertThrows(IllegalArgumentException.class, () -> new DistccTcpVsockBridge(
                InetAddress.getByName("0.0.0.0"), 0, EchoChannel::new, 4, 0));
    }

    @Test(timeout = 5000)
    public void factoryFailureClosesTheClientAndKeepsListening() throws Exception {
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, () -> {
                    throw new FastLaneException(FastLaneException.VSOCK_FAILED, "no VM");
                }, 4, 1000)) {
            bridge.start();
            try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                client.setSoTimeout(1000);
                assertClosed(client);
            }
            assertTrue(bridge.activeConnections() == 0);
        }
    }

    @Test(timeout = 5000)
    public void closeStopsTheListener() throws Exception {
        DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, EchoChannel::new, 4, 1000);
        bridge.start();
        int port = bridge.port();
        bridge.close();
        assertThrows(IOException.class, () -> new Socket(LOOPBACK, port).close());
    }

    @Test(timeout = 10000)
    public void halfCloseDeliversTheWholeResponseAfterClientShutdown() throws Exception {
        byte[] request = DistccFixture.request();
        byte[] response = DistccFixture.response();
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, () -> new FakeDistccChannel(request, response), 4, 5000, 0, stats::add)) {
            bridge.start();
            try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                client.setSoTimeout(3000);
                client.getOutputStream().write(request);
                client.getOutputStream().flush();
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
    public void connectionLimitRejectsExcessConnectionsWithoutOpeningAChannel() throws Exception {
        AtomicInteger channelsOpened = new AtomicInteger();
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, () -> {
                    channelsOpened.incrementAndGet();
                    return new BlockingChannel();
                }, 1, 5000, 0, stats::add)) {
            bridge.start();
            try (Socket first = new Socket(LOOPBACK, bridge.port())) {
                first.setSoTimeout(3000);
                waitFor(() -> bridge.activeConnections() == 1, 3000);
                try (Socket second = new Socket(LOOPBACK, bridge.port())) {
                    second.setSoTimeout(3000);
                    assertClosed(second);
                }
                waitFor(() -> stats.stream().anyMatch(
                        item -> item.outcome() == DistccTcpVsockBridge.Outcome.LIMIT_REJECTED), 3000);
                assertEquals(1, channelsOpened.get());
            }
        }
    }

    @Test(timeout = 10000)
    public void idleTimeoutClosesStalledConnectionAndReportsIt() throws Exception {
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, BlockingChannel::new, 4, 0, 150, stats::add)) {
            bridge.start();
            try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                client.setSoTimeout(3000);
                assertClosed(client);
            }
            waitFor(() -> stats.size() == 1, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.IDLE_TIMEOUT, stats.get(0).outcome());
            assertEquals(0, stats.get(0).bytesFromClient());
            assertEquals(0, stats.get(0).bytesToClient());
        }
    }

    @Test(timeout = 10000)
    public void closeCancelsActiveConnectionsWithBridgeClosedOutcome() throws Exception {
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, BlockingChannel::new, 4, 0, 0, stats::add);
        bridge.start();
        try (Socket client = new Socket(LOOPBACK, bridge.port())) {
            client.setSoTimeout(3000);
            waitFor(() -> bridge.activeConnections() == 1, 3000);
            bridge.close();
            assertClosed(client);
        }
        waitFor(() -> stats.size() == 1, 3000);
        assertEquals(DistccTcpVsockBridge.Outcome.BRIDGE_CLOSED, stats.get(0).outcome());
        assertEquals(0, bridge.activeConnections());
    }

    @Test(timeout = 15000)
    public void concurrentConnectionsKeepTheirOwnByteStreams() throws Exception {
        int jobs = 4;
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, EchoChannel::new, jobs, 0, 5000)) {
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
                        String payload = "job-" + job + "-payload";
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
                    assertEquals("job-" + i + "-payload", futures.get(i).get(8, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    public void rejectsInvalidLimitsAndTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> new DistccTcpVsockBridge(
                LOOPBACK, 0, EchoChannel::new, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new DistccTcpVsockBridge(
                LOOPBACK, 0, EchoChannel::new, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new DistccTcpVsockBridge(
                LOOPBACK, 0, EchoChannel::new, 1, 0, -1, stats -> {}));
    }

    private static void assertClosed(Socket client) throws IOException {
        try {
            assertEquals(-1, client.getInputStream().read());
        } catch (IOException expected) {
            // A reset is also a valid closed-client outcome.
        }
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue("condition not met within " + timeoutMs + "ms", condition.getAsBoolean());
    }

    /** Minimal byte fixture for the distcc v1 request/response framing. */
    static final class DistccFixture {
        private static final byte[] PREPROCESSED =
                "int answer(void) { return 42; }\n".getBytes(StandardCharsets.UTF_8);

        private DistccFixture() {}

        static byte[] request() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeHeader(out, "DIST", 1);
            writeHeader(out, "ARGC", 3);
            writeArg(out, "cc");
            writeArg(out, "-c");
            writeArg(out, "fixture.c");
            writeBody(out, "DOTI", PREPROCESSED);
            return out.toByteArray();
        }

        static byte[] response() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeHeader(out, "DONE", 1);
            writeHeader(out, "STAT", 0);
            writeHeader(out, "SERR", 0);
            writeHeader(out, "SOUT", 0);
            byte[] object = {0x7f, 'E', 'L', 'F', 0x00, 0x01, 0x02};
            writeBody(out, "DOTO", object);
            return out.toByteArray();
        }

        static void writeHeader(ByteArrayOutputStream out, String token, int value) {
            writeAscii(out, token);
            writeAscii(out, String.format("%08x", value));
        }

        static void writeArg(ByteArrayOutputStream out, String argument) {
            writeBody(out, "ARGV", argument.getBytes(StandardCharsets.UTF_8));
        }

        static void writeBody(ByteArrayOutputStream out, String token, byte[] body) {
            writeAscii(out, token);
            writeAscii(out, String.format("%08x", body.length));
            out.write(body, 0, body.length);
        }

        private static void writeAscii(ByteArrayOutputStream out, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes, 0, bytes.length);
        }
    }

    /**
     * Reads the request until the bridge half-closes the channel output, then
     * emits the canned response and closes. This is a fixture, not a distcc
     * implementation.
     */
    static final class FakeDistccChannel implements FastLaneChannel {
        private final PipedInputStream serverInput = new PipedInputStream(16 * 1024);
        private final PipedOutputStream toServer = new PipedOutputStream(serverInput);
        private final PipedInputStream clientInput = new PipedInputStream(16 * 1024);
        private final PipedOutputStream fromServer = new PipedOutputStream(clientInput);
        private final byte[] expectedRequest;
        private final byte[] response;

        FakeDistccChannel(byte[] expectedRequest, byte[] response) throws IOException {
            this.expectedRequest = expectedRequest;
            this.response = response;
            Thread worker = new Thread(this::serve, "fake-distccd");
            worker.setDaemon(true);
            worker.start();
        }

        private void serve() {
            try {
                byte[] actual = serverInput.readAllBytes();
                if (!Arrays.equals(expectedRequest, actual)) {
                    closeQuietly(fromServer);
                    return;
                }
                fromServer.write(response);
                fromServer.flush();
                fromServer.close();
            } catch (IOException ignored) {
                // Bridge teardown closes the pipes.
            }
        }

        @Override
        public InputStream inputStream() {
            return clientInput;
        }

        @Override
        public OutputStream outputStream() {
            return toServer;
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (AutoCloseable closeable : new AutoCloseable[] {
                    toServer, serverInput, fromServer, clientInput
            }) {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    if (first == null && failure instanceof IOException io) {
                        first = io;
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Fixture teardown.
            }
        }
    }

    static final class EchoChannel implements FastLaneChannel {
        private final PipedOutputStream toGuest = new PipedOutputStream();
        private final PipedInputStream guestInput;
        private final PipedOutputStream fromGuest = new PipedOutputStream();
        private final PipedInputStream guestOutput;

        EchoChannel() throws IOException {
            guestInput = new PipedInputStream(toGuest, 4096);
            guestOutput = new PipedInputStream(fromGuest, 4096);
            Thread echo = new Thread(() -> {
                try {
                    guestInput.transferTo(fromGuest);
                } catch (IOException ignored) {
                    // Closing either side ends the echo.
                } finally {
                    closeQuietly(fromGuest);
                }
            }, "fastlane-distcc-echo");
            echo.setDaemon(true);
            echo.start();
        }

        @Override
        public InputStream inputStream() {
            return guestOutput;
        }

        @Override
        public OutputStream outputStream() {
            return toGuest;
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (AutoCloseable closeable : new AutoCloseable[] {
                    toGuest, guestInput, fromGuest, guestOutput
            }) {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    if (first == null && failure instanceof IOException io) {
                        first = io;
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }

        private static void closeQuietly(AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Fixture teardown.
            }
        }
    }

    /** Opens a channel whose input never produces bytes until it is closed. */
    static final class BlockingChannel implements FastLaneChannel {
        private final PipedInputStream clientInput = new PipedInputStream();
        private final PipedOutputStream sink;
        private final PipedInputStream serverInput = new PipedInputStream();
        private final PipedOutputStream toServer;

        BlockingChannel() throws IOException {
            sink = new PipedOutputStream(clientInput);
            toServer = new PipedOutputStream(serverInput);
        }

        @Override
        public InputStream inputStream() {
            return clientInput;
        }

        @Override
        public OutputStream outputStream() {
            return toServer;
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (AutoCloseable closeable : new AutoCloseable[] {
                    toServer, serverInput, sink, clientInput
            }) {
                try {
                    closeable.close();
                } catch (Exception failure) {
                    if (first == null && failure instanceof IOException io) {
                        first = io;
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }
}
