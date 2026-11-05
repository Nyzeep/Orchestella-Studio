package io.github.nyzeep.orchestella.runtime.instance.duallane;

import java.util.Objects;

/**
 * Shared budget ledger for the QEMU, accelerator and protected-pool lanes.
 *
 * <p>Reservations are explicit and released only when a stop is confirmed.
 * An UNCONFIRMED stop keeps its reservation, matching the "do not delete the
 * lease" rule.
 *
 * <p>Semantics of every {@code cpuCores} field seen by this ledger (R-Q3,
 * {@code docs/research/2026-11-04-qemu-mttcg-scaling.md} §4.2): a <em>lane
 * concurrency budget</em>, never a physical-core count. Callers size the
 * request from the concurrency they intend to run inside the lane: the
 * QEMU-TCG lane sends its guest topology clamped to the measured effective TCG
 * width at the single hardware-to-budget conversion point
 * ({@code AcceleratorLaunchDispatcher} via
 * {@code FabricWidthDecision.qemuLaneBudgetCores}), and the accelerator lane
 * sends the host's full core count for its match_host tier (R-Q2). With both
 * VMM faces live the summed reservations therefore legitimately exceed the
 * physical core count ({@code usedCpu = smp N + match_host full cores}, R-Q7);
 * {@link LaneRejectCode#CPU_EXCEEDED} means the shared concurrency queue is
 * full, not that physical cores are exhausted. The ledger limits concurrency,
 * it never promises core pinning.
 */
public final class LaneBudgetLedger {
    private final LaneBudget budget;
    private int usedCpu;
    private long usedRam;
    private long usedDisk;

    public LaneBudgetLedger(LaneBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    public synchronized Reservation reserve(LaneRequest request) throws LaneRejectedException {
        Objects.requireNonNull(request, "request");
        if (request.cpuCores() <= 0 || request.ramMiB() <= 0L || request.diskGiB() <= 0L) {
            throw new LaneRejectedException(LaneRejectCode.INVALID_REQUEST);
        }
        if (request.lane() == LaneId.ACCEL && !budget.acceleratorAvailable()) {
            throw new LaneRejectedException(LaneRejectCode.ACCELERATOR_UNAVAILABLE);
        }
        if (usedCpu + request.cpuCores() > budget.cpuCores()) {
            throw new LaneRejectedException(LaneRejectCode.CPU_EXCEEDED);
        }
        if (usedRam + request.ramMiB() > budget.ramMiB()) {
            throw new LaneRejectedException(LaneRejectCode.RAM_EXCEEDED);
        }
        if (usedDisk + request.diskGiB() > budget.diskGiB()) {
            throw new LaneRejectedException(LaneRejectCode.DISK_EXCEEDED);
        }
        usedCpu += request.cpuCores();
        usedRam += request.ramMiB();
        usedDisk += request.diskGiB();
        return new Reservation(request.lane(), request.cpuCores(), request.ramMiB(), request.diskGiB());
    }

    public synchronized void release(Reservation reservation) {
        if (reservation == null) {
            return;
        }
        usedCpu = Math.max(0, usedCpu - reservation.cpuCores());
        usedRam = Math.max(0L, usedRam - reservation.ramMiB());
        usedDisk = Math.max(0L, usedDisk - reservation.diskGiB());
    }

    /**
     * Protected-pool view: the pool VM is part of the accelerator lane budget.
     * It does not reserve here because the pool owns its own lease.
     */
    public synchronized boolean canAdmitPool(long memoryMb) {
        return budget.acceleratorAvailable()
                && memoryMb > 0L
                && usedRam + memoryMb <= budget.ramMiB();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(usedCpu, usedRam, usedDisk);
    }

    public LaneBudget budget() {
        return budget;
    }

    /**
     * A live lease against the budget; hold it for the lane's whole run.
     *
     * @param cpuCores concurrency budget held by this lease (R-Q3), not a
     *                 physical-core count — see the class javadoc
     */
    public record Reservation(LaneId lane, int cpuCores, long ramMiB, long diskGiB) {
    }

    /**
     * Point-in-time sums of all live leases.
     *
     * @param cpuCores total concurrency budget currently reserved (R-Q3),
     *                 legitimately above the physical core count when both
     *                 VMM faces are live (R-Q7)
     */
    public record Snapshot(int cpuCores, long ramMiB, long diskGiB) {
    }
}
