package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;

import io.github.nyzeep.orchestella.ModifierKeyStateMachine.ModifierState;
import org.junit.Test;

/**
 * G-03: the pure input logic split out of DesktopActivity — modifier key labels
 * (* latched, # locked) and the touch-sensitivity option index — keeps its exact
 * pre-split behavior.
 */
public final class DesktopInputControllerTest {

    @Test
    public void releasedModifierShowsBareLabel() {
        assertEquals("CTRL", DesktopInputController.modifierLabel("CTRL", ModifierState.RELEASED));
        assertEquals("SUPER", DesktopInputController.modifierLabel("SUPER", ModifierState.RELEASED));
    }

    @Test
    public void latchedModifierShowsStarSuffix() {
        assertEquals("ALT*", DesktopInputController.modifierLabel("ALT", ModifierState.LATCHED));
        assertEquals("SHIFT*", DesktopInputController.modifierLabel("SHIFT", ModifierState.LATCHED));
    }

    @Test
    public void lockedModifierShowsHashSuffix() {
        assertEquals("CTRL#", DesktopInputController.modifierLabel("CTRL", ModifierState.LOCKED));
        assertEquals("SUPER#", DesktopInputController.modifierLabel("SUPER", ModifierState.LOCKED));
    }

    @Test
    public void sensitivityIndexMatchesExactFactors() {
        assertEquals(0, DesktopInputController.sensitivityIndexFor(0.6));
        assertEquals(1, DesktopInputController.sensitivityIndexFor(0.8));
        assertEquals(2, DesktopInputController.sensitivityIndexFor(1.0));
        assertEquals(3, DesktopInputController.sensitivityIndexFor(1.3));
        assertEquals(4, DesktopInputController.sensitivityIndexFor(1.6));
    }

    @Test
    public void sensitivityIndexSnapsToNearestOptionWithinOneTenth() {
        assertEquals(1, DesktopInputController.sensitivityIndexFor(0.85));
        assertEquals(2, DesktopInputController.sensitivityIndexFor(0.95));
        assertEquals(2, DesktopInputController.sensitivityIndexFor(1.05));
        assertEquals(3, DesktopInputController.sensitivityIndexFor(1.35));
    }

    @Test
    public void sensitivityIndexDefaultsToBalancedWhenNothingMatches() {
        // 0.7 lands on 0.6 in binary double (0.09999... < 0.1) — the pre-split formula's
        // own behavior; only factors beyond every option fall through to the default.
        assertEquals(0, DesktopInputController.sensitivityIndexFor(0.7));
        assertEquals(2, DesktopInputController.sensitivityIndexFor(5.0));
        assertEquals(2, DesktopInputController.sensitivityIndexFor(0.0));
    }

    @Test
    public void sensitivityFactorTableKeepsItsDialogCopyOrder() {
        double[] factors = DesktopInputController.SENSITIVITY_FACTORS;
        assertEquals(5, factors.length);
        assertEquals(0.6, factors[0], 1e-9);
        assertEquals(1.0, factors[2], 1e-9);
        assertEquals(1.6, factors[4], 1e-9);
    }
}
