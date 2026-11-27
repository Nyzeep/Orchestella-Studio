package io.github.nyzeep.orchestella.runtime.engineuml;

import java.util.Objects;

/**
 * Generation-scoped readiness tracker observing three distinct startup
 * milestones for one standalone UML session:
 *
 * <ol>
 *   <li>{@link Phase#PROCESS_STARTED} — host spawned {@code libuml_kernel.so};</li>
 *   <li>{@link Phase#BRIDGE_READY} — Bridge v1 handshake ({@code HELLO/READY}) and
 *       authentication succeeded for the active generation;</li>
 *   <li>{@link Phase#SHELL_READY} — the terminal stream observed a complete shell
 *       prompt <em>and</em> Bridge authentication succeeded for the active generation.</li>
 * </ol>
 *
 * <p>Timeouts, protocol rejections, cancellations, or stale-generation callbacks
 * can never promote readiness.
 */
public final class UmlReadinessTracker {

    public enum Phase { IDLE, PROCESS_STARTED, BRIDGE_READY, SHELL_READY, FAILED }

    public enum RejectionReason {
        NONE,
        TIMEOUT,
        UNSUPPORTED_VERSION,
        UNAUTHORIZED,
        MALFORMED_HANDSHAKE,
        LAUNCH_FAILED,
        STALE_GENERATION,
        CANCELLED
    }

    /** Immutable snapshot of the tracker's current generation state. */
    public record Snapshot(
            String instanceId,
            String generation,
            Phase phase,
            long pid,
            long processStartedNanos,
            long bridgeReadyNanos,
            long shellPromptObservedNanos,
            long shellReadyNanos,
            long systemdReadyNanos,
            RejectionReason rejectionReason) {

        public Snapshot(
                String instanceId,
                String generation,
                Phase phase,
                long pid,
                long processStartedNanos,
                long bridgeReadyNanos,
                long shellPromptObservedNanos,
                long shellReadyNanos,
                RejectionReason rejectionReason) {
            this(
                    instanceId,
                    generation,
                    phase,
                    pid,
                    processStartedNanos,
                    bridgeReadyNanos,
                    shellPromptObservedNanos,
                    shellReadyNanos,
                    -1L,
                    rejectionReason);
        }

        public boolean processStarted() {
            return processStartedNanos >= 0L;
        }

        public boolean bridgeReady() {
            return bridgeReadyNanos >= 0L && rejectionReason == RejectionReason.NONE;
        }

        public boolean shellPromptObserved() {
            return shellPromptObservedNanos >= 0L;
        }

        public boolean shellReady() {
            return shellReadyNanos >= 0L && rejectionReason == RejectionReason.NONE;
        }

        public boolean systemdReady() {
            return systemdReadyNanos >= 0L && rejectionReason == RejectionReason.NONE;
        }
    }

    private final Object lock = new Object();
    private final String instanceId;
    private String generation;
    private Phase phase = Phase.IDLE;
    private long pid = -1L;
    private long processStartedNanos = -1L;
    private long bridgeReadyNanos = -1L;
    private long shellPromptObservedNanos = -1L;
    private long shellReadyNanos = -1L;
    private long systemdReadyNanos = -1L;
    private RejectionReason rejectionReason = RejectionReason.NONE;

    public UmlReadinessTracker(String instanceId, String generation) {
        this.instanceId = requireNonBlank(instanceId, "instanceId");
        this.generation = requireNonBlank(generation, "generation");
    }

    public boolean markProcessStarted(String expectedGeneration, long pid, long monotonicNanos) {
        synchronized (lock) {
            if (!isActiveMutable(expectedGeneration)) {
                return false;
            }
            if (processStartedNanos < 0L) {
                this.pid = pid;
                this.processStartedNanos = Math.max(0L, monotonicNanos);
                if (phase == Phase.IDLE) {
                    phase = Phase.PROCESS_STARTED;
                }
            }
            return true;
        }
    }

    public boolean markBridgeReady(String expectedGeneration, long monotonicNanos) {
        synchronized (lock) {
            if (!isActiveMutable(expectedGeneration)) {
                return false;
            }
            long ts = Math.max(0L, monotonicNanos);
            if (bridgeReadyNanos < 0L) {
                bridgeReadyNanos = ts;
            }
            if (shellPromptObservedNanos >= 0L) {
                shellReadyNanos = Math.max(bridgeReadyNanos, shellPromptObservedNanos);
                phase = Phase.SHELL_READY;
            } else {
                phase = Phase.BRIDGE_READY;
            }
            return true;
        }
    }

    /**
     * Records observation of a complete shell prompt. Promotes {@link Phase#SHELL_READY}
     * and returns {@code true} only if {@link #markBridgeReady} already succeeded for
     * the same active generation; otherwise records the prompt timestamp and waits
     * for Bridge authentication.
     */
    public boolean markShellReady(String expectedGeneration, long monotonicNanos) {
        synchronized (lock) {
            if (!isActiveMutable(expectedGeneration)) {
                return false;
            }
            long ts = Math.max(0L, monotonicNanos);
            if (shellPromptObservedNanos < 0L) {
                shellPromptObservedNanos = ts;
            }
            if (bridgeReadyNanos < 0L) {
                return false;
            }
            if (shellReadyNanos < 0L) {
                shellReadyNanos = Math.max(bridgeReadyNanos, shellPromptObservedNanos);
            }
            phase = Phase.SHELL_READY;
            return true;
        }
    }

    /** Records observation of systemd {@code multi-user.target} readiness for the active generation. */
    public boolean markSystemdReady(String expectedGeneration, long monotonicNanos) {
        synchronized (lock) {
            if (!isActiveMutable(expectedGeneration)) {
                return false;
            }
            if (systemdReadyNanos < 0L) {
                systemdReadyNanos = Math.max(0L, monotonicNanos);
            }
            return true;
        }
    }

    public boolean markBridgeRejected(
            String expectedGeneration, RejectionReason reason, long monotonicNanos) {
        Objects.requireNonNull(reason, "reason");
        if (reason == RejectionReason.NONE) {
            throw new IllegalArgumentException("rejection reason must not be NONE");
        }
        synchronized (lock) {
            if (!Objects.equals(generation, expectedGeneration)) {
                return false;
            }
            if (rejectionReason == RejectionReason.NONE) {
                rejectionReason = reason;
                bridgeReadyNanos = -1L;
                shellReadyNanos = -1L;
                systemdReadyNanos = -1L;
                phase = Phase.FAILED;
            }
            return true;
        }
    }

    public boolean cancel(String expectedGeneration, long monotonicNanos) {
        return markBridgeRejected(expectedGeneration, RejectionReason.CANCELLED, monotonicNanos);
    }

    /**
     * Advances the active generation and resets milestone state so late callbacks
     * from any earlier generation are unconditionally ignored.
     */
    public void invalidateAndAdvanceGeneration(String nextGeneration) {
        String valid = requireNonBlank(nextGeneration, "nextGeneration");
        synchronized (lock) {
            this.generation = valid;
            this.phase = Phase.IDLE;
            this.pid = -1L;
            this.processStartedNanos = -1L;
            this.bridgeReadyNanos = -1L;
            this.shellPromptObservedNanos = -1L;
            this.shellReadyNanos = -1L;
            this.systemdReadyNanos = -1L;
            this.rejectionReason = RejectionReason.NONE;
        }
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            return new Snapshot(
                    instanceId,
                    generation,
                    phase,
                    pid,
                    processStartedNanos,
                    bridgeReadyNanos,
                    shellPromptObservedNanos,
                    shellReadyNanos,
                    systemdReadyNanos,
                    rejectionReason);
        }
    }

    private boolean isActiveMutable(String expectedGeneration) {
        return Objects.equals(generation, expectedGeneration)
                && rejectionReason == RejectionReason.NONE
                && phase != Phase.FAILED;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
