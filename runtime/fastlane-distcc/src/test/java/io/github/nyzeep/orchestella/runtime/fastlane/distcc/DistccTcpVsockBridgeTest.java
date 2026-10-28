package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    public void factoryFailureCarriesAFixedReasonCode() throws Exception {
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, () -> {
                    throw new FastLaneException(
                            Axp1DistccChannelFactory.COMPILER_MISMATCH, "mismatch");
                }, 4, 1000, 0, stats::add)) {
            bridge.start();
            try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                client.setSoTimeout(1000);
                assertClosed(client);
            }
            waitFor(() -> stats.size() == 1, 3000);
            assertEquals(DistccTcpVsockBridge.Outcome.CHANNEL_FAILED, stats.get(0).outcome());
            assertEquals(Axp1DistccChannelFactory.COMPILER_MISMATCH,
                    stats.get(0).failureCode());
        }
    }

    @Test
    public void statsFailureCodeNeverCarriesFreeFormText() {
        DistccTcpVsockBridge.ConnectionStats stats =
                new DistccTcpVsockBridge.ConnectionStats(
                        DistccTcpVsockBridge.Outcome.CHANNEL_FAILED,
                        1,
                        2,
                        3,
                        "/tmp/source.c token=secret");
        assertEquals(DistccTcpVsockBridge.ConnectionStats.CHANNEL_FAILED,
                stats.failureCode());
    }

    @Test(timeout = 5000)
    public void closeStopsTheListener() throws Exception {
        DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, EchoChannel::new, 4, 1000);
        bridge.start();
        int port = bridge.port();
        bridge.close();
        try {
            assertThrows(IOException.class, () -> new Socket(LOOPBACK, port).close());
        } catch (AssertionError race) {
            // Session-18 W-D diagnostic for the known flake: the close-vs-
            // connect race leaves the backlog accepting for a few milliseconds
            // after close() returns. Log the exact probe shape so a CI failure
            // is attributable without a debugger; the assertion is unchanged.
            try {
                new Socket(LOOPBACK, port).close();
                System.out.println(
                        "[diag] closeStopsTheListener: connect still SUCCEEDS after close(); port=" + port);
            } catch (IOException refused) {
                System.out.println(
                        "[diag] closeStopsTheListener: second connect refused as expected; port=" + port
                                + " reason=" + refused.getClass().getSimpleName());
            }
            throw race;
        }
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

    @Test(timeout = 10000)
    public void fixedHexFixtureCarriesTheExpectedProtocolTokens() {
        byte[] request = DistccFixture.request();
        byte[] response = DistccFixture.response();
        assertEquals("DIST", new String(request, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("00000001", new String(request, 4, 8, StandardCharsets.US_ASCII));
        assertEquals("DONE", new String(response, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(indexOf(request, "DOTI".getBytes(StandardCharsets.US_ASCII)) > 0);
        assertTrue(indexOf(response, "DOTO".getBytes(StandardCharsets.US_ASCII)) > 0);
        assertTrue(indexOf(response, "SERR".getBytes(StandardCharsets.US_ASCII)) > 0);
    }

    @Test(timeout = 20000)
    public void largeBinaryPayloadIsForwardedByteForByte() throws Exception {
        byte[] payload = new byte[256 * 1024];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) ((index * 31 + 7) & 0xff);
        }
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, EchoChannel::new, 1, 0, 5000)) {
            bridge.start();
            try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                client.setSoTimeout(10000);
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    Future<byte[]> reader = pool.submit(
                            () -> client.getInputStream().readNBytes(payload.length));
                    OutputStream output = client.getOutputStream();
                    for (int offset = 0; offset < payload.length; offset += 4096) {
                        int length = Math.min(4096, payload.length - offset);
                        output.write(payload, offset, length);
                        output.flush();
                    }
                    assertArrayEquals(payload, reader.get(15, TimeUnit.SECONDS));
                } finally {
                    pool.shutdownNow();
                }
            }
        }
    }

    @Test(timeout = 10000)
    public void clientResetReleasesTheConnectionSlot() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK,
                0,
                () -> opened.getAndIncrement() == 0 ? new BlockingChannel() : new EchoChannel(),
                1,
                0,
                5000,
                stats::add)) {
            bridge.start();
            Socket first = new Socket(LOOPBACK, bridge.port());
            first.setSoTimeout(3000);
            waitFor(() -> opened.get() == 1, 3000);
            waitFor(() -> bridge.activeConnections() == 1, 3000);
            first.setSoLinger(true, 0);
            first.close();
            waitFor(() -> bridge.activeConnections() == 0, 3000);
            waitFor(() -> stats.stream().anyMatch(
                    item -> item.outcome() == DistccTcpVsockBridge.Outcome.CHANNEL_FAILED), 3000);

            try (Socket second = new Socket(LOOPBACK, bridge.port())) {
                second.setSoTimeout(3000);
                second.getOutputStream().write("after-reset".getBytes(StandardCharsets.UTF_8));
                second.getOutputStream().flush();
                assertEquals(
                        "after-reset",
                        new String(
                                second.getInputStream().readNBytes("after-reset".length()),
                                StandardCharsets.UTF_8));
            }
        }
    }

    @Test(timeout = 5000)
    public void closeIsIdempotentAndInvalidatesPort() throws Exception {
        List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
        DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK, 0, EchoChannel::new, 4, 0, 0, stats::add);
        bridge.start();
        bridge.close();
        bridge.close();
        assertThrows(IllegalStateException.class, bridge::port);
        assertEquals(0, bridge.activeConnections());
        assertEquals(0, stats.size());
    }

    @Test(timeout = 10000)
    public void statsSinkFailureDoesNotStopTheListener() throws Exception {
        try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                LOOPBACK,
                0,
                EchoChannel::new,
                2,
                0,
                5000,
                stats -> {
                    throw new IllegalStateException("observer failed");
                })) {
            bridge.start();
            for (int index = 0; index < 2; index++) {
                try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                    client.setSoTimeout(3000);
                    client.getOutputStream().write("ok".getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    assertEquals(
                            "ok",
                            new String(
                                    client.getInputStream().readNBytes(2),
                                    StandardCharsets.UTF_8));
                }
            }
        }
    }

    @Test(timeout = 15000)
    public void processStderrDoesNotEnterTheProtocolStream() throws Exception {
        assumeFalse(
                "POSIX /bin/sh fixture is not executable by a Windows JVM",
                System.getProperty("os.name", "").toLowerCase().contains("windows"));
        byte[] response = DistccFixture.response();
        Path root = java.nio.file.Files.createTempDirectory("fastlane-distcc-stderr");
        Path responseFile = root.resolve("response.bin");
        Path stderrFile = root.resolve("stderr.txt");
        Path script = root.resolve("server.sh");
        java.nio.file.Files.write(responseFile, response);
        java.nio.file.Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "printf 'STDERR_NOISE\\n' >&2\n"
                        + "cat >/dev/null\n"
                        + "cat \"$1\"\n");
        script.toFile().setExecutable(true);
        try {
            try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                    LOOPBACK,
                    0,
                    () -> new ProcessChannel(script, responseFile, stderrFile),
                    1,
                    0,
                    5000)) {
                bridge.start();
                try (Socket client = new Socket(LOOPBACK, bridge.port())) {
                    client.setSoTimeout(10000);
                    client.getOutputStream().write(DistccFixture.request());
                    client.getOutputStream().flush();
                    client.shutdownOutput();
                    assertArrayEquals(response, client.getInputStream().readAllBytes());
                }
            }
            String stderr = java.nio.file.Files.readString(stderrFile);
            assertTrue(stderr.contains("STDERR_NOISE"));
            assertFalse(stderr.contains("DONE"));
        } finally {
            java.nio.file.Files.deleteIfExists(script);
            java.nio.file.Files.deleteIfExists(responseFile);
            java.nio.file.Files.deleteIfExists(stderrFile);
            java.nio.file.Files.deleteIfExists(root);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int index = 0; index + needle.length <= haystack.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
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

    /** Fixed byte fixture for the distcc v1 request/response framing. */
    static final class DistccFixture {
        private DistccFixture() {}

        static byte[] request() {
            return resource("request-v1.hex");
        }

        static byte[] response() {
            return resource("response-v1.hex");
        }
    }

    static byte[] resource(String name) {
        try (InputStream input =
                DistccTcpVsockBridgeTest.class.getResourceAsStream("/distcc/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing test resource: " + name);
            }
            String hex = new String(input.readAllBytes(), StandardCharsets.US_ASCII)
                    .replaceAll("\\s+", "");
            return java.util.HexFormat.of().parseHex(hex);
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read test resource: " + name, failure);
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

    /** Runs a real child process and exposes only its stdin/stdout as the channel. */
    static final class ProcessChannel implements FastLaneChannel {
        private final Process process;

        ProcessChannel(java.nio.file.Path script, java.nio.file.Path response, java.nio.file.Path stderr)
                throws IOException {
            process = new ProcessBuilder(script.toString(), response.toString())
                    .redirectError(stderr.toFile())
                    .start();
        }

        @Override
        public InputStream inputStream() {
            return process.getInputStream();
        }

        @Override
        public OutputStream outputStream() {
            return process.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
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
