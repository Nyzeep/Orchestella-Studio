package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Optional single-variable QEMU performance knobs used by the T7/P08 experiments.
 *
 * <p>{@link #DEFAULTS} reproduces today's launch arguments exactly: no extra
 * {@code tb-size}, {@code aio}, {@code discard}, {@code detect-zeroes}, iothread,
 * multi-queue, RNG or overcommit option, the existing {@code cortex-a57} CPU model,
 * and the existing TCP loopback transport to passt. A non-default value is only ever
 * selected by an explicit experiment; this class never changes production defaults.
 *
 * <p>{@code tb-size} is expressed in MiB, matching QEMU's
 * {@code -accel tcg,tb-size=N} option. {@code cache=unsafe} is not representable:
 * there is deliberately no cache-mode field, and the root drive keeps
 * {@code cache=writeback}.
 *
 * <p>The new switches are for the P08 matrix. They are intentionally independent so
 * one variant can change exactly one knob; see {@code tools/qemu-tuning/matrix.py}.
 */
public final class QemuTuning {
    /** {@code -cpu max} with SVE disabled; an opt-in TCG experiment. */
    public static final String CPU_MAX_SVE_OFF = "max,sve=off";
    /**
     * The P08 candidate CPU string. {@code pauth-impdef=on} selects the
     * non-cryptographic pointer-auth algorithm; it is valid in QEMU 11.1.1 but
     * redundant because impdef is already used when no qarma option is set.
     */
    public static final String CPU_MAX_SVE_OFF_PAUTH_IMPDEF = "max,sve=off,pauth-impdef=on";

    /** Upper bound for the TCG translation-block cache, in MiB. */
    public static final int MAX_TB_SIZE_MIB = 1024;

    private static final Pattern CPU_MODEL =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._=+-]*(,[A-Za-z0-9._=+-]+)*");
    /**
     * QEMU's {@code aio=threads} is the portable default; {@code io_uring} is an
     * explicit experiment. {@code aio=native} is rejected because QEMU 11.1.1
     * requires {@code cache.direct=on} for it, which this launch spec never sets
     * ({@code block/file-posix.c}: "aio=native ... requires cache.direct=on").
     */
    private static final Set<String> BLOCK_AIO = Set.of("threads", "io_uring");

    /** Today's launch behaviour, byte for byte. */
    public static final QemuTuning DEFAULTS = builder().build();

    private final String cpuModel;
    private final int tbSizeMib;
    private final String blockAio;
    private final boolean discardUnmap;
    private final boolean detectZeroesUnmap;
    private final boolean iothreadPerDisk;
    private final boolean multiQueueVcpu;
    private final boolean rngRandom;
    private final boolean overcommitMemLockOff;
    private final int vcpuCount;
    private final String passtUnixSocketPath;

    private QemuTuning(Builder builder) {
        String selectedCpu = Objects.requireNonNull(builder.cpuModel, "cpuModel");
        if (!CPU_MODEL.matcher(selectedCpu).matches()) {
            throw new IllegalArgumentException("invalid CPU model");
        }
        if (builder.tbSizeMib < 0 || builder.tbSizeMib > MAX_TB_SIZE_MIB) {
            throw new IllegalArgumentException("invalid tb-size (MiB)");
        }
        if (builder.blockAio != null && !BLOCK_AIO.contains(builder.blockAio)) {
            throw new IllegalArgumentException("invalid block aio");
        }
        if (builder.vcpuCount < 0 || builder.vcpuCount > 16) {
            throw new IllegalArgumentException("invalid vCPU count");
        }
        String selectedPasstPath = builder.passtUnixSocketPath;
        if (selectedPasstPath != null
                && (!selectedPasstPath.startsWith("/")
                        || selectedPasstPath.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100
                        || selectedPasstPath.indexOf(',') >= 0
                        || selectedPasstPath.indexOf('\\') >= 0
                        || selectedPasstPath.chars().anyMatch(value -> value < 0x20))) {
            throw new IllegalArgumentException("invalid passt unix socket path");
        }
        this.cpuModel = selectedCpu;
        this.tbSizeMib = builder.tbSizeMib;
        this.blockAio = builder.blockAio;
        this.discardUnmap = builder.discardUnmap;
        this.detectZeroesUnmap = builder.detectZeroesUnmap;
        this.iothreadPerDisk = builder.iothreadPerDisk;
        this.multiQueueVcpu = builder.multiQueueVcpu;
        this.rngRandom = builder.rngRandom;
        this.overcommitMemLockOff = builder.overcommitMemLockOff;
        this.vcpuCount = builder.vcpuCount;
        this.passtUnixSocketPath = selectedPasstPath;
    }

    /**
     * Legacy seven-argument constructor. The P08 switches stay off, so it is
     * byte-identical to {@link #DEFAULTS} when given the default values.
     */
    public QemuTuning(
            String cpuModel,
            int tbSizeMib,
            String blockAio,
            boolean discardUnmap,
            boolean detectZeroesUnmap,
            int vcpuCount,
            String passtUnixSocketPath) {
        this(builder()
                .cpuModel(cpuModel)
                .tbSizeMib(tbSizeMib)
                .blockAio(blockAio)
                .discardUnmap(discardUnmap)
                .detectZeroesUnmap(detectZeroesUnmap)
                .vcpuCount(vcpuCount)
                .passtUnixSocketPath(passtUnixSocketPath));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for the independent P08 experiment switches. */
    public static final class Builder {
        private String cpuModel = "cortex-a57";
        private int tbSizeMib;
        private String blockAio;
        private boolean discardUnmap;
        private boolean detectZeroesUnmap;
        private boolean iothreadPerDisk;
        private boolean multiQueueVcpu;
        private boolean rngRandom;
        private boolean overcommitMemLockOff;
        private int vcpuCount;
        private String passtUnixSocketPath;

        private Builder() {
        }

        public Builder cpuModel(String value) {
            this.cpuModel = value;
            return this;
        }

        /** {@code 0} omits {@code tb-size}; otherwise the value is MiB. */
        public Builder tbSizeMib(int value) {
            this.tbSizeMib = value;
            return this;
        }

        /** {@code null} omits {@code aio}; accepted values are {@code threads} and {@code io_uring}. */
        public Builder blockAio(String value) {
            this.blockAio = value;
            return this;
        }

        public Builder discardUnmap(boolean value) {
            this.discardUnmap = value;
            return this;
        }

        /**
         * {@code detect-zeroes=unmap} only unmaps when {@link #discardUnmap(boolean)}
         * is also on; QEMU otherwise clears the unmap hint and behaves like
         * {@code detect-zeroes=on}.
         */
        public Builder detectZeroesUnmap(boolean value) {
            this.detectZeroesUnmap = value;
            return this;
        }

        /** Adds one {@code -object iothread} and binds the root disk to it. */
        public Builder iothreadPerDisk(boolean value) {
            this.iothreadPerDisk = value;
            return this;
        }

        /** Adds {@code num-queues=<effective vCPU count>} to the root disk device. */
        public Builder multiQueueVcpu(boolean value) {
            this.multiQueueVcpu = value;
            return this;
        }

        /** Adds {@code rng-random} plus an MMIO {@code virtio-rng-device}. */
        public Builder rngRandom(boolean value) {
            this.rngRandom = value;
            return this;
        }

        /**
         * Emits {@code -overcommit mem-lock=off}. This is QEMU's default, so the
         * switch makes an experiment explicit rather than changing behaviour.
         */
        public Builder overcommitMemLockOff(boolean value) {
            this.overcommitMemLockOff = value;
            return this;
        }

        /** {@code 0} keeps the hardware configuration's vCPU count. */
        public Builder vcpuCount(int value) {
            this.vcpuCount = value;
            return this;
        }

        /** {@code null} keeps the existing TCP loopback transport to passt. */
        public Builder passtUnixSocketPath(String value) {
            this.passtUnixSocketPath = value;
            return this;
        }

        public QemuTuning build() {
            return new QemuTuning(this);
        }
    }

    public String cpuModel() {
        return cpuModel;
    }

    /** {@code 0} omits {@code tb-size}, preserving QEMU's automatic default. */
    public int tbSizeMib() {
        return tbSizeMib;
    }

    /** {@code null} omits {@code aio}, preserving QEMU's default. */
    public String blockAio() {
        return blockAio;
    }

    public boolean discardUnmap() {
        return discardUnmap;
    }

    public boolean detectZeroesUnmap() {
        return detectZeroesUnmap;
    }

    public boolean iothreadPerDisk() {
        return iothreadPerDisk;
    }

    public boolean multiQueueVcpu() {
        return multiQueueVcpu;
    }

    public boolean rngRandom() {
        return rngRandom;
    }

    public boolean overcommitMemLockOff() {
        return overcommitMemLockOff;
    }

    /** {@code 0} keeps the hardware configuration's vCPU count. */
    public int vcpuCount() {
        return vcpuCount;
    }

    /** {@code null} keeps the existing TCP loopback transport to passt. */
    public String passtUnixSocketPath() {
        return passtUnixSocketPath;
    }
}
