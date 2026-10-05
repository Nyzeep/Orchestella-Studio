package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.droidlayer.ModifierKeyStateMachine.ModifierKey;
import io.github.nyzeep.droidlayer.ModifierKeyStateMachine.ModifierState;
import io.github.nyzeep.droidlayer.runtime.contract.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests verifying modifier key latching, double-click locking, combo auto-release,
 * and concurrent multi-modifier state machine transitions.
 */
public final class ModifierKeyStateMachineTest {

    private ModifierKeyStateMachine stateMachine;
    private List<String> transitionLog;

    @Before
    public void setUp() {
        stateMachine = new ModifierKeyStateMachine();
        transitionLog = new ArrayList<>();
        stateMachine.addListener((key, state, activeMods) ->
                transitionLog.add(key + "->" + state + "(mods=" + activeMods + ")"));
    }

    @Test
    public void testInitialStateIsReleased() {
        for (ModifierKey key : ModifierKey.values()) {
            assertEquals(ModifierState.RELEASED, stateMachine.getState(key));
            assertFalse(stateMachine.isModifierActive(key));
        }
        assertEquals(KeyEvent.MOD_NONE, stateMachine.getActiveModifiers());
    }

    @Test
    public void testSingleClickEntersLatchedState() {
        stateMachine.onModifierClicked(ModifierKey.CTRL, 1000L);
        assertEquals(ModifierState.LATCHED, stateMachine.getState(ModifierKey.CTRL));
        assertTrue(stateMachine.isModifierActive(ModifierKey.CTRL));
        assertEquals(KeyEvent.MOD_CTRL, stateMachine.getActiveModifiers());
        assertEquals(1, transitionLog.size());
        assertEquals("CTRL->LATCHED(mods=2)", transitionLog.get(0));
    }

    @Test
    public void testDoubleClickEntersLockedState() {
        // First click at t=1000
        stateMachine.onModifierClicked(ModifierKey.CTRL, 1000L);
        assertEquals(ModifierState.LATCHED, stateMachine.getState(ModifierKey.CTRL));

        // Second click at t=1200 (interval 200ms < 350ms threshold)
        stateMachine.onModifierClicked(ModifierKey.CTRL, 1200L);
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.CTRL));
        assertTrue(stateMachine.isModifierActive(ModifierKey.CTRL));
        assertEquals(KeyEvent.MOD_CTRL, stateMachine.getActiveModifiers());
    }

    @Test
    public void testClickLockedModifierReleasesIt() {
        stateMachine.onModifierClicked(ModifierKey.ALT, 1000L);
        stateMachine.onModifierClicked(ModifierKey.ALT, 1150L); // locked
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.ALT));

        // Third click at t=2000 unlocks it back to RELEASED
        stateMachine.onModifierClicked(ModifierKey.ALT, 2000L);
        assertEquals(ModifierState.RELEASED, stateMachine.getState(ModifierKey.ALT));
        assertFalse(stateMachine.isModifierActive(ModifierKey.ALT));
        assertEquals(KeyEvent.MOD_NONE, stateMachine.getActiveModifiers());
    }

    @Test
    public void testClickLatchedModifierAfterTimeoutReleasesIt() {
        stateMachine.onModifierClicked(ModifierKey.SHIFT, 1000L);
        assertEquals(ModifierState.LATCHED, stateMachine.getState(ModifierKey.SHIFT));

        // Click again after 500ms (> 350ms threshold) toggles it off
        stateMachine.onModifierClicked(ModifierKey.SHIFT, 1500L);
        assertEquals(ModifierState.RELEASED, stateMachine.getState(ModifierKey.SHIFT));
        assertEquals(KeyEvent.MOD_NONE, stateMachine.getActiveModifiers());
    }

    @Test
    public void testNonModifierKeyConsumesLatchedModifiers() {
        stateMachine.onModifierClicked(ModifierKey.CTRL, 1000L);
        assertEquals(ModifierState.LATCHED, stateMachine.getState(ModifierKey.CTRL));
        assertEquals(KeyEvent.MOD_CTRL, stateMachine.getActiveModifiers());

        // Pressing a non-modifier key (e.g. 'C')
        stateMachine.onNonModifierKeyPressed();

        // CTRL should be automatically released back to RELEASED
        assertEquals(ModifierState.RELEASED, stateMachine.getState(ModifierKey.CTRL));
        assertEquals(KeyEvent.MOD_NONE, stateMachine.getActiveModifiers());
    }

    @Test
    public void testNonModifierKeyDoesNotReleaseLockedModifiers() {
        stateMachine.onModifierClicked(ModifierKey.SUPER, 1000L);
        stateMachine.onModifierClicked(ModifierKey.SUPER, 1200L); // LOCKED
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.SUPER));

        // Multiple non-modifier key presses
        stateMachine.onNonModifierKeyPressed();
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.SUPER));
        assertEquals(KeyEvent.MOD_SUPER, stateMachine.getActiveModifiers());

        stateMachine.onNonModifierKeyPressed();
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.SUPER));
        assertEquals(KeyEvent.MOD_SUPER, stateMachine.getActiveModifiers());
    }

    @Test
    public void testConcurrentModifiersLatchAndLock() {
        // Latch CTRL
        stateMachine.onModifierClicked(ModifierKey.CTRL, 1000L);
        // Lock SHIFT
        stateMachine.onModifierClicked(ModifierKey.SHIFT, 1050L);
        stateMachine.onModifierClicked(ModifierKey.SHIFT, 1200L);
        // Latch ALT
        stateMachine.onModifierClicked(ModifierKey.ALT, 1300L);

        int expectedMask = KeyEvent.MOD_CTRL | KeyEvent.MOD_SHIFT | KeyEvent.MOD_ALT;
        assertEquals(expectedMask, stateMachine.getActiveModifiers());
        assertEquals(ModifierState.LATCHED, stateMachine.getState(ModifierKey.CTRL));
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.SHIFT));
        assertEquals(ModifierState.LATCHED, stateMachine.getState(ModifierKey.ALT));

        // Now a regular key arrives (e.g. Tab)
        stateMachine.onNonModifierKeyPressed();

        // CTRL and ALT should be released; SHIFT must remain LOCKED
        assertEquals(ModifierState.RELEASED, stateMachine.getState(ModifierKey.CTRL));
        assertEquals(ModifierState.LOCKED, stateMachine.getState(ModifierKey.SHIFT));
        assertEquals(ModifierState.RELEASED, stateMachine.getState(ModifierKey.ALT));
        assertEquals(KeyEvent.MOD_SHIFT, stateMachine.getActiveModifiers());
    }

    @Test
    public void testResetClearsAllModifiers() {
        stateMachine.onModifierClicked(ModifierKey.CTRL, 1000L);
        stateMachine.onModifierClicked(ModifierKey.ALT, 1000L);
        stateMachine.onModifierClicked(ModifierKey.ALT, 1100L); // locked

        stateMachine.reset();
        assertEquals(KeyEvent.MOD_NONE, stateMachine.getActiveModifiers());
        for (ModifierKey key : ModifierKey.values()) {
            assertEquals(ModifierState.RELEASED, stateMachine.getState(key));
        }
    }
}
