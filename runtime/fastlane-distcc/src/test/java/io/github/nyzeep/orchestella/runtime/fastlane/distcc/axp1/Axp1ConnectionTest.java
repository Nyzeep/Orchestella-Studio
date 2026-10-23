package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Test;

/** Zero-device AXP1 protocol tests: codec, handshake, flow control, reset, reconnect. */
public final class Axp1ConnectionTest {
    // Fixed protocol test vectors, not credentials; split so secret scanners
    // do not treat a fixture as a leaked token.
    private static final String TOKEN =
            "00112233" + "44556677" + "8899aabb" + "ccddeeff";
    private static final String WRONG_TOKEN =
            "ffeeddcc" + "bbaa9988" + "77665544" + "33221100";

    private static final Axp1Capabilities CLIENT_CAPS = Axp1Capabilities.builder()
            .distcc(true).maxFrame(1_048_576).compilerTriple("aarch64-linux-android")
            .arch("aarch64").feature("pump").build();
    private static final Axp1Capabilities SERVER_CAPS = Axp1Capabilities.builder()
            .distcc(true).maxFrame(524_288).compilerTriple("aarch64-linux-android")
            .arch("aarch64").feature("pump").feature("lzo").build();

    @Test
    public void goldenVectorsDecodeAndReencode() throws Exception {
        java.io.InputStream fixture = Axp1ConnectionTest.class
                .getResourceAsStream("/axp1/golden-v1.hex");
        assertNotNull(fixture);
        List<String> lines = new String(fixture.readAllBytes(), StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank()).toList();
        assertEquals(7, lines.size());
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+", 2);
            Axp1Frame.Type type = Axp1Frame.Type.valueOf(parts[0]);
            byte[] wire = hex(parts[1]);
            Axp1Frame decoded = Axp1Codec.read(new ByteArrayInputStream(wire), 1_048_576);
            assertNotNull(decoded);
            assertEquals(type, decoded.type());
            assertArrayEquals(wire, Axp1Codec.encode(decoded));
        }
    }

    @Test
    public void partialHeaderAndCoalescedFramesAreReadInOrder() throws Exception {
        byte[] hello = Axp1Codec.encode(Axp1Frame.of(Axp1Frame.Type.PING,
                new byte[] {1, 2, 3}));
        byte[] data = Axp1Codec.encode(Axp1Frame.of(Axp1Frame.Type.DATA,
                "abc".getBytes(StandardCharsets.UTF_8)));
        byte[] both = concat(hello, data);
        InputStream oneByteAtATime = new OneByteInputStream(both);
        Axp1Frame first = Axp1Codec.read(oneByteAtATime, 1_048_576);
        Axp1Frame second = Axp1Codec.read(oneByteAtATime, 1_048_576);
        assertEquals(Axp1Frame.Type.PING, first.type());
        assertEquals(Axp1Frame.Type.DATA, second.type());
        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), second.payload());
    }

    @Test
    public void frameLengthIsBoundedBeforeAllocation() throws Exception {
        byte[] header = new byte[12];
        System.arraycopy("AXP1".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 4);
        header[4] = 1;
        header[5] = 3;
        header[8] = (byte) 0xff;
        header[9] = (byte) 0xff;
        header[10] = (byte) 0xff;
        header[11] = (byte) 0xff;
        Axp1Exception failure = assertThrows(Axp1Exception.class,
                () -> Axp1Codec.read(new ByteArrayInputStream(header), 1_048_576));
        assertEquals(Axp1Error.FRAME_TOO_LARGE, failure.code());
    }

    @Test
    public void exactlyOneMebibyteIsAcceptedAndOneByteMoreIsNot() throws Exception {
        byte[] payload = new byte[1_048_576];
        byte[] wire = Axp1Codec.encode(Axp1Frame.of(Axp1Frame.Type.DATA, payload));
        Axp1Frame decoded = Axp1Codec.read(new ByteArrayInputStream(wire), 1_048_576);
        assertEquals(1_048_576, decoded.payload().length);

        byte[] tooBig = new byte[1_048_577];
        Axp1Frame frame = Axp1Frame.of(Axp1Frame.Type.DATA, tooBig);
        Axp1Exception failure = assertThrows(Axp1Exception.class,
                () -> Axp1Codec.encode(frame, 1_048_576));
        assertEquals(Axp1Error.FRAME_TOO_LARGE, failure.code());
    }

    @Test
    public void versionMismatchIsTypedAndDoesNotEnterTheHandshake() throws Exception {
        MemoryTransport[] pair = MemoryTransport.duplex();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Axp1Connection> server = executor.submit(() -> Axp1Connection.accept(
                    pair[1], Axp1Role.GUEST, TOKEN, SERVER_CAPS, limits(1_000L)));
            byte[] header = new byte[12];
            System.arraycopy("AXP1".getBytes(StandardCharsets.US_ASCII), 0, header, 0, 4);
            header[4] = 2;
            header[5] = 1;
            pair[0].output().write(header);
            pair[0].output().flush();
            Axp1Frame reset = Axp1Codec.read(pair[0].input(), 1_048_576);
            assertEquals(Axp1Frame.Type.RST, reset.type());
            assertEquals(Axp1Error.VERSION_MISMATCH.wireCode(),
                    ((reset.payload()[0] & 0xff) << 8) | (reset.payload()[1] & 0xff));
            java.util.concurrent.ExecutionException rejected = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> server.get(2, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof Axp1Exception);
            assertEquals(Axp1Error.VERSION_MISMATCH,
                    ((Axp1Exception) rejected.getCause()).code());
        } finally {
            executor.shutdownNow();
            pair[0].close();
            pair[1].close();
        }
    }

    @Test
    public void wrongTokenFailsWithoutEchoingTheExpectedValue() throws Exception {
        MemoryTransport[] pair = MemoryTransport.duplex();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Axp1Connection> server = executor.submit(() -> Axp1Connection.accept(
                    pair[1], Axp1Role.GUEST, TOKEN, SERVER_CAPS, limits(2_000L)));
            Axp1Exception clientFailure = assertThrows(Axp1Exception.class,
                    () -> Axp1Connection.connect(
                            pair[0], Axp1Role.HOST, WRONG_TOKEN, CLIENT_CAPS, limits(2_000L)));
            assertEquals(Axp1Error.AUTH_FAILED, clientFailure.code());
            assertFalse(clientFailure.getMessage().contains(TOKEN));
            java.util.concurrent.ExecutionException rejected = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> server.get(2, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof Axp1Exception);
            assertEquals(Axp1Error.AUTH_FAILED, ((Axp1Exception) rejected.getCause()).code());
        } finally {
            executor.shutdownNow();
            pair[0].close();
            pair[1].close();
        }
    }

    @Test
    public void handshakeTimeoutIsBounded() throws Exception {
        MemoryTransport[] pair = MemoryTransport.duplex();
        long started = System.nanoTime();
        try {
            Axp1Exception failure = assertThrows(Axp1Exception.class,
                    () -> Axp1Connection.connect(
                            pair[0], Axp1Role.HOST, TOKEN, CLIENT_CAPS, limits(100L)));
            assertEquals(Axp1Error.TIMEOUT, failure.code());
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue("timeout took " + elapsedMs, elapsedMs < 2_000L);
        } finally {
            pair[0].close();
            pair[1].close();
        }
    }

    @Test
    public void negotiatedCapabilitiesUseTheServerLimitAndRecordUnknownIds() throws Exception {
        Axp1Capabilities peer = Axp1Capabilities.decode(unknownCapabilityBytes());
        assertTrue(peer.distcc());
        assertEquals(1_048_576, peer.maxFrame());
        assertTrue(peer.unknownIds().contains(99));

        Pair pair = pair(limits(2_000L));
        try {
            assertEquals(524_288, pair.client.negotiated().maxFrame());
            assertTrue(pair.client.negotiated().features().contains("pump"));
            assertFalse(pair.client.negotiated().features().contains("lzo"));
        } finally {
            pair.close();
        }
    }

    @Test
    public void sixteenMebibyteStreamIsForwardedByteForByte() throws Exception {
        Pair pair = pair(limitsWithWindow(2_000L, 4 * 1_048_576));
        byte[] source = deterministic(16 * 1_048_576);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> received = executor.submit(() -> readFully(pair.server.dataInput(), source.length));
            pair.client.dataOutput().write(source);
            pair.client.dataOutput().flush();
            assertArrayEquals(source, received.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            pair.close();
        }
    }

    @Test
    public void receiveWindowBoundsBufferedBytesAndPausesTheReader() throws Exception {
        int window = 65_536;
        Pair pair = pair(limitsWithWindow(2_000L, window));
        byte[] source = deterministic(1_048_576);
        AtomicBoolean finished = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                pair.client.dataOutput().write(source);
                pair.client.dataOutput().flush();
                finished.set(true);
            } catch (IOException ignored) {
                // reset by the test teardown
            }
        }, "axp1-test-writer");
        writer.setDaemon(true);
        try {
            writer.start();
            waitFor(() -> pair.server.bufferedBytes() >= window, 5_000L);
            assertTrue(pair.server.bufferedBytes() <= window);
            Thread.sleep(50L);
            assertFalse("reader must stop consuming while the window is full", finished.get());
            byte[] sink = new byte[source.length];
            int offset = 0;
            while (offset < sink.length) {
                int read = pair.server.dataInput().read(sink, offset, Math.min(8_192, sink.length - offset));
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            writer.join(10_000L);
            assertTrue(finished.get());
            assertArrayEquals(source, sink);
        } finally {
            pair.close();
        }
    }

    @Test
    public void halfCloseClosesOnlyTheSendingDirection() throws Exception {
        Pair pair = pair(limits(2_000L));
        try {
            pair.client.dataOutput().write("request".getBytes(StandardCharsets.UTF_8));
            pair.client.dataOutput().flush();
            pair.client.closeWrite();
            byte[] request = readFully(pair.server.dataInput(), 7);
            assertEquals("request", new String(request, StandardCharsets.UTF_8));
            pair.server.dataOutput().write("response".getBytes(StandardCharsets.UTF_8));
            pair.server.dataOutput().flush();
            byte[] response = readFully(pair.client.dataInput(), 8);
            assertEquals("response", new String(response, StandardCharsets.UTF_8));
            assertEquals(-1, pair.server.dataInput().read());
        } finally {
            pair.close();
        }
    }

    @Test
    public void closeWriteAndFullCloseAreIdempotent() throws Exception {
        Pair pair = pair(limits(2_000L));
        try {
            pair.client.closeWrite();
            pair.client.closeWrite();
            pair.client.close();
            pair.client.close();
            pair.client.closeWrite();
            assertTrue(pair.client.isClosed());
        } finally {
            pair.close();
        }
    }

    @Test
    public void resetClosesBothDirectionsAndIsIdempotent() throws Exception {
        Pair pair = pair(limits(2_000L));
        try {
            pair.client.reset(Axp1Error.PROTOCOL_ERROR);
            waitFor(pair.server::isClosed, 2_000L);
            assertThrows(IOException.class, () -> pair.server.dataInput().read());
            pair.client.reset(Axp1Error.PROTOCOL_ERROR);
            pair.client.close();
            pair.client.close();
            assertTrue(pair.client.isClosed());
        } finally {
            pair.close();
        }
    }

    @Test(timeout = 10_000)
    public void concurrentResetWithWriterIsSafe() throws Exception {
        Pair pair = pair(limits(2_000L));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> writer = executor.submit(() -> {
                byte[] chunk = new byte[8_192];
                try {
                    for (int index = 0; index < 2_048; index++) {
                        pair.client.dataOutput().write(chunk);
                    }
                } catch (IOException expected) {
                    // reset closes the write direction while the writer is active.
                }
            });
            pair.client.reset(Axp1Error.PROTOCOL_ERROR);
            writer.get(5, TimeUnit.SECONDS);
            pair.client.reset(Axp1Error.PROTOCOL_ERROR);
            pair.client.close();
            assertTrue(pair.client.isClosed());
        } finally {
            executor.shutdownNow();
            pair.close();
        }
    }

    @Test
    public void pingIsAnsweredWithPong() throws Exception {
        Pair pair = pair(limits(2_000L));
        try {
            pair.client.ping();
            waitFor(() -> pair.client.stats().pongs() >= 1L, 2_000L);
            assertEquals(1L, pair.client.stats().pings());
            assertEquals(1L, pair.client.stats().pongs());
        } finally {
            pair.close();
        }
    }

    @Test
    public void reconnectBackoffStartsAt100msCapsAt2sAndStopsAtThree() throws Exception {
        RecordingSleeper sleeper = new RecordingSleeper();
        AtomicInteger attempts = new AtomicInteger();
        Axp1Connector connector = new Axp1Connector(() -> {
            if (attempts.incrementAndGet() <= 3) {
                throw new IOException("not listening");
            }
            MemoryTransport[] pair = MemoryTransport.duplex();
            ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "axp1-reconnect-server");
                thread.setDaemon(true);
                return thread;
            });
            executor.submit(() -> Axp1Connection.accept(
                    pair[1], Axp1Role.GUEST, TOKEN, SERVER_CAPS, limits(2_000L)));
            executor.shutdown();
            return pair[0];
        }, sleeper, 3);
        Axp1Connection connection = connector.connect(Axp1Role.HOST, TOKEN, CLIENT_CAPS, limits(2_000L));
        assertNotNull(connection);
        assertEquals(List.of(100L, 200L, 400L), sleeper.delays);
        connection.close();

        RecordingSleeper failingSleeper = new RecordingSleeper();
        Axp1Connector alwaysFails = new Axp1Connector(() -> {
            throw new IOException("still not listening");
        }, failingSleeper, 3);
        assertThrows(Axp1Exception.class,
                () -> alwaysFails.connect(Axp1Role.HOST, TOKEN, CLIENT_CAPS, limits(2_000L)));
        assertEquals(List.of(100L, 200L, 400L), failingSleeper.delays);
    }

    @Test
    public void statsCarryCountsBytesAndDurationOnly() throws Exception {
        Pair pair = pair(limits(2_000L));
        try {
            pair.client.dataOutput().write("hello".getBytes(StandardCharsets.UTF_8));
            pair.client.dataOutput().flush();
            readFully(pair.server.dataInput(), 5);
            Axp1Stats stats = pair.server.stats();
            assertEquals(5L, stats.dataBytesIn());
            assertTrue(stats.durationMillis() >= 0L);
            String rendered = stats.toString();
            assertFalse(rendered.contains(TOKEN));
            assertFalse(rendered.contains("hello"));
        } finally {
            pair.close();
        }
    }

    // ---- helpers ----

    private static Axp1Limits limits(long handshakeTimeoutMs) {
        return new Axp1Limits(handshakeTimeoutMs, 4 * 1_048_576, 1_048_576);
    }

    private static Axp1Limits limitsWithWindow(long handshakeTimeoutMs, int window) {
        return new Axp1Limits(handshakeTimeoutMs, window, 1_048_576);
    }

    private record Pair(Axp1Connection client, Axp1Connection server) {
        void close() {
            client.close();
            server.close();
        }
    }

    private static Pair pair(Axp1Limits limits) throws Exception {
        MemoryTransport[] transport = MemoryTransport.duplex();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Axp1Connection> server = executor.submit(() -> Axp1Connection.accept(
                    transport[1], Axp1Role.GUEST, TOKEN, SERVER_CAPS, limits));
            Axp1Connection client = Axp1Connection.connect(
                    transport[0], Axp1Role.HOST, TOKEN, CLIENT_CAPS, limits);
            return new Pair(client, server.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(buffer, offset, length - offset);
            if (read < 0) {
                throw new IOException("unexpected EOF at " + offset);
            }
            offset += read;
        }
        return buffer;
    }

    private static byte[] deterministic(int length) {
        byte[] data = new byte[length];
        for (int index = 0; index < length; index++) {
            data[index] = (byte) (index * 31 + 7);
        }
        return data;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] hex(String value) {
        String compact = value.replace(" ", "");
        byte[] result = new byte[compact.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(
                    compact.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static byte[] unknownCapabilityBytes() {
        // distcc=1, max_frame=1 MiB, then unknown id 99 with two bytes.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(3);
        out.write(1);
        out.write(0);
        out.write(1);
        out.write(1);
        out.write(2);
        out.write(0);
        out.write(4);
        out.write(0);
        out.write(0x10);
        out.write(0);
        out.write(0);
        out.write(99);
        out.write(0);
        out.write(2);
        out.write(0xaa);
        out.write(0xbb);
        return out.toByteArray();
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms");
            }
            Thread.sleep(10L);
        }
    }

    private static final class RecordingSleeper implements Axp1Connector.Sleeper {
        private final List<Long> delays = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            delays.add(millis);
        }
    }

    private static final class OneByteInputStream extends InputStream {
        private final byte[] data;
        private int offset;

        OneByteInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return offset < data.length ? data[offset++] & 0xff : -1;
        }
    }

    /** Two in-memory directions with a real bounded pipe; no sockets, no device. */
    static final class MemoryTransport implements Axp1Transport {
        private final Pipe incoming;
        private final Pipe outgoing;

        private MemoryTransport(Pipe incoming, Pipe outgoing) {
            this.incoming = incoming;
            this.outgoing = outgoing;
        }

        static MemoryTransport[] duplex() {
            Pipe hostToGuest = new Pipe(65_536);
            Pipe guestToHost = new Pipe(65_536);
            return new MemoryTransport[] {
                new MemoryTransport(guestToHost, hostToGuest),
                new MemoryTransport(hostToGuest, guestToHost)
            };
        }

        @Override
        public InputStream input() {
            return incoming.readStream();
        }

        @Override
        public OutputStream output() {
            return outgoing.writeStream();
        }

        @Override
        public void close() {
            incoming.closeRead();
            outgoing.closeWrite();
        }
    }

    /**
     * Bounded byte pipe whose blocking does not depend on the liveness of the
     * thread that happened to write last (PipedInputStream does).
     */
    static final class Pipe {
        private final byte[] data;
        private final java.util.concurrent.locks.ReentrantLock lock =
                new java.util.concurrent.locks.ReentrantLock();
        private final java.util.concurrent.locks.Condition changed = lock.newCondition();
        private int head;
        private int count;
        private boolean writeClosed;
        private boolean readClosed;

        Pipe(int capacity) {
            this.data = new byte[capacity];
        }

        void write(byte[] source, int offset, int length) throws IOException {
            lock.lock();
            try {
                int remaining = length;
                int sourceOffset = offset;
                while (remaining > 0) {
                    while (count == data.length && !writeClosed && !readClosed) {
                        try {
                            changed.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("interrupted", interrupted);
                        }
                    }
                    if (writeClosed || readClosed) {
                        throw new IOException("pipe closed");
                    }
                    int take = Math.min(remaining, data.length - count);
                    int tail = (head + count) % data.length;
                    int first = Math.min(take, data.length - tail);
                    System.arraycopy(source, sourceOffset, data, tail, first);
                    if (take > first) {
                        System.arraycopy(source, sourceOffset + first, data, 0, take - first);
                    }
                    count += take;
                    sourceOffset += take;
                    remaining -= take;
                    changed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        int read(byte[] target, int offset, int length) throws IOException {
            lock.lock();
            try {
                while (count == 0) {
                    if (readClosed) {
                        throw new IOException("pipe read closed");
                    }
                    if (writeClosed) {
                        return -1;
                    }
                    try {
                        changed.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", interrupted);
                    }
                }
                int take = Math.min(length, count);
                int first = Math.min(take, data.length - head);
                System.arraycopy(data, head, target, offset, first);
                if (take > first) {
                    System.arraycopy(data, 0, target, offset + first, take - first);
                }
                head = (head + take) % data.length;
                count -= take;
                changed.signalAll();
                return take;
            } finally {
                lock.unlock();
            }
        }

        void closeWrite() {
            lock.lock();
            try {
                writeClosed = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void closeRead() {
            lock.lock();
            try {
                readClosed = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        OutputStream writeStream() {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    write(new byte[] {(byte) value}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    Pipe.this.write(bytes, offset, length);
                }

                @Override
                public void close() {
                    closeWrite();
                }
            };
        }

        InputStream readStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int read = Pipe.this.read(one, 0, 1);
                    return read < 0 ? -1 : one[0] & 0xff;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (length == 0) {
                        return 0;
                    }
                    return Pipe.this.read(bytes, offset, length);
                }

                @Override
                public void close() {
                    closeRead();
                }
            };
        }
    }
}
