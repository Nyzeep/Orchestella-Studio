package io.github.nyzeep.orchestella.runtime.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TDD Contract tests for {@link BridgeProxyGateway}:
 * 1. HTTP CONNECT tunnel handshake and bidirectional payload transmission.
 * 2. Multi-channel Virtio-Serial multiplexing and isolation.
 * 3. Plain HTTP forward proxying.
 * 4. Error resilience when target endpoint is unreachable.
 * 5. High-throughput data stream verification.
 */
public final class BridgeProxyGatewayContractTest {

    public static void main(String[] args) throws Exception {
        testHttpConnectTunnelHandshakeAndDataTransfer();
        testMultipleConcurrentChannels();
        testPlainHttpGetForwarding();
        testConnectionFailureReturns502();
        testHighThroughputStreamTransmission();
        System.out.println("BRIDGE_PROXY_GATEWAY_CONTRACT_TEST_OK");
    }

    private static void testHttpConnectTunnelHandshakeAndDataTransfer() throws Exception {
        try (EchoServer echoServer = new EchoServer()) {
            int port = echoServer.port();
            TestBridgePipe pipe = new TestBridgePipe();
            BridgeProxyGateway gateway = new BridgeProxyGateway(pipe.gatewayIn, pipe.gatewayOut);
            gateway.startAsync();

            try {
                int channelId = 101;
                // 1. Send CONNECT request from guest
                String connectReq = "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n";
                pipe.sendGuestFrame(channelId, BridgeProxyGateway.CMD_DATA, connectReq.getBytes(StandardCharsets.US_ASCII));

                // 2. Receive 200 Connection Established from host gateway
                BridgeFrame respFrame = pipe.readGuestFrame(5000);
                assertEquals(channelId, respFrame.channelId, "channelId matches");
                assertEquals(BridgeProxyGateway.CMD_DATA, respFrame.command, "command is DATA");
                String respStr = new String(respFrame.payload, StandardCharsets.US_ASCII);
                assertTrue(respStr.startsWith("HTTP/1.1 200 Connection Established"), "handshake 200 response: " + respStr);

                // 3. Send payload over established tunnel
                byte[] testPayload = "Hello Virtio-Serial Bridge Proxy!".getBytes(StandardCharsets.UTF_8);
                pipe.sendGuestFrame(channelId, BridgeProxyGateway.CMD_DATA, testPayload);

                // 4. EchoServer should echo the payload back
                BridgeFrame echoFrame = pipe.readGuestFrame(5000);
                assertEquals(channelId, echoFrame.channelId, "channelId matches");
                assertEquals(BridgeProxyGateway.CMD_DATA, echoFrame.command, "command is DATA");
                assertEquals(new String(testPayload, StandardCharsets.UTF_8),
                        new String(echoFrame.payload, StandardCharsets.UTF_8), "payload echoed back");

                // 5. Send CLOSE
                pipe.sendGuestFrame(channelId, BridgeProxyGateway.CMD_CLOSE, new byte[0]);
                Thread.sleep(100);
            } finally {
                gateway.close();
            }
        }
        System.out.println("TEST_HTTP_CONNECT_TUNNEL_OK");
    }

    private static void testMultipleConcurrentChannels() throws Exception {
        try (EchoServer server1 = new EchoServer(); EchoServer server2 = new EchoServer()) {
            TestBridgePipe pipe = new TestBridgePipe();
            BridgeProxyGateway gateway = new BridgeProxyGateway(pipe.gatewayIn, pipe.gatewayOut);
            gateway.startAsync();

            try {
                int ch1 = 1;
                int ch2 = 2;

                // Handshake Channel 1
                pipe.sendGuestFrame(ch1, BridgeProxyGateway.CMD_DATA,
                        ("CONNECT 127.0.0.1:" + server1.port() + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                BridgeFrame resp1 = pipe.readGuestFrame(5000);
                assertTrue(new String(resp1.payload, StandardCharsets.US_ASCII).contains("200 Connection Established"), "ch1 established");

                // Handshake Channel 2
                pipe.sendGuestFrame(ch2, BridgeProxyGateway.CMD_DATA,
                        ("CONNECT 127.0.0.1:" + server2.port() + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                BridgeFrame resp2 = pipe.readGuestFrame(5000);
                assertTrue(new String(resp2.payload, StandardCharsets.US_ASCII).contains("200 Connection Established"), "ch2 established");

                // Send data on Channel 2 first, then Channel 1
                pipe.sendGuestFrame(ch2, BridgeProxyGateway.CMD_DATA, "DATA-FOR-CH2".getBytes(StandardCharsets.UTF_8));
                pipe.sendGuestFrame(ch1, BridgeProxyGateway.CMD_DATA, "DATA-FOR-CH1".getBytes(StandardCharsets.UTF_8));

                BridgeFrame r1 = pipe.readGuestFrame(5000);
                BridgeFrame r2 = pipe.readGuestFrame(5000);

                // Both channels should receive their respective echoed data
                if (r1.channelId == ch2) {
                    assertEquals("DATA-FOR-CH2", new String(r1.payload, StandardCharsets.UTF_8), "ch2 data");
                    assertEquals(ch1, r2.channelId, "ch1 frame");
                    assertEquals("DATA-FOR-CH1", new String(r2.payload, StandardCharsets.UTF_8), "ch1 data");
                } else {
                    assertEquals(ch1, r1.channelId, "ch1 frame");
                    assertEquals("DATA-FOR-CH1", new String(r1.payload, StandardCharsets.UTF_8), "ch1 data");
                    assertEquals(ch2, r2.channelId, "ch2 frame");
                    assertEquals("DATA-FOR-CH2", new String(r2.payload, StandardCharsets.UTF_8), "ch2 data");
                }
            } finally {
                gateway.close();
            }
        }
        System.out.println("TEST_MULTIPLE_CHANNELS_OK");
    }

    private static void testPlainHttpGetForwarding() throws Exception {
        try (HttpMockServer httpServer = new HttpMockServer("Debian Package Index OK")) {
            TestBridgePipe pipe = new TestBridgePipe();
            BridgeProxyGateway gateway = new BridgeProxyGateway(pipe.gatewayIn, pipe.gatewayOut);
            gateway.startAsync();

            try {
                int ch = 42;
                String httpGet = "GET http://127.0.0.1:" + httpServer.port() + "/debian/InRelease HTTP/1.1\r\n"
                        + "Host: 127.0.0.1:" + httpServer.port() + "\r\n"
                        + "User-Agent: Debian APT-HTTP/1.3\r\n\r\n";
                pipe.sendGuestFrame(ch, BridgeProxyGateway.CMD_DATA, httpGet.getBytes(StandardCharsets.US_ASCII));

                StringBuilder totalResp = new StringBuilder();
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline && !totalResp.toString().contains("Debian Package Index OK")) {
                    BridgeFrame resp = pipe.readGuestFrame(2000);
                    assertEquals(ch, resp.channelId, "channel id matches");
                    if (resp.payload.length > 0) {
                        totalResp.append(new String(resp.payload, StandardCharsets.UTF_8));
                    }
                    if (resp.command == BridgeProxyGateway.CMD_CLOSE) {
                        break;
                    }
                }
                assertTrue(totalResp.toString().contains("Debian Package Index OK"),
                        "http response body verified: " + totalResp);
            } finally {
                gateway.close();
            }
        }
        System.out.println("TEST_PLAIN_HTTP_GET_FORWARDING_OK");
    }

    private static void testConnectionFailureReturns502() throws Exception {
        int unusedPort = findUnusedPort();
        TestBridgePipe pipe = new TestBridgePipe();
        BridgeProxyGateway gateway = new BridgeProxyGateway(pipe.gatewayIn, pipe.gatewayOut);
        gateway.startAsync();

        try {
            int ch = 99;
            String badConnect = "CONNECT 127.0.0.1:" + unusedPort + " HTTP/1.1\r\nHost: 127.0.0.1:" + unusedPort + "\r\n\r\n";
            pipe.sendGuestFrame(ch, BridgeProxyGateway.CMD_DATA, badConnect.getBytes(StandardCharsets.US_ASCII));

            BridgeFrame resp = pipe.readGuestFrame(5000);
            assertEquals(ch, resp.channelId, "channel id matches");
            String respStr = new String(resp.payload, StandardCharsets.US_ASCII);
            assertTrue(respStr.contains("502 Bad Gateway"), "expect 502 response on unreachable target: " + respStr);
        } finally {
            gateway.close();
        }
        System.out.println("TEST_CONNECTION_FAILURE_502_OK");
    }

    private static void testHighThroughputStreamTransmission() throws Exception {
        try (EchoServer echoServer = new EchoServer()) {
            TestBridgePipe pipe = new TestBridgePipe();
            BridgeProxyGateway gateway = new BridgeProxyGateway(pipe.gatewayIn, pipe.gatewayOut);
            gateway.startAsync();

            try {
                int ch = 55;
                pipe.sendGuestFrame(ch, BridgeProxyGateway.CMD_DATA,
                        ("CONNECT 127.0.0.1:" + echoServer.port() + " HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                BridgeFrame resp = pipe.readGuestFrame(5000);
                assertTrue(new String(resp.payload, StandardCharsets.US_ASCII).contains("200 Connection Established"), "tunnel open");

                // Transmit 256 KB of chunked data
                byte[] chunk = new byte[32 * 1024];
                Arrays.fill(chunk, (byte) 0x5A);
                int totalChunks = 8; // 256 KB total

                for (int i = 0; i < totalChunks; i++) {
                    pipe.sendGuestFrame(ch, BridgeProxyGateway.CMD_DATA, chunk);
                }

                int receivedBytes = 0;
                while (receivedBytes < chunk.length * totalChunks) {
                    BridgeFrame inFrame = pipe.readGuestFrame(5000);
                    assertEquals(ch, inFrame.channelId, "channel id");
                    assertEquals(BridgeProxyGateway.CMD_DATA, inFrame.command, "data frame");
                    receivedBytes += inFrame.payload.length;
                }
                assertEquals(chunk.length * totalChunks, receivedBytes, "all 256KB received");
            } finally {
                gateway.close();
            }
        }
        System.out.println("TEST_HIGH_THROUGHPUT_STREAM_OK");
    }

    // --- Test Harness Helpers ---

    private static final class BridgeFrame {
        final int channelId;
        final byte command;
        final byte[] payload;

        BridgeFrame(int channelId, byte command, byte[] payload) {
            this.channelId = channelId;
            this.command = command;
            this.payload = payload;
        }
    }

    private static final class TestBridgePipe {
        final PipedStreamPair guestToGateway = new PipedStreamPair();
        final PipedStreamPair gatewayToGuest = new PipedStreamPair();

        final InputStream gatewayIn = guestToGateway.in;
        final OutputStream gatewayOut = gatewayToGuest.out;

        final DataOutputStream guestWriter = new DataOutputStream(guestToGateway.out);
        final DataInputStream guestReader = new DataInputStream(gatewayToGuest.in);

        synchronized void sendGuestFrame(int channelId, byte command, byte[] payload) throws IOException {
            guestWriter.writeInt(channelId);
            guestWriter.writeByte(command);
            guestWriter.writeInt(payload.length);
            if (payload.length > 0) {
                guestWriter.write(payload);
            }
            guestWriter.flush();
        }

        BridgeFrame readGuestFrame(long timeoutMs) throws Exception {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (guestReader.available() < 9) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("timeout waiting for frame header");
                }
                Thread.sleep(10);
            }
            int channelId = guestReader.readInt();
            byte command = guestReader.readByte();
            int length = guestReader.readInt();
            byte[] payload = new byte[length];
            int read = 0;
            while (read < length) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IOException("timeout waiting for frame payload");
                }
                int count = guestReader.read(payload, read, length - read);
                if (count < 0) throw new IOException("EOF before payload complete");
                read += count;
            }
            return new BridgeFrame(channelId, command, payload);
        }
    }

    private static final class PipedStreamPair {
        final java.io.PipedInputStream in = new java.io.PipedInputStream(64 * 1024);
        final java.io.PipedOutputStream out = new java.io.PipedOutputStream();

        PipedStreamPair() {
            try {
                in.connect(out);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class EchoServer implements java.io.Closeable {
        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final AtomicBoolean running = new AtomicBoolean(true);

        EchoServer() throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        Socket s = serverSocket.accept();
                        Thread handler = new Thread(() -> {
                            try (Socket sock = s;
                                 InputStream in = sock.getInputStream();
                                 OutputStream out = sock.getOutputStream()) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    out.write(buf, 0, n);
                                    out.flush();
                                }
                            } catch (IOException ignored) {}
                        }, "echo-client");
                        handler.setDaemon(true);
                        handler.start();
                    } catch (IOException ignored) {}
                }
            }, "echo-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            serverSocket.close();
        }
    }

    private static final class HttpMockServer implements java.io.Closeable {
        private final ServerSocket serverSocket;
        private final Thread acceptThread;
        private final AtomicBoolean running = new AtomicBoolean(true);

        HttpMockServer(String responseBody) throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            acceptThread = new Thread(() -> {
                while (running.get()) {
                    try {
                        Socket s = serverSocket.accept();
                        Thread handler = new Thread(() -> {
                            try (Socket sock = s;
                                 InputStream in = sock.getInputStream();
                                 OutputStream out = sock.getOutputStream()) {
                                byte[] buf = new byte[4096];
                                int n = in.read(buf);
                                if (n > 0) {
                                    byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                                    String resp = "HTTP/1.1 200 OK\r\n"
                                            + "Content-Type: text/plain\r\n"
                                            + "Content-Length: " + body.length + "\r\n"
                                            + "Connection: close\r\n\r\n";
                                    out.write(resp.getBytes(StandardCharsets.US_ASCII));
                                    out.write(body);
                                    out.flush();
                                }
                            } catch (IOException ignored) {}
                        }, "http-mock-client");
                        handler.setDaemon(true);
                        handler.start();
                    } catch (IOException ignored) {}
                }
            }, "http-mock-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            serverSocket.close();
        }
    }

    private static int findUnusedPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " - expected: <" + expected + ">, but was: <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("assertion failed: " + message);
        }
    }
}
