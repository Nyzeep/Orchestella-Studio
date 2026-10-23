package io.github.nyzeep.orchestella.runtime.instance.duallane;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** AX-01: shared CPU/RAM/disk ledger semantics. */
public final class LaneBudgetLedgerTest {
    private static final LaneBudget BUDGET = new LaneBudget(8, 8192L, 128L, true);
    private static final LaneRequest QEMU = new LaneRequest(LaneId.QEMU, 4, 4096L, 32L);
    private static final LaneRequest ACCEL = new LaneRequest(LaneId.ACCEL, 2, 2048L, 16L);

    @Test
    public void bothLanesFitWithinBudget() throws Exception {
        LaneBudgetLedger ledger = new LaneBudgetLedger(BUDGET);
        ledger.reserve(QEMU);
        ledger.reserve(ACCEL);

        LaneBudgetLedger.Snapshot snapshot = ledger.snapshot();
        assertEquals(6, snapshot.cpuCores());
        assertEquals(6144L, snapshot.ramMiB());
        assertEquals(48L, snapshot.diskGiB());
    }

    @Test
    public void cpuIsRejectedBeforeAnyReservation() {
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(3, 8192L, 128L, true));
        assertRejected(ledger, QEMU, LaneRejectCode.CPU_EXCEEDED);
        assertEquals(0, ledger.snapshot().cpuCores());
    }

    @Test
    public void ramIsRejected() {
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(8, 4095L, 128L, true));
        assertRejected(ledger, QEMU, LaneRejectCode.RAM_EXCEEDED);
    }

    @Test
    public void diskIsRejected() {
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(8, 8192L, 16L, true));
        assertRejected(ledger, QEMU, LaneRejectCode.DISK_EXCEEDED);
    }

    @Test
    public void acceleratorUnavailableRejectsOnlyTheAccelerator() throws Exception {
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(8, 8192L, 128L, false));
        ledger.reserve(QEMU);
        assertRejected(ledger, ACCEL, LaneRejectCode.ACCELERATOR_UNAVAILABLE);
        assertEquals(4096L, ledger.snapshot().ramMiB());
    }

    @Test
    public void invalidRequestIsRejected() {
        LaneBudgetLedger ledger = new LaneBudgetLedger(BUDGET);
        assertRejected(ledger, new LaneRequest(LaneId.QEMU, 0, 4096L, 32L),
                LaneRejectCode.INVALID_REQUEST);
    }

    @Test
    public void releaseRestoresTheBudget() throws Exception {
        LaneBudgetLedger ledger = new LaneBudgetLedger(BUDGET);
        LaneBudgetLedger.Reservation reservation = ledger.reserve(QEMU);
        ledger.release(reservation);

        assertEquals(0, ledger.snapshot().cpuCores());
        assertEquals(0L, ledger.snapshot().ramMiB());
        assertEquals(0L, ledger.snapshot().diskGiB());
    }

    @Test
    public void poolAdmissionUsesRemainingRamAndAcceleratorAvailability() throws Exception {
        LaneBudgetLedger ledger = new LaneBudgetLedger(BUDGET);
        ledger.reserve(QEMU);

        assertTrue(ledger.canAdmitPool(4096L));
        assertFalse(ledger.canAdmitPool(4097L));
        assertFalse(ledger.canAdmitPool(0L));
    }

    @Test
    public void poolAdmissionIsClosedWhenTheAcceleratorIsUnavailable() {
        LaneBudgetLedger ledger = new LaneBudgetLedger(new LaneBudget(8, 8192L, 128L, false));
        assertFalse(ledger.canAdmitPool(1024L));
    }

    @Test
    public void budgetRejectsNonPositiveValues() {
        try {
            new LaneBudget(0, 8192L, 128L, true);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage() != null);
        }
    }

    private static void assertRejected(
            LaneBudgetLedger ledger, LaneRequest request, LaneRejectCode code) {
        try {
            ledger.reserve(request);
            throw new AssertionError("expected " + code);
        } catch (LaneRejectedException expected) {
            assertEquals(code, expected.code());
        }
    }
}
