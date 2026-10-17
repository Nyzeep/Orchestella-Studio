package io.github.nyzeep.orchestella.researchauth;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/** Fixed QA stages. A later failure never discards an earlier stage's observations. */
public final class QaFdClient {
    private QaFdClient() {}
    private interface Step { void run() throws Exception; }
    private static void step(Bundle out, String name, Step action) {
        out.putString("lastStage", name);
        try { action.run(); out.putString(name + "Stage", "RETURNED"); }
        catch (Exception | LinkageError failure) {
            out.putString(name + "Stage", ProbeErrors.kind(failure));
            if (out.containsKey("fixtureLastStage")) out.putString(name + "FixtureStage", out.getString("fixtureLastStage"));
        }
    }
    public static Bundle run(Context context, IIdentityProbe probe, long epoch) {
        Bundle out = new Bundle();
        out.putString("experiment", "SYNTHETIC_ONLY_NO_VM");
        step(out, "appContextState", () -> copy(out, "appState_", QaContextState.inspect(context, epoch, "APP")));
        step(out, "serviceContextStateIpc", () -> copy(out, "serviceState_", probe.inspectContextState(epoch)));
        final QaSyntheticFiles[] fixture = new QaSyntheticFiles[1];
        step(out, "appNewFixtureDirectory", () -> fixture[0] = new QaSyntheticFiles(context, epoch, out));
        if (fixture[0] == null) { out.putString("remainingFdStages", "NOT_RUN_FIXTURE_DIRECTORY_FAILED"); return out; }
        QaSyntheticFiles files = fixture[0];
        step(out, "symlinkOpen", () -> out.putBoolean("appSymlinkOpenRejected", files.symlinkRejected()));
        step(out, "wrongRoMode", () -> {
            try (ParcelFileDescriptor a = files.create(true); ParcelFileDescriptor b = files.create(true)) {
                copy(out, "wrongRoMode_", probe.exerciseSynthetic(epoch, a, b));
            }
        });
        step(out, "wrongRwMode", () -> {
            try (ParcelFileDescriptor a = files.create(false); ParcelFileDescriptor b = files.create(false)) {
                copy(out, "wrongRwMode_", probe.exerciseSynthetic(epoch, a, b));
            }
        });
        step(out, "sameObject", () -> {
            try (ParcelFileDescriptor a = files.create(false)) {
                copy(out, "sameObject_", probe.exerciseSynthetic(epoch, a, a));
            }
        });
        step(out, "pipeType", () -> {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try (ParcelFileDescriptor a = pipe[0]; ParcelFileDescriptor b = pipe[1]) {
                copy(out, "pipeType_", probe.exerciseSynthetic(epoch, a, b));
            }
        });
        step(out, "staleFdIpc", () -> {
            try (ParcelFileDescriptor a = files.create(false); ParcelFileDescriptor b = files.create(true)) {
                boolean rejected = false;
                try { probe.exerciseSynthetic(epoch - 1, a, b); }
                catch (SecurityException expected) { rejected = true; }
                out.putBoolean("staleFdEpochRejected", rejected);
                out.putBoolean("callerCopiesRemainOpenAfterRejection", a.getFileDescriptor().valid() && b.getFileDescriptor().valid());
            }
        });
        step(out, "closedFdIpc", () -> {
            ParcelFileDescriptor closed = files.create(false);
            closed.close();
            try (ParcelFileDescriptor b = files.create(true)) {
                try {
                    Bundle result = probe.exerciseSynthetic(epoch, closed, b);
                    copy(out, "closedFd_", result);
                    out.putString("closedFdBoundary", "RECEIVER_RETURNED_INSPECT_RESULT");
                } catch (Exception expected) {
                    out.putString("closedFdBoundary", ProbeErrors.kind(expected));
                    out.putBoolean("closedFdRejectedBeforeOrAtReceiver", true);
                }
            }
        });
        step(out, "positiveFdIpc", () -> {
            try (ParcelFileDescriptor a = files.create(false); ParcelFileDescriptor b = files.create(true)) {
                copy(out, "positive_", probe.exerciseSynthetic(epoch, a, b));
                copy(out, "replayedPair_", probe.exerciseSynthetic(epoch, a, b));
                byte[] value = new byte[1];
                out.putBoolean("appObservedRwMutation", android.system.Os.pread(b.getFileDescriptor(), value, 0, 1, 31) == 1 && value[0] == 'X');
                out.putBoolean("callerCopiesRemainOpenAfterSuccess", a.getFileDescriptor().valid() && b.getFileDescriptor().valid());
            }
        });
        out.putString("fixtureLastStage", files.stage());
        out.putBoolean("provesAvfUsable", false);
        return out;
    }

    private static void copy(Bundle output, String prefix, Bundle value) {
        for (String key : value.keySet()) {
            Object item = value.get(key);
            if (item instanceof Boolean) output.putBoolean(prefix + key, (Boolean) item);
            else if (item instanceof Long) output.putLong(prefix + key, (Long) item);
            else if (item instanceof Integer) output.putInt(prefix + key, (Integer) item);
            else output.putString(prefix + key, String.valueOf(item));
        }
    }
}
