package io.github.nyzeep.orchestella;

/**
 * Pure decision logic for the foreground service {@code PARTIAL_WAKE_LOCK} lifecycle.
 *
 * <p>Extracted from {@link OrchestellaForegroundService} so the renewal rules are unit
 * testable without an Android runtime. The caller passes monotonic timestamps
 * (elapsed-realtime domain) and applies the decision to the real lock.
 *
 * <p><b>UML face session layer (C lane).</b> {@link #decideSession} is the
 * renewal policy for the UML compile-worker face's keep-alive session
 * ({@link UmlFaceKeepAliveService}): the same shipped 2h per-grant ceiling and
 * the same renew-below-threshold margin the guest-execution service proved,
 * plus the honest session ceiling — a UML face session may renew only
 * {@value #UML_MAX_RENEWALS} times ({@value #UML_MAX_RENEWALS} × 2h = 24h);
 * past the ceiling the answer is {@link UmlSessionDecision#STOP} and the
 * caller performs the honest shutdown (release, stop the service, post the
 * notice) instead of letting the lock quietly lapse under a notification that
 * still claims a running worker. E18 basis (2026-11-07 device round §7.1.6):
 * without the FGS + wake lock pair the OEM cgroup freezer freezes the whole
 * process group — app and UML child together (HyperOS, 33 min zero drift) —
 * while the pair held 60 minutes of zero-freeze residency (MagicOS,
 * RSS Δ+0.04%, 12/12 samples {@code cgroup.freeze=0}); a renewal therefore
 * keeps the measured protection continuous across a long compile session, and
 * the ceiling keeps the honesty: a session that must outlive it is a new
 * explicit session, never a silent forever-lock.
 */
public final class WakeLockPolicy {
    /** Sentinel for "the lock is not held"; timestamps are non-negative. */
    public static final long NOT_HELD = -1L;

    /** What the caller should do with the platform wake lock. */
    public enum Decision {
        /** Not held, or the previous grant already expired: take a fresh grant. */
        ACQUIRE,
        /** Held but close to expiry: release and re-take to extend the deadline. */
        RENEW,
        /** Held with enough time left: leave it alone (no duplicate acquire). */
        NOOP
    }

    /**
     * The UML face session's answer: {@link #HOLD} leaves the live grant
     * alone, {@link #RENEW} re-takes it (a clean grant — the platform deadline
     * tracks the latest acquire), {@link #STOP} ends the session honestly.
     */
    public enum UmlSessionDecision { HOLD, RENEW, STOP }

    /**
     * The UML face's per-grant ceiling — the same project policy as the
     * persistent guest-execution service (2h), not a platform limit.
     */
    public static final long UML_GRANT_TIMEOUT_MS = 2L * 60 * 60 * 1000;

    /**
     * The UML face's renewal margin — the same below-one-hour re-take the
     * guest-execution service shipped, so protection never gaps between
     * grants over a long compile session.
     */
    public static final long UML_RENEW_THRESHOLD_MS = 60L * 60 * 1000;

    /**
     * The UML face session's honest renewal budget: 12 renewals of 2h each is
     * the 24h residency ceiling; past it, honest stop (release + service stop
     * + notice), never a silently unprotected notification.
     */
    public static final int UML_MAX_RENEWALS = 12;

    /** How often the keep-alive seam polls this policy (the glue's tick). */
    public static final long UML_CHECK_INTERVAL_MS = 60L * 1000;

    private WakeLockPolicy() {
    }

    /**
     * @param nowMs monotonic now
     * @param heldSinceMs when the current grant started, or {@link #NOT_HELD}
     * @param timeoutMs per-grant timeout granted to the platform
     * @param renewThresholdMs remaining time below which the grant is renewed
     */
    public static Decision decide(long nowMs, long heldSinceMs, long timeoutMs, long renewThresholdMs) {
        if (heldSinceMs < 0L) {
            return Decision.ACQUIRE;
        }
        long remainingMs = timeoutMs - (nowMs - heldSinceMs);
        if (remainingMs <= 0L) {
            return Decision.ACQUIRE;
        }
        if (remainingMs < renewThresholdMs) {
            return Decision.RENEW;
        }
        return Decision.NOOP;
    }

    /**
     * The UML face session decision with the shipped constants
     * ({@value #UML_GRANT_TIMEOUT_MS} ms grants, renew below
     * {@value #UML_RENEW_THRESHOLD_MS} remaining, at most
     * {@value #UML_MAX_RENEWALS} renewals per session). The caller — the
     * keep-alive seam — executes it on the real lock and posts the honest
     * notice on {@link UmlSessionDecision#STOP}.
     *
     * @param nowMs monotonic now
     * @param heldSinceMs when the current grant started, or {@link #NOT_HELD}
     * @param renewalsDone renewals the session has already spent
     */
    public static UmlSessionDecision decideSession(long nowMs, long heldSinceMs, int renewalsDone) {
        return decideSession(nowMs, heldSinceMs, renewalsDone,
                UML_GRANT_TIMEOUT_MS, UML_RENEW_THRESHOLD_MS, UML_MAX_RENEWALS);
    }

    /**
     * The parameterized session decision — the same contract with every knob
     * configurable for tests and future tuning:
     *
     * <ul>
     *   <li>{@code renewalsDone >= maxRenewals} → {@link UmlSessionDecision#STOP}
     *   (the honest ceiling wins over everything).</li>
     *   <li>The lock is not held → {@link UmlSessionDecision#RENEW} (a lost
     *   grant is re-taken and counted — the session never pretends protection
     *   it does not have).</li>
     *   <li>{@link Decision#RENEW} or an expired grant →
     *   {@link UmlSessionDecision#RENEW}.</li>
     *   <li>Otherwise → {@link UmlSessionDecision#HOLD}.</li>
     * </ul>
     */
    public static UmlSessionDecision decideSession(
            long nowMs, long heldSinceMs, int renewalsDone,
            long grantTimeoutMs, long renewThresholdMs, int maxRenewals) {
        if (maxRenewals < 1) {
            throw new IllegalArgumentException(
                    "maxRenewals must be at least 1, got: " + maxRenewals);
        }
        if (renewThresholdMs <= 0 || renewThresholdMs >= grantTimeoutMs) {
            throw new IllegalArgumentException(
                    "renewThresholdMs must be positive and inside the grant, got: "
                            + renewThresholdMs + " against a " + grantTimeoutMs + "ms grant");
        }
        if (renewalsDone >= maxRenewals) {
            return UmlSessionDecision.STOP;
        }
        if (heldSinceMs == NOT_HELD) {
            return UmlSessionDecision.RENEW;
        }
        Decision grant = decide(nowMs, heldSinceMs, grantTimeoutMs, renewThresholdMs);
        return grant == Decision.NOOP ? UmlSessionDecision.HOLD : UmlSessionDecision.RENEW;
    }
}
