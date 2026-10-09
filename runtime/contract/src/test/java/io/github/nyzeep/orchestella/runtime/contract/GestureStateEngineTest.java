package io.github.nyzeep.orchestella.runtime.contract;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 4.8 Task 2: Strategy-neutral pure JVM contract test for {@link GestureStateEngine}.
 * Verifies dual-mode touch state machine, multi-touch gestures, and event dispatching.
 */
public final class GestureStateEngineTest {

    public static void main(String[] args) {
        testInitialStateAndDefaults();
        testDirectTouchTapGeneratesLeftClickAndTouchEvents();
        testDirectTouchDragEmitsTouchMoveAndMotion();
        testVirtualTrackpadMotionMovesCursorWithAcceleration();
        testLongPressGeneratesRightClick();
        testTwoFingerTapGeneratesRightClick();
        testTwoFingerVerticalScrollGeneratesScrollEvent();
        testTwoFingerPinchGeneratesGestureEvent();
        testModeSwitchBetweenDirectTouchAndVirtualTrackpad();
        testDisplayBoundaryClamping();
        System.out.println("GESTURE_STATE_ENGINE_TEST_OK");
    }

    private static void testInitialStateAndDefaults() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        assertEquals(GestureStateEngine.TouchMode.VIRTUAL_TRACKPAD, engine.getMode(), "default mode");
        assertEquals(GestureStateEngine.State.IDLE, engine.getState(), "initial state");
        assertEquals(960.0, engine.getCursorX(), "initial cursorX");
        assertEquals(540.0, engine.getCursorY(), "initial cursorY");
    }

    private static void testDirectTouchTapGeneratesLeftClickAndTouchEvents() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        engine.setMode(GestureStateEngine.TouchMode.DIRECT_TOUCH);

        List<InputEvent> emitted = new ArrayList<>();
        engine.setEventConsumer(emitted::add);

        long t0 = 1_000_000_000L;
        // Touch down at (480, 270)
        engine.onDown(0, 480.0, 270.0, t0);
        assertEquals(GestureStateEngine.State.SINGLE_TOUCH_DOWN, engine.getState(), "state on down");
        assertEquals(480.0, engine.getCursorX(), "cursorX moved directly");
        assertEquals(270.0, engine.getCursorY(), "cursorY moved directly");

        // Touch up 100ms later at same coordinate (within slop)
        engine.onUp(0, 480.0, 270.0, t0 + 100_000_000L);
        assertEquals(GestureStateEngine.State.IDLE, engine.getState(), "state on up");

        // Expected events:
        // 1) TouchEvent.DOWN at (0.25, 0.25)
        // 2) TouchEvent.UP at (0.25, 0.25)
        // 3) PointerEvent.BUTTON_DOWN (LEFT) at (480, 270)
        // 4) PointerEvent.BUTTON_UP (LEFT) at (480, 270)
        assertEquals(4, emitted.size(), "emitted event count");

        assertTrue(emitted.get(0) instanceof TouchEvent, "event 0 is TouchEvent");
        TouchEvent tDown = (TouchEvent) emitted.get(0);
        assertEquals(TouchEvent.Action.DOWN, tDown.action(), "touch action down");
        assertEquals(0.25, tDown.x(), "tDown.x");
        assertEquals(0.25, tDown.y(), "tDown.y");

        assertTrue(emitted.get(1) instanceof TouchEvent, "event 1 is TouchEvent");
        TouchEvent tUp = (TouchEvent) emitted.get(1);
        assertEquals(TouchEvent.Action.UP, tUp.action(), "touch action up");

        assertTrue(emitted.get(2) instanceof PointerEvent, "event 2 is PointerEvent");
        PointerEvent pDown = (PointerEvent) emitted.get(2);
        assertEquals(PointerEvent.Action.BUTTON_DOWN, pDown.action(), "pointer button down");
        assertEquals(PointerEvent.BUTTON_LEFT, pDown.button(), "button left");

        assertTrue(emitted.get(3) instanceof PointerEvent, "event 3 is PointerEvent");
        PointerEvent pUp = (PointerEvent) emitted.get(3);
        assertEquals(PointerEvent.Action.BUTTON_UP, pUp.action(), "pointer button up");
        assertEquals(PointerEvent.BUTTON_LEFT, pUp.button(), "button left");
    }

    private static void testDirectTouchDragEmitsTouchMoveAndMotion() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        engine.setMode(GestureStateEngine.TouchMode.DIRECT_TOUCH);

        List<InputEvent> emitted = new ArrayList<>();
        engine.setEventConsumer(emitted::add);

        long t0 = 2_000_000_000L;
        engine.onDown(0, 100.0, 100.0, t0);
        emitted.clear();

        // Move beyond 8px tap slop to (150, 150)
        engine.onMove(0, 150.0, 150.0, t0 + 50_000_000L);
        assertEquals(GestureStateEngine.State.DRAGGING, engine.getState(), "state dragging");

        // In direct touch, move emits TouchEvent.MOVE and PointerEvent.motion
        assertEquals(2, emitted.size(), "emitted drag event count");
        assertTrue(emitted.get(0) instanceof TouchEvent, "drag event 0 is TouchEvent");
        assertTrue(emitted.get(1) instanceof PointerEvent, "drag event 1 is PointerEvent");

        TouchEvent tMove = (TouchEvent) emitted.get(0);
        assertEquals(TouchEvent.Action.MOVE, tMove.action(), "touch move action");

        PointerEvent pMotion = (PointerEvent) emitted.get(1);
        assertEquals(PointerEvent.Action.MOVE, pMotion.action(), "pointer motion action");
        assertEquals(150.0, pMotion.x(), "motion x");
        assertEquals(150.0, pMotion.y(), "motion y");

        engine.onUp(0, 150.0, 150.0, t0 + 100_000_000L);
        assertEquals(GestureStateEngine.State.IDLE, engine.getState(), "state idle after drag up");
    }

    private static void testVirtualTrackpadMotionMovesCursorWithAcceleration() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        engine.setMode(GestureStateEngine.TouchMode.VIRTUAL_TRACKPAD);
        engine.setCursorPosition(500.0, 500.0);

        List<InputEvent> emitted = new ArrayList<>();
        engine.setEventConsumer(emitted::add);

        long t0 = 3_000_000_000L;
        // Finger down at (200, 200)
        engine.onDown(0, 200.0, 200.0, t0);
        assertEquals(500.0, engine.getCursorX(), "cursorX unmoved on trackpad down");
        assertEquals(500.0, engine.getCursorY(), "cursorY unmoved on trackpad down");

        // Swipe finger by (50, 20) over 20ms
        engine.onMove(0, 250.0, 220.0, t0 + 20_000_000L);
        assertEquals(GestureStateEngine.State.DRAGGING, engine.getState(), "state dragging");

        // Cursor moved by delta * acceleration
        assertTrue(engine.getCursorX() > 550.0, "cursorX accelerated beyond simple delta");
        assertTrue(engine.getCursorY() > 520.0, "cursorY accelerated beyond simple delta");

        assertEquals(1, emitted.size(), "single pointer motion emitted");
        assertTrue(emitted.get(0) instanceof PointerEvent, "emitted event is PointerEvent");
        PointerEvent motion = (PointerEvent) emitted.get(0);
        assertEquals(PointerEvent.Action.MOVE, motion.action(), "motion action");
        assertEquals(engine.getCursorX(), motion.x(), "motion matches cursorX");
        assertEquals(engine.getCursorY(), motion.y(), "motion matches cursorY");
    }

    private static void testLongPressGeneratesRightClick() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        engine.setMode(GestureStateEngine.TouchMode.VIRTUAL_TRACKPAD);
        engine.setCursorPosition(800.0, 600.0);

        List<InputEvent> emitted = new ArrayList<>();
        engine.setEventConsumer(emitted::add);

        long t0 = 4_000_000_000L;
        engine.onDown(0, 300.0, 300.0, t0);

        // Hold in place for 550ms (>= 500ms long press timeout, movement within 2px < 8px slop)
        engine.onMove(0, 301.0, 301.0, t0 + 550_000_000L);
        assertEquals(GestureStateEngine.State.LONG_PRESS_DETECTED, engine.getState(), "state long press detected");

        // Must emit:
        // 1) PointerEvent.BUTTON_DOWN (RIGHT)
        // 2) PointerEvent.BUTTON_UP (RIGHT)
        // 3) GestureEvent.LONG_PRESS
        assertEquals(3, emitted.size(), "emitted long press events");

        PointerEvent btnDown = (PointerEvent) emitted.get(0);
        assertEquals(PointerEvent.Action.BUTTON_DOWN, btnDown.action(), "btn down action");
        assertEquals(PointerEvent.BUTTON_RIGHT, btnDown.button(), "right click button");

        PointerEvent btnUp = (PointerEvent) emitted.get(1);
        assertEquals(PointerEvent.Action.BUTTON_UP, btnUp.action(), "btn up action");
        assertEquals(PointerEvent.BUTTON_RIGHT, btnUp.button(), "right click button");

        GestureEvent gesture = (GestureEvent) emitted.get(2);
        assertEquals(GestureEvent.GestureType.LONG_PRESS, gesture.gestureType(), "gesture type long press");
    }

    private static void testTwoFingerTapGeneratesRightClick() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        engine.setMode(GestureStateEngine.TouchMode.VIRTUAL_TRACKPAD);
        engine.setCursorPosition(700.0, 400.0);

        List<InputEvent> emitted = new ArrayList<>();
        engine.setEventConsumer(emitted::add);

        long t0 = 5_000_000_000L;
        engine.onDown(0, 100.0, 100.0, t0);
        engine.onPointerDown(1, 150.0, 150.0, t0 + 10_000_000L);
        assertEquals(GestureStateEngine.State.TWO_FINGER_DOWN, engine.getState(), "state two finger down");

        // Secondary pointer lifts 120ms later (< 250ms two finger tap timeout)
        engine.onPointerUp(1, 151.0, 151.0, t0 + 130_000_000L);

        // Must emit right click and GestureEvent.twoFingerTap
        assertEquals(3, emitted.size(), "two finger tap events count");
        PointerEvent btnDown = (PointerEvent) emitted.get(0);
        assertEquals(PointerEvent.BUTTON_RIGHT, btnDown.button(), "right click on two finger tap");

        GestureEvent gesture = (GestureEvent) emitted.get(2);
        assertEquals(GestureEvent.GestureType.TWO_FINGER_TAP, gesture.gestureType(), "two finger tap gesture");
    }

    private static void testTwoFingerVerticalScrollGeneratesScrollEvent() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        engine.setMode(GestureStateEngine.TouchMode.VIRTUAL_TRACKPAD);

        List<InputEvent> emitted = new ArrayList<>();
        engine.setEventConsumer(emitted::add);

        long t0 = 6_000_000_000L;
        engine.onDown(0, 100.0, 100.0, t0);
        engine.onPointerDown(1, 150.0, 100.0, t0 + 5_000_000L); // initial distance 50px

        // Both fingers slide down together by 30px (distance between them remains ~50px -> ratio < 0.15)
        engine.onTwoFingerMove(0, 100.0, 130.0, 1, 150.0, 130.0, t0 + 30_000_000L);
        assertEquals(GestureStateEngine.State.SCROLLING, engine.getState(), "state scrolling");

        assertEquals(1, emitted.size(), "emitted scroll event count");
        assertTrue(emitted.get(0) instanceof PointerEvent, "emitted event is PointerEvent");
        PointerEvent scroll = (PointerEvent) emitted.get(0);
        assertEquals(PointerEvent.Action.SCROLL, scroll.action(), "scroll action");
        assertEquals(0.0, scroll.deltaX(), "scroll deltaX");
        assertEquals(30.0, scroll.deltaY(), "scroll deltaY");
    }

    private static void testTwoFingerPinchGeneratesGestureEvent() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        engine.setMode(GestureStateEngine.TouchMode.VIRTUAL_TRACKPAD);

        List<InputEvent> emitted = new ArrayList<>();
        engine.setEventConsumer(emitted::add);

        long t0 = 7_000_000_000L;
        // Two fingers start 100px apart: (100, 100) and (200, 100)
        engine.onDown(0, 100.0, 100.0, t0);
        engine.onPointerDown(1, 200.0, 100.0, t0 + 5_000_000L);

        // Spread fingers apart to 180px: (60, 100) and (240, 100) -> 80% distance increase > 15%
        engine.onTwoFingerMove(0, 60.0, 100.0, 1, 240.0, 100.0, t0 + 50_000_000L);
        assertEquals(GestureStateEngine.State.PINCHING, engine.getState(), "state pinching");

        assertEquals(1, emitted.size(), "emitted pinch gesture event count");
        assertTrue(emitted.get(0) instanceof GestureEvent, "emitted event is GestureEvent");
        GestureEvent pinch = (GestureEvent) emitted.get(0);
        assertEquals(GestureEvent.GestureType.PINCH, pinch.gestureType(), "gesture type pinch");
        assertEquals(1.8, pinch.scaleFactor(), 0.01, "scaleFactor ~1.8");
        assertEquals(150.0, pinch.focusX(), 0.01, "focusX center");
        assertEquals(100.0, pinch.focusY(), 0.01, "focusY center");
    }

    private static void testModeSwitchBetweenDirectTouchAndVirtualTrackpad() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        assertEquals(GestureStateEngine.TouchMode.VIRTUAL_TRACKPAD, engine.getMode(), "initial trackpad mode");

        engine.setMode(GestureStateEngine.TouchMode.DIRECT_TOUCH);
        assertEquals(GestureStateEngine.TouchMode.DIRECT_TOUCH, engine.getMode(), "switched to direct touch");

        engine.setMode(GestureStateEngine.TouchMode.VIRTUAL_TRACKPAD);
        assertEquals(GestureStateEngine.TouchMode.VIRTUAL_TRACKPAD, engine.getMode(), "switched back to trackpad");
    }

    private static void testDisplayBoundaryClamping() {
        GestureStateEngine engine = new GestureStateEngine(1920.0, 1080.0);
        engine.setCursorPosition(-100.0, -200.0);
        assertEquals(0.0, engine.getCursorX(), "cursorX clamped at 0");
        assertEquals(0.0, engine.getCursorY(), "cursorY clamped at 0");

        engine.setCursorPosition(5000.0, 5000.0);
        assertEquals(1920.0, engine.getCursorX(), "cursorX clamped at max width");
        assertEquals(1080.0, engine.getCursorY(), "cursorY clamped at max height");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(double expected, double actual, String description) {
        assertEquals(expected, actual, 0.0001, description);
    }

    private static void assertEquals(double expected, double actual, double delta, String description) {
        if (Math.abs(expected - actual) > delta) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual + " (delta=" + delta + ")");
        }
    }
}
