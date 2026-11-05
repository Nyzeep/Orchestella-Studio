package io.github.nyzeep.orchestella.runtime.instance.duallane;

/**
 * One lane's resource request.
 *
 * <p>{@code cpuCores} is the lane's concurrency budget (R-Q3,
 * {@code docs/research/2026-11-04-qemu-mttcg-scaling.md} §4.2), not a physical
 * core count: the QEMU-TCG lane sends its guest topology clamped to the
 * measured effective TCG width ({@code FabricWidthDecision.qemuLaneBudgetCores}),
 * the accelerator lane sends the host core count for its match_host tier, and
 * {@link LaneBudgetLedger} sums these budgets as a concurrency limit without
 * promising core pinning.
 */
public record LaneRequest(LaneId lane, int cpuCores, long ramMiB, long diskGiB) {
    public LaneRequest {
        if (lane == null) {
            throw new IllegalArgumentException("lane");
        }
    }
}
