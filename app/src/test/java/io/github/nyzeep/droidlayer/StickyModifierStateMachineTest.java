package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.droidlayer.StickyModifierStateMachine.Modifier;
import io.github.nyzeep.droidlayer.StickyModifierStateMachine.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests verifying sticky modifier state machine transitions:
 * single-click latching, combination consumption reset, double-click locking,
 * manual cancellation, concurrent modifiers, and control character ASCII transformation.
 */
public final class StickyModifierStateMachineTest {

    private StickyModifierStateMachine stateMachine;
    private List<String> eventLog;

    @Before
    public void setUp() {
        stateMachine = new StickyModifierStateMachine();
        eventLog = new ArrayList<>();
        stateMachine.addListener((modifier, state) -> eventLog.add(modifier + "->" + state));
    }

    @Test
    public void testInitialStateIsOff() {
        assertEquals(State.OFF, stateMachine.getState(Modifier.CTRL));
        assertEquals(State.OFF, stateMachine.getState(Modifier.ALT));
        assertFalse(stateMachine.isCtrlActive());
        assertFalse(stateMachine.isAltActive());
        assertFalse(stateMachine.isModifierActive(Modifier.CTRL));
    }

    @Test
    public void testSingleClickEntersLatchedState() {
        stateMachine.onModifierClicked(Modifier.CTRL, 1000L);
        assertEquals(State.LATCHED, stateMachine.getState(Modifier.CTRL));
        assertTrue(stateMachine.isCtrlActive());
        assertTrue(stateMachine.isModifierActive(Modifier.CTRL));
        assertEquals(1, eventLog.size());
        assertEquals("CTRL->LATCHED", eventLog.get(0));
    }

    @Test
    public void testCombinationKeyConsumesLatchedModifierAndResetsToOff() {
        stateMachine.onModifierClicked(Modifier.CTRL, 1000L);
        assertTrue(stateMachine.isCtrlActive());

        // When a combination character (e.g., 'c') is typed, it is transformed into SIGINT (\x03)
        byte ctrlC = StickyModifierStateMachine.toControlCode('c');
        assertEquals(0x03, ctrlC);

        // State machine consumes the latched modifier
        stateMachine.consumeLatched();

        // Must automatically reset back to OFF
        assertEquals(State.OFF, stateMachine.getState(Modifier.CTRL));
        assertFalse(stateMachine.isCtrlActive());
        assertEquals(2, eventLog.size());
        assertEquals("CTRL->OFF", eventLog.get(1));
    }

    @Test
    public void testDoubleClickEntersLockedState() {
        // First click at t=1000ms
        stateMachine.onModifierClicked(Modifier.CTRL, 1000L);
        assertEquals(State.LATCHED, stateMachine.getState(Modifier.CTRL));

        // Second click at t=1200ms (< 350ms timeout)
        stateMachine.onModifierClicked(Modifier.CTRL, 1200L);
        assertEquals(State.LOCKED, stateMachine.getState(Modifier.CTRL));
        assertTrue(stateMachine.isCtrlActive());
    }

    @Test
    public void testLockedModifierPersistsAcrossMultipleKeyConsumptions() {
        stateMachine.onModifierClicked(Modifier.CTRL, 1000L);
        stateMachine.onModifierClicked(Modifier.CTRL, 1200L);
        assertEquals(State.LOCKED, stateMachine.getState(Modifier.CTRL));

        // Consume characters multiple times
        stateMachine.consumeLatched();
        assertEquals(State.LOCKED, stateMachine.getState(Modifier.CTRL));
        assertTrue(stateMachine.isCtrlActive());

        stateMachine.consumeLatched();
        assertEquals(State.LOCKED, stateMachine.getState(Modifier.CTRL));
        assertTrue(stateMachine.isCtrlActive());
    }

    @Test
    public void testClickingLockedModifierUnlocksToOff() {
        stateMachine.onModifierClicked(Modifier.CTRL, 1000L);
        stateMachine.onModifierClicked(Modifier.CTRL, 1200L); // LOCKED
        assertEquals(State.LOCKED, stateMachine.getState(Modifier.CTRL));

        // Third click unlocks
        stateMachine.onModifierClicked(Modifier.CTRL, 2000L);
        assertEquals(State.OFF, stateMachine.getState(Modifier.CTRL));
        assertFalse(stateMachine.isCtrlActive());
    }

    @Test
    public void testClickingLatchedAfterTimeoutTogglesOff() {
        stateMachine.onModifierClicked(Modifier.ALT, 1000L);
        assertEquals(State.LATCHED, stateMachine.getState(Modifier.ALT));

        // Click again after 600ms (> 350ms threshold)
        stateMachine.onModifierClicked(Modifier.ALT, 1600L);
        assertEquals(State.OFF, stateMachine.getState(Modifier.ALT));
        assertFalse(stateMachine.isAltActive());
    }

    @Test
    public void testConcurrentModifiers() {
        // Latch CTRL
        stateMachine.onModifierClicked(Modifier.CTRL, 1000L);
        // Lock ALT
        stateMachine.onModifierClicked(Modifier.ALT, 1050L);
        stateMachine.onModifierClicked(Modifier.ALT, 1200L);

        assertEquals(State.LATCHED, stateMachine.getState(Modifier.CTRL));
        assertEquals(State.LOCKED, stateMachine.getState(Modifier.ALT));
        assertTrue(stateMachine.isCtrlActive());
        assertTrue(stateMachine.isAltActive());

        // Consuming combination resets latched CTRL, while ALT remains LOCKED
        stateMachine.consumeLatched();
        assertEquals(State.OFF, stateMachine.getState(Modifier.CTRL));
        assertEquals(State.LOCKED, stateMachine.getState(Modifier.ALT));
        assertFalse(stateMachine.isCtrlActive());
        assertTrue(stateMachine.isAltActive());
    }

    @Test
    public void testResetClearsAllModifiers() {
        stateMachine.onModifierClicked(Modifier.CTRL, 1000L);
        stateMachine.onModifierClicked(Modifier.ALT, 1000L);
        stateMachine.onModifierClicked(Modifier.ALT, 1200L); // LOCKED

        stateMachine.reset();
        assertEquals(State.OFF, stateMachine.getState(Modifier.CTRL));
        assertEquals(State.OFF, stateMachine.getState(Modifier.ALT));
        assertFalse(stateMachine.isCtrlActive());
        assertFalse(stateMachine.isAltActive());
    }

    @Test
    public void testAsciiControlCodeTransformations() {
        // Standard ASCII control sequences
        assertEquals(1, StickyModifierStateMachine.toControlCode('a'));
        assertEquals(1, StickyModifierStateMachine.toControlCode('A'));
        assertEquals(3, StickyModifierStateMachine.toControlCode('c')); // SIGINT
        assertEquals(3, StickyModifierStateMachine.toControlCode('C'));
        assertEquals(4, StickyModifierStateMachine.toControlCode('d')); // EOF
        assertEquals(4, StickyModifierStateMachine.toControlCode('D'));
        assertEquals(26, StickyModifierStateMachine.toControlCode('z')); // SIGTSTP
        assertEquals(26, StickyModifierStateMachine.toControlCode('Z'));

        // Special characters
        assertEquals(27, StickyModifierStateMachine.toControlCode('[')); // ESC
        assertEquals(28, StickyModifierStateMachine.toControlCode('\\'));
        assertEquals(29, StickyModifierStateMachine.toControlCode(']'));
        assertEquals(30, StickyModifierStateMachine.toControlCode('^'));
        assertEquals(31, StickyModifierStateMachine.toControlCode('_'));
        assertEquals(127, StickyModifierStateMachine.toControlCode('?')); // DEL
        assertEquals(0, StickyModifierStateMachine.toControlCode('@'));
        assertEquals(0, StickyModifierStateMachine.toControlCode(' '));
    }

    @Test
    public void testAltEscapeSequences() {
        assertArrayEquals(new byte[]{0x1B, 'f'}, StickyModifierStateMachine.toAltSequence('f'));
        assertArrayEquals(new byte[]{0x1B, 'b'}, StickyModifierStateMachine.toAltSequence('b'));
        assertArrayEquals(new byte[]{0x1B, 'd'}, StickyModifierStateMachine.toAltSequence('d'));
    }
}
