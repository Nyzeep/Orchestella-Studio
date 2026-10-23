package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome;
import java.util.Objects;

/**
 * QEMU lane adapter. Liveness and stop stay with RuntimeOwnedSession and its
 * RuntimeProcessSupervisor; this adapter only maps the result vocabulary.
 */
public final class QemuLaneRuntime implements LaneRuntime {
    private final RuntimeOwnedSession session;

    public QemuLaneRuntime(RuntimeOwnedSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    @Override
    public LaneId laneId() {
        return LaneId.QEMU;
    }

    @Override
    public boolean isAlive() {
        return session.isRuntimeAlive();
    }

    @Override
    public boolean isReady() {
        return isAlive();
    }

    @Override
    public LaneStopOutcome stop(long graceMs) {
        return switch (session.stopRuntime(graceMs)) {
            case CONFIRMED -> LaneStopOutcome.CONFIRMED;
            case ALREADY_STOPPED -> LaneStopOutcome.ALREADY_STOPPED;
            case UNCONFIRMED -> LaneStopOutcome.UNCONFIRMED;
        };
    }
}
