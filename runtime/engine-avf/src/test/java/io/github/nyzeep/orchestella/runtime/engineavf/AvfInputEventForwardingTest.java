package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.DisplayControl;
import io.github.nyzeep.orchestella.runtime.contract.DisplayListener;
import io.github.nyzeep.orchestella.runtime.contract.DisplayMode;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.KeyEvent;
import io.github.nyzeep.orchestella.runtime.contract.PointerEvent;
import io.github.nyzeep.orchestella.runtime.contract.TextCommitEvent;
import io.github.nyzeep.orchestella.runtime.contract.TouchEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests input event penetration and display resolution renegotiation forwarding over vsock:
 * validates PointerEvent, TouchEvent, KeyEvent, TextCommitEvent wire serialization down to guest,
 * DisplayControl resize/mode/fps commands, and AVF CapabilityMatrix graphics parity.
 */
public final class AvfInputEventForwardingTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        forwardsPointerEventOverVsock();
        forwardsTouchEventOverVsock();
        forwardsKeyEventOverVsock();
        forwardsTextCommitEventWithBase64OverVsock();
        displayControlResizeModeAndFpsForwarding();
        avfCapabilityMatrixReflectsRfbDisplay();
        System.out.println(
                "AVF_INPUT_EVENT_FORWARDING_TEST_OK pointer=1 touch=1 key=1 text_commit=1"
                        + " display_control=1 capability_matrix=1");
    }

    private static void forwardsPointerEventOverVsock() throws Exception {
        List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     OutputStream out = client.getOutputStream()) {
                    reader.readLine(); // DROIDLAYER/HELLO
                    out.write("ORCHESTELLA/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    String line = reader.readLine();
                    if (line != null) {
                        receivedLines.add(line);
                        latch.countDown();
                    }
                } catch (Exception ignored) {
                }
            }, "guest-pointer-test");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), TOKEN);
                channel.connect();

                PointerEvent pointer = new PointerEvent(
                        PointerEvent.Action.MOVE, 250.5, 300.0, 1.0, -2.0, 0, 1000L);
                boolean accepted = channel.sendInputEvent(pointer);
                assertTrue(accepted, "pointer event accepted");

                assertTrue(latch.await(3, TimeUnit.SECONDS), "pointer line received by guest");
                assertEquals("INPUT/POINTER MOVE 250.5 300.0 1.0 -2.0 0 1000",
                        receivedLines.get(0), "wire pointer line matches");

                channel.close();
            }
        }
    }

    private static void forwardsTouchEventOverVsock() throws Exception {
        List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

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

                    String line = reader.readLine();
                    if (line != null) {
                        receivedLines.add(line);
                        latch.countDown();
                    }
                } catch (Exception ignored) {
                }
            }, "guest-touch-test");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), TOKEN);
                channel.connect();

                TouchEvent touch = TouchEvent.down(1, 0.25, 0.75, 2000L);
                boolean accepted = channel.sendInputEvent(touch);
                assertTrue(accepted, "touch event accepted");

                assertTrue(latch.await(3, TimeUnit.SECONDS), "touch line received by guest");
                assertEquals("INPUT/TOUCH DOWN 1 0.25 0.75 2000",
                        receivedLines.get(0), "wire touch line matches");

                channel.close();
            }
        }
    }

    private static void forwardsKeyEventOverVsock() throws Exception {
        List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

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

                    String line = reader.readLine();
                    if (line != null) {
                        receivedLines.add(line);
                        latch.countDown();
                    }
                } catch (Exception ignored) {
                }
            }, "guest-key-test");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), TOKEN);
                channel.connect();

                KeyEvent key = KeyEvent.press(65, 0, 3000L);
                boolean accepted = channel.sendInputEvent(key);
                assertTrue(accepted, "key event accepted");

                assertTrue(latch.await(3, TimeUnit.SECONDS), "key line received by guest");
                assertEquals("INPUT/KEY PRESS 65 0 3000",
                        receivedLines.get(0), "wire key line matches");

                channel.close();
            }
        }
    }

    private static void forwardsTextCommitEventWithBase64OverVsock() throws Exception {
        List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

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

                    String line = reader.readLine();
                    if (line != null) {
                        receivedLines.add(line);
                        latch.countDown();
                    }
                } catch (Exception ignored) {
                }
            }, "guest-text-test");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), TOKEN);
                channel.connect();

                String chineseText = "中文测试 🚀";
                TextCommitEvent textEvent = new TextCommitEvent(chineseText, 4000L);
                boolean accepted = channel.sendInputEvent(textEvent);
                assertTrue(accepted, "text commit event accepted");

                assertTrue(latch.await(3, TimeUnit.SECONDS), "text commit line received by guest");
                String line = receivedLines.get(0);
                assertTrue(line.startsWith("INPUT/TEXT base64:"), "starts with base64 prefix");
                assertTrue(line.endsWith(" 4000"), "ends with timestamp");

                channel.close();
            }
        }
    }

    private static void displayControlResizeModeAndFpsForwarding() throws Exception {
        List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(3);

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

                    for (int i = 0; i < 3; i++) {
                        String line = reader.readLine();
                        if (line != null) {
                            receivedLines.add(line);
                            out.write("ORCHESTELLA/OK display\n".getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            latch.countDown();
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "guest-display-control-test");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), TOKEN);
                channel.connect();

                DisplayControl control = channel.displayControl();
                AtomicBoolean modeChangedNotified = new AtomicBoolean(false);
                AtomicInteger targetFpsNotified = new AtomicInteger(0);

                control.addDisplayListener(new DisplayListener() {
                    @Override
                    public void onModeChanged(DisplayMode newMode) {
                        modeChangedNotified.set(true);
                    }

                    @Override
                    public void onDisplayResized(int width, int height) {
                    }

                    @Override
                    public void onFpsUpdated(int currentFps) {
                        targetFpsNotified.set(currentFps);
                    }
                });

                // 1. Resize display
                boolean resizeOk = control.resizeDisplay(1280, 720);
                assertTrue(resizeOk, "resizeDisplay accepted");
                assertEquals(1280, control.currentMode().width(), "currentMode width updated");
                assertEquals(720, control.currentMode().height(), "currentMode height updated");

                // 2. Update scale
                boolean scaleOk = control.updateScale(1.25);
                assertTrue(scaleOk, "updateScale accepted");
                assertEquals(1.25, control.currentMode().scale(), "currentMode scale updated");

                // 3. Update target fps
                boolean fpsOk = control.updateTargetFps(144);
                assertTrue(fpsOk, "updateTargetFps accepted");
                assertEquals(144, control.currentMode().targetFps(), "currentMode fps updated");

                assertTrue(latch.await(3, TimeUnit.SECONDS), "all 3 control lines reached guest");
                assertEquals("ORCHESTELLA/DISPLAY RESIZE 1280 720", receivedLines.get(0), "resize line match");
                assertEquals("ORCHESTELLA/DISPLAY MODE 1280 720 1.25", receivedLines.get(1), "mode line match");
                assertEquals("ORCHESTELLA/DISPLAY FPS 144", receivedLines.get(2), "fps line match");

                assertTrue(modeChangedNotified.get(), "listener notified of mode change");
                assertEquals(144, targetFpsNotified.get(), "listener notified of fps change");

                // Defense: invalid dimensions rejected without sending
                assertTrue(!control.resizeDisplay(50, 50), "sub-100 dimension rejected");
                assertTrue(!control.resizeDisplay(10000, 10000), "super-7680 dimension rejected");
                assertTrue(!control.updateScale(0.05), "sub-0.1 scale rejected");
                assertTrue(!control.updateTargetFps(300), "super-240 fps rejected");

                channel.close();
            }
        }
    }

    private static void avfCapabilityMatrixReflectsRfbDisplay() {
        FullLinuxContract.CapabilityMatrix matrix = AvfVirtualMachineEngine.capabilityMatrix();
        assertTrue(matrix != null, "capability matrix must not be null");
        assertEquals(
                FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                matrix.executionStrategy(),
                "execution strategy is HARDWARE_VIRTUALIZATION");
        assertTrue(matrix.supports(FullLinuxContract.Capability.GRAPHICS_RFB),
                "the AVF display path is RFB");
        assertTrue(!matrix.supports(FullLinuxContract.Capability.GRAPHICS_WAYLAND),
                "RFB must not be reported as Wayland");
        assertEquals("RFB 显示（vsock CPU 拷贝）", matrix.graphicsBadge(),
                "transparent capability badge names the real transport");
        assertEquals("完整 Linux · 硬件加速", matrix.performanceBadge(),
                "performance badge matches specification");
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
