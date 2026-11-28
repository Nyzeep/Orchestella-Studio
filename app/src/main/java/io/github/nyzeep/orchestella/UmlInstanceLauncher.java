package io.github.nyzeep.orchestella;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import io.github.nyzeep.orchestella.runtime.capability.ExecutionStrategyAdmissionEvaluator;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import io.github.nyzeep.orchestella.runtime.contract.BridgeHandshake;
import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingEngine;
import io.github.nyzeep.orchestella.runtime.contract.SessionCapabilities;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineuml.CpioNewcToolExtractor;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlBootPlan;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlBootPlanResolver;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroCapabilityCatalog;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlDistroRuntimeProfile;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlEphemeralRootFixture;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceBudget;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceRootfsProvisioner;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlKernelPayloadContract;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessFactory;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessHandle;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlReadinessTracker;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRuntimeSupervisor;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlVolumeInspector;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.network.LoopbackEndpointRegistry;
import io.github.nyzeep.orchestella.runtime.network.download.ResilientDownloadEngine;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Isolated standalone User-Mode Linux launcher for QA end-to-end verification (W3-01 / W3-02).
 *
 * <p>Supports persistent per-instance ext4 {@code ubd0} root volumes assembled atomically on
 * first boot via {@link UmlInstanceRootfsProvisioner} (reusing {@link UmlRootfsProvisioner}'s
 * verified Alpine input as a read-only seed), while retaining compatibility with the W3-01
 * disposable HOSTFS constructor for legacy contract tests. Never alters
 * {@link UmlFaceLauncher}, {@link UmlFaceRideAlong}, QEMU, or AVF;
 * host-memory admission reads {@link UmlFaceStatusRegistry#runningMemMb()}
 * strictly as read-only input.
 */
public final class UmlInstanceLauncher implements InstanceStrategyLauncher.UserModeKernelLaunch {
    static final String ALPINE_MINIROOTFS_RESOURCE = UmlKernelAssets.FastProfile.ROOTFS_TAR;
    static final String KERNEL_LIBRARY_NAME = "libuml_kernel.so";
    static final String STUB_LIBRARY_NAME = "libuml_stub.so";
    static final String DEFAULT_QA_HOSTNAME = "qa-uml-alpine";
    static final String DEFAULT_DEBIAN_QA_HOSTNAME = "qa-uml-debian";
    static final int INITIAL_PTY_COLUMNS = 80;
    static final int INITIAL_PTY_ROWS = 24;
    static final long DEFAULT_STOP_GRACE_MS = 1_000L;
    static final long DEFAULT_TERMINATE_GRACE_MS = 2_000L;
    static final long DEFAULT_CLOSE_GRACE_MS = 2_000L;
    private static final String QEMU_BASE_INITRAMFS_RESOURCE = "initramfs.cpio.gz";
    private static final long SYSTEMD_POWEROFF_WAIT_MS = 8_000L;
    private static final Set<String> PROVISION_TOOLCHAIN_ENTRIES =
            Set.of("sbin/mkfs.ext4", "etc/mke2fs.conf", "bin/busybox", "opt/droidlayer/guest-agent");
    private static final Set<String> PROVISION_TOOLCHAIN_EXECS =
            Set.of("sbin/mkfs.ext4", "bin/busybox", "opt/droidlayer/guest-agent");

    @FunctionalInterface
    interface ExecutableResolver {
        String resolve(String libraryName) throws IOException;
    }

    @FunctionalInterface
    interface SeedInputSupplier {
        UmlInstanceRootfsProvisioner.SeedInput supply(
                InstanceStrategyLauncher.LaunchParameters parameters, String distroId)
                throws IOException;
    }

    /** Evaluates profile payload integrity for standalone UML launches. */
    @FunctionalInterface
    public interface ProfilePreflightEvaluator {
        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight evaluate(
                UmlDistroCapabilityCatalog.KernelProfile profile,
                String distroId,
                boolean requireRootfsSeed);
    }

    /** Refusal raised before launch mutations when channel, profile, or persisted config conflicts. */
    public static final class UmlLaunchAdmissionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final UmlBootPlan bootPlan;

        public UmlLaunchAdmissionException(UmlBootPlan bootPlan) {
            super(formatBootPlanMessage(Objects.requireNonNull(bootPlan, "bootPlan")));
            this.bootPlan = bootPlan;
        }

        public UmlBootPlan bootPlan() {
            return bootPlan;
        }
    }

    /** Refusal raised when an assembled root volume is missing, empty, or corrupted. */
    public static final class UmlVolumeRecoveryException extends IOException {
        private static final long serialVersionUID = 1L;
        private final UmlBootPlan bootPlan;

        public UmlVolumeRecoveryException(UmlBootPlan bootPlan) {
            super(formatBootPlanMessage(Objects.requireNonNull(bootPlan, "bootPlan")));
            this.bootPlan = bootPlan;
        }

        public UmlBootPlan bootPlan() {
            return bootPlan;
        }
    }

    /** Refusal raised when profile payloads or seeds fail integrity checks without fallback. */
    public static final class UmlPayloadVerificationException extends IOException {
        private static final long serialVersionUID = 1L;

        public UmlPayloadVerificationException(String message) {
            super(message);
        }

        public UmlPayloadVerificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String formatBootPlanMessage(UmlBootPlan plan) {
        if (!plan.recoveryGuidance().isBlank()) {
            return plan.detail() + " — " + plan.recoveryGuidance();
        }
        return plan.detail();
    }

    private static final ConcurrentMap<String, RuntimeProcessSupervisor.StopResult>
            LAST_FAILED_CLEANUP = new ConcurrentHashMap<>();
    private static final AtomicBoolean PROCESS_BUDGET_CONFIGURED = new AtomicBoolean(false);

    private final Context context;
    private final ExecutableResolver executableResolver;
    private final File filesDir;
    private final UmlProcessFactory processFactory;
    private final UmlRootReservationRegistry reservationRegistry;
    private final UmlInstanceBudget instanceBudget;
    private final LoopbackEndpointRegistry endpointRegistry;
    private final UmlEphemeralRootFixture.TarSource tarSource;
    private final LongSupplier monotonicNanos;
    private final long stopGraceMs;
    private final long forceWaitMs;
    private final long channelsCloseTimeoutMs;
    private final boolean persistentVolumeMode;
    private final UmlInstanceRootfsProvisioner instanceProvisioner;
    private final SeedInputSupplier seedInputSupplier;
    private final Object instanceWriteLock;
    private final InstanceLifecycle.FileStore instanceStore;
    private final Clock clock;
    private final BooleanSupplier isolatedQaChannelAllowed;
    private final ProfilePreflightEvaluator preflightEvaluator;

    /** Configures the process-wide UML memory budget against Android host RAM and ride-along usage. */
    public static void configureProcessBudget(Context context) {
        Objects.requireNonNull(context, "context");
        Context appContext = context.getApplicationContext();
        UmlInstanceBudget.configureDefault(
                () -> {
                    HostHardwareProfile profile = HostHardwareDetector.detect(appContext);
                    return new UmlInstanceBudget.HostMemorySnapshot(
                            profile.totalRamMb(), profile.availRamMb(), profile.isLowMemory());
                },
                UmlFaceStatusRegistry::runningMemMb,
                UmlInstanceBudget.BudgetPolicy.defaults());
        PROCESS_BUDGET_CONFIGURED.set(true);
    }

    public UmlInstanceLauncher(Context context) {
        this(context, null);
    }

    public UmlInstanceLauncher(Context context, InstanceLifecycle.FileStore instanceStore) {
        this(context, instanceStore, true);
    }

    /** Isolated device-smoke entry using the W3-01 disposable root and the production network. */
    static UmlInstanceLauncher forEphemeralQa(Context context) {
        if (!BuildConfig.IS_ISOLATED_QA_BUILD) {
            throw new IllegalStateException("disposable UML boot requires an isolated QA build");
        }
        return new UmlInstanceLauncher(context, null, false);
    }

    private UmlInstanceLauncher(
            Context context, InstanceLifecycle.FileStore instanceStore, boolean persistentVolumeMode) {
        Context appContext = Objects.requireNonNull(context, "context").getApplicationContext();
        if (PROCESS_BUDGET_CONFIGURED.compareAndSet(false, true)) {
            configureProcessBudget(appContext);
        }
        this.context = appContext;
        this.executableResolver = soName -> NativeExecutableStore.ensure(appContext, soName);
        this.filesDir = appContext.getFilesDir();
        this.processFactory = new UmlNativePtyBridge();
        this.reservationRegistry = UmlRootReservationRegistry.defaultRegistry();
        this.instanceBudget = UmlInstanceBudget.defaultBudget();
        this.endpointRegistry = LoopbackEndpointRegistry.defaultRegistry();
        this.tarSource = () -> PackagedAssetStreams.openResource(appContext, ALPINE_MINIROOTFS_RESOURCE);
        this.monotonicNanos = SystemClock::elapsedRealtimeNanos;
        this.stopGraceMs = DEFAULT_STOP_GRACE_MS;
        this.forceWaitMs = UmlRuntimeSupervisor.DEFAULT_FORCE_WAIT_MS;
        this.channelsCloseTimeoutMs = UmlRuntimeSupervisor.DEFAULT_CHANNELS_CLOSE_TIMEOUT_MS;
        this.persistentVolumeMode = persistentVolumeMode;
        this.clock = Clock.systemUTC();
        this.instanceStore = instanceStore;
        this.instanceWriteLock = instanceStore != null ? instanceStore.stateMonitor() : new Object();
        UmlInstanceRootfsProvisioner.ToolchainStager toolchainStager = stageDir -> {
            try (InputStream initramfs =
                    PackagedAssetStreams.openResource(appContext, QEMU_BASE_INITRAMFS_RESOURCE)) {
                if (initramfs == null) {
                    throw new IOException("base initramfs resource unavailable for mkfs.ext4");
                }
                CpioNewcToolExtractor.extractSelected(
                        initramfs,
                        stageDir,
                        PROVISION_TOOLCHAIN_ENTRIES,
                        PROVISION_TOOLCHAIN_EXECS);
            }
        };
        this.instanceProvisioner = new UmlInstanceRootfsProvisioner(
                this.processFactory,
                this.reservationRegistry,
                toolchainStager,
                new StorageContract.DefaultStorageSpaceChecker(),
                this.clock);
        this.isolatedQaChannelAllowed = () -> BuildConfig.IS_ISOLATED_QA_BUILD;
        this.preflightEvaluator = (profile, distroId, requireRootfsSeed) ->
                StandaloneUmlPreflightProbe.forAndroid(
                                appContext, profile, distroId, requireRootfsSeed)
                        .probe();
        this.seedInputSupplier = (parameters, distroId) ->
                resolveProductionSeed(
                        appContext,
                        filesDir.toPath(),
                        UmlDistroCapabilityCatalog.resolveRuntimeProfile(
                                distroId, resolveKernelProfile(parameters)));
    }

    /** Pure-JVM injectable constructor preserving W3-01 disposable HOSTFS contract tests. */
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
        this(
                nativeLibraryDir,
                filesDir,
                processFactory,
                reservationRegistry,
                permissiveJvmBudget(),
                new LoopbackEndpointRegistry(),
                tarSource,
                monotonicNanos,
                stopGraceMs,
                forceWaitMs,
                channelsCloseTimeoutMs);
    }

    /** Pure-JVM injectable constructor for W3-04 multi-instance disposable HOSTFS tests. */
    public UmlInstanceLauncher(
            File nativeLibraryDir,
            File filesDir,
            UmlProcessFactory processFactory,
            UmlRootReservationRegistry reservationRegistry,
            UmlInstanceBudget instanceBudget,
            LoopbackEndpointRegistry endpointRegistry,
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
        this.instanceBudget = Objects.requireNonNull(instanceBudget, "instanceBudget");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.tarSource = Objects.requireNonNull(tarSource, "tarSource");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.stopGraceMs = Math.max(0L, stopGraceMs);
        this.forceWaitMs = Math.max(0L, forceWaitMs);
        this.channelsCloseTimeoutMs = Math.max(0L, channelsCloseTimeoutMs);
        this.persistentVolumeMode = false;
        this.instanceProvisioner = null;
        this.seedInputSupplier = null;
        this.instanceWriteLock = new Object();
        this.instanceStore = null;
        this.clock = Clock.systemUTC();
        this.isolatedQaChannelAllowed = () -> true;
        this.preflightEvaluator = null;
    }

    /** Pure-JVM injectable constructor for W3-02 persistent ext4 ubd0 contract tests. */
    public UmlInstanceLauncher(
            File nativeLibraryDir,
            File filesDir,
            UmlProcessFactory processFactory,
            UmlRootReservationRegistry reservationRegistry,
            UmlInstanceRootfsProvisioner instanceProvisioner,
            SeedInputSupplier seedInputSupplier,
            Object instanceWriteLock,
            InstanceLifecycle.FileStore instanceStore,
            Clock clock,
            LongSupplier monotonicNanos,
            long stopGraceMs,
            long forceWaitMs,
            long channelsCloseTimeoutMs) {
        this(
                nativeLibraryDir,
                filesDir,
                processFactory,
                reservationRegistry,
                permissiveJvmBudget(),
                new LoopbackEndpointRegistry(),
                instanceProvisioner,
                seedInputSupplier,
                instanceWriteLock,
                instanceStore,
                clock,
                monotonicNanos,
                stopGraceMs,
                forceWaitMs,
                channelsCloseTimeoutMs);
    }

    /** Pure-JVM injectable constructor for W3-04 multi-instance persistent ext4 tests. */
    public UmlInstanceLauncher(
            File nativeLibraryDir,
            File filesDir,
            UmlProcessFactory processFactory,
            UmlRootReservationRegistry reservationRegistry,
            UmlInstanceBudget instanceBudget,
            LoopbackEndpointRegistry endpointRegistry,
            UmlInstanceRootfsProvisioner instanceProvisioner,
            SeedInputSupplier seedInputSupplier,
            Object instanceWriteLock,
            InstanceLifecycle.FileStore instanceStore,
            Clock clock,
            LongSupplier monotonicNanos,
            long stopGraceMs,
            long forceWaitMs,
            long channelsCloseTimeoutMs) {
        this(
                nativeLibraryDir,
                filesDir,
                processFactory,
                reservationRegistry,
                instanceBudget,
                endpointRegistry,
                instanceProvisioner,
                seedInputSupplier,
                instanceWriteLock,
                instanceStore,
                clock,
                monotonicNanos,
                stopGraceMs,
                forceWaitMs,
                channelsCloseTimeoutMs,
                () -> true,
                null);
    }

    /** Pure-JVM injectable constructor with explicit channel gate and profile preflight evaluator. */
    public UmlInstanceLauncher(
            File nativeLibraryDir,
            File filesDir,
            UmlProcessFactory processFactory,
            UmlRootReservationRegistry reservationRegistry,
            UmlInstanceBudget instanceBudget,
            LoopbackEndpointRegistry endpointRegistry,
            UmlInstanceRootfsProvisioner instanceProvisioner,
            SeedInputSupplier seedInputSupplier,
            Object instanceWriteLock,
            InstanceLifecycle.FileStore instanceStore,
            Clock clock,
            LongSupplier monotonicNanos,
            long stopGraceMs,
            long forceWaitMs,
            long channelsCloseTimeoutMs,
            BooleanSupplier isolatedQaChannelAllowed,
            ProfilePreflightEvaluator preflightEvaluator) {
        Objects.requireNonNull(nativeLibraryDir, "nativeLibraryDir");
        this.context = null;
        this.executableResolver = soName -> new File(nativeLibraryDir, soName).getAbsolutePath();
        this.filesDir = Objects.requireNonNull(filesDir, "filesDir");
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory");
        this.reservationRegistry = Objects.requireNonNull(reservationRegistry, "reservationRegistry");
        this.instanceBudget = Objects.requireNonNull(instanceBudget, "instanceBudget");
        this.endpointRegistry = Objects.requireNonNull(endpointRegistry, "endpointRegistry");
        this.tarSource = null;
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.stopGraceMs = Math.max(0L, stopGraceMs);
        this.forceWaitMs = Math.max(0L, forceWaitMs);
        this.channelsCloseTimeoutMs = Math.max(0L, channelsCloseTimeoutMs);
        this.persistentVolumeMode = true;
        this.instanceProvisioner = Objects.requireNonNull(instanceProvisioner, "instanceProvisioner");
        this.seedInputSupplier = Objects.requireNonNull(seedInputSupplier, "seedInputSupplier");
        this.instanceWriteLock = instanceWriteLock != null ? instanceWriteLock : new Object();
        this.instanceStore = instanceStore;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.isolatedQaChannelAllowed =
                Objects.requireNonNull(isolatedQaChannelAllowed, "isolatedQaChannelAllowed");
        this.preflightEvaluator = preflightEvaluator;
    }

    private static UmlInstanceBudget permissiveJvmBudget() {
        return new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(65536, 65536, false),
                () -> 0,
                UmlInstanceBudget.BudgetPolicy.defaults());
    }

    /**
     * Consumes the cleanup outcome of the most recent failed launch for {@code instanceId},
     * or {@code null} when none was recorded.
     */
    public static RuntimeProcessSupervisor.StopResult takeLastCleanupResult(String instanceId) {
        return instanceId == null ? null : LAST_FAILED_CLEANUP.remove(instanceId);
    }

    /**
     * Verifies that all process-wide standalone UML leases (root reservation, host RAM budget,
     * and loopback forwarding endpoints) have been released for {@code instanceId}.
     */
    public static boolean areLeasesReleased(String instanceId, Path rootVolume) {
        boolean rootBusy = rootVolume != null
                && UmlRootReservationRegistry.defaultRegistry().isReserved(rootVolume);
        boolean memBusy = instanceId != null
                && UmlInstanceBudget.defaultBudget().hasActiveLease(instanceId);
        boolean portBusy = instanceId != null
                && LoopbackEndpointRegistry.defaultRegistry().hasActiveEndpointForInstance(instanceId);
        return !rootBusy && !memBusy && !portBusy;
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
        Path bootCredsDir = null;
        UmlProcessHandle spawnedHandle = null;
        ParcelFileDescriptor spawnedNetworkSocket = null;
        UmlPasstNetwork spawnedNetwork = null;
        UmlInstanceBudget.MemoryLease memoryLease = null;
        Session registeredSession = null;
        boolean retainReservationOnUnconfirmedStop = false;
        try {
            int requestedMemMb = Math.max(UmlLaunchSpec.MIN_MEMORY_MB, config.ramMegabytes());
            memoryLease = instanceBudget.acquire(parameters.instanceId, generation, requestedMemMb);
            baseStageTrace.onGate(ProductionStageRecorder.Stage.INPUTS_BEGIN);
            String requestedDistroId = resolveDistroIdOrNull(parameters.rootfsFileName);
            Files.createDirectories(runtimeDir.resolve("tmp"));

            final UmlLaunchSpec spec;
            if (persistentVolumeMode) {
                UmlBootPlan plan = resolvePersistentBootPlan(
                        parameters, requestedDistroId, rootVolumePath);
                ResolvedLaunchBinaries binaries = verifyAndResolveBinaries(
                        parameters, plan.runtimeProfile(), plan.requireRootfsSeed());
                UmlDistroCapabilityCatalog.KernelProfile kernelProfile =
                        binaries.runtimeProfile().profileDescriptor().profile();

                if (plan.bootMode() == UmlBootPlan.BootMode.FIRST_PROVISION) {
                    timeline.markCredentialsOnly(LaunchTimeline.CredentialsOnlyOutcome.FIRST_PROVISION);
                    long provisionStartNanos = monotonicNanos.getAsLong();
                    UmlInstanceRootfsProvisioner.SeedInput seedInput =
                            seedInputSupplier.supply(parameters, binaries.runtimeProfile().distroId());
                    long requestedBytes = Math.max(
                            StorageContract.MIN_ROOT_VOLUME_BYTES,
                            config.diskCapacityBytes() > 0L
                                    ? config.diskCapacityBytes()
                                    : StorageContract.DEFAULT_ROOT_VOLUME_BYTES);
                    long provisionTimeoutMs = binaries.runtimeProfile().provisionTimeoutMs();
                    UmlInstanceRootfsProvisioner.ProvisionRequest provRequest =
                            new UmlInstanceRootfsProvisioner.ProvisionRequest(
                                    parameters.instanceId,
                                    generation,
                                    rootVolumePath,
                                    requestedBytes,
                                    binaries.hostname(),
                                    seedInput,
                                    UmlInstanceRootfsProvisioner.ProvisionMode.CREATE_NEW,
                                    null,
                                    binaries.kernelPath(),
                                    binaries.stubExePath(),
                                    runtimeDir,
                                    instanceWriteLock,
                                    (id, gen) -> !InstanceLaunchGuard.isSuperseded(id, gen),
                                    receipt -> {
                                        if (instanceStore != null) {
                                            InstanceLifecycle.Instance current =
                                                    instanceStore.load(parameters.instanceId);
                                            InstanceLifecycle.Controller ctrl =
                                                    new InstanceLifecycle.Controller(
                                                            instanceStore, clock);
                                            ctrl.recordProvisioning(
                                                    current.id(),
                                                    new InstanceLifecycle.ProvisioningEvidence(
                                                            InstanceLifecycle
                                                                    .PROVISIONING_MARKER_VERSION,
                                                            receipt.rootfsSha256(),
                                                            receipt.provisionedAtMillis()));
                                        }
                                    },
                                    provisionTimeoutMs,
                                    stopGraceMs,
                                    forceWaitMs,
                                    channelsCloseTimeoutMs,
                                    kernelProfile);
                    try {
                        instanceProvisioner.provisionWithHeldReservation(provRequest, reservation);
                        timeline.notePhase(
                                "uml-provision",
                                Math.max(0L, (monotonicNanos.getAsLong() - provisionStartNanos) / 1_000_000L));
                    } catch (UmlInstanceRootfsProvisioner.ProvisionException provFailure) {
                        if (UmlInstanceRootfsProvisioner.REASON_GUEST_STOP_UNCONFIRMED.equals(
                                provFailure.reason())) {
                            retainReservationOnUnconfirmedStop = true;
                            LAST_FAILED_CLEANUP.put(
                                    parameters.instanceId,
                                    RuntimeProcessSupervisor.StopResult.UNCONFIRMED);
                            timeline.markStopUnconfirmed();
                        }
                        throw provFailure;
                    }
                } else {
                    timeline.markCredentialsOnly(LaunchTimeline.CredentialsOnlyOutcome.HIT);
                }

                bootCredsDir = prepareBootCredsDir(
                        runtimeDir, generation, parameters.bridgeToken, binaries.hostname());
                baseStageTrace.onGate(ProductionStageRecorder.Stage.INPUTS_VERIFIED);
                timeline.mark(LaunchTimeline.Stage.ASSETS_VERIFIED);

                spec = UmlLaunchSpec.builder()
                        .kernelPath(binaries.kernelPath())
                        .stubExePath(binaries.stubExePath())
                        .memMb(requestedMemMb)
                        .rootMode(UmlLaunchSpec.RootMode.EXT4_UBD)
                        .ubd0Path(rootVolumePath)
                        .umInitPath(binaries.runtimeProfile().nativeInitPath())
                        .credsHostfsDir(bootCredsDir)
                        .runtimeDir(runtimeDir)
                        .extraArgs(List.of("console=tty0"))
                        .build();
            } else {
                String distroId = requestedDistroId != null ? requestedDistroId : "alpine";
                UmlDistroCapabilityCatalog.KernelProfile kernelProfile = resolveKernelProfile(parameters);
                UmlDistroRuntimeProfile runtimeProfile =
                        UmlDistroCapabilityCatalog.resolveRuntimeProfile(distroId, kernelProfile);
                ResolvedLaunchBinaries binaries =
                        verifyAndResolveBinaries(parameters, runtimeProfile, false);
                ephemeralRootDir = UmlEphemeralRootFixture.prepare(
                        tarSource, runtimeDir, generation, parameters.bridgeToken, binaries.hostname());
                baseStageTrace.onGate(ProductionStageRecorder.Stage.INPUTS_VERIFIED);
                timeline.mark(LaunchTimeline.Stage.ASSETS_VERIFIED);

                spec = UmlLaunchSpec.builder()
                        .kernelPath(binaries.kernelPath())
                        .stubExePath(binaries.stubExePath())
                        .memMb(requestedMemMb)
                        .ephemeralRootDir(ephemeralRootDir)
                        .runtimeDir(runtimeDir)
                        .extraArgs(List.of("console=tty0"))
                        .build();
            }

            baseStageTrace.onGate(ProductionStageRecorder.Stage.RUN_BEGIN);
            UmlProcessHandle handle;
            UmlPasstNetwork network = null;
            if (processFactory instanceof UmlNativePtyBridge androidFactory) {
                handle = androidFactory.spawnNetworked(spec, INITIAL_PTY_COLUMNS, INITIAL_PTY_ROWS);
                spawnedHandle = handle;
                try {
                    spawnedNetworkSocket = UmlNativePtyBridge.duplicateNetworkSocket(handle);
                } catch (IOException ignored) {
                    // The optional network owns its own diagnostic and cannot block the shell.
                }
                network = new UmlPasstNetwork(
                        context, runtimeDir, parameters.instanceId, generation,
                        parameters.bridgeToken, spawnedNetworkSocket, endpointRegistry);
                spawnedNetworkSocket = null;
                spawnedNetwork = network;
            } else {
                handle = processFactory.spawn(spec, INITIAL_PTY_COLUMNS, INITIAL_PTY_ROWS);
                spawnedHandle = handle;
            }
            readinessTracker.markProcessStarted(generation, handle.pid(), monotonicNanos.getAsLong());
            timeline.mark(LaunchTimeline.Stage.UML_STARTED);
            baseStageTrace.onGate(ProductionStageRecorder.Stage.RUN_RETURNED);

            Path sessionTempDir = persistentVolumeMode ? bootCredsDir : ephemeralRootDir;
            Session session = new Session(
                    parameters.instanceId,
                    generation,
                    handle,
                    network,
                    sessionTempDir,
                    reservationRegistry,
                    reservation,
                    instanceBudget,
                    memoryLease,
                    readinessTracker,
                    timeline,
                    baseStageTrace,
                    monotonicNanos,
                    forceWaitMs,
                    channelsCloseTimeoutMs,
                    DEFAULT_TERMINATE_GRACE_MS,
                    DEFAULT_CLOSE_GRACE_MS);
            registeredSession = session;

            try {
                if (network != null) network.restoreBeforePublish();
                ExecutionSessionRegistry.register(parameters.instanceId, session);
                timeline.mark(LaunchTimeline.Stage.SESSION_REGISTERED);
                return session;
            } catch (IOException | RuntimeException | Error registrationFailure) {
                baseStageTrace.onFailure(ProductionStageRecorder.Failure.UNKNOWN);
                UmlRuntimeSupervisor.StopEvidence cleanup = session.supervisor().cancelLaunch(stopGraceMs);
                RuntimeProcessSupervisor.StopResult mapped = mapStopResult(cleanup.result());
                if (mapped == RuntimeProcessSupervisor.StopResult.UNCONFIRMED) {
                    retainReservationOnUnconfirmedStop = true;
                }
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
            if (registeredSession == null && spawnedHandle != null) {
                if (spawnedNetworkSocket != null) {
                    try {
                        spawnedNetworkSocket.close();
                    } catch (IOException ignored) {
                        // Best-effort FD release on launch failure.
                    }
                }
                if (spawnedNetwork != null) {
                    try {
                        spawnedNetwork.closeAfterGuestExit();
                    } catch (RuntimeException unconfirmedNet) {
                        retainReservationOnUnconfirmedStop = true;
                        failure.addSuppressed(unconfirmedNet);
                    }
                }
                spawnedHandle.destroyForcibly();
                boolean reaped = false;
                try {
                    reaped = spawnedHandle.waitFor(forceWaitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                spawnedHandle.closeChannels();
                if (!reaped) {
                    retainReservationOnUnconfirmedStop = true;
                }
            }
            if (!LAST_FAILED_CLEANUP.containsKey(parameters.instanceId)) {
                readinessTracker.markBridgeRejected(
                        generation,
                        UmlReadinessTracker.RejectionReason.LAUNCH_FAILED,
                        monotonicNanos.getAsLong());
                baseStageTrace.onFailure(ProductionStageRecorder.Failure.CREATE_FAILED);
                if (!retainReservationOnUnconfirmedStop && memoryLease != null) {
                    instanceBudget.release(memoryLease);
                }
                RuntimeProcessSupervisor.StopResult preSpawnCleanup = retainReservationOnUnconfirmedStop
                        ? RuntimeProcessSupervisor.StopResult.UNCONFIRMED
                        : cleanUpBeforeSpawn(
                                ephemeralRootDir != null ? ephemeralRootDir : bootCredsDir,
                                reservationRegistry,
                                reservation);
                LAST_FAILED_CLEANUP.put(parameters.instanceId, preSpawnCleanup);
                if (preSpawnCleanup != RuntimeProcessSupervisor.StopResult.UNCONFIRMED) {
                    timeline.finish();
                }
            }
            throw failure;
        }
    }

    private UmlBootPlan resolvePersistentBootPlan(
            InstanceStrategyLauncher.LaunchParameters parameters,
            String distroId,
            Path rootVolumePath)
            throws IOException {
        UmlBootPlanResolver.PersistedMetadata persisted = loadPersistedMetadata(parameters.instanceId);
        UmlVolumeInspector.VolumeInspection volInspection =
                UmlVolumeInspector.inspect(rootVolumePath);
        UmlBootPlan plan = UmlBootPlanResolver.resolve(
                new UmlBootPlanResolver.RequestInputs(
                        distroId, parameters.kernelProfile, rootVolumePath),
                persisted,
                volInspection,
                isolatedQaChannelAllowed.getAsBoolean());
        if (!plan.isAllowed()) {
            if (plan.refusalCode() == UmlBootPlan.RefusalCode.DISTRO_UNSUPPORTED_ON_PROFILE
                    && plan.runtimeProfile() != null) {
                throw new UmlDistroCapabilityCatalog.DistroNotCreatableException(
                        plan.runtimeProfile().verdict());
            }
            if (plan.refusalCode() == UmlBootPlan.RefusalCode.MISSING_ASSEMBLED_ROOT_VOLUME
                    || plan.refusalCode() == UmlBootPlan.RefusalCode.INVALID_ROOT_VOLUME) {
                throw new UmlVolumeRecoveryException(plan);
            }
            throw new UmlLaunchAdmissionException(plan);
        }
        return plan;
    }

    private UmlBootPlanResolver.PersistedMetadata loadPersistedMetadata(String instanceId) {
        if (instanceStore == null) {
            return UmlBootPlanResolver.PersistedMetadata.absent();
        }
        try {
            InstanceLifecycle.Instance loaded = instanceStore.load(instanceId);
            return new UmlBootPlanResolver.PersistedMetadata(
                    true,
                    loaded.distro().id(),
                    loaded.kernelProfile(),
                    loaded.rootVolume(),
                    loaded.executionIdentity(),
                    loaded.provisioningEvidence() != null);
        } catch (java.nio.file.NoSuchFileException | java.io.FileNotFoundException missing) {
            return UmlBootPlanResolver.PersistedMetadata.absent();
        } catch (IOException ioFailure) {
            if (ioFailure.getMessage() != null
                    && ioFailure.getMessage().contains("instance metadata not found")) {
                return UmlBootPlanResolver.PersistedMetadata.absent();
            }
            throw new UncheckedIOException(ioFailure);
        }
    }

    private record ResolvedLaunchBinaries(
            UmlDistroRuntimeProfile runtimeProfile,
            String kernelPath,
            String stubExePath,
            String hostname) {}

    private ResolvedLaunchBinaries verifyAndResolveBinaries(
            InstanceStrategyLauncher.LaunchParameters parameters,
            UmlDistroRuntimeProfile runtimeProfile,
            boolean requireRootfsSeed)
            throws IOException {
        verifyFullProfilePreflight(runtimeProfile, requireRootfsSeed);
        UmlKernelPayloadContract.ProfileDescriptor profileDescriptor =
                runtimeProfile.profileDescriptor();
        String kernelPath = resolveProfileBinary(
                profileDescriptor,
                profileDescriptor.kernelLibName(),
                UmlKernelPayloadContract.FailureCode.MISSING_PAYLOAD);
        String stubExePath = resolveProfileBinary(
                profileDescriptor,
                profileDescriptor.stubLibName(),
                UmlKernelPayloadContract.FailureCode.MISSING_STUB);
        String hostname = (parameters.hostname != null && !parameters.hostname.isBlank())
                ? parameters.hostname
                : runtimeProfile.defaultQaHostname();
        return new ResolvedLaunchBinaries(runtimeProfile, kernelPath, stubExePath, hostname);
    }

    private void verifyFullProfilePreflight(
            UmlDistroRuntimeProfile runtimeProfile, boolean requireRootfsSeed) throws IOException {
        UmlDistroCapabilityCatalog.KernelProfile kernelProfile =
                runtimeProfile.profileDescriptor().profile();
        if (runtimeProfile.requiresIsolatedQaChannel() && !isolatedQaChannelAllowed.getAsBoolean()) {
            throw new UmlLaunchAdmissionException(
                    new UmlBootPlan(
                            UmlBootPlan.Status.REFUSED,
                            UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                            null,
                            UmlBootPlan.RefusalCode.CHANNEL_NOT_ALLOWED,
                            runtimeProfile,
                            "kernel profile "
                                    + runtimeProfile.profileDescriptor().profileId()
                                    + " is restricted to isolated QA builds",
                            "完整档内核仅允许在隔离 QA 通道运行，已保留实例元数据与原盘。"));
        }
        if (kernelProfile == UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64
                && preflightEvaluator != null) {
            ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight profileCheck =
                    preflightEvaluator.evaluate(
                            kernelProfile, runtimeProfile.distroId(), requireRootfsSeed);
            if (profileCheck.status()
                    != ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY) {
                if (profileCheck.status()
                        == ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.UNAVAILABLE) {
                    throw new UmlLaunchAdmissionException(
                            new UmlBootPlan(
                                    UmlBootPlan.Status.REFUSED,
                                    UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                                    null,
                                    UmlBootPlan.RefusalCode.CHANNEL_NOT_ALLOWED,
                                    runtimeProfile,
                                    profileCheck.detail(),
                                    "完整档内核仅允许在隔离 QA 通道运行，已保留实例元数据与原盘。"));
                }
                if (profileCheck.status()
                        == ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.ASSETS_MISSING) {
                    throw new UmlPayloadVerificationException(profileCheck.detail());
                }
                throw new IOException(profileCheck.detail());
            }
        }
    }

    private String resolveProfileBinary(
            UmlKernelPayloadContract.ProfileDescriptor profileDescriptor,
            String libraryName,
            UmlKernelPayloadContract.FailureCode missingCode)
            throws IOException {
        final String resolved;
        try {
            resolved = executableResolver.resolve(libraryName);
        } catch (IOException failure) {
            if (profileDescriptor.profile() == UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64) {
                throw new UmlPayloadVerificationException(
                        "[" + missingCode + "] missing full-profile UML payload: " + libraryName,
                        failure);
            }
            throw failure;
        }
        if (profileDescriptor.profile() == UmlDistroCapabilityCatalog.KernelProfile.FULL_SMP_ARM64
                && (resolved == null || !Files.isRegularFile(Paths.get(resolved)))) {
            throw new UmlPayloadVerificationException(
                    "[" + missingCode + "] missing full-profile UML payload: " + libraryName);
        }
        return resolved;
    }

    private UmlDistroCapabilityCatalog.KernelProfile resolveKernelProfile(
            InstanceStrategyLauncher.LaunchParameters parameters) {
        String explicitProfile =
                (parameters.kernelProfile != null && !parameters.kernelProfile.isBlank())
                        ? parameters.kernelProfile.trim()
                        : null;
        String persistedProfile = null;
        if (instanceStore != null) {
            try {
                String loaded = instanceStore.load(parameters.instanceId).kernelProfile();
                if (loaded != null && !loaded.isBlank()) {
                    persistedProfile = loaded.trim();
                }
            } catch (java.nio.file.NoSuchFileException | java.io.FileNotFoundException missing) {
                persistedProfile = null;
            } catch (IOException ioFailure) {
                if (ioFailure.getMessage() != null
                        && ioFailure.getMessage().contains("instance metadata not found")) {
                    persistedProfile = null;
                } else {
                    throw new UncheckedIOException(ioFailure);
                }
            }
        }
        if (explicitProfile != null
                && persistedProfile != null
                && !explicitProfile.equals(persistedProfile)) {
            throw new UmlLaunchAdmissionException(
                    new UmlBootPlan(
                            UmlBootPlan.Status.REFUSED,
                            UmlBootPlan.ResolutionKind.REFUSED_DETERMINISTIC,
                            null,
                            UmlBootPlan.RefusalCode.CONFIG_MISMATCH,
                            null,
                            "Conflicting kernelProfile for instance "
                                    + parameters.instanceId
                                    + ": request="
                                    + explicitProfile
                                    + ", persisted="
                                    + persistedProfile,
                            ""));
        }
        String effective = explicitProfile != null ? explicitProfile : persistedProfile;
        if (effective == null || effective.isBlank()) {
            return UmlDistroCapabilityCatalog.KernelProfile.FAST_UP_ARM64;
        }
        try {
            return UmlKernelPayloadContract.fromProfileId(effective);
        } catch (IllegalArgumentException unknown) {
            throw new UmlLaunchAdmissionException(
                    new UmlBootPlan(
                            UmlBootPlan.Status.REFUSED,
                            UmlBootPlan.ResolutionKind.REFUSED_RECOVERY_REQUIRED,
                            null,
                            UmlBootPlan.RefusalCode.UNKNOWN_KERNEL_PROFILE,
                            null,
                            unknown.getMessage(),
                            "实例配置了当前版本不支持的内核档位（" + effective + "），已保留元数据与原盘。"));
        }
    }

    private UmlInstanceRootfsProvisioner.SeedInput resolveProductionSeed(
            Context appContext,
            Path filesDir,
            UmlDistroRuntimeProfile runtimeProfile) throws IOException {
        UmlDistroCapabilityCatalog.DistroGateVerdict verdict = runtimeProfile.verdict();
        if (!verdict.isCreatable()) {
            throw new UmlDistroCapabilityCatalog.DistroNotCreatableException(verdict);
        }
        if (!"alpine".equals(verdict.distroId())) {
            UmlKernelAssets.StagedSeedProbe probe =
                    UmlKernelAssets.probeStagedDistroSeed(filesDir, verdict);
            if (probe.status() == UmlKernelAssets.StagedSeedStatus.NOT_STAGED) {
                throw new UmlInstanceRootfsProvisioner.ProvisionException(
                        UmlInstanceRootfsProvisioner.REASON_SEED_UNVERIFIED,
                        verdict.distroId()
                                + " rootfs tarball missing from uml-rootfs and distro-inputs: "
                                + verdict.expectedFileName());
            }
            if (probe.status() == UmlKernelAssets.StagedSeedStatus.SIZE_MISMATCH) {
                throw new UmlInstanceRootfsProvisioner.ProvisionException(
                        UmlInstanceRootfsProvisioner.REASON_PARTIAL_INPUT,
                        verdict.distroId()
                                + " rootfs size mismatch: expected="
                                + verdict.expectedSizeBytes()
                                + ", actual="
                                + probe.actualSizeBytes());
            }
            if (probe.status() == UmlKernelAssets.StagedSeedStatus.CHECKSUM_MISMATCH) {
                throw new UmlInstanceRootfsProvisioner.ProvisionException(
                        UmlInstanceRootfsProvisioner.REASON_CHECKSUM_MISMATCH,
                        verdict.distroId()
                                + " rootfs digest mismatch: expected="
                                + verdict.expectedSha256()
                                + ", actual="
                                + probe.actualSha256());
            }
            return new UmlInstanceRootfsProvisioner.SeedInput(
                    verdict.distroId(),
                    verdict.version(),
                    probe.file(),
                    null,
                    null,
                    verdict.expectedSha256(),
                    verdict.expectedSizeBytes());
        }
        UmlKernelAssets.RootfsDownload.Entry entry =
                UmlKernelAssets.RootfsDownload.Entry.alpineMinirootfsAarch64();
        if (verdict.expectedSha256() != null
                && (!entry.sha256Pin().equalsIgnoreCase(verdict.expectedSha256())
                        || (verdict.expectedSizeBytes() > 0L
                                && entry.sizeBytes() != verdict.expectedSizeBytes()))) {
            throw new UmlPayloadVerificationException(
                    "[" + UmlKernelPayloadContract.FailureCode.SHA256_MISMATCH
                            + "] UML rootfs pin drift between UmlKernelAssets and capability catalog");
        }
        UmlRootfsProvisioner.ProvisionReceipt receipt = UmlRootfsProvisioner.provision(
                new ResilientDownloadEngine(),
                entry,
                filesDir,
                BuildConfig.UML_ROOTFS_DOWNLOAD_ENABLED,
                e -> PackagedAssetStreams.openResource(appContext, e.fileName()));
        if (!receipt.enabled()) {
            throw new IOException("UML rootfs provisioning is disabled by build flag");
        }
        Path sidecar = receipt.extractedDir().resolveSibling(
                receipt.extractedDir().getFileName() + ".verified");
        return new UmlInstanceRootfsProvisioner.SeedInput(
                verdict.distroId(),
                verdict.version(),
                receipt.file(),
                receipt.extractedDir(),
                sidecar,
                entry.sha256Pin(),
                entry.sizeBytes());
    }

    private static Path prepareBootCredsDir(
            Path runtimeDir, String generation, String bridgeToken, String hostname)
            throws IOException {
        String safeGen = generation.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeGen.isEmpty() || ".".equals(safeGen) || "..".equals(safeGen)) {
            safeGen = "gen";
        }
        Path credsDir = runtimeDir.toAbsolutePath().normalize().resolve("boot-creds-" + safeGen);
        deleteTempTree(credsDir);
        Files.createDirectories(credsDir);
        applyDirModeBestEffort(credsDir, 0700);
        Path tokenFile = credsDir.resolve("bridge.token");
        Files.write(
                tokenFile,
                (bridgeToken + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        tokenFile.toFile().setReadable(true, true);
        applyFileModeBestEffort(tokenFile, 0600);
        String safeHost = (hostname == null || hostname.isBlank())
                ? DEFAULT_QA_HOSTNAME
                : hostname.replaceAll("[^A-Za-z0-9-]", "");
        if (safeHost.isEmpty()) {
            safeHost = DEFAULT_QA_HOSTNAME;
        }
        Path hostFile = credsDir.resolve("hostname");
        Files.write(
                hostFile,
                (safeHost + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        hostFile.toFile().setReadable(true, false);
        applyFileModeBestEffort(hostFile, 0644);
        return credsDir;
    }

    private static void applyDirModeBestEffort(Path target, int mode) {
        applyFileModeBestEffort(target, mode);
    }

    private static void applyFileModeBestEffort(Path target, int mode) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & 0400) != 0) perms.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) perms.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) perms.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) perms.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(target, perms);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void deleteTempTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path tokenFile = dir.resolve("bridge.token");
        if (Files.isRegularFile(tokenFile, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.write(tokenFile, new byte[65], StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ignored) {
            }
        }
        List<Path> entries;
        try (var walk = Files.walk(dir)) {
            entries = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
        }
        for (Path entry : entries) {
            Files.deleteIfExists(entry);
        }
    }

    private static RuntimeProcessSupervisor.StopResult cleanUpBeforeSpawn(
            Path ephemeralRootDir,
            UmlRootReservationRegistry registry,
            UmlRootReservationRegistry.Reservation reservation) {
        boolean rootCleaned = true;
        if (ephemeralRootDir != null) {
            try {
                deleteTempTree(ephemeralRootDir);
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

    static String resolveDistroIdOrNull(String rootfsFileName) {
        if (rootfsFileName == null || rootfsFileName.isBlank()) {
            return null;
        }
        if (ALPINE_MINIROOTFS_RESOURCE.equals(rootfsFileName) || rootfsFileName.startsWith("alpine-")) {
            return "alpine";
        }
        if ("debian".equalsIgnoreCase(rootfsFileName.trim())
                || rootfsFileName.toLowerCase(Locale.ROOT).startsWith("debian-")) {
            return "debian";
        }
        return QemuLaunchEvidence.resolveDistroId(rootfsFileName);
    }

    static String resolveDistroId(String rootfsFileName) {
        String resolved = resolveDistroIdOrNull(rootfsFileName);
        return resolved != null ? resolved : "alpine";
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
        private final UmlPasstNetwork network;
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
        private final AtomicBoolean systemdReadyMarked = new AtomicBoolean(false);
        private final java.util.concurrent.CountDownLatch powerDownLatch =
                new java.util.concurrent.CountDownLatch(1);
        private volatile boolean kernelPowerDownObserved;
        private BridgeClient bridgeClient;
        private volatile BridgeControl.Clipboard clipboardBridge;

        Session(
                String instanceId,
                String generation,
                UmlProcessHandle handle,
                UmlPasstNetwork network,
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
            this(
                    instanceId,
                    generation,
                    handle,
                    network,
                    ephemeralRootDir,
                    reservationRegistry,
                    reservation,
                    null,
                    null,
                    readinessTracker,
                    timeline,
                    baseStageTrace,
                    monotonicNanos,
                    forceWaitMs,
                    channelsCloseTimeoutMs,
                    terminateGraceMs,
                    closeGraceMs);
        }

        Session(
                String instanceId,
                String generation,
                UmlProcessHandle handle,
                UmlPasstNetwork network,
                Path ephemeralRootDir,
                UmlRootReservationRegistry reservationRegistry,
                UmlRootReservationRegistry.Reservation reservation,
                UmlInstanceBudget instanceBudget,
                UmlInstanceBudget.MemoryLease memoryLease,
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
            this.network = network;
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
                    () -> {
                        if (network != null) network.closeAfterGuestExit();
                        try {
                            cleanupEphemeralRoot(ephemeralRootDir);
                        } finally {
                            if (instanceBudget != null && memoryLease != null) {
                                instanceBudget.release(memoryLease);
                            }
                        }
                    },
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
                if (Files.isRegularFile(dir.resolve("bridge.token"), LinkOption.NOFOLLOW_LINKS)) {
                    deleteTempTree(dir);
                } else {
                    UmlEphemeralRootFixture.cleanup(dir);
                }
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

        @Override
        public String launchGeneration() {
            return generation;
        }

        String generation() {
            return generation;
        }

        long guestPid() {
            return handle.pid();
        }

        private boolean isCurrentGeneration() {
            return !InstanceLaunchGuard.isSuperseded(instanceId, generation);
        }

        @Override
        public FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            SessionCapabilities declaration =
                    SessionCapabilities.builder(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL)
                            .ownership(SessionCapabilities.Ownership.RUNTIME_OWNED)
                            .terminal(true)
                            .bridge(true)
                            .portForwarding(portForwardingEngine() != null)
                            .build();
            return FullLinuxContract.CapabilityMatrix.from(
                    declaration, FullLinuxContract.CapabilityMatrix.USER_MODE_KERNEL_ALLOWED_CAPABILITIES);
        }

        @Override
        public PortForwardingEngine.Live portForwardingEngine() {
            return network != null && network.isRunning() ? network : null;
        }

        @Override
        public String networkDiagnostic() {
            UmlPasstNetwork.Status current = networkStatus();
            return current == UmlPasstNetwork.Status.READY
                    ? null
                    : "uml-network-" + current.name().toLowerCase(Locale.ROOT);
        }

        UmlPasstNetwork.Status networkStatus() {
            return network == null ? UmlPasstNetwork.Status.TRANSPORT_UNAVAILABLE : network.status();
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
                boolean sentPoweroff = false;
                try {
                    client.power(BridgeControl.PowerAction.POWEROFF);
                    sentPoweroff = true;
                } catch (IOException | RuntimeException ignored) {
                    // Best-effort guest poweroff before host reap + release.
                }
                if (sentPoweroff) {
                    try {
                        awaitPowerDownOrExit(Math.max(terminateGraceMs, SYSTEMD_POWEROFF_WAIT_MS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
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
            final StringBuilder markerTail = new StringBuilder();
            return new FilterInputStream(raw) {
                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value == -1) {
                        onTerminalEof();
                    } else {
                        observeTerminalMarkerChar((char) (value & 0xFF), markerTail);
                    }
                    return value;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int count = super.read(b, off, len);
                    if (count == -1) {
                        onTerminalEof();
                    } else if (count > 0) {
                        for (int i = 0; i < count; i++) {
                            observeTerminalMarkerChar((char) (b[off + i] & 0xFF), markerTail);
                        }
                    }
                    return count;
                }
            };
        }

        private void observeTerminalMarkerChar(char ch, StringBuilder tail) {
            if (kernelPowerDownObserved && systemdReadyMarked.get()) {
                return;
            }
            tail.append(ch);
            if (tail.length() > 64) {
                tail.delete(0, tail.length() - 64);
            }
            if (!systemdReadyMarked.get()
                    && ch == '\u0007'
                    && tail.indexOf("\u001b]133;O;orchestella=systemd-ready\u0007") >= 0) {
                markSystemdReady();
            }
            if (!kernelPowerDownObserved && (ch == '\n' || ch == '\r')) {
                if (tail.indexOf("reboot: Power down") >= 0) {
                    kernelPowerDownObserved = true;
                    powerDownLatch.countDown();
                }
            }
        }

        boolean awaitPowerDownOrExit(long timeoutMs) throws InterruptedException {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
            while (System.nanoTime() < deadlineNanos) {
                if (!handle.isAlive()) {
                    return true;
                }
                if (kernelPowerDownObserved) {
                    if (handle.isAlive()) {
                        Thread.sleep(150L);
                    }
                    return true;
                }
                long remainingMs = Math.min(50L,
                        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())));
                powerDownLatch.await(remainingMs, TimeUnit.MILLISECONDS);
            }
            return !handle.isAlive() || kernelPowerDownObserved;
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
                        bridgeClient = new BridgeClient(
                                new PreAuthHelloEchoFilterInputStream(controlOutput()),
                                controlInput());
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

        LaunchTimeline launchTimeline() {
            return timeline;
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
            if (network != null) network.start();
            long nowNanos = monotonicNanos.getAsLong();
            boolean firstPrompt = !readinessTracker.snapshot().shellPromptObserved();
            boolean promoted = readinessTracker.markShellReady(generation, nowNanos);
            if (firstPrompt && readinessTracker.snapshot().shellPromptObserved()) {
                long startedMs = timeline.elapsedMs(LaunchTimeline.Stage.UML_STARTED);
                if (startedMs >= 0L) {
                    long deltaSinceProcess = Math.max(
                            0L,
                            (nowNanos - readinessTracker.snapshot().processStartedNanos()) / 1_000_000L);
                    timeline.notePhase("shell-prompt-observed", startedMs + deltaSinceProcess);
                }
            }
            if (promoted) {
                publishShellReady();
            }
        }

        private void publishShellReady() {
            timeline.mark(LaunchTimeline.Stage.SHELL_READY);
        }

        public void markSystemdReady() {
            if (!isCurrentGeneration()) {
                return;
            }
            systemdReadyMarked.set(true);
            if (readinessTracker.markSystemdReady(generation, monotonicNanos.getAsLong())) {
                timeline.mark(LaunchTimeline.Stage.SYSTEMD_READY);
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
                            publishShellReady();
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

    /**
     * Filters early UML {@code /dev/ttyS0} kernel echo of host {@code HELLO} lines (and blank
     * CRLF/LF lines) before the guest emits {@code READY 1}, preventing {@link BridgeClient} from
     * treating its own echoed handshake as an unauthenticated guest request. Passes every error,
     * post-ready frame, and oversized line through unchanged.
     */
    static final class PreAuthHelloEchoFilterInputStream extends InputStream {
        private static final int MAX_PRE_AUTH_LINE_BYTES = 4096;

        private final InputStream delegate;
        private byte[] pending = new byte[0];
        private int pendingPos;
        private boolean passthrough;

        PreAuthHelloEchoFilterInputStream(InputStream delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public int read() throws IOException {
            if (pendingPos < pending.length) {
                return pending[pendingPos++] & 0xFF;
            }
            if (passthrough) {
                return delegate.read();
            }
            if (!fillPreAuthBuffer()) {
                return -1;
            }
            return pending[pendingPos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (b == null) {
                throw new NullPointerException("b");
            }
            if (off < 0 || len < 0 || len > b.length - off) {
                throw new IndexOutOfBoundsException("invalid offset/length");
            }
            if (len == 0) {
                return 0;
            }
            if (pendingPos < pending.length) {
                int count = Math.min(len, pending.length - pendingPos);
                System.arraycopy(pending, pendingPos, b, off, count);
                pendingPos += count;
                return count;
            }
            if (passthrough) {
                return delegate.read(b, off, len);
            }
            if (!fillPreAuthBuffer()) {
                return -1;
            }
            int count = Math.min(len, pending.length - pendingPos);
            System.arraycopy(pending, pendingPos, b, off, count);
            pendingPos += count;
            return count;
        }

        @Override
        public int available() throws IOException {
            int buffered = pending.length - pendingPos;
            return buffered > 0 ? buffered : delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private boolean fillPreAuthBuffer() throws IOException {
            while (true) {
                ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(128);
                boolean hitNewline = false;
                while (lineBuf.size() < MAX_PRE_AUTH_LINE_BYTES) {
                    int value = delegate.read();
                    if (value < 0) {
                        break;
                    }
                    lineBuf.write(value);
                    if (value == '\n') {
                        hitNewline = true;
                        break;
                    }
                }
                byte[] raw = lineBuf.toByteArray();
                if (raw.length == 0) {
                    passthrough = true;
                    return false;
                }
                if (!hitNewline) {
                    if (lineBuf.size() >= MAX_PRE_AUTH_LINE_BYTES) {
                        passthrough = true;
                    }
                    pending = raw;
                    pendingPos = 0;
                    return true;
                }
                String trimmed = new String(raw, StandardCharsets.US_ASCII).trim();
                if (trimmed.isEmpty() || isEchoedHello(trimmed)) {
                    continue;
                }
                if (trimmed.startsWith("ORCHESTELLA/READY ")
                        || trimmed.startsWith("DROIDLAYER/READY ")) {
                    passthrough = true;
                }
                pending = raw;
                pendingPos = 0;
                return true;
            }
        }

        private static boolean isEchoedHello(String trimmed) {
            return "ORCHESTELLA/HELLO".equals(trimmed)
                    || "DROIDLAYER/HELLO".equals(trimmed)
                    || trimmed.startsWith("ORCHESTELLA/HELLO ")
                    || trimmed.startsWith("DROIDLAYER/HELLO ");
        }
    }
}
