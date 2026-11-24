package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.PortForwardingRule;
import io.github.nyzeep.orchestella.runtime.contract.Protocol;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlInstanceBudget;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessHandle;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.BatteryState;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.ThermalState;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import io.github.nyzeep.orchestella.runtime.network.LoopbackEndpointRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Zero-device integration tests for W3-04 / R-04: multi-instance UML isolation, instance+generation
 * ownership, shared foreground service leases, memory budget enforcement (including compile
 * ride-along), loopback endpoint collision prevention, and stale-generation exit safety.
 */
public final class UmlMultiInstanceIsolationTest {
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_791_000_000_000L), ZoneOffset.UTC);
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    private final List<String> cleanupInstanceIds = new ArrayList<>();
    private final List<FakeUmlProcessHandle> handlesToRelease = new ArrayList<>();
    private final List<Runnable> cleanupCallbacks = new ArrayList<>();

    @After
    public void tearDown() {
        for (FakeUmlProcessHandle handle : handlesToRelease) {
            handle.alive.set(false);
            handle.exitGate.countDown();
        }
        handlesToRelease.clear();
        for (String id : cleanupInstanceIds) {
            ExecutionSessionRegistry.unregister(id);
            UmlInstanceLauncher.takeLastCleanupResult(id);
            InstanceLaunchGuard.end(id);
        }
        cleanupInstanceIds.clear();
        for (Runnable callback : cleanupCallbacks) {
            callback.run();
        }
        cleanupCallbacks.clear();
    }

    @Test(timeout = 20_000)
    public void twoStandaloneUmlInstancesCoexistWithCompileRideAlongAndIsolateStopAndBudgets()
            throws Exception {
        Path root = tmp.newFolder("coexist-all").toPath();
        File filesDir = root.resolve("files").toFile();
        File nativeDir = root.resolve("native").toFile();
        assertTrue(new File(filesDir, "instances").mkdirs());
        assertTrue(nativeDir.mkdirs());
        createExecutableFile(nativeDir.toPath().resolve("libuml_kernel.so"));
        createExecutableFile(nativeDir.toPath().resolve("libuml_stub.so"));
        assertTrue(new File(filesDir, UmlFaceLauncher.ROOTFS_DIR_NAME).mkdirs());

        // Arm the compile ride-along face (512 MB) independently of standalone instances.
        AtomicInteger rideAlongKeepAliveAcquires = new AtomicInteger();
        AtomicInteger rideAlongKeepAliveReleases = new AtomicInteger();
        FakeFaceProcess rideAlongProcess = new FakeFaceProcess();
        UmlFaceRideAlong rideAlong = new UmlFaceRideAlong(
                (id, gate, channel) -> new UmlFaceLauncher(
                        gate,
                        new UmlFaceLauncher.Environment() {
                            @Override public File nativeLibraryDir() { return nativeDir; }
                            @Override public File filesDir() { return filesDir; }
                        },
                        UmlFaceLauncher.LaunchParams.defaults(),
                        new UmlFaceLauncher.KeepAlive() {
                            @Override public void acquire() { rideAlongKeepAliveAcquires.incrementAndGet(); }
                            @Override public void release() { rideAlongKeepAliveReleases.incrementAndGet(); }
                        },
                        (cmd, env, wd) -> rideAlongProcess,
                        channel,
                        UmlFaceLauncher.FastProfile.initramfs()),
                null,
                line -> kotlin.Unit.INSTANCE);
        assertNotNull(rideAlong.arm("qemu-host", true));
        cleanupCallbacks.add(() -> rideAlong.disarm("qemu-host"));
        assertEquals(1, UmlFaceStatusRegistry.runningWidth());
        assertEquals(512, UmlFaceStatusRegistry.runningMemMb());

        // Shared budget: 4096 MB total - 2048 reserve = 2048 MB UML ceiling.
        // Ride-along takes 512 MB -> room for two 512 MB standalone UML instances (total 1536 MB),
        // while a third 600 MB instance would exceed 2048 MB.
        UmlInstanceBudget budget = new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(4096, 3072, false),
                UmlFaceStatusRegistry::runningMemMb,
                UmlInstanceBudget.BudgetPolicy.defaults());
        UmlRootReservationRegistry reservationRegistry = new UmlRootReservationRegistry();
        LoopbackEndpointRegistry endpointRegistry = new LoopbackEndpointRegistry();
        byte[] tarGz = buildMinimalAlpineTarGz();

        FakeUmlProcessHandle handleA = new FakeUmlProcessHandle(5001L, 0);
        FakeUmlProcessHandle handleB = new FakeUmlProcessHandle(5002L, 0);
        handlesToRelease.add(handleA);
        handlesToRelease.add(handleB);
        java.util.concurrent.atomic.AtomicReference<String> instAId =
                new java.util.concurrent.atomic.AtomicReference<>();

        UmlInstanceLauncher launcher = new UmlInstanceLauncher(
                nativeDir,
                filesDir,
                (spec, cols, rows) -> {
                    String idA = instAId.get();
                    return (idA != null && spec.runtimeDir().toString().contains(idA))
                            ? handleA
                            : handleB;
                },
                reservationRegistry,
                budget,
                endpointRegistry,
                () -> new ByteArrayInputStream(tarGz),
                System::nanoTime,
                200L,
                200L,
                200L);

        InstanceLifecycle.FileStore store =
                new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller =
                new InstanceLifecycle.Controller(store, FIXED_CLOCK);
        IsolatedQaExecutionEntry qaEntry = new IsolatedQaExecutionEntry(() -> true);

        InstanceLifecycle.Instance instA = qaEntry.createStandaloneUmlInstance(
                store, controller, filesDir.toPath().resolve("qa-uml-roots"), "uml-a", 512);
        instAId.set(instA.id());
        InstanceLifecycle.Instance instB = qaEntry.createStandaloneUmlInstance(
                store, controller, filesDir.toPath().resolve("qa-uml-roots"), "uml-b", 512);
        InstanceLifecycle.Instance instC = qaEntry.createStandaloneUmlInstance(
                store, controller, filesDir.toPath().resolve("qa-uml-roots"), "uml-c", 600);
        cleanupInstanceIds.add(instA.id());
        cleanupInstanceIds.add(instB.id());
        cleanupInstanceIds.add(instC.id());

        int baselineSessions = ExecutionSessionRegistry.activeSessionCount();
        RecordingForeground foreground = new RecordingForeground();
        InstanceLaunchCoordinator coordinator = createCoordinator(
                store, filesDir, foreground, launcher, rideAlong);

        InstanceLaunchCoordinator.LaunchOutcome outA = coordinator.launch(instA);
        InstanceLaunchCoordinator.LaunchOutcome outB = coordinator.launch(instB);
        assertTrue(outA instanceof InstanceLaunchCoordinator.LaunchOutcome.Launched);
        assertTrue(outB instanceof InstanceLaunchCoordinator.LaunchOutcome.Launched);

        assertEquals(baselineSessions + 2, ExecutionSessionRegistry.activeSessionCount());
        assertTrue(ExecutionSessionRegistry.activeInstanceIds().contains(instA.id()));
        assertTrue(ExecutionSessionRegistry.activeInstanceIds().contains(instB.id()));
        assertEquals(2, foreground.binding.activeCount());
        assertTrue(foreground.binding.isActive());
        assertEquals(1024, budget.committedStandaloneMb());
        assertEquals(1536, budget.snapshot().committedStandaloneMb() + budget.snapshot().rideAlongRamMb());
        assertTrue(reservationRegistry.isReserved(instA.rootVolume()));
        assertTrue(reservationRegistry.isReserved(instB.rootVolume()));
        assertEquals(1, UmlFaceStatusRegistry.runningWidth());

        // Same instance re-launch attaches without creating a duplicate process or lease.
        assertSame(
                InstanceLaunchCoordinator.LaunchOutcome.Attached.INSTANCE,
                coordinator.launch(store.load(instA.id())));

        // Third instance exceeding ceiling (512 ride-along + 1024 active + 600 > 2048) is rejected cleanly.
        InstanceLaunchCoordinator.LaunchOutcome outC = coordinator.launch(instC);
        assertTrue(outC instanceof InstanceLaunchCoordinator.LaunchOutcome.Failed);
        assertEquals(InstanceLifecycle.State.STOPPED, store.load(instC.id()).state());
        assertFalse(reservationRegistry.isReserved(instC.rootVolume()));
        assertNull(ExecutionSessionRegistry.get(instC.id()));
        assertEquals(2, foreground.binding.activeCount());
        assertEquals(1024, budget.committedStandaloneMb());

        // Stop instA: instB, its foreground lease, its budget lease, and the ride-along face stay intact.
        handleA.alive.set(false);
        handleA.exitGate.countDown();
        assertTrue(foreground.awaitStopped(instA.id(), 5_000L));
        assertNull(ExecutionSessionRegistry.get(instA.id()));
        assertFalse(reservationRegistry.isReserved(instA.rootVolume()));
        assertNotNull(ExecutionSessionRegistry.get(instB.id()));
        assertTrue(reservationRegistry.isReserved(instB.rootVolume()));
        assertEquals(1, foreground.binding.activeCount());
        assertTrue(foreground.binding.isActive());
        assertEquals(512, budget.committedStandaloneMb());
        assertEquals(1, UmlFaceStatusRegistry.runningWidth());
        assertEquals(0, rideAlongKeepAliveReleases.get());

        // Stop instB: now foreground drops, budget drops to 0, ride-along remains until explicitly disarmed.
        handleB.alive.set(false);
        handleB.exitGate.countDown();
        assertTrue(foreground.awaitStopped(instB.id(), 5_000L));
        assertNull(ExecutionSessionRegistry.get(instB.id()));
        assertFalse(reservationRegistry.isReserved(instB.rootVolume()));
        assertEquals(0, foreground.binding.activeCount());
        assertFalse(foreground.binding.isActive());
        assertEquals(0, budget.committedStandaloneMb());
        assertEquals(1, UmlFaceStatusRegistry.runningWidth());

        rideAlong.disarm("qemu-host");
        assertEquals(0, UmlFaceStatusRegistry.runningWidth());
        assertEquals(1, rideAlongKeepAliveReleases.get());
    }

    @Test(timeout = 15_000)
    public void staleGenerationLateExitAndDirectDuplicateLaunchNeverAffectCurrentGeneration()
            throws Exception {
        Path root = tmp.newFolder("stale-late").toPath();
        File filesDir = root.resolve("files").toFile();
        File nativeDir = root.resolve("native").toFile();
        assertTrue(filesDir.mkdirs());
        assertTrue(nativeDir.mkdirs());
        Path rootVol = root.resolve("uml-stale.raw");
        Files.createFile(rootVol);
        String id = java.util.UUID.randomUUID().toString();
        cleanupInstanceIds.add(id);

        UmlInstanceBudget budget = new UmlInstanceBudget(
                () -> new UmlInstanceBudget.HostMemorySnapshot(8192, 4096, false),
                () -> 0,
                UmlInstanceBudget.BudgetPolicy.defaults());
        UmlRootReservationRegistry reservationRegistry = new UmlRootReservationRegistry();
        LoopbackEndpointRegistry endpointRegistry = new LoopbackEndpointRegistry();
        byte[] tarGz = buildMinimalAlpineTarGz();

        FakeUmlProcessHandle handle1 = new FakeUmlProcessHandle(5101L, 0);
        FakeUmlProcessHandle handle2 = new FakeUmlProcessHandle(5102L, 0);
        handlesToRelease.add(handle1);
        handlesToRelease.add(handle2);
        AtomicInteger spawnCount = new AtomicInteger();

        UmlInstanceLauncher launcher = new UmlInstanceLauncher(
                nativeDir,
                filesDir,
                (spec, cols, rows) -> spawnCount.getAndIncrement() == 0 ? handle1 : handle2,
                reservationRegistry,
                budget,
                endpointRegistry,
                () -> new ByteArrayInputStream(tarGz),
                System::nanoTime,
                100L,
                100L,
                100L);

        InstanceStrategyLauncher.LaunchParameters params = new InstanceStrategyLauncher.LaunchParameters(
                id,
                TOKEN,
                "alpine-minirootfs.tar.gz",
                rootVol.toString(),
                root.resolve("rt1").toString(),
                root.toString(),
                "orchestellaqa",
                VmHardwareConfig.builder().cpuCores(1).ramMegabytes(512).build());

        String gen1 = InstanceLaunchGuard.begin(id);
        UmlInstanceLauncher.Session session1 = (UmlInstanceLauncher.Session) launcher.launch(params);
        assertEquals(gen1, session1.launchGeneration());
        assertEquals(gen1, ExecutionSessionRegistry.generation(id, session1));

        // Direct duplicate launch while session1 is live fails closed without clearing session1 or budget.
        Path altRoot = root.resolve("uml-alt.raw");
        Files.createFile(altRoot);
        InstanceStrategyLauncher.LaunchParameters dupParams = new InstanceStrategyLauncher.LaunchParameters(
                id,
                TOKEN,
                "alpine-minirootfs.tar.gz",
                altRoot.toString(),
                root.resolve("rt-dup").toString(),
                root.toString(),
                "orchestellaqa",
                VmHardwareConfig.builder().cpuCores(1).ramMegabytes(512).build());
        assertThrows(UmlInstanceBudget.BudgetRefusalException.class, () -> launcher.launch(dupParams));
        assertFalse("rejected duplicate must not leak altRoot reservation",
                reservationRegistry.isReserved(altRoot));
        assertSame(session1, ExecutionSessionRegistry.get(id));
        assertTrue(budget.hasActiveLease(id, gen1));

        // Retire gen1 cleanly and start gen2.
        handle1.alive.set(false);
        handle1.exitGate.countDown();
        assertEquals(StopResult.ALREADY_STOPPED, session1.stopRuntime(100L));
        ExecutionSessionRegistry.unregister(id, session1);
        InstanceLaunchGuard.end(id, gen1);

        String gen2 = InstanceLaunchGuard.begin(id);
        InstanceStrategyLauncher.LaunchParameters params2 = new InstanceStrategyLauncher.LaunchParameters(
                id,
                TOKEN,
                "alpine-minirootfs.tar.gz",
                rootVol.toString(),
                root.resolve("rt2").toString(),
                root.toString(),
                "orchestellaqa",
                VmHardwareConfig.builder().cpuCores(1).ramMegabytes(512).build());
        UmlInstanceLauncher.Session session2 = (UmlInstanceLauncher.Session) launcher.launch(params2);
        assertEquals(gen2, session2.launchGeneration());
        assertTrue(budget.hasActiveLease(id, gen2));

        // Late exit/unregister against session1 must not touch session2 or gen2's budget/reservation.
        assertFalse(ExecutionSessionRegistry.unregister(id, session1));
        assertFalse(ExecutionSessionRegistry.completeExit(id, session1, () -> {}));
        assertSame(session2, ExecutionSessionRegistry.get(id));
        assertTrue(budget.hasActiveLease(id, gen2));
        assertTrue(reservationRegistry.isReserved(rootVol));

        handle2.alive.set(false);
        handle2.exitGate.countDown();
        session2.stopRuntime(100L);
        assertFalse(reservationRegistry.isReserved(rootVol));
        assertEquals(0, budget.committedStandaloneMb());
    }

    @Test(timeout = 15_000)
    public void twoPasstNetworksRejectPortConflictAndKeepOriginalRuleIntact() throws Exception {
        Path rtA = tmp.newFolder("net-a").toPath();
        Path rtB = tmp.newFolder("net-b").toPath();
        Path storeRoot = tmp.newFolder("net-store").toPath();
        LoopbackEndpointRegistry endpoints = new LoopbackEndpointRegistry();

        UmlPasstNetwork netA = new UmlPasstNetwork(
                rtA, storeRoot, "inst-a", "gen-a", TOKEN,
                () -> "/fake/libuml_passt.so", 10, () -> {},
                (argv, pidFile) -> null, new StaticOfflineMonitor(), endpoints);
        UmlPasstNetwork netB = new UmlPasstNetwork(
                rtB, storeRoot, "inst-b", "gen-b", TOKEN,
                () -> "/fake/libuml_passt.so", 11, () -> {},
                (argv, pidFile) -> null, new StaticOfflineMonitor(), endpoints);

        netA.restoreBeforePublish();
        netB.restoreBeforePublish();
        try {
            int sharedPort = freePort();
            PortForwardingRule ruleA = PortForwardingRule.defaultRule(
                    "rule-a", Protocol.TCP, sharedPort, 18080);
            PortForwardingRule ruleB = PortForwardingRule.defaultRule(
                    "rule-b", Protocol.TCP, sharedPort, 18081);

            netA.addRule(ruleA);
            assertTrue(netA.isApplied(ruleA));
            assertEquals(1, endpoints.activeEndpointCount());

            // Competing instance fails closed and leaves netA's rule bound and owned.
            assertThrows(IOException.class, () -> netB.addRule(ruleB));
            assertTrue(netA.isApplied(ruleA));
            assertFalse(netB.isApplied(ruleB));
            assertEquals("inst-a", endpoints.ownerOf("127.0.0.1", sharedPort).instanceId());

            // Compiler ports remain blocked.
            assertThrows(IOException.class, () -> netB.addRule(
                    PortForwardingRule.defaultRule("comp", Protocol.TCP, 3632, 3632)));

            // Once netA releases the port, netB can acquire it.
            netA.removeRule(ruleA);
            assertNull(endpoints.ownerOf("127.0.0.1", sharedPort));
            netB.addRule(ruleB);
            assertTrue(netB.isApplied(ruleB));
            assertEquals("inst-b", endpoints.ownerOf("127.0.0.1", sharedPort).instanceId());
        } finally {
            netA.closeAfterGuestExit();
            netB.closeAfterGuestExit();
        }
        assertEquals(0, endpoints.activeEndpointCount());
    }

    private static InstanceLaunchCoordinator createCoordinator(
            InstanceLifecycle.FileStore store,
            File filesDir,
            RecordingForeground foreground,
            UmlInstanceLauncher umlLauncher,
            UmlFaceRideAlong rideAlong) {
        return new InstanceLaunchCoordinator(
                store,
                new InstanceLaunchCoordinator.LaunchSettings() {
                    @Override public void setLastActiveInstanceId(String instanceId) {}
                    @Override public BatteryPolicy batteryPolicy() { return BatteryPolicy.BALANCED; }
                    @Override public boolean isAcceleratorLaneEnabled() { return false; }
                },
                foreground,
                new InstanceLaunchCoordinator.LaunchUi() {
                    @Override public void runOnUi(Runnable update) { update.run(); }
                    @Override public void resetCreateButtons() {}
                    @Override public void showStatus(String message) {}
                    @Override public void showAttached(InstanceLifecycle.Instance instance) {}
                    @Override public void showLaunchBusyStatus() {}
                    @Override public void showLaunchSuccess(InstanceLaunchCoordinator.LaunchNavigation nav) {}
                    @Override public void surfaceLaunchFailure(String message) {}
                    @Override public void showInstances() {}
                },
                new InstanceLaunchCoordinator.LaunchCopy() {
                    @Override public String detailMessage(Throwable failure) { return failure.getMessage(); }
                    @Override public String guestExitedStatus() { return "exited"; }
                    @Override public String launchStartedHardwareStatus() { return "hw"; }
                    @Override public String launchDegradedToSoftwareStatus(String reason) { return reason; }
                },
                new InstanceLaunchCoordinator.LaunchDiagnostics() {
                    @Override public void logAcceleratorSnapshot(String line) {}
                    @Override public void logLaunchFailure(String failureType) {}
                },
                UmlInstanceLauncher::takeLastCleanupResult,
                filesDir,
                () -> "QEMU",
                () -> false,
                params -> { throw new AssertionError("AVF unexpected"); },
                params -> { throw new AssertionError("QEMU unexpected"); },
                id -> { throw new AssertionError("CustomVm unexpected"); },
                id -> { throw new AssertionError("PVM unexpected"); },
                () -> () -> AcceleratorFacts.builder()
                        .avfPresent(false)
                        .nonProtectedSupported(false)
                        .acceleratorImagePresent(false)
                        .vsockSupported(false)
                        .compilerTriple("")
                        .thermalState(ThermalState.NOMINAL)
                        .batteryState(BatteryState.OK)
                        .build(),
                Optional::empty,
                event -> {},
                LaneClock.system(),
                FIXED_CLOCK,
                () -> {},
                rideAlong,
                () -> true,
                umlLauncher);
    }

    private static void createExecutableFile(Path path) throws IOException {
        Files.createFile(path);
        path.toFile().setExecutable(true);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static final class StaticOfflineMonitor implements UmlPasstNetwork.RouteMonitor {
        @Override public UmlPasstNetwork.HostRoute snapshot() { return new UmlPasstNetwork.HostRoute(null, null); }
        @Override public AutoCloseable observe(Runnable onChange) { return () -> {}; }
    }

    private static final class RecordingForeground implements InstanceLaunchCoordinator.ForegroundServiceHandle {
        final ForegroundSessionBinding binding = new ForegroundSessionBinding();
        final java.util.Set<String> stoppedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Object monitor = new Object();

        @Override
        public synchronized void start(
                String instanceId,
                String distroTitle,
                String performanceBadge,
                BatteryPolicy batteryPolicy,
                String launchGeneration) {
            stoppedIds.remove(instanceId);
            binding.start(instanceId, launchGeneration, distroTitle, performanceBadge, batteryPolicy);
        }

        @Override
        public void stop(String instanceId, String generation) {
            synchronized (this) {
                binding.stopLease(instanceId, generation);
            }
            synchronized (monitor) {
                if (instanceId != null) {
                    stoppedIds.add(instanceId);
                }
                monitor.notifyAll();
            }
        }

        boolean awaitStopped(String instanceId, long timeoutMs) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            synchronized (monitor) {
                while (!stoppedIds.contains(instanceId)) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
                }
                return true;
            }
        }
    }

    private static final class FakeFaceProcess implements UmlFaceLauncher.UmlProcess {
        final AtomicBoolean alive = new AtomicBoolean(true);

        @Override public boolean isAlive() { return alive.get(); }
        @Override public void destroy() { alive.set(false); }
        @Override public void destroyForcibly() { alive.set(false); }
        @Override public boolean waitForExit(long timeoutMs) { return !alive.get(); }
    }

    private static final class FakeUmlProcessHandle implements UmlProcessHandle {
        private final long pid;
        private final int exitCode;
        final CountDownLatch exitGate = new CountDownLatch(1);
        final AtomicBoolean alive = new AtomicBoolean(true);
        private final PipedOutputStream guestPtyOut = new PipedOutputStream();
        private final PipedInputStream hostPtyIn;
        private final ByteArrayOutputStream hostPtyOut = new ByteArrayOutputStream();
        private final PipedOutputStream guestCtrlOut = new PipedOutputStream();
        private final PipedInputStream hostCtrlIn;
        private final ByteArrayOutputStream hostCtrlOut = new ByteArrayOutputStream();

        FakeUmlProcessHandle(long pid, int exitCode) throws IOException {
            this.pid = pid;
            this.exitCode = exitCode;
            this.hostPtyIn = new PipedInputStream(guestPtyOut, 4096);
            this.hostCtrlIn = new PipedInputStream(guestCtrlOut, 4096);
        }

        @Override public long pid() { return pid; }
        @Override public boolean isAlive() { return alive.get(); }
        @Override public int waitFor() throws InterruptedException {
            exitGate.await();
            return exitCode;
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exitGate.await(timeout, unit);
        }
        @Override public void destroy() { alive.set(false); exitGate.countDown(); }
        @Override public void destroyForcibly() { alive.set(false); exitGate.countDown(); }
        @Override public OutputStream terminalInput() { return hostPtyOut; }
        @Override public InputStream terminalOutput() { return hostPtyIn; }
        @Override public OutputStream controlInput() { return hostCtrlOut; }
        @Override public InputStream controlOutput() { return hostCtrlIn; }
        @Override public boolean resizeTerminal(int columns, int rows) { return true; }
        @Override public void closeChannels() {
            try { hostPtyIn.close(); } catch (IOException ignored) {}
            try { hostCtrlIn.close(); } catch (IOException ignored) {}
            try { guestPtyOut.close(); } catch (IOException ignored) {}
            try { guestCtrlOut.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] buildMinimalAlpineTarGz() throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        byte[] content = "echo ok\n".getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        byte[] name = "bin/sh".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, header, 0, name.length);
        writeOctal(header, 100, 8, 0755);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, content.length);
        writeOctal(header, 136, 12, 1_700_000_000L);
        for (int i = 148; i < 156; i++) header[i] = ' ';
        header[156] = '0';
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        System.arraycopy("00".getBytes(StandardCharsets.US_ASCII), 0, header, 263, 2);
        long sum = 0;
        for (byte b : header) sum += (b & 0xFF);
        writeOctal(header, 148, 8, sum);
        tar.write(header);
        tar.write(content);
        int pad = 512 - (content.length % 512);
        if (pad < 512) tar.write(new byte[pad]);
        tar.write(new byte[1024]);

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void writeOctal(byte[] buf, int off, int len, long value) {
        String s = Long.toOctalString(value);
        int digits = len - 1;
        for (int i = 0; i < digits; i++) buf[off + i] = '0';
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, buf, off + Math.max(0, digits - bytes.length), Math.min(digits, bytes.length));
        buf[off + len - 1] = 0;
    }
}
