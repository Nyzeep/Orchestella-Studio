package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import org.junit.Before;
import org.junit.Test;

public final class NativePresenterLifecycleTest {

    private FakeSurfaceHolder holder;
    private NativeGlesSurfacePresenter presenter;

    @Before
    public void setUp() {
        holder = new FakeSurfaceHolder();
        presenter = new NativeGlesSurfacePresenter(holder);
    }

    @Test
    public void testSurfaceHolderCallbackFlow() {
        assertFalse(presenter.isReady());

        presenter.surfaceCreated(holder);
        assertTrue("Presenter should be ready after surfaceCreated", presenter.isReady());

        presenter.surfaceChanged(holder, 0, 1080, 2400);
        assertEquals(1080, presenter.getSurfaceWidth());
        assertEquals(2400, presenter.getSurfaceHeight());

        presenter.surfaceDestroyed(holder);
        assertFalse("Presenter must not be ready after surfaceDestroyed", presenter.isReady());
    }

    @Test
    public void testControllerLifecycleIntegration() {
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter);
        WaylandSurfaceController controller = new WaylandSurfaceController(
                renderer,
                (delay, task) -> () -> {}
        );

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
    }

    @Test
    public void testDoubleBufferedRendererEndToEndPresentation() {
        presenter.surfaceCreated(holder);
        assertTrue(presenter.isReady());

        final Runnable[] vsyncCallback = new Runnable[1];
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, cb -> vsyncCallback[0] = cb);
        renderer.setViewportSize(1920, 1080);

        // Create 2x2 RGBA frame
        byte[] rawPixels = new byte[] {
            (byte) 0xFF, 0x00, 0x00, (byte) 0xFF, // Red
            0x00, (byte) 0xFF, 0x00, (byte) 0xFF, // Green
            0x00, 0x00, (byte) 0xFF, (byte) 0xFF, // Blue
            (byte) 0xFF, (byte) 0xFF, 0x00, (byte) 0xFF // Yellow
        };
        WaylandChannel.FrameData frame = new WaylandChannel.FrameData(
                2, 2, 8, WaylandChannel.FrameData.FORMAT_RGBA_8888, rawPixels, 1L, 0L
        );

        renderer.onFrameAvailable(frame);
        assertTrue(renderer.hasNewFrame());
        assertEquals(0, presenter.getPresentCallCount());

        if (vsyncCallback[0] != null) {
            vsyncCallback[0].run();
        }
        assertFalse(renderer.hasNewFrame());
        assertEquals(1, renderer.getRenderedFrameCount());
        assertEquals(1, presenter.getPresentCallCount());
        assertTrue(presenter.getLastPresentTimeMs() <= 3.5f);
    }

    @Test
    public void testRepeatedRecreateCycles() {
        for (int i = 0; i < 3; i++) {
            presenter.surfaceCreated(holder);
            assertTrue(presenter.isReady());

            presenter.surfaceChanged(holder, 0, 1920, 1080);
            assertEquals(1920, presenter.getSurfaceWidth());
            assertEquals(1080, presenter.getSurfaceHeight());

            int[] pixels = new int[100];
            ViewportAspectCalculator.ViewportMapping mapping =
                    ViewportAspectCalculator.calculate(10, 10, 1920, 1080);
            presenter.present(pixels, 10, 10, mapping);

            presenter.surfaceDestroyed(holder);
            assertFalse(presenter.isReady());
        }
        assertEquals(3, presenter.getPresentCallCount());
    }

    @Test
    public void testReleaseIdempotent() {
        presenter.surfaceCreated(holder);
        assertTrue(presenter.isReady());

        presenter.release();
        assertTrue(presenter.isReleased());
        assertFalse(presenter.isReady());

        // Calling release again does not throw
        presenter.release();
        assertTrue(presenter.isReleased());
    }
}
