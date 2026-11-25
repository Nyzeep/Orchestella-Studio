package io.github.nyzeep.orchestella;

import static org.junit.Assert.*;
import io.github.nyzeep.orchestella.runtime.contract.TextCommitEvent;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;
import io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class RfbDisplaySessionTest {
    @Test public void unopenedChannelNeverProducesTemplateFramesOrAcceptsInput() throws Exception {
        try (RfbDisplaySession channel = new RfbDisplaySession(() -> { throw new IOException("no display endpoint"); },
                "a".repeat(64), () -> null)) {
            CountDownLatch frame = new CountDownLatch(1);
            channel.registerFrameListener(ignored -> frame.countDown());
            assertEquals(DisplayChannel.ConnectionState.DISCONNECTED, channel.state());
            assertFalse(channel.isConnected());
            assertFalse(frame.await(50, TimeUnit.MILLISECONDS));
            assertFalse(channel.sendInputEvent(new TextCommitEvent("guest text", 1)));
            assertFalse(channel.setMode(1280, 720, 1));
            assertFalse(channel.updateTargetFps(60));
        }
    }

    @SuppressWarnings("try") // Explicit callback close is the retirement behavior under test.
    @Test public void closingFromARealFrameCallbackStopsRetiredFrameFanout() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream wire = new java.io.DataOutputStream(bytes);
        wire.write("ORCHESTELLA/DISPLAY READY 1\nRFB 003.008\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wire.write(new byte[] {1, 1});
        wire.writeInt(0);
        wire.writeShort(1); wire.writeShort(1);
        wire.write(new byte[] {32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
        wire.writeInt(0);
        wire.writeByte(0); wire.writeByte(0); wire.writeShort(1);
        wire.writeShort(0); wire.writeShort(0); wire.writeShort(1); wire.writeShort(1); wire.writeInt(0);
        wire.write(new byte[] {0, 0, (byte) 255, 0});
        try (RfbDisplaySession channel = new RfbDisplaySession(() -> new RfbDisplayChannel.Streams(
                new java.io.ByteArrayInputStream(bytes.toByteArray()), new java.io.ByteArrayOutputStream()),
                "a".repeat(64), () -> null)) {
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch stale = new CountDownLatch(1);
            channel.registerFrameListener(frame -> { channel.close(); first.countDown(); });
            channel.registerFrameListener(frame -> stale.countDown());
            assertTrue(channel.connect(RfbDisplayChannel.InputCapabilities.NONE));
            assertTrue("literal authenticated RFB frame must arrive", first.await(2, TimeUnit.SECONDS));
            assertFalse("retired stream must stop notifying the remaining listeners", stale.await(150, TimeUnit.MILLISECONDS));
            assertEquals(DisplayChannel.ConnectionState.DISCONNECTED, channel.state());
            assertFalse(channel.connect(RfbDisplayChannel.InputCapabilities.NONE));
        }
    }

    @Test public void transportFailureCannotAdvertiseAConnectedDesktop() throws Exception {
        try (RfbDisplaySession channel = new RfbDisplaySession(() -> { throw new IOException("unavailable"); },
                "a".repeat(64), () -> null)) {
            CountDownLatch error = new CountDownLatch(1);
            channel.addStateListener((state, message) -> { if (state == DisplayChannel.ConnectionState.ERROR) error.countDown(); });
            assertTrue(channel.connect(RfbDisplayChannel.InputCapabilities.NONE));
            assertTrue(error.await(2, TimeUnit.SECONDS));
            assertFalse(channel.isConnected());
        }
    }

    @Test public void defaultConnectKeepsExperimentalZrleDisabled() throws Exception {
        java.io.ByteArrayOutputStream serverBytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream wire = new java.io.DataOutputStream(serverBytes);
        wire.write("ORCHESTELLA/DISPLAY READY 1\nRFB 003.008\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wire.write(new byte[] {1, 1});
        wire.writeInt(0);
        wire.writeShort(1); wire.writeShort(1);
        wire.write(new byte[] {32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
        wire.writeInt(0);
        wire.writeByte(0); wire.writeByte(0); wire.writeShort(1);
        wire.writeShort(0); wire.writeShort(0); wire.writeShort(1); wire.writeShort(1); wire.writeInt(0);
        wire.write(new byte[] {0, 0, (byte) 255, 0});
        java.io.ByteArrayOutputStream clientOut = new java.io.ByteArrayOutputStream();
        try (RfbDisplaySession channel = new RfbDisplaySession(() -> new RfbDisplayChannel.Streams(
                new java.io.ByteArrayInputStream(serverBytes.toByteArray()), clientOut),
                "a".repeat(64), () -> null)) {
            CountDownLatch frameLatch = new CountDownLatch(1);
            channel.registerFrameListener(frame -> frameLatch.countDown());
            assertTrue(channel.connect(RfbDisplayChannel.InputCapabilities.NONE));
            assertTrue("Raw RFB frame must arrive", frameLatch.await(2, TimeUnit.SECONDS));
            assertFalse(channel.isExperimentalZrleEnabled());
            assertEquals(1L, channel.statistics().frames());
            assertTrue(channel.totalDecodeNanos() >= 0L);

            int[] encodings = readSetEncodings(clientOut.toByteArray(), 64);
            assertArrayEquals(new int[] {0, -223}, encodings);
        }
    }

    @Test public void explicitExperimentalZrleOptionsNegotiatesAndDecodesZrleFrame() throws Exception {
        java.io.ByteArrayOutputStream serverBytes = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream wire = new java.io.DataOutputStream(serverBytes);
        wire.write("ORCHESTELLA/DISPLAY READY 1\nRFB 003.008\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        wire.write(new byte[] {1, 1});
        wire.writeInt(0);
        wire.writeShort(1); wire.writeShort(1);
        wire.write(new byte[] {32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
        wire.writeInt(0);
        wire.writeByte(0); wire.writeByte(0); wire.writeShort(1);
        wire.writeShort(0); wire.writeShort(0); wire.writeShort(1); wire.writeShort(1); wire.writeInt(16);

        byte[] solidRedTile = new byte[] {1, 0, 0, (byte) 255};
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(solidRedTile);
        byte[] compressed = new byte[64];
        int compressedLen = deflater.deflate(compressed, 0, compressed.length, java.util.zip.Deflater.SYNC_FLUSH);
        deflater.end();
        wire.writeInt(compressedLen);
        wire.write(compressed, 0, compressedLen);

        java.io.ByteArrayOutputStream clientOut = new java.io.ByteArrayOutputStream();
        java.util.concurrent.atomic.AtomicReference<DisplayChannel.FrameData> received =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (RfbDisplaySession channel = new RfbDisplaySession(() -> new RfbDisplayChannel.Streams(
                new java.io.ByteArrayInputStream(serverBytes.toByteArray()), clientOut),
                "a".repeat(64), () -> null)) {
            CountDownLatch frameLatch = new CountDownLatch(1);
            channel.registerFrameListener(frame -> {
                received.set(frame);
                frameLatch.countDown();
            });
            RfbDisplayChannel.Options zrleOptions = new RfbDisplayChannel.Options(64, 15_000, 30_000, true);
            assertTrue(channel.connect(RfbDisplayChannel.InputCapabilities.NONE, false, zrleOptions));
            assertTrue("ZRLE RFB frame must arrive", frameLatch.await(2, TimeUnit.SECONDS));
            assertTrue(channel.isExperimentalZrleEnabled());
            assertEquals(1L, channel.statistics().frames());
            assertTrue(channel.totalDecodeNanos() >= 0L);
            assertNotNull(received.get());
            assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255}, received.get().data());

            int[] encodings = readSetEncodings(clientOut.toByteArray(), 64);
            assertArrayEquals(new int[] {16, 0, -223}, encodings);
        }
    }

    @Test
    public void forcedReconnectSettlesBetweenClosingPreviousAndOpeningReplacement() {
        java.util.List<String> events = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong closedAtNanos = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong secondOpenedAtNanos = new java.util.concurrent.atomic.AtomicLong();

        RfbDisplayChannel.StreamConnector connector = () -> {
            int attempt = opens.incrementAndGet();
            if (attempt == 1) {
                events.add("open-1");
                java.io.PipedOutputStream sink = new java.io.PipedOutputStream();
                java.io.PipedInputStream blockedIn = new java.io.PipedInputStream(sink) {
                    @Override public void close() throws IOException {
                        closedAtNanos.compareAndSet(0L, System.nanoTime());
                        events.add("close-1");
                        super.close();
                    }
                };
                return new RfbDisplayChannel.Streams(blockedIn, new java.io.ByteArrayOutputStream());
            }
            secondOpenedAtNanos.compareAndSet(0L, System.nanoTime());
            events.add("open-2");
            throw new IOException("stop-after-settle");
        };

        try (RfbDisplaySession channel = new RfbDisplaySession(connector, "a".repeat(64), () -> null, 45L)) {
            assertTrue(channel.connect(RfbDisplayChannel.InputCapabilities.NONE));
            long deadline = System.currentTimeMillis() + 1000L;
            while (opens.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.yield();
            }
            assertTrue(channel.connect(RfbDisplayChannel.InputCapabilities.NONE, true));
            deadline = System.currentTimeMillis() + 1000L;
            while (opens.get() < 2 && System.currentTimeMillis() < deadline) {
                Thread.yield();
            }
            assertEquals(java.util.List.of("open-1", "close-1", "open-2"), events);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(secondOpenedAtNanos.get() - closedAtNanos.get());
            assertTrue("expected settle >= 35ms after close, was " + elapsedMs + "ms", elapsedMs >= 35L);
        }
    }

    private static int[] readSetEncodings(byte[] clientBytes, int tokenLength) throws IOException {
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(clientBytes));
        int prefixLength = ("ORCHESTELLA/DISPLAY 1 " + "\n").length() + tokenLength
                + "RFB 003.008\n".length() + 1 + 1 + 20;
        in.readFully(new byte[prefixLength]);
        assertEquals(2, in.readUnsignedByte());
        assertEquals(0, in.readUnsignedByte());
        int count = in.readUnsignedShort();
        int[] encodings = new int[count];
        for (int i = 0; i < count; i++) {
            encodings[i] = in.readInt();
        }
        return encodings;
    }
}
