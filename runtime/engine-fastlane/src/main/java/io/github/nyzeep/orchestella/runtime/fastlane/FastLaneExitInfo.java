package io.github.nyzeep.orchestella.runtime.fastlane;

import java.util.Objects;

/** Typed exit observation; a requested stop is not a crash and DELETED is not normal. */
public final class FastLaneExitInfo {
    public enum Kind {
        NORMAL,
        STOP_REQUESTED,
        CRASHED,
        DELETED,
        OBSERVE_FAILED
    }

    private final Kind kind;
    private final int exitCode;
    private final long elapsedMs;
    private final String detail;

    private FastLaneExitInfo(Kind kind, int exitCode, long elapsedMs, String detail) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.exitCode = exitCode;
        this.elapsedMs = elapsedMs;
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    public static FastLaneExitInfo normal(long elapsedMs) {
        return new FastLaneExitInfo(Kind.NORMAL, 0, elapsedMs, "platform reported STOPPED");
    }

    public static FastLaneExitInfo stopRequested(long elapsedMs) {
        return new FastLaneExitInfo(Kind.STOP_REQUESTED, 0, elapsedMs, "host requested stop");
    }

    public static FastLaneExitInfo crashed(int exitCode, long elapsedMs, String detail) {
        return new FastLaneExitInfo(Kind.CRASHED, exitCode, elapsedMs, detail);
    }

    public static FastLaneExitInfo deleted() {
        return new FastLaneExitInfo(Kind.DELETED, -1, 0L, "platform reported DELETED");
    }

    public static FastLaneExitInfo observeFailed(String detail) {
        return new FastLaneExitInfo(Kind.OBSERVE_FAILED, -1, 0L, detail);
    }

    public Kind kind() {
        return kind;
    }

    public int exitCode() {
        return exitCode;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public String detail() {
        return detail;
    }
}
