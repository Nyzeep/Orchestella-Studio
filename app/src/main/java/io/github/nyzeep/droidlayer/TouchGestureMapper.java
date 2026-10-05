package io.github.nyzeep.droidlayer;

import android.view.MotionEvent;
import io.github.nyzeep.droidlayer.runtime.contract.PointerEvent;
import io.github.nyzeep.droidlayer.runtime.contract.TouchEvent;
import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;
import java.util.Objects;

/**
 * High-precision mobile touch gesture mapping engine.
 *
 * <p>Conforms to ADR-0017 and Ticket 40:
 * <ul>
 *   <li>Maps viewport coordinates to guest Wayland content coordinates via {@link ViewportAspectCalculator};</li>
 *   <li>Single-finger tap (&lt;200ms, displacement &lt;8dp): emits BTN_LEFT click (DOWN + UP);</li>
 *   <li>Single-finger drag: emits relative PointerEvent.motion with dynamic acceleration (Trackpad mode) or direct tracking (Touchscreen mode);</li>
 *   <li>Long press (&ge;500ms): emits BTN_RIGHT click;</li>
 *   <li>Two-finger tap (&lt;250ms): emits BTN_RIGHT click;</li>
 *   <li>Two-finger scroll: emits smooth PointerEvent.scroll;</li>
 *   <li>Runtime switching between Touchscreen and Trackpad modes;</li>
 *   <li>100% pure JVM testable without requiring Android framework stubs.</li>
 * </ul>
 */
public final class TouchGestureMapper {

    public enum TouchMode {
        /** Direct absolute touch: coordinates map 1:1, dispatches TouchEvents and absolute pointer motion. */
        TOUCHSCREEN,
        /** Relative virtual trackpad: maintains virtual cursor position, applies dynamic velocity acceleration. */
        TRACKPAD
    }

    public static final long DEFAULT_TAP_TIMEOUT_MS = 200L;
    public static final float DEFAULT_TAP_SLOP_PX = 8.0f;
    public static final long DEFAULT_TWO_FINGER_TAP_TIMEOUT_MS = 250L;
    public static final long DEFAULT_LONG_PRESS_TIMEOUT_MS = 500L;

    private WaylandChannel channel;
    private ViewportAspectCalculator.ViewportMapping mapping;
    private TouchMode touchMode = TouchMode.TRACKPAD;

    private long tapTimeoutMs = DEFAULT_TAP_TIMEOUT_MS;
    private float tapSlopPx = DEFAULT_TAP_SLOP_PX;
    private long twoFingerTapTimeoutMs = DEFAULT_TWO_FINGER_TAP_TIMEOUT_MS;
    private long longPressTimeoutMs = DEFAULT_LONG_PRESS_TIMEOUT_MS;

    // Virtual cursor position in content coordinates
    private double cursorX;
    private double cursorY;

    // Single-finger tracking
    private int primaryPointerId = -1;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private long downTimeNs;
    private long lastTimeNs;
    private boolean isDragging;
    private boolean isLongPressTriggered;
    private boolean isDownAccepted;

    // Two-finger tracking
    private int secondaryPointerId = -1;
    private float secondDownX;
    private float secondDownY;
    private float secondLastX;
    private float secondLastY;
    private boolean isTwoFingerGesture;
    private boolean twoFingerTapTriggered;

    public TouchGestureMapper(WaylandChannel channel, ViewportAspectCalculator.ViewportMapping mapping) {
        this.channel = channel;
        setViewportMapping(mapping);
    }

    public synchronized void setViewportMapping(ViewportAspectCalculator.ViewportMapping mapping) {
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        // Center cursor initially if uninitialized
        if (cursorX == 0.0 && cursorY == 0.0) {
            cursorX = mapping.contentWidth() / 2.0;
            cursorY = mapping.contentHeight() / 2.0;
        } else {
            clampCursor();
        }
    }

    public synchronized ViewportAspectCalculator.ViewportMapping getViewportMapping() {
        return mapping;
    }

    public void setChannel(WaylandChannel channel) {
        this.channel = channel;
    }

    public WaylandChannel getChannel() {
        return channel;
    }

    public TouchMode getTouchMode() {
        return touchMode;
    }

    public void setTouchMode(TouchMode touchMode) {
        this.touchMode = Objects.requireNonNull(touchMode, "touchMode");
    }

    public double getCursorX() {
        return cursorX;
    }

    public double getCursorY() {
        return cursorY;
    }

    public synchronized void setCursorPosition(double x, double y) {
        this.cursorX = x;
        this.cursorY = y;
        clampCursor();
    }

    private void clampCursor() {
        if (mapping != null) {
            cursorX = Math.max(0.0, Math.min(cursorX, (double) mapping.contentWidth()));
            cursorY = Math.max(0.0, Math.min(cursorY, (double) mapping.contentHeight()));
        }
    }

    public void setTapTimeoutMs(long ms) {
        this.tapTimeoutMs = ms;
    }

    public void setTapSlopPx(float px) {
        this.tapSlopPx = px;
    }

    public void setTwoFingerTapTimeoutMs(long ms) {
        this.twoFingerTapTimeoutMs = ms;
    }

    public void setLongPressTimeoutMs(long ms) {
        this.longPressTimeoutMs = ms;
    }

    /**
     * Primary entry point for primary pointer DOWN.
     */
    public synchronized void onDown(int pointerId, float vx, float vy, long timestampNs) {
        if (mapping == null) {
            return;
        }

        // In Touchscreen mode, ignore touches falling strictly in letterbox/pillarbox bars
        if (touchMode == TouchMode.TOUCHSCREEN && !mapping.isInsideContent(vx, vy)) {
            isDownAccepted = false;
            return;
        }

        isDownAccepted = true;
        primaryPointerId = pointerId;
        downX = vx;
        downY = vy;
        lastX = vx;
        lastY = vy;
        downTimeNs = timestampNs;
        lastTimeNs = timestampNs;
        isDragging = false;
        isLongPressTriggered = false;
        isTwoFingerGesture = false;
        twoFingerTapTriggered = false;

        if (touchMode == TouchMode.TOUCHSCREEN) {
            float[] contentCoords = mapping.mapViewportToContent(vx, vy);
            cursorX = Math.max(0.0, Math.min(contentCoords[0], mapping.contentWidth()));
            cursorY = Math.max(0.0, Math.min(contentCoords[1], mapping.contentHeight()));
            double normX = Math.max(0.0, Math.min(cursorX / mapping.contentWidth(), 1.0));
            double normY = Math.max(0.0, Math.min(cursorY / mapping.contentHeight(), 1.0));

            sendEvent(TouchEvent.down(pointerId, normX, normY, timestampNs));
        }
    }

    /**
     * Entry point for secondary pointer DOWN in multi-touch sequences.
     */
    public synchronized void onPointerDown(int pointerId, float vx, float vy, long timestampNs) {
        if (!isDownAccepted || mapping == null) {
            return;
        }
        secondaryPointerId = pointerId;
        secondDownX = vx;
        secondDownY = vy;
        secondLastX = vx;
        secondLastY = vy;
        isTwoFingerGesture = true;
    }

    /**
     * Primary entry point for pointer MOVE.
     */
    public synchronized void onMove(int pointerId, float vx, float vy, long timestampNs) {
        if (!isDownAccepted || mapping == null || pointerId != primaryPointerId) {
            return;
        }

        float dxFromDown = vx - downX;
        float dyFromDown = vy - downY;
        float distFromDown = (float) Math.hypot(dxFromDown, dyFromDown);

        if (distFromDown > tapSlopPx) {
            isDragging = true;
        }

        // Check for long press (held in place without moving beyond slop)
        if (!isDragging && !isLongPressTriggered) {
            long holdDurationMs = (timestampNs - downTimeNs) / 1_000_000L;
            if (holdDurationMs >= longPressTimeoutMs) {
                isLongPressTriggered = true;
                double targetX = cursorX;
                double targetY = cursorY;
                if (touchMode == TouchMode.TOUCHSCREEN) {
                    float[] cc = mapping.mapViewportToContent(vx, vy);
                    targetX = cc[0];
                    targetY = cc[1];
                }
                sendEvent(PointerEvent.buttonDown(targetX, targetY, PointerEvent.BUTTON_RIGHT, timestampNs));
                sendEvent(PointerEvent.buttonUp(targetX, targetY, PointerEvent.BUTTON_RIGHT, timestampNs));
            }
        }

        float deltaVx = vx - lastX;
        float deltaVy = vy - lastY;
        long elapsedNs = timestampNs - lastTimeNs;

        if (touchMode == TouchMode.TRACKPAD) {
            double deltaX = deltaVx / mapping.scaleX();
            double deltaY = deltaVy / mapping.scaleY();

            // Dynamic velocity acceleration curve
            double dtMs = Math.max(1.0, elapsedNs / 1_000_000.0);
            double dist = Math.hypot(deltaX, deltaY);
            double velocity = dist / dtMs; // px per ms
            double accelFactor = 1.0 + Math.min(3.0, velocity * 0.1);

            double finalDeltaX = deltaX * accelFactor;
            double finalDeltaY = deltaY * accelFactor;

            cursorX += finalDeltaX;
            cursorY += finalDeltaY;
            clampCursor();

            if (dist > 0.0) {
                sendEvent(PointerEvent.motion(cursorX, cursorY, finalDeltaX, finalDeltaY, timestampNs));
            }
        } else {
            // Touchscreen absolute tracking
            float[] contentCoords = mapping.mapViewportToContent(vx, vy);
            double prevX = cursorX;
            double prevY = cursorY;
            cursorX = Math.max(0.0, Math.min(contentCoords[0], mapping.contentWidth()));
            cursorY = Math.max(0.0, Math.min(contentCoords[1], mapping.contentHeight()));

            double normX = Math.max(0.0, Math.min(cursorX / mapping.contentWidth(), 1.0));
            double normY = Math.max(0.0, Math.min(cursorY / mapping.contentHeight(), 1.0));

            sendEvent(TouchEvent.move(pointerId, normX, normY, timestampNs));
            sendEvent(PointerEvent.motion(cursorX, cursorY, cursorX - prevX, cursorY - prevY, timestampNs));
        }

        lastX = vx;
        lastY = vy;
        lastTimeNs = timestampNs;
    }

    /**
     * Handles simultaneous two-finger movement for smooth scrolling.
     */
    public synchronized void onTwoFingerMove(int[] pointerIds, float[] xs, float[] ys, long timestampNs) {
        if (!isDownAccepted || mapping == null || xs.length < 2 || ys.length < 2) {
            return;
        }

        isDragging = true;
        float deltaX0 = xs[0] - lastX;
        float deltaY0 = ys[0] - lastY;
        float deltaX1 = xs[1] - secondLastX;
        float deltaY1 = ys[1] - secondLastY;

        float avgDeltaVx = (deltaX0 + deltaX1) / 2.0f;
        float avgDeltaVy = (deltaY0 + deltaY1) / 2.0f;

        double scrollX = avgDeltaVx / mapping.scaleX();
        double scrollY = avgDeltaVy / mapping.scaleY();

        sendEvent(PointerEvent.scroll(cursorX, cursorY, scrollX, scrollY, timestampNs));

        lastX = xs[0];
        lastY = ys[0];
        secondLastX = xs[1];
        secondLastY = ys[1];
        lastTimeNs = timestampNs;
    }

    /**
     * Entry point for secondary pointer UP.
     */
    public synchronized void onPointerUp(int pointerId, float vx, float vy, long timestampNs) {
        if (pointerId == secondaryPointerId && isTwoFingerGesture) {
            long durationMs = (timestampNs - downTimeNs) / 1_000_000L;
            float dist0 = (float) Math.hypot(lastX - downX, lastY - downY);
            float dist1 = (float) Math.hypot(vx - secondDownX, vy - secondDownY);

            if (durationMs < twoFingerTapTimeoutMs && dist0 < (tapSlopPx * 2.5f) && dist1 < (tapSlopPx * 2.5f)) {
                twoFingerTapTriggered = true;
                sendEvent(PointerEvent.buttonDown(cursorX, cursorY, PointerEvent.BUTTON_RIGHT, timestampNs));
                sendEvent(PointerEvent.buttonUp(cursorX, cursorY, PointerEvent.BUTTON_RIGHT, timestampNs));
            }
        }
    }

    /**
     * Primary entry point for pointer UP.
     */
    public synchronized void onUp(int pointerId, float vx, float vy, long timestampNs) {
        if (!isDownAccepted || mapping == null) {
            return;
        }

        if (touchMode == TouchMode.TOUCHSCREEN) {
            float[] contentCoords = mapping.mapViewportToContent(vx, vy);
            double cx = Math.max(0.0, Math.min(contentCoords[0], mapping.contentWidth()));
            double cy = Math.max(0.0, Math.min(contentCoords[1], mapping.contentHeight()));
            double normX = Math.max(0.0, Math.min(cx / mapping.contentWidth(), 1.0));
            double normY = Math.max(0.0, Math.min(cy / mapping.contentHeight(), 1.0));

            sendEvent(TouchEvent.up(pointerId, normX, normY, timestampNs));
        }

        // Tap evaluation
        if (!isLongPressTriggered && !twoFingerTapTriggered && !isTwoFingerGesture) {
            long durationMs = (timestampNs - downTimeNs) / 1_000_000L;
            float dist = (float) Math.hypot(vx - downX, vy - downY);

            if (durationMs < tapTimeoutMs && dist < tapSlopPx) {
                double targetX = cursorX;
                double targetY = cursorY;
                if (touchMode == TouchMode.TOUCHSCREEN) {
                    float[] cc = mapping.mapViewportToContent(vx, vy);
                    targetX = cc[0];
                    targetY = cc[1];
                }
                sendEvent(PointerEvent.buttonDown(targetX, targetY, PointerEvent.BUTTON_LEFT, timestampNs));
                sendEvent(PointerEvent.buttonUp(targetX, targetY, PointerEvent.BUTTON_LEFT, timestampNs));
            }
        }

        // Reset tracking state
        primaryPointerId = -1;
        secondaryPointerId = -1;
        isDownAccepted = false;
        isDragging = false;
        isLongPressTriggered = false;
        isTwoFingerGesture = false;
        twoFingerTapTriggered = false;
    }

    /**
     * Entry point for gesture cancellation.
     */
    public synchronized void onCancel(int pointerId, float vx, float vy, long timestampNs) {
        if (isDownAccepted && touchMode == TouchMode.TOUCHSCREEN) {
            float[] cc = mapping.mapViewportToContent(vx, vy);
            double normX = Math.max(0.0, Math.min(cc[0] / mapping.contentWidth(), 1.0));
            double normY = Math.max(0.0, Math.min(cc[1] / mapping.contentHeight(), 1.0));
            sendEvent(TouchEvent.cancel(pointerId, normX, normY, timestampNs));
        }
        primaryPointerId = -1;
        secondaryPointerId = -1;
        isDownAccepted = false;
        isDragging = false;
        isLongPressTriggered = false;
        isTwoFingerGesture = false;
        twoFingerTapTriggered = false;
    }

    /**
     * Adapts Android framework {@link MotionEvent} to gesture engine.
     *
     * @param event Android motion event
     * @return true if handled
     */
    public boolean onTouchEvent(MotionEvent event) {
        if (event == null) {
            return false;
        }
        try {
            int actionMasked = event.getActionMasked();
            int actionIndex = event.getActionIndex();
            int pointerCount = event.getPointerCount();
            long eventTimeNs = event.getEventTime() * 1_000_000L;

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN: {
                    int id = event.getPointerId(0);
                    onDown(id, event.getX(0), event.getY(0), eventTimeNs);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    if (actionIndex < pointerCount) {
                        int id = event.getPointerId(actionIndex);
                        onPointerDown(id, event.getX(actionIndex), event.getY(actionIndex), eventTimeNs);
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (pointerCount >= 2) {
                        int[] ids = new int[pointerCount];
                        float[] xs = new float[pointerCount];
                        float[] ys = new float[pointerCount];
                        for (int i = 0; i < pointerCount; i++) {
                            ids[i] = event.getPointerId(i);
                            xs[i] = event.getX(i);
                            ys[i] = event.getY(i);
                        }
                        onTwoFingerMove(ids, xs, ys, eventTimeNs);
                    } else if (pointerCount == 1) {
                        onMove(event.getPointerId(0), event.getX(0), event.getY(0), eventTimeNs);
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    if (actionIndex < pointerCount) {
                        int id = event.getPointerId(actionIndex);
                        onPointerUp(id, event.getX(actionIndex), event.getY(actionIndex), eventTimeNs);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    int id = event.getPointerId(0);
                    onUp(id, event.getX(0), event.getY(0), eventTimeNs);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    int id = event.getPointerId(0);
                    onCancel(id, event.getX(0), event.getY(0), eventTimeNs);
                    return true;
                }
                default:
                    return false;
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private void sendEvent(io.github.nyzeep.droidlayer.runtime.contract.InputEvent event) {
        if (channel != null && channel.isConnected()) {
            channel.sendInputEvent(event);
        }
    }
}
