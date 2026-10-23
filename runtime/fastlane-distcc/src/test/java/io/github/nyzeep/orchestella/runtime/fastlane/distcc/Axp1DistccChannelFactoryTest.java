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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        Thread server = guest(pipe, TOKEN, capabilities(true, TRIPLE), connection -> {
            try {
                assertArrayEquals(request, connection.dataInput().readNBytes(request.length));
                connection.dataOutput().write(response);
                connection.dataOutput().flush();
                connection.closeWrite();
            } finally {
                served.countDown();
            }
        });
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
        Thread thread = new Thread(() -> {
            Axp1Connection connection = null;
            try {
                connection = Axp1Connection.accept(
                        pipe.serverTransport(), Axp1Role.GUEST, token, capabilities, LIMITS);
                action.run(connection);
            } catch (IOException ignored) {
                // Expected auth/close paths are asserted on the host side.
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

    private static final class Pipe implements Closeable {
        private final PipedInputStream clientInput;
        private final PipedOutputStream clientOutput;
        private final PipedInputStream serverInput;
        private final PipedOutputStream serverOutput;

        Pipe() throws IOException {
            clientInput = new PipedInputStream(64 * 1024);
            serverOutput = new PipedOutputStream(clientInput);
            serverInput = new PipedInputStream(64 * 1024);
            clientOutput = new PipedOutputStream(serverInput);
        }

        FastLaneChannel clientChannel() {
            return new FastLaneChannel() {
                @Override
                public InputStream inputStream() {
                    return clientInput;
                }

                @Override
                public OutputStream outputStream() {
                    return clientOutput;
                }

                @Override
                public void close() throws IOException {
                    Pipe.this.close();
                }
            };
        }

        Axp1Transport serverTransport() {
            return new Axp1Transport() {
                @Override
                public InputStream input() {
                    return serverInput;
                }

                @Override
                public OutputStream output() {
                    return serverOutput;
                }

                @Override
                public void close() throws IOException {
                    // Close only the guest directions; closing the shared pipe
                    // would discard an RST the host has not read yet.
                    IOException first = null;
                    for (Closeable closeable : new Closeable[] {serverInput, serverOutput}) {
                        try {
                            closeable.close();
                        } catch (IOException failure) {
                            if (first == null) {
                                first = failure;
                            }
                        }
                    }
                    if (first != null) {
                        throw first;
                    }
                }
            };
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            for (Closeable closeable : new Closeable[] {
                    clientOutput, serverInput, serverOutput, clientInput
            }) {
                try {
                    closeable.close();
                } catch (IOException failure) {
                    if (first == null) {
                        first = failure;
                    }
                }
            }
            if (first != null) {
                throw first;
            }
        }
    }
}
