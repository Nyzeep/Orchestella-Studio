package io.github.nyzeep.orchestella;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.view.Choreographer;
import android.view.SurfaceHolder;
import io.github.nyzeep.orchestella.runtime.contract.DisplayControl;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.util.Locale;
import java.util.Objects;

/**
 * Controller and presentation coordinator decoupling Android view lifecycle, SurfaceHolder state,
 * and display mode renegotiation from the underlying Wayland graphics channel and hardware renderer.
 *
 * <p>Conforms to ADR-0017 and Ticket 39.
 */
public final class WaylandSurfaceController {

    public enum SurfaceState {
        UNINITIALIZED,
        CREATED,
        CHANGED,
        DESTROYED
    }

    public enum LifecycleState {
        UNINITIALIZED,
        RESUMED,
        PAUSED,
        STOPPED,
        DESTROYED
    }

    /**
     * Snapshot container for head-up display (HUD) badge metrics.
     */
    public static final class HudBadgeInfo {
        private final int fps;
        private final WaylandChannel.ConnectionState connectionState;
        private final int viewportWidth;
        private final int viewportHeight;
        private final int contentWidth;
        private final int contentHeight;
        private final long renderedFrames;
        private final long droppedFrames;
        private final double scale;

        public HudBadgeInfo(
                int fps,
                WaylandChannel.ConnectionState connectionState,
                int viewportWidth,
                int viewportHeight,
                int contentWidth,
                int contentHeight,
                long renderedFrames,
                long droppedFrames) {
            this(fps, connectionState, viewportWidth, viewportHeight, contentWidth, contentHeight, renderedFrames, droppedFrames, 1.0);
        }

        public HudBadgeInfo(
                int fps,
                WaylandChannel.ConnectionState connectionState,
                int viewportWidth,
                int viewportHeight,
                int contentWidth,
                int contentHeight,
                long renderedFrames,
                long droppedFrames,
                double scale) {
            this.fps = fps;
            this.connectionState = connectionState;
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
            this.renderedFrames = renderedFrames;
            this.droppedFrames = droppedFrames;
            this.scale = scale > 0.0 ? scale : 1.0;
        }

        public double scale() {
            return scale;
        }

        public int fps() {
            return fps;
        }

        public WaylandChannel.ConnectionState connectionState() {
            return connectionState;
        }

        public int viewportWidth() {
            return viewportWidth;
        }

        public int viewportHeight() {
            return viewportHeight;
        }

        public int contentWidth() {
            return contentWidth;
        }

        public int contentHeight() {
            return contentHeight;
        }

        public long renderedFrames() {
            return renderedFrames;
        }

        public long droppedFrames() {
            return droppedFrames;
        }

        public String toBadgeText() {
            return String.format(
                    Locale.ROOT,
                    "Wayland [%s] | %d FPS | %dx%d -> %dx%d | R:%d D:%d",
                    connectionState,
                    fps,
                    contentWidth > 0 ? contentWidth : viewportWidth,
                    contentHeight > 0 ? contentHeight : viewportHeight,
                    viewportWidth,
                    viewportHeight,
                    renderedFrames,
                    droppedFrames);
        }

        @Override
        public String toString() {
            return toBadgeText();
        }
    }

    /**
     * Default Android Surface presentation pipeline using hardware-accelerated Canvas.
     */
    public static final class HardwareCanvasSurfacePresenter implements DoubleBufferedRenderer.SurfacePresenter {
        private final SurfaceHolder holder;
        private Bitmap bitmap = null;
        private final Paint paint = new Paint();
        private boolean ready = false;

        public HardwareCanvasSurfacePresenter(SurfaceHolder holder) {
            this.holder = holder;
            this.paint.setFilterBitmap(true);
        }

        @Override
        public boolean isReady() {
            return ready && holder != null && holder.getSurface() != null && holder.getSurface().isValid();
        }

        @Override
        public void setReady(boolean ready) {
            this.ready = ready;
        }

        @Override
        public void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
            if (holder == null || !holder.getSurface().isValid()) {
                return;
            }
            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);

            Canvas canvas = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    canvas = holder.lockHardwareCanvas();
                } else {
                    canvas = holder.lockCanvas();
                }
                if (canvas != null) {
                    canvas.drawColor(Color.BLACK);
                    Rect src = new Rect(0, 0, width, height);
                    Rect dst = new Rect(
                            mapping.destX(),
                            mapping.destY(),
                            mapping.destX() + mapping.destWidth(),
                            mapping.destY() + mapping.destHeight());
                    canvas.drawBitmap(bitmap, src, dst, paint);
                }
            } catch (Exception ignored) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        @Override
        public void clear(int color) {
            if (holder == null || !holder.getSurface().isValid()) {
                return;
            }
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    canvas.drawColor(color);
                }
            } catch (Exception ignored) {
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        @Override
        public void release() {
            ready = false;
            if (bitmap != null) {
                bitmap.recycle();
                bitmap = null;
            }
        }
    }

    /**
     * Default Android VSYNC scheduler delegating to Choreographer frame callbacks.
     */
    public static final class ChoreographerVsyncScheduler implements DoubleBufferedRenderer.VsyncScheduler {
        private Choreographer.FrameCallback currentCallback = null;
        private boolean isScheduled = false;

        @Override
        public void requestVsync(Runnable callback) {
            if (isScheduled) {
                return;
            }
            isScheduled = true;
            currentCallback = frameTimeNanos -> {
                isScheduled = false;
                callback.run();
            };
            try {
                Choreographer.getInstance().postFrameCallback(currentCallback);
            } catch (Exception fallback) {
                isScheduled = false;
                callback.run();
            }
        }

        @Override
        public void cancel() {
            if (isScheduled && currentCallback != null) {
                try {
                    Choreographer.getInstance().removeFrameCallback(currentCallback);
                } catch (Exception ignored) {
                }
                isScheduled = false;
                currentCallback = null;
            }
        }
    }

    private final DoubleBufferedRenderer renderer;
    private final DisplayResizeDebounce resizeDebounce;

    private SurfaceState surfaceState = SurfaceState.UNINITIALIZED;
    private LifecycleState lifecycleState = LifecycleState.RESUMED;

    private DisplayControl displayControl = null;
    private WaylandChannel waylandChannel = null;
    private final WaylandChannel.StateListener channelStateListener;
    private WaylandChannel.ConnectionState connectionState = WaylandChannel.ConnectionState.DISCONNECTED;
    private boolean isBound = false;
    private double targetScale = 1.0;

    public WaylandSurfaceController(
            DoubleBufferedRenderer renderer,
            DisplayResizeDebounce.TaskScheduler scheduler) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.channelStateListener = (state, msg) -> this.connectionState = state;
        this.resizeDebounce = new DisplayResizeDebounce(
                (w, h) -> {
                    if (displayControl != null) {
                        return displayControl.setMode(w, h, targetScale);
                    }
                    return false;
                },
                DisplayResizeDebounce.DEFAULT_DEBOUNCE_MS,
                scheduler != null ? scheduler : (delay, task) -> () -> {}
        );
    }

    public WaylandSurfaceController(
            DoubleBufferedRenderer renderer,
            DisplayResizeDebounce resizeDebounce) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.channelStateListener = (state, msg) -> this.connectionState = state;
        this.resizeDebounce = Objects.requireNonNull(resizeDebounce, "resizeDebounce");
    }

    public WaylandSurfaceController(SurfaceHolder holder) {
        this.renderer = new DoubleBufferedRenderer(
                new NativeGlesSurfacePresenter(holder),
                new ChoreographerVsyncScheduler()
        );
        this.channelStateListener = (state, msg) -> this.connectionState = state;
        this.resizeDebounce = new DisplayResizeDebounce(
                (w, h) -> {
                    if (displayControl != null) {
                        return displayControl.setMode(w, h, targetScale);
                    }
                    return false;
                }
        );
    }

    public synchronized void setTargetScale(double scale) {
        this.targetScale = scale;
        if (displayControl != null) {
            displayControl.updateScale(scale);
        }
    }

    public synchronized double getTargetScale() {
        return targetScale;
    }

    /**
     * Binds the Wayland channel and display control contract to this presentation controller.
     */
    public synchronized void bind(DisplayControl displayControl, WaylandChannel waylandChannel) {
        if (isBound) {
            unbind();
        }

        this.displayControl = displayControl;
        this.waylandChannel = waylandChannel;

        if (displayControl != null && targetScale != 1.0) {
            displayControl.updateScale(targetScale);
        }

        if (waylandChannel != null) {
            waylandChannel.registerFrameListener(renderer);
            waylandChannel.addStateListener(channelStateListener);
            this.connectionState = waylandChannel.state();
        }

        this.isBound = true;
    }

    /**
     * Unbinds the channel and display control, unregistering all frame and state observers.
     */
    public synchronized void unbind() {
        if (waylandChannel != null) {
            waylandChannel.unregisterFrameListener(renderer);
            waylandChannel.removeStateListener(channelStateListener);
            this.connectionState = WaylandChannel.ConnectionState.DISCONNECTED;
        }

        resizeDebounce.cancel();

        this.displayControl = null;
        this.waylandChannel = null;
        this.isBound = false;
    }

    public synchronized boolean isBound() {
        return isBound;
    }

    public synchronized void onSurfaceCreated(SurfaceHolder holder) {
        this.surfaceState = SurfaceState.CREATED;
        if (renderer.getPresenter() instanceof SurfaceHolder.Callback) {
            ((SurfaceHolder.Callback) renderer.getPresenter()).surfaceCreated(holder);
        }
        renderer.getPresenter().setReady(true);
    }

    public synchronized void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        this.surfaceState = SurfaceState.CHANGED;
        if (renderer.getPresenter() instanceof SurfaceHolder.Callback) {
            ((SurfaceHolder.Callback) renderer.getPresenter()).surfaceChanged(holder, format, width, height);
        }
        renderer.setViewportSize(width, height);
        resizeDebounce.requestResize(width, height);
    }

    public synchronized void onSurfaceDestroyed(SurfaceHolder holder) {
        this.surfaceState = SurfaceState.DESTROYED;
        if (renderer.getPresenter() instanceof SurfaceHolder.Callback) {
            ((SurfaceHolder.Callback) renderer.getPresenter()).surfaceDestroyed(holder);
        }
        renderer.getPresenter().setReady(false);
        resizeDebounce.cancel();
        renderer.reset();
    }

    public synchronized void onResume() {
        this.lifecycleState = LifecycleState.RESUMED;
    }

    public synchronized void onPause() {
        this.lifecycleState = LifecycleState.PAUSED;
        renderer.reset();
    }

    public synchronized void onStop() {
        this.lifecycleState = LifecycleState.STOPPED;
    }

    public synchronized void onDestroy() {
        this.lifecycleState = LifecycleState.DESTROYED;
        unbind();
        renderer.release();
    }

    public synchronized boolean isRenderingActive() {
        return lifecycleState == LifecycleState.RESUMED
                && (surfaceState == SurfaceState.CREATED || surfaceState == SurfaceState.CHANGED);
    }

    public synchronized SurfaceState getSurfaceState() {
        return surfaceState;
    }

    public synchronized LifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public synchronized DoubleBufferedRenderer getRenderer() {
        return renderer;
    }

    public synchronized DisplayResizeDebounce getResizeDebounce() {
        return resizeDebounce;
    }

    public synchronized DisplayControl getDisplayControl() {
        return displayControl;
    }

    public synchronized WaylandChannel getWaylandChannel() {
        return waylandChannel;
    }

    public synchronized HudBadgeInfo getHudBadgeInfo() {
        WaylandChannel.FrameData front = renderer.getFrontBuffer();
        int contentWidth = front != null ? front.width() : 0;
        int contentHeight = front != null ? front.height() : 0;

        return new HudBadgeInfo(
                renderer.getFps(),
                connectionState,
                renderer.getViewportWidth(),
                renderer.getViewportHeight(),
                contentWidth,
                contentHeight,
                renderer.getRenderedFrameCount(),
                renderer.getDroppedFrameCount(),
                targetScale
        );
    }
}
