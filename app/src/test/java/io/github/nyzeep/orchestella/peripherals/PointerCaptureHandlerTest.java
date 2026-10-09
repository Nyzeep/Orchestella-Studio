package io.github.nyzeep.orchestella.peripherals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.PointerEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for PointerCaptureHandler:
 * - requestCapture / releaseCapture lifecycle
 * - Automatic cursor hiding (TYPE_NULL) and restoration
 * - Captured relative motion dispatched to WaylandChannel
 * - Captured scroll wheel dispatched to WaylandChannel
 * - Ctrl + Alt shortcut triggers deterministic release
 * - Capture state change listener notifications
 */
public final class PointerCaptureHandlerTest {

    private FakeViewSeam viewSeam;
    private FakeWaylandChannel waylandChannel;
    private PointerCaptureHandler handler;
    private final List<Boolean> stateHistory = new ArrayList<>();

    private static final class FakeViewSeam implements PointerCaptureHandler.PointerCaptureViewSeam {
        boolean captured = false;
        boolean nullIconSet = false;
        int requestCount = 0;
        int releaseCount = 0;

        @Override
        public void requestPointerCapture() {
            captured = true;
            requestCount++;
        }

        @Override
        public void releasePointerCapture() {
            captured = false;
            releaseCount++;
        }

        @Override
        public boolean hasPointerCapture() {
            return captured;
        }

        @Override
        public void setPointerIconNull() {
            nullIconSet = true;
        }

        @Override
        public void restorePointerIcon() {
            nullIconSet = false;
        }
    }

    private static final class FakeWaylandChannel implements WaylandChannel {
        final List<InputEvent> events = new ArrayList<>();
        ConnectionState state = ConnectionState.CONNECTED;

        @Override
        public ConnectionState state() {
            return state;
        }

        @Override
        public void addStateListener(StateListener listener) {}

        @Override
        public void removeStateListener(StateListener listener) {}

        @Override
        public void registerFrameListener(FrameListener listener) {}

        @Override
        public void unregisterFrameListener(FrameListener listener) {}

        @Override
        public boolean sendInputEvent(InputEvent event) {
            events.add(event);
            return true;
        }

        @Override
        public void close() {
            state = ConnectionState.DISCONNECTED;
        }
    }

    @Before
    public void setUp() {
        viewSeam = new FakeViewSeam();
        waylandChannel = new FakeWaylandChannel();
        stateHistory.clear();

        handler = new PointerCaptureHandler(viewSeam, waylandChannel::sendInputEvent);
        handler.addCaptureStateListener(stateHistory::add);
    }

    @Test
    public void testInitialState() {
        assertFalse(handler.isCaptured());
        assertFalse(viewSeam.hasPointerCapture());
        assertFalse(viewSeam.nullIconSet);
    }

    @Test
    public void testRequestAndReleaseCapture() {
        handler.requestCapture();

        assertTrue(handler.isCaptured());
        assertTrue(viewSeam.hasPointerCapture());
        assertTrue(viewSeam.nullIconSet);
        assertEquals(1, stateHistory.size());
        assertTrue(stateHistory.get(0));

        handler.releaseCapture();

        assertFalse(handler.isCaptured());
        assertFalse(viewSeam.hasPointerCapture());
        assertFalse(viewSeam.nullIconSet);
        assertEquals(2, stateHistory.size());
        assertFalse(stateHistory.get(1));
    }

    @Test
    public void testCapturedRelativeMotionDispatched() {
        handler.requestCapture();

        boolean sent = handler.onCapturedMotion(10.5f, -20.25f, PointerEvent.BUTTON_PRIMARY, 1000L);
        assertTrue(sent);
        assertEquals(1, waylandChannel.events.size());

        InputEvent event = waylandChannel.events.get(0);
        assertTrue(event instanceof PointerEvent);
        PointerEvent pe = (PointerEvent) event;
        assertTrue(pe.isRelativeMotion());
        assertEquals(10.5, pe.deltaX(), 0.001);
        assertEquals(-20.25, pe.deltaY(), 0.001);
        assertEquals(PointerEvent.BUTTON_PRIMARY, pe.button());
    }

    @Test
    public void testCapturedScrollDispatched() {
        handler.requestCapture();

        boolean sent = handler.onCapturedScroll(0.0f, 15.0f, 2000L);
        assertTrue(sent);
        assertEquals(1, waylandChannel.events.size());

        InputEvent event = waylandChannel.events.get(0);
        assertTrue(event instanceof PointerEvent);
        PointerEvent pe = (PointerEvent) event;
        assertEquals(PointerEvent.Action.SCROLL, pe.action());
        assertEquals(15.0, pe.deltaY(), 0.001);
    }

    @Test
    public void testCtrlAltShortcutReleasesCapture() {
        handler.requestCapture();
        assertTrue(handler.isCaptured());

        // Normal key does not release
        boolean handledNormal = handler.onKey(65 /* KEYCODE_A */, 0 /* no meta */, 3000L);
        assertFalse(handledNormal);
        assertTrue(handler.isCaptured());

        // Ctrl + Alt shortcut triggers release
        int ctrlAltMeta = 0x1000 | 0x02;
        boolean handledShortcut = handler.onKey(0, ctrlAltMeta, 4000L);
        assertTrue(handledShortcut);
        assertFalse("Handler must be released after Ctrl+Alt", handler.isCaptured());
        assertFalse(viewSeam.hasPointerCapture());
        assertFalse(viewSeam.nullIconSet);
    }
}
