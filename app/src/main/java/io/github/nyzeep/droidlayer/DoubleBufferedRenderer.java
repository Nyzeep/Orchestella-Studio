package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;
import java.util.Objects;

/**
 * High-performance double-buffered graphics presentation engine for Wayland frame data.
 *
 * <p>Conforms to ADR-0017 and Ticket 39:
 * <ul>
 *   <li>Ping-pong front/back buffer swapping to eliminate rendering tearing and contention;</li>
 *   <li>Unpacks RGBA_8888, RGBX_8888, BGRA_8888, and BGRX_8888 pixel formats with stride support;</li>
 *   <li>Maintains aspect-ratio-preserving viewport geometry (letterboxing / pillarboxing);</li>
 *   <li>Synchronized with VSYNC; idle scenes skip rendering to maintain near-zero CPU usage;</li>
 *   <li>Drops stale intermediate frames if production outpaces presentation;</li>
 *   <li>Bounded reusable pixel buffers to prevent memory leaks and GC pressure.</li>
 * </ul>
 */
public class DoubleBufferedRenderer implements WaylandChannel.FrameListener {

    /**
     * Strategy-neutral seam for actual presentation to the underlying surface (Hardware Canvas / GLES).
     */
    public interface SurfacePresenter {
        boolean isReady();
        default void setReady(boolean ready) {}
        void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping);
        void clear(int color);
        void release();
    }

    /**
     * Strategy-neutral seam for vertical synchronization (VSYNC) clock callbacks.
     */
    @FunctionalInterface
    public interface VsyncScheduler {
        void requestVsync(Runnable callback);
        default void cancel() {}
    }

    private final SurfacePresenter presenter;
    private final VsyncScheduler vsyncScheduler;

    private int viewportWidth = 1920;
    private int viewportHeight = 1080;

    // Buffer slots for double-buffering ping-pong
    private WaylandChannel.FrameData backBuffer = null;
    private WaylandChannel.FrameData frontBuffer = null;
    private boolean hasNewFrame = false;

    // Pre-allocated reusable int[] pixel buffers to avoid GC allocations during 60 FPS streaming
    private int[] pixelBuffer = null;
    private int pixelBufferCapacity = 0;

    // Frame metrics & accounting
    private int renderedFramesCount = 0;
    private int droppedFramesCount = 0;
    private long lastFrameTimestampNs = 0;

    // Rolling FPS tracking
    private long fpsWindowStartNs = 0;
    private int fpsFrameCount = 0;
    private int currentFps = 0;

    private boolean isVsyncRequested = false;

    public DoubleBufferedRenderer(SurfacePresenter presenter, VsyncScheduler vsyncScheduler) {
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.vsyncScheduler = Objects.requireNonNull(vsyncScheduler, "vsyncScheduler");
    }

    public DoubleBufferedRenderer(SurfacePresenter presenter) {
        this(presenter, callback -> callback.run());
    }

    public SurfacePresenter getPresenter() {
        return presenter;
    }

    public synchronized void setViewportSize(int width, int height) {
        if (width > 0 && height > 0) {
            this.viewportWidth = width;
            this.viewportHeight = height;
        }
    }

    public synchronized int getViewportWidth() {
        return viewportWidth;
    }

    public synchronized int getViewportHeight() {
        return viewportHeight;
    }

    /**
     * Producer callback invoked when a new frame is decoded from the Wayland channel.
     * Enqueues into back-buffer; if previous back-buffer was unconsumed, discards it to prevent latency.
     */
    @Override
    public synchronized void onFrameAvailable(WaylandChannel.FrameData frame) {
        if (frame == null) {
            return;
        }

        if (hasNewFrame) {
            // Producer is faster than VSYNC presentation: drop stale intermediate frame
            droppedFramesCount++;
        }

        this.backBuffer = frame;
        this.hasNewFrame = true;

        if (!isVsyncRequested) {
            isVsyncRequested = true;
            vsyncScheduler.requestVsync(this::tickVsync);
        }
    }

    /**
     * Consumer callback invoked on VSYNC tick. Consumes latest back-buffer, performs format conversion,
     * calculates viewport mapping, and presents to the hardware surface.
     */
    public synchronized void tickVsync() {
        isVsyncRequested = false;

        if (!hasNewFrame || backBuffer == null) {
            // Static scene: zero redraw, saving CPU and GPU power
            return;
        }

        // Swap ping-pong buffers
        frontBuffer = backBuffer;
        backBuffer = null;
        hasNewFrame = false;

        if (!presenter.isReady()) {
            return;
        }

        int width = frontBuffer.width();
        int height = frontBuffer.height();
        int requiredSize = width * height;

        if (pixelBuffer == null || pixelBufferCapacity < requiredSize) {
            pixelBuffer = new int[requiredSize];
            pixelBufferCapacity = requiredSize;
        }

        unpackFrameData(frontBuffer, pixelBuffer);

        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(width, height, viewportWidth, viewportHeight);

        presenter.present(pixelBuffer, width, height, mapping);

        renderedFramesCount++;
        lastFrameTimestampNs = System.nanoTime();

        updateFpsMetrics(lastFrameTimestampNs);
    }

    private void updateFpsMetrics(long nowNs) {
        if (fpsWindowStartNs == 0) {
            fpsWindowStartNs = nowNs;
            fpsFrameCount = 1;
            return;
        }

        fpsFrameCount++;
        long elapsedNs = nowNs - fpsWindowStartNs;
        if (elapsedNs >= 1_000_000_000L) { // 1 second rolling window
            currentFps = (int) Math.round((fpsFrameCount * 1_000_000_000.0) / elapsedNs);
            fpsFrameCount = 0;
            fpsWindowStartNs = nowNs;
        }
    }

    /**
     * Unpacks raw FrameData byte array into 32-bit packed ARGB integers (0xAARRGGBB).
     */
    private static void unpackFrameData(WaylandChannel.FrameData frame, int[] outPixels) {
        int width = frame.width();
        int height = frame.height();
        int stride = frame.stride();
        int format = frame.format();
        byte[] data = frame.data();

        for (int y = 0; y < height; y++) {
            int rowStart = y * stride;
            int destRowStart = y * width;
            for (int x = 0; x < width; x++) {
                int src = rowStart + (x * 4);
                int dest = destRowStart + x;

                int b0 = data[src] & 0xFF;
                int b1 = data[src + 1] & 0xFF;
                int b2 = data[src + 2] & 0xFF;
                int b3 = data[src + 3] & 0xFF;

                int argb;
                switch (format) {
                    case WaylandChannel.FrameData.FORMAT_RGBA_8888:
                        // b0=R, b1=G, b2=B, b3=A
                        argb = (b3 << 24) | (b0 << 16) | (b1 << 8) | b2;
                        break;
                    case WaylandChannel.FrameData.FORMAT_RGBX_8888:
                        // b0=R, b1=G, b2=B, b3=X (force A=0xFF)
                        argb = (0xFF << 24) | (b0 << 16) | (b1 << 8) | b2;
                        break;
                    case WaylandChannel.FrameData.FORMAT_BGRA_8888:
                        // b0=B, b1=G, b2=R, b3=A
                        argb = (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
                        break;
                    case WaylandChannel.FrameData.FORMAT_BGRX_8888:
                        // b0=B, b1=G, b2=R, b3=X (force A=0xFF)
                        argb = (0xFF << 24) | (b2 << 16) | (b1 << 8) | b0;
                        break;
                    default:
                        argb = (b3 << 24) | (b0 << 16) | (b1 << 8) | b2;
                        break;
                }
                outPixels[dest] = argb;
            }
        }
    }

    public synchronized int getRenderedFrameCount() {
        return renderedFramesCount;
    }

    public synchronized int getDroppedFrameCount() {
        return droppedFramesCount;
    }

    public synchronized int getFps() {
        return currentFps;
    }

    public synchronized long getLastFrameTimestampNs() {
        return lastFrameTimestampNs;
    }

    public synchronized boolean hasNewFrame() {
        return hasNewFrame;
    }

    public synchronized WaylandChannel.FrameData getFrontBuffer() {
        return frontBuffer;
    }

    public synchronized WaylandChannel.FrameData getBackBuffer() {
        return backBuffer;
    }

    public synchronized void reset() {
        vsyncScheduler.cancel();
        isVsyncRequested = false;
        hasNewFrame = false;
        backBuffer = null;
        frontBuffer = null;
    }

    public synchronized void release() {
        reset();
        presenter.release();
        pixelBuffer = null;
        pixelBufferCapacity = 0;
    }
}
