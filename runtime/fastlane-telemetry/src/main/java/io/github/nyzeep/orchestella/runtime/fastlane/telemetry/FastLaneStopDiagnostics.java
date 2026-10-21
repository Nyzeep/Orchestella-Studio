package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import java.util.Objects;

/**
 * Diagnostic facts for a stop that could not be confirmed. This is not a new
 * instance state: the instance stays RUNNING and this record explains where the
 * stop stopped converging. It never serializes the word STOPPED.
 */
public final class FastLaneStopDiagnostics {
    private final FastLaneStopStage stage;
    private final long pid;
    private final long graceMs;
    private final Integer exitCode;

    public FastLaneStopDiagnostics(
            FastLaneStopStage stage, long pid, long graceMs, Integer exitCode) {
        this.stage = Objects.requireNonNull(stage, "stage");
        if (pid < 0L) {
            throw new IllegalArgumentException("pid must be non-negative");
        }
        if (graceMs < 0L) {
            throw new IllegalArgumentException("graceMs must be non-negative");
        }
        this.pid = pid;
        this.graceMs = graceMs;
        this.exitCode = exitCode;
    }

    public FastLaneStopStage stage() {
        return stage;
    }

    /** {@code 0} means the owner did not expose a pid. */
    public long pid() {
        return pid;
    }

    public long graceMs() {
        return graceMs;
    }

    /** {@code null} means no exit code was observed. */
    public Integer exitCode() {
        return exitCode;
    }

    String toJson() {
        return "{\"stage\":\"" + stage.name()
                + "\",\"pid\":" + pid
                + ",\"graceMs\":" + graceMs
                + ",\"exitCode\":" + (exitCode == null ? "null" : exitCode.toString())
                + "}";
    }
}
