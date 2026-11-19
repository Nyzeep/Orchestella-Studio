package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DW-01: probes the packaged QEMU binary ({@code libdroidlayer_qemu.so}) for
 * the {@code virtio-gpu-gl-device} device model. G-06 wired the launch gate to
 * {@link VirglJniBridge#isAvailable} + {@code init()}, but the packaged QEMU
 * build may still lack the device model entirely: in that case the launch
 * claimed {@code ACTIVE} in its structured log while QEMU exited with code 1
 * before the guest ever booted. This probe closes that honesty gap by asking
 * the actual binary what devices it supports.
 *
 * <p>Probe mechanics: QEMU's standard {@code -device help} flag prints the
 * supported device list to stdout and exits before any machine is created
 * (no disk, no sockets, no guest state touched). The outcome is decided by
 * content only: stdout containing {@code virtio-gpu-gl-device} means
 * supported. The exit code is deliberately ignored — QEMU versions differ on
 * whether the help path exits 0 or 1, so it carries no signal.
 *
 * <p>Fail-closed rationale (asymmetric cost): a probe that wrongly reports
 * SUPPORTED produces a dead VM (QEMU exit 1 with the gl-device argument), so
 * every failure mode — spawn error, timeout, unreadable output, null path —
 * resolves to {@link Outcome#UNSUPPORTED}. The launch then downgrades to the
 * 2D device with {@code GpuFallbackReason.QEMU_DEVICE_MODEL_UNSUPPORTED}: the
 * user loses GPU acceleration but keeps a bootable machine, and the
 * structured log states the true reason. A legacy QEMU old enough to reject
 * {@code -device help} outright also predates {@code virtio-gpu-gl-device}
 * (added in QEMU 6.1), so the fail-closed reading is factually correct there.
 *
 * <p>Threading: the probe execs a process and therefore never runs on the
 * main/UI thread. The engine path calls {@link #probeOutcome(String)} from
 * {@link VirglGpuLifecycle#activate}, which executes on the launcher's worker
 * thread; the app's UI path warms the cache from Application startup via a
 * background thread ({@code GpuDeviceModelProbeOwner.warmUpAsync}) and reads
 * the published snapshot reactively, never calling {@link #probeOutcome} on
 * Android's main thread. The result is cached process-wide (first call
 * computes, every later call reads the cache) because the packaged binary
 * cannot change within a process.
 */
public final class QemuDeviceModelProbe {
    /** Probe verdict for one binary path. */
    public enum Outcome {
        /** stdout listed the {@code virtio-gpu-gl-device} model. */
        SUPPORTED,
        /** Not listed, or the probe failed (fail-closed, see class doc). */
        UNSUPPORTED,
        /** Cache placeholder: the probe has not completed for this path yet. */
        PENDING,
    }

    /** The device model the launch args emit for an ACTIVE GPU context. */
    public static final String TARGET_DEVICE = GpuAccelerationStatus.GL_DEVICE_ARG;

    /** Bounded wait for the help output; the process exits in milliseconds on a healthy device. */
    public static final long DEFAULT_TIMEOUT_MS = 5000L;

    /**
     * Seam over process execution so JVM tests fake the QEMU stdout without
     * running a real binary. Returns the process's stdout; a thrown exception
     * means "could not obtain output" (the caller fail-closes).
     */
    public interface CommandRunner {
        String run(String[] command, long timeoutMs) throws IOException, InterruptedException;
    }

    private static final CommandRunner DEFAULT_RUNNER = new CommandRunner() {
        @Override public String run(String[] command, long timeoutMs)
                throws IOException, InterruptedException {
            return runWithTimeout(command, timeoutMs);
        }
    };

    /** Test seam; {@code null} restores the real process runner. */
    private static volatile CommandRunner runner = DEFAULT_RUNNER;

    /** Single-entry process-wide cache: the packaged binary cannot change mid-process. */
    private static final Object CACHE_LOCK = new Object();
    private static String cachedPath;
    private static Outcome cachedOutcome = Outcome.PENDING;

    private QemuDeviceModelProbe() {
    }

    /**
     * Blocking, cached probe. First call for a path runs the probe (fail-closed:
     * every failure yields {@link Outcome#UNSUPPORTED}), later calls return the
     * cached verdict. Callers must be off the Android main thread.
     */
    public static Outcome probeOutcome(String qemuBinaryPath) {
        if (qemuBinaryPath == null || qemuBinaryPath.trim().isEmpty()) {
            return Outcome.UNSUPPORTED;
        }
        synchronized (CACHE_LOCK) {
            if (qemuBinaryPath.equals(cachedPath) && cachedOutcome != Outcome.PENDING) {
                return cachedOutcome;
            }
            Outcome outcome = probeUncached(qemuBinaryPath, runner, DEFAULT_TIMEOUT_MS);
            cachedPath = qemuBinaryPath;
            cachedOutcome = outcome;
            return outcome;
        }
    }

    /**
     * Non-blocking cache read for UI consumers: {@link Outcome#PENDING} means
     * the probe has not completed for this path yet. {@code PENDING} must be
     * read conservatively (treated as unsupported) — an honest "not yet
     * proven" never overpromises a launch that would exit 1.
     */
    public static Outcome cachedOutcome(String qemuBinaryPath) {
        if (qemuBinaryPath == null || qemuBinaryPath.trim().isEmpty()) {
            return Outcome.UNSUPPORTED;
        }
        synchronized (CACHE_LOCK) {
            return qemuBinaryPath.equals(cachedPath) ? cachedOutcome : Outcome.PENDING;
        }
    }

    /** Test-only seam; {@code null} restores the real process runner. */
    static void useRunnerForTest(CommandRunner replacement) {
        runner = replacement == null ? DEFAULT_RUNNER : replacement;
    }

    /** Package-visible for tests: the currently installed runner seam. */
    static CommandRunner activeRunnerForTest() {
        return runner;
    }

    /** Test-only seam: clears the process-wide cache so tests start from PENDING. */
    static void resetForTest() {
        synchronized (CACHE_LOCK) {
            cachedPath = null;
            cachedOutcome = Outcome.PENDING;
        }
    }

    /** The active seam; package-visible so tests can pin the delegation contract. */
    static CommandRunner defaultRunner() {
        return DEFAULT_RUNNER;
    }

    /**
     * Pure probe: one run, no cache. Package-visible for tests; production
     * always goes through {@link #probeOutcome(String)}.
     */
    static Outcome probeUncached(String qemuBinaryPath, CommandRunner commandRunner, long timeoutMs) {
        if (qemuBinaryPath == null || qemuBinaryPath.trim().isEmpty()) {
            return Outcome.UNSUPPORTED;
        }
        String stdout;
        try {
            stdout = commandRunner.run(
                    new String[] {qemuBinaryPath, "-device", "help"}, timeoutMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Outcome.UNSUPPORTED;
        } catch (RuntimeException | IOException | Error failure) {
            return Outcome.UNSUPPORTED;
        }
        return stdout != null && stdout.contains(TARGET_DEVICE)
                ? Outcome.SUPPORTED
                : Outcome.UNSUPPORTED;
    }

    /**
     * Real runner: execs the binary with {@code -device help}, drains stdout and
     * stderr on pump threads (a large device list or chatty stderr must never
     * deadlock the pipe buffers), and bounds the total wait. Timeout, spawn
     * failure or a hung drain all throw so the caller fail-closes.
     */
    private static String runWithTimeout(String[] command, long timeoutMs)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        AtomicReference<String> stdout = new AtomicReference<>();
        AtomicReference<Throwable> pumpFailure = new AtomicReference<>();
        Thread stdoutPump = pump(process.getInputStream(), stdout, pumpFailure);
        Thread stderrPump = pump(process.getErrorStream(), new AtomicReference<>(), pumpFailure);
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("qemu device probe timed out after " + timeoutMs + " ms");
            }
            stdoutPump.join(TimeUnit.SECONDS.toMillis(1L));
            stderrPump.join(TimeUnit.SECONDS.toMillis(1L));
        } finally {
            stdoutPump.interrupt();
            stderrPump.interrupt();
        }
        if (stdoutPump.isAlive() || stderrPump.isAlive()) {
            throw new IOException("qemu device probe output drain did not finish");
        }
        Throwable failure = pumpFailure.get();
        if (failure != null) {
            throw new IOException("qemu device probe output drain failed", failure);
        }
        return stdout.get();
    }

    /** Daemon pump that reads one stream fully; the reference receives the text. */
    private static Thread pump(InputStream stream, AtomicReference<String> sink,
            AtomicReference<Throwable> failureSink) {
        Thread pump = new Thread(() -> {
            try (InputStream owned = stream) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = owned.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
                sink.set(buffer.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
            } catch (Throwable failure) {
                failureSink.compareAndSet(null, failure);
            }
        });
        pump.setDaemon(true);
        pump.start();
        return pump;
    }
}
