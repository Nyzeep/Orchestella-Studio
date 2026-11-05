package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.FabricWidthDecision.Dispatch;
import io.github.nyzeep.orchestella.FabricWidthDecision.Face;
import io.github.nyzeep.orchestella.FabricWidthDecision.RejectCode;
import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;

/**
 * C1 fabric width decision: the QEMU-TCG face is accounted at (and never
 * dispatched wider than) the measured effective TCG width of 2 vCPU, and
 * wide-parallel work is routed to the native/UML faces in that priority order.
 * The measured basis and the evidence trail are cited on
 * {@link FabricWidthDecision}; these tests lock the semantics, not the numbers'
 * provenance.
 */
public final class FabricWidthDecisionTest {

    // --- accounting: QEMU lane budget clamped to the effective TCG width -----

    @Test
    public void qemuLaneBudgetIsClampedToTheMeasuredEffectiveWidth() {
        assertEquals(2, FabricWidthDecision.qemuLaneBudgetCores(8));
        assertEquals(2, FabricWidthDecision.qemuLaneBudgetCores(4));
        assertEquals(2, FabricWidthDecision.qemuLaneBudgetCores(2));
    }

    @Test
    public void qemuLaneBudgetNeverExceedsTheRealTopology() {
        // A 1-vCPU guest is accounted at 1: the clamp caps inflated accounting,
        // it never invents budget that the topology does not have.
        assertEquals(1, FabricWidthDecision.qemuLaneBudgetCores(1));
    }

    @Test
    public void qemuLaneBudgetRejectsNonPositiveTopology() {
        try {
            FabricWidthDecision.qemuLaneBudgetCores(0);
            fail("zero topology is a malformed input");
        } catch (IllegalArgumentException expected) {
            // Fixed contract: the caller passes validated VmHardwareConfig.
        }
    }

    @Test
    public void accountedWidthConstantIsTheDecisionValue() {
        // The decision pins the number in one place; the accounting clamp and
        // the dispatch exclusion must move together if the ACR ever revisits it.
        assertEquals(2, FabricWidthDecision.QEMU_TCG_ACCOUNTED_VCPUS);
    }

    // --- dispatch: wide-parallel work never reaches the QEMU face ------------

    @Test
    public void narrowWorkMayTakeTheQemuFaceWhenItIsTheOnlyOneAvailable() {
        Dispatch dispatch = FabricWidthDecision.route(
                2, widths(Face.QEMU_TCG, 2));

        assertTrue(dispatch.admitted());
        assertSame(Face.QEMU_TCG, dispatch.face());
        assertEquals(2, dispatch.width());
    }

    @Test
    public void qemuFaceIsGrantedAtMostItsBudgetedWidth() {
        // Requested 2, face only budgets the accounted 2; and a narrower face
        // clamps the grant instead of over-committing.
        Dispatch narrow = FabricWidthDecision.route(2, widths(Face.QEMU_TCG, 1));
        assertTrue(narrow.admitted());
        assertEquals(1, narrow.width());
    }

    @Test
    public void wideParallelWorkIsNeverDispatchedToTheQemuFace() {
        // Parallelism 4 is splittable make-shaped work: even with the QEMU
        // face as the ONLY available face it must be refused, not queued into
        // the 92%-translation-tax face (C1 rule 2).
        Dispatch dispatch = FabricWidthDecision.route(
                4, widths(Face.QEMU_TCG, 2));

        assertFalse(dispatch.admitted());
        assertSame(RejectCode.WIDE_PARALLEL_NO_FACE, dispatch.rejectCode());
        assertEquals(0, dispatch.width());
    }

    @Test
    public void workOneAboveTheCapIsAlreadyWide() {
        Dispatch dispatch = FabricWidthDecision.route(
                3, widths(Face.QEMU_TCG, 2));

        assertFalse(dispatch.admitted());
        assertSame(RejectCode.WIDE_PARALLEL_NO_FACE, dispatch.rejectCode());
    }

    @Test
    public void wideParallelWorkPrefersNativeThenUmlAheadOfQemu() {
        // All three faces available: the priority order native > UML > QEMU
        // decides, so parallelism 4 goes to native, granted its budgeted width.
        EnumMap<Face, Integer> all = new EnumMap<>(Face.class);
        all.put(Face.HOST_NATIVE, 4);
        all.put(Face.UML, 4);
        all.put(Face.QEMU_TCG, 2);
        Dispatch nativeFirst = FabricWidthDecision.route(4, all);
        assertTrue(nativeFirst.admitted());
        assertSame(Face.HOST_NATIVE, nativeFirst.face());
        assertEquals(4, nativeFirst.width());

        // Native absent: UML is the next face in the priority order, and the
        // wide-parallel exclusion of QEMU never blocks UML.
        Dispatch umlSecond = FabricWidthDecision.route(
                4, widths(Face.UML, 4, Face.QEMU_TCG, 2));
        assertTrue(umlSecond.admitted());
        assertSame(Face.UML, umlSecond.face());
        assertEquals(4, umlSecond.width());
    }

    @Test
    public void wideParallelWorkIsGrantedTheFaceBudgetedWidthWhenNarrower() {
        // Requested 4, native budgets only 2: grant min(parallelism, width).
        Dispatch dispatch = FabricWidthDecision.route(
                4, widths(Face.HOST_NATIVE, 2));

        assertTrue(dispatch.admitted());
        assertSame(Face.HOST_NATIVE, dispatch.face());
        assertEquals(2, dispatch.width());
    }

    @Test
    public void wideParallelRefusalNamesThePolicyAndNotAnOutage() {
        // QEMU available, everything else absent: the fixed code must say the
        // decision refused the face on purpose, so the orchestration log is
        // never read as "no face existed".
        Dispatch excluded = FabricWidthDecision.route(
                8, widths(Face.QEMU_TCG, 2));
        assertSame(RejectCode.WIDE_PARALLEL_NO_FACE, excluded.rejectCode());

        // QEMU itself unavailable and nothing else: a genuine outage code.
        Dispatch outage = FabricWidthDecision.route(8, widths(Face.QEMU_TCG, 0));
        assertSame(RejectCode.NO_FACE_AVAILABLE, outage.rejectCode());
    }

    @Test
    public void narrowWorkFollowsTheSamePriorityOrder() {
        Dispatch dispatch = FabricWidthDecision.route(
                1, widths(Face.UML, 2, Face.QEMU_TCG, 2));

        assertTrue(dispatch.admitted());
        assertSame(Face.UML, dispatch.face());
    }

    // --- malformed requests and verdict shapes --------------------------------

    @Test
    public void nonPositiveParallelismIsInvalid() {
        Dispatch zero = FabricWidthDecision.route(0, widths(Face.HOST_NATIVE, 4));
        assertFalse(zero.admitted());
        assertSame(RejectCode.INVALID_REQUEST, zero.rejectCode());

        Dispatch negative = FabricWidthDecision.route(-1, widths(Face.HOST_NATIVE, 4));
        assertFalse(negative.admitted());
        assertSame(RejectCode.INVALID_REQUEST, negative.rejectCode());
    }

    @Test
    public void emptyFabricRefusesWithNoFaceAvailable() {
        Dispatch dispatch = FabricWidthDecision.route(1, Map.of());

        assertFalse(dispatch.admitted());
        assertSame(RejectCode.NO_FACE_AVAILABLE, dispatch.rejectCode());
    }

    @Test
    public void verdictCarriesExactlyOneOfFaceOrRejectCode() {
        try {
            new Dispatch(null, 0, null);
            fail("a refusal needs a reject code");
        } catch (IllegalArgumentException expected) {
            // Fixed verdict shape.
        }
        try {
            new Dispatch(Face.UML, 2, RejectCode.NO_FACE_AVAILABLE);
            fail("an admission carries no reject code");
        } catch (IllegalArgumentException expected) {
            // Fixed verdict shape.
        }
        try {
            new Dispatch(Face.UML, 0, null);
            fail("an admission needs a positive width");
        } catch (IllegalArgumentException expected) {
            // Fixed verdict shape.
        }
        try {
            new Dispatch(null, 2, RejectCode.NO_FACE_AVAILABLE);
            fail("a refusal grants no width");
        } catch (IllegalArgumentException expected) {
            // Fixed verdict shape.
        }
    }

    private static Map<Face, Integer> widths(Object... pairs) {
        EnumMap<Face, Integer> map = new EnumMap<>(Face.class);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((Face) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }
}
