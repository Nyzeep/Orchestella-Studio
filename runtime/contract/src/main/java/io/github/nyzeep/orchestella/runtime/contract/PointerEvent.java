package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral pointer event modeling cursor motion, relative displacement, mouse button clicks, and scroll.
 *
 * <p>Conforms to ADR-0017 (touch gesture mapping to Wayland pointer events).
 */
public final class PointerEvent implements InputEvent {
    public static final int BUTTON_NONE = 0;
    public static final int BUTTON_LEFT = 272;   // Linux BTN_LEFT (0x110)
    public static final int BUTTON_RIGHT = 273;  // Linux BTN_RIGHT (0x111)
    public static final int BUTTON_MIDDLE = 274; // Linux BTN_MIDDLE (0x112)

    // Strategy-neutral physical mouse button masks (aligned with Android MotionEvent)
    public static final int BUTTON_PRIMARY = 1;
    public static final int BUTTON_SECONDARY = 2;
    public static final int BUTTON_TERTIARY = 4;

    public enum Action {
        MOVE,
        BUTTON_DOWN,
        BUTTON_UP,
        SCROLL,
        RELATIVE_MOVE
    }

    private final Action action;
    private final double x;
    private final double y;
    private final double deltaX;
    private final double deltaY;
    private final int button;
    private final long timestampNs;

    public PointerEvent(
            Action action,
            double x,
            double y,
            double deltaX,
            double deltaY,
            int button,
            long timestampNs) {
        this.action = Objects.requireNonNull(action, "action");
        this.x = x;
        this.y = y;
        this.deltaX = deltaX;
        this.deltaY = deltaY;
        this.button = button;
        this.timestampNs = timestampNs;
    }

    public static PointerEvent motion(double x, double y, double deltaX, double deltaY, long timestampNs) {
        return new PointerEvent(Action.MOVE, x, y, deltaX, deltaY, BUTTON_NONE, timestampNs);
    }

    public static PointerEvent relativeMotion(double deltaX, double deltaY, int buttonMask, long timestampNs) {
        return new PointerEvent(Action.RELATIVE_MOVE, 0.0, 0.0, deltaX, deltaY, buttonMask, timestampNs);
    }

    public static PointerEvent relativeMotion(double deltaX, double deltaY, long timestampNs) {
        return relativeMotion(deltaX, deltaY, BUTTON_NONE, timestampNs);
    }

    public static PointerEvent buttonDown(double x, double y, int button, long timestampNs) {
        return new PointerEvent(Action.BUTTON_DOWN, x, y, 0.0, 0.0, button, timestampNs);
    }

    public static PointerEvent buttonUp(double x, double y, int button, long timestampNs) {
        return new PointerEvent(Action.BUTTON_UP, x, y, 0.0, 0.0, button, timestampNs);
    }

    public static PointerEvent scroll(double x, double y, double deltaX, double deltaY, long timestampNs) {
        return new PointerEvent(Action.SCROLL, x, y, deltaX, deltaY, BUTTON_NONE, timestampNs);
    }

    @Override
    public Type eventType() {
        return Type.POINTER;
    }

    public Action action() {
        return action;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double deltaX() {
        return deltaX;
    }

    public double deltaY() {
        return deltaY;
    }

    public int button() {
        return button;
    }

    public int buttonMask() {
        return button;
    }

    public boolean hasButton(int buttonMask) {
        return (button & buttonMask) != 0;
    }

    public boolean isRelativeMotion() {
        return action == Action.RELATIVE_MOVE;
    }

    public static int toLinuxButton(int buttonMask) {
        if ((buttonMask & BUTTON_PRIMARY) != 0) {
            return BUTTON_LEFT;
        }
        if ((buttonMask & BUTTON_SECONDARY) != 0) {
            return BUTTON_RIGHT;
        }
        if ((buttonMask & BUTTON_TERTIARY) != 0) {
            return BUTTON_MIDDLE;
        }
        return BUTTON_NONE;
    }

    public static int fromLinuxButton(int linuxButton) {
        switch (linuxButton) {
            case BUTTON_LEFT:
                return BUTTON_PRIMARY;
            case BUTTON_RIGHT:
                return BUTTON_SECONDARY;
            case BUTTON_MIDDLE:
                return BUTTON_TERTIARY;
            default:
                return 0;
        }
    }

    @Override
    public long timestampNs() {
        return timestampNs;
    }

    @Override
    public String serialize() {
        return InputEventSerialization.serialize(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PointerEvent)) {
            return false;
        }
        PointerEvent that = (PointerEvent) o;
        return Double.compare(that.x, x) == 0
                && Double.compare(that.y, y) == 0
                && Double.compare(that.deltaX, deltaX) == 0
                && Double.compare(that.deltaY, deltaY) == 0
                && button == that.button
                && timestampNs == that.timestampNs
                && action == that.action;
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, x, y, deltaX, deltaY, button, timestampNs);
    }

    @Override
    public String toString() {
        return "PointerEvent{"
                + "action=" + action
                + ", x=" + x
                + ", y=" + y
                + ", deltaX=" + deltaX
                + ", deltaY=" + deltaY
                + ", button=" + button
                + ", timestampNs=" + timestampNs
                + '}';
    }
}
