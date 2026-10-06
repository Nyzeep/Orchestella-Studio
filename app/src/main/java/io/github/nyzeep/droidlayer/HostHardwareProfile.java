package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.contract.VmHardwareConfig;
import java.util.Objects;

/**
 * Strategy-neutral immutable host physical hardware profile (Ticket 50 / ADR-0019).
 *
 * <p>Encapsulates physical CPU core count and RAM statistics, and provides a safe allocation
 * calculation model ensuring host Android OS responsiveness and LMK prevention:
 * <ul>
 *   <li>Mandatory host OS RAM reservation of at least 2048 MB (2 GB).</li>
 *   <li>Recommended RAM: {@code min(totalRamMb - 2048, availRamMb * 0.75)}.</li>
 *   <li>Recommended vCPU: {@code max(1, totalCores - 1)}.</li>
 *   <li>Safety zone partitioning: {@link SafetyZone#GREEN}, {@link SafetyZone#YELLOW}, and {@link SafetyZone#RED}.</li>
 * </ul>
 */
public final class HostHardwareProfile {
    public static final int HOST_RESERVED_RAM_MB = 2048;
    public static final long HOST_RESERVED_RAM_BYTES = 2048L * 1024L * 1024L;

    public enum SafetyZone {
        /** Safe zone: within recommended budget, host retains ample RAM and scheduling headroom. */
        GREEN,
        /** Warning zone: elevated allocation, may induce host background eviction or UI micro-stutters. */
        YELLOW,
        /** Blocked zone: violates mandatory host 2GB reserve or hardware boundaries; allocation forbidden. */
        RED
    }

    private final int totalCores;
    private final long totalRamBytes;
    private final long availRamBytes;
    private final long thresholdRamBytes;
    private final boolean lowMemory;

    public HostHardwareProfile(
            int totalCores,
            long totalRamBytes,
            long availRamBytes,
            long thresholdRamBytes,
            boolean lowMemory) {
        if (totalCores <= 0) {
            throw new IllegalArgumentException("totalCores must be positive: " + totalCores);
        }
        if (totalRamBytes < 0) {
            throw new IllegalArgumentException("totalRamBytes must be non-negative: " + totalRamBytes);
        }
        if (availRamBytes < 0) {
            throw new IllegalArgumentException("availRamBytes must be non-negative: " + availRamBytes);
        }
        if (thresholdRamBytes < 0) {
            throw new IllegalArgumentException("thresholdRamBytes must be non-negative: " + thresholdRamBytes);
        }
        this.totalCores = totalCores;
        this.totalRamBytes = totalRamBytes;
        this.availRamBytes = availRamBytes;
        this.thresholdRamBytes = thresholdRamBytes;
        this.lowMemory = lowMemory;
    }

    public int totalCores() {
        return totalCores;
    }

    public long totalRamBytes() {
        return totalRamBytes;
    }

    public long availRamBytes() {
        return availRamBytes;
    }

    public long thresholdRamBytes() {
        return thresholdRamBytes;
    }

    public boolean isLowMemory() {
        return lowMemory;
    }

    public int totalRamMb() {
        return (int) (totalRamBytes / (1024L * 1024L));
    }

    public int availRamMb() {
        return (int) (availRamBytes / (1024L * 1024L));
    }

    public int thresholdRamMb() {
        return (int) (thresholdRamBytes / (1024L * 1024L));
    }

    /**
     * Calculates the raw recommended RAM in megabytes based on the safe calculation model:
     * {@code min(totalRamMb - 2048, availRamMb * 0.75)}.
     */
    public int rawRecommendedRamMb() {
        int hostAvailableFromTotal = Math.max(0, totalRamMb() - HOST_RESERVED_RAM_MB);
        int safeFromAvail = (int) (availRamMb() * 0.75);
        return Math.min(hostAvailableFromTotal, safeFromAvail);
    }

    /**
     * Calculates the recommended guest RAM clamped to [MIN_RAM_MEGABYTES, MAX_RAM_MEGABYTES]
     * matching the safe calculation model: min(totalRamMb - 2048, availRamMb * 0.75).
     */
    public int recommendedRamMegabytes() {
        int raw = rawRecommendedRamMb();
        return Math.max(VmHardwareConfig.MIN_RAM_MEGABYTES,
                Math.min(raw, VmHardwareConfig.MAX_RAM_MEGABYTES));
    }

    /**
     * Calculates the recommended guest RAM aligned to a step size (e.g., 256MB for UI sliders).
     */
    public int recommendedRamMegabytesAligned(int stepMb) {
        if (stepMb <= 0) {
            return recommendedRamMegabytes();
        }
        int recommended = recommendedRamMegabytes();
        int aligned = (recommended / stepMb) * stepMb;
        return Math.max(VmHardwareConfig.MIN_RAM_MEGABYTES, aligned);
    }

    /**
     * Calculates the recommended vCPU core count:
     * {@code max(1, totalCores - 1)}, leaving at least 1 core for the Android host UI thread.
     */
    public int recommendedCpuCores() {
        int raw = Math.max(1, totalCores - 1);
        return Math.max(VmHardwareConfig.MIN_CPU_CORES,
                Math.min(raw, VmHardwareConfig.MAX_CPU_CORES));
    }

    /**
     * Evaluates the safety zone for a requested guest RAM allocation in megabytes.
     */
    public SafetyZone evaluateRamZone(int ramMb) {
        if (ramMb < VmHardwareConfig.MIN_RAM_MEGABYTES || ramMb > VmHardwareConfig.MAX_RAM_MEGABYTES) {
            return SafetyZone.RED;
        }
        if (ramMb > totalRamMb()) {
            return SafetyZone.RED;
        }
        // Host must retain at least HOST_RESERVED_RAM_MB (2048 MB)
        if (totalRamMb() - ramMb < HOST_RESERVED_RAM_MB) {
            return SafetyZone.RED;
        }
        if (ramMb <= recommendedRamMegabytes() || ramMb <= rawRecommendedRamMb()) {
            return SafetyZone.GREEN;
        }
        return SafetyZone.YELLOW;
    }

    /**
     * Evaluates the safety zone for a requested guest vCPU allocation.
     */
    public SafetyZone evaluateCpuZone(int cpuCores) {
        if (cpuCores < VmHardwareConfig.MIN_CPU_CORES || cpuCores > totalCores
                || cpuCores > VmHardwareConfig.MAX_CPU_CORES) {
            return SafetyZone.RED;
        }
        if (cpuCores <= recommendedCpuCores()) {
            return SafetyZone.GREEN;
        }
        if (cpuCores == totalCores && totalCores > 1) {
            return SafetyZone.YELLOW;
        }
        return SafetyZone.GREEN;
    }

    /**
     * Evaluates the overall safety zone for a hardware configuration.
     */
    public SafetyZone evaluateOverallZone(int cpuCores, int ramMb) {
        SafetyZone cpuZone = evaluateCpuZone(cpuCores);
        SafetyZone ramZone = evaluateRamZone(ramMb);
        if (cpuZone == SafetyZone.RED || ramZone == SafetyZone.RED) {
            return SafetyZone.RED;
        }
        if (cpuZone == SafetyZone.YELLOW || ramZone == SafetyZone.YELLOW) {
            return SafetyZone.YELLOW;
        }
        return SafetyZone.GREEN;
    }

    /**
     * Generates a recommended {@link VmHardwareConfig} derived from this profile.
     */
    public VmHardwareConfig createRecommendedHardwareConfig() {
        return VmHardwareConfig.builder()
                .cpuCores(recommendedCpuCores())
                .ramMegabytes(recommendedRamMegabytes())
                .diskCapacityBytes(VmHardwareConfig.DEFAULT_DISK_CAPACITY_BYTES)
                .gpuAccelerationEnabled(VmHardwareConfig.DEFAULT_GPU_ACCELERATION_ENABLED)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HostHardwareProfile that = (HostHardwareProfile) o;
        return totalCores == that.totalCores
                && totalRamBytes == that.totalRamBytes
                && availRamBytes == that.availRamBytes
                && thresholdRamBytes == that.thresholdRamBytes
                && lowMemory == that.lowMemory;
    }

    @Override
    public int hashCode() {
        return Objects.hash(totalCores, totalRamBytes, availRamBytes, thresholdRamBytes, lowMemory);
    }

    @Override
    public String toString() {
        return "HostHardwareProfile{"
                + "totalCores=" + totalCores
                + ", totalRamMb=" + totalRamMb()
                + ", availRamMb=" + availRamMb()
                + ", thresholdRamMb=" + thresholdRamMb()
                + ", lowMemory=" + lowMemory
                + ", recommendedCpu=" + recommendedCpuCores()
                + ", recommendedRamMb=" + recommendedRamMegabytes()
                + '}';
    }
}
