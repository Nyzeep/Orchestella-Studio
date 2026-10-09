package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.TouchGestureMapper.TouchMode;
import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.PointerEvent;
import io.github.nyzeep.orchestella.runtime.contract.TouchEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests validating coordinate translation, dynamic acceleration curve,
 * cursor bounding, and behavioral differences between Touchscreen and Trackpad modes.
 */
public final class TouchscreenTrackpadModeTest {

    private FakeWaylandChannel channel;
    private ViewportAspectCalculator.ViewportMapping mapping;
    private TouchGestureMapper mapper;

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
        channel = new FakeWaylandChannel();
        // Content: 1920x1080, Viewport: 1920x1080 (Exact fit 1:1)
        mapping = ViewportAspectCalculator.calculate(1920, 1080, 1920, 1080);
        mapper = new TouchGestureMapper(channel, mapping);
    }

    @Test
    public void testDefaultModeIsTrackpad() {
        assertEquals(TouchMode.TRACKPAD, mapper.getTouchMode());
    }

    @Test
    public void testTouchscreenModeEmitsNormalizedTouchEvents() {
        mapper.setTouchMode(TouchMode.TOUCHSCREEN);
        assertEquals(TouchMode.TOUCHSCREEN, mapper.getTouchMode());

        long t0 = 1000000000L;
        // Touch down at (960, 540) -> exactly center (0.5, 0.5)
        mapper.onDown(0, 960f, 540f, t0);

        // Move to (1440, 810) -> (0.75, 0.75)
        mapper.onMove(0, 1440f, 810f, t0 + 50_000_000L);

        // Up at (1440, 810)
        mapper.onUp(0, 1440f, 810f, t0 + 100_000_000L);

        List<TouchEvent> touchEvents = new ArrayList<>();
        for (InputEvent ev : channel.events) {
            if (ev instanceof TouchEvent) {
                touchEvents.add((TouchEvent) ev);
            }
        }

        assertTrue("Must generate TouchEvents in touchscreen mode", touchEvents.size() >= 3);
        TouchEvent down = touchEvents.get(0);
        assertEquals(TouchEvent.Action.DOWN, down.action());
        assertEquals(0.5, down.x(), 0.01);
        assertEquals(0.5, down.y(), 0.01);

        TouchEvent move = touchEvents.get(1);
        assertEquals(TouchEvent.Action.MOVE, move.action());
        assertEquals(0.75, move.x(), 0.01);
        assertEquals(0.75, move.y(), 0.01);

        TouchEvent up = touchEvents.get(2);
        assertEquals(TouchEvent.Action.UP, up.action());
        assertEquals(0.75, up.x(), 0.01);
        assertEquals(0.75, up.y(), 0.01);
    }

    @Test
    public void testTouchscreenModeLetterboxCoordinateMapping() {
        // Content: 1920x1080. Viewport: 1080x2400.
        // Letterbox bars on top/bottom: destWidth = 1080, destHeight = 608, destX = 0, destY = 896
        ViewportAspectCalculator.ViewportMapping letterboxMapping =
                ViewportAspectCalculator.calculate(1920, 1080, 1080, 2400);
        mapper.setViewportMapping(letterboxMapping);
        mapper.setTouchMode(TouchMode.TOUCHSCREEN);

        long t0 = 1000000000L;
        // Touch down at (540, 896 + 304 = 1200) -> center of active content!
        mapper.onDown(0, 540f, 1200f, t0);
        mapper.onUp(0, 540f, 1200f, t0 + 20_000_000L);

        TouchEvent downEvent = null;
        for (InputEvent ev : channel.events) {
            if (ev instanceof TouchEvent && ((TouchEvent) ev).action() == TouchEvent.Action.DOWN) {
                downEvent = (TouchEvent) ev;
                break;
            }
        }
        assertNotNull(downEvent);
        assertEquals(0.5, downEvent.x(), 0.02);
        assertEquals(0.5, downEvent.y(), 0.02);
    }

    @Test
    public void testTrackpadModeMaintainsVirtualCursorDisplacement() {
        mapper.setTouchMode(TouchMode.TRACKPAD);
        // Cursor starts at center of content (960, 540)
        double initialCursorX = mapper.getCursorX();
        double initialCursorY = mapper.getCursorY();
        assertEquals(960.0, initialCursorX, 1.0);
        assertEquals(540.0, initialCursorY, 1.0);

        long t0 = 1000000000L;
        // Finger down at viewport (200, 200)
        mapper.onDown(0, 200f, 200f, t0);
        // Move finger by +50 in X and +30 in Y slowly (interval 100ms)
        mapper.onMove(0, 250f, 230f, t0 + 100_000_000L);

        // Verify PointerEvent.motion is generated
        PointerEvent motionEvent = null;
        for (InputEvent ev : channel.events) {
            if (ev instanceof PointerEvent && ((PointerEvent) ev).action() == PointerEvent.Action.MOVE) {
                motionEvent = (PointerEvent) ev;
            }
        }
        assertNotNull(motionEvent);
        // Cursor moved to approximately 960 + 50, 540 + 30
        assertTrue("Cursor X must move forward", mapper.getCursorX() > initialCursorX);
        assertTrue("Cursor Y must move forward", mapper.getCursorY() > initialCursorY);
        assertTrue(motionEvent.deltaX() > 0);
        assertTrue(motionEvent.deltaY() > 0);
    }

    @Test
    public void testTrackpadDynamicAccelerationCurve() {
        mapper.setTouchMode(TouchMode.TRACKPAD);

        // 1. Slow displacement: move 100px over 500ms -> low velocity
        mapper.setCursorPosition(960.0, 540.0);
        mapper.onDown(0, 100f, 100f, 1000_000_000L);
        mapper.onMove(0, 200f, 100f, 1500_000_000L); // 500ms
        double slowTravel = mapper.getCursorX() - 960.0;
        mapper.onUp(0, 200f, 100f, 1510_000_000L);

        // 2. Fast displacement: move 100px over 20ms -> high velocity
        mapper.setCursorPosition(960.0, 540.0);
        mapper.onDown(0, 100f, 100f, 2000_000_000L);
        mapper.onMove(0, 200f, 100f, 2020_000_000L); // 20ms
        double fastTravel = mapper.getCursorX() - 960.0;
        mapper.onUp(0, 200f, 100f, 2025_000_000L);

        // Fast swipe should travel significantly further due to dynamic acceleration
        assertTrue("Fast flick travel (" + fastTravel + ") must exceed slow travel (" + slowTravel + ")",
                fastTravel > slowTravel);
    }

    @Test
    public void testTrackpadCursorClampedToContentBounds() {
        mapper.setTouchMode(TouchMode.TRACKPAD);
        mapper.setCursorPosition(10.0, 10.0);

        long t0 = 1000000000L;
        // Big swipe toward negative X and Y
        mapper.onDown(0, 500f, 500f, t0);
        mapper.onMove(0, 0f, 0f, t0 + 20_000_000L); // -500px

        // Virtual cursor must be clamped to 0.0
        assertEquals(0.0, mapper.getCursorX(), 0.001);
        assertEquals(0.0, mapper.getCursorY(), 0.001);

        // Big swipe toward far positive X and Y
        mapper.onMove(0, 2000f, 2000f, t0 + 40_000_000L);
        assertEquals(1920.0, mapper.getCursorX(), 0.001);
        assertEquals(1080.0, mapper.getCursorY(), 0.001);
    }

    @Test
    public void testRuntimeModeSwitchingPreservesStability() {
        // Start in Trackpad mode
        mapper.setTouchMode(TouchMode.TRACKPAD);
        mapper.onDown(0, 100f, 100f, 1000_000_000L);
        mapper.onMove(0, 150f, 150f, 1050_000_000L);
        mapper.onUp(0, 150f, 150f, 1100_000_000L);

        // Switch to Touchscreen mode
        mapper.setTouchMode(TouchMode.TOUCHSCREEN);
        assertEquals(TouchMode.TOUCHSCREEN, mapper.getTouchMode());

        mapper.onDown(0, 500f, 500f, 2000_000_000L);
        mapper.onMove(0, 550f, 550f, 2050_000_000L);
        mapper.onUp(0, 550f, 550f, 2100_000_000L);

        // Switch back to Trackpad mode
        mapper.setTouchMode(TouchMode.TRACKPAD);
        assertEquals(TouchMode.TRACKPAD, mapper.getTouchMode());
        assertTrue(mapper.getCursorX() >= 0.0 && mapper.getCursorX() <= 1920.0);
        assertTrue(mapper.getCursorY() >= 0.0 && mapper.getCursorY() <= 1080.0);
    }
}
