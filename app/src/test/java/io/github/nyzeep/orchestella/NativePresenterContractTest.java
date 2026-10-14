package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.Surface;
import android.view.SurfaceHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class NativePresenterContractTest {

    /** Explicit native-boundary fixture; it never changes library loading or production JNI state. */
    static final class NativeFixture implements NativeGlesSurfacePresenter.NativeAdapter {
        final Surface surface = new Surface((android.graphics.SurfaceTexture) null);
        final FakeSurfaceHolder callbacks = new FakeSurfaceHolder();
        final SurfaceHolder holder = (SurfaceHolder) java.lang.reflect.Proxy.newProxyInstance(
                SurfaceHolder.class.getClassLoader(), new Class<?>[] {SurfaceHolder.class},
                (proxy, method, arguments) -> "getSurface".equals(method.getName())
                        ? surface : method.invoke(callbacks, arguments));
        boolean available = true;
        boolean valid = true;
        boolean initializationSucceeds = true;
        boolean presentationSucceeds = true;
        boolean initialized;
        boolean released;
        long handle = 73;
        int createCalls, initializeCalls, presentCalls, destroyCalls, releaseCalls, changeCalls, clearCalls;
        int[] presentedPixels;
        int presentedWidth, presentedHeight;
        ViewportAspectCalculator.ViewportMapping presentedMapping;
        RuntimeException initializationFailure, presentationFailure, timingFailure, releaseFailure;
        float presentTimeMs = 2.25f;

        private void requireHandle(long actual) {
            assertEquals("adapter receives its own allocated handle", handle, actual);
            assertFalse("released native handles cannot be used again", released);
        }
        @Override public boolean available() { return available; }
        @Override public boolean isSurfaceValid(Surface candidate) { return valid && candidate == surface; }
        @Override public long create() { createCalls++; return handle; }
        @Override public boolean surfaceCreated(long actual, Surface candidate) {
            requireHandle(actual);
            assertEquals(surface, candidate);
            initializeCalls++;
            initialized = false;
            if (initializationFailure != null) throw initializationFailure;
            initialized = initializationSucceeds;
            return initialized;
        }
        @Override public void surfaceChanged(long actual, int width, int height) {
            requireHandle(actual);
            changeCalls++;
        }
        @Override public void surfaceDestroyed(long actual) {
            requireHandle(actual);
            destroyCalls++;
            initialized = false;
        }
        @Override public boolean present(long actual, int[] pixels, int width, int height,
                ViewportAspectCalculator.ViewportMapping mapping) {
            requireHandle(actual);
            assertTrue("presentation requires successful native initialization", initialized);
            presentCalls++;
            presentedPixels = java.util.Arrays.copyOf(pixels, width * height);
            presentedWidth = width;
            presentedHeight = height;
            presentedMapping = mapping;
            if (presentationFailure != null) throw presentationFailure;
            return presentationSucceeds;
        }
        @Override public void clear(long actual, int color) { requireHandle(actual); clearCalls++; }
        @Override public float lastPresentTimeMs(long actual) {
            requireHandle(actual);
            if (timingFailure != null) throw timingFailure;
            return presentTimeMs;
        }
        @Override public void release(long actual) {
            requireHandle(actual);
            releaseCalls++;
            released = true;
            initialized = false;
            if (releaseFailure != null) throw releaseFailure;
        }
    }

    private NativeFixture nativeAdapter;
    private NativeGlesSurfacePresenter presenter;

    @Before public void setUp() {
        nativeAdapter = new NativeFixture();
        presenter = new NativeGlesSurfacePresenter(nativeAdapter);
    }

    @After public void tearDown() { presenter.release(); }

    @Test public void testContractAndInitialState() {
        assertTrue("Must implement SurfacePresenter interface",
                presenter instanceof DoubleBufferedRenderer.SurfacePresenter);
        assertTrue("Must implement SurfaceHolder.Callback interface",
                presenter instanceof SurfaceHolder.Callback);
        assertFalse("Initial presenter should not be ready before surface is bound", presenter.isReady());
        assertFalse("Initial presenter should not be released", presenter.isReleased());
        assertEquals(0, presenter.getPresentCallCount());
        assertEquals(0.0f, presenter.getLastPresentTimeMs(), 0.001f);
        assertNotNull(NativeGlesSurfacePresenter.getNativeVersion());
        assertEquals(1, nativeAdapter.createCalls);
        assertEquals(0, nativeAdapter.initializeCalls);
    }

    @Test public void testSetReadyToggle() {
        assertFalse(presenter.isReady());
        presenter.setReady(true);
        assertFalse("setReady cannot substitute for binding and native initialization", presenter.isReady());
        presenter.onSurfaceCreated(nativeAdapter.surface);
        assertTrue(presenter.isReady());
        presenter.setReady(false);
        assertFalse("explicit disable cannot be undone by isReady recovery", presenter.isReady());
        presenter.setReady(true);
        assertTrue(presenter.isReady());
        assertEquals(1, nativeAdapter.initializeCalls);
    }

    @Test public void testPresentIgnoredWhenNotReady() {
        int[] pixels = new int[100 * 100];
        ViewportAspectCalculator.ViewportMapping mapping = ViewportAspectCalculator.calculate(100, 100, 100, 100);
        presenter.present(pixels, 100, 100, mapping);
        assertEquals(0, presenter.getPresentCallCount());
        assertEquals(0, nativeAdapter.presentCalls);
        assertFalse(presenter.lastPresentationSucceeded());
    }

    @Test public void testPresentProcessedWhenReady() {
        presenter.onSurfaceCreated(nativeAdapter.surface);
        int[] pixels = new int[1920 * 1080];
        pixels[0] = 0x78123456;
        ViewportAspectCalculator.ViewportMapping mapping = ViewportAspectCalculator.calculate(1920, 1080, 1920, 1080);
        presenter.present(pixels, 1920, 1080, mapping);
        assertEquals(1, presenter.getPresentCallCount());
        assertEquals(1, nativeAdapter.presentCalls);
        assertTrue(presenter.lastPresentationSucceeded());
        assertArrayEquals(pixels, nativeAdapter.presentedPixels);
        assertEquals(mapping, nativeAdapter.presentedMapping);
        assertEquals(nativeAdapter.presentTimeMs, presenter.getLastPresentTimeMs(), 0.001f);
        assertTrue("Injected successful native duration is forwarded", presenter.getLastPresentTimeMs() <= 3.5f);
    }

    @Test public void testNullOrInvalidArgumentsSafe() {
        presenter.onSurfaceCreated(nativeAdapter.surface);
        ViewportAspectCalculator.ViewportMapping mapping = ViewportAspectCalculator.calculate(100, 100, 100, 100);
        presenter.present(null, 100, 100, mapping);
        assertEquals(0, presenter.getPresentCallCount());
        int[] pixels = new int[100];
        presenter.present(pixels, 10, 10, null);
        assertEquals(0, presenter.getPresentCallCount());
        presenter.present(pixels, 0, 10, mapping);
        assertEquals(0, presenter.getPresentCallCount());
        presenter.present(pixels, 100, 100, mapping);
        presenter.present(pixels, 4096, 4096, mapping);
        assertEquals("invalid buffers never reach the native adapter", 0, nativeAdapter.presentCalls);
        presenter.clear(0xFF000000);
        presenter.onSurfaceCreated(null);
        assertFalse(presenter.isReady());
        assertEquals(1, nativeAdapter.destroyCalls);
    }

    @Test public void testReleaseInvalidatesPresenter() {
        presenter.onSurfaceCreated(nativeAdapter.surface);
        presenter.setReady(true);
        assertTrue(presenter.isReady());
        presenter.release();
        assertTrue(presenter.isReleased());
        assertFalse(presenter.isReady());
        assertEquals(1, nativeAdapter.releaseCalls);
        assertEquals(0, presenter.getNativeHandle());
        presenter.present(new int[100], 10, 10, ViewportAspectCalculator.calculate(10, 10, 10, 10));
        assertEquals(0, presenter.getPresentCallCount());
        assertEquals(0, nativeAdapter.presentCalls);
    }

    @Test public void initializationFalseCannotBecomeReadyOrPresentViaSetReady() {
        nativeAdapter.initializationSucceeds = false;
        presenter.onSurfaceCreated(nativeAdapter.surface);
        presenter.setReady(true);
        assertFalse(presenter.isReady());
        presenter.present(new int[4], 2, 2, ViewportAspectCalculator.calculate(2, 2, 2, 2));
        assertEquals(1, nativeAdapter.initializeCalls);
        assertEquals(0, nativeAdapter.presentCalls);
        assertEquals(0, presenter.getPresentCallCount());
        assertFalse(presenter.lastPresentationSucceeded());
        nativeAdapter.initializationSucceeds = true;
        presenter.onSurfaceCreated(nativeAdapter.surface);
        assertTrue("a fresh successful native initialization can recover", presenter.isReady());
    }

    @Test public void missingNativeZeroHandleAndInvalidSurfaceAllRefuseReadiness() {
        nativeAdapter.available = false;
        presenter.onSurfaceCreated(nativeAdapter.surface);
        presenter.setReady(true);
        assertFalse(presenter.isReady());
        assertEquals(0, nativeAdapter.initializeCalls);
        nativeAdapter.available = true;
        nativeAdapter.valid = false;
        presenter.onSurfaceCreated(nativeAdapter.surface);
        assertFalse(presenter.isReady());
        assertEquals(0, nativeAdapter.initializeCalls);
        NativeFixture zero = new NativeFixture();
        zero.handle = 0;
        NativeGlesSurfacePresenter withoutHandle = new NativeGlesSurfacePresenter(zero);
        try {
            withoutHandle.onSurfaceCreated(zero.surface);
            withoutHandle.setReady(true);
            assertFalse(withoutHandle.isReady());
            assertEquals(0, zero.initializeCalls);
        } finally { withoutHandle.release(); }
    }

    @Test public void initializationAndPresentationExceptionsFailClosed() {
        nativeAdapter.initializationFailure = new IllegalStateException("synthetic initialization failure");
        presenter.onSurfaceCreated(nativeAdapter.surface);
        assertFalse(presenter.isReady());
        nativeAdapter.initializationFailure = null;
        presenter.onSurfaceCreated(nativeAdapter.surface);
        assertTrue(presenter.isReady());
        nativeAdapter.presentationFailure = new IllegalStateException("synthetic swap failure");
        presenter.present(new int[4], 2, 2, ViewportAspectCalculator.calculate(2, 2, 2, 2));
        assertFalse(presenter.isReady());
        assertFalse(presenter.lastPresentationSucceeded());
        assertEquals(0, presenter.getPresentCallCount());
        assertTrue(Float.isNaN(presenter.getLastPresentTimeMs()));
    }

    @Test public void missingTimingDoesNotUndoASuccessfulNativePresentation() {
        presenter.onSurfaceCreated(nativeAdapter.surface);
        nativeAdapter.timingFailure = new IllegalStateException("synthetic metric unavailable");
        presenter.present(new int[4], 2, 2, ViewportAspectCalculator.calculate(2, 2, 2, 2));
        assertTrue(presenter.lastPresentationSucceeded());
        assertEquals(1, presenter.getPresentCallCount());
        assertTrue(Float.isNaN(presenter.getLastPresentTimeMs()));
    }
}
