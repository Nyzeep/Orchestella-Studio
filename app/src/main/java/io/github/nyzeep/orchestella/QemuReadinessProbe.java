package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * AX-08: one guest-readiness observation for the QEMU lane.
 *
 * <p>The only evidence that a QEMU guest is ready is the Bridge control
 * channel having authenticated: that is the same signal the UI already waits
 * for before it opens a session ("控制通道尚未认证"). A live QEMU process is a
 * different fact, so this probe never reads liveness and never falls back to
 * it. When no readiness source exists at all the answer is
 * {@link Readiness#UNKNOWN}, which the lane adapter reports as not-ready and
 * the coordinator keeps the lane in {@code STARTING}.
 */
public final class QemuReadinessProbe {
    /** Three-valued readiness. Unknown is not ready and is not a failure. */
    public enum Readiness {
        READY,
        NOT_READY,
        UNKNOWN
    }

    /** The single observation seam; implementations must not throw. */
    @FunctionalInterface
    public interface Signal {
        Readiness read();
    }

    private static final QemuReadinessProbe ABSENT = new QemuReadinessProbe(() -> Readiness.UNKNOWN);

    private final Signal signal;

    public QemuReadinessProbe(Signal signal) {
        this.signal = Objects.requireNonNull(signal, "signal");
    }

    /** No source is wired: every read is {@link Readiness#UNKNOWN}. */
    public static QemuReadinessProbe absent() {
        return ABSENT;
    }

    /**
     * Observes the session's control channel. A session that does not exist yet
     * or does not expose a bridge client is {@link Readiness#UNKNOWN}, never a
     * silent "ready".
     */
    public static QemuReadinessProbe ofSession(Supplier<? extends ExecutionSession> session) {
        Objects.requireNonNull(session, "session");
        return new QemuReadinessProbe(() -> {
            ExecutionSession current;
            try {
                current = session.get();
            } catch (RuntimeException failure) {
                return Readiness.UNKNOWN;
            }
            if (current == null) {
                return Readiness.UNKNOWN;
            }
            return read(current.bridgeClient());
        });
    }

    /**
     * Observes a bridge client that may arrive later. A null client means the
     * launch chain has not produced one yet: {@link Readiness#UNKNOWN}.
     */
    public static QemuReadinessProbe ofBridgeClient(Supplier<BridgeClient> client) {
        Objects.requireNonNull(client, "client");
        return new QemuReadinessProbe(() -> {
            BridgeClient current;
            try {
                current = client.get();
            } catch (RuntimeException failure) {
                return Readiness.UNKNOWN;
            }
            return read(current);
        });
    }

    public Readiness readiness() {
        try {
            Readiness observed = signal.read();
            return observed == null ? Readiness.UNKNOWN : observed;
        } catch (RuntimeException failure) {
            // A probe that cannot answer is unknown, not ready and not lost.
            return Readiness.UNKNOWN;
        }
    }

    /** True only for {@link Readiness#READY}; the lane adapter consumes this. */
    public boolean isReady() {
        return readiness() == Readiness.READY;
    }

    private static Readiness read(BridgeClient client) {
        if (client == null) {
            return Readiness.UNKNOWN;
        }
        try {
            return client.isReady() ? Readiness.READY : Readiness.NOT_READY;
        } catch (RuntimeException failure) {
            return Readiness.UNKNOWN;
        }
    }
}
