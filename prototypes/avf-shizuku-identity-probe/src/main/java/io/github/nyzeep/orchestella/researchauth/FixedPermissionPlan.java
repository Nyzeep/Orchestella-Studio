package io.github.nyzeep.orchestella.researchauth;

import java.util.Objects;

/** Fixed QA package/current-user permission experiment; no arbitrary package, permission or command. */
public final class FixedPermissionPlan {
    public static final String PACKAGE = "io.github.nyzeep.orchestella.researchauth";
    public enum Permission {
        MANAGE("android.permission.MANAGE_VIRTUAL_MACHINE"),
        CUSTOM("android.permission.USE_CUSTOM_VIRTUAL_MACHINE");
        public final String androidName;
        Permission(String androidName) { this.androidName = androidName; }
    }
    public enum State { GRANTED, DENIED, UNKNOWN }
    public enum Outcome { BOTH_GRANTED_PREPARATION_ONLY, PARTIAL, DENIED, UNKNOWN, REJECTED, STALE }
    public interface Port {
        // Called at every boundary, not just on initial user consent.
        Facts facts();
        State read(Permission permission);
        void grant(Permission permission);
        void revoke(Permission permission);
    }
    public static final class Facts {
        public final boolean client, binder, requestedManage, requestedCustom, grant, revoke;
        public final int serverUid, userId, targetUid;
        public final long epoch;
        public Facts(boolean client, boolean binder, int serverUid, int userId, int targetUid,
                boolean requestedManage, boolean requestedCustom, boolean grant, boolean revoke, long epoch) {
            this.client = client; this.binder = binder; this.serverUid = serverUid; this.userId = userId;
            this.targetUid = targetUid; this.requestedManage = requestedManage;
            this.requestedCustom = requestedCustom; this.grant = grant; this.revoke = revoke; this.epoch = epoch;
        }
    }
    public static final class Result {
        public final Outcome outcome;
        public final State manage, custom;
        Result(Outcome outcome, State manage, State custom) {
            this.outcome = outcome; this.manage = manage; this.custom = custom;
        }
    }
    private final Port port;
    private final State[] initial = new State[2];
    private final boolean[] added = new boolean[2];
    private long epoch;
    private int targetUid;
    private boolean prepared;
    private boolean busy;

    public FixedPermissionPlan(Port port) { this.port = Objects.requireNonNull(port); }

    public synchronized Result grantSelected(boolean userSelectedManage, boolean userSelectedCustom) {
        if (busy || (!userSelectedManage && !userSelectedCustom)) return rejected();
        busy = true;
        try {
            Facts facts = port.facts();
            if (!eligible(facts, false)) return rejected();
            if (!prepared) {
                epoch = facts.epoch; targetUid = facts.targetUid;
                initial[0] = port.read(Permission.MANAGE); initial[1] = port.read(Permission.CUSTOM);
                if (initial[0] == State.UNKNOWN || initial[1] == State.UNKNOWN) return unknown();
                prepared = true;
            }
            if (!current(false)) return stale();
            boolean[] selected = {userSelectedManage, userSelectedCustom};
            for (Permission permission : Permission.values()) {
                if (!selected[permission.ordinal()]) continue;
                if (!current(false)) return stale();
                State before = port.read(permission);
                if (before == State.UNKNOWN) return unknown();
                if (before == State.GRANTED) continue;
                try { port.grant(permission); }
                catch (RuntimeException failure) { /* grant exit/exception is not authoritative */ }
                if (!current(false)) return stale();
                State after = port.read(permission);
                // Remember only a confirmed transition from an originally denied permission.
                if (initial[permission.ordinal()] == State.DENIED && after == State.GRANTED) {
                    added[permission.ordinal()] = true;
                }
                if (after != State.GRANTED) return readResult(); // stop; never retry or grant next after unknown/failure
            }
            return readResult();
        } catch (RuntimeException failure) { return unknown(); }
        finally { busy = false; }
    }

    /** User explicitly chooses restore; never revoke permission that predated this experiment. */
    public synchronized Result restoreAdded(boolean userConfirmed) {
        if (busy || !prepared || !userConfirmed) return rejected();
        busy = true;
        try {
            if (!current(true)) return stale();
            for (Permission permission : Permission.values()) {
                if (!added[permission.ordinal()]) continue;
                if (!current(true)) return stale();
                try { port.revoke(permission); }
                catch (RuntimeException failure) { return unknown(); }
                if (!current(true)) return stale();
                State after = port.read(permission);
                if (after != State.DENIED) return unknown();
                added[permission.ordinal()] = false;
            }
            return readResult();
        } catch (RuntimeException failure) { return unknown(); }
        finally { busy = false; }
    }

    private boolean current(boolean restoring) {
        Facts now = port.facts();
        return eligible(now, restoring) && now.epoch == epoch && now.targetUid == targetUid;
    }
    private static boolean eligible(Facts facts, boolean restoring) {
        return baseEligible(facts) && (restoring ? facts.revoke : facts.grant);
    }
    private static boolean baseEligible(Facts facts) {
        return facts != null && facts.client && facts.binder && facts.serverUid == 2000
                && facts.userId == 0 && facts.targetUid >= 10000 && facts.targetUid < 99000
                && facts.requestedManage && facts.requestedCustom && facts.epoch > 0;
    }
    private Result readResult() {
        Facts before = port.facts();
        if (!baseEligible(before) || before.epoch != epoch || before.targetUid != targetUid) return stale();
        State a = port.read(Permission.MANAGE), b = port.read(Permission.CUSTOM);
        Facts after = port.facts();
        if (!baseEligible(after) || after.epoch != epoch || after.targetUid != targetUid) return stale();
        Outcome value = a == State.UNKNOWN || b == State.UNKNOWN ? Outcome.UNKNOWN
                : a == State.GRANTED && b == State.GRANTED ? Outcome.BOTH_GRANTED_PREPARATION_ONLY
                : a == State.GRANTED || b == State.GRANTED ? Outcome.PARTIAL : Outcome.DENIED;
        return new Result(value, a, b);
    }
    private static Result rejected() { return new Result(Outcome.REJECTED, State.UNKNOWN, State.UNKNOWN); }
    private static Result stale() { return new Result(Outcome.STALE, State.UNKNOWN, State.UNKNOWN); }
    private static Result unknown() { return new Result(Outcome.UNKNOWN, State.UNKNOWN, State.UNKNOWN); }
}
