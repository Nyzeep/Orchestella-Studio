package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;

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
        NOT_FOUND
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
        try {
            instance = store.load(instanceId);
        } catch (Exception ignored) {
        }

        if (liveHolder != null && liveHolder.isAlive()) {
            return ReconcileResult.attached(instance, liveHolder, "Active session found in pool");
        }

        if (instance == null) {
            return ReconcileResult.notFound(instanceId, "Instance not found in store: " + instanceId);
        }

        if (instance.state() == InstanceLifecycle.State.RUNNING) {
            // Attempt auto re-attach probe
            if (sessionRecoverer != null) {
                try {
                    ExecutionSession recovered = sessionRecoverer.recoverSession(instance);
                    if (recovered != null) {
                        PersistentTerminalSessionPool.SessionHolder newHolder =
                                sessionPool.register(instanceId, recovered);
                        return ReconcileResult.attached(instance, newHolder, "Session recovered and attached");
                    }
                } catch (Exception ignored) {
                }
            }

            // Recovery failed / process dead -> record crash / abnormal termination
            InstanceLifecycle.CrashDiagnostic diagnostic = new InstanceLifecycle.CrashDiagnostic(
                    clock.millis(),
                    -1,
                    null,
                    "PTY session disconnected or process died unexpectedly");

            InstanceLifecycle.Instance crashedInstance = null;
            if (controller != null) {
                try {
                    crashedInstance = controller.recordCrash(instanceId, diagnostic);
                } catch (Exception e) {
                    crashedInstance = instance;
                }
            } else {
                crashedInstance = instance;
            }
            return ReconcileResult.crashed(crashedInstance, "Instance was marked RUNNING but session is unrecoverable");
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
}
