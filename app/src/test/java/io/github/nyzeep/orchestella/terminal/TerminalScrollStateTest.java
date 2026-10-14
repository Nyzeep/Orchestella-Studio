package io.github.nyzeep.orchestella.terminal;

import static org.junit.Assert.*;
import org.junit.Test;

public final class TerminalScrollStateTest {
    @Test public void initialBootOutputFollowsTailOnceWithoutFocusScrolling() {
        TerminalScrollState state = new TerminalScrollState();
        assertEquals(2000, state.targetY(2000, false));
        assertEquals(2500, state.targetY(2500, false));
    }
    @Test public void userReadingAnchorSurvivesNewOutputAndViewportResize() {
        TerminalScrollState state = new TerminalScrollState();
        state.beginTouch(1200); state.userScrolled(700, 2000, 24); state.endTouch(700, 2000, 24);
        assertEquals(TerminalScrollState.Mode.READING, state.mode());
        assertEquals(700, state.targetY(3000, false));
        assertEquals(700, state.targetY(3200, false));
    }
    @Test public void dragAndSelectionOwnTheViewportUntilTheyFinish() {
        TerminalScrollState state = new TerminalScrollState();
        state.beginTouch(800); state.userScrolled(600, 1500, 24);
        assertEquals(-1, state.targetY(2000, false));
        state.endTouch(600, 1500, 24); state.selectionChanged(true, 600);
        assertEquals(-1, state.targetY(2000, false));
        state.selectionChanged(false, 600);
        assertEquals(600, state.targetY(2000, false));
    }
    @Test public void boundedEvictionMovesAnchorByRowsWithoutJumpingToTail() {
        TerminalScrollState state = new TerminalScrollState();
        state.userScrolled(1200, 3000, 24);
        state.rowsEvicted(10, 20);
        assertEquals(1000, state.targetY(3000, false));
        assertEquals(TerminalScrollState.Mode.READING, state.mode());
    }
    @Test public void incidentalLayoutTopDoesNotChangeTheCapturedReadingIntent() {
        TerminalScrollState state = new TerminalScrollState();
        state.userScrolled(800, 3000, 24);
        state.captureAnchor(800);
        // Android setText/requestChildFocus can temporarily scroll to 0. The view must
        // not call userScrolled or captureAnchor again during its pending render.
        assertEquals(800, state.targetY(3500, false));
        assertEquals(TerminalScrollState.Mode.READING, state.mode());
    }
    @Test public void explicitReturnToBottomRestoresFollowing() {
        TerminalScrollState state = new TerminalScrollState();
        state.userScrolled(700, 2000, 24);
        state.follow();
        assertEquals(3000, state.targetY(3000, false));
    }
    @Test public void clearStartsAFreshVisibleViewportWithoutPreviousEvictionOffset() {
        TerminalScrollState state = new TerminalScrollState();
        state.userScrolled(900, 3000, 24);
        state.displayCleared();
        assertEquals(0, state.targetY(0, false));
        assertEquals(200, state.targetY(200, false));
        assertEquals(TerminalScrollState.Mode.FOLLOWING, state.mode());
    }
    @Test public void alternateScreenUsesItsOwnViewportWithoutOverwritingReadingIntent() {
        TerminalScrollState state = new TerminalScrollState();
        state.userScrolled(600, 2000, 24);
        assertEquals(0, state.targetY(0, true));
        assertEquals(600, state.targetY(2000, false));
    }
}
