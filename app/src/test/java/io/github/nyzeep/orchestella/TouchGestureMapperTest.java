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
 * Unit tests for TouchGestureMapper verifying recognition accuracy for:
 * - Single-finger tap (left click)
 * - Single-finger drag/move
 * - Long press (right click)
 * - Two-finger tap (right click)
 * - Two-finger scroll (wheel axis)
 * - TouchEvent generation in touchscreen mode
 */
public final class TouchGestureMapperTest {

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
        // 1920x1080 content, 1920x1080 viewport (1:1 scale)
        mapping = ViewportAspectCalculator.calculate(1920, 1080, 1920, 1080);
        mapper = new TouchGestureMapper(channel, mapping);
    }

    @Test
    public void testSingleFingerTapGeneratesLeftClick() {
        long t0 = 1_000_000_000L;
        // Press at (500, 400)
        mapper.onDown(0, 500f, 400f, t0);
        // Release 120ms later at (502, 401) (displacement ~2.2px < 8dp)
        mapper.onUp(0, 502f, 401f, t0 + 120_000_000L);

        List<PointerEvent> buttons = new ArrayList<>();
        for (InputEvent ev : channel.events) {
            if (ev instanceof PointerEvent) {
                PointerEvent pe = (PointerEvent) ev;
                if (pe.action() == PointerEvent.Action.BUTTON_DOWN
                        || pe.action() == PointerEvent.Action.BUTTON_UP) {
                    buttons.add(pe);
                }
            }
        }

        // Must generate BTN_LEFT DOWN followed immediately by UP
        assertEquals(2, buttons.size());
        PointerEvent down = buttons.get(0);
        PointerEvent up = buttons.get(1);

        assertEquals(PointerEvent.Action.BUTTON_DOWN, down.action());
        assertEquals(PointerEvent.BUTTON_LEFT, down.button());

        assertEquals(PointerEvent.Action.BUTTON_UP, up.action());
        assertEquals(PointerEvent.BUTTON_LEFT, up.button());
    }

    @Test
    public void testSingleFingerTapExceeding200msDoesNotTriggerTap() {
        long t0 = 1_000_000_000L;
        mapper.onDown(0, 500f, 400f, t0);
        // Release 280ms later (exceeds 200ms window, but before 500ms long press)
        mapper.onUp(0, 500f, 400f, t0 + 280_000_000L);

        boolean hasLeftClick = false;
        for (InputEvent ev : channel.events) {
            if (ev instanceof PointerEvent) {
                PointerEvent pe = (PointerEvent) ev;
                if (pe.button() == PointerEvent.BUTTON_LEFT) {
                    hasLeftClick = true;
                    break;
                }
            }
        }
        assertFalse("Hold exceeding 200ms must not trigger tap left click", hasLeftClick);
    }

    @Test
    public void testSingleFingerTapExceedingSlopDoesNotTriggerTap() {
        long t0 = 1_000_000_000L;
        mapper.onDown(0, 500f, 400f, t0);
        // Move by 20px (> 8dp)
        mapper.onMove(0, 520f, 400f, t0 + 50_000_000L);
        mapper.onUp(0, 520f, 400f, t0 + 100_000_000L);

        boolean hasLeftClick = false;
        for (InputEvent ev : channel.events) {
            if (ev instanceof PointerEvent) {
                PointerEvent pe = (PointerEvent) ev;
                if (pe.button() == PointerEvent.BUTTON_LEFT) {
                    hasLeftClick = true;
                    break;
                }
            }
        }
        assertFalse("Movement exceeding slop must not trigger tap left click", hasLeftClick);
    }

    @Test
    public void testSingleFingerDragEmitsPointerMotion() {
        long t0 = 1_000_000_000L;
        mapper.onDown(0, 100f, 100f, t0);
        mapper.onMove(0, 150f, 120f, t0 + 50_000_000L);
        mapper.onMove(0, 200f, 140f, t0 + 100_000_000L);
        mapper.onUp(0, 200f, 140f, t0 + 150_000_000L);

        List<PointerEvent> moves = new ArrayList<>();
        for (InputEvent ev : channel.events) {
            if (ev instanceof PointerEvent) {
                PointerEvent pe = (PointerEvent) ev;
                if (pe.action() == PointerEvent.Action.MOVE) {
                    moves.add(pe);
                }
            }
        }

        assertTrue("Must generate pointer MOVE events for drag", moves.size() >= 2);
    }

    @Test
    public void testLongPressTriggersRightClick() {
        long t0 = 1_000_000_000L;
        mapper.onDown(0, 300f, 300f, t0);

        // Advance time to 550ms with no movement (long press timeout is 500ms)
        mapper.onMove(0, 301f, 300f, t0 + 550_000_000L);

        List<PointerEvent> rightClicks = new ArrayList<>();
        for (InputEvent ev : channel.events) {
            if (ev instanceof PointerEvent) {
                PointerEvent pe = (PointerEvent) ev;
                if (pe.button() == PointerEvent.BUTTON_RIGHT) {
                    rightClicks.add(pe);
                }
            }
        }

        assertTrue("Long press must generate right click", rightClicks.size() >= 1);
        assertEquals(PointerEvent.BUTTON_RIGHT, rightClicks.get(0).button());

        // When finger is finally released, it must NOT trigger a tap
        channel.events.clear();
        mapper.onUp(0, 301f, 300f, t0 + 600_000_000L);

        for (InputEvent ev : channel.events) {
            if (ev instanceof PointerEvent) {
                PointerEvent pe = (PointerEvent) ev;
                assertFalse("Must not trigger left tap after long press",
                        pe.button() == PointerEvent.BUTTON_LEFT);
            }
        }
    }

    @Test
    public void testTwoFingerTapTriggersRightClick() {
        long t0 = 1_000_000_000L;
        // Finger 0 down
        mapper.onDown(0, 400f, 400f, t0);
        // Finger 1 down 30ms later
        mapper.onPointerDown(1, 460f, 410f, t0 + 30_000_000L);

        // Both fingers lift within 150ms (< 250ms threshold)
        mapper.onPointerUp(1, 460f, 410f, t0 + 120_000_000L);
        mapper.onUp(0, 400f, 400f, t0 + 130_000_000L);

        List<PointerEvent> rightClicks = new ArrayList<>();
        for (InputEvent ev : channel.events) {
            if (ev instanceof PointerEvent) {
                PointerEvent pe = (PointerEvent) ev;
                if (pe.button() == PointerEvent.BUTTON_RIGHT) {
                    rightClicks.add(pe);
                }
            }
        }

        assertEquals("Two-finger tap must generate BTN_RIGHT DOWN and UP", 2, rightClicks.size());
        assertEquals(PointerEvent.Action.BUTTON_DOWN, rightClicks.get(0).action());
        assertEquals(PointerEvent.Action.BUTTON_UP, rightClicks.get(1).action());
    }

    @Test
    public void testTwoFingerScrollGeneratesAxisEvents() {
        long t0 = 1_000_000_000L;
        // Both fingers down
        mapper.onDown(0, 500f, 600f, t0);
        mapper.onPointerDown(1, 550f, 600f, t0 + 10_000_000L);

        // Slide two fingers downward in Y by +80px (scrolling)
        int[] ids = new int[]{0, 1};
        float[] xs = new float[]{500f, 550f};
        float[] ys = new float[]{680f, 680f};
        mapper.onTwoFingerMove(ids, xs, ys, t0 + 50_000_000L);

        List<PointerEvent> scrolls = new ArrayList<>();
        for (InputEvent ev : channel.events) {
            if (ev instanceof PointerEvent) {
                PointerEvent pe = (PointerEvent) ev;
                if (pe.action() == PointerEvent.Action.SCROLL) {
                    scrolls.add(pe);
                }
            }
        }

        assertTrue("Two finger drag must generate scroll events", scrolls.size() >= 1);
        PointerEvent scroll = scrolls.get(0);
        assertEquals(PointerEvent.Action.SCROLL, scroll.action());
        assertTrue("deltaY must be non-zero", Math.abs(scroll.deltaY()) > 0);
    }

    @Test
    public void testTouchOutsideContentInLetterboxIsSafelyIgnored() {
        // Content: 1920x1080. Viewport: 1080x2400 (bars top y < 896, bottom y >= 1504)
        ViewportAspectCalculator.ViewportMapping letterboxMapping =
                ViewportAspectCalculator.calculate(1920, 1080, 1080, 2400);
        mapper.setViewportMapping(letterboxMapping);
        mapper.setTouchMode(TouchMode.TOUCHSCREEN);

        long t0 = 1_000_000_000L;
        // Click at top black bar (y = 100, which is < 896 destY)
        mapper.onDown(0, 500f, 100f, t0);
        mapper.onUp(0, 500f, 100f, t0 + 50_000_000L);

        // Should not dispatch any events because it's outside content
        assertEquals(0, channel.events.size());
    }

    @Test
    public void testTwoFingerPinchGeneratesGestureEventAndNotifiesListener() {
        long t0 = 1_000_000_000L;
        // Two fingers 100px apart: (400, 500) and (500, 500)
        mapper.onDown(0, 400f, 500f, t0);
        mapper.onPointerDown(1, 500f, 500f, t0 + 10_000_000L);

        final java.util.concurrent.atomic.AtomicBoolean listenerCalled = new java.util.concurrent.atomic.AtomicBoolean(false);
        mapper.setPinchListener((scale, fx, fy) -> {
            listenerCalled.set(true);
            assertEquals(1.5, scale, 0.05);
            assertEquals(450.0, fx, 1.0);
            assertEquals(500.0, fy, 1.0);
        });

        // Spread fingers to 150px apart: (375, 500) and (525, 500) -> 50% change > 15%
        int[] ids = new int[]{0, 1};
        float[] xs = new float[]{375f, 525f};
        float[] ys = new float[]{500f, 500f};
        mapper.onTwoFingerMove(ids, xs, ys, t0 + 50_000_000L);

        assertTrue("Pinch listener must be called", listenerCalled.get());

        io.github.nyzeep.orchestella.runtime.contract.GestureEvent pinchEvent = null;
        for (InputEvent ev : channel.events) {
            if (ev instanceof io.github.nyzeep.orchestella.runtime.contract.GestureEvent) {
                pinchEvent = (io.github.nyzeep.orchestella.runtime.contract.GestureEvent) ev;
                break;
            }
        }

        assertNotNull("GestureEvent must be dispatched on channel", pinchEvent);
        assertEquals(io.github.nyzeep.orchestella.runtime.contract.GestureEvent.GestureType.PINCH, pinchEvent.gestureType());
        assertEquals(1.5, pinchEvent.scaleFactor(), 0.05);
    }

    @Test
    public void testToggleTouchMode() {
        assertEquals(TouchMode.TRACKPAD, mapper.getTouchMode());
        TouchMode mode1 = mapper.toggleTouchMode();
        assertEquals(TouchMode.TOUCHSCREEN, mode1);
        assertEquals(TouchMode.TOUCHSCREEN, mapper.getTouchMode());

        TouchMode mode2 = mapper.toggleTouchMode();
        assertEquals(TouchMode.TRACKPAD, mode2);
        assertEquals(TouchMode.TRACKPAD, mapper.getTouchMode());
    }

    @Test
    public void testDampingFactorScalesPointerMotion() {
        mapper.setTouchMode(TouchMode.TRACKPAD);
        mapper.setCursorPosition(500.0, 500.0);
        mapper.setDampingFactor(1.0);

        long t0 = 1_000_000_000L;
        mapper.onDown(0, 100f, 100f, t0);
        mapper.onMove(0, 150f, 100f, t0 + 20_000_000L);
        double normalDeltaX = mapper.getCursorX() - 500.0;

        // Reset and test with 0.5 damping factor
        mapper.onUp(0, 150f, 100f, t0 + 30_000_000L);
        mapper.setCursorPosition(500.0, 500.0);
        mapper.setDampingFactor(0.5);

        mapper.onDown(0, 100f, 100f, t0 + 100_000_000L);
        mapper.onMove(0, 150f, 100f, t0 + 120_000_000L);
        double dampedDeltaX = mapper.getCursorX() - 500.0;

        assertEquals(normalDeltaX * 0.5, dampedDeltaX, 0.01);
    }

    @Test
    public void testJitterDeadzoneSuppressesMicroMotion() {
        mapper.setTouchMode(TouchMode.TRACKPAD);
        mapper.setCursorPosition(500.0, 500.0);
        mapper.setJitterDeadzonePx(1.0);
        channel.events.clear();

        long t0 = 1_000_000_000L;
        mapper.onDown(0, 100f, 100f, t0);
        // Small 0.3px micro movement < 1.0px deadzone
        mapper.onMove(0, 100.3f, 100f, t0 + 10_000_000L);

        assertEquals(500.0, mapper.getCursorX(), 0.001);
        assertTrue(channel.events.isEmpty());

        // Legitimate movement > 1.0px deadzone
        mapper.onMove(0, 110f, 100f, t0 + 20_000_000L);
        assertTrue(mapper.getCursorX() > 500.0);
        assertFalse(channel.events.isEmpty());
    }
}
