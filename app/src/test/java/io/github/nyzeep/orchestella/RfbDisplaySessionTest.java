package io.github.nyzeep.orchestella;

import static org.junit.Assert.*;
import io.github.nyzeep.orchestella.runtime.contract.TextCommitEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
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
            assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state());
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
            assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state());
            assertFalse(channel.connect(RfbDisplayChannel.InputCapabilities.NONE));
        }
    }

    @Test public void transportFailureCannotAdvertiseAConnectedDesktop() throws Exception {
        try (RfbDisplaySession channel = new RfbDisplaySession(() -> { throw new IOException("unavailable"); },
                "a".repeat(64), () -> null)) {
            CountDownLatch error = new CountDownLatch(1);
            channel.addStateListener((state, message) -> { if (state == WaylandChannel.ConnectionState.ERROR) error.countDown(); });
            assertTrue(channel.connect(RfbDisplayChannel.InputCapabilities.NONE));
            assertTrue(error.await(2, TimeUnit.SECONDS));
            assertFalse(channel.isConnected());
        }
    }
}
