package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;

/**
 * Host-side launch lifecycle sequencing for one instance. The Full Linux
 * contract v0 only allows CREATED -> PROVISIONING -> STOPPED -> RUNNING ->
 * STOPPED, so a launch must drive the provisioning record before the RUNNING
 * transition, and an observed guest exit may only record RUNNING -> STOPPED.
 */
public final class InstanceLaunchSequence {
    private InstanceLaunchSequence() {}

    /**
     * Drives the normal pre-launch transitions: first boot records CREATED ->
     * PROVISIONING -> STOPPED (host-side provisioning of verified inputs), then
     * every boot takes the single RUNNING slot via STOPPED -> RUNNING.
     */
    public static void beforeLaunch(InstanceLifecycle.Controller controller,
            InstanceLifecycle.Instance instance) throws IOException {
        if (instance.state() == InstanceLifecycle.State.CREATED) {
            controller.beginProvisioning(instance.id());
            controller.completeProvisioning(instance.id());
        }
        if (instance.state() == InstanceLifecycle.State.CRASHED
                || instance.state() == InstanceLifecycle.State.INTERRUPTED) {
            controller.coldRestore(instance.id());
        } else {
            controller.start(instance.id());
        }
    }

    public enum ExitStatus { NORMAL, CRASHED, WAIT_FAILED, STALE }

    /** Result of the registered owner's wait, consumed by the existing foreground/UI adapter. */
    public static final class ExitResult {
        private final ExitStatus status;
        private final int exitCode;
        ExitResult(ExitStatus status, int exitCode) {
            this.status = status;
            this.exitCode = exitCode;
        }
        public ExitStatus status() { return status; }
        public int exitCode() { return exitCode; }
    }

    /** Public watcher seam used by MainActivity; terminal I/O remains in the single pool pump. */
    public static ExitResult awaitGuestExit(InstanceLifecycle.Controller controller, String instanceId,
            ExecutionSession session, Runnable onConfirmedExit) throws IOException {
        final String generation = ExecutionSessionRegistry.generation(instanceId, session);
        if (generation == null) return new ExitResult(ExitStatus.STALE, -1);
        final int exitCode;
        try {
            exitCode = session.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExitResult(ExitStatus.WAIT_FAILED, -1);
        } catch (Exception failure) {
            // A failed observer has no evidence that the VM stopped. Keep its owner,
            // foreground binding and metadata, and report only the typed failure.
            return new ExitResult(ExitStatus.WAIT_FAILED, -1);
        }
        final boolean[] normal = {exitCode == 0};
        boolean completed = ExecutionSessionRegistry.completeExit(instanceId, session, () -> {
            normal[0] |= ExecutionSessionRegistry.stopIntent(instanceId, session)
                    == PersistentTerminalSessionPool.StopIntent.FORCE;
            if (normal[0]) afterGuestExit(controller, instanceId);
            else controller.recordCrash(instanceId, exitCode, null, "Virtual machine exited unexpectedly");
            InstanceLaunchGuard.end(instanceId, generation);
        });
        // Notification delivery may fail on Android. The confirmed owner has already
        // been retired; its generation still guards a delayed STOP intent.
        if (completed) onConfirmedExit.run();
        return new ExitResult(!completed ? ExitStatus.STALE
                : normal[0] ? ExitStatus.NORMAL : ExitStatus.CRASHED, exitCode);
    }

    /** Only an unregistered failed launch may release its own running slot. */
    public static boolean afterLaunchFailure(InstanceLifecycle.Controller controller, String instanceId,
            String generation, boolean enteredRunning, Runnable stopForeground) throws IOException {
        synchronized (PersistentTerminalSessionPool.getInstance()) {
            if (!InstanceLaunchGuard.isLatest(instanceId, generation)
                    || ExecutionSessionRegistry.get(instanceId) != null) return false;
            if (enteredRunning) controller.recordCrash(instanceId, -1, null, "Launch failed before VM owner registration");
            InstanceLaunchGuard.end(instanceId, generation);
            stopForeground.run();
            return true;
        }
    }

    /** Records an observed normal guest-initiated stop: RUNNING -> STOPPED. */
    public static void afterGuestExit(InstanceLifecycle.Controller controller, String instanceId)
            throws IOException {
        controller.recordNormalGuestStop(instanceId);
    }
}
