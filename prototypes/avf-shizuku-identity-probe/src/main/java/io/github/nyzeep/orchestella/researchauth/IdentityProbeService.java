package io.github.nyzeep.orchestella.researchauth;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

/** Isolated QA service. No path/command interface and no VM owner. */
public final class IdentityProbeService extends IIdentityProbe.Stub {
    private final Context context;
    private final ProbeSession session;
    private final int ownerUid;
    private final SyntheticFdExercise synthetic;
    private QaPermissionExperiment permissions;

    public IdentityProbeService(Context context) {
        if (context == null || !ProbeSnapshot.PACKAGE.equals(context.getPackageName())) {
            throw new SecurityException("QA_PACKAGE_REQUIRED");
        }
        if (Process.myUid() != 2000) throw new SecurityException("ADB_SHELL_ONLY");
        ownerUid = context.getApplicationInfo().uid;
        session = new ProbeSession(ownerUid);
        synthetic = new SyntheticFdExercise(ownerUid);
        this.context = context;
    }

    @Override public void begin(int protocol, long generation) {
        session.begin(Binder.getCallingUid(), protocol, generation);
    }
    @Override public Bundle inspect(long generation) {
        session.require(Binder.getCallingUid(), generation);
        long identity = Binder.clearCallingIdentity();
        try { return ProbeSnapshot.inspect(context, generation, "USER_SERVICE"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public void end(long generation) { session.end(Binder.getCallingUid(), generation); }

    @Override public Bundle inspectContextState(long generation) {
        session.require(Binder.getCallingUid(), generation);
        long identity = Binder.clearCallingIdentity();
        try { return QaContextState.inspect(context, generation, "USER_SERVICE"); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public Bundle exerciseSynthetic(long generation,
            android.os.ParcelFileDescriptor ro, android.os.ParcelFileDescriptor rw) {
        Bundle result = null;
        try {
            session.require(Binder.getCallingUid(), generation);
            long identity = Binder.clearCallingIdentity();
            try { result = synthetic.run(ro, rw); return result; }
            finally { Binder.restoreCallingIdentity(identity); }
        } finally {
            boolean a = SyntheticFdExercise.close(ro), b = SyntheticFdExercise.close(rw);
            if (result != null) result.putBoolean("receiverCopiesClosed", a && b);
        }
    }

    private QaPermissionExperiment permissionExperiment(long generation) throws ReflectiveOperationException {
        if (permissions == null) permissions = new QaPermissionExperiment(context, ownerUid, generation);
        if (permissions.epoch() != generation) throw new SecurityException("PERMISSION_LEDGER_EPOCH_MISMATCH");
        return permissions;
    }
    private Bundle permissionAction(long generation, int action, boolean manage, boolean custom) {
        session.require(Binder.getCallingUid(), generation);
        long identity = Binder.clearCallingIdentity();
        try {
            QaPermissionExperiment experiment = permissionExperiment(generation);
            if (action == 1) return experiment.grantSelected(manage, custom);
            if (action == 2) return experiment.restore();
            return experiment.snapshot();
        } catch (Exception | LinkageError failure) {
            Bundle out = new Bundle();
            out.putString("permissionOutcome", "REJECTED_" + ProbeErrors.kind(failure));
            out.putBoolean("provesAvfUsable", false);
            return out;
        } finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public synchronized Bundle permissionSnapshot(long generation) {
        return permissionAction(generation, 0, false, false);
    }
    @Override public synchronized Bundle grantSelected(long generation, boolean manage, boolean custom) {
        return permissionAction(generation, 1, manage, custom);
    }
    @Override public synchronized Bundle restoreAdded(long generation) {
        return permissionAction(generation, 2, false, false);
    }
    @Override public Bundle managerCapability(long generation) {
        session.require(Binder.getCallingUid(), generation);
        long identity = Binder.clearCallingIdentity();
        try { return QaManagerCapability.inspect(context, generation); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller != ownerUid && caller != 2000) throw new SecurityException("CALLER_REJECTED");
        session.retire();
        System.exit(0);
    }
}
