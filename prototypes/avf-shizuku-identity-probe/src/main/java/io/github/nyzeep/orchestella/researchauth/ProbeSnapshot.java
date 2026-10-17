package io.github.nyzeep.orchestella.researchauth;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import java.io.File;

/** Fixed read-only observations. Never obtains a manager or initializes AVF classes. */
public final class ProbeSnapshot {
    public static final String PACKAGE = "io.github.nyzeep.orchestella.researchauth";
    public static final String MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE";
    public static final String CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE";
    private ProbeSnapshot() {}

    public static Bundle inspect(Context context, long epoch, String actor) {
        Bundle out = new Bundle();
        out.putInt("schemaVersion", ProbeSession.PROTOCOL);
        out.putLong("generation", epoch);
        out.putString("actor", actor);
        out.putInt("uid", Process.myUid());
        out.putInt("pid", Process.myPid());
        out.putInt("androidApi", Build.VERSION.SDK_INT);
        out.putString("managePermission", permission(context, MANAGE));
        out.putString("customPermission", permission(context, CUSTOM));
        out.putString("selinuxContext", selinuxContext());
        out.putString("customBuilderConstructor", shape(true));
        out.putString("managerApi", shape(false));
        out.putString("capabilityBits", "NOT_QUERIED");
        out.putString("managerInstance", "NOT_CREATED");
        out.putBoolean("provesAvfUsable", false);
        out.putString("initialIdentityFilesystemObservation", "NO_FILES_OPENED_OR_CREATED_BY_IDENTITY_SNAPSHOT");
        out.putString("filesystemAndVmState", "IDENTITY_SNAPSHOT_ONLY_SUBSEQUENT_STEPS_REPORTED_SEPARATELY");
        try {
            out.putBoolean("contextPackageMatches", PACKAGE.equals(context.getPackageName()));
            out.putInt("contextApplicationUid", context.getApplicationInfo().uid);
            out.putBoolean("applicationContextAvailable", context.getApplicationContext() != null);
            // getDataDir returns metadata only; unlike getFilesDir, it does not create directories.
            File dir = context.getDataDir();
            out.putString("contextDataDir", dir == null ? "UNAVAILABLE" : "AVAILABLE_NOT_OPENED");
        } catch (RuntimeException | LinkageError failure) {
            out.putString("contextDataDir", "CONTEXT_UNUSABLE");
        }
        return out;
    }

    private static String permission(Context context, String name) {
        try {
            return context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
                    ? "GRANTED_TO_ACTOR_UID" : "DENIED_TO_ACTOR_UID";
        } catch (RuntimeException | LinkageError failure) { return "UNKNOWN"; }
    }

    private static String selinuxContext() {
        try {
            Object value = Class.forName("android.os.SELinux", false,
                    ProbeSnapshot.class.getClassLoader()).getMethod("getContext").invoke(null);
            if (value instanceof String && ((String) value).length() <= 160
                    && ((String) value).matches("[A-Za-z0-9_:,.-]+")) return (String) value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { }
        return "UNAVAILABLE";
    }

    private static String shape(boolean constructor) {
        try {
            Class<?> type = Class.forName("android.system.virtualmachine."
                    + (constructor ? "VirtualMachineCustomImageConfig$Builder" : "VirtualMachineManager"),
                    false, ProbeSnapshot.class.getClassLoader());
            if (constructor) type.getConstructor(); else type.getMethod("getCapabilities");
            return "MEMBER_VISIBLE_NOT_INVOKED";
        } catch (ClassNotFoundException failure) { return "CLASS_UNAVAILABLE";
        } catch (NoSuchMethodException | SecurityException failure) { return "MEMBER_UNAVAILABLE_OR_HIDDEN";
        } catch (LinkageError failure) { return "LINKAGE_UNAVAILABLE"; }
    }
}
