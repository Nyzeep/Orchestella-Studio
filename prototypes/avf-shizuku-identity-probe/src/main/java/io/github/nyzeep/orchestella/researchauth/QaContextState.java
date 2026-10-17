package io.github.nyzeep.orchestella.researchauth;

import android.content.Context;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;

/** Exactly one new QA namespace below the real Context. No fake Context or VM manager. */
public final class QaContextState {
    private QaContextState() {}
    public static Bundle inspect(Context context, long epoch, String actor) {
        Bundle out = new Bundle();
        out.putString("actor", actor);
        out.putLong("generation", epoch);
        out.putString("managerInstance", "NOT_CREATED");
        out.putBoolean("provesAvfUsable", false);
        ProbeStageLedger effects = new ProbeStageLedger();
        FileDescriptor fd = null;
        try {
            effects.stage("DATA_DIR_METADATA");
            File data = context.getDataDir();
            if (epoch <= 0 || data == null || !ProbeSnapshot.PACKAGE.equals(context.getPackageName())) {
                throw new SecurityException("CONTEXT_REJECTED");
            }
            effects.stage("DATA_DIR_LSTAT");
            if (!OsConstants.S_ISDIR(Os.lstat(data.getAbsolutePath()).st_mode)) {
                throw new SecurityException("REAL_DATA_DIRECTORY_REQUIRED");
            }
            File directory = new File(data, "identity-qa-state-"
                    + ("APP".equals(actor) ? "app" : "service") + "-e" + epoch);
            effects.stage("NEW_QA_DIRECTORY_MKDIR");
            Os.mkdir(directory.getAbsolutePath(), 0700);
            effects.happened("newQaDirectoryCreated");
            File sentinel = new File(directory, "synthetic-state.bin");
            effects.stage("NEW_SENTINEL_OPEN_NOFOLLOW");
            fd = Os.open(sentinel.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_CREAT
                    | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            effects.happened("newSentinelCreated");
            effects.stage("SENTINEL_WRITE");
            int written = Os.write(fd, new byte[]{'Q', 'A'}, 0, 2);
            out.putInt("sentinelBytesWritten", written);
            if (written > 0) effects.happened("sentinelBytesWritten");
            out.putString("contextStateResult", written == 2 ? "NEW_QA_STATE_WRITABLE" : "SHORT_STATE_WRITE");
        } catch (Exception | LinkageError failure) {
            String kind = ProbeErrors.kind(failure);
            effects.failed(kind);
            out.putString("contextStateResult", kind);
        } finally {
            if (fd != null) {
                try { Os.close(fd); effects.happened("sentinelDescriptorClosed"); }
                catch (ErrnoException failure) { out.putString("closeFailure", ProbeErrors.kind(failure)); }
            }
            for (java.util.Map.Entry<String,String> item : effects.snapshot().entrySet()) {
                out.putString("effect_" + item.getKey(), item.getValue());
            }
        }
        return out;
    }
}
