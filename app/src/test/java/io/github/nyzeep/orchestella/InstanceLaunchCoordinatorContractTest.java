package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.SessionCapabilities;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.BatteryState;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.ThermalState;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * W3-00 / R-00 contract tests for execution-policy preflight, legacy route compatibility,
 * isolated QA round-trip & standalone UML wiring, and non-destructive diagnostic refusals.
 */
public final class InstanceLaunchCoordinatorContractTest {
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final class RecordingForeground implements InstanceLaunchCoordinator.ForegroundServiceHandle {
        final List<String> events = new ArrayList<>();

        @Override
        public synchronized void start(
                String instanceId,
                String distroTitle,
                String performanceBadge,
                BatteryPolicy batteryPolicy,
                String launchGeneration) {
            events.add("start:" + instanceId + ":" + performanceBadge);
        }

        @Override
        public synchronized void stop(String instanceId, String generation) {
            events.add("stop:" + instanceId + ":" + generation);
        }
    }

    private static final class RecordingUi implements InstanceLaunchCoordinator.LaunchUi {
        final List<String> failures = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final List<InstanceLaunchCoordinator.LaunchNavigation> successes = new ArrayList<>();

        @Override public void runOnUi(Runnable update) { update.run(); }
        @Override public void resetCreateButtons() {}
        @Override public synchronized void showStatus(String message) { statuses.add(message); }
        @Override public void showAttached(InstanceLifecycle.Instance instance) {}
        @Override public void showLaunchBusyStatus() {}
        @Override public synchronized void showLaunchSuccess(InstanceLaunchCoordinator.LaunchNavigation navigation) {
            successes.add(navigation);
        }
        @Override public synchronized void surfaceLaunchFailure(String message) { failures.add(message); }
        @Override public void showInstances() {}
    }

    private static final class FakeSettings implements InstanceLaunchCoordinator.LaunchSettings {
        boolean acceleratorLaneEnabled;

        @Override public void setLastActiveInstanceId(String instanceId) {}
        @Override public BatteryPolicy batteryPolicy() { return BatteryPolicy.BALANCED; }
        @Override public boolean isAcceleratorLaneEnabled() { return acceleratorLaneEnabled; }
    }

    private static final class StubCopy implements InstanceLaunchCoordinator.LaunchCopy {
        @Override public String detailMessage(Throwable failure) { return failure.getMessage(); }
        @Override public String guestExitedStatus() { return "exited"; }
        @Override public String launchStartedHardwareStatus() { return "hardware"; }
        @Override public String launchDegradedToSoftwareStatus(String reason) { return "degraded:" + reason; }
    }

    private static final class NoOpDiagnostics implements InstanceLaunchCoordinator.LaunchDiagnostics {
        @Override public void logAcceleratorSnapshot(String line) {}
        @Override public void logLaunchFailure(String failureType) {}
    }

    private static final class RecordingUmlFaceLane implements InstanceLaunchCoordinator.UmlFaceLane {
        final AtomicInteger arms = new AtomicInteger();

        @Override
        public UmlFaceLauncher arm(String instanceId, boolean gateEnabled) {
            arms.incrementAndGet();
            return null;
        }

        @Override
        public void disarm(String instanceId) {}
    }

    private static class ContractExitSession implements ExecutionSession, RuntimeOwnedSession {
        private final FullLinuxContract.CapabilityMatrix matrix;
        private final CountDownLatch release;

        ContractExitSession(FullLinuxContract.CapabilityMatrix matrix, CountDownLatch release) {
            this.matrix = matrix;
            this.release = release;
        }

        @Override
        public int waitFor() throws InterruptedException {
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("waitFor timeout");
            }
            return 0;
        }

        @Override public OutputStream terminalInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream terminalOutput() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream controlInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public void close() {}
        @Override public void terminate() {}
        @Override public FullLinuxContract.CapabilityMatrix capabilityMatrix() { return matrix; }
        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            return RuntimeProcessSupervisor.StopResult.CONFIRMED;
        }
        @Override public boolean isRuntimeAlive() { return false; }
    }

    @Test
    public void legacySchemaZeroAndSchemaOneInstancesKeepExistingAvfQemuRoutingWithoutWriteMigration()
            throws Exception {
        for (int schema = 0; schema <= 1; schema++) {
            Path dir = temporaryFolder.newFolder("legacy-route-" + schema).toPath();
            Path storeDir = dir.resolve("instances");
            Files.createDirectories(storeDir);
            String id = UUID.randomUUID().toString();
            Path rawVol = dir.resolve("root.raw");
            byte[] rawPayload = ("SYNTHETIC_LEGACY_VOL_" + schema).getBytes(StandardCharsets.UTF_8);
            Files.write(rawVol, rawPayload);
            String expectedSha = StorageContract.sha256Hex(rawPayload);

            Properties props = new Properties();
            if (schema == 1) {
                props.setProperty("metadata.schemaVersion", "1");
                props.setProperty("hardware.cpuCores", "2");
                props.setProperty("hardware.ramMegabytes", "2048");
                props.setProperty("hardware.diskCapacityBytes", "2147483648");
                props.setProperty("hardware.gpuAccelerationEnabled", "false");
                props.setProperty("hardware.legacyDefaults", "true");
            }
            props.setProperty("id", id);
            props.setProperty("distroId", "debian");
            props.setProperty("distroVersion", "13");
            props.setProperty("rootVolume", rawVol.toString());
            props.setProperty("bridgeToken", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
            props.setProperty("guestPassword", "LegacyPassword1");
            props.setProperty("runtimeClass", "FULL_LINUX");
            props.setProperty("state", "STOPPED");
            props.setProperty("createdAtMillis", "1700000000000");
            props.setProperty("updatedAtMillis", "1700000001000");
            Path propsFile = storeDir.resolve(id + ".properties");
            try (OutputStream out = Files.newOutputStream(propsFile)) {
                props.store(out, "legacy schema " + schema);
            }
            byte[] beforeReadBytes = Files.readAllBytes(propsFile);

            InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
            InstanceLifecycle.Instance loaded = store.load(id);
            assertArrayEquals("load must not migrate legacy file", beforeReadBytes, Files.readAllBytes(propsFile));
            assertTrue(loaded.executionIdentity().isLegacyAbsent());

            File filesDir = temporaryFolder.newFolder("files-legacy-" + schema);
            assertTrue(new File(filesDir, "instances").mkdirs());
            FakeSettings settings = new FakeSettings();
            settings.acceleratorLaneEnabled = false;
            RecordingForeground foreground = new RecordingForeground();
            RecordingUi ui = new RecordingUi();
            RecordingUmlFaceLane faceLane = new RecordingUmlFaceLane();
            List<ExecutionPolicyContract.LaunchDiagnostic> diagnostics = new ArrayList<>();
            AtomicInteger avfCalls = new AtomicInteger();
            AtomicInteger qemuCalls = new AtomicInteger();
            CountDownLatch release = new CountDownLatch(1);
            ContractExitSession qemuSession = new ContractExitSession(
                    FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline(), release);

            InstanceLaunchCoordinator coordinator = new InstanceLaunchCoordinator(
                    store,
                    settings,
                    foreground,
                    ui,
                    new StubCopy(),
                    new NoOpDiagnostics(),
                    ignored -> null,
                    filesDir,
                    () -> "64GB RAM",
                    () -> true,
                    params -> {
                        avfCalls.incrementAndGet();
                        throw new AvfEngineException(AvfEngineException.PERMISSION_DENIED, "no manage vm");
                    },
                    params -> {
                        qemuCalls.incrementAndGet();
                        ExecutionSessionRegistry.register(params.instanceId, qemuSession);
                        return qemuSession;
                    },
                    instanceId -> { throw new UnsupportedOperationException(); },
                    instanceId -> { throw new UnsupportedOperationException(); },
                    unarmedFacts(),
                    Optional::empty,
                    event -> {},
                    LaneClock.system(),
                    FIXED_CLOCK,
                    () -> {},
                    faceLane,
                    () -> false,
                    null,
                    diagnostics::add);

            try {
                InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(loaded);
                assertLaunched(outcome);
                assertEquals("legacy preferAvf=true still tries AVF preflight", 1, avfCalls.get());
                assertEquals("AVF failure falls back to QEMU", 1, qemuCalls.get());
                assertEquals(0, faceLane.arms.get());
                assertEquals(2, diagnostics.size());
                assertEquals(ExecutionPolicyContract.PreflightCode.LEGACY_DEFAULT_ROUTE,
                        diagnostics.get(0).preflightVerdict().code());
                assertEquals(ExecutionPolicyContract.FallbackReason.HARDWARE_PERMISSION_DENIED,
                        diagnostics.get(1).fallbackReason());

                // Diagnostics remain per-launch only and never pollute persisted .properties.
                Properties afterProps = readProperties(propsFile);
                assertFalse(afterProps.containsKey("execution.strategy"));
                assertFalse(afterProps.containsKey("storage.rootVolumeFormat"));
                assertFalse(afterProps.containsKey("launch.preflight"));
                assertFalse(afterProps.containsKey("launch.fallbackReason"));
                assertEquals(expectedSha, StorageContract.sha256Hex(Files.readAllBytes(rawVol)));
            } finally {
                release.countDown();
                ExecutionSessionRegistry.unregister(id);
                InstanceLaunchGuard.end(id);
            }
        }
    }

    @Test
    public void ordinaryCreateAndDefaultBuildRouteKeepExistingSoftwarePath() throws Exception {
        Path dir = temporaryFolder.newFolder("ordinary-create").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);
        Path rootVol = dir.resolve("ordinary.raw");
        Files.write(rootVol, "ORDINARY_RAW".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), rootVol);
        assertTrue(created.executionIdentity().isLegacyAbsent());

        File filesDir = temporaryFolder.newFolder("files-ordinary");
        assertTrue(new File(filesDir, "instances").mkdirs());
        FakeSettings settings = new FakeSettings();
        settings.acceleratorLaneEnabled = false;
        AtomicInteger qemuCalls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ContractExitSession session = new ContractExitSession(
                FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline(), release);

        InstanceLaunchCoordinator coordinator = new InstanceLaunchCoordinator(
                store,
                settings,
                new RecordingForeground(),
                new RecordingUi(),
                new StubCopy(),
                new NoOpDiagnostics(),
                ignored -> null,
                filesDir,
                () -> "64GB RAM",
                () -> false,
                params -> { throw new AssertionError("AVF not preferred"); },
                params -> {
                    qemuCalls.incrementAndGet();
                    ExecutionSessionRegistry.register(params.instanceId, session);
                    return session;
                },
                instanceId -> { throw new UnsupportedOperationException(); },
                instanceId -> { throw new UnsupportedOperationException(); },
                unarmedFacts(),
                Optional::empty,
                event -> {},
                LaneClock.system(),
                FIXED_CLOCK,
                () -> {},
                new RecordingUmlFaceLane());
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(created);
            assertLaunched(outcome);
            assertEquals(1, qemuCalls.get());
        } finally {
            release.countDown();
            ExecutionSessionRegistry.unregister(created.id());
            InstanceLaunchGuard.end(created.id());
        }
    }

    @Test
    public void isolatedQaEntryGatesCreationRoundTripsMetadataAndLaunchesStandaloneUmlThroughMatrix()
            throws Exception {
        Path dir = temporaryFolder.newFolder("qa-uml").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir.resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);
        Path rootVol = dir.resolve("qa-uml.raw");
        byte[] diskBytes = "SYNTHETIC_QA_UML_VOLUME".getBytes(StandardCharsets.UTF_8);
        Files.write(rootVol, diskBytes);
        String expectedSha = StorageContract.sha256Hex(diskBytes);

        // Non-QA build refuses isolated QA instance creation.
        IsolatedQaExecutionEntry nonQaEntry = new IsolatedQaExecutionEntry(() -> false);
        assertFalse(nonQaEntry.isEnabled());
        try {
            nonQaEntry.createInstance(
                    controller,
                    new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                    rootVol,
                    VmHardwareConfig.defaultConfig(),
                    InstanceUserConfig.defaultRoot(),
                    FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL);
            fail("non-QA channel must refuse isolated QA entry");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("QA"));
        }

        // QA build creates explicit USER_MODE_KERNEL + RAW_SPARSE instance and round-trips through SAF archive.
        IsolatedQaExecutionEntry qaEntry = new IsolatedQaExecutionEntry(() -> true);
        assertTrue(qaEntry.isEnabled());
        InstanceLifecycle.Instance created = qaEntry.createInstance(
                controller,
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                rootVol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL);
        controller.beginProvisioning(created.id());
        controller.completeProvisioning(created.id());

        Path importedVol = dir.resolve("qa-uml-imported.raw");
        InstanceLifecycle.Instance roundTripped = qaEntry.roundTripViaArchive(
                controller, created.id(), importedVol, UUID.randomUUID().toString());
        assertEquals(created.executionIdentity(), roundTripped.executionIdentity());
        assertEquals(expectedSha, StorageContract.sha256Hex(Files.readAllBytes(roundTripped.rootVolume())));

        // Launch via standalone UML seam inside QA channel; upper layer reads CapabilityMatrix only.
        File filesDir = temporaryFolder.newFolder("files-qa-uml");
        assertTrue(new File(filesDir, "instances").mkdirs());
        FakeSettings settings = new FakeSettings();
        settings.acceleratorLaneEnabled = true;
        RecordingForeground foreground = new RecordingForeground();
        RecordingUi ui = new RecordingUi();
        RecordingUmlFaceLane faceLane = new RecordingUmlFaceLane();
        AtomicInteger umlCalls = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        FullLinuxContract.CapabilityMatrix umlMatrix = FullLinuxContract.CapabilityMatrix.from(
                SessionCapabilities.builder(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL)
                        .ownership(SessionCapabilities.Ownership.RUNTIME_OWNED)
                        .terminal(true)
                        .bridge(true)
                        .display(SessionCapabilities.DisplayTransport.RFB)
                        .sharedDirectory(true)
                        .build());
        ContractExitSession umlSession = new ContractExitSession(umlMatrix, release);

        InstanceLaunchCoordinator coordinator = new InstanceLaunchCoordinator(
                store,
                settings,
                foreground,
                ui,
                new StubCopy(),
                new NoOpDiagnostics(),
                ignored -> null,
                filesDir,
                () -> "64GB RAM",
                () -> true,
                params -> { throw new AssertionError("AVF must not run for USER_MODE_KERNEL"); },
                params -> { throw new AssertionError("QEMU must not run for USER_MODE_KERNEL"); },
                instanceId -> { throw new UnsupportedOperationException(); },
                instanceId -> { throw new UnsupportedOperationException(); },
                () -> { throw new AssertionError("accelerator facts must not run for standalone UML"); },
                Optional::empty,
                event -> {},
                LaneClock.system(),
                FIXED_CLOCK,
                () -> {},
                faceLane,
                () -> true,
                params -> {
                    umlCalls.incrementAndGet();
                    ExecutionSessionRegistry.register(params.instanceId, umlSession);
                    return umlSession;
                },
                ignored -> {});

        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(roundTripped);
            InstanceLaunchCoordinator.LaunchOutcome.Launched launched = assertLaunched(outcome);
            assertEquals(1, umlCalls.get());
            assertEquals("standalone UML must not arm ride-along compile face", 0, faceLane.arms.get());
            assertFalse(launched.getHardwareAccelerated());
            assertNotNull(launched.getCapabilityMatrix());
            assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                    launched.getCapabilityMatrix().executionStrategy());
            assertFalse(launched.getCapabilityMatrix().supports(FullLinuxContract.Capability.GRAPHICS_RFB));
            assertFalse(launched.getCapabilityMatrix().supports(FullLinuxContract.Capability.AUDIO_OUTPUT));
            assertFalse(launched.getCapabilityMatrix().supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION));
            assertFalse(launched.getCapabilityMatrix().supports(FullLinuxContract.Capability.SHARED_DIRECTORY));
            String baselineBadge = FullLinuxContract.CapabilityMatrix.userModeKernelBaseline().performanceBadge();
            assertEquals(
                    java.util.List.of(
                            "start:" + roundTripped.id() + ":" + baselineBadge,
                            "start:" + roundTripped.id() + ":" + umlMatrix.performanceBadge()),
                    foreground.events);
        } finally {
            release.countDown();
            ExecutionSessionRegistry.unregister(roundTripped.id());
            InstanceLaunchGuard.end(roundTripped.id());
        }
    }

    @Test
    public void refusesUnknownStrategyUnrecognizedFormatAndVolumeConflictWhilePreservingExportAndRecovery()
            throws Exception {
        Path dir = temporaryFolder.newFolder("refusal-preserve").toPath();
        Path storeDir = dir.resolve("instances");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);

        // 1. Unknown execution strategy
        Path rawVol = dir.resolve("valid.raw");
        byte[] rawBytes = "PRESERVE_EXPORT_DATA".getBytes(StandardCharsets.UTF_8);
        Files.write(rawVol, rawBytes);
        String expectedSha = StorageContract.sha256Hex(rawBytes);

        InstanceLifecycle.Instance unknownStrategyInstance = createWithRawProperties(
                storeDir, store, controller, rawVol, "FUTURE_ENGINE_V9", "RAW_SPARSE");
        assertRefusedPreservingStateVolumeAndExport(
                storeDir, store, controller, unknownStrategyInstance, expectedSha,
                ExecutionPolicyContract.PreflightCode.UNKNOWN_EXECUTION_STRATEGY,
                ErrorCopy.CODE_EXEC_STRATEGY_UNKNOWN, true);

        // 2. Unrecognized volume format
        InstanceLifecycle.Instance unknownFormatInstance = createWithRawProperties(
                storeDir, store, controller, rawVol, "USER_MODE_KERNEL", "EXOTIC_CONTAINER");
        assertRefusedPreservingStateVolumeAndExport(
                storeDir, store, controller, unknownFormatInstance, expectedSha,
                ExecutionPolicyContract.PreflightCode.UNRECOGNIZED_VOLUME_FORMAT,
                ErrorCopy.CODE_VOLUME_FORMAT_CONFLICT, true);

        // 3. QCOW2 header conflict on disk
        Path qcow2Vol = dir.resolve("qcow2-spoof.raw");
        byte[] qcow2Bytes = new byte[] { 0x51, 0x46, 0x49, (byte) 0xFB, 0x00, 0x00, 0x00, 0x03 };
        Files.write(qcow2Vol, qcow2Bytes);
        String qcow2Sha = StorageContract.sha256Hex(qcow2Bytes);
        InstanceLifecycle.Instance qcow2Instance = createWithRawProperties(
                storeDir, store, controller, qcow2Vol, "USER_MODE_KERNEL", "RAW_SPARSE");
        assertRefusedPreservingStateVolumeAndExport(
                storeDir, store, controller, qcow2Instance, qcow2Sha,
                ExecutionPolicyContract.PreflightCode.VOLUME_FORMAT_CONFLICT,
                ErrorCopy.CODE_VOLUME_FORMAT_CONFLICT, true);

        // 4. USER_MODE_KERNEL outside isolated QA channel refuses instead of silently falling back to QEMU
        InstanceLifecycle.Instance qaOnlyInstance = createWithRawProperties(
                storeDir, store, controller, rawVol, "USER_MODE_KERNEL", "RAW_SPARSE");
        assertRefusedPreservingStateVolumeAndExport(
                storeDir, store, controller, qaOnlyInstance, expectedSha,
                ExecutionPolicyContract.PreflightCode.STRATEGY_NOT_AVAILABLE,
                ErrorCopy.CODE_EXEC_STRATEGY_UNAVAILABLE, false);
    }

    @Test
    public void explicitHardwareVirtualizationFailsWithAvfErrorWithoutSilentQemuFallback() throws Exception {
        Path dir = temporaryFolder.newFolder("explicit-avf-fail").toPath();
        Path storeDir = dir.resolve("instances");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);
        Path rawVol = dir.resolve("explicit-avf.raw");
        Files.write(rawVol, "EXPLICIT_AVF_VOLUME".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance instance = createWithRawProperties(
                storeDir, store, controller, rawVol, "HARDWARE_VIRTUALIZATION", "RAW_SPARSE");

        File filesDir = temporaryFolder.newFolder("files-explicit-avf");
        assertTrue(new File(filesDir, "instances").mkdirs());
        RecordingUi ui = new RecordingUi();
        AtomicInteger avfCalls = new AtomicInteger();

        InstanceLaunchCoordinator coordinator = new InstanceLaunchCoordinator(
                store,
                new FakeSettings(),
                new RecordingForeground(),
                ui,
                new StubCopy(),
                new NoOpDiagnostics(),
                ignored -> null,
                filesDir,
                () -> "64GB RAM",
                () -> true,
                params -> {
                    avfCalls.incrementAndGet();
                    throw new AvfEngineException(AvfEngineException.PERMISSION_DENIED, "no manage vm");
                },
                params -> {
                    throw new AssertionError("QEMU fallback must not run for explicit HARDWARE_VIRTUALIZATION");
                },
                instanceId -> { throw new UnsupportedOperationException(); },
                instanceId -> { throw new UnsupportedOperationException(); },
                unarmedFacts(),
                Optional::empty,
                event -> {},
                LaneClock.system(),
                FIXED_CLOCK,
                () -> {},
                new RecordingUmlFaceLane());

        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Failed.class, outcome.getClass());
            Throwable failure = ((InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome).getFailure();
            assertTrue(
                    "expected AvfEngineException but got " + failure,
                    failure instanceof AvfEngineException);
            assertEquals(
                    ErrorCopy.resolve(new AvfEngineException(AvfEngineException.PERMISSION_DENIED, "x")).getCode(),
                    ErrorCopy.resolve(failure).getCode());
            assertEquals(1, avfCalls.get());
            assertEquals(InstanceLifecycle.State.CRASHED, store.load(instance.id()).state());
        } finally {
            InstanceLaunchGuard.end(instance.id());
        }
    }

    private void assertRefusedPreservingStateVolumeAndExport(
            Path storeDir,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            InstanceLifecycle.Instance instance,
            String expectedVolumeSha,
            ExecutionPolicyContract.PreflightCode expectedCode,
            String expectedErrorCopyCode,
            boolean qaEnabled) throws Exception {
        Path propsPath = storeDir.resolve(instance.id() + ".properties");
        byte[] propsBefore = Files.readAllBytes(propsPath);

        File filesDir = temporaryFolder.newFolder("files-refusal-" + UUID.randomUUID());
        assertTrue(new File(filesDir, "instances").mkdirs());
        RecordingUi ui = new RecordingUi();
        List<ExecutionPolicyContract.LaunchDiagnostic> diagnostics = new ArrayList<>();
        InstanceLaunchCoordinator coordinator = new InstanceLaunchCoordinator(
                store,
                new FakeSettings(),
                new RecordingForeground(),
                ui,
                new StubCopy(),
                new NoOpDiagnostics(),
                ignored -> null,
                filesDir,
                () -> "64GB RAM",
                () -> false,
                params -> { throw new AssertionError("AVF must not start on refused preflight"); },
                params -> { throw new AssertionError("QEMU must not start on refused preflight"); },
                instanceId -> { throw new UnsupportedOperationException(); },
                instanceId -> { throw new UnsupportedOperationException(); },
                unarmedFacts(),
                Optional::empty,
                event -> {},
                LaneClock.system(),
                FIXED_CLOCK,
                () -> {},
                new RecordingUmlFaceLane(),
                () -> qaEnabled,
                params -> { throw new AssertionError("UML must not start on refused preflight"); },
                diagnostics::add);

        InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(instance);
        assertSame(InstanceLaunchCoordinator.LaunchOutcome.Failed.class, outcome.getClass());
        Throwable failure = ((InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome).getFailure();
        assertTrue(failure instanceof ExecutionPolicyContract.ExecutionPolicyRefusalException);
        assertEquals(expectedCode,
                ((ExecutionPolicyContract.ExecutionPolicyRefusalException) failure).verdict().code());
        assertEquals(expectedErrorCopyCode, ErrorCopy.resolve(failure).getCode());
        assertEquals(1, diagnostics.size());
        assertEquals(ExecutionPolicyContract.PreflightStatus.REFUSED, diagnostics.get(0).preflightVerdict().status());
        assertEquals(1, ui.failures.size());

        // Instance metadata, state, and root volume remain intact for export/recovery.
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
        assertArrayEquals(propsBefore, Files.readAllBytes(propsPath));
        assertEquals(expectedVolumeSha, StorageContract.sha256Hex(Files.readAllBytes(instance.rootVolume())));

        ByteArrayOutputStream backupOut = new ByteArrayOutputStream();
        StorageContract.InstanceExportMetadata exported = controller.exportToArchive(instance.id(), backupOut);
        assertEquals(expectedVolumeSha, exported.sha256RootVolume());
    }

    private static InstanceLifecycle.Instance createWithRawProperties(
            Path storeDir,
            InstanceLifecycle.FileStore store,
            InstanceLifecycle.Controller controller,
            Path rootVolume,
            String rawStrategy,
            String rawVolumeFormat) throws Exception {
        InstanceLifecycle.Instance base = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), rootVolume);
        controller.beginProvisioning(base.id());
        controller.completeProvisioning(base.id());
        Path propsPath = storeDir.resolve(base.id() + ".properties");
        Properties props = readProperties(propsPath);
        props.setProperty("execution.strategy", rawStrategy);
        props.setProperty("storage.rootVolumeFormat", rawVolumeFormat);
        try (OutputStream out = Files.newOutputStream(propsPath)) {
            props.store(out, "raw strategy/format test fixture");
        }
        return store.load(base.id());
    }

    private static InstanceLaunchCoordinator.FactsSource unarmedFacts() {
        return () -> () -> AcceleratorFacts.builder()
                .avfPresent(false)
                .nonProtectedSupported(false)
                .acceleratorImagePresent(false)
                .vsockSupported(false)
                .compilerTriple("")
                .thermalState(ThermalState.NOMINAL)
                .batteryState(BatteryState.OK)
                .build();
    }

    private static InstanceLaunchCoordinator.LaunchOutcome.Launched assertLaunched(
            InstanceLaunchCoordinator.LaunchOutcome outcome) {
        if (outcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Failed failed) {
            throw new AssertionError("expected Launched but failed", failed.getFailure());
        }
        assertSame(InstanceLaunchCoordinator.LaunchOutcome.Launched.class, outcome.getClass());
        return (InstanceLaunchCoordinator.LaunchOutcome.Launched) outcome;
    }

    private static Properties readProperties(Path path) throws Exception {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        }
        return props;
    }
}
