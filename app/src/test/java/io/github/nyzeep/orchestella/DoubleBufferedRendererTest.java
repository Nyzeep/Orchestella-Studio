package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class DoubleBufferedRendererTest {

    private static final class TestPresenter implements DoubleBufferedRenderer.SurfacePresenter {
        static final class PresentationRecord {
            final int[] pixels;
            final int width;
            final int height;
            final ViewportAspectCalculator.ViewportMapping mapping;

            PresentationRecord(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
                this.pixels = pixels.clone();
                this.width = width;
                this.height = height;
                this.mapping = mapping;
            }
        }

        boolean ready = true;
        final List<PresentationRecord> presentations = new ArrayList<>();
        int clearCount = 0;
        int releaseCount = 0;

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
            presentations.add(new PresentationRecord(pixels, width, height, mapping));
        }

        @Override
        public void clear(int color) {
            clearCount++;
        }

        @Override
        public void release() {
            releaseCount++;
            ready = false;
        }
    }

    private static final class TestVsyncScheduler implements DoubleBufferedRenderer.VsyncScheduler {
        Runnable registeredCallback;
        int requestCount = 0;
        int cancelCount = 0;

        @Override
        public void requestVsync(Runnable callback) {
            this.registeredCallback = callback;
            requestCount++;
        }

        @Override
        public void cancel() {
            this.registeredCallback = null;
            cancelCount++;
        }

        void triggerVsync() {
            Runnable cb = registeredCallback;
            registeredCallback = null;
            if (cb != null) {
                cb.run();
            }
        }
    }

    private WaylandChannel.FrameData createFrame(int width, int height, int format, byte[] data, long frameId) {
        return new WaylandChannel.FrameData(
                width,
                height,
                width * 4,
                format,
                data,
                frameId,
                System.nanoTime()
        );
    }

    @Test
    public void doubleBufferingPingPongSwap() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        renderer.setViewportSize(1920, 1080);

        byte[] pixels1 = new byte[4 * 4]; // 2x2
        WaylandChannel.FrameData frame1 = createFrame(2, 2, WaylandChannel.FrameData.FORMAT_RGBA_8888, pixels1, 101);

        renderer.onFrameAvailable(frame1);
        assertTrue(renderer.hasNewFrame());
        assertEquals(0, presenter.presentations.size());

        // VSYNC tick consumes frame1
        scheduler.triggerVsync();
        assertEquals(1, presenter.presentations.size());
        assertEquals(1, renderer.getRenderedFrameCount());
        assertFalse(renderer.hasNewFrame());

        // Frame 2
        byte[] pixels2 = new byte[4 * 4];
        WaylandChannel.FrameData frame2 = createFrame(2, 2, WaylandChannel.FrameData.FORMAT_RGBA_8888, pixels2, 102);

        renderer.onFrameAvailable(frame2);
        assertTrue(renderer.hasNewFrame());

        scheduler.triggerVsync();
        assertEquals(2, presenter.presentations.size());
        assertEquals(2, renderer.getRenderedFrameCount());
        assertEquals(0, renderer.getDroppedFrameCount());
    }

    @Test
    public void staticSceneDoesNotRedrawOnVsync() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        renderer.setViewportSize(1920, 1080);

        byte[] pixels = new byte[4 * 4];
        renderer.onFrameAvailable(createFrame(2, 2, WaylandChannel.FrameData.FORMAT_RGBA_8888, pixels, 1));
        scheduler.triggerVsync();
        assertEquals(1, presenter.presentations.size());

        // Without any new frame, multiple VSYNC ticks should not perform redrawing
        for (int i = 0; i < 10; i++) {
            scheduler.triggerVsync();
        }

        assertEquals(1, presenter.presentations.size());
        assertEquals(1, renderer.getRenderedFrameCount());
    }

    @Test
    public void dropsLaggingStaleFramesWhenProducerOutpacesConsumer() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        renderer.setViewportSize(1920, 1080);

        byte[] p1 = new byte[]{1, 1, 1, (byte) 255, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] p2 = new byte[]{2, 2, 2, (byte) 255, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] p3 = new byte[]{3, 3, 3, (byte) 255, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] p4 = new byte[]{4, 4, 4, (byte) 255, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

        // Producer pushes 4 frames before VSYNC fires
        renderer.onFrameAvailable(createFrame(2, 2, WaylandChannel.FrameData.FORMAT_RGBA_8888, p1, 1));
        renderer.onFrameAvailable(createFrame(2, 2, WaylandChannel.FrameData.FORMAT_RGBA_8888, p2, 2));
        renderer.onFrameAvailable(createFrame(2, 2, WaylandChannel.FrameData.FORMAT_RGBA_8888, p3, 3));
        renderer.onFrameAvailable(createFrame(2, 2, WaylandChannel.FrameData.FORMAT_RGBA_8888, p4, 4));

        assertEquals(3, renderer.getDroppedFrameCount());
        assertTrue(renderer.hasNewFrame());

        // VSYNC consumes only the latest frame (p4)
        scheduler.triggerVsync();
        assertEquals(1, presenter.presentations.size());
        assertEquals(1, renderer.getRenderedFrameCount());
        assertEquals(3, renderer.getDroppedFrameCount());

        // Check that pixel 0 corresponds to p4 (R=4, G=4, B=4, A=255)
        int presentedPixel = presenter.presentations.get(0).pixels[0];
        int expectedArgb = (0xFF << 24) | (4 << 16) | (4 << 8) | 4;
        assertEquals(expectedArgb, presentedPixel);
    }

    @Test
    public void pixelFormatConversionRgba8888() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        renderer.setViewportSize(100, 100);

        // 1x2 image:
        // Pixel 0: R=255, G=0, B=0, A=255 (Red) -> 0xFFFF0000
        // Pixel 1: R=0, G=255, B=0, A=128 (Semi-transparent Green) -> 0x8000FF00
        byte[] bytes = new byte[]{
                (byte) 255, 0, 0, (byte) 255,
                0, (byte) 255, 0, (byte) 128
        };
        renderer.onFrameAvailable(new WaylandChannel.FrameData(
                1, 2, 4, WaylandChannel.FrameData.FORMAT_RGBA_8888, bytes, 1, 0));
        scheduler.triggerVsync();

        assertEquals(1, presenter.presentations.size());
        int[] pixels = presenter.presentations.get(0).pixels;
        assertEquals(0xFFFF0000, pixels[0]);
        assertEquals(0x8000FF00, pixels[1]);
    }

    @Test
    public void pixelFormatConversionRgbx8888() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        renderer.setViewportSize(100, 100);

        // RGBX: X is ignored, Alpha forced to 0xFF
        byte[] bytes = new byte[]{
                (byte) 255, (byte) 128, 0, 0 // R=255, G=128, B=0, X=0
        };
        renderer.onFrameAvailable(new WaylandChannel.FrameData(
                1, 1, 4, WaylandChannel.FrameData.FORMAT_RGBX_8888, bytes, 1, 0));
        scheduler.triggerVsync();

        int[] pixels = presenter.presentations.get(0).pixels;
        assertEquals(0xFFFF8000, pixels[0]);
    }

    @Test
    public void pixelFormatConversionBgra8888() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        renderer.setViewportSize(100, 100);

        // BGRA: B=255, G=0, R=0, A=255 -> 0xFF0000FF (Blue in ARGB)
        byte[] bytes = new byte[]{
                (byte) 255, 0, 0, (byte) 255
        };
        renderer.onFrameAvailable(new WaylandChannel.FrameData(
                1, 1, 4, WaylandChannel.FrameData.FORMAT_BGRA_8888, bytes, 1, 0));
        scheduler.triggerVsync();

        int[] pixels = presenter.presentations.get(0).pixels;
        assertEquals(0xFF0000FF, pixels[0]);
    }

    @Test
    public void pixelFormatConversionBgrx8888() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        renderer.setViewportSize(100, 100);

        // BGRX: B=0, G=255, R=255, X=12 -> ARGB Yellow (0xFFFFFF00)
        byte[] bytes = new byte[]{
                0, (byte) 255, (byte) 255, 12
        };
        renderer.onFrameAvailable(new WaylandChannel.FrameData(
                1, 1, 4, WaylandChannel.FrameData.FORMAT_BGRX_8888, bytes, 1, 0));
        scheduler.triggerVsync();

        int[] pixels = presenter.presentations.get(0).pixels;
        assertEquals(0xFFFFFF00, pixels[0]);
    }

    @Test
    public void handlesStrideGreaterThanWidthTimesFour() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        renderer.setViewportSize(100, 100);

        // 1x2 image, but stride is 8 bytes (4 bytes padding per line)
        byte[] bytes = new byte[]{
                (byte) 255, 0, 0, (byte) 255, 99, 99, 99, 99, // row 0 + padding
                0, 0, (byte) 255, (byte) 255, 88, 88, 88, 88  // row 1 + padding
        };

        renderer.onFrameAvailable(new WaylandChannel.FrameData(
                1, 2, 8, WaylandChannel.FrameData.FORMAT_RGBA_8888, bytes, 1, 0));
        scheduler.triggerVsync();

        int[] pixels = presenter.presentations.get(0).pixels;
        assertEquals(0xFFFF0000, pixels[0]);
        assertEquals(0xFF0000FF, pixels[1]);
    }

    @Test
    public void memoryBoundedOver1000Frames() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        renderer.setViewportSize(1920, 1080);

        byte[] frameBytes = new byte[64 * 64 * 4];

        // Push and render 1000 frames
        for (int i = 0; i < 1000; i++) {
            renderer.onFrameAvailable(new WaylandChannel.FrameData(
                    64, 64, 64 * 4, WaylandChannel.FrameData.FORMAT_RGBA_8888, frameBytes, i, i * 16_666_666L));
            scheduler.triggerVsync();
        }

        assertEquals(1000, renderer.getRenderedFrameCount());
        assertEquals(0, renderer.getDroppedFrameCount());
        assertEquals(1000, presenter.presentations.size());
    }

    @Test
    public void pausedAndReleasedRenderersRejectLateFramesAndPresentationCallbacks() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);
        int[] confirmations = {0};
        renderer.setPresentationObserver(frame -> confirmations[0]++);
        WaylandChannel.FrameData frame = createFrame(2, 2, 1, new byte[16], 1);
        renderer.onFrameAvailable(frame);
        Runnable staleVsync = scheduler.registeredCallback;
        renderer.setActive(false);
        renderer.onFrameAvailable(frame);
        staleVsync.run();
        assertFalse(renderer.hasNewFrame());
        assertEquals(0, presenter.presentations.size());
        assertEquals(0, confirmations[0]);
        renderer.setActive(true);
        renderer.onFrameAvailable(frame);
        scheduler.triggerVsync();
        assertEquals(1, presenter.presentations.size());
        assertEquals(1, confirmations[0]);
        renderer.release();
        renderer.setActive(true);
        renderer.onFrameAvailable(frame);
        renderer.tickVsync();
        assertFalse(renderer.hasNewFrame());
        assertEquals(1, presenter.presentations.size());
        assertEquals(1, confirmations[0]);
        renderer.release();
        assertEquals(1, presenter.releaseCount);
    }

    @Test
    public void aspectRatioPreservedInPresentedViewportMapping() {
        TestPresenter presenter = new TestPresenter();
        TestVsyncScheduler scheduler = new TestVsyncScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, scheduler);

        // Viewport is 2400x1080 (20:9)
        renderer.setViewportSize(2400, 1080);

        // Content is 1920x1080 (16:9)
        byte[] bytes = new byte[1920 * 1080 * 4];
        renderer.onFrameAvailable(new WaylandChannel.FrameData(
                1920, 1080, 1920 * 4, WaylandChannel.FrameData.FORMAT_RGBA_8888, bytes, 1, 0));
        scheduler.triggerVsync();

        assertEquals(1, presenter.presentations.size());
        ViewportAspectCalculator.ViewportMapping mapping = presenter.presentations.get(0).mapping;
        assertNotNull(mapping);
        assertEquals(240, mapping.destX());
        assertEquals(0, mapping.destY());
        assertEquals(1920, mapping.destWidth());
        assertEquals(1080, mapping.destHeight());
        assertTrue(mapping.isPillarbox());
    }
}
