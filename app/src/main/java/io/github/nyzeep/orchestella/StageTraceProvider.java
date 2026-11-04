package io.github.nyzeep.orchestella;

import android.content.Context;
import android.os.SystemClock;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * Composition root for launch stage diagnostics (ticket 27).
 *
 * <p>Switch policy: diagnostics are opt-in. While the setting is off, the
 * provider hands out the disabled recorder without reading any identity,
 * creating any file, queue or thread — launch behavior is bit-for-bit
 * unchanged. When the setting is on, one trace per launch attempt is bound to
 * its real identity (instance, launch-scoped session, launch-guard generation,
 * actual strategy); a launch whose identity cannot be honestly bound records
 * nothing rather than a fake identity.
 *
 * <p>The {@link #open} seam is pure JVM (clock and directory injected); the
 * Android entry points only resolve the setting, the private directory and the
 * monotonic clock.
 */
public final class StageTraceProvider {
    /** Private subdirectory holding the versioned trace files. */
    public static final String TRACE_DIRECTORY_NAME = "stage-traces";

    private StageTraceProvider() {}

    /** The disabled recorder: zero persistent collection. */
    public static ProductionStageRecorder disabled() {
        return ProductionStageRecorder.disabled();
    }

    /**
     * Pure seam: when {@code enabled} is false the directory is never touched;
     * when enabled, one bounded versioned file sink is created lazily on first
     * write. An identity that cannot be honestly bound disables the trace.
     */
    public static ProductionStageRecorder open(boolean enabled, Path directory,
            ProductionStageRecorder.Identity identity, LongSupplier clock, String clockName) {
        if (!enabled || identity == null) return ProductionStageRecorder.disabled();
        StageTraceFileSink sink = StageTraceFileSink.defaultV1(directory);
        return ProductionStageRecorder.enabled(identity, clock, clockName,
                sink::writeLine);
    }

    /**
     * Production entry point: resolves the opt-in setting and binds one
     * observer to this launch's real identity. Returns a no-op observer when
     * diagnostics are off or the identity cannot be honestly bound — the
     * launch flow never changes either way.
     */
    public static LaunchStageObserver observerForLaunch(Context context, String instanceId,
            String generation, String vmAlias) {
        boolean enabled = context.getApplicationContext()
                .getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SettingsManager.KEY_STAGE_TRACE_DIAGNOSTICS, false);
        if (!enabled) return new LaunchStageObserver() {};
        String sessionId = "s-" + java.util.UUID.randomUUID();
        ProductionStageRecorder.Identity identity = ProductionStageRecorder.Identity.of(
                instanceId, sessionId, generation, vmAlias);
        if (identity == null) {
            android.util.Log.w("OrchestellaStageTrace",
                    "stage trace identity unbindable; recording nothing this launch");
            return new LaunchStageObserver() {};
        }
        ProductionStageRecorder recorder = open(true,
                context.getFilesDir().toPath().resolve(TRACE_DIRECTORY_NAME), identity,
                StageTraceProvider::androidMonotonicNanos,
                ProductionStageRecorder.CLOCK_ANDROID_ELAPSED_REALTIME_NS);
        return observer(recorder, identity);
    }

    /** The single production time base: Android elapsedRealtimeNanos. */
    public static long androidMonotonicNanos() {
        return SystemClock.elapsedRealtimeNanos();
    }

    /**
     * Binds a recorder to its identity as an observer for the trigger points.
     * All recorder results are ignored and every callback is failure-isolated:
     * observation can never affect control flow.
     */
    public static LaunchStageObserver observer(ProductionStageRecorder recorder,
            ProductionStageRecorder.Identity identity) {
        if (recorder == null || identity == null) return new LaunchStageObserver() {};
        return new BoundObserver(recorder, identity);
    }

    private static final class BoundObserver implements LaunchStageObserver {
        private final ProductionStageRecorder recorder;
        private final ProductionStageRecorder.Identity identity;

        BoundObserver(ProductionStageRecorder recorder,
                ProductionStageRecorder.Identity identity) {
            this.recorder = recorder;
            this.identity = identity;
        }

        @Override public void onGate(ProductionStageRecorder.Stage stage) {
            try {
                recorder.mark(stage, identity);
            } catch (RuntimeException | LinkageError ignored) {
                // Observation must never affect the launch.
            }
        }

        @Override public void onGateAuthenticated(ProductionStageRecorder.Stage stage) {
            try {
                recorder.markAuthenticated(stage, identity);
            } catch (RuntimeException | LinkageError ignored) {
                // Observation must never affect the launch.
            }
        }

        @Override public void onFallback(ProductionStageRecorder.Failure failure) {
            try {
                recorder.fallback(failure, identity);
            } catch (RuntimeException | LinkageError ignored) {
                // Observation must never affect the launch.
            }
        }

        @Override public void onFailure(ProductionStageRecorder.Failure failure) {
            try {
                recorder.fail(failure, identity);
            } catch (RuntimeException | LinkageError ignored) {
                // Observation must never affect the launch.
            }
        }

        @Override public void onCancel() {
            try {
                recorder.cancel(identity);
            } catch (RuntimeException | LinkageError ignored) {
                // Observation must never affect the launch.
            }
        }

        @Override public void onStopFact(ProductionStageRecorder.StopFact fact) {
            try {
                recorder.observeStop(fact, identity);
            } catch (RuntimeException | LinkageError ignored) {
                // Observation must never affect the launch.
            }
        }

        @Override public void onFinish() {
            try {
                recorder.finish(identity);
            } catch (RuntimeException | LinkageError ignored) {
                // Observation must never affect the launch.
            }
        }
    }
}
