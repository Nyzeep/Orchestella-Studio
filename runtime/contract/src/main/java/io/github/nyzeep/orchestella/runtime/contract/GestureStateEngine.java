package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Strategy-neutral mobile multi-touch gesture state engine and dual-mode pointer interpreter.
 *
 * <p>Conforms to ADR-0017 and Phase 4.8 Task 2 specifications:
 * <ul>
 *   <li>Dual Touch Modes:
 *     <ul>
 *       <li>{@link TouchMode#DIRECT_TOUCH}: Direct absolute mapping (suitable for large touch targets);</li>
 *       <li>{@link TouchMode#VIRTUAL_TRACKPAD}: Relative laptop-like trackpad with dynamic velocity acceleration;</li>
 *     </ul>
 *   </li>
 *   <li>Mobile Multi-touch Gestures:
 *     <ul>
 *       <li>Single finger tap (&lt;200ms, displacement &lt;8px): Left click (BTN_LEFT);</li>
 *       <li>Long press (&ge;500ms): Right click context menu (BTN_RIGHT);</li>
 *       <li>Two-finger tap (&lt;250ms): Right click context menu (BTN_RIGHT);</li>
 *       <li>Two-finger vertical/horizontal drag: Mouse wheel scroll (wl_pointer.axis);</li>
 *       <li>Two-finger pinch-to-zoom: Viewport zoom negotiation ({@link GestureEvent#pinch});</li>
 *     </ul>
 *   </li>
 *   <li>Pure JVM design: 100% testable without Android framework stubs or native dependencies.</li>
 * </ul>
 */
public final class GestureStateEngine {

    public enum TouchMode {
        /** Absolute coordinates mapping directly to Wayland display. */
        DIRECT_TOUCH,
        /** Relative displacement trackpad driving virtual cursor. */
        VIRTUAL_TRACKPAD
    }

    public enum State {
        IDLE,
        SINGLE_TOUCH_DOWN,
        DRAGGING,
        TWO_FINGER_DOWN,
        SCROLLING,
        PINCHING,
        LONG_PRESS_DETECTED
    }

    public static final long DEFAULT_TAP_TIMEOUT_MS = 200L;
    public static final double DEFAULT_TAP_SLOP_PX = 8.0;
    public static final long DEFAULT_TWO_FINGER_TAP_TIMEOUT_MS = 250L;
    public static final long DEFAULT_LONG_PRESS_TIMEOUT_MS = 500L;
    public static final double DEFAULT_PINCH_THRESHOLD_RATIO = 0.15;

    private TouchMode touchMode = TouchMode.VIRTUAL_TRACKPAD;
    private State state = State.IDLE;

    private double displayWidth = 1920.0;
    private double displayHeight = 1080.0;
    private double cursorX = 960.0;
    private double cursorY = 540.0;

    private long tapTimeoutMs = DEFAULT_TAP_TIMEOUT_MS;
    private double tapSlopPx = DEFAULT_TAP_SLOP_PX;
    private long twoFingerTapTimeoutMs = DEFAULT_TWO_FINGER_TAP_TIMEOUT_MS;
    private long longPressTimeoutMs = DEFAULT_LONG_PRESS_TIMEOUT_MS;
    private double pinchThresholdRatio = DEFAULT_PINCH_THRESHOLD_RATIO;

    // Single finger tracking
    private int primaryPointerId = -1;
    private double downX;
    private double downY;
    private double lastX;
    private double lastY;
    private long downTimeNs;
    private long lastTimeNs;

    // Two finger tracking
    private int secondaryPointerId = -1;
    private double secondDownX;
    private double secondDownY;
    private double secondLastX;
    private double secondLastY;
    private double initialDistance;

    private Consumer<InputEvent> eventConsumer;

    public GestureStateEngine() {
        this(1920.0, 1080.0);
    }

    public GestureStateEngine(double displayWidth, double displayHeight) {
        setDisplaySize(displayWidth, displayHeight);
    }

    public synchronized void setDisplaySize(double width, double height) {
        if (width <= 0.0 || height <= 0.0) {
            throw new IllegalArgumentException("Display dimensions must be positive: " + width + "x" + height);
        }
        this.displayWidth = width;
        this.displayHeight = height;
        clampCursor();
    }

    public double getDisplayWidth() {
        return displayWidth;
    }

    public double getDisplayHeight() {
        return displayHeight;
    }

    public synchronized TouchMode getMode() {
        return touchMode;
    }

    public synchronized void setMode(TouchMode mode) {
        this.touchMode = Objects.requireNonNull(mode, "mode");
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized double getCursorX() {
        return cursorX;
    }

    public synchronized double getCursorY() {
        return cursorY;
    }

    public synchronized void setCursorPosition(double x, double y) {
        this.cursorX = x;
        this.cursorY = y;
        clampCursor();
    }

    public void setEventConsumer(Consumer<InputEvent> consumer) {
        this.eventConsumer = consumer;
    }

    public void setTapTimeoutMs(long ms) {
        this.tapTimeoutMs = ms;
    }

    public void setTapSlopPx(double px) {
        this.tapSlopPx = px;
    }

    public void setTwoFingerTapTimeoutMs(long ms) {
        this.twoFingerTapTimeoutMs = ms;
    }

    public void setLongPressTimeoutMs(long ms) {
        this.longPressTimeoutMs = ms;
    }

    public void setPinchThresholdRatio(double ratio) {
        this.pinchThresholdRatio = ratio;
    }

    private void clampCursor() {
        cursorX = Math.max(0.0, Math.min(cursorX, displayWidth));
        cursorY = Math.max(0.0, Math.min(cursorY, displayHeight));
    }

    /**
     * Primary entry point for primary touch down.
     */
    public synchronized void onDown(int pointerId, double x, double y, long timestampNs) {
        primaryPointerId = pointerId;
        downX = x;
        downY = y;
        lastX = x;
        lastY = y;
        downTimeNs = timestampNs;
        lastTimeNs = timestampNs;
        state = State.SINGLE_TOUCH_DOWN;

        if (touchMode == TouchMode.DIRECT_TOUCH) {
            cursorX = x;
            cursorY = y;
            clampCursor();
            double normX = Math.max(0.0, Math.min(cursorX / displayWidth, 1.0));
            double normY = Math.max(0.0, Math.min(cursorY / displayHeight, 1.0));
            dispatch(TouchEvent.down(pointerId, normX, normY, timestampNs));
        }
    }

    /**
     * Primary entry point for primary pointer movement.
     */
    public synchronized void onMove(int pointerId, double x, double y, long timestampNs) {
        if (pointerId != primaryPointerId || state == State.IDLE) {
            return;
        }

        double dxFromDown = x - downX;
        double dyFromDown = y - downY;
        double distFromDown = Math.hypot(dxFromDown, dyFromDown);

        // Check for long press while held in place
        if (state == State.SINGLE_TOUCH_DOWN && distFromDown <= tapSlopPx) {
            long holdMs = (timestampNs - downTimeNs) / 1_000_000L;
            if (holdMs >= longPressTimeoutMs) {
                state = State.LONG_PRESS_DETECTED;
                double targetX = (touchMode == TouchMode.DIRECT_TOUCH) ? x : cursorX;
                double targetY = (touchMode == TouchMode.DIRECT_TOUCH) ? y : cursorY;
                dispatch(PointerEvent.buttonDown(targetX, targetY, PointerEvent.BUTTON_RIGHT, timestampNs));
                dispatch(PointerEvent.buttonUp(targetX, targetY, PointerEvent.BUTTON_RIGHT, timestampNs));
                dispatch(GestureEvent.longPress(targetX, targetY, timestampNs));
                return;
            }
        }

        if (distFromDown > tapSlopPx && state == State.SINGLE_TOUCH_DOWN) {
            state = State.DRAGGING;
        }

        double deltaX = x - lastX;
        double deltaY = y - lastY;
        long elapsedNs = Math.max(1L, timestampNs - lastTimeNs);

        if (touchMode == TouchMode.VIRTUAL_TRACKPAD) {
            double dtMs = Math.max(1.0, elapsedNs / 1_000_000.0);
            double dist = Math.hypot(deltaX, deltaY);
            double velocity = dist / dtMs;
            double accelFactor = 1.0 + Math.min(3.0, velocity * 0.1);

            double finalDx = deltaX * accelFactor;
            double finalDy = deltaY * accelFactor;

            cursorX += finalDx;
            cursorY += finalDy;
            clampCursor();

            if (dist > 0.0) {
                dispatch(PointerEvent.motion(cursorX, cursorY, finalDx, finalDy, timestampNs));
            }
        } else {
            // DIRECT_TOUCH
            double prevX = cursorX;
            double prevY = cursorY;
            cursorX = x;
            cursorY = y;
            clampCursor();

            double normX = Math.max(0.0, Math.min(cursorX / displayWidth, 1.0));
            double normY = Math.max(0.0, Math.min(cursorY / displayHeight, 1.0));

            dispatch(TouchEvent.move(pointerId, normX, normY, timestampNs));
            dispatch(PointerEvent.motion(cursorX, cursorY, cursorX - prevX, cursorY - prevY, timestampNs));
        }

        lastX = x;
        lastY = y;
        lastTimeNs = timestampNs;
    }

    /**
     * Entry point for secondary pointer down (two-finger gesture sequence).
     */
    public synchronized void onPointerDown(int pointerId, double x, double y, long timestampNs) {
        secondaryPointerId = pointerId;
        secondDownX = x;
        secondDownY = y;
        secondLastX = x;
        secondLastY = y;
        initialDistance = Math.hypot(x - lastX, y - lastY);
        state = State.TWO_FINGER_DOWN;
    }

    /**
     * Entry point for two-finger movement (distinguishes between scroll and pinch-to-zoom).
     */
    public synchronized void onTwoFingerMove(
            int id0, double x0, double y0,
            int id1, double x1, double y1,
            long timestampNs) {
        if (state != State.TWO_FINGER_DOWN && state != State.SCROLLING && state != State.PINCHING) {
            return;
        }

        double currentDist = Math.hypot(x1 - x0, y1 - y0);
        double distDelta = Math.abs(currentDist - initialDistance);
        double distRatio = distDelta / Math.max(initialDistance, 1.0);

        if (distRatio > pinchThresholdRatio || state == State.PINCHING) {
            state = State.PINCHING;
            double scaleFactor = initialDistance > 0.0 ? currentDist / initialDistance : 1.0;
            double focusX = (x0 + x1) / 2.0;
            double focusY = (y0 + y1) / 2.0;
            dispatch(GestureEvent.pinch(scaleFactor, focusX, focusY, timestampNs));
        } else {
            state = State.SCROLLING;
            double deltaX0 = x0 - lastX;
            double deltaY0 = y0 - lastY;
            double deltaX1 = x1 - secondLastX;
            double deltaY1 = y1 - secondLastY;

            double avgDx = (deltaX0 + deltaX1) / 2.0;
            double avgDy = (deltaY0 + deltaY1) / 2.0;

            dispatch(PointerEvent.scroll(cursorX, cursorY, avgDx, avgDy, timestampNs));
        }

        lastX = x0;
        lastY = y0;
        secondLastX = x1;
        secondLastY = y1;
        lastTimeNs = timestampNs;
    }

    /**
     * Entry point for secondary pointer up.
     */
    public synchronized void onPointerUp(int pointerId, double x, double y, long timestampNs) {
        if (pointerId == secondaryPointerId && state == State.TWO_FINGER_DOWN) {
            long holdMs = (timestampNs - downTimeNs) / 1_000_000L;
            double dist0 = Math.hypot(lastX - downX, lastY - downY);
            double dist1 = Math.hypot(x - secondDownX, y - secondDownY);

            if (holdMs < twoFingerTapTimeoutMs && dist0 <= (tapSlopPx * 2.5) && dist1 <= (tapSlopPx * 2.5)) {
                double targetX = (touchMode == TouchMode.DIRECT_TOUCH) ? (downX + secondDownX) / 2.0 : cursorX;
                double targetY = (touchMode == TouchMode.DIRECT_TOUCH) ? (downY + secondDownY) / 2.0 : cursorY;
                dispatch(PointerEvent.buttonDown(targetX, targetY, PointerEvent.BUTTON_RIGHT, timestampNs));
                dispatch(PointerEvent.buttonUp(targetX, targetY, PointerEvent.BUTTON_RIGHT, timestampNs));
                dispatch(GestureEvent.twoFingerTap(targetX, targetY, timestampNs));
            }
        }
        secondaryPointerId = -1;
    }

    /**
     * Primary entry point for primary pointer up.
     */
    public synchronized void onUp(int pointerId, double x, double y, long timestampNs) {
        if (touchMode == TouchMode.DIRECT_TOUCH) {
            double normX = Math.max(0.0, Math.min(cursorX / displayWidth, 1.0));
            double normY = Math.max(0.0, Math.min(cursorY / displayHeight, 1.0));
            dispatch(TouchEvent.up(pointerId, normX, normY, timestampNs));
        }

        // Tap recognition
        if (state == State.SINGLE_TOUCH_DOWN) {
            long holdMs = (timestampNs - downTimeNs) / 1_000_000L;
            double dist = Math.hypot(x - downX, y - downY);

            if (holdMs < tapTimeoutMs && dist <= tapSlopPx) {
                double targetX = (touchMode == TouchMode.DIRECT_TOUCH) ? x : cursorX;
                double targetY = (touchMode == TouchMode.DIRECT_TOUCH) ? y : cursorY;
                dispatch(PointerEvent.buttonDown(targetX, targetY, PointerEvent.BUTTON_LEFT, timestampNs));
                dispatch(PointerEvent.buttonUp(targetX, targetY, PointerEvent.BUTTON_LEFT, timestampNs));
            }
        }

        // Reset
        state = State.IDLE;
        primaryPointerId = -1;
        secondaryPointerId = -1;
    }

    /**
     * Entry point for gesture cancellation.
     */
    public synchronized void onCancel(long timestampNs) {
        if (touchMode == TouchMode.DIRECT_TOUCH && primaryPointerId != -1) {
            double normX = Math.max(0.0, Math.min(cursorX / displayWidth, 1.0));
            double normY = Math.max(0.0, Math.min(cursorY / displayHeight, 1.0));
            dispatch(TouchEvent.cancel(primaryPointerId, normX, normY, timestampNs));
        }
        state = State.IDLE;
        primaryPointerId = -1;
        secondaryPointerId = -1;
    }

    private void dispatch(InputEvent event) {
        if (eventConsumer != null && event != null) {
            eventConsumer.accept(event);
        }
    }
}
