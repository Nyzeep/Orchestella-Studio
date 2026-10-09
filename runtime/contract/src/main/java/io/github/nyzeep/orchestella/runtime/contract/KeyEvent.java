package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;

/**
 * Strategy-neutral keyboard event carrying Linux/XKB key code, modifier bitmask, and press/release state.
 *
 * <p>Conforms to ADR-0017 (VirtualKeyBar & extra keys integration).
 */
public final class KeyEvent implements InputEvent {
    public static final int MOD_NONE      = 0;
    public static final int MOD_SHIFT     = 1 << 0;
    public static final int MOD_CTRL      = 1 << 1;
    public static final int MOD_ALT       = 1 << 2;
    public static final int MOD_SUPER     = 1 << 3;
    public static final int MOD_CAPS_LOCK = 1 << 4;
    public static final int MOD_NUM_LOCK  = 1 << 5;

    // Standard Linux input-event-codes key definitions
    public static final int KEY_RESERVED = 0;
    public static final int KEY_ESC      = 1;
    public static final int KEY_TAB      = 15;
    public static final int KEY_ENTER    = 28;
    public static final int KEY_LEFTCTRL = 29;
    public static final int KEY_A        = 30;
    public static final int KEY_C        = 46;
    public static final int KEY_LEFTSHIFT= 42;
    public static final int KEY_LEFTALT  = 56;
    public static final int KEY_SPACE    = 57;
    public static final int KEY_UP       = 103;
    public static final int KEY_LEFT     = 105;
    public static final int KEY_RIGHT    = 106;
    public static final int KEY_DOWN     = 108;

    public enum Action {
        PRESS,
        RELEASE
    }

    private final Action action;
    private final int keyCode;
    private final int modifiers;
    private final long timestampNs;

    public KeyEvent(Action action, int keyCode, int modifiers, long timestampNs) {
        this.action = Objects.requireNonNull(action, "action");
        if (keyCode < 0) {
            throw new IllegalArgumentException("keyCode must be non-negative: " + keyCode);
        }
        this.keyCode = keyCode;
        this.modifiers = modifiers;
        this.timestampNs = timestampNs;
    }

    public static KeyEvent press(int keyCode, int modifiers, long timestampNs) {
        return new KeyEvent(Action.PRESS, keyCode, modifiers, timestampNs);
    }

    public static KeyEvent release(int keyCode, int modifiers, long timestampNs) {
        return new KeyEvent(Action.RELEASE, keyCode, modifiers, timestampNs);
    }

    @Override
    public Type eventType() {
        return Type.KEY;
    }

    public Action action() {
        return action;
    }

    public int keyCode() {
        return keyCode;
    }

    public int modifiers() {
        return modifiers;
    }

    @Override
    public long timestampNs() {
        return timestampNs;
    }

    public boolean isPressed() {
        return action == Action.PRESS;
    }

    public boolean hasModifier(int modifier) {
        return (this.modifiers & modifier) != 0;
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
        if (!(o instanceof KeyEvent)) {
            return false;
        }
        KeyEvent that = (KeyEvent) o;
        return keyCode == that.keyCode
                && modifiers == that.modifiers
                && timestampNs == that.timestampNs
                && action == that.action;
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, keyCode, modifiers, timestampNs);
    }

    @Override
    public String toString() {
        return "KeyEvent{"
                + "action=" + action
                + ", keyCode=" + keyCode
                + ", modifiers=" + modifiers
                + ", timestampNs=" + timestampNs
                + '}';
    }
}
