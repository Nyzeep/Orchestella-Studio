package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
                try {
                    assertEquals(-1, client.getInputStream().read());
                } catch (IOException expected) {
                    // Connection reset is also a valid closed-client outcome.
                }
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

    private static final class EchoChannel implements FastLaneChannel {
        private final PipedOutputStream toGuest = new PipedOutputStream();
        private final PipedInputStream guestInput;
        private final PipedOutputStream fromGuest = new PipedOutputStream();
        private final PipedInputStream guestOutput;

        private EchoChannel() throws IOException {
            guestInput = new PipedInputStream(toGuest, 4096);
            guestOutput = new PipedInputStream(fromGuest, 4096);
            Thread echo = new Thread(() -> {
                try {
                    guestInput.transferTo(fromGuest);
                } catch (IOException ignored) {
                    // Closing either side ends the echo.
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
            for (AutoCloseable closeable : new AutoCloseable[] {toGuest, guestInput, fromGuest, guestOutput}) {
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
