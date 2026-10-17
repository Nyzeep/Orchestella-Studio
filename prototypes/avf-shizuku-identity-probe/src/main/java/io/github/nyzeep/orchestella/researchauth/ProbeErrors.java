package io.github.nyzeep.orchestella.researchauth;

/** Fixed error taxonomy only. Never emit exception messages, paths or arbitrary stack traces. */
public final class ProbeErrors {
    private ProbeErrors() {}
    public static String kind(Throwable failure) {
        if (failure instanceof android.system.ErrnoException) {
            return "OS_ERROR_" + ((android.system.ErrnoException) failure).errno;
        }
        if (failure instanceof SecurityException) return "SECURITY_EXCEPTION";
        if (failure instanceof android.os.DeadObjectException) return "BINDER_DEAD";
        if (failure instanceof android.os.RemoteException) return "REMOTE_EXCEPTION";
        if (failure instanceof java.io.FileNotFoundException) return "FILE_NOT_FOUND";
        if (failure instanceof java.io.InterruptedIOException) return "IO_INTERRUPTED";
        if (failure instanceof java.io.IOException) return "IO_EXCEPTION";
        if (failure instanceof IllegalArgumentException) return "ILLEGAL_ARGUMENT";
        if (failure instanceof IllegalStateException) return "ILLEGAL_STATE";
        if (failure instanceof NoSuchMethodException) return "METHOD_UNAVAILABLE";
        if (failure instanceof NoSuchFieldException) return "FIELD_UNAVAILABLE";
        if (failure instanceof ClassNotFoundException) return "CLASS_UNAVAILABLE";
        if (failure instanceof ReflectiveOperationException) return "REFLECTION_FAILURE";
        if (failure instanceof LinkageError) return "LINKAGE_FAILURE";
        return "OPERATION_FAILURE";
    }
}
