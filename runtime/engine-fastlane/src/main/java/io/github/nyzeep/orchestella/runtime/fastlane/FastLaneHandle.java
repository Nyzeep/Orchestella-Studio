package io.github.nyzeep.orchestella.runtime.fastlane;

import java.util.List;

/** Platform handle for one protected Microdroid payload. */
public interface FastLaneHandle {
    String instanceId();

    /** Platform status: 0 stopped, 1 running, 2 deleted, negative unobservable. */
    int status();

    FastLaneExitInfo waitForExit() throws InterruptedException, FastLaneException;

    /**
     * True after the payload reported readiness. {@code run()} only starts the
     * VM; a caller should wait for readiness or retry {@code connectVsock}
     * with a bounded backoff.
     */
    default boolean payloadReady() {
        return false;
    }

    /** Bounded, idempotent host-requested stop. */
    FastLaneStopResult stopRuntime(long graceMs);

    void close();

    List<String> notes();
}
