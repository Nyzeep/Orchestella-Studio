package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.KeyEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * State machine managing keyboard modifier keys (Ctrl, Alt, Shift, Super) for touch-based desktop UX.
 *
 * <p>Supports:
 * <ul>
 *   <li>Single-click latching (LATCHED): active for the next non-modifier key, then auto-released;</li>
 *   <li>Double-click locking (LOCKED): double clicks within {@value #DEFAULT_DOUBLE_CLICK_TIMEOUT_MS}ms lock the modifier until clicked again;</li>
 *   <li>Multi-modifier concurrency: multiple modifiers can be independently latched or locked;</li>
 *   <li>State change notifications for UI key highlight and styling.</li>
 * </ul>
 *
 * <p>Conforms to ADR-0017 and Ticket 40.
 */
public final class ModifierKeyStateMachine {

    public static final long DEFAULT_DOUBLE_CLICK_TIMEOUT_MS = 350L;
    public static final int KEY_SUPER_META = 125; // Linux KEY_LEFTMETA

    public enum ModifierKey {
        CTRL(KeyEvent.MOD_CTRL, KeyEvent.KEY_LEFTCTRL),
        ALT(KeyEvent.MOD_ALT, KeyEvent.KEY_LEFTALT),
        SHIFT(KeyEvent.MOD_SHIFT, KeyEvent.KEY_LEFTSHIFT),
        SUPER(KeyEvent.MOD_SUPER, KEY_SUPER_META);

        private final int bitmask;
        private final int linuxKeyCode;

        ModifierKey(int bitmask, int linuxKeyCode) {
            this.bitmask = bitmask;
            this.linuxKeyCode = linuxKeyCode;
        }

        public int bitmask() {
            return bitmask;
        }

        public int linuxKeyCode() {
            return linuxKeyCode;
        }
    }

    public enum ModifierState {
        /** Inactive, key is not engaged. */
        RELEASED,
        /** Latched: active for the next key event, then automatically released. */
        LATCHED,
        /** Locked: permanently active across multiple keys until explicitly clicked again. */
        LOCKED
    }

    @FunctionalInterface
    public interface Listener {
        void onStateChanged(ModifierKey key, ModifierState state, int activeModifiers);
    }

    private final Map<ModifierKey, ModifierState> states = new EnumMap<>(ModifierKey.class);
    private final Map<ModifierKey, Long> lastClickTimestamps = new EnumMap<>(ModifierKey.class);
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final long doubleClickTimeoutMs;

    public ModifierKeyStateMachine() {
        this(DEFAULT_DOUBLE_CLICK_TIMEOUT_MS);
    }

    public ModifierKeyStateMachine(long doubleClickTimeoutMs) {
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

    public synchronized ModifierState getState(ModifierKey key) {
        return states.getOrDefault(Objects.requireNonNull(key, "key"), ModifierState.RELEASED);
    }

    public synchronized boolean isModifierActive(ModifierKey key) {
        ModifierState state = getState(key);
        return state == ModifierState.LATCHED || state == ModifierState.LOCKED;
    }

    public synchronized int getActiveModifiers() {
        int mask = KeyEvent.MOD_NONE;
        for (ModifierKey key : ModifierKey.values()) {
            if (isModifierActive(key)) {
                mask |= key.bitmask();
            }
        }
        return mask;
    }

    public void onModifierClicked(ModifierKey key) {
        onModifierClicked(key, System.currentTimeMillis());
    }

    public synchronized void onModifierClicked(ModifierKey key, long timestampMs) {
        Objects.requireNonNull(key, "key");
        ModifierState current = getState(key);
        Long lastClick = lastClickTimestamps.get(key);
        long elapsed = (lastClick != null) ? (timestampMs - lastClick) : Long.MAX_VALUE;

        ModifierState next;
        switch (current) {
            case RELEASED:
                next = ModifierState.LATCHED;
                break;
            case LATCHED:
                if (elapsed >= 0 && elapsed < doubleClickTimeoutMs) {
                    next = ModifierState.LOCKED;
                } else {
                    next = ModifierState.RELEASED;
                }
                break;
            case LOCKED:
            default:
                next = ModifierState.RELEASED;
                break;
        }

        states.put(key, next);
        lastClickTimestamps.put(key, timestampMs);

        int activeMods = getActiveModifiers();
        notifyListeners(key, next, activeMods);
    }

    public synchronized void onNonModifierKeyPressed() {
        boolean changed = false;
        for (ModifierKey key : ModifierKey.values()) {
            if (states.get(key) == ModifierState.LATCHED) {
                states.put(key, ModifierState.RELEASED);
                changed = true;
                notifyListeners(key, ModifierState.RELEASED, getActiveModifiers());
            }
        }
    }

    public synchronized void reset() {
        for (ModifierKey key : ModifierKey.values()) {
            states.put(key, ModifierState.RELEASED);
            lastClickTimestamps.put(key, 0L);
        }
        int activeMods = KeyEvent.MOD_NONE;
        for (ModifierKey key : ModifierKey.values()) {
            notifyListeners(key, ModifierState.RELEASED, activeMods);
        }
    }

    private void notifyListeners(ModifierKey key, ModifierState state, int activeModifiers) {
        for (Listener listener : listeners) {
            listener.onStateChanged(key, state, activeModifiers);
        }
    }
}
