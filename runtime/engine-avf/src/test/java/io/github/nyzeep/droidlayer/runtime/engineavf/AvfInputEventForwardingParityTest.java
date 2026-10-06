package io.github.nyzeep.droidlayer.runtime.engineavf;

import io.github.nyzeep.droidlayer.runtime.contract.InputEvent;
import io.github.nyzeep.droidlayer.runtime.contract.InputEventSerialization;
import io.github.nyzeep.droidlayer.runtime.contract.PointerEvent;
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

/**
 * Validates relative motion and physical button mask forwarding parity on vsock port 9103.
 *
 * <p>Conforms to ADR-0018 and Ticket 48:
 * Ensures relative pointer events (deltaX, deltaY, button masks) pass seamlessly
 * across the Stage-2 boundary with exact byte-for-byte wire equivalence to the local contract.
 */
public final class AvfInputEventForwardingParityTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    public static void main(String[] arguments) throws Exception {
        relativeMotionSerializedEquivalentlyOverVsock();
        physicalButtonMasksArePreservedAndMapped();
        multiButtonAndCombinedMotionEquivalence();
        standardPointerActionsRemainIntact();
        System.out.println(
                "AVF_INPUT_EVENT_FORWARDING_PARITY_TEST_OK relative_motion=1 button_masks=1 multi_button=1 standard_motion=1");
    }

    private static void relativeMotionSerializedEquivalentlyOverVsock() throws Exception {
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
                    out.write("DROIDLAYER/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    String line = reader.readLine();
                    if (line != null) {
                        receivedLines.add(line);
                        latch.countDown();
                    }
                } catch (Exception ignored) {
                }
            }, "guest-relative-pointer-test");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), TOKEN);
                channel.connect();

                double deltaX = 12.75;
                double deltaY = -8.5;
                long timestampNs = 55_000_000L;
                int buttonMask = PointerEvent.BUTTON_PRIMARY;

                PointerEvent relativeEvent = PointerEvent.relativeMotion(deltaX, deltaY, buttonMask, timestampNs);
                String expectedWireLine = InputEventSerialization.serialize(relativeEvent).trim();

                boolean sent = channel.sendInputEvent(relativeEvent);
                assertTrue(sent, "relative pointer event successfully sent");

                assertTrue(latch.await(3, TimeUnit.SECONDS), "guest received relative pointer line");
                assertEquals(expectedWireLine, receivedLines.get(0), "wire line matches exact local serialization");

                InputEvent deserialized = InputEventSerialization.deserialize(receivedLines.get(0));
                assertTrue(deserialized instanceof PointerEvent, "deserialized event is PointerEvent");
                PointerEvent p = (PointerEvent) deserialized;
                assertTrue(p.isRelativeMotion(), "isRelativeMotion is true");
                assertEquals(PointerEvent.Action.RELATIVE_MOVE, p.action(), "action is RELATIVE_MOVE");
                assertEquals(deltaX, p.deltaX(), "deltaX matches");
                assertEquals(deltaY, p.deltaY(), "deltaY matches");
                assertEquals(buttonMask, p.buttonMask(), "buttonMask matches");
                assertTrue(p.hasButton(PointerEvent.BUTTON_PRIMARY), "hasButton PRIMARY is true");
                assertEquals(timestampNs, p.timestampNs(), "timestamp matches");

                channel.close();
            }
        }
    }

    private static void physicalButtonMasksArePreservedAndMapped() throws Exception {
        int[] masks = {
                PointerEvent.BUTTON_NONE,
                PointerEvent.BUTTON_PRIMARY,
                PointerEvent.BUTTON_SECONDARY,
                PointerEvent.BUTTON_TERTIARY,
                PointerEvent.BUTTON_PRIMARY | PointerEvent.BUTTON_SECONDARY,
                PointerEvent.BUTTON_PRIMARY | PointerEvent.BUTTON_TERTIARY,
                PointerEvent.BUTTON_SECONDARY | PointerEvent.BUTTON_TERTIARY,
                PointerEvent.BUTTON_PRIMARY | PointerEvent.BUTTON_SECONDARY | PointerEvent.BUTTON_TERTIARY
        };

        for (int mask : masks) {
            PointerEvent event = PointerEvent.relativeMotion(5.0, 10.0, mask, 1000L);
            String wire = InputEventSerialization.serialize(event);
            PointerEvent decoded = (PointerEvent) InputEventSerialization.deserialize(wire);

            assertEquals(mask, decoded.buttonMask(), "button mask preserved for mask " + mask);
            if ((mask & PointerEvent.BUTTON_PRIMARY) != 0) {
                assertTrue(decoded.hasButton(PointerEvent.BUTTON_PRIMARY), "has primary button");
                assertEquals(PointerEvent.BUTTON_LEFT, PointerEvent.toLinuxButton(PointerEvent.BUTTON_PRIMARY), "primary to linux left");
            }
            if ((mask & PointerEvent.BUTTON_SECONDARY) != 0) {
                assertTrue(decoded.hasButton(PointerEvent.BUTTON_SECONDARY), "has secondary button");
                assertEquals(PointerEvent.BUTTON_RIGHT, PointerEvent.toLinuxButton(PointerEvent.BUTTON_SECONDARY), "secondary to linux right");
            }
            if ((mask & PointerEvent.BUTTON_TERTIARY) != 0) {
                assertTrue(decoded.hasButton(PointerEvent.BUTTON_TERTIARY), "has tertiary button");
                assertEquals(PointerEvent.BUTTON_MIDDLE, PointerEvent.toLinuxButton(PointerEvent.BUTTON_TERTIARY), "tertiary to linux middle");
            }
        }
    }

    private static void multiButtonAndCombinedMotionEquivalence() throws Exception {
        List<String> receivedLines = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(3);

        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();

            Thread guestThread = new Thread(() -> {
                try (Socket client = server.accept();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                     OutputStream out = client.getOutputStream()) {
                    reader.readLine(); // DROIDLAYER/HELLO
                    out.write("DROIDLAYER/READY 1\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();

                    for (int i = 0; i < 3; i++) {
                        String line = reader.readLine();
                        if (line != null) {
                            receivedLines.add(line);
                            latch.countDown();
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "guest-multi-button-test");
            guestThread.setDaemon(true);
            guestThread.start();

            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                AvfWaylandChannel channel = new AvfWaylandChannel(
                        socket.getInputStream(), socket.getOutputStream(), TOKEN);
                channel.connect();

                // 1. Relative motion with secondary button held (right click drag)
                PointerEvent rightDrag = PointerEvent.relativeMotion(100.0, -50.0, PointerEvent.BUTTON_SECONDARY, 2000L);
                channel.sendInputEvent(rightDrag);

                // 2. Relative motion with primary + tertiary held (left + middle click drag)
                PointerEvent multiDrag = PointerEvent.relativeMotion(-20.0, 30.0,
                        PointerEvent.BUTTON_PRIMARY | PointerEvent.BUTTON_TERTIARY, 2001L);
                channel.sendInputEvent(multiDrag);

                // 3. Pure relative motion (no buttons)
                PointerEvent pureMotion = PointerEvent.relativeMotion(0.5, 0.5, 2002L);
                channel.sendInputEvent(pureMotion);

                assertTrue(latch.await(3, TimeUnit.SECONDS), "all 3 relative events received");
                assertEquals(InputEventSerialization.serialize(rightDrag).trim(), receivedLines.get(0), "right drag wire match");
                assertEquals(InputEventSerialization.serialize(multiDrag).trim(), receivedLines.get(1), "multi drag wire match");
                assertEquals(InputEventSerialization.serialize(pureMotion).trim(), receivedLines.get(2), "pure motion wire match");

                channel.close();
            }
        }
    }

    private static void standardPointerActionsRemainIntact() {
        PointerEvent move = PointerEvent.motion(100, 200, 1.0, 2.0, 3000L);
        PointerEvent down = PointerEvent.buttonDown(100, 200, PointerEvent.BUTTON_LEFT, 3001L);
        PointerEvent up = PointerEvent.buttonUp(100, 200, PointerEvent.BUTTON_LEFT, 3002L);
        PointerEvent scroll = PointerEvent.scroll(100, 200, 0.0, -15.0, 3003L);

        assertEquals(PointerEvent.Action.MOVE, ((PointerEvent) InputEventSerialization.deserialize(InputEventSerialization.serialize(move))).action(), "move action");
        assertEquals(PointerEvent.Action.BUTTON_DOWN, ((PointerEvent) InputEventSerialization.deserialize(InputEventSerialization.serialize(down))).action(), "button down action");
        assertEquals(PointerEvent.Action.BUTTON_UP, ((PointerEvent) InputEventSerialization.deserialize(InputEventSerialization.serialize(up))).action(), "button up action");
        assertEquals(PointerEvent.Action.SCROLL, ((PointerEvent) InputEventSerialization.deserialize(InputEventSerialization.serialize(scroll))).action(), "scroll action");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
