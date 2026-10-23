package io.github.nyzeep.orchestella.runtime.instance.duallane;

/** One lane's resource request. */
public record LaneRequest(LaneId lane, int cpuCores, long ramMiB, long diskGiB) {
    public LaneRequest {
        if (lane == null) {
            throw new IllegalArgumentException("lane");
        }
    }
}
