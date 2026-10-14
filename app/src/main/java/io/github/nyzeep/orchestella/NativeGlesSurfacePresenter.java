package io.github.nyzeep.orchestella;

import android.view.Surface;
import android.view.SurfaceHolder;

/**
 * High-performance native OpenGL ES 3.0 presenter using ANativeWindow and dual-PBO ping-pong streaming.
 *
 * <p>Conforms to Ticket 56, ADR-0017, and ADR-0019:
 * <ul>
 *   <li>Replaces legacy Java {@link android.graphics.Bitmap#setPixels} and Hardware Canvas presentation;</li>
 *   <li>Integrates directly with NDK {@code ANativeWindow_fromSurface};</li>
 *   <li>Initializes EGL 1.4 + OpenGL ES 3.0 context locked to hardware VSYNC via {@code eglSwapInterval(1)};</li>
 *   <li>Maintains dual PBOs ({@code pboIds[2]}) for asynchronous DMA texture upload ($<= 3.5\text{ms}$);</li>
 *   <li>Performs aspect-ratio letterboxing and full-screen shader quad presentation;</li>
 *   <li>Requires actual native initialization and presentation results; host tests inject the native boundary.</li>
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
            }
        }
        return "GLES presenter unavailable";
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
        @Override public void clear(long handle, int color) { nativeClear(handle, color); }
        @Override public float lastPresentTimeMs(long handle) { return nativeGetLastPresentTimeMs(handle); }
    };

    private final NativeAdapter nativeAdapter;
    private boolean enabled = true;
    private long nativeHandle = 0;
    private SurfaceHolder holder = null;
    private Surface surface = null;
    private boolean ready = false;
    private int surfaceWidth = 0;
    private int surfaceHeight = 0;
    private float lastPresentTimeMs = 0.0f;
    private long presentCallCount = 0;
    private boolean released = false;
    private long nextRecoveryNs;
    private boolean lastSuccess;
    @Override public synchronized boolean lastPresentationSucceeded() { return lastSuccess; }

    public NativeGlesSurfacePresenter() {
        this(JNI);
    }

    NativeGlesSurfacePresenter(NativeAdapter nativeAdapter) {
        this.nativeAdapter = java.util.Objects.requireNonNull(nativeAdapter, "nativeAdapter");
        initHandleIfAvailable();
    }

    public NativeGlesSurfacePresenter(SurfaceHolder holder) {
        this(holder, JNI);
    }

    NativeGlesSurfacePresenter(SurfaceHolder holder, NativeAdapter nativeAdapter) {
        this(nativeAdapter);
        this.holder = holder;
        if (holder != null) {
            holder.addCallback(this);
        }
    }

    public NativeGlesSurfacePresenter(Surface surface) {
        this(JNI);
        if (validSurface(surface)) {
            onSurfaceCreated(surface);
        }
    }

    private boolean validSurface(Surface candidate) {
        return candidate != null && nativeAdapter.isSurfaceValid(candidate);
    }

    private void initHandleIfAvailable() {
        if (nativeAdapter.available() && nativeHandle == 0 && !released) {
            try {
                nativeHandle = nativeAdapter.create();
            } catch (RuntimeException | LinkageError ignored) {
                nativeHandle = 0;
            }
        }
    }

    @Override
    public synchronized boolean isReady() {
        if (!ready && enabled && !released && nativeAdapter.available() && validSurface(surface)
                && System.nanoTime() >= nextRecoveryNs) {
            onSurfaceCreated(surface);
        }
        return ready && enabled && !released && nativeHandle != 0
                && nativeAdapter.available() && validSurface(surface);
    }

    @Override
    public synchronized void setReady(boolean ready) {
        // This is a presentation enable switch, never evidence that EGL initialization succeeded.
        enabled = ready && !released;
        if (!enabled) lastSuccess = false;
    }

    public synchronized void onSurfaceCreated(Surface surface) {
        if (released) return;
        if (!validSurface(surface)) {
            onSurfaceDestroyed();
            return;
        }
        if (this.surface == surface && this.ready) return;
        this.surface = surface;
        ready = false;
        lastSuccess = false;
        lastPresentTimeMs = Float.NaN;
        nextRecoveryNs = System.nanoTime() + 2_000_000_000L;
        if (nativeAdapter.available()) {
            initHandleIfAvailable();
            if (nativeHandle != 0) {
                try {
                    ready = nativeAdapter.surfaceCreated(nativeHandle, surface);
                } catch (RuntimeException | LinkageError ignored) {
                    // Initialization failure never becomes readiness, including under host tests.
                }
            }
        }
    }

    public synchronized void onSurfaceChanged(int width, int height) {
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

    public synchronized void onSurfaceDestroyed() {
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
    public synchronized void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
        lastSuccess = false;
        if (!isReady() || pixels == null || mapping == null || width <= 0 || height <= 0
                || width > 4096 || height > 4096 || (long) width * height > 4_194_304
                || pixels.length < (long) width * height) return;
        lastPresentTimeMs = Float.NaN;
        try {
            if (nativeAdapter.present(nativeHandle, pixels, width, height, mapping)) {
                lastSuccess = true;
                presentCallCount++;
                try { lastPresentTimeMs = nativeAdapter.lastPresentTimeMs(nativeHandle); }
                catch (RuntimeException | LinkageError ignored) { /* Missing metrics do not undo a real swap. */ }
                return;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A failed JNI call has no presentation evidence.
        }
        ready = false;
        nextRecoveryNs = System.nanoTime() + 2_000_000_000L;
    }

    @Override
    public synchronized void clear(int color) {
        if (!isReady()) return;
        try {
            nativeAdapter.clear(nativeHandle, color);
        } catch (RuntimeException | LinkageError ignored) {
            ready = false;
            lastSuccess = false;
        }
    }

    @Override
    public synchronized void release() {
        if (released) {
            return;
        }
        this.ready = false;
        this.enabled = false;
        this.lastSuccess = false;
        this.lastPresentTimeMs = Float.NaN;
        this.released = true;

        if (holder != null) {
            holder.removeCallback(this);
            holder = null;
        }

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
    }

    @Override
    public synchronized void surfaceCreated(SurfaceHolder holder) {
        if (released) return;
        this.holder = holder;
        onSurfaceCreated(holder == null ? null : holder.getSurface());
    }

    @Override
    public synchronized void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        this.holder = holder;
        onSurfaceChanged(width, height);
    }

    @Override
    public synchronized void surfaceDestroyed(SurfaceHolder holder) {
        onSurfaceDestroyed();
        this.holder = null;
    }

    public synchronized float getLastPresentTimeMs() {
        return lastPresentTimeMs;
    }

    public synchronized long getPresentCallCount() {
        return presentCallCount;
    }

    public synchronized int getSurfaceWidth() {
        return surfaceWidth;
    }

    public synchronized int getSurfaceHeight() {
        return surfaceHeight;
    }

    public synchronized long getNativeHandle() {
        return nativeHandle;
    }

    public synchronized Surface getSurface() {
        return surface;
    }

    public synchronized boolean isReleased() {
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
    private static native void nativeClear(long handle, int argbColor);
    private static native float nativeGetLastPresentTimeMs(long handle);
    private static native String nativeGetVersion();
}
