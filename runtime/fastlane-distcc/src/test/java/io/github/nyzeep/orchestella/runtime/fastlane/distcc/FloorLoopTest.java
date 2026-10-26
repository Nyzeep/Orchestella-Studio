package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Capabilities;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Connection;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Error;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Limits;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Role;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Transport;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

/**
 * DV-PVM-03: the 60 s floor loop. Host only, no device, under a second per case.
 *
 * <p>The device measurement says every crossfire job costs about 60 s of wall
 * clock that no measured segment explains. This class reproduces the decision
 * point in-process: a real {@link DistccTcpVsockBridge} on a loopback port, the
 * real {@link Axp1DistccChannelFactory} in front of it, and a stub payload that
 * speaks real AXP1 on an in-memory duplex link. Nothing here touches vsock,
 * QEMU or the payload's libclang.
 *
 * <p>The four cases separate the three ways the 60 s can exist:
 *
 * <ul>
 *   <li>{@code d0a} — client half-closes, payload answers and sends EOF: the
 *       bridge's own teardown must be milliseconds.
 *   <li>{@code d0b} — client half-closes, payload answers but never sends EOF:
 *       measures what "the peer never says it is done" costs, and attributes it
 *       to the bridge idle watchdog rather than to a hidden constant.
 *   <li>{@code d0c} — payload stays completely silent: exposes every hidden
 *       timeout on the path.
 *   <li>{@code d0d} — client writes the request and does <em>not</em>
 *       half-close, which is what the real distcc client does. This case decides
 *       whether the request ever reaches the payload at all.
 * </ul>
 *
 * <p>These numbers are a mechanism verdict, never a performance result. Do not
 * quote them as an acceleration figure.
 */
public final class FloorLoopTest {
    private static final String TOKEN = AcceleratorUplink.newToken();
    private static final String TRIPLE = "x86_64-linux-gnu";
    private static final Axp1Limits LIMITS = new Axp1Limits(2_000L, 1_048_576, 1_048_576);

    /** Bridge idle watchdog used by d0b/d0c, deliberately far below 60 s. */
    private static final int IDLE_TIMEOUT_MS = 1_500;

    /** Payload-side per-frame blocking read, mirroring {@code frame_timeout_ms}. */
    private static final int PAYLOAD_FRAME_TIMEOUT_MS = 800;

    private static final List<String> LINES = new ArrayList<>();
    private static final Path REPORT_DIR = Path.of("build", "floor-loop");
    private static final String REPORT_FILE = "floor-loop-report.txt";

    private final List<Closeable> open = new ArrayList<>();

    /** One report file per run, so the numbers in a report always match a run. */
    @BeforeClass
    public static void resetReport() throws IOException {
        Files.createDirectories(REPORT_DIR);
        Files.writeString(REPORT_DIR.resolve(REPORT_FILE), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @After
    public void release() {
        for (Closeable resource : open) {
            try {
                resource.close();
            } catch (IOException ignored) {
                // Teardown is best effort; the assertions already ran.
            }
        }
        open.clear();
    }

    /** Ends a normal request/response: the client half-closes and the peer sends EOF. */
    @Test(timeout = 20_000)
    public void d0aHalfCloseAndPeerEofIsMilliseconds() throws Exception {
        byte[] request = distccRequest(2048);
        byte[] response = "DOTO00000010".getBytes(StandardCharsets.US_ASCII);

        try (Harness harness = harness(true, true, 0L, 5_000)) {
            ClientRun run = harness.client.exchange(request, true, 8_000L);
            assertArrayEquals("the response bytes must arrive unchanged", response, run.response);
            assertTrue("d0a: client never saw EOF", run.eofNanos > 0);
            long eofMs = millis(run.eofNanos - run.requestSentNanos);
            record("d0a", "eof_after_ms=" + eofMs + " bytes=" + run.response.length);
            assertTrue("d0a: bridge needs " + eofMs + " ms to close after the peer's EOF",
                    eofMs < 1_000L);
        }
    }

    /**
     * The peer answers but never sends its AXP1 EOF frame. The client cannot
     * distinguish "response finished" from "response still arriving", so the
     * only exit is the bridge's idle watchdog — which is what we must see here.
     */
    @Test(timeout = 20_000)
    public void d0bPeerWithoutEofCostsTheBridgeIdleTimeout() throws Exception {
        byte[] request = distccRequest(2048);

        try (Harness harness = harness(true, false, 0L, IDLE_TIMEOUT_MS)) {
            ClientRun run = harness.client.exchange(request, true, 12_000L);
            assertTrue("d0b: the response bytes never reached the client", run.response.length > 0);
            assertTrue("d0b: client never observed the bridge closing", run.eofNanos > 0);
            long waitedMs = millis(run.eofNanos - run.requestSentNanos);
            harness.awaitStats(2_000L);
            record("d0b", "wait_ms=" + waitedMs + " idle_ms=" + IDLE_TIMEOUT_MS
                    + " outcome=" + harness.statsOutcome());
            assertTrue("d0b: waited " + waitedMs + " ms, below the idle watchdog",
                    waitedMs >= IDLE_TIMEOUT_MS - 300L);
            assertTrue("d0b: waited " + waitedMs + " ms, far beyond the idle watchdog",
                    waitedMs < IDLE_TIMEOUT_MS + 1_500L);
        }
    }

    /** No response at all: every hidden timeout on the path shows up here. */
    @Test(timeout = 20_000)
    public void d0cSilentPeerCostsOnlyTheBridgeIdleTimeout() throws Exception {
        byte[] request = distccRequest(2048);

        try (Harness harness = harness(false, false, 0L, IDLE_TIMEOUT_MS)) {
            ClientRun run = harness.client.exchange(request, true, 12_000L);
            long waitedMs = millis(run.eofNanos - run.requestSentNanos);
            harness.awaitStats(2_000L);
            record("d0c", "wait_ms=" + waitedMs + " idle_ms=" + IDLE_TIMEOUT_MS
                    + " bytes=" + run.response.length + " outcome=" + harness.statsOutcome());
            assertTrue("d0c: silent peer ended without the bridge closing the client",
                    run.eofNanos > 0);
            assertTrue("d0c: waited " + waitedMs + " ms, below the idle watchdog",
                    waitedMs >= IDLE_TIMEOUT_MS - 300L);
            assertTrue("d0c: waited " + waitedMs + " ms; a hidden timeout is in the path",
                    waitedMs < IDLE_TIMEOUT_MS + 1_500L);
            assertEquals("d0c: a silent peer must not produce a response", 0, run.response.length);
        }
    }

    /**
     * The shape the real client actually has. distcc ends a request with the
     * {@code DOTI} length prefix and then reads the response on the same socket;
     * it never calls {@code shutdown(SHUT_WR)}. This case records what the bridge
     * does with that shape today — every request byte is forwarded, but the
     * payload never learns the request ended.
     */
    @Test(timeout = 20_000)
    public void d0dRequestWithoutHalfCloseNeverReachesThePayload() throws Exception {
        Shape shape = runDistccShapedClient();

        assertEquals("d0d: the bridge must forward every request byte",
                shape.requestBytes, shape.payloadBytesReceived);
        assertTrue("d0d: the payload must not see an end-of-request it was never sent",
                !shape.payloadSawEof);
        assertTrue("d0d: the payload must fall through to its frame timeout",
                shape.payloadTimedOut);
        assertEquals("d0d: a timed-out request must produce no distcc bytes at all",
                0, shape.clientBytes);
    }

    /**
     * The same shape, asserted the way it must behave after PVM-10. Kept
     * disabled on purpose: this branch is a diagnosis, and a red suite would
     * block every other ticket. Enable it once end-of-request no longer depends
     * on a client half-close; it is the DV-PVM-03 regression test.
     */
    @Test(timeout = 20_000)
    @Ignore("PVM-10: enable when AXP1 end-of-request no longer depends on TCP half-close")
    public void pvm10RegressionRequestWithoutHalfCloseMustReachThePayload() throws Exception {
        Shape shape = runDistccShapedClient();
        assertTrue("the payload never saw the end of the request without a client half-close",
                shape.payloadSawEof);
        assertTrue("the payload must answer without waiting for its frame timeout",
                shape.clientBytes > 0);
    }

    private Shape runDistccShapedClient() throws Exception {
        byte[] request = distccRequest(2048);
        try (Harness harness = harness(true, true, 0L, 8_000)) {
            ClientRun run = harness.client.exchange(request, false, 6_000L);
            harness.awaitPayload(4_000L);
            harness.awaitStats(2_000L);
            record("d0d", "half_close=no payload_saw_eof=" + harness.payload.sawEof.get()
                    + " payload_bytes=" + harness.payload.bytesIn.get()
                    + " payload_timed_out=" + harness.payload.timedOut.get()
                    + " client_bytes=" + run.response.length
                    + " outcome=" + harness.statsOutcome());
            return new Shape(request.length, harness.payload.bytesIn.get(),
                    harness.payload.sawEof.get(), harness.payload.timedOut.get(),
                    run.response.length);
        }
    }

    private record Shape(int requestBytes, int payloadBytesReceived, boolean payloadSawEof,
            boolean payloadTimedOut, int clientBytes) {}

    // ---------------------------------------------------------------- harness

    private Harness harness(boolean answer, boolean sendEof, long delayBeforeAnswerMs,
            int idleTimeoutMs) throws Exception {
        Duplex duplex = new Duplex();
        open.add(duplex);
        StubPayload payload = new StubPayload(duplex, answer, sendEof, delayBeforeAnswerMs);
        payload.start();
        open.add(payload);

        AtomicReference<DistccTcpVsockBridge.ConnectionStats> last = new AtomicReference<>();
        DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                InetAddress.getByName("127.0.0.1"),
                0,
                new Axp1DistccChannelFactory(
                        duplex::clientChannel, TOKEN, capabilities(), TRIPLE, LIMITS),
                1,
                0,
                idleTimeoutMs,
                last::set);
        bridge.start();
        open.add(bridge);

        RawClient client = new RawClient(bridge.port());
        open.add(client);
        return new Harness(client, payload, last);
    }

    private static Axp1Capabilities capabilities() {
        return Axp1Capabilities.builder()
                .distcc(true)
                .compilerTriple(TRIPLE)
                .arch("x86_64")
                .maxFrame(1_048_576)
                .build();
    }

    /** A v1 distcc request: DIST + ARGC + ARGV + DOTI with a length prefix. */
    private static byte[] distccRequest(int sourceBytes) {
        String header = "DIST00000001"
                + "ARGC00000005"
                + "ARGV00000005clang"
                + "ARGV00000002-c"
                + "ARGV00000004test"
                + "ARGV00000002-o"
                + "ARGV00000007out.o";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = header.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(head);
        out.writeBytes(("DOTI" + String.format("%08x", sourceBytes))
                .getBytes(StandardCharsets.US_ASCII));
        byte[] body = new byte[sourceBytes];
        for (int index = 0; index < body.length; index++) {
            body[index] = (byte) ('a' + (index % 26));
        }
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    private static void record(String id, String detail) {
        LINES.add("[floor-loop] " + id + " " + detail);
    }

    /** Appends the timing lines to a file the report can quote verbatim. */
    @After
    public void flushReport() throws IOException {
        if (LINES.isEmpty()) {
            return;
        }
        Files.createDirectories(REPORT_DIR);
        Files.write(REPORT_DIR.resolve(REPORT_FILE),
                (String.join(System.lineSeparator(), LINES) + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        LINES.clear();
    }

    private record Harness(
            RawClient client,
            StubPayload payload,
            AtomicReference<DistccTcpVsockBridge.ConnectionStats> stats) implements Closeable {
        String statsOutcome() {
            DistccTcpVsockBridge.ConnectionStats value = stats.get();
            return value == null ? "none" : value.outcome().name() + "/" + value.durationMillis();
        }

        void awaitPayload(long millis) throws InterruptedException {
            payload.finished.await(millis, TimeUnit.MILLISECONDS);
        }

        /** Waits for the bridge to publish this connection's terminal stats. */
        void awaitStats(long millis) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (stats.get() == null && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
        }

        /** Teardown is owned by the enclosing {@code open} list, not by this handle. */
        @Override
        public void close() {
            // Intentionally empty.
        }
    }

    private record ClientRun(byte[] response, long connectNanos, long requestSentNanos,
            long firstByteNanos, long eofNanos) {}

    /** Bare socket client with the distcc client's shape and four timestamps. */
    private static final class RawClient implements Closeable {
        private final int port;
        private Socket socket;

        private RawClient(int port) {
            this.port = port;
        }

        ClientRun exchange(byte[] request, boolean halfClose, long readBudgetMs) throws IOException {
            Socket client = new Socket();
            socket = client;
            long connectAt = System.nanoTime();
            client.setTcpNoDelay(true);
            // Bounded reads: the exchange loop owns the deadline, not the socket.
            client.setSoTimeout(100);
            client.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 2_000);
            OutputStream out = client.getOutputStream();
            out.write(request);
            out.flush();
            long sentAt = System.nanoTime();
            if (halfClose) {
                client.shutdownOutput();
            }
            InputStream in = client.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long firstByte = 0L;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readBudgetMs);
            long eof = 0L;
            while (System.nanoTime() < deadline) {
                int read;
                try {
                    read = in.read(buffer);
                } catch (java.net.SocketTimeoutException idle) {
                    continue;
                }
                if (read < 0) {
                    eof = System.nanoTime();
                    break;
                }
                if (read == 0) {
                    continue;
                }
                if (firstByte == 0L) {
                    firstByte = System.nanoTime();
                }
                response.write(buffer, 0, read);
            }
            return new ClientRun(response.toByteArray(), connectAt, sentAt, firstByte, eof);
        }

        @Override
        public void close() throws IOException {
            Socket client = socket;
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * Stands in for the payload's AXP1 server: accumulate DATA until the EOF
     * frame, answer, optionally send its own EOF. When no EOF arrives within
     * {@link #PAYLOAD_FRAME_TIMEOUT_MS} it resets with the same code the C++
     * {@code frame_timeout_ms} path uses.
     */
    private static final class StubPayload extends Thread implements Closeable {
        private final Duplex duplex;
        private final boolean answer;
        private final boolean sendEof;
        private final long delayBeforeAnswerMs;
        private final AtomicBoolean sawEof = new AtomicBoolean();
        private final AtomicBoolean timedOut = new AtomicBoolean();
        private final AtomicInteger bytesIn = new AtomicInteger();
        private final CountDownLatch finished = new CountDownLatch(1);

        private StubPayload(Duplex duplex, boolean answer, boolean sendEof,
                long delayBeforeAnswerMs) {
            super("floor-loop-stub-payload");
            this.duplex = duplex;
            this.answer = answer;
            this.sendEof = sendEof;
            this.delayBeforeAnswerMs = delayBeforeAnswerMs;
            setDaemon(true);
        }

        @Override
        public void run() {
            Axp1Connection connection = null;
            try {
                connection = Axp1Connection.accept(
                        duplex.serverTransport(), Axp1Role.GUEST, TOKEN, capabilities(), LIMITS);
                Axp1Connection accepted = connection;
                Thread watchdog = new Thread(() -> {
                    try {
                        Thread.sleep(PAYLOAD_FRAME_TIMEOUT_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!sawEof.get() && !accepted.isClosed()) {
                        timedOut.set(true);
                        accepted.reset(Axp1Error.TIMEOUT);
                    }
                }, "floor-loop-payload-frame-timeout");
                watchdog.setDaemon(true);
                watchdog.start();

                InputStream in = connection.dataInput();
                byte[] buffer = new byte[8192];
                while (true) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        sawEof.set(true);
                        break;
                    }
                    bytesIn.addAndGet(read);
                }
                watchdog.interrupt();
                if (!answer) {
                    return;
                }
                if (delayBeforeAnswerMs > 0L) {
                    try {
                        Thread.sleep(delayBeforeAnswerMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] response = "DOTO00000010".getBytes(StandardCharsets.US_ASCII);
                OutputStream out = connection.dataOutput();
                out.write(response);
                out.flush();
                if (sendEof) {
                    connection.closeWrite();
                }
            } catch (IOException | RuntimeException expected) {
                // A reset path or a test teardown: the assertions are on the
                // host side and on the counters above.
            } finally {
                finished.countDown();
            }
        }

        @Override
        public void close() {
            interrupt();
        }
    }

    /**
     * Deterministic in-memory duplex: two unbounded chunk queues. No piped
     * streams, so thread death cannot corrupt a run (see
     * {@code Axp1DistccChannelFactoryTest} for the same reasoning).
     */
    private static final class Duplex implements Closeable {
        private final OneWay bridgeToPayload = new OneWay();
        private final OneWay payloadToBridge = new OneWay();

        FastLaneChannel clientChannel() {
            return new FastLaneChannel() {
                @Override
                public InputStream inputStream() {
                    return payloadToBridge.input();
                }

                @Override
                public OutputStream outputStream() {
                    return bridgeToPayload.output();
                }

                @Override
                public void close() {
                    Duplex.this.close();
                }
            };
        }

        Axp1Transport serverTransport() {
            return new Axp1Transport() {
                @Override
                public InputStream input() {
                    return bridgeToPayload.input();
                }

                @Override
                public OutputStream output() {
                    return payloadToBridge.output();
                }

                @Override
                public void close() {
                    payloadToBridge.closeWriter();
                    bridgeToPayload.closeReader();
                }
            };
        }

        @Override
        public void close() {
            bridgeToPayload.close();
            payloadToBridge.close();
        }
    }

    private static final class OneWay {
        private static final byte[] EOF = new byte[0];

        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private final InputStream input = new QueueInput();
        private final OutputStream output = new QueueOutput();

        InputStream input() {
            return input;
        }

        OutputStream output() {
            return output;
        }

        void closeWriter() {
            ((QueueOutput) output).close();
        }

        void closeReader() {
            try {
                input.close();
            } catch (IOException ignored) {
                // Nothing to release.
            }
        }

        void close() {
            closeWriter();
            closeReader();
        }

        private final class QueueInput extends InputStream {
            private byte[] current = EOF;
            private int offset;
            private boolean endOfStream;
            private boolean closed;

            @Override
            public int read() throws IOException {
                return fill() ? current[offset++] & 0xFF : -1;
            }

            @Override
            public int read(byte[] target, int targetOffset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (!fill()) {
                    return -1;
                }
                int copied = Math.min(length, current.length - offset);
                System.arraycopy(current, offset, target, targetOffset, copied);
                offset += copied;
                return copied;
            }

            @Override
            public int available() {
                return current.length - offset;
            }

            @Override
            public void close() {
                closed = true;
                chunks.offer(EOF);
            }

            private boolean fill() throws IOException {
                while (offset >= current.length) {
                    if (endOfStream) {
                        return false;
                    }
                    if (closed && chunks.isEmpty()) {
                        throw new IOException("stream closed");
                    }
                    byte[] next;
                    try {
                        next = chunks.take();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted", interrupted);
                    }
                    if (next == EOF) {
                        endOfStream = true;
                        return false;
                    }
                    current = next;
                    offset = 0;
                }
                return true;
            }
        }

        private final class QueueOutput extends OutputStream {
            private boolean closed;

            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] source, int sourceOffset, int length) throws IOException {
                if (closed) {
                    throw new IOException("stream closed");
                }
                if (length == 0) {
                    return;
                }
                byte[] copy = new byte[length];
                System.arraycopy(source, sourceOffset, copy, 0, length);
                chunks.add(copy);
            }

            @Override
            public void flush() {
                // Chunks are handed over immediately.
            }

            @Override
            public void close() {
                if (!closed) {
                    closed = true;
                    chunks.add(EOF);
                }
            }
        }
    }

    /** Kept so the unused-method lint stays quiet on empty teardown paths. */
}
