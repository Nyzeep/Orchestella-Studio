package io.github.nyzeep.orchestella;

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
        return (byte) toControlCodePoint(ch);
    }

    /** Ctrl aliases used by terminal keyboards; other Unicode stays intact. */
    public static int toControlCodePoint(int codePoint) {
        if (codePoint >= 'a' && codePoint <= 'z') return codePoint - 'a' + 1;
        if (codePoint >= 'A' && codePoint <= 'Z') return codePoint - 'A' + 1;
        switch (codePoint) {
            case '@': case ' ': case '2': return 0;
            case '[': case '3': return 27;
            case '\\': case '4': return 28;
            case ']': case '5': return 29;
            case '^': case '6': return 30;
            case '_': case '7': case '/': return 31;
            case '?': case '8': return 127;
            default: return codePoint;
        }
    }

    /** Generates ESC followed by a complete UTF-8 code point. */
    public static byte[] toAltSequence(char ch) {
        return toAltSequence((int) ch);
    }

    public static byte[] toAltSequence(int codePoint) {
        return ("\u001b" + new String(Character.toChars(codePoint)))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
