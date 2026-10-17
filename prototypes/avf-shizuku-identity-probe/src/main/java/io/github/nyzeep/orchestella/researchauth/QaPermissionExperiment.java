package io.github.nyzeep.orchestella.researchauth;

import android.content.Context;
import android.os.Bundle;

/** One live QA session. Restarting the service never invents a restore ledger. */
public final class QaPermissionExperiment {
    private final QaPermissionPort port;
    private final FixedPermissionPlan plan;
    private final long epoch;
    private final int ownerUid;

    public QaPermissionExperiment(Context context, int ownerUid, long epoch) throws ReflectiveOperationException {
        this.epoch = epoch; this.ownerUid = ownerUid;
        port = new QaPermissionPort(context, ownerUid, epoch);
        plan = new FixedPermissionPlan(port);
    }

    public long epoch() { return epoch; }

    public Bundle snapshot() {
        Bundle out = common();
        FixedPermissionPlan.Facts facts = port.facts();
        out.putBoolean("requestedManageDevelopment", facts.requestedManage);
        out.putBoolean("requestedCustomDevelopment", facts.requestedCustom);
        out.putBoolean("serverCanGrant", facts.grant);
        out.putBoolean("serverCanRevoke", facts.revoke);
        out.putString("qaManage", port.read(FixedPermissionPlan.Permission.MANAGE).name());
        out.putString("qaCustom", port.read(FixedPermissionPlan.Permission.CUSTOM).name());
        for (FixedPermissionPlan.Permission permission : FixedPermissionPlan.Permission.values()) {
            try { out.putInt(permission.name() + "Flags", port.permissionFlags(permission)); }
            catch (RuntimeException failure) { out.putString(permission.name() + "Flags", "UNKNOWN_REJECTED"); }
        }
        return out;
    }

    public Bundle grantSelected(boolean manage, boolean custom) {
        return result(plan.grantSelected(manage, custom), "EXPLICIT_SELECTED_GRANT");
    }

    public Bundle restore() {
        return result(plan.restoreAdded(true), "EXPLICIT_RESTORE_THIS_SESSION_ONLY");
    }

    private Bundle result(FixedPermissionPlan.Result result, String action) {
        Bundle out = common();
        out.putString("action", action);
        out.putString("permissionOutcome", result.outcome.name());
        out.putString("qaManage", result.manage.name());
        out.putString("qaCustom", result.custom.name());
        out.putString("restoreScope", "CONFIRMED_ADDED_IN_THIS_LIVE_SESSION_ONLY");
        return out;
    }
    private Bundle common() {
        Bundle out = new Bundle();
        out.putString("targetPackage", FixedPermissionPlan.PACKAGE);
        out.putInt("targetUid", ownerUid);
        out.putInt("targetUser", 0);
        out.putLong("generation", epoch);
        out.putBoolean("provesAvfUsable", false);
        out.putString("scope", "QA_PERMISSION_PREPARATION_ONLY");
        return out;
    }
}
