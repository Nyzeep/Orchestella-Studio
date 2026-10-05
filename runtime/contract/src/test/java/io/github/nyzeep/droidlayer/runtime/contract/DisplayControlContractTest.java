package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Strategy-neutral display control and Wayland graphics channel contract tests. */
public final class DisplayControlContractTest {
    public static void main(String[] arguments) {
        createsDisplayModeWithValidParameters();
        rejectsDisplayModeWithInvalidDimensions();
        rejectsDisplayModeWithInvalidScale();
        rejectsDisplayModeWithInvalidFps();
        rejectsDisplayModeWithInvalidDpi();
        displayModeSupportsWithModifiers();
        notifiesListenersOnModeAndResizeEvents();
        notifiesListenersOnScaleAndFpsEvents();
        listenerRemovalPreventsFurtherNotifications();
        waylandChannelManagesLifecycleAndStateTransitions();
        waylandChannelDispatchesFramesAndInputEvents();
        System.out.println("DISPLAY_CONTROL_CONTRACT_TEST_OK");
    }

    private static void createsDisplayModeWithValidParameters() {
        DisplayMode mode = new DisplayMode(1920, 1080, 1.5, 320, 60);
        assertEquals(1920, mode.width(), "width");
        assertEquals(1080, mode.height(), "height");
        assertEquals(1.5, mode.scale(), "scale");
        assertEquals(320, mode.dpi(), "dpi");
        assertEquals(60, mode.targetFps(), "targetFps");
    }

    private static void rejectsDisplayModeWithInvalidDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(99, 1080, 1.0, 320, 60),
                "width below 100 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(7681, 1080, 1.0, 320, 60),
                "width above 7680 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 99, 1.0, 320, 60),
                "height below 100 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 7681, 1.0, 320, 60),
                "height above 7680 must be rejected");
    }

    private static void rejectsDisplayModeWithInvalidScale() {
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 1080, 0.09, 320, 60),
                "scale below 0.1 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 1080, 5.01, 320, 60),
                "scale above 5.0 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 1080, Double.NaN, 320, 60),
                "NaN scale must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 1080, Double.POSITIVE_INFINITY, 320, 60),
                "infinite scale must be rejected");
    }

    private static void rejectsDisplayModeWithInvalidFps() {
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 1080, 1.0, 320, 0),
                "fps below 1 must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 1080, 1.0, 320, 241),
                "fps above 240 must be rejected");
    }

    private static void rejectsDisplayModeWithInvalidDpi() {
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 1080, 1.0, 0, 60),
                "non-positive dpi must be rejected");
        assertThrows(IllegalArgumentException.class,
                () -> new DisplayMode(1920, 1080, 1.0, -1, 60),
                "negative dpi must be rejected");
    }

    private static void displayModeSupportsWithModifiers() {
        DisplayMode original = new DisplayMode(1920, 1080, 1.0, 320, 60);
        DisplayMode resized = original.withDimensions(2560, 1440);
        assertEquals(2560, resized.width(), "resized width");
        assertEquals(1440, resized.height(), "resized height");
        assertEquals(1.0, resized.scale(), "preserved scale");
        assertEquals(320, resized.dpi(), "preserved dpi");
        assertEquals(60, resized.targetFps(), "preserved fps");

        DisplayMode rescaled = original.withScale(2.0);
        assertEquals(2.0, rescaled.scale(), "updated scale");
        assertEquals(1920, rescaled.width(), "preserved width");

        DisplayMode refps = original.withFps(120);
        assertEquals(120, refps.targetFps(), "updated fps");
        assertEquals(1080, refps.height(), "preserved height");
    }

    private static void notifiesListenersOnModeAndResizeEvents() {
        SimpleDisplayControl control = new SimpleDisplayControl(
                new DisplayMode(1280, 720, 1.0, 160, 60));

        AtomicReference<DisplayMode> modeRef = new AtomicReference<>();
        AtomicInteger resizedW = new AtomicInteger();
        AtomicInteger resizedH = new AtomicInteger();

        control.addDisplayListener(new DisplayListener() {
            @Override
            public void onModeChanged(DisplayMode mode) {
                modeRef.set(mode);
            }

            @Override
            public void onDisplayResized(int width, int height) {
                resizedW.set(width);
                resizedH.set(height);
            }

            @Override
            public void onFpsUpdated(int currentFps) {}
        });

        boolean success = control.resizeDisplay(1920, 1080);
        assertTrue(success, "resizeDisplay must succeed for valid dimension");
        assertEquals(1920, control.currentMode().width(), "currentMode width");
        assertEquals(1080, control.currentMode().height(), "currentMode height");
        assertEquals(1920, resizedW.get(), "listener resized width");
        assertEquals(1080, resizedH.get(), "listener resized height");
        assertEquals(1920, modeRef.get().width(), "listener mode width");

        assertFalse(control.resizeDisplay(50, 50), "resizeDisplay must fail for invalid dimension");
    }

    private static void notifiesListenersOnScaleAndFpsEvents() {
        SimpleDisplayControl control = new SimpleDisplayControl(
                new DisplayMode(1920, 1080, 1.0, 320, 60));

        AtomicReference<DisplayMode> modeRef = new AtomicReference<>();
        AtomicInteger fpsRef = new AtomicInteger();

        control.addDisplayListener(new DisplayListener() {
            @Override
            public void onModeChanged(DisplayMode mode) {
                modeRef.set(mode);
            }

            @Override
            public void onDisplayResized(int width, int height) {}

            @Override
            public void onFpsUpdated(int currentFps) {
                fpsRef.set(currentFps);
            }
        });

        assertTrue(control.updateScale(2.5), "updateScale must succeed");
        assertEquals(2.5, control.currentMode().scale(), "scale updated");
        assertEquals(2.5, modeRef.get().scale(), "listener received updated scale mode");

        assertTrue(control.updateTargetFps(144), "updateTargetFps must succeed");
        assertEquals(144, control.currentMode().targetFps(), "fps updated");
        assertEquals(144, fpsRef.get(), "listener received updated fps");

        assertFalse(control.updateScale(10.0), "updateScale must fail for out of bounds scale");
        assertFalse(control.updateTargetFps(300), "updateTargetFps must fail for out of bounds fps");
    }

    private static void listenerRemovalPreventsFurtherNotifications() {
        SimpleDisplayControl control = new SimpleDisplayControl(
                new DisplayMode(1920, 1080, 1.0, 320, 60));

        AtomicInteger notifications = new AtomicInteger();
        DisplayListener listener = new DisplayListener() {
            @Override
            public void onModeChanged(DisplayMode mode) {
                notifications.incrementAndGet();
            }

            @Override
            public void onDisplayResized(int width, int height) {
                notifications.incrementAndGet();
            }

            @Override
            public void onFpsUpdated(int currentFps) {
                notifications.incrementAndGet();
            }
        };

        control.addDisplayListener(listener);
        control.resizeDisplay(1280, 720);
        assertTrue(notifications.get() > 0, "notifications must have fired");

        int countBefore = notifications.get();
        control.removeDisplayListener(listener);
        control.resizeDisplay(1920, 1080);
        assertEquals(countBefore, notifications.get(), "no notifications after removal");
    }

    private static void waylandChannelManagesLifecycleAndStateTransitions() {
        SimpleWaylandChannel channel = new SimpleWaylandChannel();
        assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state(), "initial state");
        assertFalse(channel.isConnected(), "initially not connected");

        AtomicReference<WaylandChannel.ConnectionState> stateRef = new AtomicReference<>();
        AtomicReference<String> msgRef = new AtomicReference<>();
        channel.addStateListener((newState, message) -> {
            stateRef.set(newState);
            msgRef.set(message);
        });

        channel.transitionTo(WaylandChannel.ConnectionState.CONNECTED, "Wayland socket connected");
        assertEquals(WaylandChannel.ConnectionState.CONNECTED, channel.state(), "connected state");
        assertTrue(channel.isConnected(), "isConnected returns true");
        assertEquals(WaylandChannel.ConnectionState.CONNECTED, stateRef.get(), "state listener notified");
        assertEquals("Wayland socket connected", msgRef.get(), "state listener message");

        channel.transitionTo(WaylandChannel.ConnectionState.ERROR, "Transport error");
        assertEquals(WaylandChannel.ConnectionState.ERROR, channel.state(), "error state");
        assertFalse(channel.isConnected(), "not connected in error");

        channel.close();
        assertEquals(WaylandChannel.ConnectionState.DISCONNECTED, channel.state(), "disconnected on close");
    }

    private static void waylandChannelDispatchesFramesAndInputEvents() {
        SimpleWaylandChannel channel = new SimpleWaylandChannel();
        channel.transitionTo(WaylandChannel.ConnectionState.CONNECTED, "ready");

        AtomicReference<WaylandChannel.FrameData> frameRef = new AtomicReference<>();
        channel.registerFrameListener(frameRef::set);

        byte[] pixels = new byte[] {1, 2, 3, 4};
        WaylandChannel.FrameData frame = new WaylandChannel.FrameData(
                1920, 1080, 7680, WaylandChannel.FrameData.FORMAT_RGBA_8888, pixels, 1L, 1000000L);
        channel.dispatchFrame(frame);

        assertEquals(1920, frameRef.get().width(), "frame width");
        assertEquals(1080, frameRef.get().height(), "frame height");
        assertEquals(7680, frameRef.get().stride(), "frame stride");
        assertEquals(WaylandChannel.FrameData.FORMAT_RGBA_8888, frameRef.get().format(), "frame format");
        assertEquals(1L, frameRef.get().frameId(), "frame index");

        PointerEvent event = PointerEvent.motion(100.0, 200.0, 5.0, -3.0, 5000L);
        assertTrue(channel.sendInputEvent(event), "sendInputEvent must succeed when connected");
        assertEquals(event, channel.lastInputEvent(), "channel received sent input event");

        channel.transitionTo(WaylandChannel.ConnectionState.DISCONNECTED, "closed");
        assertFalse(channel.sendInputEvent(event), "sendInputEvent must fail when disconnected");
    }

    private static final class SimpleDisplayControl implements DisplayControl {
        private DisplayMode currentMode;
        private final List<DisplayListener> listeners = new ArrayList<>();

        SimpleDisplayControl(DisplayMode initialMode) {
            this.currentMode = initialMode;
        }

        @Override
        public DisplayMode currentMode() {
            return currentMode;
        }

        @Override
        public boolean resizeDisplay(int width, int height) {
            if (width < 100 || width > 7680 || height < 100 || height > 7680) {
                return false;
            }
            this.currentMode = currentMode.withDimensions(width, height);
            for (DisplayListener listener : new ArrayList<>(listeners)) {
                listener.onDisplayResized(width, height);
                listener.onModeChanged(currentMode);
            }
            return true;
        }

        @Override
        public boolean updateScale(double scale) {
            if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < 0.1 || scale > 5.0) {
                return false;
            }
            this.currentMode = currentMode.withScale(scale);
            for (DisplayListener listener : new ArrayList<>(listeners)) {
                listener.onModeChanged(currentMode);
            }
            return true;
        }

        @Override
        public boolean updateTargetFps(int targetFps) {
            if (targetFps < 1 || targetFps > 240) {
                return false;
            }
            this.currentMode = currentMode.withFps(targetFps);
            for (DisplayListener listener : new ArrayList<>(listeners)) {
                listener.onFpsUpdated(targetFps);
                listener.onModeChanged(currentMode);
            }
            return true;
        }

        @Override
        public void addDisplayListener(DisplayListener listener) {
            if (listener != null && !listeners.contains(listener)) {
                listeners.add(listener);
            }
        }

        @Override
        public void removeDisplayListener(DisplayListener listener) {
            listeners.remove(listener);
        }
    }

    private static final class SimpleWaylandChannel implements WaylandChannel {
        private ConnectionState state = ConnectionState.DISCONNECTED;
        private final List<StateListener> stateListeners = new ArrayList<>();
        private final List<FrameListener> frameListeners = new ArrayList<>();
        private InputEvent lastEvent;

        @Override
        public ConnectionState state() {
            return state;
        }

        @Override
        public void addStateListener(StateListener listener) {
            if (listener != null && !stateListeners.contains(listener)) {
                stateListeners.add(listener);
            }
        }

        @Override
        public void removeStateListener(StateListener listener) {
            stateListeners.remove(listener);
        }

        @Override
        public void registerFrameListener(FrameListener listener) {
            if (listener != null && !frameListeners.contains(listener)) {
                frameListeners.add(listener);
            }
        }

        @Override
        public void unregisterFrameListener(FrameListener listener) {
            frameListeners.remove(listener);
        }

        @Override
        public boolean sendInputEvent(InputEvent event) {
            if (!isConnected() || event == null) {
                return false;
            }
            this.lastEvent = event;
            return true;
        }

        public InputEvent lastInputEvent() {
            return lastEvent;
        }

        public void transitionTo(ConnectionState newState, String message) {
            this.state = newState;
            for (StateListener listener : new ArrayList<>(stateListeners)) {
                listener.onStateChanged(newState, message);
            }
        }

        public void dispatchFrame(FrameData frame) {
            for (FrameListener listener : new ArrayList<>(frameListeners)) {
                listener.onFrameAvailable(frame);
            }
        }

        @Override
        public void close() {
            transitionTo(ConnectionState.DISCONNECTED, "Channel closed");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static <T extends Throwable> T assertThrows(
            Class<T> type, ThrowingRunnable operation, String description) {
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (type.isInstance(throwable)) {
                return type.cast(throwable);
            }
            throw new AssertionError(description + ": unexpected exception", throwable);
        }
        throw new AssertionError(description + ": expected exception " + type.getName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
