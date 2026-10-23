package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Capabilities;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Connection;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Error;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Exception;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Limits;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Role;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Transport;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * AX-06: the host-side AXP1 layer for the distcc bridge.
 *
 * <p>Each accepted TCP connection is already mapped to one
 * {@link FastLaneChannel}. This factory runs the AXP1 HOST handshake on that
 * channel, verifies the accelerator advertised {@code distcc} and the expected
 * compiler triple, then exposes the AXP1 DATA stream unchanged. It never
 * parses or rewrites distcc bytes.
 *
 * <p>Failures carry only fixed reason codes. They never include the token,
 * peer capability values, source text or object bytes.
 */
public final class Axp1DistccChannelFactory
        implements DistccTcpVsockBridge.ChannelFactory {
    public static final String AXP1_AUTH_FAILED = "AXP1_AUTH_FAILED";
    public static final String AXP1_HANDSHAKE_TIMEOUT = "AXP1_HANDSHAKE_TIMEOUT";
    public static final String AXP1_VERSION_MISMATCH = "AXP1_VERSION_MISMATCH";
    public static final String AXP1_PROTOCOL_ERROR = "AXP1_PROTOCOL_ERROR";
    public static final String AXP1_FRAME_TOO_LARGE = "AXP1_FRAME_TOO_LARGE";
    public static final String AXP1_CLOSED = "AXP1_CLOSED";
    public static final String COMPILER_MISMATCH = "COMPILER_MISMATCH";
    public static final String DISTCC_UNSUPPORTED = "DISTCC_UNSUPPORTED";

    private final DistccTcpVsockBridge.ChannelFactory channels;
    private final String token;
    private final Axp1Capabilities hostCapabilities;
    private final String requiredCompilerTriple;
    private final Axp1Limits limits;

    public Axp1DistccChannelFactory(
            DistccTcpVsockBridge.ChannelFactory channels,
            String token,
            Axp1Capabilities hostCapabilities,
            String requiredCompilerTriple,
            Axp1Limits limits) {
        this.channels = Objects.requireNonNull(channels, "channels");
        this.token = Objects.requireNonNull(token, "token");
        if (!token.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("token must be 32 lowercase hex characters");
        }
        this.hostCapabilities = Objects.requireNonNull(hostCapabilities, "hostCapabilities");
        this.requiredCompilerTriple =
                Objects.requireNonNull(requiredCompilerTriple, "requiredCompilerTriple");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    @Override
    public FastLaneChannel open() throws FastLaneException, IOException {
        FastLaneChannel raw = channels.open();
        Axp1Connection connection;
        try {
            connection = Axp1Connection.connect(
                    Axp1Transport.of(raw), Axp1Role.HOST, token, hostCapabilities, limits);
        } catch (Axp1Exception failure) {
            closeQuietly(raw);
            throw new FastLaneException(
                    reasonCode(failure.code()), "AXP1 handshake failed", failure);
        }
        Axp1Capabilities peer = connection.peerCapabilities();
        if (!peer.distcc()) {
            connection.close();
            throw new FastLaneException(
                    DISTCC_UNSUPPORTED, "accelerator does not advertise distcc");
        }
        String peerTriple = peer.compilerTriple();
        if (peerTriple.isBlank() || !requiredCompilerTriple.equals(peerTriple)) {
            connection.close();
            throw new FastLaneException(
                    COMPILER_MISMATCH, "accelerator compiler triple does not match");
        }
        return new Axp1Channel(connection);
    }

    public static String reasonCode(Axp1Error error) {
        return switch (error) {
            case AUTH_FAILED -> AXP1_AUTH_FAILED;
            case TIMEOUT -> AXP1_HANDSHAKE_TIMEOUT;
            case VERSION_MISMATCH -> AXP1_VERSION_MISMATCH;
            case FRAME_TOO_LARGE -> AXP1_FRAME_TOO_LARGE;
            case PROTOCOL_ERROR -> AXP1_PROTOCOL_ERROR;
            case CLOSED -> AXP1_CLOSED;
        };
    }

    private static void closeQuietly(FastLaneChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // The failed handshake already owns the terminal reason.
        }
    }

    private static final class Axp1Channel implements FastLaneChannel {
        private final Axp1Connection connection;

        private Axp1Channel(Axp1Connection connection) {
            this.connection = connection;
        }

        @Override
        public InputStream inputStream() {
            return connection.dataInput();
        }

        @Override
        public OutputStream outputStream() {
            OutputStream delegate = connection.dataOutput();
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    write(new byte[] {(byte) value}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    try {
                        delegate.write(bytes, offset, length);
                    } catch (Axp1Exception failure) {
                        throw failure;
                    } catch (IOException failure) {
                        throw new Axp1Exception(
                                Axp1Error.CLOSED, "AXP1 transport write failed", failure);
                    }
                }

                @Override
                public void flush() throws IOException {
                    delegate.flush();
                }

                @Override
                public void close() throws IOException {
                    connection.closeWrite();
                }
            };
        }

        @Override
        public void close() throws IOException {
            connection.close();
        }
    }
}
