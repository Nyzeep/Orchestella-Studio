package io.github.nyzeep.orchestella;

import android.view.Surface;
import android.view.SurfaceHolder;
import io.github.nyzeep.orchestella.runtime.contract.display.DisplayViewportBudget;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance native OpenGL ES 3.0 presenter using ANativeWindow and dual-PBO ping-pong streaming.
 *
 * <p>Conforms to Ticket 56, ADR-0017, and ADR-0019:
 * <ul>
 *   <li>All EGL/GL lifecycle and presentation calls execute strictly on a single dedicated render
 *       thread ({@code orchestella-gles}) to honor EGL context/surface thread affinity;</li>
 *   <li>Accepts read-only RGBA_8888 buffers directly via {@link #presentRgba} and dual PBO upload,
 *       skipping ARGB integer conversion;</li>
 *   <li>SurfaceHolder callbacks are routed once from {@link WaylandSurfaceView} /
 *       {@link WaylandSurfaceController} rather than registering a duplicate callback.</li>
 * </ul>
 */
public class NativeGlesSurfacePresenter implements DoubleBufferedRenderer.SurfacePresenter, SurfaceHolder.Callback {

    private static volatile boolean isNativeLoaded = false;

    static {
        try {
            System.loadLibrary("orchestella_gles");
            isNativeLoaded = true;
        } catch (Throwable ignored) {
            try {
                System.loadLibrary("droidlayer_gles");
                isNativeLoaded = true;
            } catch (Throwable ignored2) {
                isNativeLoaded = false;
            }
        }
    }

    public static boolean isNativeAvailable() {
        return isNativeLoaded;
    }

    public static String getNativeVersion() {
        if (isNativeLoaded) {
            try {
                return nativeGetVersion();
            } catch (Throwable ignored) {
                // Defensive read: an unloading/uninitialized native layer falls through to
                // the fixed unavailable marker below instead of crashing the caller.
            }
        }
        return "GLES presenter unavailable";
    }

    /** Execution seam pinning all native EGL/GLES calls to a single owning thread. */
    interface RenderExecutor {
        void post(Runnable task);
        void runSync(Runnable task);
        boolean isOnRenderThread();
        void shutdown();

        RenderExecutor DIRECT = new RenderExecutor() {
            @Override public void post(Runnable task) { task.run(); }
            @Override public void runSync(Runnable task) { task.run(); }
            @Override public boolean isOnRenderThread() { return true; }
            @Override public void shutdown() {}
        };
    }

    static final class SingleThreadRenderExecutor implements RenderExecutor {
        private final ExecutorService executor;
        private final AtomicBoolean shutdown = new AtomicBoolean(false);
        private volatile Thread renderThread;

        SingleThreadRenderExecutor() {
            this.executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "orchestella-gles");
                thread.setDaemon(true);
                renderThread = thread;
                return thread;
            });
        }

        @Override
        public void post(Runnable task) {
            if (shutdown.get()) {
                return;
            }
            try {
                executor.execute(task);
            } catch (RejectedExecutionException ignored) {
                // Shutdown race: late render work is discarded safely.
            }
        }

        @Override
        public void runSync(Runnable task) {
            if (isOnRenderThread()) {
                task.run();
                return;
            }
            if (shutdown.get()) {
                return;
            }
            try {
                Future<?> future = executor.submit(task);
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | RejectedExecutionException ignored) {
                // Shutdown or native boundary failure is handled by caller state.
            }
        }

        @Override
        public boolean isOnRenderThread() {
            return Thread.currentThread() == renderThread;
        }

        @Override
        public void shutdown() {
            if (!shutdown.compareAndSet(false, true)) {
                return;
            }
            executor.shutdown();
            if (!isOnRenderThread()) {
                try {
                    if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    executor.shutdownNow();
                }
            }
        }
    }

    /** Native calls are injected explicitly by host tests; production always uses the JNI adapter. */
    interface NativeAdapter {
        boolean available();
        boolean isSurfaceValid(Surface surface);
        long create();
        void release(long handle);
        boolean surfaceCreated(long handle, Surface surface);
        void surfaceChanged(long handle, int width, int height);
        void surfaceDestroyed(long handle);
        boolean present(long handle, int[] pixels, int width, int height,
                ViewportAspectCalculator.ViewportMapping mapping);
        default boolean presentRgba(long handle, ByteBuffer directRgba, int offsetBytes,
                int width, int height, int strideBytes,
                ViewportAspectCalculator.ViewportMapping mapping) {
            return false;
        }
        void clear(long handle, int color);
        float lastPresentTimeMs(long handle);
    }

    private static final NativeAdapter JNI = new NativeAdapter() {
        @Override public boolean available() { return isNativeLoaded; }
        @Override public boolean isSurfaceValid(Surface surface) { return surface != null && surface.isValid(); }
        @Override public long create() { return nativeCreate(); }
        @Override public void release(long handle) { nativeRelease(handle); }
        @Override public boolean surfaceCreated(long handle, Surface surface) { return nativeOnSurfaceCreated(handle, surface); }
        @Override public void surfaceChanged(long handle, int width, int height) { nativeOnSurfaceChanged(handle, width, height); }
        @Override public void surfaceDestroyed(long handle) { nativeOnSurfaceDestroyed(handle); }
        @Override public boolean present(long handle, int[] pixels, int width, int height,
                ViewportAspectCalculator.ViewportMapping mapping) {
            return nativePresent(handle, pixels, width, height, mapping.destX(), mapping.destY(),
                    mapping.destWidth(), mapping.destHeight(), mapping.viewportWidth(), mapping.viewportHeight());
        }
        @Override public boolean presentRgba(long handle, ByteBuffer directRgba, int offsetBytes,
                int width, int height, int strideBytes,
                ViewportAspectCalculator.ViewportMapping mapping) {
            return nativePresentRgba(handle, directRgba, offsetBytes, width, height, strideBytes,
                    mapping.destX(), mapping.destY(), mapping.destWidth(), mapping.destHeight(),
                    mapping.viewportWidth(), mapping.viewportHeight());
        }
        @Override public void clear(long handle, int color) { nativeClear(handle, color); }
        @Override public float lastPresentTimeMs(long handle) { return nativeGetLastPresentTimeMs(handle); }
    };

    private final NativeAdapter nativeAdapter;
    private final RenderExecutor renderExecutor;
    private volatile boolean enabled = true;
    private volatile long nativeHandle = 0;
    private volatile SurfaceHolder holder = null;
    private volatile Surface surface = null;
    private volatile boolean ready = false;
    private volatile int surfaceWidth = 0;
    private volatile int surfaceHeight = 0;
    private volatile float lastPresentTimeMs = 0.0f;
    private volatile long presentCallCount = 0;
    private volatile boolean released = false;
    private volatile long nextRecoveryNs;
    private volatile boolean lastSuccess;
    private ByteBuffer stagingDirectBuffer = null;

    @Override
    public boolean lastPresentationSucceeded() {
        return lastSuccess;
    }

    public NativeGlesSurfacePresenter() {
        this(JNI, new SingleThreadRenderExecutor());
    }

    NativeGlesSurfacePresenter(NativeAdapter nativeAdapter) {
        this(nativeAdapter, RenderExecutor.DIRECT);
    }

    NativeGlesSurfacePresenter(NativeAdapter nativeAdapter, RenderExecutor renderExecutor) {
        this.nativeAdapter = Objects.requireNonNull(nativeAdapter, "nativeAdapter");
        this.renderExecutor = Objects.requireNonNull(renderExecutor, "renderExecutor");
        this.renderExecutor.runSync(this::initHandleOnRenderThread);
    }

    public NativeGlesSurfacePresenter(SurfaceHolder holder) {
        this(holder, JNI, new SingleThreadRenderExecutor());
    }

    NativeGlesSurfacePresenter(SurfaceHolder holder, NativeAdapter nativeAdapter) {
        this(holder, nativeAdapter, RenderExecutor.DIRECT);
    }

    NativeGlesSurfacePresenter(
            SurfaceHolder holder,
            NativeAdapter nativeAdapter,
            RenderExecutor renderExecutor) {
        this(nativeAdapter, renderExecutor);
        this.holder = holder;
    }

    public NativeGlesSurfacePresenter(Surface surface) {
        this(JNI, new SingleThreadRenderExecutor());
        if (validSurface(surface)) {
            onSurfaceCreated(surface);
        }
    }

    private boolean validSurface(Surface candidate) {
        return candidate != null && nativeAdapter.isSurfaceValid(candidate);
    }

    private void initHandleOnRenderThread() {
        if (nativeAdapter.available() && nativeHandle == 0 && !released) {
            try {
                nativeHandle = nativeAdapter.create();
            } catch (RuntimeException | LinkageError ignored) {
                nativeHandle = 0;
            }
        }
    }

    @Override
    public boolean isReady() {
        if (!ready && enabled && !released && nativeAdapter.available() && validSurface(surface)
                && System.nanoTime() >= nextRecoveryNs) {
            if (renderExecutor.isOnRenderThread()) {
                onSurfaceCreatedOnRenderThread(surface);
            } else {
                renderExecutor.runSync(() -> {
                    if (!ready && enabled && !released && validSurface(surface)
                            && System.nanoTime() >= nextRecoveryNs) {
                        onSurfaceCreatedOnRenderThread(surface);
                    }
                });
            }
        }
        return ready && enabled && !released && nativeHandle != 0
                && nativeAdapter.available() && validSurface(surface);
    }

    @Override
    public void setReady(boolean ready) {
        // This is a presentation enable switch, never evidence that EGL initialization succeeded.
        enabled = ready && !released;
        if (!enabled) {
            lastSuccess = false;
        }
    }

    public void onSurfaceCreated(Surface surface) {
        if (released) {
            return;
        }
        renderExecutor.runSync(() -> onSurfaceCreatedOnRenderThread(surface));
    }

    private void onSurfaceCreatedOnRenderThread(Surface candidate) {
        if (released) {
            return;
        }
        if (!validSurface(candidate)) {
            onSurfaceDestroyedOnRenderThread();
            return;
        }
        if (this.surface == candidate && this.ready) {
            return;
        }
        this.surface = candidate;
        ready = false;
        lastSuccess = false;
        lastPresentTimeMs = Float.NaN;
        nextRecoveryNs = System.nanoTime() + 2_000_000_000L;
        if (nativeAdapter.available()) {
            initHandleOnRenderThread();
            if (nativeHandle != 0) {
                try {
                    ready = nativeAdapter.surfaceCreated(nativeHandle, candidate);
                } catch (RuntimeException | LinkageError ignored) {
                    // Initialization failure never becomes readiness, including under host tests.
                }
            }
        }
    }

    public void onSurfaceChanged(int width, int height) {
        if (released) {
            return;
        }
        renderExecutor.runSync(() -> onSurfaceChangedOnRenderThread(width, height));
    }

    private void onSurfaceChangedOnRenderThread(int width, int height) {
        if (released) {
            return;
        }
        this.surfaceWidth = width;
        this.surfaceHeight = height;

        if (nativeAdapter.available() && nativeHandle != 0) {
            try {
                nativeAdapter.surfaceChanged(nativeHandle, width, height);
            } catch (RuntimeException | LinkageError ignored) {
                ready = false;
                lastSuccess = false;
            }
        }
    }

    public void onSurfaceDestroyed() {
        renderExecutor.runSync(this::onSurfaceDestroyedOnRenderThread);
    }

    private void onSurfaceDestroyedOnRenderThread() {
        ready = false;
        lastSuccess = false;
        lastPresentTimeMs = Float.NaN;
        if (!released && surface != null && nativeAdapter.available() && nativeHandle != 0) {
            try {
                nativeAdapter.surfaceDestroyed(nativeHandle);
            } catch (RuntimeException | LinkageError ignored) {
                // The surface stays invalid even when native cleanup fails.
            }
        }
        surface = null;
    }

    @Override
    public boolean submitPresentation(Runnable task) {
        if (released) {
            return false;
        }
        renderExecutor.post(task);
        return true;
    }

    @Override
    public void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
        if (pixels == null || mapping == null || !isWithinRenderBudget(width, height)
                || pixels.length < (long) width * height) {
            lastSuccess = false;
            return;
        }
        renderExecutor.runSync(() -> presentArgbOnRenderThread(pixels, width, height, mapping));
    }

    private void presentArgbOnRenderThread(
            int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
        lastSuccess = false;
        if (!isReady()) {
            return;
        }
        lastPresentTimeMs = Float.NaN;
        try {
            if (nativeAdapter.present(nativeHandle, pixels, width, height, mapping)) {
                recordSuccessfulPresent();
                return;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A failed JNI call has no presentation evidence.
        }
        markPresentationFailed();
    }

    @Override
    public void presentRgba(
            ByteBuffer rgbaPixels,
            int width,
            int height,
            int strideBytes,
            ViewportAspectCalculator.ViewportMapping mapping) {
        if (rgbaPixels == null || mapping == null || !isWithinRenderBudget(width, height)
                || strideBytes < width * 4 || (strideBytes & 3) != 0
                || (long) strideBytes * height > DisplayViewportBudget.MAX_BYTES) {
            lastSuccess = false;
            return;
        }
        renderExecutor.runSync(() -> presentRgbaOnRenderThread(rgbaPixels, width, height, strideBytes, mapping));
    }

    private void presentRgbaOnRenderThread(
            ByteBuffer rgbaPixels,
            int width,
            int height,
            int strideBytes,
            ViewportAspectCalculator.ViewportMapping mapping) {
        lastSuccess = false;
        int requiredBytes = strideBytes * height;
        if (!isReady() || rgbaPixels.remaining() < requiredBytes) {
            return;
        }
        lastPresentTimeMs = Float.NaN;
        ByteBuffer directBuffer;
        int offsetBytes;
        if (rgbaPixels.isDirect()) {
            directBuffer = rgbaPixels;
            offsetBytes = rgbaPixels.position();
        } else {
            if (stagingDirectBuffer == null || stagingDirectBuffer.capacity() < requiredBytes) {
                stagingDirectBuffer = ByteBuffer.allocateDirect(requiredBytes);
            }
            stagingDirectBuffer.clear();
            ByteBuffer sourceSlice = rgbaPixels.slice();
            sourceSlice.limit(requiredBytes);
            stagingDirectBuffer.put(sourceSlice);
            stagingDirectBuffer.flip();
            directBuffer = stagingDirectBuffer;
            offsetBytes = 0;
        }

        try {
            if (nativeAdapter.presentRgba(
                    nativeHandle, directBuffer, offsetBytes, width, height, strideBytes, mapping)) {
                recordSuccessfulPresent();
                return;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A failed JNI call has no presentation evidence.
        }
        markPresentationFailed();
    }

    private static boolean isWithinRenderBudget(int width, int height) {
        return width > 0 && height > 0
                && width <= DisplayViewportBudget.MAX_EDGE
                && height <= DisplayViewportBudget.MAX_EDGE
                && (long) width * height <= DisplayViewportBudget.MAX_PIXELS;
    }

    private void markPresentationFailed() {
        ready = false;
        nextRecoveryNs = System.nanoTime() + 2_000_000_000L;
    }

    private void recordSuccessfulPresent() {
        lastSuccess = true;
        presentCallCount++;
        try {
            lastPresentTimeMs = nativeAdapter.lastPresentTimeMs(nativeHandle);
        } catch (RuntimeException | LinkageError ignored) {
            // Missing metrics do not undo a real swap.
        }
    }

    @Override
    public void clear(int color) {
        if (released) {
            return;
        }
        renderExecutor.runSync(() -> {
            if (!isReady()) {
                return;
            }
            try {
                nativeAdapter.clear(nativeHandle, color);
            } catch (RuntimeException | LinkageError ignored) {
                ready = false;
                lastSuccess = false;
            }
        });
    }

    @Override
    public void release() {
        if (released) {
            return;
        }
        renderExecutor.runSync(() -> {
            if (released) {
                return;
            }
            this.ready = false;
            this.enabled = false;
            this.lastSuccess = false;
            this.lastPresentTimeMs = Float.NaN;
            this.released = true;
            this.holder = null;
            this.stagingDirectBuffer = null;

            if (nativeHandle != 0) {
                long handle = nativeHandle;
                nativeHandle = 0;
                try {
                    nativeAdapter.release(handle);
                } catch (RuntimeException | LinkageError ignored) {
                    // The detached handle is never retried or revived after release.
                }
            }
            this.surface = null;
        });
        renderExecutor.shutdown();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (released) {
            return;
        }
        this.holder = holder;
        onSurfaceCreated(holder == null ? null : holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        this.holder = holder;
        onSurfaceChanged(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        onSurfaceDestroyed();
        this.holder = null;
    }

    public float getLastPresentTimeMs() {
        return lastPresentTimeMs;
    }

    public long getPresentCallCount() {
        return presentCallCount;
    }

    public int getSurfaceWidth() {
        return surfaceWidth;
    }

    public int getSurfaceHeight() {
        return surfaceHeight;
    }

    public long getNativeHandle() {
        return nativeHandle;
    }

    public Surface getSurface() {
        return surface;
    }

    public boolean isReleased() {
        return released;
    }

    // JNI Native declarations
    private static native long nativeCreate();
    private static native void nativeRelease(long handle);
    private static native boolean nativeOnSurfaceCreated(long handle, Surface surface);
    private static native void nativeOnSurfaceChanged(long handle, int width, int height);
    private static native void nativeOnSurfaceDestroyed(long handle);
    private static native boolean nativePresent(
            long handle,
            int[] pixels,
            int width,
            int height,
            int destX,
            int destY,
            int destWidth,
            int destHeight,
            int viewportWidth,
            int viewportHeight);
    private static native boolean nativePresentRgba(
            long handle,
            ByteBuffer rgbaBuffer,
            int offsetBytes,
            int width,
            int height,
            int strideBytes,
            int destX,
            int destY,
            int destWidth,
            int destHeight,
            int viewportWidth,
            int viewportHeight);
    private static native void nativeClear(long handle, int argbColor);
    private static native float nativeGetLastPresentTimeMs(long handle);
    private static native String nativeGetVersion();
}
