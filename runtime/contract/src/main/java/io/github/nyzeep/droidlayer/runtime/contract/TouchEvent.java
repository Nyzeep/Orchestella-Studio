package io.github.nyzeep.droidlayer.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral multi-touch event carrying slot pointer ID, touch phase action, and normalized coordinates.
 *
 * <p>Coordinates ({@code x}, {@code y}) are normalized between 0.0 and 1.0 relative to the active display viewport.
 * Conforms to ADR-0017.
 */
public final class TouchEvent implements InputEvent {
    public enum Action {
        DOWN,
        MOVE,
        UP,
        CANCEL
    }

    private final Action action;
    private final int pointerId;
    private final double x;
    private final double y;
    private final long timestampNs;

    public TouchEvent(Action action, int pointerId, double x, double y, long timestampNs) {
        this.action = Objects.requireNonNull(action, "action");
        if (pointerId < 0) {
            throw new IllegalArgumentException("pointerId must be non-negative: " + pointerId);
        }
        validateNormalizedCoordinate(x, "x");
        validateNormalizedCoordinate(y, "y");
        this.pointerId = pointerId;
        this.x = x;
        this.y = y;
        this.timestampNs = timestampNs;
    }

    private static void validateNormalizedCoordinate(double value, String name) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " coordinate must be normalized between 0.0 and 1.0: " + value);
        }
    }

    public static TouchEvent down(int pointerId, double x, double y, long timestampNs) {
        return new TouchEvent(Action.DOWN, pointerId, x, y, timestampNs);
    }

    public static TouchEvent move(int pointerId, double x, double y, long timestampNs) {
        return new TouchEvent(Action.MOVE, pointerId, x, y, timestampNs);
    }

    public static TouchEvent up(int pointerId, double x, double y, long timestampNs) {
        return new TouchEvent(Action.UP, pointerId, x, y, timestampNs);
    }

    public static TouchEvent cancel(int pointerId, double x, double y, long timestampNs) {
        return new TouchEvent(Action.CANCEL, pointerId, x, y, timestampNs);
    }

    @Override
    public Type eventType() {
        return Type.TOUCH;
    }

    public Action action() {
        return action;
    }

    public int pointerId() {
        return pointerId;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
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
        if (!(o instanceof TouchEvent)) {
            return false;
        }
        TouchEvent that = (TouchEvent) o;
        return pointerId == that.pointerId
                && Double.compare(that.x, x) == 0
                && Double.compare(that.y, y) == 0
                && timestampNs == that.timestampNs
                && action == that.action;
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, pointerId, x, y, timestampNs);
    }

    @Override
    public String toString() {
        return "TouchEvent{"
                + "action=" + action
                + ", pointerId=" + pointerId
                + ", x=" + x
                + ", y=" + y
                + ", timestampNs=" + timestampNs
                + '}';
    }
}
