package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.StopOutcome;

/**
 * OPT-05: exhaustive mapping contract from the AVF engine's stop vocabularies
 * ({@link AvfStopResult}, {@link AvfEngineException} reason codes and
 * {@link AvfLaunchTrace.Stage} stop stages) onto the shared
 * {@link StopOutcome}. Pure vocabulary unification only; no call site changes.
 * An UNCONFIRMED stop never upgrades to Confirmed.
 */
public final class AvfStopOutcomeAdapterTest {
    private AvfStopOutcomeAdapterTest() {}

    public static void main(String[] arguments) {
        mapsEveryAvfStopResultExactlyOnce();
        unconfirmedAvfStopResultKeepsUnconfirmedFields();
        mapsUnconfirmedEngineReasonOnly();
        mapsLaunchTraceStopStagesOnly();
        unconfirmedNeverMapsToConfirmed();
        rejectsNullInputs();
        System.out.println("AvfStopOutcomeAdapterTest: OK");
    }

    private static void mapsEveryAvfStopResultExactlyOnce() {
        assertSame(StopOutcome.Confirmed.INSTANCE,
                AvfStopOutcomeAdapters.toStopOutcome(AvfStopResult.CONFIRMED));
        assertEquals(
                new StopOutcome.Unconfirmed("UNCONFIRMED",
                        StopOutcome.UNKNOWN_PID, 0L, StopOutcome.UNKNOWN_EXIT_CODE),
                AvfStopOutcomeAdapters.toStopOutcome(AvfStopResult.UNCONFIRMED),
                "UNCONFIRMED mapping");
        assertSame(StopOutcome.AlreadyStopped.INSTANCE,
                AvfStopOutcomeAdapters.toStopOutcome(AvfStopResult.ALREADY_STOPPED));
    }

    private static void unconfirmedAvfStopResultKeepsUnconfirmedFields() {
        StopOutcome.Unconfirmed outcome = (StopOutcome.Unconfirmed)
                AvfStopOutcomeAdapters.toStopOutcome(AvfStopResult.UNCONFIRMED);
        assertEquals("UNCONFIRMED", outcome.getStage(), "stage");
        assertEquals(StopOutcome.UNKNOWN_PID, outcome.getPid(), "pid");
        assertEquals(0L, outcome.getGrace(), "grace");
        assertEquals(StopOutcome.UNKNOWN_EXIT_CODE, outcome.getExitCode(), "exitCode");
    }

    private static void mapsUnconfirmedEngineReasonOnly() {
        StopOutcome outcome = AvfStopOutcomeAdapters.toStopOutcomeFromEngineReason(
                AvfEngineException.VM_STOP_UNCONFIRMED);
        StopOutcome.Unconfirmed unconfirmed = (StopOutcome.Unconfirmed) outcome;
        assertEquals(AvfEngineException.VM_STOP_UNCONFIRMED, unconfirmed.getStage(), "stage");
        // Every other engine reason is a launch failure, not a stop result.
        String[] nonStopReasons = {
            AvfEngineException.REFLECTION_UNAVAILABLE,
            AvfEngineException.VM_MANAGER_UNAVAILABLE,
            AvfEngineException.VM_CREATE_FAILED,
            AvfEngineException.CONFIG_MISMATCH,
            AvfEngineException.HARDWARE_UNSUPPORTED,
            AvfEngineException.PERMISSION_DENIED,
            AvfEngineException.VSOCK_CONNECT_FAILED,
            AvfEngineException.INVALID_LAUNCH_CONFIG,
            AvfEngineException.NETWORK_BACKEND_UNAVAILABLE,
            "UNRECOGNIZED_FUTURE_REASON",
        };
        for (String reason : nonStopReasons) {
            assertNull(reason, AvfStopOutcomeAdapters.toStopOutcomeFromEngineReason(reason));
        }
        assertNull("null reason", AvfStopOutcomeAdapters.toStopOutcomeFromEngineReason(null));
    }

    private static void mapsLaunchTraceStopStagesOnly() {
        assertSame(StopOutcome.Confirmed.INSTANCE,
                AvfStopOutcomeAdapters.toStopOutcome(AvfLaunchTrace.Stage.STOP_CONFIRMED));
        assertEquals(
                new StopOutcome.Unconfirmed("STOP_UNCONFIRMED",
                        StopOutcome.UNKNOWN_PID, 0L, StopOutcome.UNKNOWN_EXIT_CODE),
                AvfStopOutcomeAdapters.toStopOutcome(AvfLaunchTrace.Stage.STOP_UNCONFIRMED),
                "trace stage mapping");
        AvfLaunchTrace.Stage[] nonStopStages = {
            AvfLaunchTrace.Stage.LAUNCH_REQUESTED,
            AvfLaunchTrace.Stage.VM_CREATED,
            AvfLaunchTrace.Stage.RUN_RETURNED,
            AvfLaunchTrace.Stage.STOP_REQUESTED,
            AvfLaunchTrace.Stage.EXIT_OBSERVED,
        };
        for (AvfLaunchTrace.Stage stage : nonStopStages) {
            assertNull(stage.name(), AvfStopOutcomeAdapters.toStopOutcome(stage));
        }
    }

    private static void unconfirmedNeverMapsToConfirmed() {
        StopOutcome fromResult = AvfStopOutcomeAdapters.toStopOutcome(AvfStopResult.UNCONFIRMED);
        StopOutcome fromReason = AvfStopOutcomeAdapters.toStopOutcomeFromEngineReason(
                AvfEngineException.VM_STOP_UNCONFIRMED);
        StopOutcome fromStage =
                AvfStopOutcomeAdapters.toStopOutcome(AvfLaunchTrace.Stage.STOP_UNCONFIRMED);
        for (StopOutcome outcome : new StopOutcome[] {fromResult, fromReason, fromStage}) {
            assertTrue(! (outcome instanceof StopOutcome.Confirmed),
                    "UNCONFIRMED must never map to Confirmed");
        }
    }

    private static void rejectsNullInputs() {
        try {
            AvfStopOutcomeAdapters.toStopOutcome((AvfStopResult) null);
            fail("null AvfStopResult must be rejected");
        } catch (NullPointerException expected) { /* rejected */ }
        try {
            AvfStopOutcomeAdapters.toStopOutcome((AvfLaunchTrace.Stage) null);
            fail("null AvfLaunchTrace.Stage must be rejected");
        } catch (NullPointerException expected) { /* rejected */ }
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> was <" + actual + ">");
        }
    }

    private static void assertSame(Object expected, Object actual) {
        if (expected != actual) {
            throw new AssertionError("expected same <" + expected + "> was <" + actual + ">");
        }
    }

    private static void assertNull(String what, Object actual) {
        if (actual != null) {
            throw new AssertionError(what + " must map to null, was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
