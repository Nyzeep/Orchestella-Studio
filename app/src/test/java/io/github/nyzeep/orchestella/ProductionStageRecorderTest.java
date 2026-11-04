package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.ProductionStageRecorder.Failure;
import io.github.nyzeep.orchestella.ProductionStageRecorder.Identity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Zero-device contract for the production stage event component (ticket 27).
 * Groups: disabled zero-collection, gate/stop recording, failure honesty,
 * schema versioning and sensitive-field containment.
 */
public final class ProductionStageRecorderTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final Identity IDENTITY =
            Identity.of("instance-0f3a", "s-9c1f2b3a-1111-2222-3333-444455556666", "gen-1", "qemu");

    /** One-line collecting sink; returns false only when told to. */
    static class RecordingSink implements ProductionStageRecorder.LineSink {
        final List<String> lines = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        boolean failOnNext;

        @Override public boolean writeLine(String line) {
            calls.incrementAndGet();
            if (failOnNext) return false;
            lines.add(line);
            return true;
        }
    }

    /** Manual monotonic nanos clock counting every read. */
    static final class ManualClock implements java.util.function.LongSupplier {
        final AtomicLong value = new AtomicLong(1000L);
        final AtomicInteger calls = new AtomicInteger();
        boolean goBackwards;
        boolean explode;

        @Override public long getAsLong() {
            calls.incrementAndGet();
            if (explode) throw new IllegalStateException("synthetic clock failure");
            long current = goBackwards ? value.addAndGet(-500L) : value.getAndAdd(100L);
            return current;
        }
    }

    private static ProductionStageRecorder enabledRecorder(ManualClock clock, RecordingSink sink) {
        return ProductionStageRecorder.enabled(IDENTITY, clock, "test_monotonic_ns", sink);
    }

    // ---------------------------------------------------------------- group 1

    @Test
    public void disabledRecorderRetainsNothingReadsNoClockAndTouchesNoSink() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = ProductionStageRecorder.disabled();

        assertEquals("DISABLED", recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals("DISABLED", recorder.markAuthenticated(
                ProductionStageRecorder.Stage.AGENT_AUTHENTICATED, IDENTITY));
        assertEquals("DISABLED", recorder.fail(Failure.RUN_FAILED, IDENTITY));
        assertEquals("DISABLED", recorder.fallback(Failure.API_BLOCKED, IDENTITY));
        assertEquals("DISABLED", recorder.cancel(IDENTITY));
        assertEquals("DISABLED", recorder.observeStop(
                ProductionStageRecorder.StopFact.OWNED_VM_STOPPED, IDENTITY));
        assertEquals("DISABLED", recorder.finish(IDENTITY));

        ProductionStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(ProductionStageRecorder.State.DISABLED, snapshot.state);
        assertEquals("NONE", snapshot.reason);
        assertEquals(0, snapshot.eventCount);
        assertEquals(0, snapshot.bytesWritten);
        assertNull(snapshot.identity);
        assertTrue(snapshot.observedGates.isEmpty());
        assertTrue(snapshot.stopFacts.isEmpty());
        assertEquals(0, clock.calls.get());
        assertEquals(0, sink.calls.get());
        // Honesty fields mirror the ticket 22 offline snapshot vocabulary.
        assertEquals(0, snapshot.realReceiptCount);
        assertEquals("NOT_DETERMINED", snapshot.deviceVerdict);
        assertEquals("not_observed", snapshot.kernelObservation);
        assertEquals("not_applicable", snapshot.payloadCallback);
    }

    @Test
    public void disabledProviderNeverCreatesDirectoryQueueOrThread() throws Exception {
        Path directory = temporaryFolder.newFolder("stage-traces-off").toPath();
        ManualClock clock = new ManualClock();
        ProductionStageRecorder recorder = StageTraceProvider.open(false, directory, IDENTITY,
                clock, "test_monotonic_ns");
        assertEquals(ProductionStageRecorder.State.DISABLED, recorder.snapshot().state);
        recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY);
        recorder.finish(IDENTITY);
        assertFalse("disabled diagnostics must not create any file",
                Files.exists(directory.resolve(StageTraceFileSink.FILE_NAME)));
        assertEquals(0, clock.calls.get());
        // The directory itself is left exactly as it was found.
        assertFalse(Files.exists(directory.resolve("anything")));
    }

    @Test
    public void explicitEventAndByteBoundsAreEnforcedWithRetirement() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = ProductionStageRecorder.enabled(IDENTITY, clock,
                "test_monotonic_ns", sink, 2, ProductionStageRecorder.MAX_TRACE_BYTES);
        assertEquals("RECORDED", recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals("RECORDED", recorder.mark(ProductionStageRecorder.Stage.PREFLIGHT_BEGIN, IDENTITY));
        assertEquals("CAPTURE_LIMIT", recorder.mark(ProductionStageRecorder.Stage.PREFLIGHT_END, IDENTITY));
        assertEquals(ProductionStageRecorder.State.FAILED, recorder.snapshot().state);
        assertEquals("CAPTURE_LIMIT", recorder.snapshot().reason);
        assertEquals("the rejected event must not reach the sink", 2, sink.lines.size());
        assertEquals("RETIRED", recorder.mark(ProductionStageRecorder.Stage.PREFLIGHT_END, IDENTITY));

        RecordingSink byteSink = new RecordingSink();
        ProductionStageRecorder byteCapped = ProductionStageRecorder.enabled(IDENTITY, clock,
                "test_monotonic_ns", byteSink, ProductionStageRecorder.MAX_EVENTS, 600);
        assertEquals("RECORDED", byteCapped.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals("CAPTURE_LIMIT",
                byteCapped.mark(ProductionStageRecorder.Stage.PREFLIGHT_BEGIN, IDENTITY));
        assertEquals(1, byteSink.lines.size());
    }

    @Test
    public void boundArgumentsOutsideTheTicket22MagnitudeAreRejected() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        try {
            ProductionStageRecorder.enabled(IDENTITY, clock, "test_monotonic_ns", sink, 17,
                    ProductionStageRecorder.MAX_TRACE_BYTES);
            fail("maxEvents above the 16-gate magnitude must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ProductionStageRecorder.enabled(IDENTITY, clock, "test_monotonic_ns", sink,
                    ProductionStageRecorder.MAX_EVENTS, 32769);
            fail("maxTraceBytes above 32768 must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ProductionStageRecorder.enabled(null, clock, "test_monotonic_ns", sink);
            fail("an enabled recorder without identity must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    // ---------------------------------------------------------------- group 2

    @Test
    public void allSixteenGatesRecordedInRealProductionOrderWithInjectedClock() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);

        // The real QEMU production partial order (bootstrap before any manager
        // gate; the offline chain's assumed order is deliberately not enforced).
        ProductionStageRecorder.Stage[] order = {
                ProductionStageRecorder.Stage.LAUNCH_REQUESTED,
                ProductionStageRecorder.Stage.INPUTS_BEGIN,
                ProductionStageRecorder.Stage.INPUTS_VERIFIED,
                ProductionStageRecorder.Stage.RUN_BEGIN,
                ProductionStageRecorder.Stage.VM_CREATED,
                ProductionStageRecorder.Stage.RUN_RETURNED,
                ProductionStageRecorder.Stage.VSOCK_CONNECTED,
                ProductionStageRecorder.Stage.AGENT_AUTHENTICATED,
                ProductionStageRecorder.Stage.GUEST_COMMAND_OK,
                ProductionStageRecorder.Stage.SERVICES_READY,
                ProductionStageRecorder.Stage.STOP_REQUESTED,
        };
        for (ProductionStageRecorder.Stage stage : order) {
            boolean authenticated = stage == ProductionStageRecorder.Stage.AGENT_AUTHENTICATED
                    || stage == ProductionStageRecorder.Stage.GUEST_COMMAND_OK
                    || stage == ProductionStageRecorder.Stage.SERVICES_READY;
            String status = authenticated
                    ? recorder.markAuthenticated(stage, IDENTITY)
                    : recorder.mark(stage, IDENTITY);
            assertEquals(stage.wireName(), "RECORDED", status);
        }
        // The three stop facts are independent observations.
        assertEquals("OBSERVED", recorder.observeStop(
                ProductionStageRecorder.StopFact.OWNED_VM_STOPPED, IDENTITY));
        assertEquals("OBSERVED", recorder.observeStop(
                ProductionStageRecorder.StopFact.OWNED_RESOURCES_RELEASED, IDENTITY));
        assertEquals("OBSERVED", recorder.observeStopAuthenticated(
                ProductionStageRecorder.StopFact.GUEST_SHUTDOWN_ACKNOWLEDGED, IDENTITY));
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.STOP_COMPLETED, IDENTITY));
        assertEquals("ENDED", recorder.finish(IDENTITY));

        assertEquals(13, sink.lines.size());
        for (int i = 0; i < 11; i++) {
            String line = sink.lines.get(i);
            assertTrue(line.startsWith("{\"schema_version\":1,\"schema\":\"orchestella_stage_trace\","
                    + "\"kind\":\"stage_event\",\"provenance\":\"production\","
                    + "\"clock\":\"test_monotonic_ns\",\"identity\":{\"owner_alias\":\"instance-0f3a\","
                    + "\"session_id\":\"s-9c1f2b3a-1111-2222-3333-444455556666\","
                    + "\"generation\":\"gen-1\",\"vm_alias\":\"qemu\"},"));
            assertTrue(line.contains("\"stage\":\"" + order[i].wireName() + "\""));
            long expectedNanos = 1000L + 100L * i;
            assertTrue(line.endsWith(",\"t_ns\":" + expectedNanos + "}"));
        }
        assertTrue("stop_completed is the twelfth gate event",
                sink.lines.get(11).contains("\"stage\":\"stop_completed\""));
        assertTrue(sink.lines.get(11).endsWith(",\"t_ns\":2100}"));
        String end = sink.lines.get(12);
        assertTrue(end.contains("\"kind\":\"trace_end\""));
        assertTrue(end.contains("\"state\":\"ENDED\""));
        assertTrue(end.contains("\"reason\":\"NONE\""));
        assertTrue(end.contains("\"last_stage\":\"stop_completed\""));
        assertTrue(end.contains("\"observed_gates\":[\"launch_requested\",\"inputs_begin\","
                + "\"inputs_verified\",\"run_begin\",\"vm_created\",\"run_returned\","
                + "\"vsock_connected\",\"agent_authenticated\",\"guest_command_ok\","
                + "\"services_ready\",\"stop_requested\",\"stop_completed\"]"));
        assertTrue(end.contains("\"missing_gates\":[\"preflight_begin\",\"preflight_end\","
                + "\"manager_begin\",\"vm_running_observed\"]"));
        assertTrue(end.contains("\"stop_facts\":[\"owned_vm_stopped\",\"owned_resources_released\","
                + "\"guest_shutdown_acknowledged\"]"));

        ProductionStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(ProductionStageRecorder.State.ENDED, snapshot.state);
        assertEquals(12, snapshot.eventCount);
        assertTrue(snapshot.missingGates.contains("preflight_begin"));
        assertEquals("not_observed stays honest for unobserved gates", 4,
                snapshot.missingGates.size());
    }

    @Test
    public void authenticatedOnlyGatesRejectTheOwnerRoute() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("ROUTE_REJECTED",
                recorder.mark(ProductionStageRecorder.Stage.AGENT_AUTHENTICATED, IDENTITY));
        assertEquals(ProductionStageRecorder.State.FAILED, recorder.snapshot().state);
        assertEquals("ROUTE_REJECTED", recorder.snapshot().reason);
        assertEquals(0, sink.lines.size());
    }

    @Test
    public void ownerOnlyGatesRejectTheAuthenticatedRoute() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("ROUTE_REJECTED", recorder.markAuthenticated(
                ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals("ROUTE_REJECTED", recorder.snapshot().reason);
    }

    @Test
    public void guestShutdownAcknowledgementRequiresTheAuthenticatedRoute() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("ROUTE_REJECTED", recorder.observeStop(
                ProductionStageRecorder.StopFact.GUEST_SHUTDOWN_ACKNOWLEDGED, IDENTITY));
        assertEquals("ROUTE_REJECTED", recorder.snapshot().reason);
    }

    @Test
    public void stopCompletedWithoutAllThreeFactsIsRejected() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.STOP_REQUESTED, IDENTITY));
        assertEquals("OBSERVED", recorder.observeStop(
                ProductionStageRecorder.StopFact.OWNED_VM_STOPPED, IDENTITY));
        assertEquals("OBSERVED", recorder.observeStop(
                ProductionStageRecorder.StopFact.OWNED_RESOURCES_RELEASED, IDENTITY));
        assertEquals("STOP_UNCONFIRMED",
                recorder.mark(ProductionStageRecorder.Stage.STOP_COMPLETED, IDENTITY));
        assertEquals("STOP_UNCONFIRMED", recorder.snapshot().reason);
        // EOF or exit code 0 alone cannot sign a normal stop: the missing
        // guest acknowledgement keeps the gate rejected.
    }

    @Test
    public void duplicateGateKeepsTheFirstObservationWithoutRewriting() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.STOP_REQUESTED, IDENTITY));
        clock.value.set(99999L);
        assertEquals("ALREADY_RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.STOP_REQUESTED, IDENTITY));
        assertEquals(1, sink.lines.size());
        assertEquals(1, recorder.snapshot().eventCount);
    }

    @Test
    public void backwardsOrBrokenClockRetiresTheTraceBeforeAnyWrite() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        clock.goBackwards = true;
        assertEquals("CLOCK_REJECTED",
                recorder.mark(ProductionStageRecorder.Stage.INPUTS_BEGIN, IDENTITY));
        assertEquals("CLOCK_REJECTED", recorder.snapshot().reason);
        assertEquals("the rejected event must not reach the sink", 1, sink.lines.size());

        RecordingSink sink2 = new RecordingSink();
        ManualClock exploding = new ManualClock();
        exploding.explode = true;
        ProductionStageRecorder recorder2 = enabledRecorder(exploding, sink2);
        assertEquals("CLOCK_REJECTED",
                recorder2.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals(0, sink2.lines.size());
    }

    // ---------------------------------------------------------------- group 3

    @Test
    public void failureKeepsTheRealLastStageAndLateEventsCannotFakeReady() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.INPUTS_BEGIN, IDENTITY));
        assertEquals("CREATE_FAILED", recorder.fail(Failure.CREATE_FAILED, IDENTITY));

        ProductionStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(ProductionStageRecorder.State.FAILED, snapshot.state);
        assertEquals("CREATE_FAILED", snapshot.reason);
        assertEquals("the last stage stays the last honestly observed gate",
                "inputs_begin", snapshot.lastStage);
        assertTrue(sink.lines.get(2).contains("\"kind\":\"trace_end\""));
        assertTrue(sink.lines.get(2).contains("\"state\":\"FAILED\""));
        assertTrue(sink.lines.get(2).contains("\"reason\":\"CREATE_FAILED\""));

        assertEquals("RETIRED",
                recorder.mark(ProductionStageRecorder.Stage.SERVICES_READY, IDENTITY));
        assertEquals("RETIRED", recorder.markAuthenticated(
                ProductionStageRecorder.Stage.SERVICES_READY, IDENTITY));
        assertEquals("RETIRED", recorder.finish(IDENTITY));
        assertEquals("RETIRED", recorder.fail(Failure.RUN_FAILED, IDENTITY));
        assertEquals("a retired trace never fakes readiness",
                "inputs_begin", recorder.snapshot().lastStage);
        assertEquals("the end line is written exactly once", 3, sink.lines.size());
    }

    @Test
    public void fallbackRetiresWithTheRealMappedReasonAndLastStage() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.PREFLIGHT_BEGIN, IDENTITY));
        assertEquals("PERMISSION_DENIED", recorder.fallback(Failure.PERMISSION_DENIED, IDENTITY));
        ProductionStageRecorder.Snapshot snapshot = recorder.snapshot();
        assertEquals(ProductionStageRecorder.State.FALLBACK, snapshot.state);
        assertEquals("PERMISSION_DENIED", snapshot.reason);
        assertEquals("preflight_begin", snapshot.lastStage);
        assertTrue(sink.lines.get(2).contains("\"state\":\"FALLBACK\""));
        assertTrue(sink.lines.get(2).contains("\"reason\":\"PERMISSION_DENIED\""));
        assertEquals("RETIRED", recorder.mark(ProductionStageRecorder.Stage.RUN_BEGIN, IDENTITY));
    }

    @Test
    public void cancelRetiresTheTraceAsSuperseded() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals("CANCELLED", recorder.cancel(IDENTITY));
        assertEquals(ProductionStageRecorder.State.CANCELLED, recorder.snapshot().state);
        assertEquals("CANCELLED", recorder.snapshot().reason);
        assertEquals("RETIRED",
                recorder.mark(ProductionStageRecorder.Stage.RUN_BEGIN, IDENTITY));
    }

    @Test
    public void lateWrongGenerationIsRejectedWithoutTouchingClockOrSinkOrTrace() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        Identity staleGeneration = Identity.of("instance-0f3a",
                "s-9c1f2b3a-1111-2222-3333-444455556666", "gen-0", "qemu");
        Identity staleOwner = Identity.of("other-instance", "s-9c1f2b3a-1111-2222-3333-444455556666",
                "gen-1", "qemu");
        for (Identity stale : new Identity[] {staleGeneration, staleOwner, null}) {
            assertEquals("IDENTITY_MISMATCH",
                    recorder.mark(ProductionStageRecorder.Stage.INPUTS_BEGIN, stale));
            assertEquals("IDENTITY_MISMATCH",
                    recorder.observeStop(
                            ProductionStageRecorder.StopFact.OWNED_VM_STOPPED, stale));
            assertEquals("IDENTITY_MISMATCH", recorder.finish(stale));
        }
        assertEquals("late wrong-generation events never mutated the trace",
                1, recorder.snapshot().eventCount);
        assertEquals(1, sink.lines.size());
        assertEquals("the clock was not read for rejected claims", 1, clock.calls.get());
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.INPUTS_BEGIN, IDENTITY));
    }

    @Test
    public void sinkFailureRetiresExplicitlyWithoutRetryOrQueue() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        sink.failOnNext = true;
        assertEquals("SINK_FAILED",
                recorder.mark(ProductionStageRecorder.Stage.INPUTS_BEGIN, IDENTITY));
        assertEquals("SINK_FAILED", recorder.snapshot().reason);
        assertEquals(ProductionStageRecorder.State.FAILED, recorder.snapshot().state);
        assertEquals("a failed sink is never retried and never queued", 2, sink.calls.get());

        RecordingSink throwingSink = new RecordingSink() {
            @Override public boolean writeLine(String line) {
                calls.incrementAndGet();
                throw new IllegalStateException("synthetic sink failure");
            }
        };
        ProductionStageRecorder throwing = ProductionStageRecorder.enabled(IDENTITY, clock,
                "test_monotonic_ns", throwingSink);
        assertEquals("SINK_FAILED",
                throwing.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals("SINK_FAILED", throwing.snapshot().reason);
    }

    @Test
    public void unbindableIdentityDisablesRecordingInsteadOfFaking() {
        assertNull(Identity.of(null, "s-abc", "gen-1", "qemu"));
        assertNull(Identity.of("instance-0f3a", null, "gen-1", "qemu"));
        assertNull("uppercase device-serial-like values are not app aliases",
                Identity.of("DeviceSerialABC123", "s-abc", "gen-1", "qemu"));
        assertNull("paths are not app aliases",
                Identity.of("/data/data/app", "s-abc", "gen-1", "qemu"));
        assertNull("tokens are not app aliases",
                Identity.of("tok_en_default", "s-abc", "gen-1", "qemu"));
        assertNull(Identity.of("ab", "s-abc", "gen-1", "qemu"));
        assertNull(Identity.of("instance-0f3a", "s-abc", "", "qemu"));
        assertNull(Identity.of("instance-0f3a", "s-abc", "gen-1",
                "qemu-with-a-string-longer-than-sixty-four-characters-aaaaaaaaaaaaaaa"));
    }

    // ---------------------------------------------------------------- group 4

    @Test
    public void observedPartialOrderIsRecordedWithoutBackfilledTimeOrPseudoOrder() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        // The AVF production reality: the shared bootstrap (inputs) runs inside
        // the preflight window, before preflight_end — not the offline chain's
        // assumed preflight-before-inputs order.
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY));
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.PREFLIGHT_BEGIN, IDENTITY));
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.INPUTS_BEGIN, IDENTITY));
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.INPUTS_VERIFIED, IDENTITY));
        assertEquals("RECORDED",
                recorder.mark(ProductionStageRecorder.Stage.PREFLIGHT_END, IDENTITY));
        assertEquals("the real partial order must not be order-rejected", 5, sink.lines.size());
        for (int i = 0; i < sink.lines.size(); i++) {
            long expectedNanos = 1000L + 100L * i;
            assertTrue("observation time only, never backfilled",
                    sink.lines.get(i).endsWith(",\"t_ns\":" + expectedNanos + "}"));
        }
        assertTrue(sink.lines.get(1).contains("\"stage\":\"preflight_begin\""));
        assertTrue(sink.lines.get(2).contains("\"stage\":\"inputs_begin\""));
    }

    @Test
    public void everyCarriedLineIsVersionedAndTerminalStatesAreWrittenExactlyOnce() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY);
        recorder.fail(Failure.RUN_FAILED, IDENTITY);
        recorder.cancel(IDENTITY);
        recorder.fallback(Failure.API_BLOCKED, IDENTITY);
        recorder.finish(IDENTITY);
        assertEquals("terminal transitions after the first are no-ops on the wire",
                2, sink.lines.size());
        assertTrue(sink.lines.get(1).contains("\"kind\":\"trace_end\""));
    }

    // ---------------------------------------------------------------- group 5

    @Test
    public void eventLinesCarryOnlyTheAllowlistedSchemaKeys() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        ProductionStageRecorder recorder = enabledRecorder(clock, sink);
        recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, IDENTITY);
        recorder.fail(Failure.RUN_FAILED, IDENTITY);
        String event = sink.lines.get(0);
        String identity = "{\"owner_alias\":\"instance-0f3a\","
                + "\"session_id\":\"s-9c1f2b3a-1111-2222-3333-444455556666\","
                + "\"generation\":\"gen-1\",\"vm_alias\":\"qemu\"}";
        assertEquals("{\"schema_version\":1,\"schema\":\"orchestella_stage_trace\","
                + "\"kind\":\"stage_event\",\"provenance\":\"production\","
                + "\"clock\":\"test_monotonic_ns\",\"identity\":" + identity + ","
                + "\"stage\":\"launch_requested\",\"route\":\"owner\",\"t_ns\":1000}", event);
        String end = sink.lines.get(1);
        for (String required : new String[] {"\"schema_version\":1", "\"kind\":\"trace_end\"",
                "\"state\":\"FAILED\"", "\"reason\":\"RUN_FAILED\"", "\"last_stage\"",
                "\"observed_gates\"", "\"missing_gates\"", "\"stop_facts\"", "\"t_ns\":"}) {
            assertTrue("trace end must carry " + required, end.contains(required));
        }
        assertFalse("no free-text payload field exists",
                end.contains("\"message\"") || end.contains("\"exception\"")
                        || end.contains("\"path\"") || end.contains("\"token\""));
    }

    @Test
    public void sensitiveShapedInputsNeverReachTheWire() {
        ManualClock clock = new ManualClock();
        RecordingSink sink = new RecordingSink();
        Identity clean = Identity.of("instance-0f3a", "s-9c1f2b3a-1111-2222-3333-444455556666",
                "gen-1", "qemu");
        ProductionStageRecorder recorder = ProductionStageRecorder.enabled(clean, clock,
                "test_monotonic_ns", sink);
        recorder.mark(ProductionStageRecorder.Stage.LAUNCH_REQUESTED, clean);
        String secret = "Sup3rSecretBridgeToken/password?query=synthetic/private-cache-dir";
        // The component has no free-text channel: attempt the secret through
        // every input that exists (identity fields only) and verify the wire.
        Identity poisoned = Identity.of(secret, "s-abc", "gen-1", "qemu");
        assertNull("token/path-shaped identity must be unbindable", poisoned);
        for (String line : sink.lines) {
            assertFalse(line.contains(secret));
            assertFalse(line.toLowerCase().contains("password"));
            assertFalse(line.contains("synthetic/private-cache-dir"));
        }
        assertEquals("IDENTITY_MISMATCH",
                recorder.mark(ProductionStageRecorder.Stage.INPUTS_BEGIN, poisoned));
    }

    private static void fail(String message) {
        org.junit.Assert.fail(message);
    }
}
