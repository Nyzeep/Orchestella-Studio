package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Contract tests for {@link AvfWaylandChannel}:
 * validates state machine transitions, Bridge v1 handshake authentication,
 * FrameListener frame ingestion/dispatch, dirty region skipping, and clean disconnection.
 */
public final class AvfWaylandChannelContractTest {
    private static final String VALID_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String INVALID_TOKEN =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    public static void main(String[] arguments) throws Exception {
        stateMachineTransitionsAndListeners();
        handshakeSendsBridgeV1HelloAndRequiresReady();
        unauthorizedTokenTransitionsToErrorAndCloses();
        frameListenerRegistrationAndDispatch();
        damageRegionZeroPayloadSkipsDispatch();
        channelCloseReleasesTransportAndNotifiesDisconnected();
        System.out.println(
                "AVF_WAYLAND_CHANNEL_CONTRACT_TEST_OK state_machine=1 handshake=1 unauthorized=1"
                        + " frame_dispatch=1 damage_skip=1 close=1");
    }

    private static void stateMachineTransitionsAndListeners() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            CountDownLatch readyLatch = new CountDownLatch(1);

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     OutputStream out = client.getOutputStream()) {
                    String hello = reader.readLine();
                    if (hello != null && hello.contains(VALID_TOKEN)) {
                        out.write("ORCHESTELLA/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                        readyLatch.await(5, TimeUnit.SECONDS);
                    }
                } catch (Exception ignored) {
                }
            }, "guest-sim-state-machine");
            guestThread.setDaemon(true);
            guestThread.start();

            Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
            AvfWaylandChannel channel = new AvfWaylandChannel(
                    socket.getInputStream(), socket.getOutputStream(), VALID_TOKEN, () -> {
                try {
                    socket.close();
                } catch (IOException ignored) {}
            });

                AtomicReference<WaylandChannel.ConnectionState> observedState = new AtomicReference<>();
                channel.addStateListener((newState, msg) -> observedState.set(newState));

                assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state(), "initial state");

                channel.connect();

                assertEquals(WaylandChannel.ConnectionState.CONNECTED, channel.state(), "connected state");
                assertTrue(channel.isConnected(), "isConnected returns true");
                assertEquals(WaylandChannel.ConnectionState.CONNECTED, observedState.get(), "state listener notified");

                readyLatch.countDown();
                channel.close();
                assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state(), "closed state");
        }
    }

    private static void handshakeSendsBridgeV1HelloAndRequiresReady() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            AtomicReference<String> receivedHello = new AtomicReference<>();

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     OutputStream out = client.getOutputStream()) {
                    receivedHello.set(reader.readLine());
                    out.write("ORCHESTELLA/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    Thread.sleep(100);
                } catch (Exception ignored) {
                }
            }, "guest-sim-handshake");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), VALID_TOKEN);
                channel.connect();

                assertTrue(channel.isConnected(), "channel connected after READY");
                assertEquals(
                        "ORCHESTELLA/HELLO 1 1 " + VALID_TOKEN,
                        receivedHello.get(),
                        "host initiates Bridge v1 handshake line");
                channel.close();
            }
        }
    }

    private static void unauthorizedTokenTransitionsToErrorAndCloses() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     OutputStream out = client.getOutputStream()) {
                    reader.readLine();
                    out.write("ORCHESTELLA/ERROR unauthorized\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception ignored) {
                }
            }, "guest-sim-unauth");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), INVALID_TOKEN);
                AtomicReference<WaylandChannel.ConnectionState> lastState = new AtomicReference<>();
                channel.addStateListener((state, msg) -> lastState.set(state));

                try {
                    channel.connect();
                } catch (Exception expected) {
                    // Handshake failure throws exception
                }

                assertEquals(WaylandChannel.ConnectionState.ERROR, channel.state(), "state is ERROR on auth failure");
                assertEquals(WaylandChannel.ConnectionState.ERROR, lastState.get(), "listener received ERROR");
                assertTrue(!channel.isConnected(), "not connected");
                channel.close();
            }
        }
    }

    private static void frameListenerRegistrationAndDispatch() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            CountDownLatch frameDeliveredLatch = new CountDownLatch(1);

            int width = 1920;
            int height = 1080;
            int stride = width * 4;
            int format = WaylandChannel.FrameData.FORMAT_RGBA_8888;
            byte[] pixelData = new byte[stride * height];
            pixelData[0] = (byte) 0xFF;
            pixelData[1] = (byte) 0xAA;

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     OutputStream out = client.getOutputStream()) {
                    reader.readLine();
                    out.write("ORCHESTELLA/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    // Send one valid frame packet
                    byte[] header = AvfGraphicsVsockProtocol.encodeFrameHeader(
                            1, width, height, stride, format, 10_000_000L, pixelData.length);
                    out.write(header);
                    out.write(pixelData);
                    out.flush();

                    frameDeliveredLatch.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }, "guest-sim-frame-stream");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), VALID_TOKEN);

                AtomicReference<WaylandChannel.FrameData> receivedFrame = new AtomicReference<>();
                WaylandChannel.FrameListener listener = frame -> {
                    receivedFrame.set(frame);
                    frameDeliveredLatch.countDown();
                };

                channel.registerFrameListener(listener);
                channel.connect();

                assertTrue(frameDeliveredLatch.await(3, TimeUnit.SECONDS), "frame delivered within SLA");
                WaylandChannel.FrameData frame = receivedFrame.get();
                assertTrue(frame != null, "received frame must not be null");
                assertEquals(width, frame.width(), "frame width");
                assertEquals(height, frame.height(), "frame height");
                assertEquals(stride, frame.stride(), "frame stride");
                assertEquals(format, frame.format(), "frame format");
                assertEquals(1L, frame.frameId(), "frame ID");
                assertEquals(10_000_000L, frame.timestampNs(), "timestamp");
                assertEquals((byte) 0xFF, frame.data()[0], "pixel byte 0");
                assertEquals((byte) 0xAA, frame.data()[1], "pixel byte 1");

                channel.unregisterFrameListener(listener);
                channel.close();
            }
        }
    }

    private static void damageRegionZeroPayloadSkipsDispatch() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            CountDownLatch secondFrameLatch = new CountDownLatch(1);

            int width = 800;
            int height = 600;
            int stride = width * 4;
            int format = WaylandChannel.FrameData.FORMAT_RGBA_8888;
            byte[] normalPixels = new byte[stride * height];

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     OutputStream out = client.getOutputStream()) {
                    reader.readLine();
                    out.write("ORCHESTELLA/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    // 1. Zero-damage frame: payloadLength == 0 (static desktop, dirty regions empty)
                    byte[] zeroDamageHeader = AvfGraphicsVsockProtocol.encodeFrameHeader(
                            1, width, height, stride, format, 1_000_000L, 0);
                    out.write(zeroDamageHeader);
                    out.flush();

                    Thread.sleep(50);

                    // 2. Normal frame
                    byte[] normalHeader = AvfGraphicsVsockProtocol.encodeFrameHeader(
                            2, width, height, stride, format, 2_000_000L, normalPixels.length);
                    out.write(normalHeader);
                    out.write(normalPixels);
                    out.flush();

                    secondFrameLatch.await(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }, "guest-sim-damage-skip");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), VALID_TOKEN);

                AtomicInteger dispatchCount = new AtomicInteger(0);
                channel.registerFrameListener(frame -> {
                    dispatchCount.incrementAndGet();
                    if (frame.frameId() == 2L) {
                        secondFrameLatch.countDown();
                    }
                });

                channel.connect();

                assertTrue(secondFrameLatch.await(3, TimeUnit.SECONDS), "second frame delivered");
                // The zero-damage frame must have been skipped: dispatchCount should be exactly 1!
                assertEquals(1, dispatchCount.get(), "zero damage frame was skipped; only frame 2 dispatched");
                assertEquals(1L, channel.damageSkippedFramesCount(), "damage skipped count is 1");

                channel.close();
            }
        }
    }

    private static void channelCloseReleasesTransportAndNotifiesDisconnected() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     OutputStream out = client.getOutputStream()) {
                    reader.readLine();
                    out.write("ORCHESTELLA/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    while (reader.read() != -1) {
                        // wait for close
                    }
                } catch (Exception ignored) {
                }
            }, "guest-sim-close");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), VALID_TOKEN);

                AtomicReference<WaylandChannel.ConnectionState> stateRef = new AtomicReference<>();
                channel.addStateListener((state, msg) -> stateRef.set(state));

                channel.connect();
                assertEquals(WaylandChannel.ConnectionState.CONNECTED, channel.state(), "connected");

                channel.close();
                assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state(), "disconnected after close");
                assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, stateRef.get(), "notified disconnected");
                assertTrue(!channel.isConnected(), "isConnected false");

                // Idempotent close
                channel.close();
                assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state(), "remains disconnected");
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
