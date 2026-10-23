package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * QEMU lane adapter. Liveness and stop stay with RuntimeOwnedSession and its
 * RuntimeProcessSupervisor; this adapter only maps the result vocabulary.
 *
 * <p>Readiness is a separate signal owned by the assembly root. A live QEMU
 * process is not necessarily ready: without a signal the adapter stays
 * not-ready and the coordinator keeps the lane in {@code STARTING}; it must
 * never infer readiness from liveness.
 */
public final class QemuLaneRuntime implements LaneRuntime {
    private final RuntimeOwnedSession session;
    private final BooleanSupplier readySignal;

    /** No readiness source: always not-ready until a signal is supplied. */
    public QemuLaneRuntime(RuntimeOwnedSession session) {
        this(session, () -> false);
    }

    /**
     * AX-08 production wiring: readiness comes from the guest signal observed by
     * {@link QemuReadinessProbe}. The adapter never substitutes liveness for it.
     */
    public QemuLaneRuntime(RuntimeOwnedSession session, QemuReadinessProbe probe) {
        this(session, Objects.requireNonNull(probe, "probe")::isReady);
    }

    public QemuLaneRuntime(RuntimeOwnedSession session, BooleanSupplier readySignal) {
        this.session = Objects.requireNonNull(session, "session");
        this.readySignal = Objects.requireNonNull(readySignal, "readySignal");
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
        return session.isRuntimeAlive() && readySignal.getAsBoolean();
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
