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
            if (result != null) return result;

            boolean processAlive = process.isAlive();
            boolean gatewayAlive = gateway != null && gateway.isAlive();
            if (!processAlive && !gatewayAlive) {
                closeChannelsOnce();
                result = StopResult.ALREADY_STOPPED;
                diagnostics = new Diagnostics("already-stopped", process.pid(), grace, false, exitCode());
                return result;
            }

            boolean confirmed = true;
            boolean forced = false;
            String stage = "destroy-wait";
            if (processAlive) {
                process.destroy();
                if (!awaitExit(grace)) {
                    forced = true;
                    stage = "force-wait";
                    process.destroyForcibly();
                    if (!awaitExit(forceWaitMs)) {
                        closeChannelsOnce();
                        result = StopResult.UNCONFIRMED;
                        diagnostics = new Diagnostics(stage, process.pid(), grace, true, -1);
                        return result;
                    }
                }
            }

            if (!closeChannelsOnce()) {
                confirmed = false;
                stage = "channels";
            }
            if (gatewayAlive && !gateway.stop(grace)) {
                confirmed = false;
                stage = "gateway";
            }

            result = confirmed ? StopResult.CONFIRMED : StopResult.UNCONFIRMED;
            diagnostics = new Diagnostics(confirmed ? "confirmed" : stage,
                    process.pid(), grace, forced, confirmed ? exitCode() : -1);
            return result;
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

    public StopResult stopResult() {
        synchronized (lock) {
            return result;
        }
    }

    private boolean closeChannelsOnce() {
        if (channelsClosed) return true;
        channelsClosed = true;
        try {
            closeChannels.run();
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
