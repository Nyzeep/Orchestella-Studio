package io.github.nyzeep.orchestella.researchauth;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Bounded synchronous synthetic-only FD exercise; closes every receiver-owned Binder duplicate. */
public final class SyntheticFdExercise {
    public static final byte[] FIXTURE = "ORCHESTELLA_QA_SYNTHETIC_FD_0001".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private final int ownerUid;
    private final Set<String> usedObjects = new HashSet<>();
    public SyntheticFdExercise(int ownerUid) { this.ownerUid = ownerUid; }

    public synchronized Bundle run(ParcelFileDescriptor readOnly, ParcelFileDescriptor readWrite) {
        Bundle out = new Bundle();
        out.putBoolean("provesAvfUsable", false);
        out.putString("scope", "FIXED_32_BYTE_SYNTHETIC_PAIR");
        ProbeStageLedger effects = new ProbeStageLedger();
        try {
            effects.stage("RECEIVED_DESCRIPTOR_ARGUMENTS");
            if (readOnly == null || readWrite == null) throw new SecurityException("TWO_FDS_REQUIRED");
            FileDescriptor ro = readOnly.getFileDescriptor(), rw = readWrite.getFileDescriptor();
            effects.stage("RECEIVER_FSTAT");
            StructStat a = Os.fstat(ro), b = Os.fstat(rw);
            effects.stage("RECEIVER_FCNTL_AND_METADATA_GATE");
            String keyA = a.st_dev + ":" + a.st_ino, keyB = b.st_dev + ":" + b.st_ino;
            boolean same = keyA.equals(keyB);
            SyntheticFdGate.require(ownerUid, OsConstants.S_ISREG(a.st_mode), a.st_uid, a.st_size,
                    Os.fcntlInt(ro, OsConstants.F_GETFL, 0) & OsConstants.O_ACCMODE,
                    false, same, usedObjects.contains(keyA));
            SyntheticFdGate.require(ownerUid, OsConstants.S_ISREG(b.st_mode), b.st_uid, b.st_size,
                    Os.fcntlInt(rw, OsConstants.F_GETFL, 0) & OsConstants.O_ACCMODE,
                    true, same, usedObjects.contains(keyB));
            effects.stage("RECEIVER_PREAD_SYNTHETIC_SENTINELS");
            byte[] roBytes = new byte[SyntheticFdGate.FIXTURE_BYTES];
            byte[] rwBytes = new byte[SyntheticFdGate.FIXTURE_BYTES];
            if (Os.pread(ro, roBytes, 0, roBytes.length, 0) != roBytes.length
                    || !Arrays.equals(roBytes, FIXTURE)
                    || Os.pread(rw, rwBytes, 0, rwBytes.length, 0) != rwBytes.length
                    || !Arrays.equals(rwBytes, FIXTURE)) throw new SecurityException("SYNTHETIC_SENTINEL_REQUIRED");
            // Cache before mutation; at most one admitted pair per service lifetime.
            if (!usedObjects.isEmpty()) throw new SecurityException("ONE_PAIR_ONLY");
            usedObjects.add(keyA); usedObjects.add(keyB);
            effects.stage("RECEIVER_PWRITE_SYNTHETIC_LAST_BYTE");
            if (Os.pwrite(rw, new byte[]{'X'}, 0, 1, 31) != 1) throw new IOException("SHORT_WRITE");
            effects.happened("syntheticRwByteWritten");
            effects.stage("RECEIVER_PREAD_MUTATION");
            byte[] value = new byte[1];
            if (Os.pread(rw, value, 0, 1, 31) != 1 || value[0] != 'X') throw new IOException("READBACK_FAILED");
            out.putString("fdResult", "SYNTHETIC_READ_WRITE_OK");
            out.putBoolean("roRead", true);
            out.putBoolean("rwWriteReadback", true);
        } catch (SecurityException failure) {
            // Messages are fixed in this class/gate; never include OS exception messages or paths.
            String reason = failure.getMessage();
            Set<String> knownReasons = new HashSet<>(Arrays.asList("TWO_FDS_REQUIRED",
                    "REGULAR_FILE_REQUIRED", "FILE_OWNER_REJECTED", "FIXTURE_SIZE_REJECTED",
                    "FD_MODE_REJECTED", "DISTINCT_OBJECTS_REQUIRED", "FD_REPLAY_REJECTED",
                    "SYNTHETIC_SENTINEL_REQUIRED", "ONE_PAIR_ONLY"));
            out.putString("fdResult", knownReasons.contains(reason) ? reason : "FD_SECURITY_REJECTED");
        } catch (ErrnoException failure) {
            out.putString("fdResult", "OS_ERROR_" + failure.errno);
        } catch (IOException | RuntimeException failure) {
            out.putString("fdResult", "FD_IO_REJECTED");
        } finally {
            out.putString("receiverCloseOwner", "SERVICE_FINALLY");
            for (java.util.Map.Entry<String,String> item : effects.snapshot().entrySet()) {
                out.putString("effect_" + item.getKey(), item.getValue());
            }
        }
        return out;
    }

    public static boolean close(ParcelFileDescriptor fd) {
        if (fd == null) return true;
        try {
            FileDescriptor observed = fd.getFileDescriptor();
            fd.close();
            return !observed.valid();
        } catch (IOException | RuntimeException failure) { return false; }
    }
}
