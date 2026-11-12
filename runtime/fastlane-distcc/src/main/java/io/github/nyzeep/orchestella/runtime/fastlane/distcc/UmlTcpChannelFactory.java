package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ACR 2026-11-13 方案 A (fabric-route doc D-1, Gate 1): the UML lane's channel
 * factory — the one new seam the compile bridge gained. The bridge's listener,
 * dual pump, outcome classification and stats are reused unchanged; the only
 * thing this class adds is how one channel of the bridge's
 * {@link DistccTcpVsockBridge.ChannelFactory} contract opens for the UML face:
 * a plain TCP connection to the UML guest's {@code distccd}.
 *
 * <p><b>Lane marking (D-1 wire design, unchanged).</b> The lane is the
 * transport itself: a vsock channel is the pVM payload lane, a TCP channel is
 * the UML lane. No byte of the wire protocol changes — the bridge never parses
 * distcc bytes and this factory never speaks any protocol either; it only
 * dials the guest endpoint and exposes the socket's two directions as the
 * channel. The UML-face side that executes across this channel implements the
 * face's own {@code UmlChannel} seam above this module; the launcher never
 * reaches into the bridge directly.
 *
 * <p><b>Security posture, identical to the bridge's listener.</b> The dial
 * target must be a loopback address: the UML guest's distccd is reached
 * through the device-local usermode network path (passt forwarding, the Gate
 * 2 device window's design), never across a LAN, and this factory has no
 * authentication of its own — the same "not a port-forward" rule the bridge's
 * listener lives by.
 *
 * <p>One {@link #open()} call = one TCP connection = one distcc job. The
 * caller (the bridge's per-connection pump, or the UML channel's execute)
 * owns the byte flow; closing the channel closes the socket. A connect
 * refusal (guest down, passt not forwarding yet) surfaces as
 * {@link IOException} — the bridge classifies it {@code CHANNEL_FAILED} and
 * the face's own contract evicts honestly, never faking a route.
 */
public final class UmlTcpChannelFactory implements DistccTcpVsockBridge.ChannelFactory {

    /** The dial target's connect timeout, bounded so a dead guest fails fast. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;

    private final InetSocketAddress endpoint;
    private final int connectTimeoutMs;

    /**
     * @param endpoint the UML guest's distccd endpoint as seen from the host
     *        (the passt-forwarded loopback address); must be loopback
     * @param connectTimeoutMs bounded connect wait in milliseconds, positive
     */
    public UmlTcpChannelFactory(InetSocketAddress endpoint, int connectTimeoutMs) {
        Objects.requireNonNull(endpoint, "endpoint");
        InetAddress address = endpoint.getAddress();
        if (address == null || !address.isLoopbackAddress()) {
            throw new IllegalArgumentException(
                    "the UML TCP lane dials a loopback endpoint only, got: " + endpoint);
        }
        if (endpoint.getPort() <= 0 || endpoint.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "endpoint port must be in [1, 65535], got: " + endpoint.getPort());
        }
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "connectTimeoutMs must be positive, got: " + connectTimeoutMs);
        }
        this.endpoint = endpoint;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /**
     * The shipped default timing: a bounded 3-second connect so a guest that
     * is not up yet fails the job instead of stalling the bridge's pump.
     */
    public static UmlTcpChannelFactory loopback(InetAddress loopback, int port) {
        return new UmlTcpChannelFactory(
                new InetSocketAddress(Objects.requireNonNull(loopback, "loopback"), port),
                DEFAULT_CONNECT_TIMEOUT_MS);
    }

    /** The lane's dial target (diagnostics only; never logged with payload bytes). */
    public InetSocketAddress endpoint() {
        return endpoint;
    }

    @Override
    public FastLaneChannel open() throws IOException {
        Socket socket = new Socket();
        boolean opened = false;
        try {
            socket.connect(endpoint, connectTimeoutMs);
            FastLaneChannel channel = new TcpChannel(socket);
            opened = true;
            return channel;
        } finally {
            if (!opened) {
                closeQuietly(socket);
            }
        }
    }

    /**
     * The socket's two directions as one channel. The streams are taken once
     * at open (a socket's streams are stable). The output stream is
     * half-close aware: closing it shuts down the socket's <b>output
     * direction only</b> ({@code shutdownOutput}, the distcc request
     * terminator), leaving the response direction readable — the exact shape
     * the bridge's pump drives. It must not close the whole socket: a plain
     * {@code Socket.getOutputStream().close()} would tear down both
     * directions and the response would never come back.
     */
    private static final class TcpChannel implements FastLaneChannel {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;
        private final AtomicBoolean outputShutdown = new AtomicBoolean();

        TcpChannel(Socket socket) throws IOException {
            this.socket = socket;
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
        }

        @Override
        public InputStream inputStream() {
            return input;
        }

        @Override
        public OutputStream outputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    output.write(value);
                }

                @Override public void write(byte[] bytes, int offset, int length)
                        throws IOException {
                    output.write(bytes, offset, length);
                }

                @Override public void flush() throws IOException {
                    output.flush();
                }

                @Override public void close() {
                    halfCloseOutput();
                }
            };
        }

        /** Idempotent output-direction shutdown; the input stays readable. */
        private void halfCloseOutput() {
            if (outputShutdown.compareAndSet(false, true)) {
                try {
                    socket.shutdownOutput();
                } catch (IOException ignored) {
                    // Already closed or already shut down by the peer path.
                }
            }
        }

        @Override
        public void close() throws IOException {
            halfCloseOutput();
            socket.close();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The failed dial already owns the terminal outcome.
        }
    }
}
