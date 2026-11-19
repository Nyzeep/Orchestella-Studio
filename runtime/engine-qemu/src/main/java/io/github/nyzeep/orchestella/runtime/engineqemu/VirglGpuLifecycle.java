package io.github.nyzeep.orchestella.runtime.engineqemu;

/**
 * G-06 production gate for the VirGL GPU lifecycle (first real caller of
 * {@link VirglJniBridge#init}).
 *
 * <p>Decision contract (tested by {@code VirglGpuLifecycleTest}):
 * <ul>
 *   <li>flag off → {@link GpuAccelerationStatus.State#DISABLED}, the bridge is
 *       never touched and the spec stays on the 2D device;</li>
 *   <li>flag on + {@code isAvailable()==false} → fallback
 *       {@code NATIVE_UNAVAILABLE} without calling init;</li>
 *   <li>flag on + QEMU device model probe unsupported (DW-01) → fallback
 *       {@code QEMU_DEVICE_MODEL_UNSUPPORTED} without calling init: the
 *       packaged binary would exit 1 on the gl-device arguments, so the
 *       launch downgrades to the 2D device and the structured log states the
 *       real reason instead of a false ACTIVE;</li>
 *   <li>flag on + probe supported + init ok →
 *       {@link GpuAccelerationStatus.State#ACTIVE}, the launch keeps the
 *       {@code virtio-gpu-gl-device} arguments;</li>
 *   <li>flag on + init false/throw → fallback {@code INIT_FAILED} (code -1);
 *       a throwing bridge never breaks the launch.</li>
 * </ul>
 *
 * <p>Cleanup keeps the pre-G-06 sequencing: the launcher's exit watcher calls
 * {@link VirglJniBridge#cleanup()} exactly as before; this class only gates
 * activation. The bridge seam is package-visible for tests only — production
 * always runs the {@link VirglJniBridge} delegation.
 */
public final class VirglGpuLifecycle {
    /** GPU memory quota ceiling, aligned with {@link MemoryQuotaSentinel}'s default (1024 MB). */
    public static final int MAX_GPU_QUOTA_MB =
            (int) (MemoryQuotaSentinel.DEFAULT_MAX_QUOTA_BYTES / (1024L * 1024L));

    /** Narrow seam over the static bridge so JVM tests can drive all branches. */
    public interface Bridge {
        boolean isAvailable();
        boolean init(int quotaMb, boolean robustness);
    }

    private static final Bridge DEFAULT_BRIDGE = new Bridge() {
        @Override public boolean isAvailable() {
            return VirglJniBridge.isAvailable();
        }

        @Override public boolean init(int quotaMb, boolean robustness) {
            return VirglJniBridge.init(quotaMb, robustness);
        }
    };

    private static volatile Bridge bridge = DEFAULT_BRIDGE;

    private VirglGpuLifecycle() {
    }

    /** Test-only seam; {@code null} restores the {@link VirglJniBridge} delegation. */
    static void useBridgeForTest(Bridge replacement) {
        bridge = replacement == null ? DEFAULT_BRIDGE : replacement;
    }

    /** The active seam; package-visible so tests can pin the delegation contract. */
    static Bridge defaultBridge() {
        return DEFAULT_BRIDGE;
    }

    /**
     * Quota sizing rule: the instance's configured RAM as the ceiling, capped by
     * the {@link MemoryQuotaSentinel} default (1024 MB) and floored at 1 MB so a
     * degenerate config can never produce a zero/negative quota.
     */
    public static int quotaMbForRam(int ramMb) {
        int usable = Math.max(1, ramMb);
        return Math.min(MAX_GPU_QUOTA_MB, usable);
    }

    /**
     * Legacy two-argument gate without the DW-01 device-model probe: the
     * decision contract is identical except the QEMU device model is assumed
     * supported. Kept byte-for-byte compatible for existing callers/tests;
     * production launches must use {@link #activate(boolean, int, String)}
     * with the packaged QEMU path.
     */
    public static GpuAccelerationStatus activate(boolean gpuAccelerationRequested, int ramMb) {
        return activate(gpuAccelerationRequested, ramMb, null);
    }

    /**
     * DW-01: runs the activation gate for one launch, including the QEMU
     * device-model probe when {@code qemuBinaryPath} is supplied (the packaged
     * {@code libdroidlayer_qemu.so} path the launch will exec). Never throws:
     * bridge failures are converted into an {@code INIT_FAILED} fallback and a
     * failing probe fail-closes to {@code QEMU_DEVICE_MODEL_UNSUPPORTED}, so
     * the launch continues on the 2D device. The probe execs a process and is
     * cached process-wide — callers must be off the Android main thread, which
     * the launcher's worker-thread launch path already guarantees.
     */
    public static GpuAccelerationStatus activate(boolean gpuAccelerationRequested, int ramMb,
            String qemuBinaryPath) {
        if (!gpuAccelerationRequested) {
            return GpuAccelerationStatus.disabled();
        }
        int quotaMb = quotaMbForRam(ramMb);
        if (!bridge.isAvailable()) {
            return GpuAccelerationStatus.fallback(GpuFallbackReason.NATIVE_UNAVAILABLE, 0, quotaMb);
        }
        if (qemuBinaryPath != null && !deviceModelSupported(qemuBinaryPath)) {
            return GpuAccelerationStatus.fallback(
                    GpuFallbackReason.QEMU_DEVICE_MODEL_UNSUPPORTED, 0, quotaMb);
        }
        boolean initialized;
        try {
            initialized = bridge.init(quotaMb, true);
        } catch (RuntimeException | Error failure) {
            initialized = false;
        }
        if (!initialized) {
            return GpuAccelerationStatus.fallback(GpuFallbackReason.INIT_FAILED, -1, quotaMb);
        }
        return GpuAccelerationStatus.active(quotaMb);
    }

    /**
     * DW-01 fail-closed read of the probe: any unexpected failure (the probe
     * already swallows its own failure modes, this only keeps the
     * "activate() never throws" contract intact against Errors) is treated as
     * unsupported — see {@link QemuDeviceModelProbe} for the asymmetric-cost
     * rationale.
     */
    private static boolean deviceModelSupported(String qemuBinaryPath) {
        try {
            return QemuDeviceModelProbe.probeOutcome(qemuBinaryPath)
                    == QemuDeviceModelProbe.Outcome.SUPPORTED;
        } catch (RuntimeException | Error failure) {
            return false;
        }
    }
}
