package io.github.nyzeep.orchestella;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * B2 follow-up ② (UML face product wiring, step one): the product lifecycle
 * manager for the UML compile-worker face. It starts the UML kernel the same
 * way the research prototype ({@code research/uml-runner}, the source of
 * {@code dev.orchestella.umlrunner} 0.2.0) proved it on device — exec
 * {@code nativeLibraryDir/libuml_kernel.so} with the {@code stub_exe=} hostfs
 * configuration — and exposes exactly the two seams the B2 orchestration
 * consumes: an {@link #widthSupplier() IntSupplier} for the fabric slot's
 * {@code umlFaceWidth} and a {@link #faceWorker() FaceWorker} for the
 * {@link FabricOrchestrator} workers map under
 * {@link FabricWidthDecision.Face#UML}. {@code FabricOrchestrator} itself is
 * untouched: it was built to consume this face through injection.
 *
 * <p><b>Hard prerequisite: foreground service + wake lock.</b> The 2026-11-07
 * device round (§7.1.6) proved the whole process group — this app and its UML
 * child together — is frozen by the OEM cgroup freezer within minutes of the
 * screen dozing unless the app holds a foreground service and a wake lock;
 * the E18 window measured 60 minutes of zero-freeze residency with that
 * combination. {@link #start} therefore acquires the injected
 * {@link KeepAlive} right after a successful spawn and releases it on stop or
 * observed kernel death; the production {@code KeepAlive} is
 * {@link UmlFaceKeepAliveService#keepAlive}, which is required, never a
 * silent no-op.
 *
 * <p><b>Honest degradation.</b> A face that cannot start never pretends to:
 * <ul>
 *   <li>Missing assets (kernel/stub/rootfs — the kernel binaries are not in
 *   the repository, they are placed per the prototype's prepare flow) answer
 *   the fixed {@link StartCode#MISSING_ASSET} code and the width stays 0.</li>
 *   <li>A spawn failure answers {@link StartCode#SPAWN_FAILED}; nothing is
 *   acquired.</li>
 *   <li>An unwired {@link Channel} (the device window injects the real
 *   distcc-over-TCP channel; the bridge lane-marker change is fabric-route
 *   doc D-1, ACR-bound) keeps the width at 0, so the orchestration never
 *   routes work into a face that cannot serve it.</li>
 *   <li>The gate off means a zero-side-effect refusal: no asset is read, no
 *   process is spawned, no keep-alive is touched — the run stays today's
 *   run.</li>
 * </ul>
 *
 * <p><b>Fast profile (P2, flag-gated).</b> A build that opts in via the
 * {@code orchestella.uml.fastProfile} Gradle property (→ {@code
 * BuildConfig.UML_FAST_PROFILE}, default <b>off</b>) launches the
 * {@code research/uml-fastboot} §4 fast shapes: the built-in-initramfs UP
 * base line and its {@code umroot=}/{@code ubd0=} external-root channels, as
 * selected by {@link FastProfile}. The flag only changes the command line and
 * the asset precheck; the double gate, the lifecycle, the keep-alive, the
 * width honesty and the channel contract are this class's own invariants and
 * stay exactly as locked. The default build reads the flag off, answers
 * {@code null}, and produces today's command line byte-identically — the
 * flag-off contract tests hold that without exception.
 *
 * <p><b>Width.</b> The UML face's dispatchable width is the kernel's
 * effective SMP width: the {@code ncpus=} knob when it is ≥2 (the knob the
 * device round measured — {@code ncpus=N}, never {@code --cpus=N}, which the
 * SMP kernel does not recognize), and 1 for the default host-1 single-core
 * shape. A stopped or dead face reads 0. The runner shape (the flag-off
 * build's face) additionally reads 0 while the compile channel is unwired —
 * its locked flag-off semantics. The fast-profile shapes (ACR 2026-11-12 D4,
 * the built-initramfs UP default reads 1) report the kernel's real width
 * while up; their unroutability until the device window wires the compile
 * channel is enforced at the worker (the fixed channel-unwired refusal), not
 * by zeroing the width. The fabric slot clamps this to the ledger headroom,
 * so an over-declaring config cannot oversubscribe the ledger.
 */
public final class UmlFaceLauncher {

    /** The UML kernel image, packaged under {@code jniLibs} as a {@code lib*.so}. */
    public static final String KERNEL_LIB_NAME = "libuml_kernel.so";
    /** The UML {@code stub_exe} helper, packaged under {@code jniLibs} likewise. */
    public static final String STUB_LIB_NAME = "libuml_stub.so";
    /** The guest rootfs directory (hostfs channel) under {@code filesDir}. */
    public static final String ROOTFS_DIR_NAME = "rootfs";
    /**
     * The fast-profile ext4 image under {@code filesDir} — the {@code ubd0=}
     * channel of {@link FastProfile} (the name the device windows place it
     * under; see {@link #fastProfileCommand}).
     */
    public static final String FAST_ROOTFS_IMAGE_NAME = "rootfs.ext4";

    /** Fixed detail for a dispatch that reached a face with no kernel running. */
    public static final String FACE_NOT_RUNNING_DETAIL = "uml-face-not-running";
    /** Fixed detail for a dispatch that reached a face with no compile channel wired. */
    public static final String CHANNEL_UNWIRED_DETAIL = "uml-face-compile-channel-unwired";

    private static final long STOP_GRACE_MS = 3_000L;
    /** Kernel env: the guest RAM backing files must land somewhere exec-mappable. */
    private static final String PROCESS_PATH = "/system/bin:/system/xbin:/vendor/bin";

    /**
     * Fixed start outcomes. A start either succeeds or carries exactly one
     * fixed code; diagnostics travel in {@link StartResult#detail()}.
     */
    public enum StartCode {
        /** The kernel process is up and the keep-alive is held. */
        STARTED,
        /** The double gate is off: nothing was read, spawned or acquired. */
        GATE_DISABLED,
        /** A required asset (kernel, stub or rootfs) is missing — width stays 0. */
        MISSING_ASSET,
        /** The kernel process could not be spawned — nothing was acquired. */
        SPAWN_FAILED,
        /** A previous instance is still running; stop it first. */
        ALREADY_RUNNING
    }

    /** One start outcome: a fixed code plus optional diagnostic detail. */
    public record StartResult(StartCode code, String detail) {
        public StartResult {
            Objects.requireNonNull(code, "code");
        }

        public static StartResult of(StartCode code) {
            return new StartResult(code, null);
        }

        public boolean started() {
            return code == StartCode.STARTED;
        }
    }

    /** Lifecycle states; {@link #STOPPING} already reads width 0. */
    public enum State { STOPPED, RUNNING, STOPPING }

    /** Fixed health codes for the probe the wiring owner polls. */
    public enum Health {
        /** Running and the kernel process is alive. */
        HEALTHY,
        /** Not running (never started, stopped, or reaped). */
        NOT_RUNNING,
        /** Marked running but the kernel process died — the probe reaps it. */
        PROCESS_DEAD
    }

    /** The paths the face needs; the Android glue supplies the app's own dirs. */
    public interface Environment {
        /** The app's {@code nativeLibraryDir} — where the kernel images unpack. */
        File nativeLibraryDir();

        /** The app's {@code filesDir} — rootfs parent, TMPDIR and working dir. */
        File filesDir();
    }

    /**
     * The compile-work execution seam of the face. The device window injects
     * the real channel (distcc over the fabric's TCP access point); until
     * then the face is unwired, reads width 0 and is not routable.
     */
    @FunctionalInterface
    public interface Channel {
        void execute(int grantedWidth, FabricOrchestrator.WorkItem item)
                throws FabricOrchestrator.FaceException;
    }

    /**
     * The foreground-service + wake-lock seam (the anti-freeze hard
     * prerequisite). Acquired once per running kernel and released on stop or
     * observed death; an acquire failure must not crash the face (the
     * research prototype keeps running without freeze protection), but the
     * seam itself is required — a wiring point that cannot protect the face
     * must not wire it at all.
     */
    public interface KeepAlive {
        void acquire();

        void release();
    }

    /** The spawned kernel process; the only process surface the launcher sees. */
    public interface UmlProcess {
        boolean isAlive();

        /** Graceful stop (SIGTERM semantics). */
        void destroy();

        /** Last-resort stop (SIGKILL semantics). */
        void destroyForcibly();

        /** True when the process exited within the timeout. */
        boolean waitForExit(long timeoutMs) throws InterruptedException;

        static UmlProcess adapt(Process process) {
            Objects.requireNonNull(process, "process");
            return new UmlProcess() {
                @Override public boolean isAlive() {
                    return process.isAlive();
                }

                @Override public void destroy() {
                    process.destroy();
                }

                @Override public void destroyForcibly() {
                    process.destroyForcibly();
                }

                @Override public boolean waitForExit(long timeoutMs) throws InterruptedException {
                    return process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            };
        }
    }

    /** Spawns the kernel; injectable so tests and the device window own exec. */
    @FunctionalInterface
    public interface ProcessSpawner {
        UmlProcess spawn(List<String> command, Map<String, String> environment, File workingDir)
                throws IOException;

        /** The production spawner: merged stdout/stderr, explicit environment. */
        static ProcessSpawner processBuilder() {
            return (command, environment, workingDir) -> {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(workingDir);
                builder.redirectErrorStream(true);
                builder.environment().putAll(environment);
                return UmlProcess.adapt(builder.start());
            };
        }
    }

    /**
     * Injectable launch parameters — the device window's tuning interface.
     * The defaults are the prototype's proven shape (512M guest RAM, host-1
     * single core, no extra args); {@code extraArgs} land verbatim after the
     * fixed argument list so a device round can tune scalar kernel knobs
     * (UML parses params sequentially, later scalar assignments win) without
     * touching this class.
     */
    public record LaunchParams(int memMb, int ncpus, List<String> extraArgs) {
        public LaunchParams {
            if (memMb <= 0) {
                throw new IllegalArgumentException("memMb must be positive, got: " + memMb);
            }
            if (ncpus < 0) {
                throw new IllegalArgumentException("ncpus must be non-negative, got: " + ncpus);
            }
            extraArgs = List.copyOf(extraArgs == null ? List.of() : extraArgs);
        }

        /** The prototype's proven defaults: 512M, host-1 single core. */
        public static LaunchParams defaults() {
            return new LaunchParams(512, 0, List.of());
        }

        /**
         * The device-capability default (C lane, the width thermostat's
         * supply side): the guest gets the device's whole core count as the
         * {@code ncpus} knob when the device has a multi-core CPU
         * ({@code ncpus=N} — the knob the device round measured, never
         * {@code --cpus=N}), and stays on the host-1 single-core shape when it
         * does not. This is a supply, not a grant: the fabric's ledger is the
         * thermostat — {@link FabricOrchestrator#ledgerBackedWidths} clamps the
         * resulting width to the ledger headroom, so an eight-core supply on a
         * two-core ledger budget cannot oversubscribe the fabric (the
         * sum-of-faces-equals-ledger invariant the locked tests hold).
         *
         * @param deviceCpuCores the device's CPU core count
         *        ({@code Runtime.getRuntime().availableProcessors()} shape)
         * @throws IllegalArgumentException when {@code deviceCpuCores <= 0}
         */
        public static LaunchParams forDeviceCapability(int deviceCpuCores) {
            if (deviceCpuCores <= 0) {
                throw new IllegalArgumentException(
                        "deviceCpuCores must be positive, got: " + deviceCpuCores);
            }
            return new LaunchParams(512, deviceCpuCores >= 2 ? deviceCpuCores : 0, List.of());
        }

        /**
         * The worker's real serviceable compile concurrency: {@code ncpus=N}
         * when the knob is ≥2, 1 for the omitted/host-1 single-core shape.
         */
        public int effectiveWidth() {
            return ncpus >= 2 ? ncpus : 1;
        }
    }

    /**
     * The fast-profile launch shape ({@code research/uml-fastboot} README §4,
     * the authoritative fast-kernel window): the minimal UP kernel with the
     * built-in initramfs boots through the §4 command line —
     * {@code mem=…M rw con=null con0=null,fd:1 seccomp=on panic=1
     * printk.time=1 um.hold_ms=1000 stub_exe=…} — and an external root is
     * switched into by the initramfs init via {@code umroot=}. The shapes:
     * <ul>
     *   <li>{@link Root#INITRAMFS} — the §4 base line only; no external root,
     *   so no rootfs asset is required;</li>
     *   <li>{@link Root#HOSTFS} — the §4 base line plus
     *   {@code umroot=<filesDir>/rootfs umrootfstype=hostfs}; requires the
     *   {@link #ROOTFS_DIR_NAME} directory;</li>
     *   <li>{@link Root#EXT4} — the §4 base line plus
     *   {@code ubd0=<filesDir>/rootfs.ext4 umroot=/dev/ubda umrootfstype=ext4};
     *   requires the {@link #FAST_ROOTFS_IMAGE_NAME} image.</li>
     * </ul>
     *
     * <p>All three shapes run the same kernel image ({@link #KERNEL_LIB_NAME},
     * the built-in-initramfs fast kernel) and the same stub, keep the same
     * lifecycle/width/channel honesty as the runner shape, and stay behind the
     * double gate — a fast profile is a launch shape, never an authorization.
     * The shape is selected by the build flag ({@code
     * orchestella.uml.fastProfile} → {@code BuildConfig.UML_FAST_PROFILE},
     * default off) via {@link #currentBuild()}, or injected explicitly by the
     * device window through the seven-argument constructor. {@code null} (and
     * {@link #currentBuild()} when the flag is off) keeps the exact
     * runner-shape command line the locked tests hold.
     */
    public record FastProfile(Root root) {
        public FastProfile {
            Objects.requireNonNull(root, "root");
        }

        /** The external-root channel of the §4 fast shapes. */
        public enum Root { INITRAMFS, HOSTFS, EXT4 }

        /** The §4 UP built-in-initramfs shape (no external root). */
        public static FastProfile initramfs() {
            return new FastProfile(Root.INITRAMFS);
        }

        /** The §4 external hostfs root shape ({@code umroot=}). */
        public static FastProfile hostfs() {
            return new FastProfile(Root.HOSTFS);
        }

        /** The §4 external ext4 root shape ({@code ubd0=} + {@code umroot=}). */
        public static FastProfile ext4() {
            return new FastProfile(Root.EXT4);
        }

        /**
         * The build-flag read ({@code BuildConfig.UML_FAST_PROFILE}, the
         * {@code orchestella.uml.fastProfile} Gradle property): the §4 UP
         * initramfs shape when the build opted in, {@code null} — the exact
         * runner shape — otherwise. The default (bare local) build keeps the
         * flag off, so it answers {@code null}; the release pipeline states
         * the flag explicitly (ACR 2026-11-12 D1/D4), and the assembly's
         * {@code umlFace} slot feeds this read into the shipped face.
         */
        public static FastProfile currentBuild() {
            return BuildConfig.UML_FAST_PROFILE ? initramfs() : null;
        }
    }

    private final boolean umlFaceEnabled;
    private final Environment environment;
    private final LaunchParams params;
    private final KeepAlive keepAlive;
    private final ProcessSpawner spawner;
    private final Channel channel;
    /** {@code null} = the exact runner shape (the flag-off contract). */
    private final FastProfile fastProfile;

    private UmlProcess process;
    private State state = State.STOPPED;
    private boolean keepAliveHeld;

    /**
     * The historical six-argument wiring: the exact runner-shape launcher the
     * locked tests hold (byte-identical command line, unchanged asset
     * precheck). Equivalent to the seven-argument constructor with a
     * {@code null} fast profile.
     */
    public UmlFaceLauncher(
            boolean umlFaceEnabled,
            Environment environment,
            LaunchParams params,
            KeepAlive keepAlive,
            ProcessSpawner spawner,
            Channel channel) {
        this(umlFaceEnabled, environment, params, keepAlive, spawner, channel, null);
    }

    /**
     * @param umlFaceEnabled the existing double gate (build flag + user
     *        setting) as the launch consumed it; off means every start is a
     *        zero-side-effect {@link StartCode#GATE_DISABLED} answer — the
     *        fast profile never bypasses it
     * @param environment the app directories the kernel runs from
     * @param params the injectable launch parameters (device-window tuning)
     * @param keepAlive the foreground-service + wake-lock seam; required,
     *        never a silent no-op (the anti-freeze hard prerequisite)
     * @param spawner the process spawn seam (production: ProcessBuilder)
     * @param channel the compile-work channel; {@code null} until the device
     *        window wires the real one — an unwired face reads width 0 and is
     *        not routable, which keeps the orchestration honest
     * @param fastProfile the fast-profile launch shape ({@code
     *        research/uml-fastboot} README §4); {@code null} keeps the exact
     *        runner shape — the default build's answer, locked byte-identical
     *        by the flag-off contract tests
     */
    public UmlFaceLauncher(
            boolean umlFaceEnabled,
            Environment environment,
            LaunchParams params,
            KeepAlive keepAlive,
            ProcessSpawner spawner,
            Channel channel,
            FastProfile fastProfile) {
        this.umlFaceEnabled = umlFaceEnabled;
        this.environment = Objects.requireNonNull(environment, "environment");
        this.params = Objects.requireNonNull(params, "params");
        this.keepAlive = Objects.requireNonNull(keepAlive, "keepAlive");
        this.spawner = Objects.requireNonNull(spawner, "spawner");
        this.channel = channel;
        this.fastProfile = fastProfile;
    }

    /**
     * The production wiring: the ProcessBuilder spawner and no compile channel
     * yet (the device window injects the real channel over the fabric's TCP
     * access point), in the exact runner shape.
     */
    public static UmlFaceLauncher create(
            boolean umlFaceEnabled,
            Environment environment,
            LaunchParams params,
            KeepAlive keepAlive) {
        return new UmlFaceLauncher(
                umlFaceEnabled, environment, params, keepAlive,
                ProcessSpawner.processBuilder(), null);
    }

    /**
     * The production wiring with the build flag's fast-profile shape
     * ({@link FastProfile#currentBuild()}): the §4 fast command line when the
     * build opted in ({@code -Porchestella.uml.fastProfile=true}), the exact
     * runner shape otherwise. The lifecycle seams are unchanged.
     *
     * <p>Wired since ACR 2026-11-12 D4: {@link AcceleratorLaneAssembly#umlFace}
     * — the assembly's one UML-face wiring point — calls this instead of
     * {@link #create}, so the shipped face's launch shape follows
     * {@code BuildConfig.UML_FAST_PROFILE} (the {@code
     * orchestella.uml.fastProfile} Gradle property) and the flag-off build
     * keeps the byte-identical runner shape. The double gate, the keep-alive
     * seam and the honest-degradation contract are the constructor's own
     * invariants; the fast profile is a launch shape, never an authorization.
     */
    public static UmlFaceLauncher createWithCurrentFastProfile(
            boolean umlFaceEnabled,
            Environment environment,
            LaunchParams params,
            KeepAlive keepAlive) {
        return new UmlFaceLauncher(
                umlFaceEnabled, environment, params, keepAlive,
                ProcessSpawner.processBuilder(), null, FastProfile.currentBuild());
    }

    /**
     * Starts the UML compile worker: prechecks the assets, spawns the kernel
     * with the prototype's proven command line and acquires the keep-alive.
     * Every non-{@link StartCode#STARTED} outcome is a fixed code with zero
     * side effects — the face never fakes a running state.
     */
    public synchronized StartResult start() {
        if (!umlFaceEnabled) {
            // Zero side effects: no asset read, no spawn, no keep-alive.
            return StartResult.of(StartCode.GATE_DISABLED);
        }
        if (state != State.STOPPED) {
            return StartResult.of(StartCode.ALREADY_RUNNING);
        }
        String missing = missingAsset();
        if (missing != null) {
            return new StartResult(StartCode.MISSING_ASSET, missing);
        }
        UmlProcess spawned;
        try {
            spawned = spawner.spawn(command(), kernelEnvironment(), environment.filesDir());
        } catch (IOException | RuntimeException spawnFailure) {
            return new StartResult(StartCode.SPAWN_FAILED, String.valueOf(spawnFailure));
        }
        process = spawned;
        state = State.RUNNING;
        acquireKeepAliveQuietly();
        return StartResult.of(StartCode.STARTED);
    }

    /**
     * Stops the kernel: SIGTERM, a bounded grace, then SIGKILL; releases the
     * keep-alive. Idempotent — stopping a stopped face is a no-op. The width
     * reads 0 from the moment the stop begins.
     */
    public void stop() {
        UmlProcess stopping;
        synchronized (this) {
            if (state != State.RUNNING) {
                return;
            }
            state = State.STOPPING;
            stopping = process;
        }
        boolean exited = false;
        if (stopping != null) {
            stopping.destroy();
            try {
                exited = stopping.waitForExit(STOP_GRACE_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!exited) {
                stopping.destroyForcibly();
            }
        }
        synchronized (this) {
            process = null;
            state = State.STOPPED;
            releaseKeepAlive();
        }
    }

    /**
     * Probes the face and reaps a dead kernel lazily: the next observation
     * after an unexpected kernel death zeroes the width and releases the
     * keep-alive, so the face never keeps freeze protection for a kernel that
     * is gone. The wiring owner may poll this; the width is honest either way.
     */
    public synchronized Health probe() {
        if (state != State.RUNNING) {
            return Health.NOT_RUNNING;
        }
        if (process == null || !process.isAlive()) {
            process = null;
            state = State.STOPPED;
            releaseKeepAlive();
            return Health.PROCESS_DEAD;
        }
        return Health.HEALTHY;
    }

    /**
     * The face's dispatchable width: the kernel's effective SMP width while
     * the face is running and healthy; 0 otherwise. This is the {@code
     * umlFaceWidth} value the fabric slot consumes (it clamps the value to
     * the ledger headroom).
     *
     * <p>Channel semantics by shape (ACR 2026-11-12 D4): the runner shape —
     * the flag-off build's exact historical face — keeps the locked rule that
     * an unwired compile channel reads 0, so the orchestration never routes
     * work into a face that cannot serve it. A fast-profile face reads the
     * kernel's real width while it is up (the built-in-initramfs UP default
     * reads 1, the device round's T3 verdict); its unwired channel is a
     * routability question, not a capacity one, and stays enforced at the
     * worker — a dispatch that reaches a channel-less fast face throws the
     * fixed {@link #CHANNEL_UNWIRED_DETAIL} {@link FabricOrchestrator.FaceException},
     * which rides the orchestrator's release-grant-and-evict contract. The
     * flag-off build never constructs a fast-profile face through the product
     * wiring, so its width semantics are byte-identical to the locked ones.
     */
    public synchronized int width() {
        if (state != State.RUNNING || process == null || !process.isAlive()) {
            return 0;
        }
        if (channel == null && fastProfile == null) {
            // The runner shape's locked rule: an unwired compile channel keeps
            // the face at width 0. The fast shape (ACR D4) reports the kernel's
            // real width below; its unroutability rides the worker's contract.
            return 0;
        }
        return params.effectiveWidth();
    }

    /** The {@code umlFaceWidth} supplier for the fabric slot. */
    public IntSupplier widthSupplier() {
        return this::width;
    }

    /**
     * The face's {@link FabricOrchestrator.FaceWorker}: hands admitted work to
     * the wired channel. A dispatch that reaches a face that stopped (or died)
     * mid-flight throws the fixed {@link FabricOrchestrator.FaceException} —
     * the orchestrator's own failure contract (release grant, evict face)
     * takes over from there.
     */
    public FabricOrchestrator.FaceWorker faceWorker() {
        return (face, grantedWidth, item) -> execute(grantedWidth, item);
    }

    private void execute(int grantedWidth, FabricOrchestrator.WorkItem item)
            throws FabricOrchestrator.FaceException {
        Channel wired;
        synchronized (this) {
            if (state != State.RUNNING || process == null || !process.isAlive()) {
                throw new FabricOrchestrator.FaceException(FACE_NOT_RUNNING_DETAIL);
            }
            wired = channel;
        }
        if (wired == null) {
            throw new FabricOrchestrator.FaceException(CHANNEL_UNWIRED_DETAIL);
        }
        wired.execute(grantedWidth, item);
    }

    /** The current lifecycle state (observability for the wiring owner). */
    public synchronized State state() {
        return state;
    }

    private String missingAsset() {
        // The runner shape keeps the single source of the placement layout (C
        // lane): the parity check in UmlKernelAssets, semantics-identical to
        // the historical precheck. The fast shapes need a different asset set
        // (the built-in initramfs needs no external root at all), so they
        // precheck locally with the same honesty: binaries must be executable
        // files, the root channel must exist, first failure names the asset.
        if (fastProfile == null) {
            return UmlKernelAssets.firstMissingName(environment);
        }
        return fastMissingAsset(fastProfile, environment);
    }

    /**
     * The fast-shape asset precheck, in the fixed priority kernel → stub →
     * root channel, named exactly as the {@code MISSING_ASSET} detail names
     * it. {@link FastProfile.Root#INITRAMFS} requires no external root; {@link
     * FastProfile.Root#HOSTFS} requires the {@link #ROOTFS_DIR_NAME}
     * directory; {@link FastProfile.Root#EXT4} requires the {@link
     * #FAST_ROOTFS_IMAGE_NAME} image. Null when everything the shape needs is
     * in place — a missing root channel is a fixed-code refusal, never a
     * faked start.
     */
    static String fastMissingAsset(FastProfile fast, Environment environment) {
        Objects.requireNonNull(fast, "fast");
        Objects.requireNonNull(environment, "environment");
        File libDir = environment.nativeLibraryDir();
        File kernel = new File(libDir, KERNEL_LIB_NAME);
        if (!kernel.isFile() || !kernel.canExecute()) {
            return KERNEL_LIB_NAME;
        }
        File stub = new File(libDir, STUB_LIB_NAME);
        if (!stub.isFile() || !stub.canExecute()) {
            return STUB_LIB_NAME;
        }
        File files = environment.filesDir();
        switch (fast.root()) {
            case INITRAMFS:
                return null;
            case HOSTFS:
                File rootfs = new File(files, ROOTFS_DIR_NAME);
                return rootfs.isDirectory() ? null : ROOTFS_DIR_NAME;
            case EXT4:
                File image = new File(files, FAST_ROOTFS_IMAGE_NAME);
                return image.isFile() ? null : FAST_ROOTFS_IMAGE_NAME;
            default:
                throw new IllegalStateException("unknown fast profile root " + fast.root());
        }
    }

    /**
     * The fast-profile command line (research/uml-fastboot README §4, the
     * authoritative fast-kernel device window): the §4 base line over the
     * built-in-initramfs kernel, the shape's external-root channel appended,
     * then the {@code ncpus=} knob when the launch parameters ask for SMP
     * (wave2 §3: {@code ncpus=N}, never {@code --cpus=N}), then the verbatim
     * {@code extraArgs} last so a device window can override a scalar (UML
     * parses params sequentially, later scalar assignments win — including
     * {@code um.hold_ms}). Pure and framework-free: the contract tests assert
     * the §4 list argument for argument.
     *
     * @param fast the fast-profile shape (non-null)
     * @param params the launch parameters ({@code memMb}, {@code ncpus},
     *        {@code extraArgs})
     * @param nativeLibraryDir the app's {@code nativeLibraryDir} (kernel and
     *        stub placement)
     * @param filesDir the app's {@code filesDir} (the external-root channels
     *        live under it)
     */
    public static List<String> fastProfileCommand(
            FastProfile fast, LaunchParams params, File nativeLibraryDir, File filesDir) {
        Objects.requireNonNull(fast, "fast");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(nativeLibraryDir, "nativeLibraryDir");
        Objects.requireNonNull(filesDir, "filesDir");
        List<String> args = new ArrayList<>(Arrays.asList(
                "mem=" + params.memMb() + "M",
                "rw",
                "con=null",
                "con0=null,fd:1",
                "seccomp=on",
                "panic=1",
                "printk.time=1",
                "um.hold_ms=1000",
                "stub_exe=" + new File(nativeLibraryDir, STUB_LIB_NAME).getAbsolutePath()));
        switch (fast.root()) {
            case INITRAMFS:
                // The built-in initramfs is the root; no external-root args.
                break;
            case HOSTFS:
                args.add("umroot=" + new File(filesDir, ROOTFS_DIR_NAME).getAbsolutePath());
                args.add("umrootfstype=hostfs");
                break;
            case EXT4:
                args.add("ubd0=" + new File(filesDir, FAST_ROOTFS_IMAGE_NAME).getAbsolutePath());
                args.add("umroot=/dev/ubda");
                args.add("umrootfstype=ext4");
                break;
            default:
                throw new IllegalStateException("unknown fast profile root " + fast.root());
        }
        if (params.ncpus() >= 2) {
            args.add("ncpus=" + params.ncpus());
        }
        args.addAll(params.extraArgs());
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(new File(nativeLibraryDir, KERNEL_LIB_NAME).getAbsolutePath());
        command.addAll(args);
        return command;
    }

    /**
     * The prototype's proven command line (research/uml-runner README §5):
     * hostfs rootfs, {@code stub_exe=} from the app's own library dir, and
     * {@code ncpus=N} only when the knob is ≥2 (an UP kernel must not see the
     * knob; {@code --cpus=} is never sent — the SMP kernel does not know it).
     * With the fast profile wired, the §4 fast shape takes over (see {@link
     * #fastProfileCommand}); with it {@code null} — the default build — this
     * is byte-identical to the historical list the locked tests hold.
     */
    private List<String> command() {
        if (fastProfile != null) {
            return fastProfileCommand(
                    fastProfile, params, environment.nativeLibraryDir(), environment.filesDir());
        }
        File libDir = environment.nativeLibraryDir();
        File files = environment.filesDir();
        List<String> args = new ArrayList<>(Arrays.asList(
                "mem=" + params.memMb() + "M",
                "rw",
                "init=/um-init",
                "con=null",
                "con0=fd:0,fd:1",
                "stub_exe=" + new File(libDir, STUB_LIB_NAME).getAbsolutePath(),
                "rootfstype=hostfs",
                "rootflags=" + new File(files, ROOTFS_DIR_NAME).getAbsolutePath(),
                "seccomp=on",
                "panic=1"));
        if (params.ncpus() >= 2) {
            args.add("ncpus=" + params.ncpus());
        }
        args.addAll(params.extraArgs());
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(new File(libDir, KERNEL_LIB_NAME).getAbsolutePath());
        command.addAll(args);
        return command;
    }

    private Map<String, String> kernelEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        File files = environment.filesDir();
        env.put("TMPDIR", files.getAbsolutePath());
        env.put("HOME", files.getAbsolutePath());
        env.put("PATH", PROCESS_PATH);
        return env;
    }

    private void acquireKeepAliveQuietly() {
        try {
            keepAlive.acquire();
            keepAliveHeld = true;
        } catch (RuntimeException acquireFailure) {
            // The face keeps running without freeze protection (the prototype's
            // contract); a release is then a no-op.
            keepAliveHeld = false;
        }
    }

    private void releaseKeepAlive() {
        if (!keepAliveHeld) {
            return;
        }
        keepAliveHeld = false;
        try {
            keepAlive.release();
        } catch (RuntimeException releaseFailure) {
            // Release failures must not mask the lifecycle result.
        }
    }
}
