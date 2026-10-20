package io.github.nyzeep.orchestella.runtime.contract;

import java.util.Objects;

/**
 * Immutable, strategy-neutral VM hardware configuration model (M4 Ticket 49 / ADR-0019).
 *
 * <p>Defines guest execution resource constraints (vCPU cores, RAM size, disk capacity,
 * GPU hardware acceleration toggle, and optional extra kernel arguments). Validates all inputs
 * within safety boundaries to prevent host memory exhaustion, invalid smp counts, or disk overflow.
 */
public final class VmHardwareConfig {
    public static final int MIN_CPU_CORES = 1;
    public static final int MAX_CPU_CORES = 8;
    public static final int DEFAULT_CPU_CORES = 2;

    public static final int MIN_RAM_MEGABYTES = 512;
    public static final int MAX_RAM_MEGABYTES = 8192;
    public static final int DEFAULT_RAM_MEGABYTES = 2048;

    public static final long MIN_DISK_CAPACITY_BYTES = 2L * 1024L * 1024L * 1024L; // 2 GB
    public static final long MAX_DISK_CAPACITY_BYTES = 64L * 1024L * 1024L * 1024L; // 64 GB
    public static final long DEFAULT_DISK_CAPACITY_BYTES = 8L * 1024L * 1024L * 1024L; // 8 GB

    public static final boolean DEFAULT_GPU_ACCELERATION_ENABLED = true;

    private final int cpuCores;
    private final int ramMegabytes;
    private final long diskCapacityBytes;
    private final boolean gpuAccelerationEnabled;
    private final String extraKernelArguments;
    /**
     * True when this config is the explicit migration of legacy metadata that
     * carried no hardware block. Provenance only; it does not change the
     * effective hardware and is intentionally excluded from equality.
     */
    private final boolean legacyDefaults;

    public VmHardwareConfig(
            int cpuCores,
            int ramMegabytes,
            long diskCapacityBytes,
            boolean gpuAccelerationEnabled,
            String extraKernelArguments) {
        this(cpuCores, ramMegabytes, diskCapacityBytes, gpuAccelerationEnabled,
                extraKernelArguments, false);
    }

    public VmHardwareConfig(
            int cpuCores,
            int ramMegabytes,
            long diskCapacityBytes,
            boolean gpuAccelerationEnabled,
            String extraKernelArguments,
            boolean legacyDefaults) {
        if (cpuCores < MIN_CPU_CORES || cpuCores > MAX_CPU_CORES) {
            throw new IllegalArgumentException(
                    "cpuCores must be between " + MIN_CPU_CORES + " and " + MAX_CPU_CORES + ", got: " + cpuCores);
        }
        if (ramMegabytes < MIN_RAM_MEGABYTES || ramMegabytes > MAX_RAM_MEGABYTES) {
            throw new IllegalArgumentException(
                    "ramMegabytes must be between " + MIN_RAM_MEGABYTES + " and " + MAX_RAM_MEGABYTES + ", got: " + ramMegabytes);
        }
        if (diskCapacityBytes < MIN_DISK_CAPACITY_BYTES || diskCapacityBytes > MAX_DISK_CAPACITY_BYTES) {
            throw new IllegalArgumentException(
                    "diskCapacityBytes must be between " + MIN_DISK_CAPACITY_BYTES + " and " + MAX_DISK_CAPACITY_BYTES + ", got: " + diskCapacityBytes);
        }
        this.cpuCores = cpuCores;
        this.ramMegabytes = ramMegabytes;
        this.diskCapacityBytes = diskCapacityBytes;
        this.gpuAccelerationEnabled = gpuAccelerationEnabled;
        this.extraKernelArguments = extraKernelArguments != null && !extraKernelArguments.trim().isEmpty()
                ? extraKernelArguments.trim()
                : null;
        this.legacyDefaults = legacyDefaults;
    }

    public static VmHardwareConfig defaultConfig() {
        return new VmHardwareConfig(
                DEFAULT_CPU_CORES,
                DEFAULT_RAM_MEGABYTES,
                DEFAULT_DISK_CAPACITY_BYTES,
                DEFAULT_GPU_ACCELERATION_ENABLED,
                null);
    }

    public static VmHardwareConfig defaultConfig(boolean gpuAccelerationEnabled) {
        return new VmHardwareConfig(
                DEFAULT_CPU_CORES,
                DEFAULT_RAM_MEGABYTES,
                DEFAULT_DISK_CAPACITY_BYTES,
                gpuAccelerationEnabled,
                null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public int cpuCores() {
        return cpuCores;
    }

    public int ramMegabytes() {
        return ramMegabytes;
    }

    public long diskCapacityBytes() {
        return diskCapacityBytes;
    }

    public boolean gpuAccelerationEnabled() {
        return gpuAccelerationEnabled;
    }

    public String extraKernelArguments() {
        return extraKernelArguments;
    }

    /** True only for the explicit legacy-metadata migration; never a user edit. */
    public boolean isLegacyDefaults() {
        return legacyDefaults;
    }

    public VmHardwareConfig withCpuCores(int cpuCores) {
        return new VmHardwareConfig(
                cpuCores, this.ramMegabytes, this.diskCapacityBytes, this.gpuAccelerationEnabled, this.extraKernelArguments);
    }

    public VmHardwareConfig withRamMegabytes(int ramMegabytes) {
        return new VmHardwareConfig(
                this.cpuCores, ramMegabytes, this.diskCapacityBytes, this.gpuAccelerationEnabled, this.extraKernelArguments);
    }

    public VmHardwareConfig withDiskCapacityBytes(long diskCapacityBytes) {
        return new VmHardwareConfig(
                this.cpuCores, this.ramMegabytes, diskCapacityBytes, this.gpuAccelerationEnabled, this.extraKernelArguments);
    }

    public VmHardwareConfig withGpuAcceleration(boolean gpuAccelerationEnabled) {
        return new VmHardwareConfig(
                this.cpuCores, this.ramMegabytes, this.diskCapacityBytes, gpuAccelerationEnabled, this.extraKernelArguments);
    }

    public VmHardwareConfig withExtraKernelArguments(String extraKernelArguments) {
        return new VmHardwareConfig(
                this.cpuCores, this.ramMegabytes, this.diskCapacityBytes, this.gpuAccelerationEnabled, extraKernelArguments);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        VmHardwareConfig that = (VmHardwareConfig) o;
        return cpuCores == that.cpuCores
                && ramMegabytes == that.ramMegabytes
                && diskCapacityBytes == that.diskCapacityBytes
                && gpuAccelerationEnabled == that.gpuAccelerationEnabled
                && Objects.equals(extraKernelArguments, that.extraKernelArguments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cpuCores, ramMegabytes, diskCapacityBytes, gpuAccelerationEnabled, extraKernelArguments);
    }

    @Override
    public String toString() {
        return "VmHardwareConfig{"
                + "cpuCores=" + cpuCores
                + ", ramMegabytes=" + ramMegabytes
                + ", diskCapacityBytes=" + diskCapacityBytes
                + ", gpuAccelerationEnabled=" + gpuAccelerationEnabled
                + (extraKernelArguments != null ? ", extraKernelArguments='" + extraKernelArguments + '\'' : "")
                + '}';
    }

    /** Fluent builder for constructing immutable {@link VmHardwareConfig} instances. */
    public static final class Builder {
        private int cpuCores = DEFAULT_CPU_CORES;
        private int ramMegabytes = DEFAULT_RAM_MEGABYTES;
        private long diskCapacityBytes = DEFAULT_DISK_CAPACITY_BYTES;
        private boolean gpuAccelerationEnabled = DEFAULT_GPU_ACCELERATION_ENABLED;
        private String extraKernelArguments = null;

        public Builder cpuCores(int cpuCores) {
            this.cpuCores = cpuCores;
            return this;
        }

        public Builder ramMegabytes(int ramMegabytes) {
            this.ramMegabytes = ramMegabytes;
            return this;
        }

        public Builder diskCapacityBytes(long diskCapacityBytes) {
            this.diskCapacityBytes = diskCapacityBytes;
            return this;
        }

        public Builder gpuAccelerationEnabled(boolean gpuAccelerationEnabled) {
            this.gpuAccelerationEnabled = gpuAccelerationEnabled;
            return this;
        }

        public Builder extraKernelArguments(String extraKernelArguments) {
            this.extraKernelArguments = extraKernelArguments;
            return this;
        }

        public VmHardwareConfig build() {
            return new VmHardwareConfig(
                    cpuCores, ramMegabytes, diskCapacityBytes, gpuAccelerationEnabled, extraKernelArguments);
        }
    }
}
