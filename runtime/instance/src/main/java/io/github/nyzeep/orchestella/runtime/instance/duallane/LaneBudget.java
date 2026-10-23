package io.github.nyzeep.orchestella.runtime.instance.duallane;

/** Host budget shared by both lanes. */
public record LaneBudget(int cpuCores, long ramMiB, long diskGiB, boolean acceleratorAvailable) {
    public LaneBudget {
        if (cpuCores <= 0 || ramMiB <= 0L || diskGiB <= 0L) {
            throw new IllegalArgumentException("budget must be positive");
        }
    }
}
