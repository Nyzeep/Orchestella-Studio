package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deep module governing external manual power actions and virtualization state transitions.
 *
 * <p>Supports external graceful shutdown (ACPI / Agent poweroff) with timeout guard defense,
 * hard process kill, vCPU suspension (freeze), and resume (unfreeze) across pure JVM abstractions.
 */
public final class InstancePowerController {

    /**
     * Low-level virtualization and process management seam.
     * Strategy-specific launchers implement this interface to connect to QEMU / AVF monitors.
     */
    public interface PowerOperationSeam {
        /**
         * Sends a graceful shutdown signal (ACPI power button event or Guest Agent poweroff).
         *
         * @param instanceId target instance ID
         * @return true if the shutdown signal was accepted and dispatched
         * @throws IOException on I/O failure
         */
        boolean sendGracefulShutdown(String instanceId) throws IOException;

        /**
         * Forcefully terminates the underlying VM process and frees host memory and sockets.
         *
         * @param instanceId target instance ID
         * @return true if process termination was executed
         * @throws IOException on I/O failure
         */
        boolean killProcess(String instanceId) throws IOException;

        /**
         * Freezes vCPU scheduling (e.g. QMP {@code stop}).
         *
         * @param instanceId target instance ID
         * @return true if execution was frozen
         * @throws IOException on I/O failure
         */
        boolean freezeCpu(String instanceId) throws IOException;

        /**
         * Resumes vCPU scheduling (e.g. QMP {@code cont}).
         *
         * @param instanceId target instance ID
         * @return true if execution was resumed
         * @throws IOException on I/O failure
         */
        boolean unfreezeCpu(String instanceId) throws IOException;

        /**
         * Queries whether the underlying VM process is currently running and alive.
         *
         * @param instanceId target instance ID
         * @return true if process is alive, false otherwise
         */
        boolean isVmAlive(String instanceId);
    }

    private final InstanceLifecycle.Controller controller;
    private final InstanceLifecycle.Store store;
    private final PowerOperationSeam seam;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    public InstancePowerController(
            InstanceLifecycle.Controller controller,
            InstanceLifecycle.Store store,
            PowerOperationSeam seam) {
        this(controller, store, seam, null);
    }

    public InstancePowerController(
            InstanceLifecycle.Controller controller,
            InstanceLifecycle.Store store,
            PowerOperationSeam seam,
            ScheduledExecutorService scheduler) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.store = Objects.requireNonNull(store, "store");
        this.seam = Objects.requireNonNull(seam, "seam");
        if (scheduler != null) {
            this.scheduler = scheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "orchestella-power-controller");
                thread.setDaemon(true);
                return thread;
            });
            this.ownsScheduler = true;
        }
    }

    /**
     * Dispatches a graceful shutdown signal (ACPI / Guest Agent) to the instance and waits asynchronously
     * for the guest process to exit within {@code timeoutMs}.
     *
     * <p>If the guest exits within the timeout window, the instance state transitions cleanly to {@code STOPPED}
     * and the future completes with {@code true}.
     * If {@code timeoutMs} expires before the guest exits, the future resolves to {@code false} without
     * locking up or prematurely altering the state machine, allowing the host or user to fall back to {@link #powerOffForce}.
     *
     * @param instanceId ID of the instance to gracefully power off
     * @param timeoutMs maximum time in milliseconds to wait for graceful exit
     * @return a future completing with true if shut down gracefully, false if timed out or rejected
     */
    public CompletableFuture<Boolean> shutdownGraceful(String instanceId, long timeoutMs) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            InstanceLifecycle.Instance instance = store.load(instanceId);
            if (instance.state() == InstanceLifecycle.State.STOPPED) {
                future.complete(true);
                return future;
            }
            if (instance.state() != InstanceLifecycle.State.RUNNING) {
                future.complete(false);
                return future;
            }

            boolean signalSent = seam.sendGracefulShutdown(instanceId);
            if (!signalSent) {
                future.complete(false);
                return future;
            }

            // Immediately check if process already exited
            if (!seam.isVmAlive(instanceId)) {
                controller.recordGracefulStop(instanceId);
                future.complete(true);
                return future;
            }

            // Polling task guarded by timeout
            long pollIntervalMs = Math.min(50L, Math.max(10L, timeoutMs / 10));
            long startTimeMillis = System.currentTimeMillis();
            AtomicLong checkCounter = new AtomicLong(0);

            ScheduledFuture<?>[] scheduledHolder = new ScheduledFuture<?>[1];
            scheduledHolder[0] = scheduler.scheduleAtFixedRate(() -> {
                try {
                    checkCounter.incrementAndGet();
                    if (!seam.isVmAlive(instanceId)) {
                        scheduledHolder[0].cancel(false);
                        controller.recordGracefulStop(instanceId);
                        future.complete(true);
                        return;
                    }

                    long elapsed = System.currentTimeMillis() - startTimeMillis;
                    if (elapsed >= timeoutMs) {
                        scheduledHolder[0].cancel(false);
                        // Timeout expired: guest remains running, no state deadlock
                        future.complete(false);
                    }
                } catch (Throwable t) {
                    scheduledHolder[0].cancel(false);
                    future.completeExceptionally(t);
                }
            }, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);

        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    /**
     * Directly terminates the underlying VM process, releases memory and sockets,
     * and transitions instance state to {@code STOPPED}.
     *
     * @param instanceId target instance ID
     * @return true if successfully powered off
     * @throws IOException on I/O error
     */
    public boolean powerOffForce(String instanceId) throws IOException {
        InstanceLifecycle.Instance instance = store.load(instanceId);
        if (instance.state() == InstanceLifecycle.State.STOPPED) {
            return true;
        }

        seam.killProcess(instanceId);
        controller.forceStop(instanceId);
        return true;
    }

    /**
     * Freezes vCPU execution and transitions state to {@code SUSPENDED}.
     *
     * @param instanceId target instance ID
     * @return true if suspended
     * @throws IOException on I/O error
     */
    public boolean suspend(String instanceId) throws IOException {
        InstanceLifecycle.Instance instance = store.load(instanceId);
        if (instance.state() != InstanceLifecycle.State.RUNNING) {
            throw new InstanceLifecycle.InvalidTransitionException(
                    "cannot suspend instance in state: " + instance.state() + "; expected RUNNING");
        }

        boolean frozen = seam.freezeCpu(instanceId);
        if (!frozen) {
            return false;
        }
        controller.suspend(instanceId);
        return true;
    }

    /**
     * Resumes vCPU execution and transitions state from {@code SUSPENDED} back to {@code RUNNING}.
     *
     * @param instanceId target instance ID
     * @return true if resumed
     * @throws IOException on I/O error
     */
    public boolean resume(String instanceId) throws IOException {
        InstanceLifecycle.Instance instance = store.load(instanceId);
        if (instance.state() != InstanceLifecycle.State.SUSPENDED) {
            throw new InstanceLifecycle.InvalidTransitionException(
                    "cannot resume instance in state: " + instance.state() + "; expected SUSPENDED");
        }

        // Validate single running instance constraint before unfreezing CPU
        controller.resume(instanceId);

        boolean unfrozen = seam.unfreezeCpu(instanceId);
        if (!unfrozen) {
            // Roll back state if hardware unfreeze fails
            controller.suspend(instanceId);
            return false;
        }
        return true;
    }
}
