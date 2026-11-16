package io.github.nyzeep.orchestella.runtime.instance.duallane;

import io.github.nyzeep.orchestella.runtime.contract.StopOutcome;
import java.util.Objects;

/**
 * OPT-05: maps the dual-lane strategy-neutral stop outcome onto the shared
 * {@link StopOutcome} vocabulary. Pure function; reads only the existing
 * production enum and never changes any call site's return value.
 *
 * <p>Mapping discipline: UNCONFIRMED evidence stays unconfirmed. The result
 * carries no invented diagnostics; callers that own richer facts (supervisor
 * diagnostics, launch stage, exit code) map through their own adapters. Until
 * a real owner confirms the stop, the instance metadata stays RUNNING, the
 * session owner is preserved and a restart is forbidden.</p>
 */
public final class LaneStopOutcomeAdapters {
    private LaneStopOutcomeAdapters() {}

    /**
     * Exhaustive mapping of {@link LaneStopOutcome}:
     * CONFIRMED to Confirmed, UNCONFIRMED to Unconfirmed (no diagnostics
     * available at this layer), ALREADY_STOPPED to AlreadyStopped.
     */
    public static StopOutcome toStopOutcome(LaneStopOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        switch (outcome) {
            case CONFIRMED:
                return StopOutcome.Confirmed.INSTANCE;
            case UNCONFIRMED:
                return StopOutcome.unconfirmedWithoutDiagnostics("UNCONFIRMED");
            case ALREADY_STOPPED:
                return StopOutcome.AlreadyStopped.INSTANCE;
            default:
                throw new IllegalStateException("unmapped LaneStopOutcome: " + outcome);
        }
    }
}
