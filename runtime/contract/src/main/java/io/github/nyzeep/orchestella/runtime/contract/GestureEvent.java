package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral high-level gesture event modeling multi-touch gestures such as pinch-to-zoom,
 * two-finger taps, and viewport scale negotiation.
 *
 * <p>Conforms to ADR-0017 and Phase 4.8 Task 2 specifications.
 */
public final class GestureEvent implements InputEvent {
    public enum GestureType {
        PINCH,
        TWO_FINGER_TAP,
        LONG_PRESS,
        DOUBLE_TAP
    }

    private final GestureType gestureType;
    private final double scaleFactor;
    private final double focusX;
    private final double focusY;
    private final long timestampNs;

    public GestureEvent(GestureType gestureType, double scaleFactor, double focusX, double focusY, long timestampNs) {
        this.gestureType = Objects.requireNonNull(gestureType, "gestureType");
        this.scaleFactor = scaleFactor;
        this.focusX = focusX;
        this.focusY = focusY;
        this.timestampNs = timestampNs;
    }

    public static GestureEvent pinch(double scaleFactor, double focusX, double focusY, long timestampNs) {
        return new GestureEvent(GestureType.PINCH, scaleFactor, focusX, focusY, timestampNs);
    }

    public static GestureEvent twoFingerTap(double focusX, double focusY, long timestampNs) {
        return new GestureEvent(GestureType.TWO_FINGER_TAP, 1.0, focusX, focusY, timestampNs);
    }

    public static GestureEvent longPress(double focusX, double focusY, long timestampNs) {
        return new GestureEvent(GestureType.LONG_PRESS, 1.0, focusX, focusY, timestampNs);
    }

    public static GestureEvent doubleTap(double focusX, double focusY, long timestampNs) {
        return new GestureEvent(GestureType.DOUBLE_TAP, 1.0, focusX, focusY, timestampNs);
    }

    @Override
    public Type eventType() {
        return Type.GESTURE;
    }

    public GestureType gestureType() {
        return gestureType;
    }

    public double scaleFactor() {
        return scaleFactor;
    }

    public double focusX() {
        return focusX;
    }

    public double focusY() {
        return focusY;
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
        if (!(o instanceof GestureEvent)) {
            return false;
        }
        GestureEvent that = (GestureEvent) o;
        return Double.compare(that.scaleFactor, scaleFactor) == 0
                && Double.compare(that.focusX, focusX) == 0
                && Double.compare(that.focusY, focusY) == 0
                && timestampNs == that.timestampNs
                && gestureType == that.gestureType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(gestureType, scaleFactor, focusX, focusY, timestampNs);
    }

    @Override
    public String toString() {
        return "GestureEvent{"
                + "gestureType=" + gestureType
                + ", scaleFactor=" + scaleFactor
                + ", focusX=" + focusX
                + ", focusY=" + focusY
                + ", timestampNs=" + timestampNs
                + '}';
    }
}
