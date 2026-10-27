package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reconciler coordinating instance metadata state and live terminal sessions.
 *
 * <p>Implements Ticket 07 contract:
 * <ul>
 *   <li>If an active session exists in {@link PersistentTerminalSessionPool}, instantly binds.</li>
 *   <li>If no session exists, inspects persistent state in {@link InstanceLifecycle.Store}.</li>
 *   <li>If state is RUNNING, attempts auto-reattach via {@link SessionRecoverer}. If that fails,
 *       transitions instance to CRASHED with diagnostic information.</li>
 *   <li>If state is STOPPED or CREATED, indicates OFFLINE state so UI can offer one-click launch.</li>
 * </ul>
 */
public final class InstanceSessionReconciler {

    public enum Status {
        ATTACHED,
        OFFLINE,
        CRASHED,
        NOT_FOUND,
        /** Metadata exists but could not be read: this is an I/O/decoding failure, not absence. */
        STORE_ERROR
    }

    public static final class ReconcileResult {
        private final Status status;
        private final InstanceLifecycle.Instance instance;
        private final PersistentTerminalSessionPool.SessionHolder sessionHolder;
        private final String message;

        public static ReconcileResult attached(
                InstanceLifecycle.Instance instance,
                PersistentTerminalSessionPool.SessionHolder holder,
                String message) {
            return new ReconcileResult(Status.ATTACHED, instance, holder, message);
        }

        public static ReconcileResult offline(InstanceLifecycle.Instance instance, String message) {
            return new ReconcileResult(Status.OFFLINE, instance, null, message);
        }

        public static ReconcileResult crashed(InstanceLifecycle.Instance instance, String message) {
            return new ReconcileResult(Status.CRASHED, instance, null, message);
        }

        public static ReconcileResult notFound(String instanceId, String message) {
            return new ReconcileResult(Status.NOT_FOUND, null, null, message);
        }

        public static ReconcileResult storeError(String instanceId, String message) {
            return new ReconcileResult(Status.STORE_ERROR, null, null, message);
        }

        private ReconcileResult(
                Status status,
                InstanceLifecycle.Instance instance,
                PersistentTerminalSessionPool.SessionHolder sessionHolder,
                String message) {
            this.status = status;
            this.instance = instance;
            this.sessionHolder = sessionHolder;
            this.message = message;
        }

        public Status status() {
            return status;
        }

        public InstanceLifecycle.Instance instance() {
            return instance;
        }

        public PersistentTerminalSessionPool.SessionHolder sessionHolder() {
            return sessionHolder;
        }

        public String message() {
            return message;
        }
    }

    @FunctionalInterface
    public interface SessionRecoverer {
        ExecutionSession recoverSession(InstanceLifecycle.Instance instance) throws Exception;
    }

    private static final Logger LOGGER = Logger.getLogger(InstanceSessionReconciler.class.getName());

    private final PersistentTerminalSessionPool sessionPool;
    private final InstanceLifecycle.Store store;
    private final InstanceLifecycle.Controller controller;
    private final SessionRecoverer sessionRecoverer;
    private final Clock clock;

    public InstanceSessionReconciler(
            PersistentTerminalSessionPool sessionPool,
            InstanceLifecycle.Store store,
            InstanceLifecycle.Controller controller,
            SessionRecoverer sessionRecoverer) {
        this(sessionPool, store, controller, sessionRecoverer, Clock.systemUTC());
    }

    public InstanceSessionReconciler(
            PersistentTerminalSessionPool sessionPool,
            InstanceLifecycle.Store store,
            InstanceLifecycle.Controller controller,
            SessionRecoverer sessionRecoverer,
            Clock clock) {
        this.sessionPool = Objects.requireNonNull(sessionPool, "sessionPool");
        this.store = Objects.requireNonNull(store, "store");
        this.controller = controller;
        this.sessionRecoverer = sessionRecoverer;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReconcileResult ensureSessionAliveOrRecover(String instanceId) throws IOException {
        if (instanceId == null || instanceId.isEmpty()) {
            return ReconcileResult.notFound(instanceId, "Instance ID is null or empty");
        }

        PersistentTerminalSessionPool.SessionHolder liveHolder = sessionPool.getHolder(instanceId);
        InstanceLifecycle.Instance instance = null;
        Exception storeFailure = null;
        try {
            instance = store.load(instanceId);
        } catch (Exception failure) {
            // Never fold a read failure into "instance does not exist": a torn or
            // unreadable metadata file is a store fault the UI must not resolve by
            // offering to recreate the instance.
            storeFailure = failure;
        }

        if (liveHolder != null && liveHolder.hasOwner()) {
            // Terminal EOF is a transport event. Only the owner's exit supervisor may
            // retire this session or persist a VM exit; never replace it with a socket probe.
            return ReconcileResult.attached(instance, liveHolder,
                    liveHolder.isAlive() ? "Active session found in pool" : "Terminal ended; VM exit is not confirmed");
        }

        if (storeFailure != null) {
            if (isMissingMetadata(storeFailure)) {
                return ReconcileResult.notFound(instanceId, "Instance not found in store: " + instanceId);
            }
            LOGGER.log(Level.WARNING, "Instance metadata read failed for " + instanceId, storeFailure);
            return ReconcileResult.storeError(instanceId,
                    "Instance store read failed for " + instanceId + ": " + describe(storeFailure));
        }

        if (instance == null) {
            return ReconcileResult.notFound(instanceId, "Instance not found in store: " + instanceId);
        }

        if (instance.state() == InstanceLifecycle.State.RUNNING) {
            // A launch that is still in flight has not registered its session yet; never
            // judge it crashed while the host is mid-launch (observed as spurious CRASHED
            // when the terminal opened before QEMU's sockets were ready).
            if (InstanceLaunchGuard.isInFlight(instanceId)) {
                return ReconcileResult.offline(instance, "Instance launch in progress");
            }
            // ADR-0011: this process has no supervised VM owner. A reachable socket
            // cannot reconstruct its wait/stop identity, so do not create a transport
            // stand-in with a synthetic successful wait. Preserve the instance for cold restore.
            InstanceLifecycle.Instance interrupted = controller != null
                    ? controller.reconcileAfterHostDeath(instanceId) : instance;
            return ReconcileResult.crashed(interrupted, "VM owner is absent; cold restore is required");
        }

        if (instance.state() == InstanceLifecycle.State.STOPPED
                || instance.state() == InstanceLifecycle.State.CREATED) {
            return ReconcileResult.offline(instance, "Instance is offline (" + instance.state() + ")");
        }

        if (instance.state() == InstanceLifecycle.State.CRASHED
                || instance.state() == InstanceLifecycle.State.INTERRUPTED) {
            return ReconcileResult.crashed(instance, "Instance in state " + instance.state());
        }

        return ReconcileResult.offline(instance, "Instance state: " + instance.state());
    }

    /**
     * {@link InstanceLifecycle.FileStore#load(String)} reports absence as an
     * {@code IOException("instance metadata not found")}; every other read failure means the
     * metadata is present but unusable. An id that cannot name any instance is also absence.
     */
    private static boolean isMissingMetadata(Exception failure) {
        if (failure instanceof NoSuchFileException) {
            return true;
        }
        String detail = failure.getMessage();
        return detail != null
                && (detail.startsWith("instance metadata not found")
                        || detail.equals("invalid instance id"));
    }

    private static String describe(Exception failure) {
        StringBuilder text = new StringBuilder(failure.getClass().getSimpleName());
        if (failure.getMessage() != null) {
            text.append(": ").append(failure.getMessage());
        }
        Throwable cause = failure.getCause();
        if (cause != null && cause != failure) {
            text.append(" (cause: ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                text.append(": ").append(cause.getMessage());
            }
            text.append(')');
        }
        return text.toString();
    }
}
