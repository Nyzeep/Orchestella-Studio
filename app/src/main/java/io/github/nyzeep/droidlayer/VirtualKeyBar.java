package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.ModifierKeyStateMachine.ModifierKey;
import io.github.nyzeep.droidlayer.runtime.contract.KeyEvent;
import io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel;
import java.util.Objects;

/**
 * Controller and presentation bridge for the floating/fixed virtual extra keys bar.
 *
 * <p>Conforms to ADR-0017 and Ticket 40:
 * <ul>
 *   <li>Coordinates Esc, Tab, Ctrl, Alt, Shift, Super, Arrow keys (↑, ↓, ←, →);</li>
 *   <li>Integrates with {@link ModifierKeyStateMachine} for latching and locking;</li>
 *   <li>Dispatches serialized {@link KeyEvent} pairs (press + release) to {@link WaylandChannel};</li>
 *   <li>Supports toggling the Android host soft keyboard.</li>
 * </ul>
 */
public final class VirtualKeyBar {

    @FunctionalInterface
    public interface SoftKeyboardToggleListener {
        void onToggleSoftKeyboard();
    }

    private WaylandChannel channel;
    private final ModifierKeyStateMachine stateMachine;
    private SoftKeyboardToggleListener keyboardToggleListener;

    public VirtualKeyBar(WaylandChannel channel) {
        this(channel, new ModifierKeyStateMachine());
    }

    public VirtualKeyBar(WaylandChannel channel, ModifierKeyStateMachine stateMachine) {
        this.channel = channel;
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
    }

    public void setChannel(WaylandChannel channel) {
        this.channel = channel;
    }

    public WaylandChannel getChannel() {
        return channel;
    }

    public ModifierKeyStateMachine getStateMachine() {
        return stateMachine;
    }

    public void setOnSoftKeyboardToggleListener(SoftKeyboardToggleListener listener) {
        this.keyboardToggleListener = listener;
    }

    public void pressModifier(ModifierKey key) {
        pressModifier(key, System.currentTimeMillis());
    }

    public void pressModifier(ModifierKey key, long timestampMs) {
        stateMachine.onModifierClicked(key, timestampMs);
    }

    public boolean pressKey(int linuxKeyCode) {
        return pressKey(linuxKeyCode, System.nanoTime());
    }

    public synchronized boolean pressKey(int linuxKeyCode, long timestampNs) {
        int modifiers = stateMachine.getActiveModifiers();
        boolean sent = true;
        if (channel != null && channel.isConnected()) {
            boolean pressSent = channel.sendInputEvent(KeyEvent.press(linuxKeyCode, modifiers, timestampNs));
            boolean releaseSent = channel.sendInputEvent(KeyEvent.release(linuxKeyCode, modifiers, timestampNs));
            sent = pressSent && releaseSent;
        } else {
            sent = false;
        }

        // Non-modifier key triggers consumption of latched modifiers
        stateMachine.onNonModifierKeyPressed();
        return sent;
    }

    public boolean pressEsc() {
        return pressKey(KeyEvent.KEY_ESC);
    }

    public boolean pressTab() {
        return pressKey(KeyEvent.KEY_TAB);
    }

    public boolean pressUp() {
        return pressKey(KeyEvent.KEY_UP);
    }

    public boolean pressDown() {
        return pressKey(KeyEvent.KEY_DOWN);
    }

    public boolean pressLeft() {
        return pressKey(KeyEvent.KEY_LEFT);
    }

    public boolean pressRight() {
        return pressKey(KeyEvent.KEY_RIGHT);
    }

    public void toggleSoftKeyboard() {
        if (keyboardToggleListener != null) {
            keyboardToggleListener.onToggleSoftKeyboard();
        }
    }
}
