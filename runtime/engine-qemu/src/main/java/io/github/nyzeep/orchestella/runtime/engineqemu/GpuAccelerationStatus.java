package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.util.Objects;

/**
 * G-06 immutable snapshot of the GPU acceleration decision for one launch.
 *
 * <p>The launcher derives it once per launch ({@link VirglGpuLifecycle#activate})
 * before assembling the QEMU arguments and carries it on the session, so the UI
 * layer (G-07) can read the effective GPU state without re-deriving it.
 * {@link #structuredLogLine()} is the grep-able structured record: one line,
 * {@code key=value} tokens, reason codes come from {@link GpuFallbackReason}.
 *
 * <p>Honesty note: {@link State#ACTIVE} means the in-process VirGL context was
 * initialized; real-device rendering via this path remains 未验证 (G-08 owns the
 * benchmark), so consumers must treat ACTIVE as "wired", not "verified".
 */
public final class GpuAccelerationStatus {
    /** Lifecycle state of the GPU decision for one launch. */
    public enum State { ACTIVE, FALLBACK, DISABLED }

    /** QEMU device argument emitted for an ACTIVE GPU context. */
    public static final String GL_DEVICE_ARG = "virtio-gpu-gl-device";
    /** QEMU device argument emitted for the 2D fallback (and for flag-off). */
    public static final String DEVICE_2D_ARG = "virtio-gpu-device";

    private final State state;
    private final GpuFallbackReason reason;
    private final int failureCode;
    private final int quotaMb;
    private final boolean robustness;

    private GpuAccelerationStatus(State state, GpuFallbackReason reason, int failureCode,
            int quotaMb, boolean robustness) {
        this.state = state;
        this.reason = reason;
        this.failureCode = failureCode;
        this.quotaMb = quotaMb;
        this.robustness = robustness;
    }

    /** Acceleration flag off: the bridge is never touched, spec stays on the 2D device. */
    public static GpuAccelerationStatus disabled() {
        return new GpuAccelerationStatus(State.DISABLED, GpuFallbackReason.NONE, 0, 0, false);
    }

    /** Native context initialized; spec keeps the gl-device arguments. */
    public static GpuAccelerationStatus active(int quotaMb) {
        return new GpuAccelerationStatus(State.ACTIVE, GpuFallbackReason.NONE, 0, quotaMb, true);
    }

    /**
     * Explicit fallback with a reason code: native missing or init failed. The
     * spec is downgraded to the 2D device and the reason is logged.
     *
     * @param failureCode {@code 0} when the reason carries no code, {@code -1} for an unknown code
     */
    public static GpuAccelerationStatus fallback(GpuFallbackReason reason, int failureCode, int quotaMb) {
        return new GpuAccelerationStatus(State.FALLBACK, reason, failureCode, quotaMb, false);
    }

    public State state() {
        return state;
    }

    public GpuFallbackReason reason() {
        return reason;
    }

    /** {@code -1} for an unknown code (the bridge API has no numeric error). */
    public int failureCode() {
        return failureCode;
    }

    /** GPU memory quota the bridge was configured with (0 when disabled). */
    public int quotaMb() {
        return quotaMb;
    }

    public boolean robustness() {
        return robustness;
    }

    /** True only when the launch may keep the {@code virtio-gpu-gl-device} arguments. */
    public boolean gpuDeviceActive() {
        return state == State.ACTIVE;
    }

    /** The {@code -device} token this launch carries for the virtio-gpu pair. */
    public String deviceArg() {
        return gpuDeviceActive() ? GL_DEVICE_ARG : DEVICE_2D_ARG;
    }

    /**
     * Single-line structured log record (grep-able reason code):
     * {@code gpu-acceleration state=<State> reason=<GpuFallbackReason> code=<n> device=<arg> quotaMb=<n> robustness=<b>}
     */
    public String structuredLogLine() {
        return "gpu-acceleration state=" + state
                + " reason=" + reason
                + " code=" + failureCode
                + " device=" + deviceArg()
                + " quotaMb=" + quotaMb
                + " robustness=" + robustness;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GpuAccelerationStatus)) return false;
        GpuAccelerationStatus that = (GpuAccelerationStatus) o;
        return state == that.state
                && reason == that.reason
                && failureCode == that.failureCode
                && quotaMb == that.quotaMb
                && robustness == that.robustness;
    }

    @Override public int hashCode() {
        return Objects.hash(state, reason, failureCode, quotaMb, robustness);
    }

    @Override public String toString() {
        return structuredLogLine();
    }
}
