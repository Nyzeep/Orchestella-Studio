package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class DoubleBufferedRendererContentionTest {

    private static final class SlowRgbaPresenter implements DoubleBufferedRenderer.SurfacePresenter {
        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "slow-egl-test");
            t.setDaemon(true);
            return t;
        });
        final List<Integer> presentedFirstReds = new ArrayList<>();
        final AtomicInteger rgbaCalls = new AtomicInteger();
        final AtomicInteger argbCalls = new AtomicInteger();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        volatile long delayMs = 50L;
        volatile boolean ready = true;

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public boolean submitPresentation(Runnable task) {
            worker.execute(task);
            return true;
        }

        @Override
        public void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
            argbCalls.incrementAndGet();
        }

        @Override
        public void presentRgba(
                ByteBuffer rgbaPixels,
                int width,
                int height,
                int strideBytes,
                ViewportAspectCalculator.ViewportMapping mapping) {
            int callIndex = rgbaCalls.incrementAndGet();
            if (callIndex == 1) {
                firstEntered.countDown();
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            synchronized (presentedFirstReds) {
                presentedFirstReds.add(rgbaPixels.get(rgbaPixels.position()) & 0xFF);
            }
        }

        @Override
        public void clear(int color) {}

        @Override
        public void release() {
            ready = false;
            worker.shutdownNow();
        }
    }

    private static final class LegacyArgbOnlyPresenter implements DoubleBufferedRenderer.SurfacePresenter {
        int[] lastPixels;
        int presentCount;

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
            presentCount++;
            lastPixels = pixels.clone();
        }

        @Override
        public void clear(int color) {}

        @Override
        public void release() {}
    }

    @Test
    public void highFrequencyProducerNeverBlocksOnSlowPresenterAndDeliversLatestFrame() throws Exception {
        SlowRgbaPresenter presenter = new SlowRgbaPresenter();
        ExecutorService vsyncThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vsync-test");
            t.setDaemon(true);
            return t;
        });
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, vsyncThread::execute);
        renderer.setViewportSize(1080, 2400);

        CountDownLatch latestPresented = new CountDownLatch(1);
        renderer.setPresentationObserver(frame -> {
            if (frame.frameId() == 200L) {
                latestPresented.countDown();
            }
        });

        try {
            // Trigger frame 1 first so the slow presenter enters its 50ms sleep.
            renderer.onFrameAvailable(rgbaFrame(1, 2, 2, 8, (byte) 1));
            assertTrue(presenter.firstEntered.await(2, TimeUnit.SECONDS));

            long startNs = System.nanoTime();
            for (int i = 2; i <= 200; i++) {
                renderer.onFrameAvailable(rgbaFrame(i, 2, 2, 8, (byte) i));
            }
            long producerElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

            assertTrue("onFrameAvailable for 199 frames must stay lock-free (< 20ms), took "
                    + producerElapsedMs + "ms", producerElapsedMs < 20);
            assertTrue("latest frame #200 must eventually present",
                    latestPresented.await(5, TimeUnit.SECONDS));

            assertEquals(0, presenter.argbCalls.get());
            assertTrue("intermediate stale frames must be dropped", renderer.getDroppedFrameCount() > 0);
            assertTrue("slow presenter must skip intermediate frames",
                    renderer.getRenderedFrameCount() < 200);
            synchronized (presenter.presentedFirstReds) {
                int lastRed = presenter.presentedFirstReds.get(presenter.presentedFirstReds.size() - 1);
                assertEquals(200, lastRed);
            }
        } finally {
            renderer.release();
            vsyncThread.shutdownNow();
        }
    }

    @Test
    public void directRgbaSkipsArgbConversionWhileLegacyPresenterBridgeStillWorks() {
        List<byte[]> receivedRgba = new ArrayList<>();
        List<Integer> receivedStrides = new ArrayList<>();
        AtomicInteger directArgbCalls = new AtomicInteger();
        DoubleBufferedRenderer.SurfacePresenter syncRgbaPresenter = new DoubleBufferedRenderer.SurfacePresenter() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
                directArgbCalls.incrementAndGet();
            }

            @Override
            public void presentRgba(
                    ByteBuffer rgbaPixels,
                    int width,
                    int height,
                    int strideBytes,
                    ViewportAspectCalculator.ViewportMapping mapping) {
                byte[] copy = new byte[strideBytes * height];
                rgbaPixels.slice().get(copy);
                receivedRgba.add(copy);
                receivedStrides.add(strideBytes);
            }

            @Override
            public void clear(int color) {}

            @Override
            public void release() {}
        };
        DoubleBufferedRenderer directRenderer = new DoubleBufferedRenderer(syncRgbaPresenter, Runnable::run);
        directRenderer.setViewportSize(100, 100);

        byte[] paddedRgba = new byte[] {
                (byte) 255, 10, 20, (byte) 255, 99, 99, 99, 99,
                30, (byte) 255, 40, (byte) 128, 88, 88, 88, 88
        };
        DisplayChannel.FrameData frame = DisplayChannel.FrameData.fromOwned(
                1, 2, 8, DisplayChannel.FrameData.FORMAT_RGBA_8888, paddedRgba, 1L, 100L);
        directRenderer.onFrameAvailable(frame);

        assertEquals(1, receivedRgba.size());
        assertEquals(0, directArgbCalls.get());
        assertEquals(Integer.valueOf(8), receivedStrides.get(0));
        assertArrayEquals(paddedRgba, receivedRgba.get(0));

        LegacyArgbOnlyPresenter legacyPresenter = new LegacyArgbOnlyPresenter();
        DoubleBufferedRenderer legacyRenderer = new DoubleBufferedRenderer(legacyPresenter, Runnable::run);
        legacyRenderer.setViewportSize(100, 100);
        legacyRenderer.onFrameAvailable(frame);

        assertEquals(1, legacyPresenter.presentCount);
        assertArrayEquals(new int[] {0xFFFF0A14, 0x801EFF28}, legacyPresenter.lastPixels);

        directRenderer.release();
        legacyRenderer.release();
    }

    private static DisplayChannel.FrameData rgbaFrame(long id, int width, int height, int stride, byte red) {
        byte[] bytes = new byte[stride * height];
        bytes[0] = red;
        bytes[1] = 20;
        bytes[2] = 30;
        bytes[3] = (byte) 255;
        return DisplayChannel.FrameData.fromOwned(
                width, height, stride, DisplayChannel.FrameData.FORMAT_RGBA_8888, bytes, id, id * 1000L);
    }
}
