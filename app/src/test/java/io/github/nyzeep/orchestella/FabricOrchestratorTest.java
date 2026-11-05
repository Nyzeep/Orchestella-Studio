package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.FabricOrchestrator.FaceException;
import io.github.nyzeep.orchestella.FabricOrchestrator.FaceWidthSource;
import io.github.nyzeep.orchestella.FabricOrchestrator.Outcome;
import io.github.nyzeep.orchestella.FabricWidthDecision.Dispatch;
import io.github.nyzeep.orchestella.FabricWidthDecision.Face;
import io.github.nyzeep.orchestella.FabricWidthDecision.RejectCode;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudgetLedger;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRequest;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * B2 fabric orchestration: the layer that consumes the C1 dispatch contract
 * ({@link FabricWidthDecision#route}) with ledger-derived face widths. These
 * tests lock the orchestration behaviour over fake workers: wide-parallel work
 * routes to the native/UML faces and never to the QEMU face, narrow (≤2) work
 * keeps the C1-era routing unchanged, the outstanding grants never exceed the
 * ledger-derived face widths (their sum is the ledger's core budget), a failed
 * face is evicted until re-armed, and the gate-off path has zero side effects.
 *
 * <p>Fixture note: the QEMU face's product worker is the guest-local execution
 * seam, so the tests that exercise QEMU-face availability register a no-op
 * worker for it — an unwired face reads zero and is a genuine
 * {@code NO_FACE_AVAILABLE} outage, not the policy refusal.
 */
public final class FabricOrchestratorTest {

    private static final FabricOrchestrator.WorkItem ITEM = new FabricOrchestrator.WorkItem() {
    };

    // --- gate: off means zero side effects --------------------------------------

    @Test
    public void gateOffRefusesWithoutReadingWidthsOrTouchingWorkers() {
        RecordingWorker nativeWorker = new RecordingWorker();
        MutableWidths widths = new MutableWidths().put(Face.HOST_NATIVE, 4);
        int[] reads = {0};
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                false, counted(widths, reads), workers(Face.HOST_NATIVE, nativeWorker));

        Outcome outcome = orchestrator.dispatch(ITEM, 8);

        assertFalse(outcome.admitted());
        assertNull(outcome.route());
        assertSame(FabricOrchestrator.Code.GATE_DISABLED, outcome.code());
        assertEquals(0, reads[0]);
        assertEquals(0, nativeWorker.executions.size());
    }

    // --- outcome shape: exactly one of route verdict or orchestration code ------

    @Test
    public void outcomeCarriesExactlyOneOfRouteVerdictOrCode() {
        Dispatch admission = FabricWidthDecision.route(2, widths(Face.HOST_NATIVE, 2));
        new Outcome(admission, null);
        new Outcome(null, FabricOrchestrator.Code.GATE_DISABLED);
        try {
            new Outcome(null, null);
            fail("an outcome needs one of the two carriers");
        } catch (IllegalArgumentException expected) {
            // Fixed outcome shape.
        }
        try {
            new Outcome(admission, FabricOrchestrator.Code.FACE_FAILED);
            fail("an outcome carries exactly one, never both");
        } catch (IllegalArgumentException expected) {
            // Fixed outcome shape.
        }
    }

    // --- wide-parallel routing: never the QEMU face ------------------------------

    @Test
    public void wideParallelWorkRoutesToNativeThenUml() {
        RecordingWorker nativeWorker = new RecordingWorker();
        RecordingWorker umlWorker = new RecordingWorker();
        MutableWidths widths = new MutableWidths()
                .put(Face.HOST_NATIVE, 4)
                .put(Face.UML, 4)
                .put(Face.QEMU_TCG, 2);
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                true, widths,
                workers(Face.HOST_NATIVE, nativeWorker,
                        Face.UML, umlWorker,
                        Face.QEMU_TCG, new RecordingWorker()));

        Outcome nativeFirst = orchestrator.dispatch(ITEM, 8);

        assertTrue(nativeFirst.admitted());
        assertSame(Face.HOST_NATIVE, nativeFirst.route().face());
        assertEquals(4, nativeFirst.route().width());
        nativeWorker.assertExecuted(Face.HOST_NATIVE, 4);

        // The native face loses its budget: UML is the next face in the C1
        // priority order and the QEMU exclusion never blocks it.
        widths.put(Face.HOST_NATIVE, 0);
        Outcome umlSecond = orchestrator.dispatch(ITEM, 8);

        assertTrue(umlSecond.admitted());
        assertSame(Face.UML, umlSecond.route().face());
        assertEquals(4, umlSecond.route().width());
        umlWorker.assertExecuted(Face.UML, 4);
    }

    @Test
    public void wideParallelWorkRefusesWithThePolicyCodeWhenOnlyQemuRemains() {
        MutableWidths widths = new MutableWidths().put(Face.QEMU_TCG, 2);
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                true, widths, workers(Face.QEMU_TCG, new RecordingWorker()));

        Outcome outcome = orchestrator.dispatch(ITEM, 3);

        assertFalse(outcome.admitted());
        assertNull(outcome.code());
        assertSame(RejectCode.WIDE_PARALLEL_NO_FACE, outcome.route().rejectCode());
    }

    @Test
    public void aFaceWithoutAWorkerIsAGenuineOutageNotThePolicyRefusal() {
        MutableWidths widths = new MutableWidths().put(Face.QEMU_TCG, 2);
        FabricOrchestrator orchestrator = new FabricOrchestrator(true, widths, workers());

        Outcome outcome = orchestrator.dispatch(ITEM, 3);

        assertFalse(outcome.admitted());
        assertNull(outcome.code());
        assertSame(RejectCode.NO_FACE_AVAILABLE, outcome.route().rejectCode());
    }

    // --- narrow work: unchanged C1-era routing -----------------------------------

    @Test
    public void narrowWorkStillReachesTheQemuFaceWhenItIsTheOnlyFace() {
        RecordingWorker qemuWorker = new RecordingWorker();
        MutableWidths widths = new MutableWidths().put(Face.QEMU_TCG, 2);
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                true, widths, workers(Face.QEMU_TCG, qemuWorker));

        Outcome twoWay = orchestrator.dispatch(ITEM, 2);
        assertTrue(twoWay.admitted());
        assertSame(Face.QEMU_TCG, twoWay.route().face());
        assertEquals(2, twoWay.route().width());
        qemuWorker.assertExecuted(Face.QEMU_TCG, 2);

        Outcome oneWay = orchestrator.dispatch(ITEM, 1);
        assertTrue(oneWay.admitted());
        assertSame(Face.QEMU_TCG, oneWay.route().face());
        assertEquals(1, oneWay.route().width());
    }

    @Test
    public void narrowWorkKeepsTheRoutePriorityOrder() {
        RecordingWorker nativeWorker = new RecordingWorker();
        RecordingWorker umlWorker = new RecordingWorker();
        MutableWidths widths = new MutableWidths()
                .put(Face.HOST_NATIVE, 2)
                .put(Face.UML, 2)
                .put(Face.QEMU_TCG, 2);
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                true, widths,
                workers(Face.HOST_NATIVE, nativeWorker,
                        Face.UML, umlWorker,
                        Face.QEMU_TCG, new RecordingWorker()));

        Outcome outcome = orchestrator.dispatch(ITEM, 2);
        assertSame(Face.HOST_NATIVE, outcome.route().face());

        widths.put(Face.HOST_NATIVE, 0);
        Outcome umlOutcome = orchestrator.dispatch(ITEM, 1);
        assertSame(Face.UML, umlOutcome.route().face());
    }

    @Test
    public void malformedParallelismIsTheRouteInvalidRequestRefusal() {
        RecordingWorker nativeWorker = new RecordingWorker();
        MutableWidths widths = new MutableWidths().put(Face.HOST_NATIVE, 4);
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                true, widths, workers(Face.HOST_NATIVE, nativeWorker));

        Outcome zero = orchestrator.dispatch(ITEM, 0);
        assertFalse(zero.admitted());
        assertNull(zero.code());
        assertSame(RejectCode.INVALID_REQUEST, zero.route().rejectCode());

        Outcome negative = orchestrator.dispatch(ITEM, -1);
        assertSame(RejectCode.INVALID_REQUEST, negative.route().rejectCode());
        assertEquals(0, nativeWorker.executions.size());
    }

    // --- width accounting: the ledger sum holds under concurrency ---------------

    @Test
    public void outstandingGrantsBoundDispatchToTheLedgerSum() throws Exception {
        MutableWidths widths = new MutableWidths()
                .put(Face.HOST_NATIVE, 1)
                .put(Face.UML, 0)
                .put(Face.QEMU_TCG, 2);
        BlockingWorker nativeWorker = new BlockingWorker();
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                true, widths,
                workers(Face.HOST_NATIVE, nativeWorker,
                        Face.QEMU_TCG, new RecordingWorker()));

        // A wide dispatch occupies the native face's single core mid-flight.
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Outcome> wideOutcome = new AtomicReference<>();
        Thread wide = new Thread(() -> {
            wideOutcome.set(orchestrator.dispatch(ITEM, 4));
        });
        nativeWorker.started = started;
        wide.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));

        // The native core is outstanding: the next wide dispatch is a policy
        // refusal (QEMU excluded on purpose), not a queueing into the QEMU face.
        Outcome refusedWide = orchestrator.dispatch(ITEM, 4);
        assertFalse(refusedWide.admitted());
        assertSame(RejectCode.WIDE_PARALLEL_NO_FACE, refusedWide.route().rejectCode());

        // Narrow work still takes the QEMU face (unchanged behaviour); the
        // outstanding widths now sum to exactly the face budgets (1 + 2 = 3).
        Outcome narrow = orchestrator.dispatch(ITEM, 2);
        assertTrue(narrow.admitted());
        assertSame(Face.QEMU_TCG, narrow.route().face());
        assertEquals(2, narrow.route().width());

        // Once the native work returns its grant, the face serves again.
        nativeWorker.release.countDown();
        wide.join(5_000L);
        assertTrue(wideOutcome.get().admitted());
        assertSame(Face.HOST_NATIVE, wideOutcome.get().route().face());

        Outcome afterReturn = orchestrator.dispatch(ITEM, 4);
        assertTrue(afterReturn.admitted());
        assertSame(Face.HOST_NATIVE, afterReturn.route().face());
    }

    // --- face failure and eviction (D-3) -----------------------------------------

    @Test
    public void faceFailureReleasesTheGrantEvictsTheFaceAndReportsTheFixedCode() {
        // The worker fails its first execution; the rebuild path repairs it.
        RecordingWorker failingNative = new RecordingWorker(
                1, new FaceException("worker gone"));
        RecordingWorker umlWorker = new RecordingWorker();
        MutableWidths widths = new MutableWidths()
                .put(Face.HOST_NATIVE, 4)
                .put(Face.UML, 4)
                .put(Face.QEMU_TCG, 2);
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                true, widths,
                workers(Face.HOST_NATIVE, failingNative,
                        Face.UML, umlWorker,
                        Face.QEMU_TCG, new RecordingWorker()));

        Outcome failed = orchestrator.dispatch(ITEM, 4);

        assertFalse(failed.admitted());
        assertNull(failed.route());
        assertSame(FabricOrchestrator.Code.FACE_FAILED, failed.code());

        // The failed face is evicted: the next wide dispatch falls back to UML
        // (per-face fallback), not back onto the broken worker.
        Outcome fallback = orchestrator.dispatch(ITEM, 4);
        assertSame(Face.UML, fallback.route().face());
        umlWorker.assertExecuted(Face.UML, 4);

        // The re-arm path (rebuild done, worker repaired) puts the face back
        // into routing and its budget was never leaked by the failure.
        orchestrator.arm(Face.HOST_NATIVE);
        Outcome rearmed = orchestrator.dispatch(ITEM, 4);
        assertSame(Face.HOST_NATIVE, rearmed.route().face());
        assertEquals(4, rearmed.route().width());
    }

    @Test
    public void runtimeWorkerFailureIsAlsoAFaceFailureWithoutLeakingTheGrant() {
        RecordingWorker failingNative = new RecordingWorker(
                1, new IllegalStateException("worker bug"));
        MutableWidths widths = new MutableWidths().put(Face.HOST_NATIVE, 2);
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                true, widths, workers(Face.HOST_NATIVE, failingNative));

        Outcome failed = orchestrator.dispatch(ITEM, 2);
        assertSame(FabricOrchestrator.Code.FACE_FAILED, failed.code());

        // The grant was released with the eviction, so the face's budget is
        // intact for the rebuild path to hand back.
        orchestrator.arm(Face.HOST_NATIVE);
        Outcome afterArm = orchestrator.dispatch(ITEM, 2);
        assertTrue(afterArm.admitted());
        assertEquals(2, afterArm.route().width());
    }

    @Test
    public void explicitlyEvictedFaceIsSkippedUntilRearmed() {
        RecordingWorker qemuWorker = new RecordingWorker();
        MutableWidths widths = new MutableWidths().put(Face.QEMU_TCG, 2);
        FabricOrchestrator orchestrator = new FabricOrchestrator(
                true, widths, workers(Face.QEMU_TCG, qemuWorker));

        orchestrator.evict(Face.QEMU_TCG);
        Outcome whileEvicted = orchestrator.dispatch(ITEM, 2);
        assertFalse(whileEvicted.admitted());
        assertSame(RejectCode.NO_FACE_AVAILABLE, whileEvicted.route().rejectCode());

        orchestrator.arm(Face.QEMU_TCG);
        Outcome rearmed = orchestrator.dispatch(ITEM, 2);
        assertTrue(rearmed.admitted());
        assertSame(Face.QEMU_TCG, rearmed.route().face());
    }

    // --- ledger-derived widths: the sum invariant -------------------------------

    @Test
    public void ledgerBackedWidthsSumToTheLedgerBudgetAfterBothLanesReserve() {
        // C1 product flow: an 8-core host's QEMU request is the clamped 2, the
        // accel lane reserves 1, the envelope is the sum — 3 cores.
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(3, 6144L, 48L, true));
        reserveQuietly(ledger, new LaneRequest(LaneId.QEMU, 2, 4096L, 32L));
        reserveQuietly(ledger, new LaneRequest(LaneId.ACCEL, 1, 2048L, 16L));

        FaceWidthSource widths = FabricOrchestrator.ledgerBackedWidths(
                ledger, 2, 1, () -> 0);

        Map<Face, Integer> snapshot = widths.budgetWidths();
        assertEquals(2, snapshot.get(Face.QEMU_TCG).intValue());
        assertEquals(1, snapshot.get(Face.HOST_NATIVE).intValue());
        assertEquals(0, snapshot.get(Face.UML).intValue());
        assertEquals(ledger.budget().cpuCores(), snapshot.get(Face.QEMU_TCG)
                + snapshot.get(Face.HOST_NATIVE) + snapshot.get(Face.UML));
    }

    @Test
    public void umlWidthIsClampedToTheLedgerHeadroomAndNativeKeepsTheRemainder() {
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(4, 8192L, 64L, true));
        reserveQuietly(ledger, new LaneRequest(LaneId.QEMU, 2, 4096L, 32L));
        reserveQuietly(ledger, new LaneRequest(LaneId.ACCEL, 1, 2048L, 16L));

        // Headroom is 1 core; a UML supplier over-declaring 8 is clamped to it
        // and the native face keeps its own reservation — the sum stays 4.
        FaceWidthSource widths = FabricOrchestrator.ledgerBackedWidths(
                ledger, 2, 1, () -> 8);

        Map<Face, Integer> snapshot = widths.budgetWidths();
        assertEquals(1, snapshot.get(Face.UML).intValue());
        assertEquals(1, snapshot.get(Face.HOST_NATIVE).intValue());
        assertEquals(2, snapshot.get(Face.QEMU_TCG).intValue());
        assertEquals(ledger.budget().cpuCores(), snapshot.get(Face.QEMU_TCG)
                + snapshot.get(Face.HOST_NATIVE) + snapshot.get(Face.UML));
    }

    @Test
    public void qemuFaceWidthIsClampedToTheDecisionValueEvenWhenOverdeclared() {
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(9, 8192L, 64L, true));

        // A caller passing the raw topology instead of the C1 reservation is
        // still clamped at the consumption point: the QEMU face can never be
        // dispatched wider than the measured effective TCG width.
        FaceWidthSource widths = FabricOrchestrator.ledgerBackedWidths(
                ledger, 8, 1, () -> 0);

        assertEquals(2, widths.budgetWidths().get(Face.QEMU_TCG).intValue());
    }

    @Test
    public void ledgerBackedWidthsRejectInconsistentReservations() {
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(3, 6144L, 48L, true));
        try {
            FabricOrchestrator.ledgerBackedWidths(ledger, -1, 1, () -> 0);
            fail("a negative qemu width is malformed");
        } catch (IllegalArgumentException expected) {
            // Fixed derivation contract.
        }
        try {
            FabricOrchestrator.ledgerBackedWidths(ledger, 2, -1, () -> 0);
            fail("a negative native width is malformed");
        } catch (IllegalArgumentException expected) {
            // Fixed derivation contract.
        }
        try {
            FabricOrchestrator.ledgerBackedWidths(ledger, 4, 1, () -> 0);
            fail("a qemu width beyond the ledger budget is malformed");
        } catch (IllegalArgumentException expected) {
            // Fixed derivation contract.
        }
    }

    // --- fakes -------------------------------------------------------------------

    private static FaceWidthSource counted(FaceWidthSource delegate, int[] reads) {
        return () -> {
            reads[0]++;
            return delegate.budgetWidths();
        };
    }

    private static void reserveQuietly(LaneBudgetLedger ledger, LaneRequest request) {
        try {
            ledger.reserve(request);
        } catch (io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectedException
                failure) {
            throw new AssertionError("fixture reservation refused", failure);
        }
    }

    private static Map<Face, Integer> widths(Object... pairs) {
        MutableWidths map = new MutableWidths();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((Face) pairs[i], (Integer) pairs[i + 1]);
        }
        return map.budgetWidths();
    }

    private static Map<Face, FabricOrchestrator.FaceWorker> workers(Object... pairs) {
        Map<Face, FabricOrchestrator.FaceWorker> map = new EnumMap<>(Face.class);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((Face) pairs[i], (FabricOrchestrator.FaceWorker) pairs[i + 1]);
        }
        return map;
    }

    /** Mutable budget widths, standing in for the per-dispatch ledger read. */
    private static final class MutableWidths implements FaceWidthSource {
        private final Map<Face, Integer> values = new EnumMap<>(Face.class);

        MutableWidths put(Face face, int width) {
            values.put(face, width);
            return this;
        }

        @Override
        public Map<Face, Integer> budgetWidths() {
            return new EnumMap<>(values);
        }
    }

    /**
     * A worker that records (face, width) executions. It throws the given
     * failure for the first {@code failures} executions only — the model of a
     * worker the rebuild path repairs.
     */
    private static final class RecordingWorker implements FabricOrchestrator.FaceWorker {
        final java.util.List<String> executions = new java.util.ArrayList<>();
        private final int failures;
        private final Exception failure;
        private int remainingFailures;

        RecordingWorker() {
            this(0, null);
        }

        RecordingWorker(int failures, Exception failure) {
            this.failures = failures;
            this.failure = failure;
        }

        @Override
        public void execute(Face executedFace, int grantedWidth, FabricOrchestrator.WorkItem item)
                throws FaceException {
            executions.add(executedFace + ":" + grantedWidth);
            if (remainingFailures < failures) {
                remainingFailures++;
                if (failure instanceof FaceException) {
                    throw (FaceException) failure;
                }
                throw (RuntimeException) failure;
            }
        }

        void assertExecuted(Face expectedFace, int expectedWidth) {
            if (!executions.contains(expectedFace + ":" + expectedWidth)) {
                fail("expected execution " + expectedFace + ":" + expectedWidth
                        + " but saw " + executions);
            }
        }
    }

    /** A worker whose execute blocks until released, to hold a grant mid-flight. */
    private static final class BlockingWorker implements FabricOrchestrator.FaceWorker {
        final CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        @Override
        public void execute(Face face, int grantedWidth, FabricOrchestrator.WorkItem item)
                throws FaceException {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new FaceException("interrupted while executing", failure);
            }
        }
    }
}
