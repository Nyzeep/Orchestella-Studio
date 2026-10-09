package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public final class NativePresenterContractTest {

    private NativeGlesSurfacePresenter presenter;

    @Before
    public void setUp() {
        presenter = new NativeGlesSurfacePresenter();
    }

    @Test
    public void testContractAndInitialState() {
        assertTrue("Must implement SurfacePresenter interface",
                presenter instanceof DoubleBufferedRenderer.SurfacePresenter);
        assertTrue("Must implement SurfaceHolder.Callback interface",
                presenter instanceof android.view.SurfaceHolder.Callback);
        assertFalse("Initial presenter should not be ready before surface is bound",
                presenter.isReady());
        assertFalse("Initial presenter should not be released",
                presenter.isReleased());
        assertEquals(0, presenter.getPresentCallCount());
        assertEquals(0.0f, presenter.getLastPresentTimeMs(), 0.001f);
        assertNotNull(NativeGlesSurfacePresenter.getNativeVersion());
    }

    @Test
    public void testSetReadyToggle() {
        assertFalse(presenter.isReady());
        presenter.setReady(true);
        assertTrue(presenter.isReady());
        presenter.setReady(false);
        assertFalse(presenter.isReady());
    }

    @Test
    public void testPresentIgnoredWhenNotReady() {
        int[] pixels = new int[100 * 100];
        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(100, 100, 100, 100);

        presenter.present(pixels, 100, 100, mapping);
        assertEquals(0, presenter.getPresentCallCount());
    }

    @Test
    public void testPresentProcessedWhenReady() {
        presenter.setReady(true);
        int[] pixels = new int[1920 * 1080];
        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(1920, 1080, 1920, 1080);

        presenter.present(pixels, 1920, 1080, mapping);
        assertEquals(1, presenter.getPresentCallCount());
        assertTrue("Present duration should be <= 3.5ms constraint",
                presenter.getLastPresentTimeMs() <= 3.5f);
    }

    @Test
    public void testNullOrInvalidArgumentsSafe() {
        presenter.setReady(true);
        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(100, 100, 100, 100);

        // Null pixels
        presenter.present(null, 100, 100, mapping);
        assertEquals(0, presenter.getPresentCallCount());

        // Null mapping
        int[] pixels = new int[100];
        presenter.present(pixels, 10, 10, null);
        assertEquals(0, presenter.getPresentCallCount());

        // Invalid dimensions
        presenter.present(pixels, 0, 10, mapping);
        assertEquals(0, presenter.getPresentCallCount());

        // Clear and onSurfaceCreated with null do not throw
        presenter.clear(0xFF000000);
        presenter.onSurfaceCreated(null);
        assertFalse(presenter.isReady());
    }

    @Test
    public void testReleaseInvalidatesPresenter() {
        presenter.setReady(true);
        assertTrue(presenter.isReady());

        presenter.release();
        assertTrue(presenter.isReleased());
        assertFalse(presenter.isReady());

        // Further present calls after release must be no-ops
        int[] pixels = new int[100];
        ViewportAspectCalculator.ViewportMapping mapping =
                ViewportAspectCalculator.calculate(10, 10, 10, 10);
        presenter.present(pixels, 10, 10, mapping);
        assertEquals(0, presenter.getPresentCallCount());
    }
}
