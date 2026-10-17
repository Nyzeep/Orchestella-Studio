package io.github.nyzeep.orchestella.researchauth;

import android.content.Context;
import android.os.Bundle;
import java.lang.reflect.InvocationTargetException;

/** Fixed ctor/getCapabilities experiment only. Never creates, loads, runs, or deletes a VM. */
public final class QaManagerCapability {
    private QaManagerCapability() {}
    public static Bundle inspect(Context context, long epoch) {
        Bundle out = new Bundle();
        out.putLong("generation", epoch);
        out.putInt("actorUid", android.os.Process.myUid());
        out.putBoolean("provesAvfUsable", false);
        out.putString("capabilityStage", "LOAD_FIXED_MANAGER_CLASS");
        out.putString("capabilityResult", "UNKNOWN");
        out.putString("sideEffectBoundary", "CTOR_AND_GET_CAPABILITIES_ONLY_NO_VM_OPERATIONS");
        out.putString("spawnEvidence", "PIN_HAS_NO_SPAWN_IN_THESE_METHODS_ROM_PROCESS_CHECK_REQUIRED");
        try {
            Class<?> type = Class.forName("android.system.virtualmachine.VirtualMachineManager", false,
                    QaManagerCapability.class.getClassLoader());
            out.putString("capabilityStage", "MANAGER_CONTEXT_CONSTRUCTOR");
            Object manager = type.getConstructor(Context.class).newInstance(context);
            out.putString("managerInstance", "CONSTRUCTED_FOR_CAPABILITY_QUERY_ONLY");
            out.putString("capabilityStage", "GET_CAPABILITIES");
            int bits = ((Number) type.getMethod("getCapabilities").invoke(manager)).intValue();
            out.putInt("capabilityBits", bits);
            int protectedBit = type.getField("CAPABILITY_PROTECTED_VM").getInt(null);
            int nonProtectedBit = type.getField("CAPABILITY_NON_PROTECTED_VM").getInt(null);
            out.putBoolean("declaresProtected", (bits & protectedBit) != 0);
            out.putBoolean("declaresNonProtected", (bits & nonProtectedBit) != 0);
            out.putString("capabilityResult", "QUERY_RETURNED_DECLARATION_ONLY");
        } catch (InvocationTargetException failure) {
            out.putString("capabilityResult", ProbeErrors.kind(failure.getCause()));
        } catch (Exception | LinkageError failure) {
            out.putString("capabilityResult", ProbeErrors.kind(failure));
        }
        return out;
    }
}
