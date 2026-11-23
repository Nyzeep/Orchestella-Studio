package io.github.nyzeep.orchestella;

import android.content.Context;
import android.os.SystemClock;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import io.github.nyzeep.orchestella.runtime.contract.BridgeHandshake;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.SessionCapabilities;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlEphemeralRootFixture;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessFactory;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessHandle;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlReadinessTracker;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRuntimeSupervisor;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Isolated standalone User-Mode Linux launcher for QA end-to-end verification (W3-01 / R-01).
 *
 * <p>Owns one UML guest process, duplex PTY terminal streams, Bridge v1 pipe transport,
 * disposable Alpine rootfs fixture, and exclusive root-volume reservation via
 * {@link UmlRuntimeSupervisor}. Never alters or shares state with {@link UmlFaceLauncher},
 * {@link UmlFaceRideAlong}, QEMU, or AVF.
 */
public final class UmlInstanceLauncher implements InstanceStrategyLauncher.UserModeKernelLaunch {
    static final String ALPINE_MINIROOTFS_RESOURCE = "alpine-minirootfs-3.22.2-aarch64.tar.gz";
    static final String KERNEL_LIBRARY_NAME = "libuml_kernel.so";
    static final String STUB_LIBRARY_NAME = "libuml_stub.so";
    static final String DEFAULT_QA_HOSTNAME = "qa-uml-alpine";
    static final int INITIAL_PTY_COLUMNS = 80;
    static final int INITIAL_PTY_ROWS = 24;
    static final long DEFAULT_STOP_GRACE_MS = 1_000L;
    static final long DEFAULT_TERMINATE_GRACE_MS = 2_000L;
    static final long DEFAULT_CLOSE_GRACE_MS = 2_000L;

    @FunctionalInterface
    interface ExecutableResolver {
        String resolve(String libraryName) throws IOException;
    }

    private static final ConcurrentMap<String, RuntimeProcessSupervisor.StopResult>
            LAST_FAILED_CLEANUP = new ConcurrentHashMap<>();

    private final Context context;
    private final ExecutableResolver executableResolver;
    private final File filesDir;
    private final UmlProcessFactory processFactory;
    private final UmlRootReservationRegistry reservationRegistry;
    private final UmlEphemeralRootFixture.TarSource tarSource;
    private final LongSupplier monotonicNanos;
    private final long stopGraceMs;
    private final long forceWaitMs;
    private final long channelsCloseTimeoutMs;

    public UmlInstanceLauncher(Context context) {
        Context appContext = Objects.requireNonNull(context, "context").getApplicationContext();
        this.context = appContext;
        this.executableResolver = soName -> NativeExecutableStore.ensure(appContext, soName);
        this.filesDir = appContext.getFilesDir();
        this.processFactory = new UmlNativePtyBridge();
        this.reservationRegistry = UmlRootReservationRegistry.defaultRegistry();
        this.tarSource = () -> PackagedAssetStreams.openResource(appContext, ALPINE_MINIROOTFS_RESOURCE);
        this.monotonicNanos = SystemClock::elapsedRealtimeNanos;
        this.stopGraceMs = DEFAULT_STOP_GRACE_MS;
        this.forceWaitMs = UmlRuntimeSupervisor.DEFAULT_FORCE_WAIT_MS;
        this.channelsCloseTimeoutMs = UmlRuntimeSupervisor.DEFAULT_CHANNELS_CLOSE_TIMEOUT_MS;
    }

    /** Pure-JVM injectable constructor for host contract tests. */
    public UmlInstanceLauncher(
            File nativeLibraryDir,
            File filesDir,
            UmlProcessFactory processFactory,
            UmlRootReservationRegistry reservationRegistry,
            UmlEphemeralRootFixture.TarSource tarSource,
            LongSupplier monotonicNanos,
            long stopGraceMs,
            long forceWaitMs,
            long channelsCloseTimeoutMs) {
        Objects.requireNonNull(nativeLibraryDir, "nativeLibraryDir");
        this.context = null;
        this.executableResolver = soName -> new File(nativeLibraryDir, soName).getAbsolutePath();
        this.filesDir = Objects.requireNonNull(filesDir, "filesDir");
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory");
        this.reservationRegistry = Objects.requireNonNull(reservationRegistry, "reservationRegistry");
        this.tarSource = Objects.requireNonNull(tarSource, "tarSource");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.stopGraceMs = Math.max(0L, stopGraceMs);
        this.forceWaitMs = Math.max(0L, forceWaitMs);
        this.channelsCloseTimeoutMs = Math.max(0L, channelsCloseTimeoutMs);
    }

    /**
     * Consumes the cleanup outcome of the most recent failed launch for {@code instanceId},
     * or {@code null} when none was recorded.
     */
    public static RuntimeProcessSupervisor.StopResult takeLastCleanupResult(String instanceId) {
        return instanceId == null ? null : LAST_FAILED_CLEANUP.remove(instanceId);
    }

    @Override
    public ExecutionSession launch(InstanceStrategyLauncher.LaunchParameters parameters)
            throws IOException, InterruptedException {
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.instanceId == null || parameters.instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }
        if (parameters.instanceRootVolume == null || parameters.instanceRootVolume.isBlank()) {
            throw new IllegalArgumentException("instanceRootVolume must not be blank");
        }
        if (parameters.runtimeDirectory == null || parameters.runtimeDirectory.isBlank()) {
            throw new IllegalArgumentException("runtimeDirectory must not be blank");
        }
        if (!BridgeHandshake.isCanonicalToken(parameters.bridgeToken)) {
            throw new IllegalArgumentException(
                    "bridgeToken must be 64 lowercase hexadecimal characters");
        }

        LAST_FAILED_CLEANUP.remove(parameters.instanceId);

        VmHardwareConfig config =
                parameters.hardwareConfig != null
                        ? parameters.hardwareConfig
                        : VmHardwareConfig.defaultConfig();
        Path runtimeDir = Paths.get(parameters.runtimeDirectory);
        Path rootVolumePath = Paths.get(parameters.instanceRootVolume);
        Files.createDirectories(runtimeDir);

        LaunchTimeline timeline = LaunchTimeline.open(
                runtimeDir,
                parameters.instanceId,
                resolveDistroId(parameters.rootfsFileName),
                config.ramMegabytes(),
                config.cpuCores(),
                monotonicNanos);
        timeline.mark(LaunchTimeline.Stage.LAUNCH_START);

        String generation = resolveGeneration(parameters.instanceId);
        LaunchStageObserver baseStageTrace = context != null
                ? StageTraceProvider.observerForLaunch(context, parameters.instanceId, generation, "uml")
                : new LaunchStageObserver() {};
        baseStageTrace.onGate(ProductionStageRecorder.Stage.LAUNCH_REQUESTED);

        final UmlRootReservationRegistry.Reservation reservation;
        try {
            reservation = reservationRegistry.acquire(rootVolumePath, parameters.instanceId, generation);
        } catch (UmlRootReservationRegistry.RootVolumeBusyException busy) {
            if (Objects.equals(parameters.instanceId, busy.occupyingInstanceId())) {
                LAST_FAILED_CLEANUP.put(
                        parameters.instanceId, RuntimeProcessSupervisor.StopResult.UNCONFIRMED);
            }
            baseStageTrace.onFailure(ProductionStageRecorder.Failure.CREATE_FAILED);
            timeline.finish();
            throw busy;
        }

        UmlReadinessTracker readinessTracker =
                new UmlReadinessTracker(parameters.instanceId, generation);

        Path ephemeralRootDir = null;
        try {
            baseStageTrace.onGate(ProductionStageRecorder.Stage.INPUTS_BEGIN);
            String kernelPath = executableResolver.resolve(KERNEL_LIBRARY_NAME);
            String stubExePath = executableResolver.resolve(STUB_LIBRARY_NAME);
            Files.createDirectories(runtimeDir.resolve("tmp"));
            String hostname = (parameters.hostname != null && !parameters.hostname.isBlank())
                    ? parameters.hostname
                    : DEFAULT_QA_HOSTNAME;
            ephemeralRootDir = UmlEphemeralRootFixture.prepare(
                    tarSource, runtimeDir, generation, parameters.bridgeToken, hostname);
            baseStageTrace.onGate(ProductionStageRecorder.Stage.INPUTS_VERIFIED);
            timeline.mark(LaunchTimeline.Stage.ASSETS_VERIFIED);

            UmlLaunchSpec spec = UmlLaunchSpec.builder()
                    .kernelPath(kernelPath)
                    .stubExePath(stubExePath)
                    .memMb(Math.max(UmlLaunchSpec.MIN_MEMORY_MB, config.ramMegabytes()))
                    .ephemeralRootDir(ephemeralRootDir)
                    .runtimeDir(runtimeDir)
                    .extraArgs(List.of("console=tty0"))
                    .build();

            baseStageTrace.onGate(ProductionStageRecorder.Stage.RUN_BEGIN);
            UmlProcessHandle handle = processFactory.spawn(spec, INITIAL_PTY_COLUMNS, INITIAL_PTY_ROWS);
            readinessTracker.markProcessStarted(generation, handle.pid(), monotonicNanos.getAsLong());
            timeline.mark(LaunchTimeline.Stage.UML_STARTED);
            baseStageTrace.onGate(ProductionStageRecorder.Stage.RUN_RETURNED);

            Session session = new Session(
                    parameters.instanceId,
                    generation,
                    handle,
                    ephemeralRootDir,
                    reservationRegistry,
                    reservation,
                    readinessTracker,
                    timeline,
                    baseStageTrace,
                    monotonicNanos,
                    forceWaitMs,
                    channelsCloseTimeoutMs,
                    DEFAULT_TERMINATE_GRACE_MS,
                    DEFAULT_CLOSE_GRACE_MS);

            try {
                ExecutionSessionRegistry.register(parameters.instanceId, session);
                timeline.mark(LaunchTimeline.Stage.SESSION_REGISTERED);
                return session;
            } catch (IOException | RuntimeException | Error registrationFailure) {
                baseStageTrace.onFailure(ProductionStageRecorder.Failure.UNKNOWN);
                UmlRuntimeSupervisor.StopEvidence cleanup = session.supervisor().cancelLaunch(stopGraceMs);
                RuntimeProcessSupervisor.StopResult mapped = mapStopResult(cleanup.result());
                LAST_FAILED_CLEANUP.put(parameters.instanceId, mapped);
                if (mapped == RuntimeProcessSupervisor.StopResult.UNCONFIRMED) {
                    timeline.markStopUnconfirmed();
                } else {
                    timeline.markStopConfirmed(mapped);
                    timeline.finish();
                }
                if (registrationFailure instanceof IOException io) {
                    throw io;
                }
                throw new IOException("UML session registration failed", registrationFailure);
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (!LAST_FAILED_CLEANUP.containsKey(parameters.instanceId)) {
                readinessTracker.markBridgeRejected(
                        generation,
                        UmlReadinessTracker.RejectionReason.LAUNCH_FAILED,
                        monotonicNanos.getAsLong());
                baseStageTrace.onFailure(ProductionStageRecorder.Failure.CREATE_FAILED);
                RuntimeProcessSupervisor.StopResult preSpawnCleanup =
                        cleanUpBeforeSpawn(ephemeralRootDir, reservationRegistry, reservation);
                LAST_FAILED_CLEANUP.put(parameters.instanceId, preSpawnCleanup);
                if (preSpawnCleanup != RuntimeProcessSupervisor.StopResult.UNCONFIRMED) {
                    timeline.finish();
                }
            }
            throw failure;
        }
    }

    private static RuntimeProcessSupervisor.StopResult cleanUpBeforeSpawn(
            Path ephemeralRootDir,
            UmlRootReservationRegistry registry,
            UmlRootReservationRegistry.Reservation reservation) {
        boolean rootCleaned = true;
        if (ephemeralRootDir != null) {
            try {
                UmlEphemeralRootFixture.cleanup(ephemeralRootDir);
            } catch (IOException | RuntimeException failure) {
                rootCleaned = false;
            }
        }
        if (rootCleaned) {
            registry.release(reservation);
            return RuntimeProcessSupervisor.StopResult.CONFIRMED;
        }
        return RuntimeProcessSupervisor.StopResult.UNCONFIRMED;
    }

    private static String resolveGeneration(String instanceId) {
        String active = InstanceLaunchGuard.generation(instanceId);
        if (active != null && !active.isBlank()) {
            return active;
        }
        return "uml-" + UUID.randomUUID();
    }

    static String resolveDistroId(String rootfsFileName) {
        if (rootfsFileName == null || rootfsFileName.isBlank()) {
            return "alpine";
        }
        return QemuLaunchEvidence.resolveDistroId(rootfsFileName);
    }

    static RuntimeProcessSupervisor.StopResult mapStopResult(UmlRuntimeSupervisor.StopResult result) {
        return switch (result) {
            case CONFIRMED -> RuntimeProcessSupervisor.StopResult.CONFIRMED;
            case UNCONFIRMED -> RuntimeProcessSupervisor.StopResult.UNCONFIRMED;
            case ALREADY_STOPPED -> RuntimeProcessSupervisor.StopResult.ALREADY_STOPPED;
        };
    }

    /** Standalone UML session owning PTY, Bridge v1 client, ephemeral root, and reservation. */
    public static final class Session implements ExecutionSession, RuntimeOwnedSession {
        private final String instanceId;
        private final String generation;
        private final UmlProcessHandle handle;
        private final Path ephemeralRootDir;
        private final UmlReadinessTracker readinessTracker;
        private final LaunchTimeline timeline;
        private final LaunchStageObserver stageObserver;
        private final LongSupplier monotonicNanos;
        private final long channelsCloseTimeoutMs;
        private final long terminateGraceMs;
        private final long closeGraceMs;
        private final UmlRuntimeSupervisor supervisor;

        private final Object bridgeLock = new Object();
        private final AtomicBoolean bridgeServing = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean terminalEofHandled = new AtomicBoolean(false);
        private BridgeClient bridgeClient;
        private volatile BridgeControl.Clipboard clipboardBridge;

        Session(
                String instanceId,
                String generation,
                UmlProcessHandle handle,
                Path ephemeralRootDir,
                UmlRootReservationRegistry reservationRegistry,
                UmlRootReservationRegistry.Reservation reservation,
                UmlReadinessTracker readinessTracker,
                LaunchTimeline timeline,
                LaunchStageObserver baseStageTrace,
                LongSupplier monotonicNanos,
                long forceWaitMs,
                long channelsCloseTimeoutMs,
                long terminateGraceMs,
                long closeGraceMs) {
            this.instanceId = instanceId;
            this.generation = generation;
            this.handle = handle;
            this.ephemeralRootDir = ephemeralRootDir;
            this.readinessTracker = readinessTracker;
            this.timeline = timeline;
            this.monotonicNanos = monotonicNanos;
            this.channelsCloseTimeoutMs = channelsCloseTimeoutMs;
            this.terminateGraceMs = terminateGraceMs;
            this.closeGraceMs = closeGraceMs;
            this.stageObserver = new GenerationAwareStageObserver(baseStageTrace);
            this.supervisor = new UmlRuntimeSupervisor(
                    handle,
                    this::closeBridgeQuietly,
                    () -> cleanupEphemeralRoot(ephemeralRootDir),
                    reservationRegistry,
                    reservation,
                    forceWaitMs,
                    channelsCloseTimeoutMs,
                    new TimelineStopObserver());
        }

        private static void cleanupEphemeralRoot(Path dir) {
            if (dir == null) {
                return;
            }
            try {
                UmlEphemeralRootFixture.cleanup(dir);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private void closeBridgeQuietly() {
            BridgeClient client;
            synchronized (bridgeLock) {
                client = this.bridgeClient;
                this.bridgeClient = null;
            }
            try {
                handle.controlInput().close();
            } catch (IOException | RuntimeException ignored) {
                // Best-effort pipe unblock for BridgeClient response pump.
            }
            try {
                handle.controlOutput().close();
            } catch (IOException | RuntimeException ignored) {
                // Best-effort pipe unblock for BridgeClient response pump.
            }
            if (client != null) {
                try {
                    client.awaitDisconnectedReader(Math.min(500L, channelsCloseTimeoutMs));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        UmlReadinessTracker readinessTracker() {
            return readinessTracker;
        }

        UmlRuntimeSupervisor supervisor() {
            return supervisor;
        }

        Path ephemeralRootDir() {
            return ephemeralRootDir;
        }

        String generation() {
            return generation;
        }

        private boolean isCurrentGeneration() {
            return !InstanceLaunchGuard.isSuperseded(instanceId, generation);
        }

        @Override
        public FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            return FullLinuxContract.CapabilityMatrix.from(
                    SessionCapabilities.builder(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL)
                            .ownership(SessionCapabilities.Ownership.RUNTIME_OWNED)
                            .terminal(true)
                            .bridge(true)
                            .build());
        }

        @Override
        public int waitFor() throws InterruptedException {
            return handle.waitFor();
        }

        @Override
        public boolean isRuntimeAlive() {
            return supervisor.isRuntimeAlive();
        }

        @Override
        public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            timeline.mark(LaunchTimeline.Stage.STOP_REQUESTED);
            stageObserver.onGate(ProductionStageRecorder.Stage.STOP_REQUESTED);
            UmlRuntimeSupervisor.StopEvidence evidence = supervisor.stop(graceMs);
            RuntimeProcessSupervisor.StopResult mapped = mapStopResult(evidence.result());
            if (mapped == RuntimeProcessSupervisor.StopResult.UNCONFIRMED) {
                timeline.markStopUnconfirmed();
            } else {
                timeline.markStopConfirmed(mapped);
                stageObserver.onStopFact(ProductionStageRecorder.StopFact.OWNED_VM_STOPPED);
                stageObserver.onFinish();
                timeline.finish();
            }
            return mapped;
        }

        @Override
        public void terminate() throws IOException {
            BridgeClient client;
            synchronized (bridgeLock) {
                client = this.bridgeClient;
            }
            if (client != null && client.isReady()) {
                try {
                    client.power(BridgeControl.PowerAction.POWEROFF);
                } catch (IOException | RuntimeException ignored) {
                    // Best-effort guest poweroff before host reap + release.
                }
            }
            if (stopRuntime(terminateGraceMs) == RuntimeProcessSupervisor.StopResult.UNCONFIRMED) {
                throw new IOException("UML virtual machine stop was not confirmed");
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            stopRuntime(closeGraceMs);
        }

        @Override
        public OutputStream terminalInput() throws IOException {
            return handle.terminalInput();
        }

        @Override
        public InputStream terminalOutput() throws IOException {
            InputStream raw = handle.terminalOutput();
            return new FilterInputStream(raw) {
                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value == -1) {
                        onTerminalEof();
                    }
                    return value;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int count = super.read(b, off, len);
                    if (count == -1) {
                        onTerminalEof();
                    }
                    return count;
                }
            };
        }

        private void onTerminalEof() {
            if (terminalEofHandled.compareAndSet(false, true)) {
                UmlRuntimeSupervisor.StopEvidence evidence = supervisor.signalTerminalEof();
                if (evidence != null
                        && evidence.result() != UmlRuntimeSupervisor.StopResult.UNCONFIRMED) {
                    timeline.markStopConfirmed(mapStopResult(evidence.result()));
                    timeline.finish();
                }
            }
        }

        @Override
        public OutputStream controlInput() throws IOException {
            return handle.controlInput();
        }

        @Override
        public InputStream controlOutput() throws IOException {
            return handle.controlOutput();
        }

        @Override
        public boolean resizeTerminalHost(int columns, int rows) {
            return handle.resizeTerminal(columns, rows);
        }

        @Override
        public BridgeClient bridgeClient() {
            synchronized (bridgeLock) {
                if (closed.get()) {
                    return null;
                }
                if (bridgeClient == null) {
                    try {
                        bridgeClient = new BridgeClient(controlOutput(), controlInput());
                    } catch (IOException unavailable) {
                        return null;
                    }
                }
                return bridgeClient;
            }
        }

        @Override
        public boolean recoverControlChannel() {
            return false;
        }

        @Override
        public void registerClipboardBridge(BridgeControl.Clipboard clipboard) {
            this.clipboardBridge = clipboard;
        }

        @Override
        public BridgeControl.Clipboard clipboardBridge() {
            return this.clipboardBridge;
        }

        @Override
        public boolean markBridgeServing() {
            return bridgeServing.compareAndSet(false, true);
        }

        @Override
        public LaunchStageObserver launchStageObserver() {
            return stageObserver;
        }

        @Override
        public void markShellReady() {
            if (!isCurrentGeneration()) {
                readinessTracker.markBridgeRejected(
                        generation,
                        UmlReadinessTracker.RejectionReason.STALE_GENERATION,
                        monotonicNanos.getAsLong());
                return;
            }
            if (readinessTracker.markShellReady(generation, monotonicNanos.getAsLong())) {
                timeline.mark(LaunchTimeline.Stage.SHELL_READY);
            }
        }

        private final class GenerationAwareStageObserver implements LaunchStageObserver {
            private final LaunchStageObserver delegate;

            GenerationAwareStageObserver(LaunchStageObserver delegate) {
                this.delegate = delegate;
            }

            @Override
            public void onGate(ProductionStageRecorder.Stage stage) {
                if (!isCurrentGeneration()) {
                    return;
                }
                delegate.onGate(stage);
            }

            @Override
            public void onGateAuthenticated(ProductionStageRecorder.Stage stage) {
                if (!isCurrentGeneration()) {
                    readinessTracker.markBridgeRejected(
                            generation,
                            UmlReadinessTracker.RejectionReason.STALE_GENERATION,
                            monotonicNanos.getAsLong());
                    return;
                }
                delegate.onGateAuthenticated(stage);
                if (stage == ProductionStageRecorder.Stage.AGENT_AUTHENTICATED) {
                    if (readinessTracker.markBridgeReady(generation, monotonicNanos.getAsLong())) {
                        timeline.mark(LaunchTimeline.Stage.BRIDGE_READY);
                        if (readinessTracker.snapshot().shellReady()) {
                            timeline.mark(LaunchTimeline.Stage.SHELL_READY);
                        }
                    }
                }
            }

            @Override
            public void onFallback(ProductionStageRecorder.Failure failure) {
                if (!isCurrentGeneration()) {
                    return;
                }
                delegate.onFallback(failure);
            }

            @Override
            public void onFailure(ProductionStageRecorder.Failure failure) {
                if (!isCurrentGeneration()) {
                    return;
                }
                delegate.onFailure(failure);
                if (failure == ProductionStageRecorder.Failure.READINESS_FAILED) {
                    BridgeClient client;
                    synchronized (bridgeLock) {
                        client = Session.this.bridgeClient;
                    }
                    UmlReadinessTracker.RejectionReason reason =
                            client != null && "failed".equals(client.readinessState())
                                    ? UmlReadinessTracker.RejectionReason.TIMEOUT
                                    : UmlReadinessTracker.RejectionReason.UNAUTHORIZED;
                    readinessTracker.markBridgeRejected(
                            generation,
                            reason,
                            monotonicNanos.getAsLong());
                }
            }

            @Override
            public void onCancel() {
                if (!isCurrentGeneration()) {
                    return;
                }
                readinessTracker.cancel(generation, monotonicNanos.getAsLong());
                delegate.onCancel();
            }

            @Override
            public void onStopFact(ProductionStageRecorder.StopFact fact) {
                delegate.onStopFact(fact);
            }

            @Override
            public void onFinish() {
                delegate.onFinish();
            }
        }

        private final class TimelineStopObserver implements UmlRuntimeSupervisor.StopObserver {
            @Override
            public void onProcessReaped(long pid, int exitCode) {
                timeline.mark(LaunchTimeline.Stage.EXIT_OBSERVED);
            }

            @Override
            public void onChannelsReleased(boolean released) {}

            @Override
            public void onResourcesReleased(boolean released) {}

            @Override
            public void onRootReleased(boolean released) {}
        }
    }
}
