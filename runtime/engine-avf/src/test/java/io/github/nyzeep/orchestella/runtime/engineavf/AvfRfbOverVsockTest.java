package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.KeyEvent;
import io.github.nyzeep.orchestella.runtime.contract.PointerEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ticket 06: the production AVF display path is RFB over the fake vsock
 * transport. This exercises connect, frame delivery, close and reconnect on a
 * fresh channel; it is a synthetic wire test, not a guest/compositor result.
 */
public final class AvfRfbOverVsockTest {
    private static final String TOKEN = "0123456789abcdef".repeat(4);
    private static final int GRAPHICS_PORT = 9103;

    public static void main(String[] arguments) throws Exception {
        rfbConnectsReceivesFramesAndReconnectsOverVsock();
        keyboardAndPointerTravelOverTheSameFakeVsockTransport();
        System.out.println("AVF_RFB_OVER_VSOCK_TEST_OK connect=1 frame=1 close=1 reconnect=1 input=1");
    }

    private static void rfbConnectsReceivesFramesAndReconnectsOverVsock() throws Exception {
        AtomicInteger served = new AtomicInteger();
        android.system.virtualmachine.FakeVsockNetwork network =
                new android.system.virtualmachine.FakeVsockNetwork();
        try {
            network.listen(GRAPHICS_PORT, socket -> {
                served.incrementAndGet();
                serveOneRfbSession(socket);
            });
            var factory = AvfVirtualMachineApiFixture.channelFactoryOver(network);
            RfbDisplayChannel display = new RfbDisplayChannel(
                    () -> {
                        AvfVirtualMachineApi.AvfVsockChannel channel = factory.connect(GRAPHICS_PORT);
                        return new RfbDisplayChannel.Streams(
                                channel.inputStream(), channel.outputStream(), channel);
                    },
                    TOKEN,
                    RfbDisplayChannel.InputCapabilities.NONE);

            CountDownLatch firstFrame = new CountDownLatch(1);
            display.registerFrameListener(frame -> firstFrame.countDown());
            assertTrue(display.connect(), "the RFB handshake completes over vsock");
            assertTrue(firstFrame.await(2, TimeUnit.SECONDS),
                    "a literal RFB frame arrives over the vsock stream");
            assertEquals(WaylandChannel.ConnectionState.CONNECTED, display.state(),
                    "the display is connected after a frame");
            display.close();

            CountDownLatch secondFrame = new CountDownLatch(1);
            RfbDisplayChannel reconnect = new RfbDisplayChannel(
                    () -> {
                        AvfVirtualMachineApi.AvfVsockChannel channel = factory.connect(GRAPHICS_PORT);
                        return new RfbDisplayChannel.Streams(
                                channel.inputStream(), channel.outputStream(), channel);
                    },
                    TOKEN,
                    RfbDisplayChannel.InputCapabilities.NONE);
            reconnect.registerFrameListener(frame -> secondFrame.countDown());
            assertTrue(reconnect.connect(), "a fresh vsock channel reconnects");
            assertTrue(secondFrame.await(2, TimeUnit.SECONDS),
                    "a literal RFB frame arrives after reconnect");
            reconnect.close();
            assertEquals(2, served.get(), "each connect opened exactly one vsock session");
        } finally {
            network.closeGuestSide();
        }
    }

    /**
     * Ticket 10: the AVF RFB path must carry real keyboard and pointer input,
     * not just pixels. The fake guest reads the RFB client messages and checks
     * the exact keysym and pointer coordinates; no Android input surface is
     * involved.
     */
    private static void keyboardAndPointerTravelOverTheSameFakeVsockTransport() throws Exception {
        AtomicInteger served = new AtomicInteger();
        android.system.virtualmachine.FakeVsockNetwork network =
                new android.system.virtualmachine.FakeVsockNetwork();
        CountDownLatch inputSeen = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<byte[]> keyPacket = new AtomicReference<>();
        AtomicReference<byte[]> pointerPacket = new AtomicReference<>();
        try {
            network.listen(GRAPHICS_PORT, socket -> {
                served.incrementAndGet();
                serveRfbAndReadInput(socket, inputSeen, release, keyPacket, pointerPacket);
            });
            var factory = AvfVirtualMachineApiFixture.channelFactoryOver(network);
            RfbDisplayChannel display = new RfbDisplayChannel(
                    () -> {
                        AvfVirtualMachineApi.AvfVsockChannel channel = factory.connect(GRAPHICS_PORT);
                        return new RfbDisplayChannel.Streams(
                                channel.inputStream(), channel.outputStream(), channel);
                    },
                    TOKEN,
                    new RfbDisplayChannel.InputCapabilities(true, false, true));

            CountDownLatch firstFrame = new CountDownLatch(1);
            display.registerFrameListener(frame -> firstFrame.countDown());
            assertTrue(display.connect(), "the RFB handshake completes over vsock");
            assertTrue(firstFrame.await(2, TimeUnit.SECONDS),
                    "a literal RFB frame arrives before input is sent");
            assertTrue(display.sendInputEvent(
                            KeyEvent.press(KeyEvent.KEY_A, KeyEvent.MOD_NONE, 1L)),
                    "the display accepts a keyboard event");
            assertTrue(display.sendInputEvent(
                            PointerEvent.motion(10, 20, 0, 0, 2L)),
                    "the display accepts a pointer event");
            assertTrue(inputSeen.await(2, TimeUnit.SECONDS),
                    "the fake guest received both RFB input messages");

            byte[] key = keyPacket.get();
            assertEquals(4, key[0] & 0xff, "RFB key message type");
            assertEquals(1, key[1] & 0xff, "key is pressed");
            assertEquals(0x61, ((key[4] & 0xff) << 24) | ((key[5] & 0xff) << 16)
                    | ((key[6] & 0xff) << 8) | (key[7] & 0xff), "KEY_A maps to the 'a' keysym");

            byte[] pointer = pointerPacket.get();
            assertEquals(5, pointer[0] & 0xff, "RFB pointer message type");
            assertEquals(0, pointer[1] & 0xff, "no buttons are held");
            assertEquals(10, ((pointer[2] & 0xff) << 8) | (pointer[3] & 0xff), "pointer x");
            assertEquals(20, ((pointer[4] & 0xff) << 8) | (pointer[5] & 0xff), "pointer y");

            awaitInputCount(display, 2L);
            assertEquals(2L, display.statistics().inputEvents(), "both input messages were written");
            assertEquals(0L, display.statistics().rejectedInputEvents(), "no input was rejected");

            display.close();
            release.countDown();
            assertEquals(1, served.get(), "exactly one input session was served");
        } finally {
            release.countDown();
            network.closeGuestSide();
        }
    }

    private static void serveRfbAndReadInput(Socket socket, CountDownLatch inputSeen,
            CountDownLatch release, AtomicReference<byte[]> keyPacket,
            AtomicReference<byte[]> pointerPacket) {
        try (socket) {
            writeRfbSession(socket, 100, 100);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            readAsciiLine(input);          // ORCHESTELLA/DISPLAY token preamble
            input.readFully(new byte[12]); // client RFB version
            input.readUnsignedByte();      // security type selection
            input.readUnsignedByte();      // ClientInit
            boolean keySeen = false;
            boolean pointerSeen = false;
            while (!keySeen || !pointerSeen) {
                int type = input.readUnsignedByte();
                if (type == 0) {
                    input.readFully(new byte[19]); // SetPixelFormat
                } else if (type == 2) {
                    input.readUnsignedByte(); // padding
                    int encodings = input.readUnsignedShort();
                    input.readFully(new byte[encodings * 4]);
                } else if (type == 3) {
                    input.readFully(new byte[9]);
                } else if (type == 4) {
                    byte[] rest = new byte[7];
                    input.readFully(rest);
                    keyPacket.set(packet(4, rest));
                    keySeen = true;
                } else if (type == 5) {
                    byte[] rest = new byte[5];
                    input.readFully(rest);
                    pointerPacket.set(packet(5, rest));
                    pointerSeen = true;
                } else {
                    return;
                }
            }
            inputSeen.countDown();
            release.await(2, TimeUnit.SECONDS);
        } catch (IOException ignored) {
            // fixture-only session end
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] packet(int type, byte[] rest) {
        byte[] packet = new byte[rest.length + 1];
        packet[0] = (byte) type;
        System.arraycopy(rest, 0, packet, 1, rest.length);
        return packet;
    }

    private static void readAsciiLine(DataInputStream input) throws IOException {
        for (int index = 0; index < 128; index++) {
            if (input.readUnsignedByte() == '\n') {
                return;
            }
        }
        throw new IOException("client preamble is too long");
    }

    private static void awaitInputCount(RfbDisplayChannel display, long expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (display.statistics().inputEvents() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
    }

    /** Writes the minimal authenticated RFB exchange the host-side client expects. */
    private static void serveOneRfbSession(Socket socket) {
        try (socket) {
            writeRfbSession(socket, 1, 1);
            // Keep the guest side open briefly so the client can read the frame
            // before EOF; the host closes the channel in the test.
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException ignored) {
            // fixture-only session end
        }
    }

    private static void writeRfbSession(Socket socket, int width, int height) throws IOException {
        OutputStream out = socket.getOutputStream();
        DataOutputStream wire = new DataOutputStream(out);
        wire.write("ORCHESTELLA/DISPLAY READY 1\nRFB 003.008\n"
                .getBytes(StandardCharsets.US_ASCII));
        wire.write(new byte[] {1, 1});
        wire.writeInt(0);
        wire.writeShort(width);
        wire.writeShort(height);
        wire.write(new byte[] {32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
                16, 8, 0, 0, 0, 0});
        wire.writeInt(0);
        wire.writeByte(0);
        wire.writeByte(0);
        wire.writeShort(1);
        wire.writeShort(0);
        wire.writeShort(0);
        wire.writeShort(width);
        wire.writeShort(height);
        wire.writeInt(0);
        wire.write(new byte[width * height * 4]);
        wire.flush();
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
