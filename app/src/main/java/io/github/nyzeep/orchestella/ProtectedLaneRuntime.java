package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneSession;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneStopResult;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome;
import java.util.Objects;

/**
 * Accelerator lane adapter. FastLaneSession remains the one owner stop for a
 * protected Fast Lane handle; no Microdroid handle enters
 * RuntimeProcessSupervisor.
 */
public final class ProtectedLaneRuntime implements LaneRuntime {
    private final FastLaneSession session;

    public ProtectedLaneRuntime(FastLaneSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public LaneId laneId() {
        return LaneId.ACCEL;
    }

    @Override
    public boolean isAlive() {
        return session.isAlive();
    }

    @Override
    public boolean isReady() {
        return session.payloadReady();
    }

    @Override
    public LaneStopOutcome stop(long graceMs) {
        FastLaneStopResult result = session.stopRuntime(graceMs);
        return switch (result) {
            case CONFIRMED -> LaneStopOutcome.CONFIRMED;
            case ALREADY_STOPPED -> LaneStopOutcome.ALREADY_STOPPED;
            case UNCONFIRMED -> LaneStopOutcome.UNCONFIRMED;
        };
    }
}
