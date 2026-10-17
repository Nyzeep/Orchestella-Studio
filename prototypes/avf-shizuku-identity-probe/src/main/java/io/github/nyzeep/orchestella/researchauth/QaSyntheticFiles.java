package io.github.nyzeep.orchestella.researchauth;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/** App-owned fixed synthetic fixtures. No public paths, user assets, or caller-controlled leaf names. */
public final class QaSyntheticFiles {
    private final File directory;
    private int number;
    private final int ownerUid;
    private final android.os.Bundle diagnostics;
    private String stage = "CONTEXT_IDENTITY";
    private final ProbeStageLedger effects = new ProbeStageLedger();
    private void flush() {
        for (java.util.Map.Entry<String,String> item : effects.snapshot().entrySet()) {
            diagnostics.putString("fixtureEffect_" + item.getKey(), item.getValue());
        }
    }
    private void happened(String value) { effects.happened(value); flush(); }
    private void mark(String value) {
        stage = value; diagnostics.putString("fixtureLastStage", value);
        effects.stage(value); flush();
    }
    public String stage() { return stage; }

    public QaSyntheticFiles(Context context, long epoch, android.os.Bundle diagnostics) throws ErrnoException {
        this.diagnostics = diagnostics;
        mark("CONTEXT_IDENTITY");
        if (!ProbeSnapshot.PACKAGE.equals(context.getPackageName()) || epoch <= 0) {
            throw new SecurityException("QA_CONTEXT_REQUIRED");
        }
        ownerUid = android.os.Process.myUid();
        mark("DATA_DIR_METADATA");
        File data = context.getDataDir();
        mark("DATA_DIR_LSTAT");
        if (!OsConstants.S_ISDIR(Os.lstat(data.getAbsolutePath()).st_mode)) {
            throw new SecurityException("REAL_DATA_DIRECTORY_REQUIRED");
        }
        directory = new File(data, "identity-qa-fd-e" + epoch);
        mark("NEW_DIRECTORY_MKDIR");
        Os.mkdir(directory.getAbsolutePath(), 0700); // exclusive new namespace, never reused
        happened("newQaDirectoryCreated");
        mark("NEW_DIRECTORY_CREATED");
    }

    public ParcelFileDescriptor create(boolean writable) throws ErrnoException, IOException {
        File file = new File(directory, "fixture-" + ++number + ".bin");
        mark("FIXTURE_OPEN_CREATE_NOFOLLOW");
        FileDescriptor creating = Os.open(file.getAbsolutePath(), OsConstants.O_RDWR
                | OsConstants.O_CREAT | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
        happened("newFixtureFileCreated");
        try {
            mark("FIXTURE_WRITE_SENTINEL");
            int written = Os.write(creating, SyntheticFdExercise.FIXTURE, 0, SyntheticFdExercise.FIXTURE.length);
            diagnostics.putInt("fixtureLastWriteBytes", written);
            if (written > 0) happened("fixtureBytesWritten");
            if (written != SyntheticFdGate.FIXTURE_BYTES) throw new IOException("SHORT_FIXTURE_WRITE");
        } finally { Os.close(creating); happened("creatingDescriptorClosed"); }
        mark("FIXTURE_REOPEN_ROLE_NOFOLLOW");
        FileDescriptor opened = Os.open(file.getAbsolutePath(), (writable ? OsConstants.O_RDWR : OsConstants.O_RDONLY)
                | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
        try {
            mark("FIXTURE_LSTAT_FSTAT_VALIDATE");
            StructStat before = Os.lstat(file.getAbsolutePath()), after = Os.fstat(opened);
            if (!OsConstants.S_ISREG(after.st_mode) || after.st_uid != ownerUid
                    || before.st_dev != after.st_dev || before.st_ino != after.st_ino
                    || after.st_size != SyntheticFdGate.FIXTURE_BYTES) {
                throw new SecurityException("OPENED_OBJECT_REJECTED");
            }
            mark("FIXTURE_DUP_PFD");
            ParcelFileDescriptor result = ParcelFileDescriptor.dup(opened);
            happened("callerDescriptorDuplicated");
            return result;
        } finally { Os.close(opened); happened("openedDescriptorClosed"); }
    }

    public boolean symlinkRejected() throws ErrnoException, IOException {
        try (ParcelFileDescriptor ignored = create(false)) { }
        File target = new File(directory, "fixture-" + number + ".bin");
        File link = new File(directory, "synthetic-link");
        mark("SYNTHETIC_SYMLINK_CREATE");
        Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        happened("syntheticSymlinkCreated");
        mark("SYNTHETIC_SYMLINK_OPEN_NOFOLLOW");
        FileDescriptor fd = null;
        try {
            fd = Os.open(link.getAbsolutePath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW, 0);
            return false;
        } catch (ErrnoException expected) {
            return expected.errno == OsConstants.ELOOP;
        } finally { if (fd != null) Os.close(fd); }
    }
}
