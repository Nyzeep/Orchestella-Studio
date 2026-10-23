package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Capabilities;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Connection;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Limits;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Role;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Transport;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** AX-06: deterministic AXP1 wrapping around the transparent byte bridge. */
public final class Axp1DistccChannelFactoryTest {
    private static final String TOKEN = AcceleratorUplink.newToken();
    private static final String TRIPLE = "x86_64-linux-gnu";
    private static final Axp1Limits LIMITS = new Axp1Limits(1_000L, 1_048_576, 1_048_576);

    @Test
    public void tokenMustBeThirtyTwoLowercaseHexCharacters() {
        assertThrows(IllegalArgumentException.class, () -> new Axp1DistccChannelFactory(
                () -> {
                    throw new IOException("not reached");
                },
                "not-a-token",
                capabilities(true, TRIPLE),
                TRIPLE,
                LIMITS));
    }

    @Test(timeout = 5_000)
    public void successExposesTheAxp1DataBytesUnchanged() throws Exception {
        Pipe pipe = new Pipe();
        byte[] request = "distcc-request-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] response = "distcc-response-bytes".getBytes(StandardCharsets.UTF_8);
        CountDownLatch served = new CountDownLatch(1);
        AtomicReference<Throwable> guestFailure = new AtomicReference<>();
        Thread server = guest(pipe, TOKEN, capabilities(true, TRIPLE), connection -> {
            try {
                assertArrayEquals(request, connection.dataInput().readNBytes(request.length));
                connection.dataOutput().write(response);
                connection.dataOutput().flush();
                connection.closeWrite();
            } finally {
                served.countDown();
            }
        }, guestFailure);
        Axp1DistccChannelFactory factory = new Axp1DistccChannelFactory(
                pipe::clientChannel, TOKEN, capabilities(true, TRIPLE), TRIPLE, LIMITS);

        try (FastLaneChannel channel = factory.open()) {
            channel.outputStream().write(request);
            channel.outputStream().close();
            assertArrayEquals(response, channel.inputStream().readAllBytes());
        }
        assertEquals(true, served.await(2, TimeUnit.SECONDS));
        server.join(2_000L);
        pipe.close();
        // The guest half must not have failed silently: a swallowed transport
        // error used to surface later as an unexplained host-side write failure.
        if (guestFailure.get() != null) {
            throw new AssertionError("guest half failed", guestFailure.get());
        }
    }

    @Test(timeout = 5_000)
    public void wrongTokenMapsToTheFixedAuthCode() throws Exception {
        Pipe pipe = new Pipe();
        String wrongToken = AcceleratorUplink.newToken();
        Thread server = guest(pipe, wrongToken, capabilities(true, TRIPLE), connection -> { });
        Axp1DistccChannelFactory factory = new Axp1DistccChannelFactory(
                pipe::clientChannel, TOKEN, capabilities(true, TRIPLE), TRIPLE, LIMITS);

        FastLaneException failure = assertThrows(
                FastLaneException.class, factory::open);
        assertEquals(Axp1DistccChannelFactory.AXP1_AUTH_FAILED, failure.reason());
        server.join(2_000L);
        pipe.close();
    }

    @Test(timeout = 5_000)
    public void compilerMismatchMapsToTheFixedCode() throws Exception {
        Pipe pipe = new Pipe();
        Thread server = guest(pipe, TOKEN, capabilities(true, "aarch64-linux-gnu"),
                connection -> {
                    try {
                        connection.dataInput().read();
                    } catch (IOException ignored) {
                        // The host closes after the triple check.
                    }
                });
        Axp1DistccChannelFactory factory = new Axp1DistccChannelFactory(
                pipe::clientChannel, TOKEN, capabilities(true, TRIPLE), TRIPLE, LIMITS);

        FastLaneException failure = assertThrows(
                FastLaneException.class, factory::open);
        assertEquals(Axp1DistccChannelFactory.COMPILER_MISMATCH, failure.reason());
        server.join(2_000L);
        pipe.close();
    }

    @Test(timeout = 5_000)
    public void peerWithoutACompilerTripleIsRejected() throws Exception {
        Pipe pipe = new Pipe();
        Thread server = guest(pipe, TOKEN, capabilitiesWithoutTriple(),
                connection -> {
                    try {
                        connection.dataInput().read();
                    } catch (IOException ignored) {
                        // The host closes after the triple check.
                    }
                });
        Axp1DistccChannelFactory factory = new Axp1DistccChannelFactory(
                pipe::clientChannel, TOKEN, capabilities(true, TRIPLE), TRIPLE, LIMITS);

        FastLaneException failure = assertThrows(
                FastLaneException.class, factory::open);
        assertEquals(Axp1DistccChannelFactory.COMPILER_MISMATCH, failure.reason());
        server.join(2_000L);
        pipe.close();
    }

    @Test(timeout = 5_000)
    public void distccUnsupportedMapsToTheFixedCode() throws Exception {
        Pipe pipe = new Pipe();
        Thread server = guest(pipe, TOKEN, capabilities(false, TRIPLE),
                connection -> {
                    try {
                        connection.dataInput().read();
                    } catch (IOException ignored) {
                        // The host closes after the capability check.
                    }
                });
        Axp1DistccChannelFactory factory = new Axp1DistccChannelFactory(
                pipe::clientChannel, TOKEN, capabilities(true, TRIPLE), TRIPLE, LIMITS);

        FastLaneException failure = assertThrows(
                FastLaneException.class, factory::open);
        assertEquals(Axp1DistccChannelFactory.DISTCC_UNSUPPORTED, failure.reason());
        server.join(2_000L);
        pipe.close();
    }

    @Test(timeout = 5_000)
    public void silentPeerMapsToTheHandshakeTimeoutCode() throws Exception {
        Pipe pipe = new Pipe();
        Axp1DistccChannelFactory factory = new Axp1DistccChannelFactory(
                pipe::clientChannel,
                TOKEN,
                capabilities(true, TRIPLE),
                TRIPLE,
                new Axp1Limits(150L, 1_048_576, 1_048_576));

        FastLaneException failure = assertThrows(
                FastLaneException.class, factory::open);
        assertEquals(Axp1DistccChannelFactory.AXP1_HANDSHAKE_TIMEOUT, failure.reason());
        pipe.close();
    }

    private static Thread guest(
            Pipe pipe,
            String token,
            Axp1Capabilities capabilities,
            GuestAction action) {
        return guest(pipe, token, capabilities, action, new AtomicReference<>());
    }

    private static Thread guest(
            Pipe pipe,
            String token,
            Axp1Capabilities capabilities,
            GuestAction action,
            AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            Axp1Connection connection = null;
            try {
                connection = Axp1Connection.accept(
                        pipe.serverTransport(), Axp1Role.GUEST, token, capabilities, LIMITS);
                action.run(connection);
            } catch (IOException expectedOnRejectPaths) {
                // Auth/capability rejections are asserted on the host side; the
                // caller decides whether this guest was supposed to succeed.
                failure.set(expectedOnRejectPaths);
            } catch (Throwable unexpected) {
                failure.set(unexpected);
            }
            // Do not close the transport from the guest side: closing the
            // shared pipe would discard a response the host has not read yet.
            // The test's Pipe.close() owns teardown.
        }, "axp1-test-guest");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static Axp1Capabilities capabilitiesWithoutTriple() {
        return Axp1Capabilities.builder()
                .distcc(true)
                .arch("x86_64")
                .maxFrame(1_048_576)
                .build();
    }

    private static Axp1Capabilities capabilities(boolean distcc, String triple) {
        return Axp1Capabilities.builder()
                .distcc(distcc)
                .compilerTriple(triple)
                .arch(triple.split("-")[0])
                .maxFrame(1_048_576)
                .build();
    }

    @FunctionalInterface
    private interface GuestAction {
        void run(Axp1Connection connection) throws IOException;
    }

    /**
     * Deterministic in-memory duplex link.
     *
     * <p>Both directions are unbounded {@link BlockingQueue} chunk queues rather
     * than {@code PipedInputStream}/{@code PipedOutputStream}. The piped streams
     * are only valid while the writing thread that created them is still alive —
     * a dead reader turns every later write into {@code IOException("Pipe
     * broken")}, which made this test fail intermittently. A queue keeps the
     * bytes, so teardown order and thread death can no longer corrupt a
     * successful run.
     */
    private static final class Pipe implements Closeable {
        private final OneWayPipe clientToServer = new OneWayPipe();
        private final OneWayPipe serverToClient = new OneWayPipe();

        FastLaneChannel clientChannel() {
            return new FastLaneChannel() {
                @Override
                public InputStream inputStream() {
                    return serverToClient.input();
                }

                @Override
                public OutputStream outputStream() {
                    return clientToServer.output();
                }

                @Override
                public void close() {
                    Pipe.this.close();
                }
            };
        }

        Axp1Transport serverTransport() {
            return new Axp1Transport() {
                @Override
                public InputStream input() {
                    return clientToServer.input();
                }

                @Override
                public OutputStream output() {
                    return serverToClient.output();
                }

                @Override
                public void close() {
                    // Close only the guest directions; the host still owns its
                    // side and may have a response left to read.
                    serverToClient.closeWriter();
                    clientToServer.closeReader();
                }
            };
        }

        @Override
        public void close() {
            clientToServer.close();
            serverToClient.close();
        }
    }

    /** One direction: a writer, a reader and the chunks between them. */
    private static final class OneWayPipe {
        /** End-of-stream marker; a zero-length chunk can never be real data. */
        private static final byte[] EOF = new byte[0];

        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private final QueueInput input = new QueueInput();
        private final QueueOutput output = new QueueOutput();

        InputStream input() {
            return input;
        }

        OutputStream output() {
            return output;
        }

        void closeWriter() {
            output.close();
        }

        void closeReader() {
            input.close();
        }

        void close() {
            output.close();
            input.close();
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
                // Unblock a reader parked in fill(); queued bytes stay readable.
                chunks.offer(EOF);
            }

            /** Ensures the current chunk has bytes, or reports end of stream. */
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
                // Chunks are handed over immediately; there is nothing to flush.
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
}
