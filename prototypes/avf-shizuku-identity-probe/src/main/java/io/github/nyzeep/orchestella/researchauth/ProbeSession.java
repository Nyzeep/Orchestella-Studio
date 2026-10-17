package io.github.nyzeep.orchestella.researchauth;

/** Research IPC gate. The caller UID always comes from Binder, never from request data. */
public final class ProbeSession {
    public static final int PROTOCOL = 1;
    private final int ownerUid;
    private long newestEpoch;
    private long activeEpoch;
    private boolean destroyed;

    public ProbeSession(int ownerUid) {
        if (ownerUid < 10000 || ownerUid >= 99000) {
            throw new SecurityException("QA_CURRENT_USER_APPLICATION_REQUIRED");
        }
        this.ownerUid = ownerUid;
    }

    public synchronized void begin(int callerUid, int protocol, long epoch) {
        owner(callerUid);
        if (destroyed) throw new SecurityException("DESTROYED");
        if (protocol != PROTOCOL) throw new SecurityException("PROTOCOL_REJECTED");
        if (epoch <= newestEpoch || epoch <= 0) throw new SecurityException("STALE_EPOCH");
        if (activeEpoch != 0) throw new SecurityException("SESSION_ALREADY_ACTIVE");
        newestEpoch = activeEpoch = epoch;
    }

    public synchronized void require(int callerUid, long epoch) {
        owner(callerUid);
        if (destroyed || activeEpoch == 0 || epoch != activeEpoch) {
            throw new SecurityException("INACTIVE_OR_STALE_EPOCH");
        }
    }

    public synchronized void end(int callerUid, long epoch) {
        require(callerUid, epoch);
        activeEpoch = 0;
    }

    public synchronized void retire() {
        activeEpoch = 0;
        destroyed = true;
    }

    public void owner(int callerUid) {
        if (callerUid != ownerUid) throw new SecurityException("CALLER_REJECTED");
    }
}
