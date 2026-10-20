package io.github.nyzeep.orchestella.runtime.engineavf;

import java.util.Objects;

/**
 * Typed observation of how one AVF guest stopped. The platform does not expose
 * a guest exit code through the pinned SystemApi, so the numeric code is an
 * honest synthesis: {@code 0} only for an observed guest-initiated stop,
 * a non-zero code for a crash, deletion or unobservable exit, and {@code 0}
 * for an exit caused by the host's own stop request (which is a separate kind).
 */
public final class AvfExitInfo {
    public enum Kind {
        /** The guest stopped on its own with no stop request and no failure evidence. */
        NORMAL,
        /** The VM disappeared or failed without a requested stop. */
        CRASHED,
        /** The platform reports the VM as DELETED. Never a normal exit. */
        DELETED,
        /** The host requested this stop; the exit is intentional but distinguishable. */
        STOP_REQUESTED,
        /** The status could not be observed; no evidence of a stop. */
        OBSERVE_FAILED,
        /** The VM stopped shortly after a network-enabled run with an empty console. */
        NETWORK_BACKEND_UNAVAILABLE
    }

    private final Kind kind;
    private final int exitCode;
    private final String detail;

    private AvfExitInfo(Kind kind, int exitCode, String detail) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.exitCode = exitCode;
        this.detail = detail == null ? "" : detail;
    }

    public static AvfExitInfo normal(long elapsedMs) {
        return new AvfExitInfo(Kind.NORMAL, 0, "elapsedMs=" + Math.max(0L, elapsedMs));
    }

    public static AvfExitInfo crashed(int code, String detail) {
        // A crash must never be reported as a clean exit.
        return new AvfExitInfo(Kind.CRASHED, code == 0 ? 1 : code, detail);
    }

    public static AvfExitInfo deleted() {
        return new AvfExitInfo(Kind.DELETED, 2, "platform status DELETED");
    }

    public static AvfExitInfo stopRequested(long elapsedMs) {
        return new AvfExitInfo(Kind.STOP_REQUESTED, 0, "elapsedMs=" + Math.max(0L, elapsedMs));
    }

    public static AvfExitInfo observeFailed(String detail) {
        return new AvfExitInfo(Kind.OBSERVE_FAILED, 3, detail);
    }

    public static AvfExitInfo networkBackendUnavailable(int rawStatus, long elapsedMs) {
        return new AvfExitInfo(Kind.NETWORK_BACKEND_UNAVAILABLE,
                rawStatus == 0 ? 35 : rawStatus,
                "elapsedMs=" + Math.max(0L, elapsedMs));
    }

    public Kind kind() {
        return kind;
    }

    public int exitCode() {
        return exitCode;
    }

    /** Secret-free diagnostic text; never carries user content or tokens. */
    public String detail() {
        return detail;
    }

    public boolean confirmedStopped() {
        return kind == Kind.NORMAL || kind == Kind.DELETED || kind == Kind.STOP_REQUESTED;
    }
}
