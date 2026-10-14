package io.github.nyzeep.orchestella.runtime.contract.display;

import static org.junit.Assert.*;

import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import io.github.nyzeep.orchestella.runtime.contract.KeyEvent;
import io.github.nyzeep.orchestella.runtime.contract.TextCommitEvent;
import io.github.nyzeep.orchestella.runtime.contract.PointerEvent;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Synthetic wire tests: these do not claim a real guest, compositor capture or GPU result. */
public final class RfbDisplayChannelTest {
    private static final String TOKEN = "0123456789abcdef".repeat(4);

    @Test(timeout = 5000)
    public void authenticatesAndConvertsRealWirePixelsBeforeConnected() throws Exception {
        try (Wire wire = new Wire()) {
            assertTrue(wire.channel.connect());
            wire.handshake(2, 1);
            assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, wire.channel.state());
            assertEquals(0, wire.channel.statistics().frames());
            wire.expectRequest(false, 2, 1);
            wire.raw(0, 0, 2, 1, new byte[] {0, 0, (byte) 255, 0, 0, (byte) 255, 0, 0});
            WaylandChannel.FrameData frame = wire.frame();
            assertEquals(WaylandChannel.ConnectionState.CONNECTED, wire.channel.state());
            assertEquals(2, frame.width());
            assertEquals(8, frame.stride());
            assertEquals(WaylandChannel.FrameData.FORMAT_RGBA_8888, frame.format());
            assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255}, frame.data());
            wire.expectRequest(true, 2, 1);
            wire.raw(1, 0, 1, 1, new byte[] {(byte) 255, 0, 0, 0});
            WaylandChannel.FrameData changed = wire.frame();
            assertArrayEquals(new byte[] {(byte) 255, 0, 0, (byte) 255, 0, 0, (byte) 255, (byte) 255}, changed.data());
            assertTrue(changed.frameId() > frame.frameId());
            assertEquals(2, wire.channel.statistics().frames());
            assertEquals(12, wire.channel.statistics().pixelBytes());
            assertTrue(wire.channel.message().contains("capture not verified"));
        }
    }

    @Test(timeout = 5000)
    public void sendsEvdevModifiersUnicodeAndPointerAsOrderedRfbPackets() throws Exception {
        try (Wire wire = new Wire(new RfbDisplayChannel.InputCapabilities(true, true, true))) {
            wire.channel.connect();
            wire.handshake(8, 4);
            wire.expectRequest(false, 8, 4);
            wire.raw(0, 0, 8, 4, new byte[128]);
            wire.frame();
            wire.expectRequest(true, 8, 4);
            assertTrue(wire.channel.sendInputEvent(KeyEvent.press(125, KeyEvent.MOD_SUPER, 1)));
            assertTrue(wire.channel.sendInputEvent(KeyEvent.press(103, KeyEvent.MOD_SUPER, 2)));
            assertTrue(wire.channel.sendInputEvent(KeyEvent.release(103, KeyEvent.MOD_SUPER, 3)));
            assertTrue(wire.channel.sendInputEvent(KeyEvent.release(125, 0, 4)));
            wire.key(true, 0xffeb);
            wire.key(true, 0xff52);
            wire.key(false, 0xff52);
            wire.key(false, 0xffeb);
            assertTrue(wire.channel.sendInputEvent(new TextCommitEvent("中\ud83d\ude42", 5)));
            wire.key(true, 0x01004e2d);
            wire.key(false, 0x01004e2d);
            wire.key(true, 0x0101f642);
            wire.key(false, 0x0101f642);
            assertTrue(wire.channel.sendInputEvent(PointerEvent.buttonDown(3, 2, PointerEvent.BUTTON_RIGHT, 6)));
            assertTrue(wire.channel.sendInputEvent(PointerEvent.relativeMotion(2, -1, PointerEvent.BUTTON_SECONDARY, 7)));
            assertTrue(wire.channel.sendInputEvent(PointerEvent.scroll(5, 1, -1, 1, 8)));
            assertTrue(wire.channel.sendInputEvent(PointerEvent.buttonUp(5, 1, PointerEvent.BUTTON_RIGHT, 9)));
            wire.pointer(4, 3, 2);
            wire.pointer(4, 5, 1);
            wire.pointer(12, 5, 1); // positive vertical wheel: up, retains right button
            wire.pointer(4, 5, 1);
            wire.pointer(36, 5, 1); // negative horizontal wheel: left
            wire.pointer(4, 5, 1);
            wire.pointer(0, 5, 1);
            assertFalse(wire.channel.sendInputEvent(KeyEvent.press(9999, 0, 10)));
            assertFalse(wire.channel.sendInputEvent(new TextCommitEvent("\ud800", 11)));
            assertFalse(wire.channel.sendInputEvent(PointerEvent.motion(Double.NaN, 0, 0, 0, 12)));
        }
    }

    @Test(timeout = 5000)
    public void incompleteFirstPixelsAndResizeNeverPublishFabricatedRemainder() throws Exception {
        try (Wire wire = new Wire()) {
            wire.channel.connect();
            wire.handshake(2, 1);
            wire.expectRequest(false, 2, 1);
            wire.raw(0, 0, 1, 1, new byte[] {0, 0, (byte) 255, 0});
            wire.expectRequest(false, 2, 1);
            assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, wire.channel.state());
            assertTrue(wire.frames.isEmpty());
            wire.raw(1, 0, 1, 1, new byte[] {0, (byte) 255, 0, 0});
            wire.frame();
            wire.expectRequest(true, 2, 1);
            wire.out.write(new byte[] {0, 0, 0, 1});
            wire.rectangle(0, 0, 1, 2, -223);
            wire.out.flush();
            wire.expectRequest(false, 1, 2);
            assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, wire.channel.state());
            assertTrue(wire.frames.isEmpty());
            wire.raw(0, 0, 1, 2, new byte[] {(byte) 255, 0, 0, 0, (byte) 255, (byte) 255, (byte) 255, 0});
            WaylandChannel.FrameData resized = wire.frame();
            assertEquals(1, resized.width());
            assertEquals(2, resized.height());
            assertArrayEquals(new byte[] {0, 0, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 255}, resized.data());
        }
    }

    @Test(timeout = 5000)
    public void oneUpdateWithMultipleRectanglesAndEmptyUpdatesHasOneFollowingRequest() throws Exception {
        try (Wire wire = new Wire()) {
            wire.channel.connect();
            wire.handshake(2, 1);
            wire.expectRequest(false, 2, 1);
            wire.out.write(new byte[] {0, 0, 0, 2});
            wire.rectangle(0, 0, 1, 1, 0);
            wire.out.write(new byte[] {0, 0, (byte) 255, 0});
            wire.rectangle(1, 0, 1, 1, 0);
            wire.out.write(new byte[] {(byte) 255, 0, 0, 0});
            wire.out.flush();
            wire.frame();
            wire.expectRequest(true, 2, 1);
            assertEquals(0, wire.in.available());
            wire.out.write(new byte[] {2, 3, 0, 0, 0, 0, 0, 0, 4, 't', 'e', 's', 't', 0, 0, 0, 0});
            wire.out.flush(); // Bell + bounded clipboard + empty FramebufferUpdate
            wire.expectRequest(true, 2, 1);
            assertEquals(0, wire.in.available());
            assertEquals(1, wire.channel.statistics().frames());
            assertEquals(2, wire.channel.statistics().updates());
            assertEquals(2, wire.channel.statistics().rectangles());
        }
    }

    @Test(timeout = 5000)
    public void rejectsTokenResponseWithoutReflectingSecretsOrStartingRfb() throws Exception {
        try (Wire wire = new Wire()) {
            wire.channel.connect();
            assertTrue(wire.line().startsWith("ORCHESTELLA/DISPLAY 1 "));
            wire.out.write(("ORCHESTELLA/DISPLAY ERROR auth " + TOKEN + "\n").getBytes(StandardCharsets.US_ASCII));
            wire.out.flush();
            wire.error("authentication");
            assertEquals(0, wire.channel.statistics().frames());
            assertFalse(wire.channel.message().contains(TOKEN));
            assertFalse(wire.channel.history().toString().contains(TOKEN));
        }
    }

    @Test(timeout = 5000)
    public void rejectsUnknownEncodingCopyRectRectangleBoundsAndTruncatedPixels() throws Exception {
        for (int encoding : new int[] {1, 5, 999}) {
            try (Wire wire = new Wire()) {
                wire.channel.connect(); wire.handshake(2, 1); wire.expectRequest(false, 2, 1);
                wire.out.write(new byte[] {0, 0, 0, 1});
                wire.rectangle(0, 0, 1, 1, encoding); wire.out.flush();
                wire.error("encoding");
            }
        }
        try (Wire wire = new Wire()) {
            wire.channel.connect(); wire.handshake(2, 1); wire.expectRequest(false, 2, 1);
            wire.raw(2, 0, 1, 1, new byte[4]);
            wire.error("outside");
        }
        try (Wire wire = new Wire()) {
            wire.channel.connect(); wire.handshake(2, 1); wire.expectRequest(false, 2, 1);
            wire.raw(0, 0, 2, 1, new byte[3]);
            wire.serverOutput.close();
            wire.error("ended");
            assertTrue(wire.frames.isEmpty());
        }
    }

    @Test(timeout = 5000)
    public void rejectsUntrustedDimensionsNamesFormatsAndRectangleCountsBeforePayload() throws Exception {
        for (int[] size : new int[][] {{0, 1}, {4097, 1}, {4096, 4096}, {2049, 2048}}) {
            try (Wire wire = new Wire()) {
                wire.channel.connect(); wire.beginHandshake();
                wire.out.writeShort(size[0]); wire.out.writeShort(size[1]); wire.out.flush();
                wire.error("dimensions");
            }
        }
        try (Wire wire = new Wire()) {
            wire.channel.connect(); wire.beginHandshake();
            wire.serverInit(2, 1, Wire.FORMAT, 4097, new byte[0]);
            wire.error("desktop name");
        }
        try (Wire wire = new Wire()) {
            wire.channel.connect(); wire.beginHandshake();
            byte[] invalid = Wire.FORMAT.clone(); invalid[11] = 16;
            wire.serverInit(2, 1, invalid, 0, new byte[0]);
            wire.error("color components");
        }
        try (Wire wire = new Wire()) {
            wire.channel.connect(); wire.handshake(2, 1); wire.expectRequest(false, 2, 1);
            wire.out.write(new byte[] {0, 0, 1, 1}); wire.out.flush();
            wire.error("rectangle count");
        }
    }

    @Test(timeout = 5000)
    public void queueIsBoundedAndWriterFailureWakesReaderWithoutAutomaticRetry() throws Exception {
        try (Wire wire = new Wire(new RfbDisplayChannel.InputCapabilities(true, false, false), new RfbDisplayChannel.Options(2, 1000, 1000))) {
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.raw(0, 0, 1, 1, new byte[4]); wire.frame(); wire.expectRequest(true, 1, 1);
            wire.gate.block = true;
            assertTrue(wire.channel.sendInputEvent(KeyEvent.press(30, 0, 1)));
            assertTrue(wire.gate.entered.await(1, TimeUnit.SECONDS));
            assertTrue(wire.channel.sendInputEvent(KeyEvent.release(30, 0, 2)));
            assertTrue(wire.channel.sendInputEvent(KeyEvent.press(46, 0, 3)));
            assertFalse(wire.channel.sendInputEvent(KeyEvent.release(46, 0, 4)));
            wire.gate.fail = true;
            wire.gate.release.countDown();
            wire.error("writer");
            assertFalse(wire.channel.sendInputEvent(KeyEvent.press(30, 0, 5)));
            assertEquals(0, wire.channel.statistics().inputEvents());
            assertEquals(1, wire.gate.maximumWriters.get());
            assertFalse(wire.channel.message().contains(TOKEN));
        }
    }

    @Test(timeout = 5000)
    public void defaultCapabilitiesRejectInputAndNoSamplesMeansNoFps() throws Exception {
        try (Wire wire = new Wire()) {
            assertFalse(wire.channel.statistics().averageReceiveFps().isPresent());
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.raw(0, 0, 1, 1, new byte[4]); wire.frame(); wire.expectRequest(true, 1, 1);
            assertFalse(wire.channel.sendInputEvent(KeyEvent.press(30, 0, 0)));
            assertFalse(wire.channel.sendInputEvent(new TextCommitEvent("中", 0)));
            assertFalse(wire.channel.sendInputEvent(PointerEvent.motion(0, 0, 0, 0, 0)));
            assertFalse(wire.channel.statistics().averageReceiveFps().isPresent());
            assertEquals(0, wire.in.available());
        }
    }

    @Test(timeout = 5000)
    @SuppressWarnings("try") // Explicit double-close is the lifecycle behavior under test.
    public void reconnectUsesFreshStreamsAndRetainsErrorHistoryWithoutReplayingFrames() throws Exception {
        try (Wire first = new Wire(); Wire second = new Wire()) {
            AtomicInteger opens = new AtomicInteger();
            try (RfbDisplayChannel channel = new RfbDisplayChannel(() -> {
                Wire target = opens.getAndIncrement() == 0 ? first : second;
                return new RfbDisplayChannel.Streams(target.clientInput, target.clientOutput);
            }, TOKEN)) {
                BlockingQueue<WaylandChannel.ConnectionState> states = new LinkedBlockingQueue<>();
                channel.addStateListener((state, detail) -> states.add(state));
                channel.registerFrameListener(first.frames::add);
                channel.connect(); first.handshake(1, 1); first.expectRequest(false, 1, 1);
                first.raw(0, 0, 1, 1, new byte[] {0, 0, (byte) 255, 0});
                long id = first.frame().frameId(); first.expectRequest(true, 1, 1);
                first.serverOutput.close();
                WaylandChannel.ConnectionState observed;
                do {
                    observed = states.poll(1, TimeUnit.SECONDS);
                    assertNotNull("EOF did not produce an error", observed);
                } while (observed != WaylandChannel.ConnectionState.ERROR);
                assertEquals(1, opens.get());
                assertTrue(channel.reconnect());
                assertFalse(channel.connect());
                second.handshake(1, 1); second.expectRequest(false, 1, 1);
                assertEquals(0, channel.statistics().frames());
                assertTrue(first.frames.isEmpty());
                second.raw(0, 0, 1, 1, new byte[] {(byte) 255, 0, 0, 0});
                assertTrue(first.frame().frameId() > id);
                assertTrue(channel.history().stream().anyMatch(change -> change.state() == WaylandChannel.ConnectionState.ERROR));
                channel.close(); channel.close();
                assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state());
            }
        }
    }

    @Test(timeout = 5000)
    public void establishmentDeadlinesAndCloseFromListenerAreDeterministic() throws Exception {
        try (Wire wire = new Wire(RfbDisplayChannel.InputCapabilities.NONE, new RfbDisplayChannel.Options(2, 100, 100))) {
            wire.channel.connect(); wire.line(); wire.error("handshake timed out");
        }
        try (Wire wire = new Wire(RfbDisplayChannel.InputCapabilities.NONE, new RfbDisplayChannel.Options(2, 1000, 100))) {
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.error("pixel frame timed out");
        }
        try (Wire wire = new Wire()) {
            wire.channel.addStateListener((state, detail) -> wire.channel.close());
            wire.channel.connect();
            assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, wire.channel.state());
        }
    }

    @Test(timeout = 5000)
    public void rejectsVersionsSecurityChoicesAndOversizedIgnoredMessages() throws Exception {
        try (Wire wire = new Wire()) {
            wire.channel.connect(); wire.line();
            wire.out.write("ORCHESTELLA/DISPLAY READY 1\nRFB 003.007\n".getBytes(StandardCharsets.US_ASCII)); wire.out.flush();
            wire.error("version");
        }
        for (byte[] security : new byte[][] {{1, 2}, {33}, {0, 0, 0, 16, 1}}) {
            try (Wire wire = new Wire()) {
                wire.channel.connect(); wire.line();
                wire.out.write("ORCHESTELLA/DISPLAY READY 1\nRFB 003.008\n".getBytes(StandardCharsets.US_ASCII)); wire.out.flush();
                byte[] version = new byte[12]; wire.in.readFully(version);
                wire.out.write(security); wire.out.flush();
                wire.error(security[0] == 1 ? "security unavailable" : security[0] == 33 ? "security type count" : "security reason");
            }
        }
        try (Wire wire = new Wire()) {
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.out.write(new byte[] {3, 0, 0, 0}); wire.out.writeInt(-1); wire.out.flush();
            wire.error("cut text");
        }
        try (Wire wire = new Wire()) {
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.out.writeByte(1); wire.out.flush(); wire.error("server message");
        }
    }

    @Test(timeout = 5000)
    public void syntheticModifiersAndLiteralTextPreserveVirtualLatchWithoutShell() throws Exception {
        try (Wire wire = new Wire(new RfbDisplayChannel.InputCapabilities(true, true, false))) {
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.raw(0, 0, 1, 1, new byte[4]); wire.frame(); wire.expectRequest(true, 1, 1);
            assertTrue(wire.channel.sendInputEvent(KeyEvent.press(30, KeyEvent.MOD_CTRL, 1)));
            assertTrue(wire.channel.sendInputEvent(KeyEvent.release(30, 0, 2)));
            wire.key(true, 0xffe3); wire.key(true, 'a'); wire.key(false, 'a'); wire.key(false, 0xffe3);
            assertTrue(wire.channel.sendInputEvent(KeyEvent.press(42, KeyEvent.MOD_SHIFT, 3)));
            assertTrue(wire.channel.sendInputEvent(new TextCommitEvent("$", 4)));
            assertTrue(wire.channel.sendInputEvent(KeyEvent.release(42, 0, 5)));
            wire.key(true, 0xffe1); wire.key(false, 0xffe1);
            wire.key(true, '$'); wire.key(false, '$');
            wire.key(true, 0xffe1); wire.key(false, 0xffe1);
            assertFalse(wire.channel.sendInputEvent(new TextCommitEvent("x".repeat(1025), 6)));
            assertFalse(wire.channel.sendInputEvent(KeyEvent.press(30, KeyEvent.MOD_CAPS_LOCK, 7)));
            assertFalse(wire.channel.sendInputEvent(new io.github.nyzeep.orchestella.runtime.contract.InputEvent() {
                @Override public Type eventType() { return Type.TOUCH; }
                @Override public long timestampNs() { return 0; }
                @Override public String serialize() { throw new AssertionError("custom input must not serialize"); }
            }));
        }
    }

    @Test(timeout = 5000)
    public void keyboardCapabilitySupportsAsciiImeWithoutClaimingUnicodeSupport() throws Exception {
        try (Wire wire = new Wire(new RfbDisplayChannel.InputCapabilities(true, false, false))) {
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.raw(0, 0, 1, 1, new byte[4]); wire.frame(); wire.expectRequest(true, 1, 1);
            assertTrue(wire.channel.sendInputEvent(new TextCommitEvent("A$\t\n", 1)));
            wire.key(true, 'A'); wire.key(false, 'A');
            wire.key(true, '$'); wire.key(false, '$');
            wire.key(true, 0xff09); wire.key(false, 0xff09);
            wire.key(true, 0xff0d); wire.key(false, 0xff0d);
            assertFalse(wire.channel.sendInputEvent(new TextCommitEvent("中", 2)));
        }
    }

    @Test(timeout = 5000)
    public void callbacksBackpressureFramesAndExceptionsDoNotBreakTransport() throws Exception {
        try (Wire wire = new Wire()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            wire.channel.registerFrameListener(frame -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            wire.channel.registerFrameListener(frame -> { throw new IllegalStateException(TOKEN); });
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.raw(0, 0, 1, 1, new byte[4]); wire.frame();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(0, wire.in.available()); // next request waits for consumer; no frame queue
            release.countDown(); wire.expectRequest(true, 1, 1);
            assertEquals(1, wire.channel.statistics().listenerFailures());
            assertEquals(WaylandChannel.ConnectionState.CONNECTED, wire.channel.state());
        }
    }

    @Test(timeout = 15000)
    public void acceptsExactFourMegapixelLimitButRejectsAggregateOverSixteenMiB() throws Exception {
        try (Wire wire = new Wire()) {
            wire.channel.connect(); wire.handshake(4096, 1024); wire.expectRequest(false, 4096, 1024);
            wire.out.write(new byte[] {0, 0, 0, 1}); wire.rectangle(0, 0, 4096, 1024, 0);
            byte[] row = new byte[16384]; row[2] = (byte) 255;
            for (int y = 0; y < 1024; y++) { wire.out.write(row); }
            wire.out.flush();
            WaylandChannel.FrameData frame = wire.frame();
            assertEquals(16_777_216, frame.data().length);
            assertEquals(16_777_216, wire.channel.statistics().pixelBytes());
            wire.expectRequest(true, 4096, 1024);
            wire.out.write(new byte[] {0, 0, 0, 2}); wire.rectangle(0, 0, 4096, 1024, 0);
            for (int y = 0; y < 1024; y++) { wire.out.write(row); }
            wire.rectangle(0, 0, 1, 1, 0); wire.out.flush();
            wire.error("update pixel bytes");
            assertEquals(1, wire.channel.statistics().frames());
        }
    }

    @Test(timeout = 5000)
    public void partialUpdateAfterConnectedHasDeadlineButIdleDesktopDoesNot() throws Exception {
        try (Wire wire = new Wire(RfbDisplayChannel.InputCapabilities.NONE, new RfbDisplayChannel.Options(2, 1000, 100))) {
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.raw(0, 0, 1, 1, new byte[4]); wire.frame(); wire.expectRequest(true, 1, 1);
            // Drain prior notifications, then allow a genuinely idle desktop without a timeout.
            wire.states.clear();
            assertNull(wire.states.poll(150, TimeUnit.MILLISECONDS));
            assertEquals(WaylandChannel.ConnectionState.CONNECTED, wire.channel.state());
            wire.out.write(new byte[] {0, 0, 0, 1}); wire.rectangle(0, 0, 1, 1, 0);
            wire.out.writeByte(0); wire.out.flush(); // truncated, but deliberately keeps the pipe open
            wire.error("update timed out");
        }
    }

    @Test(timeout = 5000)
    public void blockedInputWriterTimesOutAndRejectsFurtherEvents() throws Exception {
        try (Wire wire = new Wire(new RfbDisplayChannel.InputCapabilities(true, false, false), new RfbDisplayChannel.Options(2, 1000, 100))) {
            wire.channel.connect(); wire.handshake(1, 1); wire.expectRequest(false, 1, 1);
            wire.raw(0, 0, 1, 1, new byte[4]); wire.frame(); wire.expectRequest(true, 1, 1);
            wire.gate.block = true;
            assertTrue(wire.channel.sendInputEvent(KeyEvent.press(30, 0, 1)));
            assertTrue(wire.gate.entered.await(1, TimeUnit.SECONDS));
            wire.error("write timed out");
            assertFalse(wire.channel.sendInputEvent(KeyEvent.release(30, 0, 2)));
            assertEquals(0, wire.channel.statistics().inputEvents());
        }
    }

    private static final class Gate extends OutputStream {
        final OutputStream delegate;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger writers = new AtomicInteger();
        final AtomicInteger maximumWriters = new AtomicInteger();
        volatile boolean block;
        volatile boolean fail;
        Gate(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            maximumWriters.accumulateAndGet(writers.incrementAndGet(), Math::max);
            try {
                if (block && length > 0 && (bytes[offset] == 4 || bytes[offset] == 5)) {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IOException("cancelled", interrupted);
                    }
                }
                if (fail) { throw new IOException(TOKEN); }
                delegate.write(bytes, offset, length);
            } finally { writers.decrementAndGet(); }
        }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { release.countDown(); delegate.close(); }
    }

    private static final class Wire implements AutoCloseable {
        final PipedInputStream clientInput = new PipedInputStream(65536);
        final PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
        final PipedInputStream serverInput = new PipedInputStream(65536);
        final PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
        final Gate gate = new Gate(clientOutput);
        final BlockingQueue<RfbDisplayChannel.StateChange> states = new LinkedBlockingQueue<>();
        static final byte[] FORMAT = {32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0};
        final DataInputStream in = new DataInputStream(serverInput);
        final DataOutputStream out = new DataOutputStream(serverOutput);
        final BlockingQueue<WaylandChannel.FrameData> frames = new LinkedBlockingQueue<>();
        final RfbDisplayChannel channel;

        Wire() throws IOException {
            this(RfbDisplayChannel.InputCapabilities.NONE);
        }

        Wire(RfbDisplayChannel.InputCapabilities capabilities) throws IOException {
            this(capabilities, RfbDisplayChannel.Options.DEFAULT);
        }

        Wire(RfbDisplayChannel.InputCapabilities capabilities, RfbDisplayChannel.Options options) throws IOException {
            channel = new RfbDisplayChannel(() -> new RfbDisplayChannel.Streams(clientInput, gate), TOKEN, capabilities, options);
            channel.registerFrameListener(frames::add);
            channel.addStateListener((state, detail) -> states.add(new RfbDisplayChannel.StateChange(state, detail, 0)));
        }

        void error(String expected) throws InterruptedException {
            RfbDisplayChannel.StateChange change;
            do {
                change = states.poll(2, TimeUnit.SECONDS);
                assertNotNull("missing error: " + channel.message(), change);
            } while (change.state() != WaylandChannel.ConnectionState.ERROR);
            assertTrue(change.message(), change.message().contains(expected));
            assertEquals(WaylandChannel.ConnectionState.ERROR, channel.state());
        }

        void key(boolean down, int keysym) throws IOException {
            assertEquals(4, in.readUnsignedByte());
            assertEquals(down ? 1 : 0, in.readUnsignedByte());
            assertEquals(0, in.readUnsignedShort());
            assertEquals(keysym, in.readInt());
        }

        void pointer(int buttons, int x, int y) throws IOException {
            assertEquals(5, in.readUnsignedByte());
            assertEquals(buttons, in.readUnsignedByte());
            assertEquals(x, in.readUnsignedShort());
            assertEquals(y, in.readUnsignedShort());
        }

        void beginHandshake() throws IOException {
            assertEquals("ORCHESTELLA/DISPLAY 1 " + TOKEN + "\n", line());
            out.write("ORCHESTELLA/DISPLAY READY 1\nRFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] version = new byte[12];
            in.readFully(version);
            assertEquals("RFB 003.008\n", new String(version, StandardCharsets.US_ASCII));
            out.write(new byte[] {1, 1});
            out.flush();
            assertEquals(1, in.readUnsignedByte());
            out.writeInt(0);
            out.flush();
            assertEquals(1, in.readUnsignedByte()); // shared desktop, not an exclusive takeover
        }

        void serverInit(int width, int height, byte[] format, int nameLength, byte[] name) throws IOException {
            out.writeShort(width);
            out.writeShort(height);
            out.write(format);
            out.writeInt(nameLength);
            out.write(name);
            out.flush();
        }

        void handshake(int width, int height) throws IOException {
            beginHandshake();
            serverInit(width, height, FORMAT, 4, "test".getBytes(StandardCharsets.US_ASCII));
            byte[] format = new byte[20];
            in.readFully(format);
            assertArrayEquals(new byte[] {0, 0, 0, 0, 32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0}, format);
            assertEquals(2, in.readUnsignedByte());
            assertEquals(0, in.readUnsignedByte());
            assertEquals(2, in.readUnsignedShort());
            assertEquals(0, in.readInt());
            assertEquals(-223, in.readInt());
        }

        String line() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int next; (next = in.read()) != -1;) {
                bytes.write(next);
                if (next == '\n') {
                    return bytes.toString(StandardCharsets.US_ASCII);
                }
            }
            throw new IOException("unexpected end of test pipe");
        }

        void expectRequest(boolean incremental, int width, int height) throws IOException {
            byte[] request = new byte[10];
            in.readFully(request);
            assertArrayEquals(new byte[] {3, (byte) (incremental ? 1 : 0), 0, 0, 0, 0,
                    (byte) (width >>> 8), (byte) width, (byte) (height >>> 8), (byte) height}, request);
        }

        void raw(int x, int y, int width, int height, byte[] pixels) throws IOException {
            out.writeByte(0);
            out.writeByte(0);
            out.writeShort(1);
            rectangle(x, y, width, height, 0);
            out.write(pixels);
            out.flush();
        }

        void rectangle(int x, int y, int width, int height, int encoding) throws IOException {
            out.writeShort(x);
            out.writeShort(y);
            out.writeShort(width);
            out.writeShort(height);
            out.writeInt(encoding);
        }

        WaylandChannel.FrameData frame() throws InterruptedException {
            WaylandChannel.FrameData result = frames.poll(2, TimeUnit.SECONDS);
            assertNotNull("frame missing: " + channel.message(), result);
            return result;
        }

        @Override
        public void close() {
            channel.close();
            try {
                in.close();
                out.close();
            } catch (IOException ignored) {
                // Closing either side may already have closed the pipe.
            }
        }
    }
}
