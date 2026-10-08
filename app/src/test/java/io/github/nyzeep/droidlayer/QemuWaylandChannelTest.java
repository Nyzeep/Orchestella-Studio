package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.droidlayer.runtime.contract.DisplayMode;
import io.github.nyzeep.droidlayer.runtime.contract.InputEvent;
import io.github.nyzeep.droidlayer.runtime.contract.PointerEvent;
import io.github.nyzeep.droidlayer.runtime.contract.TextCommitEvent;
import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class QemuWaylandChannelTest {

    @Test
    public void testChannelInitialStateAndMode() {
        try (QemuWaylandChannel channel = new QemuWaylandChannel("test-instance", "test-token")) {
            assertEquals(WaylandChannel.ConnectionState.CONNECTED, channel.state());
            assertTrue(channel.isConnected());

            DisplayMode mode = channel.currentMode();
            assertNotNull(mode);
            assertEquals(1280, mode.width());
            assertEquals(720, mode.height());
            assertEquals(60, mode.targetFps());
        }
    }

    @Test
    public void testFramePumpDeliversFrames() throws Exception {
        try (QemuWaylandChannel channel = new QemuWaylandChannel("test-instance", "test-token")) {
            CountDownLatch frameLatch = new CountDownLatch(3);
            AtomicInteger frameCount = new AtomicInteger(0);
            AtomicReference<WaylandChannel.FrameData> lastFrame = new AtomicReference<>();

            channel.registerFrameListener(frame -> {
                lastFrame.set(frame);
                frameCount.incrementAndGet();
                frameLatch.countDown();
            });

            boolean received = frameLatch.await(500, TimeUnit.MILLISECONDS);
            assertTrue("Expected to receive frames from pump", received);
            assertTrue(frameCount.get() >= 3);

            WaylandChannel.FrameData frame = lastFrame.get();
            assertNotNull(frame);
            assertEquals(1280, frame.width());
            assertEquals(720, frame.height());
            assertEquals(1280 * 4, frame.stride());
            assertEquals(WaylandChannel.FrameData.FORMAT_RGBA_8888, frame.format());
            assertNotNull(frame.data());
            assertEquals(1280 * 720 * 4, frame.data().length);
        }
    }

    @Test
    public void testResizeAndScaleDisplay() {
        try (QemuWaylandChannel channel = new QemuWaylandChannel("test-instance", "test-token")) {
            assertTrue(channel.resizeDisplay(1920, 1080));
            DisplayMode mode = channel.currentMode();
            assertEquals(1920, mode.width());
            assertEquals(1080, mode.height());

            assertTrue(channel.updateScale(1.5));
            assertEquals(1.5, channel.currentMode().scale(), 0.001);

            assertTrue(channel.updateTargetFps(30));
            assertEquals(30, channel.currentMode().targetFps());
        }
    }

    @Test
    public void testInputEventForwarding() {
        try (QemuWaylandChannel channel = new QemuWaylandChannel("test-instance", "test-token")) {
            InputEvent pointerEvent = new PointerEvent(
                    PointerEvent.Action.MOVE, 100.0, 200.0, 0.0, 0.0, PointerEvent.BUTTON_NONE, System.nanoTime());
            assertTrue(channel.sendInputEvent(pointerEvent));

            InputEvent textEvent = new TextCommitEvent("Hello Wayland", System.nanoTime());
            assertTrue(channel.sendInputEvent(textEvent));
        }
    }

    @Test
    public void testCloseTransitionsToDisconnected() {
        QemuWaylandChannel channel = new QemuWaylandChannel("test-instance", "test-token");
        assertEquals(WaylandChannel.ConnectionState.CONNECTED, channel.state());
        channel.close();
        assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state());
    }
}
