package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.StopOutcome;
import java.util.Objects;

/**
 * OPT-05: maps the AVF engine's stop vocabularies onto the shared
 * {@link StopOutcome}. Pure functions; read-only references to the existing
 * production types ({@link AvfStopResult}, {@link AvfEngineException} reason
 * codes, {@link AvfLaunchTrace.Stage}); no call site behavior changes.
 *
 * <p>Mapping discipline: UNCONFIRMED evidence stays unconfirmed — nothing in
 * this adapter can produce a Confirmed from an Unconfirmed input. Until a
 * real owner confirms the stop, instance metadata stays RUNNING, the session
 * owner is preserved and a restart is forbidden; only a retry owned by the
 * same session may later settle the outcome.</p>
 */
public final class AvfStopOutcomeAdapters {
    private AvfStopOutcomeAdapters() {}

    /**
     * Exhaustive mapping of {@link AvfStopResult}: CONFIRMED to Confirmed,
     * UNCONFIRMED to Unconfirmed (stage "UNCONFIRMED"; the engine result alone
     * carries no diagnostics), ALREADY_STOPPED to AlreadyStopped.
     */
    public static StopOutcome toStopOutcome(AvfStopResult result) {
        Objects.requireNonNull(result, "result");
        switch (result) {
            case CONFIRMED:
                return StopOutcome.Confirmed.INSTANCE;
            case UNCONFIRMED:
                return StopOutcome.unconfirmedWithoutDiagnostics("UNCONFIRMED");
            case ALREADY_STOPPED:
                return StopOutcome.AlreadyStopped.INSTANCE;
            default:
                throw new IllegalStateException("unmapped AvfStopResult: " + result);
        }
    }

    /**
     * Maps the typed engine failure reason onto the shared vocabulary. Only
     * {@link AvfEngineException#VM_STOP_UNCONFIRMED} is a stop result; every
     * other reason describes a launch failure, not a stop outcome, so it maps
     * to {@code null}. The stage carries the engine's own reason code verbatim
     * for traceability.
     */
    public static StopOutcome toStopOutcomeFromEngineReason(String reason) {
        if (reason == null) return null;
        if (AvfEngineException.VM_STOP_UNCONFIRMED.equals(reason)) {
            return StopOutcome.unconfirmedWithoutDiagnostics(reason);
        }
        return null;
    }

    /**
     * Maps the engine launch-trace stage onto the shared vocabulary. Only the
     * two stop terminal stages are stop outcomes: STOP_CONFIRMED to Confirmed,
     * STOP_UNCONFIRMED to Unconfirmed; every other stage (including the
     * STOP_REQUESTED intent) is not a stop result and maps to {@code null}.
     */
    public static StopOutcome toStopOutcome(AvfLaunchTrace.Stage stage) {
        Objects.requireNonNull(stage, "stage");
        switch (stage) {
            case STOP_CONFIRMED:
                return StopOutcome.Confirmed.INSTANCE;
            case STOP_UNCONFIRMED:
                return StopOutcome.unconfirmedWithoutDiagnostics("STOP_UNCONFIRMED");
            default:
                return null;
        }
    }
}
