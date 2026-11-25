package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.SurfaceHolder;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class NativePresenterLifecycleTest {

    private NativePresenterContractTest.NativeFixture nativeAdapter;
    private SurfaceHolder holder;
    private NativeGlesSurfacePresenter presenter;

    @Before public void setUp() {
        nativeAdapter = new NativePresenterContractTest.NativeFixture();
        holder = nativeAdapter.holder;
        presenter = new NativeGlesSurfacePresenter(holder, nativeAdapter);
    }

    @After public void tearDown() { presenter.release(); }

    @Test public void testSurfaceHolderCallbackFlow() {
        assertFalse(presenter.isReady());
        assertFalse("NativeGlesSurfacePresenter must not register duplicate SurfaceHolder.Callback",
                nativeAdapter.callbacks.callbacks.contains(presenter));
        presenter.surfaceCreated(holder);
        assertTrue("Presenter should be ready after successful native surface initialization", presenter.isReady());
        assertEquals(1, nativeAdapter.initializeCalls);
        presenter.surfaceChanged(holder, 0, 1080, 2400);
        assertEquals(1080, presenter.getSurfaceWidth());
        assertEquals(2400, presenter.getSurfaceHeight());
        assertEquals(1, nativeAdapter.changeCalls);
        presenter.surfaceDestroyed(holder);
        assertFalse("Presenter must not be ready after surfaceDestroyed", presenter.isReady());
        assertEquals(1, nativeAdapter.destroyCalls);
    }

    @Test public void testControllerLifecycleIntegration() {
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter);
        WaylandSurfaceController controller = new WaylandSurfaceController(renderer, (delay, task) -> () -> {});
        controller.onSurfaceCreated(holder);
        assertEquals(WaylandSurfaceController.SurfaceState.CREATED, controller.getSurfaceState());
        assertTrue(presenter.isReady());
        controller.onSurfaceChanged(holder, 0, 1920, 1080);
        assertEquals(WaylandSurfaceController.SurfaceState.CHANGED, controller.getSurfaceState());
        assertEquals(1920, presenter.getSurfaceWidth());
        assertEquals(1080, presenter.getSurfaceHeight());
        controller.onSurfaceDestroyed(holder);
        assertEquals(WaylandSurfaceController.SurfaceState.DESTROYED, controller.getSurfaceState());
        assertFalse(presenter.isReady());
        assertEquals(1, nativeAdapter.initializeCalls);
        assertEquals(1, nativeAdapter.destroyCalls);
    }

    private DisplayChannel.FrameData coloredFrame() {
        byte[] pixels = {
                (byte) 255, 0, 0, (byte) 255,
                0, (byte) 255, 0, (byte) 255,
                0, 0, (byte) 255, (byte) 255,
                (byte) 255, (byte) 255, 0, (byte) 255
        };
        return new DisplayChannel.FrameData(2, 2, 8, DisplayChannel.FrameData.FORMAT_RGBA_8888, pixels, 1, 0);
    }

    @Test public void testDoubleBufferedRendererEndToEndPresentation() {
        presenter.surfaceCreated(holder);
        assertTrue(presenter.isReady());
        final Runnable[] vsyncCallback = new Runnable[1];
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, callback -> vsyncCallback[0] = callback);
        renderer.setViewportSize(1920, 1080);
        int[] confirmations = {0};
        renderer.setPresentationObserver(frame -> confirmations[0]++);
        renderer.onFrameAvailable(coloredFrame());
        assertTrue(renderer.hasNewFrame());
        assertEquals(0, presenter.getPresentCallCount());
        assertNotNull(vsyncCallback[0]);
        vsyncCallback[0].run();
        assertFalse(renderer.hasNewFrame());
        assertEquals(1, renderer.getRenderedFrameCount());
        assertEquals(1, presenter.getPresentCallCount());
        assertTrue(presenter.lastPresentationSucceeded());
        assertEquals(1, confirmations[0]);
        assertEquals(1, nativeAdapter.presentRgbaCalls);
        assertEquals(0, nativeAdapter.presentArgbCalls);
        assertArrayEquals(new byte[] {
                (byte) 255, 0, 0, (byte) 255,
                0, (byte) 255, 0, (byte) 255,
                0, 0, (byte) 255, (byte) 255,
                (byte) 255, (byte) 255, 0, (byte) 255
        }, nativeAdapter.presentedRgba);
        assertEquals(2, nativeAdapter.presentedWidth);
        assertEquals(2, nativeAdapter.presentedHeight);
        assertEquals(nativeAdapter.presentTimeMs, presenter.getLastPresentTimeMs(), 0.001f);
        assertTrue(presenter.getLastPresentTimeMs() <= 3.5f);
    }

    @Test public void testRepeatedRecreateCycles() {
        for (int index = 0; index < 3; index++) {
            presenter.surfaceCreated(holder);
            assertTrue(presenter.isReady());
            presenter.surfaceChanged(holder, 0, 1920, 1080);
            assertEquals(1920, presenter.getSurfaceWidth());
            assertEquals(1080, presenter.getSurfaceHeight());
            presenter.present(new int[100], 10, 10, ViewportAspectCalculator.calculate(10, 10, 1920, 1080));
            assertTrue(presenter.lastPresentationSucceeded());
            presenter.surfaceDestroyed(holder);
            assertFalse(presenter.isReady());
            assertFalse(presenter.lastPresentationSucceeded());
        }
        assertEquals(3, presenter.getPresentCallCount());
        assertEquals(3, nativeAdapter.initializeCalls);
        assertEquals(3, nativeAdapter.presentCalls);
        assertEquals(3, nativeAdapter.destroyCalls);
        assertEquals("surface recreation reuses its one native handle", 1, nativeAdapter.createCalls);
    }

    @Test public void testReleaseIdempotent() {
        presenter.surfaceCreated(holder);
        assertTrue(presenter.isReady());
        presenter.release();
        assertTrue(presenter.isReleased());
        assertFalse(presenter.isReady());
        assertFalse(nativeAdapter.callbacks.callbacks.contains(presenter));
        presenter.release();
        assertTrue(presenter.isReleased());
        presenter.surfaceCreated(holder);
        presenter.setReady(true);
        assertFalse(presenter.isReady());
        assertEquals(1, nativeAdapter.releaseCalls);
        assertEquals(1, nativeAdapter.initializeCalls);
    }

    @Test public void failedInitializationThroughControllerCannotRenderOrConfirm() {
        nativeAdapter.initializationSucceeds = false;
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, Runnable::run);
        int[] confirmations = {0};
        renderer.setPresentationObserver(frame -> confirmations[0]++);
        WaylandSurfaceController controller = new WaylandSurfaceController(renderer, (delay, task) -> () -> {});
        controller.onSurfaceCreated(holder);
        assertFalse("controller setReady(true) cannot override a false native init", presenter.isReady());
        renderer.onFrameAvailable(coloredFrame());
        assertEquals(0, nativeAdapter.presentCalls);
        assertEquals(0, renderer.getRenderedFrameCount());
        assertEquals(0, confirmations[0]);
    }

    @Test public void failedNativePresentationDoesNotCountOrConfirmAndFreshInitRecovers() {
        presenter.surfaceCreated(holder);
        nativeAdapter.presentationSucceeds = false;
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, Runnable::run);
        int[] confirmations = {0};
        renderer.setPresentationObserver(frame -> confirmations[0]++);
        renderer.onFrameAvailable(coloredFrame());
        assertEquals(1, nativeAdapter.presentCalls);
        assertFalse(presenter.lastPresentationSucceeded());
        assertFalse(presenter.isReady());
        assertEquals(0, presenter.getPresentCallCount());
        assertEquals(0, renderer.getRenderedFrameCount());
        assertEquals(1, renderer.getDroppedFrameCount());
        assertEquals(0, confirmations[0]);
        assertTrue(Float.isNaN(presenter.getLastPresentTimeMs()));
        presenter.setReady(true);
        assertFalse("enable cannot fabricate recovery from native swap failure", presenter.isReady());
        nativeAdapter.presentationSucceeds = true;
        presenter.surfaceCreated(holder);
        assertTrue(presenter.isReady());
        renderer.onFrameAvailable(coloredFrame());
        assertEquals(2, nativeAdapter.presentCalls);
        assertEquals(1, presenter.getPresentCallCount());
        assertEquals(1, renderer.getRenderedFrameCount());
        assertEquals(1, confirmations[0]);
    }

    @Test public void nullSurfaceHolderCannotInitializeEvenAnAvailableAdapter() {
        presenter.surfaceCreated(new FakeSurfaceHolder());
        presenter.setReady(true);
        assertFalse(presenter.isReady());
        assertEquals(0, nativeAdapter.initializeCalls);
        assertEquals(0, nativeAdapter.presentCalls);
    }

    @Test public void failedReleaseStillDetachesTheHandleOnce() {
        presenter.surfaceCreated(holder);
        nativeAdapter.releaseFailure = new IllegalStateException("synthetic release failure");
        presenter.release();
        presenter.release();
        presenter.setReady(true);
        presenter.surfaceCreated(holder);
        assertFalse(presenter.isReady());
        assertFalse(presenter.lastPresentationSucceeded());
        assertEquals(0, presenter.getNativeHandle());
        assertEquals(1, nativeAdapter.releaseCalls);
        assertEquals(1, nativeAdapter.initializeCalls);
    }

    @Test public void singleThreadRenderExecutorPinsAllEglCallsToOneDedicatedThread() throws Exception {
        NativePresenterContractTest.NativeFixture fixture = new NativePresenterContractTest.NativeFixture();
        NativeGlesSurfacePresenter.SingleThreadRenderExecutor executor =
                new NativeGlesSurfacePresenter.SingleThreadRenderExecutor();
        NativeGlesSurfacePresenter threadedPresenter =
                new NativeGlesSurfacePresenter(fixture.holder, fixture, executor);
        try {
            threadedPresenter.surfaceCreated(fixture.holder);
            threadedPresenter.surfaceChanged(fixture.holder, 0, 1080, 2400);

            DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(threadedPresenter, Runnable::run);
            renderer.setViewportSize(1080, 2400);
            java.util.concurrent.CountDownLatch presented = new java.util.concurrent.CountDownLatch(1);
            renderer.setPresentationObserver(frame -> presented.countDown());
            renderer.onFrameAvailable(coloredFrame());
            assertTrue("async EGL render thread must present frame",
                    presented.await(2, java.util.concurrent.TimeUnit.SECONDS));

            threadedPresenter.clear(0xFF000000);
            threadedPresenter.surfaceDestroyed(fixture.holder);
            threadedPresenter.release();

            assertEquals("all native EGL/GL hooks must run on one dedicated thread",
                    1, fixture.nativeCallThreads.size());
            Thread eglThread = fixture.nativeCallThreads.iterator().next();
            assertFalse("EGL thread must differ from caller thread",
                    eglThread == Thread.currentThread());
            assertEquals("orchestella-gles", eglThread.getName());
            assertEquals(1, fixture.presentRgbaCalls);
            assertEquals(0, fixture.presentArgbCalls);
            assertEquals(1, fixture.clearCalls);
            assertEquals(1, fixture.destroyCalls);
            assertEquals(1, fixture.releaseCalls);
        } finally {
            threadedPresenter.release();
        }
    }
}
