package io.github.nyzeep.orchestella.researchauth;

/** Public callback boundary for the isolated QA client. No Android dependency. */
public final class ProbeConnectionEvents {
    private ProbeConnectionEvents() {}
    public static void disconnected(long binding, Object sourceConnection, long activeEpoch,
            Object activeConnection, boolean visible, Runnable retireActive) {
        if (!visible || binding != activeEpoch || sourceConnection != activeConnection) return;
        retireActive.run();
    }
}
