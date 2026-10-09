package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.ModifierKeyStateMachine.ModifierKey;
import io.github.nyzeep.orchestella.ModifierKeyStateMachine.ModifierState;
import io.github.nyzeep.orchestella.runtime.contract.InputEvent;
import io.github.nyzeep.orchestella.runtime.contract.KeyEvent;
import io.github.nyzeep.orchestella.runtime.contract.WaylandChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for VirtualKeyBar: keyboard shortcut dispatches, modifier state machine coordination,
 * and soft keyboard toggle actions.
 */
public final class VirtualKeyBarTest {

    private FakeWaylandChannel channel;
    private ModifierKeyStateMachine stateMachine;
    private VirtualKeyBar keyBar;
    private boolean keyboardToggled;

    private static final class FakeWaylandChannel implements WaylandChannel {
        final List<InputEvent> events = new ArrayList<>();
        ConnectionState state = ConnectionState.CONNECTED;

        @Override
        public ConnectionState state() {
            return state;
        }

        @Override
        public void addStateListener(StateListener listener) {}

        @Override
        public void removeStateListener(StateListener listener) {}

        @Override
        public void registerFrameListener(FrameListener listener) {}

        @Override
        public void unregisterFrameListener(FrameListener listener) {}

        @Override
        public boolean sendInputEvent(InputEvent event) {
            events.add(event);
            return true;
        }

        @Override
        public void close() {
            state = ConnectionState.DISCONNECTED;
        }
    }

    @Before
    public void setUp() {
        channel = new FakeWaylandChannel();
        stateMachine = new ModifierKeyStateMachine();
        keyBar = new VirtualKeyBar(channel, stateMachine);
        keyboardToggled = false;
        keyBar.setOnSoftKeyboardToggleListener(() -> keyboardToggled = !keyboardToggled);
    }

    @Test
    public void testPressEscGeneratesPressAndRelease() {
        keyBar.pressEsc();

        assertEquals(2, channel.events.size());
        KeyEvent press = (KeyEvent) channel.events.get(0);
        KeyEvent release = (KeyEvent) channel.events.get(1);

        assertEquals(KeyEvent.Action.PRESS, press.action());
        assertEquals(KeyEvent.KEY_ESC, press.keyCode());
        assertEquals(KeyEvent.MOD_NONE, press.modifiers());

        assertEquals(KeyEvent.Action.RELEASE, release.action());
        assertEquals(KeyEvent.KEY_ESC, release.keyCode());
        assertEquals(KeyEvent.MOD_NONE, release.modifiers());
    }

    @Test
    public void testPressTabGeneratesPressAndRelease() {
        keyBar.pressTab();

        assertEquals(2, channel.events.size());
        KeyEvent press = (KeyEvent) channel.events.get(0);
        assertEquals(KeyEvent.KEY_TAB, press.keyCode());
    }

    @Test
    public void testDirectionalKeys() {
        keyBar.pressUp();
        keyBar.pressDown();
        keyBar.pressLeft();
        keyBar.pressRight();

        assertEquals(8, channel.events.size()); // 4 keys * 2 events
        assertEquals(KeyEvent.KEY_UP, ((KeyEvent) channel.events.get(0)).keyCode());
        assertEquals(KeyEvent.KEY_DOWN, ((KeyEvent) channel.events.get(2)).keyCode());
        assertEquals(KeyEvent.KEY_LEFT, ((KeyEvent) channel.events.get(4)).keyCode());
        assertEquals(KeyEvent.KEY_RIGHT, ((KeyEvent) channel.events.get(6)).keyCode());
    }

    @Test
    public void testModifierCombinationWithAutoRelease() {
        // 1. Click CTRL -> latched
        keyBar.pressModifier(ModifierKey.CTRL);
        assertEquals(ModifierState.LATCHED, stateMachine.getState(ModifierKey.CTRL));

        // 2. Press 'C' key (keyCode 46)
        keyBar.pressKey(KeyEvent.KEY_C);

        assertEquals(2, channel.events.size());
        KeyEvent press = (KeyEvent) channel.events.get(0);
        KeyEvent release = (KeyEvent) channel.events.get(1);

        assertEquals(KeyEvent.KEY_C, press.keyCode());
        assertEquals(KeyEvent.MOD_CTRL, press.modifiers());
        assertEquals(KeyEvent.KEY_C, release.keyCode());
        assertEquals(KeyEvent.MOD_CTRL, release.modifiers());

        // Latched CTRL must now be automatically released
        assertEquals(ModifierState.RELEASED, stateMachine.getState(ModifierKey.CTRL));

        // 3. Next key press should have no modifiers
        channel.events.clear();
        keyBar.pressKey(KeyEvent.KEY_A);
        assertEquals(KeyEvent.MOD_NONE, ((KeyEvent) channel.events.get(0)).modifiers());
    }

    @Test
    public void testModifierLockedPersistsAcrossMultipleKeys() {
        // Double-click ALT within 200ms -> LOCKED
        keyBar.pressModifier(ModifierKey.ALT, 1000L);
        keyBar.pressModifier(ModifierKey.ALT, 1200L);
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.ALT));

        // Press Tab (Alt+Tab)
        keyBar.pressTab();
        assertEquals(KeyEvent.MOD_ALT, ((KeyEvent) channel.events.get(0)).modifiers());
        // ALT stays LOCKED
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.ALT));

        // Press Tab again
        channel.events.clear();
        keyBar.pressTab();
        assertEquals(KeyEvent.MOD_ALT, ((KeyEvent) channel.events.get(0)).modifiers());
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.ALT));

        // Third click releases ALT
        keyBar.pressModifier(ModifierKey.ALT, 2000L);
        assertEquals(ModifierState.RELEASED, stateMachine.getState(ModifierKey.ALT));
    }

    @Test
    public void testToggleSoftKeyboardCallback() {
        assertFalse(keyboardToggled);
        keyBar.toggleSoftKeyboard();
        assertTrue(keyboardToggled);
        keyBar.toggleSoftKeyboard();
        assertFalse(keyboardToggled);
    }
}
