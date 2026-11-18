package io.github.nyzeep.orchestella.runtime.engineqemu;

/**
 * G-06 single source for GPU fallback reason codes (replaces the silent
 * gl-device → 2D rebind in the packaged launcher).
 *
 * <p>The enum names double as the log tokens emitted by
 * {@link GpuAccelerationStatus#structuredLogLine()}; renaming a constant would
 * silently change the grep-able reason keys, so
 * {@code VirglGpuLifecycleTest} pins the literals.
 */
public enum GpuFallbackReason {
    /** No fallback: GPU active or the acceleration flag is off. */
    NONE,
    /** {@code libvirglrenderer.so} is not packaged/loaded ({@code isAvailable()==false}). */
    NATIVE_UNAVAILABLE,
    /**
     * The native renderer refused {@code init()} (or threw). The bridge API
     * carries no numeric error code, so the status records {@code -1} as the
     * unknown code.
     */
    INIT_FAILED,
}
