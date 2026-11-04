package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single owner of one QEMU runtime: the QEMU process, the gvproxy handle and the
 * host-side control/terminal/display/proxy channels.
 *
 * <p>Stop is bounded and idempotent. It returns {@link StopResult#UNCONFIRMED}
 * instead of pretending the runtime stopped when any stage times out, so callers
 * can keep RUNNING metadata, the disk lease and the registered owner intact.
 *
 * <p>The channel-cleanup stage has its own hard deadline ({@value
 * #DEFAULT_CHANNELS_CLOSE_TIMEOUT_MS} ms by default). Cleanup runs on a daemon
 * worker so a local close that hangs cannot hold the stop past that window; the
 * supervisor still proceeds to the gateway stop and reports stage {@code
 * channels}. The worker only closes host channels and never becomes a second
 * stop owner.
 */
public final class RuntimeProcessSupervisor {
    /** Default hard deadline for the host-channel cleanup stage. */
    static final long DEFAULT_CHANNELS_CLOSE_TIMEOUT_MS = 1000L;
    public enum StopResult { CONFIRMED, UNCONFIRMED, ALREADY_STOPPED }

    /**
     * Observation-only stop facts (ticket 27), fired at the existing decision
     * points after the fact they report. Implementations must never throw,
     * never block for long and never mutate control flow; a throwing observer
     * is swallowed and the stop result is unaffected. Called while the
     * supervisor lock is held.
     */
    public interface StopObserver {
        /** The owner verified the backing VM is no longer alive. */
        void onVmStoppedObserved();

        /** The host channels/FDs were released ({@code true}) or not ({@code false}). */
        void onChannelsReleased(boolean released);

        /** The gateway process was stopped ({@code true}) or not ({@code false}). */
        void onGatewayReleased(boolean released);
    }

    /** Structured, secret-free stop diagnostics. */
    public static final class Diagnostics {
        private final String stage;
        private final long pid;
        private final long graceMs;
        private final boolean forced;
        private final int exitCode;

        Diagnostics(String stage, long pid, long graceMs, boolean forced, int exitCode) {
            this.stage = stage;
            this.pid = pid;
            this.graceMs = graceMs;
            this.forced = forced;
            this.exitCode = exitCode;
        }

        public String stage() { return stage; }
        public long pid() { return pid; }
        public long graceMs() { return graceMs; }
        public boolean forced() { return forced; }
        public int exitCode() { return exitCode; }

        static Diagnostics none() {
            return new Diagnostics("not-started", -1L, 0L, false, -1);
        }
    }

    private final QemuProcessLauncher.ProcessHandle process;
    private final RuntimeGateway gateway;
    private final Runnable closeChannels;
    private final long forceWaitMs;
    private final long channelsCloseTimeoutMs;
    private final StopObserver stopObserver;
    private final Object lock = new Object();
    private StopResult result;
    private Diagnostics diagnostics = Diagnostics.none();
    private final AtomicBoolean channelsClosed = new AtomicBoolean(false);
    private final AtomicReference<Thread> channelsWorker = new AtomicReference<>();

    public RuntimeProcessSupervisor(QemuProcessLauncher.ProcessHandle process,
            RuntimeGateway gateway, Runnable closeChannels) {
        this(process, gateway, closeChannels, 2000L, DEFAULT_CHANNELS_CLOSE_TIMEOUT_MS);
    }

    public RuntimeProcessSupervisor(QemuProcessLauncher.ProcessHandle process,
            RuntimeGateway gateway, Runnable closeChannels, long forceWaitMs) {
        this(process, gateway, closeChannels, forceWaitMs, DEFAULT_CHANNELS_CLOSE_TIMEOUT_MS);
    }

    /** Adds the observation-only stop facts observer with the default deadlines. */
    public RuntimeProcessSupervisor(QemuProcessLauncher.ProcessHandle process,
            RuntimeGateway gateway, Runnable closeChannels, StopObserver stopObserver) {
        this(process, gateway, closeChannels, 2000L, DEFAULT_CHANNELS_CLOSE_TIMEOUT_MS,
                stopObserver);
    }

    /**
     * @param forceWaitMs bounded wait after {@code destroyForcibly}
     * @param channelsCloseTimeoutMs hard deadline for the channel-cleanup stage;
     *        {@code 0} means do not block (the cleanup worker may finish later)
     */
    public RuntimeProcessSupervisor(QemuProcessLauncher.ProcessHandle process,
            RuntimeGateway gateway, Runnable closeChannels, long forceWaitMs,
            long channelsCloseTimeoutMs) {
        this(process, gateway, closeChannels, forceWaitMs, channelsCloseTimeoutMs, null);
    }

    /**
     * Adds the observation-only stop observer; {@code null} keeps the previous
     * behavior exactly. Observing never changes the stop outcome.
     */
    public RuntimeProcessSupervisor(QemuProcessLauncher.ProcessHandle process,
            RuntimeGateway gateway, Runnable closeChannels, long forceWaitMs,
            long channelsCloseTimeoutMs, StopObserver stopObserver) {
        if (process == null) throw new IllegalArgumentException("process");
        if (closeChannels == null) throw new IllegalArgumentException("closeChannels");
        this.process = process;
        this.gateway = gateway;
        this.closeChannels = closeChannels;
        this.forceWaitMs = Math.max(0L, forceWaitMs);
        this.channelsCloseTimeoutMs = Math.max(0L, channelsCloseTimeoutMs);
        this.stopObserver = stopObserver;
    }

    /**
     * Stops the runtime with a bounded grace window. Concurrent callers block on
     * the same monitor and all observe the first caller's result.
     */
    public StopResult stop(long graceMs) {
        long grace = Math.max(0L, graceMs);
        synchronized (lock) {
            // Confirmed outcomes are final. UNCONFIRMED is retryable: a later call
            // re-probes liveness so a process that died after the timeout can be
            // confirmed and release its gateway instead of blocking restart forever.
            if (result == StopResult.CONFIRMED || result == StopResult.ALREADY_STOPPED) return result;

            boolean processAlive = process.isAlive();
            boolean gatewayAlive = gateway != null && gateway.isAlive();
            if (!processAlive && !gatewayAlive) {
                observeVmStopped();
                boolean channels = closeChannelsOnce();
                observeChannels(channels);
                boolean gatewayStopped = gateway == null || stopGateway(grace);
                observeGateway(gatewayStopped);
                result = channels && gatewayStopped ? StopResult.ALREADY_STOPPED : StopResult.UNCONFIRMED;
                // Channels succeeding must not mask a failed gateway cleanup: stage
                // "gateway" is what tells a reader which resource is still unconfirmed.
                diagnostics = new Diagnostics(channels && gatewayStopped ? "already-stopped"
                        : !channels ? "channels" : "gateway",
                        process.pid(), grace, false, channels && gatewayStopped ? exitCode() : -1);
                return result;
            }

            boolean forced = false;
            if (processAlive) {
                try {
                    process.destroy();
                } catch (RuntimeException failure) {
                    return unconfirmed("destroy", grace, false);
                }
                if (!awaitExit(grace)) {
                    forced = true;
                    try {
                        process.destroyForcibly();
                    } catch (RuntimeException failure) {
                        return unconfirmed("force", grace, true);
                    }
                    if (!awaitExit(forceWaitMs)) {
                        observeChannels(closeChannelsOnce());
                        return unconfirmed("force-wait", grace, true);
                    }
                }
            }
            observeVmStopped();

            boolean channels = closeChannelsOnce();
            observeChannels(channels);
            boolean gatewayStopped = gateway == null || stopGateway(grace);
            observeGateway(gatewayStopped);
            result = channels && gatewayStopped ? StopResult.CONFIRMED : StopResult.UNCONFIRMED;
            int code = process.isAlive() ? -1 : exitCode();
            diagnostics = new Diagnostics(channels && gatewayStopped ? "confirmed"
                    : !channels ? "channels" : "gateway",
                    process.pid(), grace, forced, code);
            return result;
        }
    }

    private StopResult unconfirmed(String stage, long grace, boolean forced) {
        result = StopResult.UNCONFIRMED;
        diagnostics = new Diagnostics(stage, process.pid(), grace, forced, -1);
        return result;
    }

    /** Observation only: a throwing observer never changes the stop outcome. */
    private void observeVmStopped() {
        if (stopObserver == null) return;
        try {
            stopObserver.onVmStoppedObserved();
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never affect the owner stop.
        }
    }

    private void observeChannels(boolean released) {
        if (stopObserver == null) return;
        try {
            stopObserver.onChannelsReleased(released);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never affect the owner stop.
        }
    }

    private void observeGateway(boolean released) {
        if (stopObserver == null) return;
        try {
            stopObserver.onGatewayReleased(released);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never affect the owner stop.
        }
    }

    private boolean stopGateway(long grace) {
        if (gateway == null) return true;
        try {
            return gateway.stop(grace);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    /** True while the process or the gateway may still be alive. */
    public boolean isRuntimeAlive() {
        return process.isAlive() || (gateway != null && gateway.isAlive());
    }

    public Diagnostics diagnostics() {
        synchronized (lock) {
            return diagnostics;
        }
    }

    /**
     * Runs channel cleanup at most once successfully, on a daemon worker bounded by
     * {@code channelsCloseTimeoutMs}. A cleanup that hangs is left behind as at most
     * one daemon thread per supervisor; a later stop joins the same worker instead of
     * stacking another. Failure or timeout is reported as an unconfirmed stage.
     */
    private boolean closeChannelsOnce() {
        if (channelsClosed.get()) return true;
        Thread worker = channelsWorker.get();
        if (worker == null || !worker.isAlive()) {
            Thread next = new Thread(() -> {
                try {
                    closeChannels.run();
                    channelsClosed.set(true);
                } catch (RuntimeException failure) {
                    // Retryable on the next stop, matching the previous failure semantics.
                }
            }, "runtime-channels-close");
            next.setDaemon(true);
            if (channelsWorker.compareAndSet(worker, next)) {
                worker = next;
                worker.start();
            } else {
                worker = channelsWorker.get();
            }
        }
        if (worker == null) return channelsClosed.get();
        if (channelsCloseTimeoutMs > 0L) {
            boolean interrupted = false;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(channelsCloseTimeoutMs);
            while (worker.isAlive()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) break;
                try {
                    worker.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                } catch (InterruptedException ignored) {
                    // Keep the deadline and restore the caller's interrupt afterwards.
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            // A truly stuck local close must not hold the stop; interrupt is best-effort.
            // With a zero deadline the caller asked not to wait, so leave the worker alone.
            if (channelsCloseTimeoutMs > 0L) worker.interrupt();
            return false;
        }
        return channelsClosed.get();
    }

    private boolean awaitExit(long timeoutMs) {
        try {
            boolean exited = process.waitFor(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
            return exited || !process.isAlive();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    private int exitCode() {
        try {
            if (!process.waitFor(0L, TimeUnit.MILLISECONDS)) return -1;
            return process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
}
