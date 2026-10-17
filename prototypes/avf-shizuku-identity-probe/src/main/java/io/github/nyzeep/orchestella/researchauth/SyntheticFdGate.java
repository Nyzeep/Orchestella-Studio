package io.github.nyzeep.orchestella.researchauth;

/** Validates metadata from opened descriptors, never a caller-provided pathname. */
public final class SyntheticFdGate {
    public static final int READ_ONLY = 0;
    public static final int READ_WRITE = 2;
    public static final int FIXTURE_BYTES = 32;
    private SyntheticFdGate() {}

    public static void require(int ownerUid, boolean regular, int fileUid, long size,
            int accessMode, boolean writableRole, boolean sameObject, boolean alreadyUsed) {
        if (!regular) throw new SecurityException("REGULAR_FILE_REQUIRED");
        if (fileUid != ownerUid) throw new SecurityException("FILE_OWNER_REJECTED");
        if (size != FIXTURE_BYTES) throw new SecurityException("FIXTURE_SIZE_REJECTED");
        if (accessMode != (writableRole ? READ_WRITE : READ_ONLY)) {
            throw new SecurityException("FD_MODE_REJECTED");
        }
        if (sameObject) throw new SecurityException("DISTINCT_OBJECTS_REQUIRED");
        if (alreadyUsed) throw new SecurityException("FD_REPLAY_REJECTED");
    }
}
