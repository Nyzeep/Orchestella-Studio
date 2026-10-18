package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.engineqemu.QemuProcessLauncher;
import java.util.concurrent.TimeUnit;

/**
 * Single owner of one QEMU runtime: the QEMU process, the gvproxy handle and the
 * host-side control/terminal/display/proxy channels.
 *
 * <p>Stop is bounded and idempotent. It returns {@link StopResult#UNCONFIRMED}
 * instead of pretending the runtime stopped when any stage times out, so callers
 * can keep RUNNING metadata, the disk lease and the registered owner intact.
 */
public final class RuntimeProcessSupervisor {
    public enum StopResult { CONFIRMED, UNCONFIRMED, ALREADY_STOPPED }

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
    private final Object lock = new Object();
    private StopResult result;
    private Diagnostics diagnostics = Diagnostics.none();
    private boolean channelsClosed;

    public RuntimeProcessSupervisor(QemuProcessLauncher.ProcessHandle process,
            RuntimeGateway gateway, Runnable closeChannels) {
        this(process, gateway, closeChannels, 2000L);
    }

    public RuntimeProcessSupervisor(QemuProcessLauncher.ProcessHandle process,
            RuntimeGateway gateway, Runnable closeChannels, long forceWaitMs) {
        if (process == null) throw new IllegalArgumentException("process");
        if (closeChannels == null) throw new IllegalArgumentException("closeChannels");
        this.process = process;
        this.gateway = gateway;
        this.closeChannels = closeChannels;
        this.forceWaitMs = Math.max(0L, forceWaitMs);
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
                boolean channels = closeChannelsOnce();
                boolean gatewayStopped = gateway == null || stopGateway(grace);
                result = channels && gatewayStopped ? StopResult.ALREADY_STOPPED : StopResult.UNCONFIRMED;
                diagnostics = new Diagnostics(channels && gatewayStopped ? "already-stopped" : "channels",
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
                        closeChannelsOnce();
                        return unconfirmed("force-wait", grace, true);
                    }
                }
            }

            boolean channels = closeChannelsOnce();
            boolean gatewayStopped = gateway == null || stopGateway(grace);
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

    private boolean closeChannelsOnce() {
        if (channelsClosed) return true;
        try {
            closeChannels.run();
            channelsClosed = true;
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
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
