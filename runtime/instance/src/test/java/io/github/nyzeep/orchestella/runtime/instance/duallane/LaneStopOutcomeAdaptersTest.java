package io.github.nyzeep.orchestella.runtime.instance.duallane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.contract.StopOutcome;
import org.junit.Test;

/**
 * OPT-05: exhaustive {@link LaneStopOutcome} to {@link StopOutcome} mapping
 * contract. Pure vocabulary unification only; no call site changes. The
 * mapping must keep every UNCONFIRMED honest: it never upgrades to Confirmed.
 */
public class LaneStopOutcomeAdaptersTest {

    @Test
    public void mapsEveryLaneStopOutcomeExactlyOnce() {
        assertSame(StopOutcome.Confirmed.INSTANCE,
                LaneStopOutcomeAdapters.toStopOutcome(LaneStopOutcome.CONFIRMED));
        assertEquals(
                new StopOutcome.Unconfirmed("UNCONFIRMED",
                        StopOutcome.UNKNOWN_PID, 0L, StopOutcome.UNKNOWN_EXIT_CODE),
                LaneStopOutcomeAdapters.toStopOutcome(LaneStopOutcome.UNCONFIRMED));
        assertSame(StopOutcome.AlreadyStopped.INSTANCE,
                LaneStopOutcomeAdapters.toStopOutcome(LaneStopOutcome.ALREADY_STOPPED));
    }

    @Test
    public void unconfirmedMappingKeepsUnconfirmedFields() {
        StopOutcome outcome = LaneStopOutcomeAdapters.toStopOutcome(LaneStopOutcome.UNCONFIRMED);
        assertTrue(outcome instanceof StopOutcome.Unconfirmed);
        StopOutcome.Unconfirmed unconfirmed = (StopOutcome.Unconfirmed) outcome;
        assertEquals("UNCONFIRMED", unconfirmed.getStage());
        assertEquals(StopOutcome.UNKNOWN_PID, unconfirmed.getPid());
        assertEquals(0L, unconfirmed.getGrace());
        assertEquals(StopOutcome.UNKNOWN_EXIT_CODE, unconfirmed.getExitCode());
    }

    @Test
    public void unconfirmedNeverMapsToConfirmed() {
        for (LaneStopOutcome outcome : LaneStopOutcome.values()) {
            if (outcome == LaneStopOutcome.UNCONFIRMED) {
                assertTrue(!(LaneStopOutcomeAdapters.toStopOutcome(outcome)
                        instanceof StopOutcome.Confirmed));
            }
        }
    }

    @Test
    public void nullOutcomeIsRejected() {
        try {
            LaneStopOutcomeAdapters.toStopOutcome(null);
            fail("null LaneStopOutcome must be rejected");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage() != null && !expected.getMessage().isBlank());
        }
    }
}
