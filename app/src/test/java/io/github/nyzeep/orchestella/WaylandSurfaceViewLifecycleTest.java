package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.DisplayControl;
import io.github.nyzeep.orchestella.runtime.contract.DisplayListener;
import io.github.nyzeep.orchestella.runtime.contract.DisplayMode;
import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class WaylandSurfaceViewLifecycleTest {

    private static final class FakeDisplayControl implements DisplayControl {
        DisplayMode mode = new DisplayMode(1280, 720, 1.0, 160, 60);
        final List<DisplayListener> listeners = new ArrayList<>();
        int lastResizeWidth = -1;
        int lastResizeHeight = -1;
        int resizeCallCount = 0;

        @Override
        public DisplayMode currentMode() {
            return mode;
        }

        @Override
        public boolean resizeDisplay(int width, int height) {
            this.lastResizeWidth = width;
            this.lastResizeHeight = height;
            this.resizeCallCount++;
            this.mode = mode.withDimensions(width, height);
            for (DisplayListener l : listeners) {
                l.onDisplayResized(width, height);
            }
            return true;
        }

        @Override
        public boolean updateScale(double scale) {
            this.mode = mode.withScale(scale);
            return true;
        }

        @Override
        public boolean updateTargetFps(int targetFps) {
            this.mode = mode.withFps(targetFps);
            return true;
        }

        @Override
        public void addDisplayListener(DisplayListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeDisplayListener(DisplayListener listener) {
            listeners.remove(listener);
        }
    }

    private static final class FakeWaylandChannel implements WaylandChannel {
        ConnectionState state = ConnectionState.CONNECTED;
        final List<StateListener> stateListeners = new ArrayList<>();
        final List<FrameListener> frameListeners = new ArrayList<>();
        boolean closed = false;

        @Override
        public ConnectionState state() {
            return state;
        }

        @Override
        public void addStateListener(StateListener listener) {
            stateListeners.add(listener);
        }

        @Override
        public void removeStateListener(StateListener listener) {
            stateListeners.remove(listener);
        }

        @Override
        public void registerFrameListener(FrameListener listener) {
            frameListeners.add(listener);
        }

        @Override
        public void unregisterFrameListener(FrameListener listener) {
            frameListeners.remove(listener);
        }

        @Override
        public boolean sendInputEvent(InputEvent event) {
            return true;
        }

        @Override
        public void close() {
            closed = true;
            state = ConnectionState.DISCONNECTED;
        }

        void notifyState(ConnectionState newState, String msg) {
            this.state = newState;
            for (StateListener l : stateListeners) {
                l.onStateChanged(newState, msg);
            }
        }
    }

    private static final class TestPresenter implements DoubleBufferedRenderer.SurfacePresenter {
        boolean ready = false;
        int presentCount = 0;

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void setReady(boolean ready) {
            this.ready = ready;
        }

        @Override
        public void present(int[] pixels, int width, int height, ViewportAspectCalculator.ViewportMapping mapping) {
            presentCount++;
        }

        @Override
        public void clear(int color) {}

        @Override
        public void release() {
            ready = false;
        }
    }

    private static final class TestScheduler implements DisplayResizeDebounce.TaskScheduler {
        Runnable task;
        @Override
        public DisplayResizeDebounce.Cancellable schedule(long delayMs, Runnable task) {
            this.task = task;
            return () -> this.task = null;
        }
        void fire() {
            if (task != null) {
                Runnable t = task;
                task = null;
                t.run();
            }
        }
    }

    @Test
    public void surfaceHolderCallbackLifecycleTransitions() {
        TestPresenter presenter = new TestPresenter();
        TestScheduler debounceScheduler = new TestScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, (cb) -> {});
        WaylandSurfaceController controller = new WaylandSurfaceController(renderer, debounceScheduler);

        FakeSurfaceHolder holder = new FakeSurfaceHolder();

        // 1. Surface created
        controller.onSurfaceCreated(holder);
        assertTrue(presenter.ready);
        assertEquals(WaylandSurfaceController.SurfaceState.CREATED, controller.getSurfaceState());

        // 2. Surface changed (e.g. 1920x1080)
        controller.onSurfaceChanged(holder, 0, 1920, 1080);
        assertEquals(WaylandSurfaceController.SurfaceState.CHANGED, controller.getSurfaceState());
        assertEquals(1920, renderer.getViewportWidth());
        assertEquals(1080, renderer.getViewportHeight());

        // 3. Surface destroyed
        controller.onSurfaceDestroyed(holder);
        assertEquals(WaylandSurfaceController.SurfaceState.DESTROYED, controller.getSurfaceState());
        assertFalse(presenter.ready);
    }

    @Test
    public void dynamicBindingAndUnbindingOfWaylandChannelAndDisplayControl() {
        TestPresenter presenter = new TestPresenter();
        TestScheduler scheduler = new TestScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, (cb) -> {});
        WaylandSurfaceController controller = new WaylandSurfaceController(renderer, scheduler);

        FakeDisplayControl displayControl = new FakeDisplayControl();
        FakeWaylandChannel channel = new FakeWaylandChannel();

        assertFalse(controller.isBound());

        // Bind
        controller.bind(displayControl, channel);
        assertTrue(controller.isBound());
        assertEquals(1, channel.frameListeners.size());
        assertEquals(1, channel.stateListeners.size());

        // Unbind
        controller.unbind();
        assertFalse(controller.isBound());
        assertEquals(0, channel.frameListeners.size());
        assertEquals(0, channel.stateListeners.size());
    }

    @Test
    public void renegotiatesDisplayDimensionsOnSurfaceChanged() {
        TestPresenter presenter = new TestPresenter();
        TestScheduler scheduler = new TestScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, (cb) -> {});
        WaylandSurfaceController controller = new WaylandSurfaceController(renderer, scheduler);

        FakeDisplayControl displayControl = new FakeDisplayControl();
        FakeWaylandChannel channel = new FakeWaylandChannel();
        controller.bind(displayControl, channel);

        FakeSurfaceHolder holder = new FakeSurfaceHolder();
        controller.onSurfaceCreated(holder);

        // Screen rotation to 2400x1080
        controller.onSurfaceChanged(holder, 0, 2400, 1080);
        assertEquals(0, displayControl.resizeCallCount);

        // Debounce timer fires
        scheduler.fire();
        assertEquals(1, displayControl.resizeCallCount);
        assertEquals(2400, displayControl.lastResizeWidth);
        assertEquals(1080, displayControl.lastResizeHeight);
    }

    @Test
    public void appForegroundBackgroundLifecycle() {
        TestPresenter presenter = new TestPresenter();
        TestScheduler scheduler = new TestScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, (cb) -> {});
        WaylandSurfaceController controller = new WaylandSurfaceController(renderer, scheduler);

        FakeDisplayControl displayControl = new FakeDisplayControl();
        FakeWaylandChannel channel = new FakeWaylandChannel();
        controller.bind(displayControl, channel);

        FakeSurfaceHolder holder = new FakeSurfaceHolder();
        controller.onSurfaceCreated(holder);

        // Background: onPause / onStop
        controller.onPause();
        assertEquals(WaylandSurfaceController.LifecycleState.PAUSED, controller.getLifecycleState());
        assertFalse(controller.isRenderingActive());

        controller.onStop();
        assertEquals(WaylandSurfaceController.LifecycleState.STOPPED, controller.getLifecycleState());

        // Foreground: onResume
        controller.onResume();
        assertEquals(WaylandSurfaceController.LifecycleState.RESUMED, controller.getLifecycleState());
        assertTrue(controller.isRenderingActive());

        // Destroy
        controller.onDestroy();
        assertEquals(WaylandSurfaceController.LifecycleState.DESTROYED, controller.getLifecycleState());
        assertFalse(controller.isBound());
    }

    @Test
    public void hudBadgeReportsAccurateStateAndFps() {
        TestPresenter presenter = new TestPresenter();
        TestScheduler scheduler = new TestScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, (cb) -> {});
        WaylandSurfaceController controller = new WaylandSurfaceController(renderer, scheduler);

        FakeDisplayControl displayControl = new FakeDisplayControl();
        FakeWaylandChannel channel = new FakeWaylandChannel();
        controller.bind(displayControl, channel);

        FakeSurfaceHolder holder = new FakeSurfaceHolder();
        controller.onSurfaceCreated(holder);
        controller.onSurfaceChanged(holder, 0, 2400, 1080);

        WaylandSurfaceController.HudBadgeInfo badge = controller.getHudBadgeInfo();
        assertNotNull(badge);
        assertEquals(WaylandChannel.ConnectionState.CONNECTED, badge.connectionState());
        assertEquals(2400, badge.viewportWidth());
        assertEquals(1080, badge.viewportHeight());
        assertTrue(badge.toBadgeText().contains("CONNECTED"));
        assertTrue(badge.toBadgeText().contains("2400x1080"));

        // Disconnect channel
        channel.notifyState(WaylandChannel.ConnectionState.DISCONNECTED, "Socket closed");
        WaylandSurfaceController.HudBadgeInfo disconnectedBadge = controller.getHudBadgeInfo();
        assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, disconnectedBadge.connectionState());
        assertTrue(disconnectedBadge.toBadgeText().contains("DISCONNECTED"));
    }

    @Test
    public void waylandSurfaceViewDelegatesToController() {
        TestPresenter presenter = new TestPresenter();
        TestScheduler scheduler = new TestScheduler();
        DoubleBufferedRenderer renderer = new DoubleBufferedRenderer(presenter, (cb) -> {});
        WaylandSurfaceController controller = new WaylandSurfaceController(renderer, scheduler);

        WaylandSurfaceView view = new WaylandSurfaceView(null, controller);
        assertEquals(controller, view.getController());

        FakeSurfaceHolder holder = new FakeSurfaceHolder();
        view.surfaceCreated(holder);
        assertEquals(WaylandSurfaceController.SurfaceState.CREATED, controller.getSurfaceState());

        view.surfaceChanged(holder, 0, 1920, 1080);
        assertEquals(WaylandSurfaceController.SurfaceState.CHANGED, controller.getSurfaceState());

        view.surfaceDestroyed(holder);
        assertEquals(WaylandSurfaceController.SurfaceState.DESTROYED, controller.getSurfaceState());
    }
}
