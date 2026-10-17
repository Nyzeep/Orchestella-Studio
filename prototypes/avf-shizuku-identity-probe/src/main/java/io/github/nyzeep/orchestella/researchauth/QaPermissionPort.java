package io.github.nyzeep.orchestella.researchauth;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Process;
import android.os.UserHandle;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

/** Shell-process adapter. Only the QA constant package, user0 and two enum permissions are accepted. */
public final class QaPermissionPort implements FixedPermissionPlan.Port {
    private final Context context;
    private final int ownerUid;
    private final long epoch;
    private final UserHandle user;
    private final java.lang.reflect.Method grant;
    private final java.lang.reflect.Method revoke;
    private final java.lang.reflect.Method flags;
    private final int fixedMask;

    public QaPermissionPort(Context context, int ownerUid, long epoch) throws ReflectiveOperationException {
        if (!FixedPermissionPlan.PACKAGE.equals(context.getPackageName()) || Process.myUid() != 2000
                || ownerUid < 10000 || ownerUid >= 99000 || epoch <= 0) {
            throw new SecurityException("QA_SHELL_CURRENT_USER_ONLY");
        }
        this.context = context; this.ownerUid = ownerUid; this.epoch = epoch;
        user = Process.myUserHandle(); // shell UID2000 is current experiment user0 only
        grant = PackageManager.class.getMethod("grantRuntimePermission", String.class, String.class, UserHandle.class);
        revoke = PackageManager.class.getMethod("revokeRuntimePermission", String.class, String.class, UserHandle.class);
        flags = PackageManager.class.getMethod("getPermissionFlags", String.class, String.class, UserHandle.class);
        fixedMask = PackageManager.class.getField("FLAG_PERMISSION_SYSTEM_FIXED").getInt(null)
                | PackageManager.class.getField("FLAG_PERMISSION_POLICY_FIXED").getInt(null);
    }

    @Override public FixedPermissionPlan.Facts facts() {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageInfo(FixedPermissionPlan.PACKAGE, PackageManager.GET_PERMISSIONS);
            if (info.applicationInfo == null || info.applicationInfo.uid != ownerUid
                    || !FixedPermissionPlan.PACKAGE.equals(info.packageName)) throw new SecurityException("TARGET_IDENTITY_CHANGED");
            boolean manage = requested(info, FixedPermissionPlan.Permission.MANAGE);
            boolean custom = requested(info, FixedPermissionPlan.Permission.CUSTOM);
            boolean grantAllowed = context.checkSelfPermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED;
            boolean revokeAllowed = context.checkSelfPermission("android.permission.REVOKE_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED;
            return new FixedPermissionPlan.Facts(true, true, Process.myUid(), 0, ownerUid,
                    manage, custom, grantAllowed, revokeAllowed, epoch);
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            return new FixedPermissionPlan.Facts(false, false, Process.myUid(), 0, ownerUid,
                    false, false, false, false, epoch);
        }
    }

    private boolean requested(PackageInfo info, FixedPermissionPlan.Permission permission) throws PackageManager.NameNotFoundException {
        if (info.requestedPermissions == null || !Arrays.asList(info.requestedPermissions).contains(permission.androidName)) return false;
        PermissionInfo declaration = context.getPackageManager().getPermissionInfo(permission.androidName, 0);
        return (declaration.protectionLevel & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0;
    }

    @Override public FixedPermissionPlan.State read(FixedPermissionPlan.Permission permission) {
        try {
            int result = context.getPackageManager().checkPermission(permission.androidName, FixedPermissionPlan.PACKAGE);
            return result == PackageManager.PERMISSION_GRANTED ? FixedPermissionPlan.State.GRANTED : FixedPermissionPlan.State.DENIED;
        } catch (RuntimeException failure) { return FixedPermissionPlan.State.UNKNOWN; }
    }

    public int permissionFlags(FixedPermissionPlan.Permission permission) {
        return (Integer) invoke(flags, permission.androidName, FixedPermissionPlan.PACKAGE, user);
    }

    @Override public void grant(FixedPermissionPlan.Permission permission) {
        if ((permissionFlags(permission) & fixedMask) != 0) throw new SecurityException("FIXED_FLAG_REJECTED");
        invoke(grant, FixedPermissionPlan.PACKAGE, permission.androidName, user);
    }
    @Override public void revoke(FixedPermissionPlan.Permission permission) {
        if ((permissionFlags(permission) & fixedMask) != 0) throw new SecurityException("FIXED_FLAG_REJECTED");
        invoke(revoke, FixedPermissionPlan.PACKAGE, permission.androidName, user);
    }
    private Object invoke(java.lang.reflect.Method method, Object... arguments) {
        try { return method.invoke(context.getPackageManager(), arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof SecurityException) throw new SecurityException("PLATFORM_PERMISSION_DENIED");
            throw new IllegalStateException("PLATFORM_PERMISSION_CALL_FAILED");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("PLATFORM_PERMISSION_API_UNAVAILABLE");
        }
    }
}
