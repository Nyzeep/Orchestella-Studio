package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.util.Objects;

/**
 * Optional single-variable QEMU performance knobs used by the T7 experiment.
 *
 * <p>{@link #DEFAULTS} reproduces today's launch arguments exactly: no extra
 * {@code tb-size}, {@code aio}, {@code discard}, {@code detect-zeroes} or vCPU
 * override, the existing {@code cortex-a57} CPU model, and the existing TCP
 * loopback transport to passt. A non-default value is only ever selected by an
 * explicit experiment; this class never changes production defaults.
 *
 * <p>There is deliberately no cache-mode field: {@code cache=unsafe} is not an
 * accepted experiment value.
 */
public final class QemuTuning {
    private static final java.util.regex.Pattern CPU_MODEL =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._=+-]*(,[A-Za-z0-9._=+-]+)*");
    private static final java.util.Set<String> BLOCK_AIO =
            java.util.Set.of("threads", "native", "io_uring");

    /** Today's launch behaviour, byte for byte. */
    public static final QemuTuning DEFAULTS = new QemuTuning(
            "cortex-a57", 0, null, false, false, 0, null);

    private final String cpuModel;
    private final int tbSizeKb;
    private final String blockAio;
    private final boolean discardUnmap;
    private final boolean detectZeroesUnmap;
    private final int vcpuCount;
    private final String passtUnixSocketPath;

    public QemuTuning(
            String cpuModel,
            int tbSizeKb,
            String blockAio,
            boolean discardUnmap,
            boolean detectZeroesUnmap,
            int vcpuCount,
            String passtUnixSocketPath) {
        this.cpuModel = Objects.requireNonNull(cpuModel, "cpuModel");
        if (!CPU_MODEL.matcher(cpuModel).matches()) {
            throw new IllegalArgumentException("invalid CPU model");
        }
        if (tbSizeKb < 0 || tbSizeKb > 1024) {
            throw new IllegalArgumentException("invalid tb-size");
        }
        if (blockAio != null && !BLOCK_AIO.contains(blockAio)) {
            throw new IllegalArgumentException("invalid block aio");
        }
        if (vcpuCount < 0 || vcpuCount > 16) {
            throw new IllegalArgumentException("invalid vCPU count");
        }
        if (passtUnixSocketPath != null
                && (!passtUnixSocketPath.startsWith("/")
                        || passtUnixSocketPath.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100
                        || passtUnixSocketPath.indexOf(',') >= 0
                        || passtUnixSocketPath.indexOf('\\') >= 0
                        || passtUnixSocketPath.chars().anyMatch(value -> value < 0x20))) {
            throw new IllegalArgumentException("invalid passt unix socket path");
        }
        this.tbSizeKb = tbSizeKb;
        this.blockAio = blockAio;
        this.discardUnmap = discardUnmap;
        this.detectZeroesUnmap = detectZeroesUnmap;
        this.vcpuCount = vcpuCount;
        this.passtUnixSocketPath = passtUnixSocketPath;
    }

    public static QemuTuning defaults() {
        return DEFAULTS;
    }

    public String cpuModel() {
        return cpuModel;
    }

    /** {@code 0} omits {@code tb-size}, preserving QEMU's default. */
    public int tbSizeKb() {
        return tbSizeKb;
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

    /** {@code 0} keeps the hardware configuration's vCPU count. */
    public int vcpuCount() {
        return vcpuCount;
    }

    /** {@code null} keeps the existing TCP loopback transport to passt. */
    public String passtUnixSocketPath() {
        return passtUnixSocketPath;
    }
}
