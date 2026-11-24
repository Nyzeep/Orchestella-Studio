package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceBudget;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEventSink;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Contract tests for W3-05 (R-05) atomic UML -> QEMU fallback preconditions,
 * dirty-disk / unconfirmed-stop data preservation with recovery guidance,
 * and post-revocation preservation of existing explicit UML instances.
 */
public final class UmlAtomicFallbackContractTest {
    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    private final List<String> registeredIds = new ArrayList<>();

    @Before
    public void setUp() {
        LaunchDiagnosticStore.clearAll();
    }

    @After
    public void tearDown() {
        for (String id : registeredIds) {
            ExecutionSessionRegistry.unregister(id);
            InstanceLaunchGuard.end(id);
            LaunchDiagnosticStore.clear(id);
        }
        registeredIds.clear();
    }

    @Test
    public void atomicFallbackSucceedsOnlyWhenAllFourPreconditionsAreProven() throws Exception {
        Path root = tmp.newFolder().toPath();
        File filesDir = root.resolve("files").toFile();
        assertTrue(filesDir.mkdirs());
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        Path vol = root.resolve("unwritten-alpine.raw");
        Files.write(vol, new byte[0]);
        InstanceLifecycle.Instance instance = createExplicitUmlInstance(controller, vol, "alpine");
        registeredIds.add(instance.id());

        CountDownLatch qemuExit = new CountDownLatch(1);
        AtomicInteger qemuLaunchCount = new AtomicInteger();
        List<ExecutionPolicyContract.LaunchDiagnostic> diagnostics = new ArrayList<>();

        InstanceLaunchCoordinator coordinator = buildCoordinator(
                store,
                filesDir,
                id -> RuntimeProcessSupervisor.StopResult.CONFIRMED,
                params -> {
                    throw new IOException("simulated UML spawn failure before mount");
                },
                params -> {
                    qemuLaunchCount.incrementAndGet();
                    ExecutionSession session = new StubSession(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION, qemuExit);
                    ExecutionSessionRegistry.register(params.instanceId, session);
                    return session;
                },
                diagnostics::add,
                true,
                true,
                (id, path) -> true,
                distro -> true);

        InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(instance);
        assertTrue("expected Launched via atomic QEMU fallback", outcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Launched);
        InstanceLaunchCoordinator.LaunchOutcome.Launched launched = (InstanceLaunchCoordinator.LaunchOutcome.Launched) outcome;
        assertEquals(1, qemuLaunchCount.get());
        assertEquals(ExecutionPolicyContract.FallbackReason.UML_LAUNCH_FAILED_ATOMIC_FALLBACK.name(), launched.getDegradationReason());

        // Persisted execution identity must stay USER_MODE_KERNEL (never silently mutated).
        InstanceLifecycle.Instance reloaded = store.load(instance.id());
        assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL, reloaded.executionIdentity().strategy().strategy());

        // Diagnostic recorded explicit atomic fallback reason.
        ExecutionPolicyContract.LaunchDiagnostic last = LaunchDiagnosticStore.latest(instance.id());
        assertNotNull(last);
        assertEquals(ExecutionPolicyContract.FallbackReason.UML_LAUNCH_FAILED_ATOMIC_FALLBACK, last.fallbackReason());
        assertEquals(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION, last.resolvedStrategy());

        qemuExit.countDown();
        awaitStopped(store, instance.id());
    }

    @Test
    public void refusesFallbackAndPreservesRunningAndDataWhenStopIsUnconfirmed() throws Exception {
        Path root = tmp.newFolder().toPath();
        File filesDir = root.resolve("files").toFile();
        assertTrue(filesDir.mkdirs());
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        Path vol = root.resolve("unconfirmed-stop.raw");
        Files.write(vol, new byte[0]);
        InstanceLifecycle.Instance instance = createExplicitUmlInstance(controller, vol, "alpine");
        registeredIds.add(instance.id());

        AtomicInteger qemuLaunchCount = new AtomicInteger();
        InstanceLaunchCoordinator coordinator = buildCoordinator(
                store,
                filesDir,
                id -> RuntimeProcessSupervisor.StopResult.UNCONFIRMED,
                params -> {
                    throw new IOException("UML hung during launch");
                },
                params -> {
                    qemuLaunchCount.incrementAndGet();
                    throw new AssertionError("QEMU fallback must never start when old process stop is unconfirmed");
                },
                diag -> {},
                true,
                true,
                (id, path) -> true,
                distro -> true);

        InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(instance);
        assertTrue(outcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Failed);
        assertEquals(0, qemuLaunchCount.get());

        Throwable failure = ((InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome).getFailure();
        assertTrue(failure instanceof InstanceLaunchCoordinator.AtomicFallbackRefusedException);
        assertEquals(ErrorCopy.CODE_UML_FALLBACK_UNCONFIRMED_STOP, ErrorCopy.resolve(failure).getCode());

        // ADR-0020: unconfirmed stop keeps RUNNING metadata rather than marking CRASHED/STOPPED.
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
        ExecutionPolicyContract.LaunchDiagnostic diagnostic = LaunchDiagnosticStore.latest(instance.id());
        assertNotNull(diagnostic);
        assertNotNull(diagnostic.recoveryGuidance());
        assertTrue(diagnostic.recoveryGuidance().contains("停止未确认"));
    }

    @Test
    public void refusesFallbackAndPreservesUserDiskWhenDiskWasAlreadyWritten() throws Exception {
        Path root = tmp.newFolder().toPath();
        File filesDir = root.resolve("files").toFile();
        assertTrue(filesDir.mkdirs());
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        Path vol = root.resolve("written-ext4.raw");
        byte[] ext4Stub = new byte[4096];
        ext4Stub[0x438] = 0x53;
        ext4Stub[0x439] = (byte) 0xEF;
        Files.write(vol, ext4Stub);
        InstanceLifecycle.Instance instance = createExplicitUmlInstance(controller, vol, "alpine");
        registeredIds.add(instance.id());

        AtomicInteger qemuLaunchCount = new AtomicInteger();
        InstanceLaunchCoordinator coordinator = buildCoordinator(
                store,
                filesDir,
                id -> RuntimeProcessSupervisor.StopResult.CONFIRMED,
                params -> {
                    throw new IOException("UML exited after mounting ext4");
                },
                params -> {
                    qemuLaunchCount.incrementAndGet();
                    throw new AssertionError("QEMU fallback must never run on an already-written user disk");
                },
                diag -> {},
                true,
                true,
                (id, path) -> true,
                distro -> true);

        InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(instance);
        assertTrue(outcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Failed);
        assertEquals(0, qemuLaunchCount.get());
        Throwable failure = ((InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome).getFailure();
        assertTrue(failure instanceof InstanceLaunchCoordinator.AtomicFallbackRefusedException);
        assertEquals(ErrorCopy.CODE_UML_FALLBACK_DIRTY_DISK, ErrorCopy.resolve(failure).getCode());

        // User disk bytes and persisted execution identity must remain untouched.
        assertArrayEquals(ext4Stub, Files.readAllBytes(vol));
        assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                store.load(instance.id()).executionIdentity().strategy().strategy());
        ExecutionPolicyContract.LaunchDiagnostic diagnostic = LaunchDiagnosticStore.latest(instance.id());
        assertNotNull(diagnostic);
        assertNotNull(diagnostic.recoveryGuidance());
        assertTrue(diagnostic.recoveryGuidance().contains("用户盘已写入"));
    }

    @Test
    public void refusesFallbackWhenLeasesRemainHeldOrFormatDistroIncompatible() throws Exception {
        Path root = tmp.newFolder().toPath();
        File filesDir = root.resolve("files").toFile();
        assertTrue(filesDir.mkdirs());
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        Path vol = root.resolve("lease-held.raw");
        Files.write(vol, new byte[0]);
        InstanceLifecycle.Instance instance = createExplicitUmlInstance(controller, vol, "alpine");
        registeredIds.add(instance.id());

        AtomicInteger qemuLaunchCount = new AtomicInteger();
        InstanceLaunchCoordinator coordinator = buildCoordinator(
                store,
                filesDir,
                id -> RuntimeProcessSupervisor.StopResult.CONFIRMED,
                params -> {
                    throw new IOException("UML failed with dangling lease");
                },
                params -> {
                    qemuLaunchCount.incrementAndGet();
                    throw new AssertionError("QEMU fallback must not start while leases are held or distro incompatible");
                },
                diag -> {},
                true,
                true,
                (id, path) -> false, // leases still held
                distro -> false);    // QEMU does not support this distro

        InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(instance);
        assertTrue(outcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Failed);
        assertEquals(0, qemuLaunchCount.get());
        Throwable failure = ((InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome).getFailure();
        assertTrue(failure instanceof InstanceLaunchCoordinator.AtomicFallbackRefusedException);
        InstanceLaunchCoordinator.AtomicFallbackRefusedException refused =
                (InstanceLaunchCoordinator.AtomicFallbackRefusedException) failure;
        assertTrue(refused.getDecision().blockers().contains(
                ExecutionPolicyContract.AtomicFallbackBlocker.LEASES_NOT_RELEASED));
        assertTrue(refused.getDecision().blockers().contains(
                ExecutionPolicyContract.AtomicFallbackBlocker.VOLUME_FORMAT_INCOMPATIBLE));
        assertEquals(ErrorCopy.CODE_UML_FALLBACK_BLOCKED, ErrorCopy.resolve(failure).getCode());
    }

    @Test
    public void revokingNewInstanceRecommendationStillPreservesExplicitStartStopAndExport() throws Exception {
        Path root = tmp.newFolder().toPath();
        File filesDir = root.resolve("files").toFile();
        assertTrue(filesDir.mkdirs());
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        Path vol = root.resolve("existing-uml.raw");
        byte[] content = "existing-uml-user-data".getBytes(StandardCharsets.UTF_8);
        Files.write(vol, content);
        InstanceLifecycle.Instance existing = createExplicitUmlInstance(controller, vol, "alpine");
        registeredIds.add(existing.id());

        // Recommendation/QA creation flag is OFF (revoked), while explicitUmlLaunchAllowed stays ON.
        CountDownLatch exitLatch = new CountDownLatch(1);
        AtomicInteger umlLaunches = new AtomicInteger();
        InstanceLaunchCoordinator coordinator = buildCoordinator(
                store,
                filesDir,
                id -> null,
                params -> {
                    umlLaunches.incrementAndGet();
                    ExecutionSession session = new StubSession(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL, exitLatch);
                    ExecutionSessionRegistry.register(params.instanceId, session);
                    return session;
                },
                params -> {
                    throw new AssertionError("QEMU must not hijack existing explicit UML instance");
                },
                diag -> {},
                /* isolatedQaEnabled (revoked) */ false,
                /* explicitUmlLaunchAllowed */ true,
                (id, path) -> true,
                distro -> true);

        InstanceLaunchCoordinator.LaunchOutcome launched = coordinator.launch(existing);
        assertTrue(launched instanceof InstanceLaunchCoordinator.LaunchOutcome.Launched);
        assertEquals(1, umlLaunches.get());

        // Stop path works cleanly.
        exitLatch.countDown();
        awaitStopped(store, existing.id());

        // Persisted identity unchanged & SAF export/import roundtrip stays intact after revocation.
        InstanceLifecycle.Instance afterStop = store.load(existing.id());
        assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                afterStop.executionIdentity().strategy().strategy());
        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        controller.exportToArchive(existing.id(), exported);
        Path restoredVol = root.resolve("restored-uml.raw");
        InstanceLifecycle.Instance imported = controller.importFromArchive(
                new ByteArrayInputStream(exported.toByteArray()), restoredVol);
        assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                imported.executionIdentity().strategy().strategy());
        assertArrayEquals(content, Files.readAllBytes(restoredVol));
    }

    @Test
    public void atomicFallbackDoesNotMaskQemuUnconfirmedStopAfterCleanUmlFailure() throws Exception {
        Path root = tmp.newFolder().toPath();
        File filesDir = root.resolve("files").toFile();
        assertTrue(filesDir.mkdirs());
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        Path vol = root.resolve("uml-clean-qemu-unconfirmed.raw");
        Files.write(vol, new byte[0]);
        InstanceLifecycle.Instance instance = createExplicitUmlInstance(controller, vol, "alpine");
        registeredIds.add(instance.id());

        AtomicInteger cleanupReads = new AtomicInteger();
        InstanceLaunchCoordinator coordinator = buildCoordinator(
                store,
                filesDir,
                id -> {
                    int call = cleanupReads.incrementAndGet();
                    return call == 1
                            ? RuntimeProcessSupervisor.StopResult.CONFIRMED
                            : RuntimeProcessSupervisor.StopResult.UNCONFIRMED;
                },
                params -> {
                    throw new IOException("UML failed before disk write");
                },
                params -> {
                    throw new IOException("QEMU fallback hung and stop was unconfirmed");
                },
                diag -> {},
                true,
                true,
                (id, path) -> true,
                distro -> true);

        InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(instance);
        assertTrue(outcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Failed);
        // QEMU's UNCONFIRMED cleanup must not be masked by UML's earlier CONFIRMED cleanup.
        assertEquals(2, cleanupReads.get());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(instance.id()).state());
    }

    @Test
    public void budgetRefusalFromUmlDoesNotTriggerQemuFallbackAndRestoresStopped() throws Exception {
        Path root = tmp.newFolder().toPath();
        File filesDir = root.resolve("files").toFile();
        assertTrue(filesDir.mkdirs());
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        Path vol = root.resolve("budget-refusal.raw");
        Files.write(vol, new byte[0]);
        InstanceLifecycle.Instance instance = createExplicitUmlInstance(controller, vol, "alpine");
        registeredIds.add(instance.id());

        AtomicInteger qemuCalls = new AtomicInteger();
        InstanceLaunchCoordinator coordinator = buildCoordinator(
                store,
                filesDir,
                id -> RuntimeProcessSupervisor.StopResult.CONFIRMED,
                params -> {
                    throw new UmlInstanceBudget.BudgetRefusalException(
                            UmlInstanceBudget.RefusalReason.HOST_AVAILABLE_RAM_INSUFFICIENT,
                            params.instanceId,
                            512,
                            new UmlInstanceBudget.BudgetSnapshot(0, 0, 0, 256, 128, false),
                            "simulated host budget refusal");
                },
                params -> {
                    qemuCalls.incrementAndGet();
                    throw new AssertionError("QEMU fallback must not run on UML prelaunch budget refusal");
                },
                diag -> {},
                true,
                true,
                (id, path) -> true,
                distro -> true);

        InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(instance);
        assertTrue(outcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Failed);
        assertEquals(0, qemuCalls.get());
        assertTrue(((InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome).getFailure()
                instanceof UmlInstanceBudget.BudgetRefusalException);
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instance.id()).state());
    }

    @Test
    public void refusesAtomicFallbackWhenAnotherInstanceIsActiveForSingleInstancePolicy() throws Exception {
        Path root = tmp.newFolder().toPath();
        File filesDir = root.resolve("files").toFile();
        assertTrue(filesDir.mkdirs());
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());

        Path activeVol = root.resolve("active-uml.raw");
        Files.write(activeVol, new byte[0]);
        InstanceLifecycle.Instance activeUml = createExplicitUmlInstance(controller, activeVol, "alpine");
        controller.start(activeUml.id());
        registeredIds.add(activeUml.id());

        Path secondVol = root.resolve("second-uml.raw");
        Files.write(secondVol, new byte[0]);
        InstanceLifecycle.Instance secondUml = createExplicitUmlInstance(controller, secondVol, "alpine");
        registeredIds.add(secondUml.id());

        AtomicInteger qemuCalls = new AtomicInteger();
        InstanceLaunchCoordinator coordinator = buildCoordinator(
                store,
                filesDir,
                id -> RuntimeProcessSupervisor.StopResult.CONFIRMED,
                params -> {
                    throw new IOException("second UML failed");
                },
                params -> {
                    qemuCalls.incrementAndGet();
                    throw new AssertionError("QEMU fallback must not start alongside another running UML instance");
                },
                diag -> {},
                true,
                true,
                (id, path) -> true,
                distro -> true);

        InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(secondUml);
        assertTrue(outcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Failed);
        assertEquals(0, qemuCalls.get());
        Throwable failure = ((InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome).getFailure();
        assertTrue(failure instanceof InstanceLaunchCoordinator.AtomicFallbackRefusedException);
        assertTrue(((InstanceLaunchCoordinator.AtomicFallbackRefusedException) failure)
                .getDecision()
                .blockers()
                .contains(ExecutionPolicyContract.AtomicFallbackBlocker.LEASES_NOT_RELEASED));
    }

    @Test
    public void detectsWritePast64KiBWithRestoredMtimeInDiskWriteProbe() throws Exception {
        Path root = tmp.newFolder().toPath();
        Path placeholder = root.resolve("placeholder-256k.raw");
        byte[] initial = new byte[256 * 1024];
        Files.write(placeholder, initial);
        FileTime originalMtime = Files.getLastModifiedTime(placeholder);

        UmlDiskWriteProbe.DiskSnapshot snapshot = UmlDiskWriteProbe.captureBeforeLaunch(placeholder, null);
        assertTrue(UmlDiskWriteProbe.isUserDiskUnwritten(snapshot, placeholder, null));

        // Mutate bytes beyond the old 64 KiB head window and restore the original timestamp.
        initial[128 * 1024] = 0x5A;
        Files.write(placeholder, initial);
        Files.setLastModifiedTime(placeholder, originalMtime);

        assertFalse(
                "Writes beyond 64 KiB must be detected even if mtime is unchanged",
                UmlDiskWriteProbe.isUserDiskUnwritten(snapshot, placeholder, null));
    }

    private static InstanceLifecycle.Instance createExplicitUmlInstance(
            InstanceLifecycle.Controller controller, Path rootVol, String distroId) throws IOException {
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference(distroId, "3.22.2"),
                rootVol,
                VmHardwareConfig.builder().cpuCores(1).ramMegabytes(512).build(),
                InstanceUserConfig.defaultRoot(),
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE));
        controller.beginProvisioning(created.id());
        return controller.completeProvisioning(created.id());
    }

    private static InstanceLaunchCoordinator buildCoordinator(
            InstanceLifecycle.FileStore store,
            File filesDir,
            InstanceLaunchCoordinator.CleanupBridge cleanupBridge,
            InstanceStrategyLauncher.UserModeKernelLaunch umlLaunch,
            InstanceStrategyLauncher.QemuLaunch qemuLaunch,
            InstanceLaunchCoordinator.LaunchDiagnosticSink diagnosticSink,
            boolean isolatedQaEnabled,
            boolean explicitUmlLaunchAllowed,
            InstanceLaunchCoordinator.UmlLeaseVerifier leaseVerifier,
            InstanceLaunchCoordinator.QemuFallbackDistroSupport qemuDistroSupport) {
        return new InstanceLaunchCoordinator(
                store,
                new InstanceLaunchCoordinator.LaunchSettings() {
                    @Override public void setLastActiveInstanceId(String instanceId) {}
                    @Override public BatteryPolicy batteryPolicy() { return BatteryPolicy.BALANCED; }
                    @Override public boolean isAcceleratorLaneEnabled() { return false; }
                },
                new InstanceLaunchCoordinator.ForegroundServiceHandle() {
                    @Override public void start(String id, String title, String badge, BatteryPolicy p, String gen) {}
                    @Override public void stop(String id, String gen) {}
                },
                new InstanceLaunchCoordinator.LaunchUi() {
                    @Override public void runOnUi(Runnable update) { update.run(); }
                    @Override public void resetCreateButtons() {}
                    @Override public void showStatus(String message) {}
                    @Override public void showAttached(InstanceLifecycle.Instance instance) {}
                    @Override public void showLaunchBusyStatus() {}
                    @Override public void showLaunchSuccess(InstanceLaunchCoordinator.LaunchNavigation navigation) {}
                    @Override public void surfaceLaunchFailure(String message) {}
                    @Override public void showInstances() {}
                },
                new InstanceLaunchCoordinator.LaunchCopy() {
                    @Override public String detailMessage(Throwable failure) { return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName(); }
                    @Override public String guestExitedStatus() { return "exited"; }
                    @Override public String launchStartedHardwareStatus() { return "hw"; }
                    @Override public String launchDegradedToSoftwareStatus(String reason) { return "fallback:" + reason; }
                },
                new InstanceLaunchCoordinator.LaunchDiagnostics() {
                    @Override public void logAcceleratorSnapshot(String line) {}
                    @Override public void logLaunchFailure(String failureType) {}
                },
                cleanupBridge,
                filesDir,
                () -> "badge",
                () -> false,
                params -> { throw new AssertionError("AVF not used"); },
                qemuLaunch,
                id -> { throw new AssertionError("custom VM not used"); },
                id -> { throw new AssertionError("pVM not used"); },
                () -> AcceleratorFacts::unknown,
                Optional::empty,
                event -> {},
                LaneClock.system(),
                Clock.systemUTC(),
                () -> {},
                new InstanceLaunchCoordinator.UmlFaceLane() {
                    @Override public UmlFaceLauncher arm(String instanceId, boolean gateEnabled) { return null; }
                    @Override public void disarm(String instanceId) {}
                },
                () -> isolatedQaEnabled,
                umlLaunch,
                diagnosticSink,
                () -> explicitUmlLaunchAllowed,
                () -> true,
                leaseVerifier,
                qemuDistroSupport);
    }

    private static void awaitStopped(InstanceLifecycle.FileStore store, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (store.load(id).state() == InstanceLifecycle.State.STOPPED) {
                return;
            }
            Thread.sleep(20L);
        }
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(id).state());
    }

    private static final class StubSession implements RuntimeOwnedSession {
        private final FullLinuxContract.ExecutionStrategy strategy;
        private final CountDownLatch exitLatch;

        StubSession(FullLinuxContract.ExecutionStrategy strategy, CountDownLatch exitLatch) {
            this.strategy = strategy;
            this.exitLatch = exitLatch;
        }

        @Override public FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            return strategy == FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL
                    ? FullLinuxContract.CapabilityMatrix.userModeKernelBaseline()
                    : FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline();
        }
        @Override public int waitFor() throws InterruptedException {
            exitLatch.await(5, TimeUnit.SECONDS);
            return 0;
        }
        @Override public void terminate() { exitLatch.countDown(); }
        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            exitLatch.countDown();
            return RuntimeProcessSupervisor.StopResult.CONFIRMED;
        }
        @Override public boolean isRuntimeAlive() { return exitLatch.getCount() > 0; }
        @Override public OutputStream terminalInput() { return OutputStream.nullOutputStream(); }
        @Override public InputStream terminalOutput() { return InputStream.nullInputStream(); }
        @Override public OutputStream controlInput() { return OutputStream.nullOutputStream(); }
        @Override public InputStream controlOutput() { return InputStream.nullInputStream(); }
        @Override public void close() { exitLatch.countDown(); }
    }
}
