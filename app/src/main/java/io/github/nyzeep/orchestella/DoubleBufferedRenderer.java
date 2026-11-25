package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lock-free latest-frame double-buffered graphics presentation scheduler for display frame data.
 *
 * <p>Conforms to ADR-0017 and Ticket 39:
 * <ul>
 *   <li>Lock-free O(1) {@link #onFrameAvailable(DisplayChannel.FrameData)} latest-frame exchange so
 *       the RFB reader thread is never blocked by pixel unpacking or GLES upload;</li>
 *   <li>Zero-clone RGBA pass-through via {@link SurfacePresenter#presentRgba} when the channel
 *       delivers {@code FORMAT_RGBA_8888}, with automatic fallback bridge for legacy presenters;</li>
 *   <li>Maintains aspect-ratio-preserving viewport geometry (letterboxing / pillarboxing);</li>
 *   <li>Synchronized with VSYNC and single-flight presentation scheduling so slow consumers drop
 *       stale intermediate frames without queuing backlogs.</li>
 * </ul>
 */
public class DoubleBufferedRenderer implements DisplayChannel.FrameListener {

    /**
     * Strategy-neutral seam for actual presentation to the underlying surface (Hardware Canvas / GLES).
     */
    public interface SurfacePresenter {
        boolean isReady();
        default void setReady(boolean ready) {}
        void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping);

        /**
         * Presents a read-only RGBA_8888 buffer directly to the underlying surface without an
         * intermediate {@code int[]} conversion. Legacy presenters inherit a bridge to
         * {@link #present(int[], int, int, ViewportAspectCalculator.ViewportMapping)}.
         */
        default void presentRgba(
                ByteBuffer rgbaPixels,
                int width,
                int height,
                int strideBytes,
                ViewportAspectCalculator.ViewportMapping mapping) {
            if (rgbaPixels == null || mapping == null || width <= 0 || height <= 0
                    || strideBytes < width * 4) {
                return;
            }
            ByteBuffer ro = rgbaPixels.slice();
            if (ro.remaining() < strideBytes * height) {
                return;
            }
            int[] argb = new int[width * height];
            for (int y = 0; y < height; y++) {
                int rowBase = y * strideBytes;
                int destBase = y * width;
                for (int x = 0; x < width; x++) {
                    int off = rowBase + (x * 4);
                    int r = ro.get(off) & 0xFF;
                    int g = ro.get(off + 1) & 0xFF;
                    int b = ro.get(off + 2) & 0xFF;
                    int a = ro.get(off + 3) & 0xFF;
                    argb[destBase + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            present(argb, width, height, mapping);
        }

        /**
         * Dispatches a frame presentation task on the presenter's owning execution context
         * (e.g., dedicated single-thread EGL worker). Synchronous presenters run inline.
         */
        default boolean submitPresentation(Runnable task) {
            task.run();
            return true;
        }

        default boolean lastPresentationSucceeded() { return isReady(); }
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
    private final Object consumerLock = new Object();

    private final AtomicInteger viewportWidth = new AtomicInteger(1920);
    private final AtomicInteger viewportHeight = new AtomicInteger(1080);

    private final AtomicReference<DisplayChannel.FrameData> pendingFrame = new AtomicReference<>();
    private final AtomicReference<DisplayChannel.FrameData> frontBuffer = new AtomicReference<>();

    private final AtomicBoolean vsyncRequested = new AtomicBoolean(false);
    private final AtomicBoolean presentInFlight = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicBoolean released = new AtomicBoolean(false);

    // Consumer-only reusable int[] buffer for non-RGBA formats; guarded exclusively by consumerLock.
    private int[] pixelBuffer = null;
    private int pixelBufferCapacity = 0;

    private final AtomicInteger renderedFramesCount = new AtomicInteger(0);
    private final AtomicInteger droppedFramesCount = new AtomicInteger(0);
    private final AtomicLong lastFrameTimestampNs = new AtomicLong(0);
    private final AtomicInteger currentFps = new AtomicInteger(0);

    // Consumer-only FPS window tracking; guarded exclusively by consumerLock.
    private long fpsWindowStartNs = 0;
    private int fpsFrameCount = 0;

    private volatile java.util.function.Consumer<DisplayChannel.FrameData> presentationObserver;

    public void setPresentationObserver(java.util.function.Consumer<DisplayChannel.FrameData> observer) {
        presentationObserver = observer;
    }

    public DoubleBufferedRenderer(SurfacePresenter presenter, VsyncScheduler vsyncScheduler) {
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.vsyncScheduler = Objects.requireNonNull(vsyncScheduler, "vsyncScheduler");
    }

    public DoubleBufferedRenderer(SurfacePresenter presenter) {
        this(presenter, Runnable::run);
    }

    public SurfacePresenter getPresenter() {
        return presenter;
    }

    public void setViewportSize(int width, int height) {
        if (width > 0 && height > 0) {
            viewportWidth.set(width);
            viewportHeight.set(height);
        }
    }

    public int getViewportWidth() {
        return viewportWidth.get();
    }

    public int getViewportHeight() {
        return viewportHeight.get();
    }

    /**
     * Lock-free O(1) producer callback invoked when a new frame arrives from the display channel.
     * Atomically exchanges the latest pending slot; any overwritten unconsumed frame is counted as dropped.
     */
    @Override
    public void onFrameAvailable(DisplayChannel.FrameData frame) {
        if (frame == null || !active.get() || released.get()) {
            return;
        }

        DisplayChannel.FrameData overwritten = pendingFrame.getAndSet(frame);
        if (overwritten != null) {
            droppedFramesCount.incrementAndGet();
        }

        if (!active.get() || released.get()) {
            pendingFrame.set(null);
            return;
        }

        if (vsyncRequested.compareAndSet(false, true)) {
            vsyncScheduler.requestVsync(this::tickVsync);
        }
    }

    /**
     * Consumer callback invoked on VSYNC tick. Pops the latest pending frame without blocking the
     * producer and dispatches upload/swap onto the presenter's owning execution context.
     */
    public void tickVsync() {
        vsyncRequested.set(false);

        if (!active.get() || released.get()) {
            pendingFrame.set(null);
            return;
        }

        if (!presentInFlight.compareAndSet(false, true)) {
            return;
        }

        DisplayChannel.FrameData frame = pendingFrame.getAndSet(null);
        if (frame == null) {
            presentInFlight.set(false);
            schedulePendingIfNeeded();
            return;
        }

        Runnable task = () -> {
            try {
                presentConsumedFrame(frame);
            } finally {
                presentInFlight.set(false);
                schedulePendingIfNeeded();
            }
        };

        boolean submitted = false;
        try {
            submitted = presenter.submitPresentation(task);
        } finally {
            if (!submitted) {
                presentInFlight.set(false);
            }
        }
    }

    private void schedulePendingIfNeeded() {
        if (active.get() && !released.get()
                && pendingFrame.get() != null
                && vsyncRequested.compareAndSet(false, true)) {
            vsyncScheduler.requestVsync(this::tickVsync);
        }
    }

    private void presentConsumedFrame(DisplayChannel.FrameData frame) {
        synchronized (consumerLock) {
            if (!active.get() || released.get()) {
                return;
            }

            frontBuffer.set(frame);

            if (!presenter.isReady()) {
                return;
            }

            int width = frame.width();
            int height = frame.height();
            int vw = viewportWidth.get();
            int vh = viewportHeight.get();
            ViewportAspectCalculator.ViewportMapping mapping =
                    ViewportAspectCalculator.calculate(width, height, vw, vh);

            if (frame.format() == DisplayChannel.FrameData.FORMAT_RGBA_8888) {
                presenter.presentRgba(frame.asReadOnlyBuffer(), width, height, frame.stride(), mapping);
            } else {
                int requiredSize = width * height;
                if (pixelBuffer == null || pixelBufferCapacity < requiredSize) {
                    pixelBuffer = new int[requiredSize];
                    pixelBufferCapacity = requiredSize;
                }
                frame.unpackArgbInto(pixelBuffer);
                presenter.present(pixelBuffer, width, height, mapping);
            }

            if (!presenter.lastPresentationSucceeded()) {
                droppedFramesCount.incrementAndGet();
                return;
            }

            renderedFramesCount.incrementAndGet();
            long nowNs = System.nanoTime();
            lastFrameTimestampNs.set(nowNs);
            updateFpsMetrics(nowNs);

            java.util.function.Consumer<DisplayChannel.FrameData> observer = presentationObserver;
            if (observer != null && active.get() && !released.get()) {
                observer.accept(frame);
            }
        }
    }

    private void updateFpsMetrics(long nowNs) {
        if (fpsWindowStartNs == 0) {
            fpsWindowStartNs = nowNs;
            fpsFrameCount = 1;
            return;
        }

        fpsFrameCount++;
        long elapsedNs = nowNs - fpsWindowStartNs;
        if (elapsedNs >= 1_000_000_000L) {
            currentFps.set((int) Math.round((fpsFrameCount * 1_000_000_000.0) / elapsedNs));
            fpsFrameCount = 0;
            fpsWindowStartNs = nowNs;
        }
    }

    public int getRenderedFrameCount() {
        return renderedFramesCount.get();
    }

    public int getDroppedFrameCount() {
        return droppedFramesCount.get();
    }

    public int getFps() {
        return currentFps.get();
    }

    public long getLastFrameTimestampNs() {
        return lastFrameTimestampNs.get();
    }

    public boolean hasNewFrame() {
        return pendingFrame.get() != null;
    }

    public DisplayChannel.FrameData getFrontBuffer() {
        return frontBuffer.get();
    }

    public DisplayChannel.FrameData getBackBuffer() {
        return pendingFrame.get();
    }

    /** Pausing rejects producer callbacks until the controller explicitly resumes presentation. */
    public void setActive(boolean isActive) {
        if (released.get()) {
            return;
        }
        active.set(isActive);
        if (!isActive) {
            reset();
        }
    }

    public void reset() {
        vsyncScheduler.cancel();
        vsyncRequested.set(false);
        pendingFrame.set(null);
        frontBuffer.set(null);
    }

    public void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        active.set(false);
        presentationObserver = null;
        reset();
        synchronized (consumerLock) {
            pixelBuffer = null;
            pixelBufferCapacity = 0;
        }
        presenter.release();
    }
}
