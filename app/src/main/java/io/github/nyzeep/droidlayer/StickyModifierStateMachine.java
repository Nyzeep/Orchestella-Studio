package io.github.nyzeep.droidlayer;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sticky modifier state machine for mobile terminal auxiliary key interactions.
 *
 * <p>Supports:
 * <ul>
 *   <li>Single-click latching: transitions to {@link State#LATCHED};</li>
 *   <li>Automatic consumption reset: latched state reverts to {@link State#OFF} after non-modifier combination;</li>
 *   <li>Double-click locking: transitions to {@link State#LOCKED} if clicked within timeout;</li>
 *   <li>Manual unlock: clicking a locked modifier reverts to {@link State#OFF};</li>
 *   <li>ASCII control code transformation (e.g. 'c' -> 0x03) and Alt escape sequencing.</li>
 * </ul>
 */
public final class StickyModifierStateMachine {

    public static final long DEFAULT_DOUBLE_CLICK_TIMEOUT_MS = 350L;

    public enum Modifier {
        CTRL,
        ALT
    }

    public enum State {
        OFF,
        LATCHED,
        LOCKED
    }

    @FunctionalInterface
    public interface Listener {
        void onModifierStateChanged(Modifier modifier, State state);
    }

    private final Map<Modifier, State> states = new EnumMap<>(Modifier.class);
    private final Map<Modifier, Long> lastClickTimestamps = new EnumMap<>(Modifier.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final long doubleClickTimeoutMs;

    public StickyModifierStateMachine() {
        this(DEFAULT_DOUBLE_CLICK_TIMEOUT_MS);
    }

    public StickyModifierStateMachine(long doubleClickTimeoutMs) {
        this.doubleClickTimeoutMs = doubleClickTimeoutMs > 0 ? doubleClickTimeoutMs : DEFAULT_DOUBLE_CLICK_TIMEOUT_MS;
        reset();
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized State getState(Modifier modifier) {
        return states.getOrDefault(Objects.requireNonNull(modifier, "modifier"), State.OFF);
    }

    public synchronized boolean isModifierActive(Modifier modifier) {
        State state = getState(modifier);
        return state == State.LATCHED || state == State.LOCKED;
    }

    public synchronized boolean isCtrlActive() {
        return isModifierActive(Modifier.CTRL);
    }

    public synchronized boolean isAltActive() {
        return isModifierActive(Modifier.ALT);
    }

    public void onModifierClicked(Modifier modifier) {
        onModifierClicked(modifier, System.currentTimeMillis());
    }

    public synchronized void onModifierClicked(Modifier modifier, long timestampMs) {
        Objects.requireNonNull(modifier, "modifier");
        State current = getState(modifier);
        Long lastClick = lastClickTimestamps.get(modifier);
        long elapsed = (lastClick != null) ? (timestampMs - lastClick) : Long.MAX_VALUE;

        State next;
        switch (current) {
            case OFF:
                next = State.LATCHED;
                break;
            case LATCHED:
                if (elapsed >= 0 && elapsed <= doubleClickTimeoutMs) {
                    next = State.LOCKED;
                } else {
                    next = State.OFF;
                }
                break;
            case LOCKED:
            default:
                next = State.OFF;
                break;
        }

        states.put(modifier, next);
        lastClickTimestamps.put(modifier, timestampMs);

        notifyListeners(modifier, next);
    }

    /**
     * Consumes all latched modifiers and resets them to {@link State#OFF}.
     * Modifiers in {@link State#LOCKED} remain locked.
     */
    public synchronized void consumeLatched() {
        for (Modifier modifier : Modifier.values()) {
            if (states.get(modifier) == State.LATCHED) {
                states.put(modifier, State.OFF);
                notifyListeners(modifier, State.OFF);
            }
        }
    }

    /**
     * Resets all modifiers back to {@link State#OFF}.
     */
    public synchronized void reset() {
        for (Modifier modifier : Modifier.values()) {
            states.put(modifier, State.OFF);
            lastClickTimestamps.put(modifier, 0L);
        }
        for (Modifier modifier : Modifier.values()) {
            notifyListeners(modifier, State.OFF);
        }
    }

    private void notifyListeners(Modifier modifier, State state) {
        for (Listener listener : listeners) {
            listener.onModifierStateChanged(modifier, state);
        }
    }

    /**
     * Maps an input character to its corresponding ASCII control code.
     * E.g. 'c' or 'C' -> 0x03 (SIGINT), 'z' or 'Z' -> 0x1A (SIGTSTP), 'd' or 'D' -> 0x04 (EOF).
     */
    public static byte toControlCode(char ch) {
        if (ch >= 'a' && ch <= 'z') {
            return (byte) (ch - 'a' + 1);
        }
        if (ch >= 'A' && ch <= 'Z') {
            return (byte) (ch - 'A' + 1);
        }
        switch (ch) {
            case '@':
            case ' ':
                return 0;
            case '[':
                return 27; // ESC (0x1B)
            case '\\':
                return 28;
            case ']':
                return 29;
            case '^':
                return 30;
            case '_':
                return 31;
            case '?':
                return 127; // DEL
            default:
                return (byte) (Character.toUpperCase(ch) & 0x1F);
        }
    }

    /**
     * Generates standard Alt escape sequence bytes (ESC followed by the character).
     */
    public static byte[] toAltSequence(char ch) {
        return new byte[]{0x1B, (byte) ch};
    }
}
