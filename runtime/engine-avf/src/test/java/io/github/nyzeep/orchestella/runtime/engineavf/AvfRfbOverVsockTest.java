package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        System.out.println("AVF_RFB_OVER_VSOCK_TEST_OK connect=1 frame=1 close=1 reconnect=1");
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

    /** Writes the minimal authenticated RFB exchange the host-side client expects. */
    private static void serveOneRfbSession(Socket socket) {
        try (socket) {
            OutputStream out = socket.getOutputStream();
            DataOutputStream wire = new DataOutputStream(out);
            wire.write("ORCHESTELLA/DISPLAY READY 1\nRFB 003.008\n"
                    .getBytes(StandardCharsets.US_ASCII));
            wire.write(new byte[] {1, 1});
            wire.writeInt(0);
            wire.writeShort(1);
            wire.writeShort(1);
            wire.write(new byte[] {32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255,
                    16, 8, 0, 0, 0, 0});
            wire.writeInt(0);
            wire.writeByte(0);
            wire.writeByte(0);
            wire.writeShort(1);
            wire.writeShort(0);
            wire.writeShort(0);
            wire.writeShort(1);
            wire.writeShort(1);
            wire.writeInt(0);
            wire.write(new byte[] {0, 0, (byte) 255, 0});
            wire.flush();
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
