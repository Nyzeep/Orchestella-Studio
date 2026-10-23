package io.github.nyzeep.orchestella.runtime.instance.duallane;

import java.util.Objects;

/**
 * Shared budget ledger for the QEMU, accelerator and protected-pool lanes.
 *
 * <p>Reservations are explicit and released only when a stop is confirmed.
 * An UNCONFIRMED stop keeps its reservation, matching the "do not delete the
 * lease" rule.
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

    public record Reservation(LaneId lane, int cpuCores, long ramMiB, long diskGiB) {
    }

    public record Snapshot(int cpuCores, long ramMiB, long diskGiB) {
    }
}
