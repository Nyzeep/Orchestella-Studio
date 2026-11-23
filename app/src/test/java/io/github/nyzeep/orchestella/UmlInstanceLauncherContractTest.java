package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.RuntimeProcessSupervisor.StopResult;
import io.github.nyzeep.orchestella.runtime.contract.BridgeClient;
import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import io.github.nyzeep.orchestella.runtime.contract.BridgeHandshake;
import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlBridgeAdapter;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlEphemeralRootFixture;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessFactory;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessHandle;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlReadinessTracker;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRootReservationRegistry;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlRuntimeSupervisor;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.BatteryState;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.ThermalState;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pure-JVM integration and failure-injection contract suite for W3-01 / R-01:
 * {@link UmlInstanceLauncher}, {@link IsolatedQaExecutionEntry#ensureEphemeralAlpineInstance},
 * {@link BridgeConnector}, {@link PersistentTerminalSessionPool}, and
 * {@link InstanceLaunchCoordinator}.
 */
public final class UmlInstanceLauncherContractTest {
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Rule public final TemporaryFolder tmp = new TemporaryFolder();

    private final List<String> cleanupInstanceIds = new ArrayList<>();

    @After
    public void cleanupRegistryAndGuards() {
        for (String id : cleanupInstanceIds) {
            ExecutionSessionRegistry.unregister(id);
            UmlInstanceLauncher.takeLastCleanupResult(id);
            InstanceLaunchGuard.end(id);
        }
        cleanupInstanceIds.clear();
    }

    @Test(timeout = 20_000)
    public void happyPathThreeMilestonesPtyResizeCtrlCCleanExitAndRootReuse() throws Exception {
        Path root = tmp.newFolder("happy").toPath();
        File filesDir = root.resolve("files").toFile();
        File nativeDir = root.resolve("native").toFile();
        assertTrue(new File(filesDir, "instances").mkdirs());
        assertTrue(nativeDir.mkdirs());
        Files.createFile(nativeDir.toPath().resolve("libuml_kernel.so"));
        Files.createFile(nativeDir.toPath().resolve("libuml_stub.so"));

        Path storeDir = filesDir.toPath().resolve("instances");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);

        IsolatedQaExecutionEntry qaEntry = new IsolatedQaExecutionEntry(() -> true);
        InstanceLifecycle.Instance alpine = qaEntry.ensureEphemeralAlpineInstance(
                store, controller, filesDir.toPath().resolve("qa-uml-roots"));
        cleanupInstanceIds.add(alpine.id());
        assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                alpine.executionIdentity().strategy().strategy());
        assertEquals(StorageContract.RootVolumeFormat.RAW_SPARSE,
                alpine.executionIdentity().volumeFormat().format());
        assertEquals(alpine.id(), qaEntry.ensureEphemeralAlpineInstance(
                store, controller, filesDir.toPath().resolve("qa-uml-roots")).id());

        byte[] tarGz = buildMinimalAlpineTarGz();
        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        AtomicLong monoNanos = new AtomicLong(1_000_000L);
        List<UmlLaunchSpec> capturedSpecs = new ArrayList<>();

        CountDownLatch exitGate = new CountDownLatch(1);
        FakeUmlProcessHandle handle1 = new FakeUmlProcessHandle(4101L, exitGate, 0);
        FakeUmlProcessHandle handle2 = new FakeUmlProcessHandle(4102L, new CountDownLatch(0), 0);
        AtomicInteger spawnCount = new AtomicInteger();
        UmlProcessFactory factory = (spec, cols, rows) -> {
            capturedSpecs.add(spec);
            return spawnCount.getAndIncrement() == 0 ? handle1 : handle2;
        };

        UmlInstanceLauncher launcher = new UmlInstanceLauncher(
                nativeDir, filesDir, factory, registry,
                () -> new ByteArrayInputStream(tarGz),
                () -> monoNanos.addAndGet(1_000_000L),
                500L, 500L, 500L);

        RecordingUmlFaceLane faceLane = new RecordingUmlFaceLane();
        RecordingUi ui = new RecordingUi();
        InstanceLaunchCoordinator coordinator = coordinator(
                store, filesDir, true, launcher, faceLane, ui);

        AtomicReference<Throwable> coordinatorFailure = new AtomicReference<>();
        CountDownLatch launchDone = new CountDownLatch(1);
        Thread coordinatorThread = new Thread(() -> {
            try {
                InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(alpine);
                if (outcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Failed failed) {
                    coordinatorFailure.set(failed.getFailure());
                }
            } finally {
                launchDone.countDown();
            }
        }, "qa-coordinator-1");
        coordinatorThread.start();

        UmlInstanceLauncher.Session session =
                (UmlInstanceLauncher.Session) awaitRegisteredSession(alpine.id(), 5_000);
        assertEquals(1, spawnCount.get());
        awaitCondition(() -> ui.successes.size() == 1, 3_000, "UI navigation not surfaced");
        assertEquals(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                session.capabilityMatrix().executionStrategy());
        assertFalse(session.capabilityMatrix().supports(FullLinuxContract.Capability.GRAPHICS_RFB));
        assertFalse(session.capabilityMatrix().supports(FullLinuxContract.Capability.AUDIO_OUTPUT));
        assertFalse(session.capabilityMatrix().supports(FullLinuxContract.Capability.HARDWARE_ACCELERATION));
        assertFalse(session.capabilityMatrix().supports(FullLinuxContract.Capability.SHARED_DIRECTORY));
        assertEquals(
                FullLinuxContract.CapabilityMatrix.userModeKernelBaseline().performanceBadge(),
                session.capabilityMatrix().performanceBadge());
        assertTrue("ephemeral root must be reserved while live", registry.isReserved(alpine.rootVolume()));
        assertTrue("spec must include console=tty0", capturedSpecs.get(0).argv().contains("console=tty0"));
        assertTrue("ephemeral rootfs must exist while live", Files.isDirectory(session.ephemeralRootDir()));
        assertFalse("ride-along face directory must stay untouched",
                Files.exists(filesDir.toPath().resolve("uml-rootfs")));
        assertEquals(0, faceLane.arms.get());

        // Process has started, but neither Bridge nor shell is ready yet.
        assertEquals(UmlReadinessTracker.Phase.PROCESS_STARTED,
                session.readinessTracker().snapshot().phase());

        // Start guest Bridge v1 adapter on pipe end and guest PTY echo simulator.
        CountDownLatch bridgeResized = new CountDownLatch(1);
        AtomicInteger bridgeResizeCols = new AtomicInteger();
        AtomicInteger bridgeResizeRows = new AtomicInteger();
        AtomicBoolean powerOffCalled = new AtomicBoolean(false);
        Thread guestBridgeThread = new Thread(() -> {
            try {
                UmlBridgeAdapter adapter = new UmlBridgeAdapter(
                        handle1.guestControlIn(),
                        handle1.guestControlOut(),
                        alpine.bridgeToken(),
                        (cols, rows) -> {
                            bridgeResizeCols.set(cols);
                            bridgeResizeRows.set(rows);
                            bridgeResized.countDown();
                            return true;
                        },
                        action -> {
                            if (action == BridgeControl.PowerAction.POWEROFF) {
                                powerOffCalled.set(true);
                                exitGate.countDown();
                            }
                            return true;
                        });
                adapter.serve();
            } catch (IOException ignored) {
            }
        }, "guest-bridge-1");
        guestBridgeThread.setDaemon(true);
        guestBridgeThread.start();

        CountDownLatch ctrlCSaw = new CountDownLatch(1);
        Thread guestPtyThread = new Thread(() -> {
            try {
                byte[] buf = new byte[256];
                int n;
                while ((n = handle1.guestPtyIn().read(buf)) != -1) {
                    for (int i = 0; i < n; i++) {
                        if (buf[i] == 0x03) {
                            ctrlCSaw.countDown();
                            handle1.guestPtyOut().write("^C\r\n".getBytes(StandardCharsets.UTF_8));
                            handle1.guestPtyOut().flush();
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "guest-pty-1");
        guestPtyThread.setDaemon(true);
        guestPtyThread.start();

        // Pre-Bridge OSC 133 prompt must not promote SHELL_READY before BRIDGE_READY.
        PersistentTerminalSessionPool.SessionHolder holder =
                PersistentTerminalSessionPool.getInstance().getHolder(alpine.id());
        assertNotNull("session holder must be published in terminal pool", holder);
        CountDownLatch readyLatch = new CountDownLatch(1);
        AtomicReference<Throwable> bridgeServeError = new AtomicReference<>();
        Thread hostBridgeThread = new Thread(() -> {
            try {
                new BridgeConnector().serveSession(
                        session, alpine.bridgeToken(), 80, 24,
                        new BridgeConnector.Events() {
                            @Override public void onReady() { readyLatch.countDown(); }
                            @Override public void onPong() {}
                            @Override public void onResize(int columns, int rows) {}
                        }, null, null, null);
            } catch (Throwable failure) {
                if (readyLatch.getCount() > 0) {
                    bridgeServeError.set(failure);
                }
            }
        }, "host-bridge-connector");
        hostBridgeThread.setDaemon(true);
        hostBridgeThread.start();
        assertTrue("Bridge handshake must succeed", readyLatch.await(5, TimeUnit.SECONDS));
        assertNull(bridgeServeError.get());
        assertTrue(session.readinessTracker().snapshot().bridgeReady());

        // Guest sends OSC 133 prompt after Bridge READY -> SHELL_READY milestone reached.
        String prompt = "\u001b]133;A;orchestella=1\u0007root@qa-uml-alpine:~# \u001b]133;B\u0007";
        handle1.guestPtyOut().write(prompt.getBytes(StandardCharsets.UTF_8));
        handle1.guestPtyOut().flush();
        awaitCondition(() -> session.readinessTracker().snapshot().shellReady(), 5_000,
                "SHELL_READY never observed");

        // Verify TIOCSWINSZ resize + Bridge resize + Ctrl-C delivery.
        assertTrue(session.resizeTerminalHost(120, 40));
        assertEquals(120, handle1.resizeCols.get());
        assertEquals(40, handle1.resizeRows.get());
        BridgeClient.Result resizeRes = session.bridgeClient().resize(120, 40);
        assertTrue(resizeRes.accepted());
        assertTrue(bridgeResized.await(3, TimeUnit.SECONDS));
        assertEquals(120, bridgeResizeCols.get());
        assertEquals(40, bridgeResizeRows.get());

        holder.sendBytes(new byte[] {0x03});
        assertTrue("Ctrl-C byte must reach UML PTY", ctrlCSaw.await(3, TimeUnit.SECONDS));
        awaitCondition(() -> holder.getTranscript().contains("^C"), 3_000,
                "PTY echo missing ^C");

        // Trigger clean guest shutdown and confirm coordinator releases root + ephemeral dir.
        session.terminate();
        assertTrue(powerOffCalled.get());
        assertTrue("Coordinator launch thread must finish after guest exit",
                launchDone.await(5, TimeUnit.SECONDS));
        assertNull(coordinatorFailure.get());
        awaitCondition(() -> isStopped(store, alpine.id()) && !registry.isReserved(alpine.rootVolume()),
                5_000, "coordinator exit-watcher did not transition instance1 to STOPPED and release root");
        assertFalse("ephemeral rootfs must be cleaned after reap",
                Files.exists(session.ephemeralRootDir()));

        // Timeline must contain UML_STARTED, SESSION_REGISTERED, BRIDGE_READY, SHELL_READY, STOP_CONFIRMED.
        Path timelineFile = filesDir.toPath().resolve("runtime").resolve(alpine.id())
                .resolve(LaunchTimeline.FILE_NAME);
        String timelineLine = String.join("\n", Files.readAllLines(timelineFile, StandardCharsets.UTF_8));
        assertTrue(timelineLine.contains("\"UML_STARTED\""));
        assertTrue(timelineLine.contains("\"SESSION_REGISTERED\""));
        assertTrue(timelineLine.contains("\"BRIDGE_READY\""));
        assertTrue(timelineLine.contains("\"SHELL_READY\""));
        assertTrue(timelineLine.contains("\"STOP_CONFIRMED\""));

        // Reuse the same persistent instance metadata & root volume for a second launch.
        InstanceLaunchCoordinator.LaunchOutcome second = coordinator.launch(store.load(alpine.id()));
        assertTrue(second instanceof InstanceLaunchCoordinator.LaunchOutcome.Launched);
        assertEquals(2, spawnCount.get());
        awaitCondition(() -> isStopped(store, alpine.id()) && !registry.isReserved(alpine.rootVolume()),
                5_000, "second coordinator launch did not finish transitioning to STOPPED and release root");
    }

    @Test(timeout = 15_000)
    public void bridgeUnsupportedVersionUnauthorizedAndTimeoutNeverPromoteReady() throws Exception {
        byte[] tarGz = buildMinimalAlpineTarGz();

        // 1. Unsupported version rejection over Bridge pipe must never promote BRIDGE_READY / SHELL_READY.
        assertBridgeRejectionNeverPromotesReady(
                tarGz,
                handle -> "ORCHESTELLA/ERROR unsupported-version\n",
                false);

        // 2. Unauthorized rejection over Bridge pipe must never promote BRIDGE_READY / SHELL_READY.
        assertBridgeRejectionNeverPromotesReady(
                tarGz,
                handle -> "ORCHESTELLA/ERROR unauthorized\n",
                false);

        // 3. Handshake timeout must notify READINESS_FAILED and keep SHELL_READY false even after OSC 133 prompt.
        assertBridgeRejectionNeverPromotesReady(
                tarGz,
                null,
                true);
    }

    private void assertBridgeRejectionNeverPromotesReady(
            byte[] tarGz,
            java.util.function.Function<FakeUmlProcessHandle, String> errorLineFactory,
            boolean simulateTimeout) throws Exception {
        Path root = tmp.newFolder().toPath();
        File filesDir = root.resolve("files").toFile();
        File nativeDir = root.resolve("native").toFile();
        assertTrue(filesDir.mkdirs());
        assertTrue(nativeDir.mkdirs());
        Path runtimeDir = root.resolve("runtime");
        Path rootVol = root.resolve("qa-uml.raw");
        Files.createFile(rootVol);
        String id = UUID.randomUUID().toString();
        cleanupInstanceIds.add(id);

        CountDownLatch exitGate = new CountDownLatch(1);
        FakeUmlProcessHandle handle = new FakeUmlProcessHandle(4200L, exitGate, 0);
        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        UmlInstanceLauncher launcher = new UmlInstanceLauncher(
                nativeDir, filesDir, (spec, cols, rows) -> handle, registry,
                () -> new ByteArrayInputStream(tarGz), System::nanoTime,
                200L, 200L, 200L);

        InstanceStrategyLauncher.LaunchParameters params = new InstanceStrategyLauncher.LaunchParameters(
                id, TOKEN, "alpine-minirootfs.tar.gz", rootVol.toString(),
                runtimeDir.toString(), root.toString(),
                "orchestellaqa", VmHardwareConfig.defaultConfig());

        UmlInstanceLauncher.Session session = (UmlInstanceLauncher.Session) launcher.launch(params);
        try {
            if (simulateTimeout) {
                session.bridgeClient().startResponsePump(null, 80L);
                try {
                    new BridgeConnector().serveSession(
                            session, TOKEN, 80, 24, null, null, null, null);
                    fail("stalled handshake must time out");
                } catch (IOException expectedTimeout) {
                    // Expected timeout through BridgeConnector.serveSession.
                }
            } else {
                Thread responder = new Thread(() -> {
                    try {
                        byte[] buf = new byte[256];
                        while (handle.guestControlIn().read(buf) != -1) {
                            String err = errorLineFactory.apply(handle);
                            handle.guestControlOut().write(err.getBytes(StandardCharsets.US_ASCII));
                            handle.guestControlOut().flush();
                        }
                    } catch (IOException ignored) {
                    }
                });
                responder.setDaemon(true);
                responder.start();
                try {
                    BridgeConnector.Session rejected = new BridgeConnector().serveSession(
                            session, TOKEN, 80, 24, null, null, null, null);
                    assertTrue("rejected handshake must return non-zero outcome",
                            rejected.outcome != 0);
                } catch (IOException expected) {
                    // Transport-level error/timeout rejection is also valid.
                }
            }

            // Even if PTY emits a full OSC 133 prompt, readiness stays rejected/unpromoted.
            session.markShellReady();
            UmlReadinessTracker.Snapshot snapshot = session.readinessTracker().snapshot();
            assertFalse("rejected Bridge must never be bridgeReady", snapshot.bridgeReady());
            assertFalse("rejected Bridge must never promote shellReady", snapshot.shellReady());
            assertEquals(UmlReadinessTracker.Phase.FAILED, snapshot.phase());
            if (simulateTimeout) {
                assertEquals(UmlReadinessTracker.RejectionReason.TIMEOUT, snapshot.rejectionReason());
            } else {
                assertEquals(UmlReadinessTracker.RejectionReason.UNAUTHORIZED, snapshot.rejectionReason());
            }
        } finally {
            exitGate.countDown();
            handle.alive.set(false);
            session.stopRuntime(200L);
        }
    }

    @Test(timeout = 15_000)
    public void unconfirmedStopRetainsRunningMetadataAndRootReservationUntilReaped() throws Exception {
        Path root = tmp.newFolder("unconfirmed").toPath();
        File filesDir = root.resolve("files").toFile();
        File nativeDir = root.resolve("native").toFile();
        assertTrue(new File(filesDir, "instances").mkdirs());
        assertTrue(nativeDir.mkdirs());
        Path storeDir = filesDir.toPath().resolve("instances");
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(storeDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);

        IsolatedQaExecutionEntry qaEntry = new IsolatedQaExecutionEntry(() -> true);
        InstanceLifecycle.Instance alpine = qaEntry.ensureEphemeralAlpineInstance(
                store, controller, filesDir.toPath().resolve("qa-uml-roots"));
        cleanupInstanceIds.add(alpine.id());

        byte[] tarGz = buildMinimalAlpineTarGz();
        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();

        // Stubborn process ignores SIGTERM/SIGKILL until unlocked manually.
        CountDownLatch exitGate = new CountDownLatch(1);
        FakeUmlProcessHandle stubborn = new FakeUmlProcessHandle(4301L, exitGate, 137);
        stubborn.ignoreSignals = true;

        UmlInstanceLauncher launcher = new UmlInstanceLauncher(
                nativeDir, filesDir, (spec, cols, rows) -> stubborn, registry,
                () -> new ByteArrayInputStream(tarGz), System::nanoTime,
                40L, 40L, 200L);

        // 1. Coordinator exit-watch hits UNCONFIRMED stop -> retains RUNNING metadata, session owner, and root reservation.
        InstanceLaunchCoordinator coordinator = coordinator(
                store, filesDir, true, launcher, new RecordingUmlFaceLane(), new RecordingUi());
        CountDownLatch coordinatorFinished = new CountDownLatch(1);
        AtomicReference<InstanceLaunchCoordinator.LaunchOutcome> outcomeRef = new AtomicReference<>();
        Thread launchThread = new Thread(() -> {
            outcomeRef.set(coordinator.launch(alpine));
            coordinatorFinished.countDown();
        }, "unconfirmed-coordinator");
        launchThread.start();

        UmlInstanceLauncher.Session liveSession =
                (UmlInstanceLauncher.Session) awaitRegisteredSession(alpine.id(), 5_000);
        // Unblock waitFor() while process stays alive and ignores signals -> STOP_UNCONFIRMED.
        exitGate.countDown();
        assertTrue(coordinatorFinished.await(5, TimeUnit.SECONDS));
        assertSame(InstanceLaunchCoordinator.LaunchOutcome.Launched.class,
                outcomeRef.get().getClass());
        assertEquals(InstanceLifecycle.State.RUNNING, store.load(alpine.id()).state());
        assertSame("unconfirmed stop retains active owner",
                liveSession, ExecutionSessionRegistry.get(alpine.id()));
        assertTrue(registry.isReserved(alpine.rootVolume()));

        InstanceStrategyLauncher.LaunchParameters blockedParams = new InstanceStrategyLauncher.LaunchParameters(
                alpine.id(),
                alpine.bridgeToken(),
                "alpine-minirootfs.tar.gz",
                alpine.rootVolume().toString(),
                filesDir.toPath().resolve("runtime").resolve(alpine.id()).toString(),
                filesDir.getAbsolutePath(),
                alpine.guestPassword(),
                alpine.hardwareConfig());
        try {
            launcher.launch(blockedParams);
            fail("restart while reservation held by unconfirmed cleanup must fail");
        } catch (UmlRootReservationRegistry.RootVolumeBusyException expected) {
            assertEquals(alpine.id(), expected.occupyingInstanceId());
            assertEquals(StopResult.UNCONFIRMED, UmlInstanceLauncher.takeLastCleanupResult(alpine.id()));
        }

        // Kernel finally exits -> retry stop confirms and releases root + ephemeral rootfs.
        stubborn.alive.set(false);
        StopResult confirmed1 = liveSession.stopRuntime(50L);
        assertTrue(confirmed1 == StopResult.CONFIRMED || confirmed1 == StopResult.ALREADY_STOPPED);
        assertFalse(registry.isReserved(alpine.rootVolume()));
        assertFalse(Files.exists(liveSession.ephemeralRootDir()));
        ExecutionSessionRegistry.unregister(alpine.id());
        InstanceLaunchGuard.end(alpine.id());

        // 2. Post-spawn registration failure + stubborn stop UNCONFIRMED retains reservation until dead.
        Path sub2Root = tmp.newFolder("unconfirmed-retry").toPath();
        File filesDir2 = sub2Root.resolve("files").toFile();
        assertTrue(new File(filesDir2, "instances").mkdirs());
        InstanceLifecycle.FileStore store2 = new InstanceLifecycle.FileStore(filesDir2.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller2 = new InstanceLifecycle.Controller(store2, FIXED_CLOCK);
        InstanceLifecycle.Instance instance2 = qaEntry.ensureEphemeralAlpineInstance(
                store2, controller2, filesDir2.toPath().resolve("qa-uml-roots"));
        cleanupInstanceIds.add(instance2.id());

        UmlRootReservationRegistry registry2 = new UmlRootReservationRegistry();
        CountDownLatch exitGate2 = new CountDownLatch(1);
        FakeUmlProcessHandle stubborn2 = new FakeUmlProcessHandle(4302L, exitGate2, 137);
        stubborn2.ignoreSignals = true;
        UmlInstanceLauncher launcher2 = new UmlInstanceLauncher(
                nativeDir, filesDir2, (spec, cols, rows) -> stubborn2, registry2,
                () -> new ByteArrayInputStream(tarGz), System::nanoTime,
                40L, 40L, 200L);

        InstanceLaunchGuard.begin(instance2.id());
        InstanceLaunchSequence.beforeLaunch(controller2, instance2);
        DummyOwnedSession dummyLive = new DummyOwnedSession();
        ExecutionSessionRegistry.register(instance2.id(), dummyLive);

        InstanceStrategyLauncher.LaunchParameters params = new InstanceStrategyLauncher.LaunchParameters(
                instance2.id(),
                instance2.bridgeToken(),
                "alpine-minirootfs.tar.gz",
                instance2.rootVolume().toString(),
                filesDir2.toPath().resolve("runtime").resolve(instance2.id()).toString(),
                filesDir2.getAbsolutePath(),
                instance2.guestPassword(),
                instance2.hardwareConfig());
        try {
            launcher2.launch(params);
            fail("duplicate registration must fail launch");
        } catch (IOException expected) {
            assertEquals(StopResult.UNCONFIRMED, UmlInstanceLauncher.takeLastCleanupResult(instance2.id()));
        }
        assertTrue("unconfirmed registration failure retains root reservation",
                registry2.isReserved(instance2.rootVolume()));

        // Attempting restart while root is still reserved must fail closed with RootVolumeBusyException.
        try {
            launcher2.launch(params);
            fail("restart on reserved root must throw RootVolumeBusyException");
        } catch (UmlRootReservationRegistry.RootVolumeBusyException busy) {
            assertEquals(instance2.id(), busy.occupyingInstanceId());
            assertEquals(StopResult.UNCONFIRMED, UmlInstanceLauncher.takeLastCleanupResult(instance2.id()));
        }

        // Release stubborn2 and dummyLive for clean test teardown.
        dummyLive.allowStop = true;
        stubborn2.alive.set(false);
        exitGate2.countDown();
    }

    @Test(timeout = 10_000)
    public void staleGenerationAndLateCallbacksAreIgnoredAndDoNotPolluteCurrentGeneration()
            throws Exception {
        Path root = tmp.newFolder("stale-gen").toPath();
        File filesDir = root.resolve("files").toFile();
        File nativeDir = root.resolve("native").toFile();
        assertTrue(filesDir.mkdirs());
        assertTrue(nativeDir.mkdirs());
        Path runtime1 = root.resolve("runtime1");
        Path runtime2 = root.resolve("runtime2");
        Path rootVol = root.resolve("qa-uml.raw");
        Files.createFile(rootVol);
        String id = UUID.randomUUID().toString();
        cleanupInstanceIds.add(id);

        byte[] tarGz = buildMinimalAlpineTarGz();
        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        AtomicInteger spawnSeq = new AtomicInteger();
        CountDownLatch gate1 = new CountDownLatch(1);
        CountDownLatch gate2 = new CountDownLatch(1);
        FakeUmlProcessHandle handle1 = new FakeUmlProcessHandle(4401L, gate1, 0);
        FakeUmlProcessHandle handle2 = new FakeUmlProcessHandle(4402L, gate2, 0);
        UmlInstanceLauncher launcher = new UmlInstanceLauncher(
                nativeDir, filesDir,
                (spec, cols, rows) -> spawnSeq.getAndIncrement() == 0 ? handle1 : handle2,
                registry, () -> new ByteArrayInputStream(tarGz), System::nanoTime,
                100L, 100L, 100L);

        String gen1 = InstanceLaunchGuard.begin(id);
        InstanceStrategyLauncher.LaunchParameters p1 = new InstanceStrategyLauncher.LaunchParameters(
                id, TOKEN, "alpine-minirootfs.tar.gz", rootVol.toString(),
                runtime1.toString(), root.toString(),
                "orchestellaqa", VmHardwareConfig.defaultConfig());
        UmlInstanceLauncher.Session s1 = (UmlInstanceLauncher.Session) launcher.launch(p1);
        InstanceLaunchGuard.end(id, gen1);
        gate1.countDown();
        handle1.alive.set(false);
        assertEquals(StopResult.ALREADY_STOPPED, s1.stopRuntime(100L));
        ExecutionSessionRegistry.unregister(id);

        // Start generation 2 for the same instance.
        String gen2 = InstanceLaunchGuard.begin(id);
        assertNotNull(gen2);
        InstanceStrategyLauncher.LaunchParameters p2 = new InstanceStrategyLauncher.LaunchParameters(
                id, TOKEN, "alpine-minirootfs.tar.gz", rootVol.toString(),
                runtime2.toString(), root.toString(),
                "orchestellaqa", VmHardwareConfig.defaultConfig());
        UmlInstanceLauncher.Session s2 = (UmlInstanceLauncher.Session) launcher.launch(p2);

        // Late callbacks against s1 (gen1) must be rejected/ignored and must not touch s2.
        s1.launchStageObserver().onGateAuthenticated(ProductionStageRecorder.Stage.AGENT_AUTHENTICATED);
        s1.markShellReady();
        assertFalse("stale generation callback must not mark gen1 bridgeReady",
                s1.readinessTracker().snapshot().bridgeReady());
        assertFalse("stale generation callback must not mark gen1 shellReady",
                s1.readinessTracker().snapshot().shellReady());
        assertEquals(UmlReadinessTracker.RejectionReason.STALE_GENERATION,
                s1.readinessTracker().snapshot().rejectionReason());
        assertEquals(UmlReadinessTracker.Phase.PROCESS_STARTED,
                s2.readinessTracker().snapshot().phase());

        gate2.countDown();
        handle2.alive.set(false);
        s2.stopRuntime(100L);
    }

    @Test(timeout = 10_000)
    public void foregroundBackgroundReattachSingleSubscriberAltScreenPreservedAndEofWithoutExitKeepsOwner()
            throws Exception {
        Path root = tmp.newFolder("pty-reentry").toPath();
        File filesDir = root.resolve("files").toFile();
        File nativeDir = root.resolve("native").toFile();
        assertTrue(filesDir.mkdirs());
        assertTrue(nativeDir.mkdirs());
        Path runtimeDir = root.resolve("runtime");
        Path rootVol = root.resolve("qa-uml.raw");
        Files.createFile(rootVol);
        String id = UUID.randomUUID().toString();
        cleanupInstanceIds.add(id);

        byte[] tarGz = buildMinimalAlpineTarGz();
        UmlRootReservationRegistry registry = new UmlRootReservationRegistry();
        CountDownLatch exitGate = new CountDownLatch(1);
        FakeUmlProcessHandle handle = new FakeUmlProcessHandle(4501L, exitGate, 0);
        UmlInstanceLauncher launcher = new UmlInstanceLauncher(
                nativeDir, filesDir, (spec, cols, rows) -> handle, registry,
                () -> new ByteArrayInputStream(tarGz), System::nanoTime,
                200L, 200L, 200L);

        InstanceStrategyLauncher.LaunchParameters params = new InstanceStrategyLauncher.LaunchParameters(
                id, TOKEN, "alpine-minirootfs.tar.gz", rootVol.toString(),
                runtimeDir.toString(), root.toString(),
                "orchestellaqa", VmHardwareConfig.defaultConfig());
        UmlInstanceLauncher.Session session = (UmlInstanceLauncher.Session) launcher.launch(params);

        PersistentTerminalSessionPool.SessionHolder holder =
                PersistentTerminalSessionPool.getInstance().getHolder(id);
        assertNotNull("session holder must be registered by launcher", holder);
        List<String> firstUi = Collections.synchronizedList(new ArrayList<>());
        Consumer<String> listener1 = firstUi::add;
        holder.attach(listener1);
        assertEquals(1, holder.subscriberCount());

        // Enter & leave alt-screen once, then detach UI (Home/background).
        handle.guestPtyOut().write("\u001b[?1049hTOP_VIEW\n\u001b[?1049lMAIN_PROMPT\n"
                .getBytes(StandardCharsets.UTF_8));
        handle.guestPtyOut().flush();
        awaitCondition(() -> String.join("", firstUi).contains("MAIN_PROMPT"), 3_000,
                "initial terminal output not received");

        holder.detach(listener1);
        assertEquals(0, holder.subscriberCount());

        // While backgrounded, guest enters vim/top alternate screen again.
        handle.guestPtyOut().write("\u001b[?1049hVIM_ALT_SCREEN\n".getBytes(StandardCharsets.UTF_8));
        handle.guestPtyOut().flush();
        awaitCondition(
                () -> holder.getScreenModel().isAlternateScreen()
                        && holder.getScreenModel().snapshot().text().contains("VIM_ALT_SCREEN"),
                3_000, "background alternate screen not updated in canonical model");

        // Foreground re-entry attaches exactly once and preserves alternate screen text.
        List<String> secondUi = Collections.synchronizedList(new ArrayList<>());
        Consumer<String> listener2 = secondUi::add;
        holder.attach(listener2);
        assertEquals(1, holder.subscriberCount());
        assertTrue(holder.getScreenModel().isAlternateScreen());
        assertTrue(holder.getScreenModel().snapshot().text().contains("VIM_ALT_SCREEN"));

        // Close PTY stream (EOF) BEFORE kernel process dies -> supervisor records EOF but keeps owner.
        handle.guestPtyOut().close();
        awaitCondition(() -> session.supervisor().wasEofObserved(), 3_000,
                "EOF was not observed by supervisor");
        assertTrue("live UML process keeps runtime alive despite premature EOF",
                session.isRuntimeAlive());
        assertSame("premature EOF must keep active session owner registered",
                session, ExecutionSessionRegistry.get(id));
        assertTrue("root reservation must stay held while process lives",
                registry.isReserved(rootVol));

        // Now let guest kernel exit cleanly -> retry confirms stop and releases root reservation.
        holder.detach(listener2);
        exitGate.countDown();
        handle.alive.set(false);
        StopResult stopped = session.stopRuntime(200L);
        assertTrue(stopped == StopResult.CONFIRMED || stopped == StopResult.ALREADY_STOPPED);
        assertFalse(registry.isReserved(rootVol));
    }

    @Test
    public void nonQaBuildRefusesUserModeKernelAndPreservesQemuAndFaceLane() throws Exception {
        Path root = tmp.newFolder("non-qa").toPath();
        File filesDir = root.resolve("files").toFile();
        assertTrue(new File(filesDir, "instances").mkdirs());
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(filesDir.toPath().resolve("instances"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, FIXED_CLOCK);

        IsolatedQaExecutionEntry nonQa = new IsolatedQaExecutionEntry(() -> false);
        try {
            nonQa.ensureEphemeralAlpineInstance(store, controller, root.resolve("qa-roots"));
            fail("non-QA entry must refuse ensureEphemeralAlpineInstance");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("QA"));
        }

        // Even with a USER_MODE_KERNEL instance metadata entry on disk, isolatedQaEnabled=false refuses launch.
        Path umlVol = root.resolve("uml.raw");
        Files.createFile(umlVol);
        InstanceLifecycle.Instance umlInstance = new IsolatedQaExecutionEntry(() -> true)
                .ensureEphemeralAlpineInstance(store, controller, root.resolve("qa-roots"));
        cleanupInstanceIds.add(umlInstance.id());

        AtomicInteger umlCalls = new AtomicInteger();
        RecordingUmlFaceLane faceLane = new RecordingUmlFaceLane();
        RecordingUi ui = new RecordingUi();
        InstanceLaunchCoordinator nonQaCoordinator = coordinator(
                store, filesDir, false,
                params -> {
                    umlCalls.incrementAndGet();
                    throw new AssertionError("standalone UML launcher must not run when isolatedQaEnabled=false");
                },
                faceLane, ui);
        InstanceLaunchCoordinator.LaunchOutcome refused = nonQaCoordinator.launch(umlInstance);
        assertSame(InstanceLaunchCoordinator.LaunchOutcome.Failed.class, refused.getClass());
        assertEquals(0, umlCalls.get());

        // Ordinary default instance still routes to QEMU without touching UmlFaceLane or standalone UML.
        Path qemuVol = root.resolve("qemu.raw");
        Files.write(qemuVol, "ORDINARY_QEMU_VOL".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance ordinary = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), qemuVol);
        cleanupInstanceIds.add(ordinary.id());
        AtomicInteger qemuCalls = new AtomicInteger();
        CountDownLatch qemuRelease = new CountDownLatch(0);
        InstanceLaunchCoordinator ordinaryCoordinator = new InstanceLaunchCoordinator(
                store, new FakeSettings(), new RecordingForeground(), ui, new StubCopy(),
                new NoOpDiagnostics(), ignored -> null, filesDir, () -> "RAM", () -> false,
                params -> { throw new AssertionError("AVF not preferred"); },
                params -> {
                    qemuCalls.incrementAndGet();
                    ExecutionSession qemu = new DummyQemuSession(qemuRelease);
                    ExecutionSessionRegistry.register(params.instanceId, qemu);
                    return qemu;
                },
                instanceId -> { throw new UnsupportedOperationException(); },
                instanceId -> { throw new UnsupportedOperationException(); },
                unarmedFacts(), Optional::empty, e -> {},
                LaneClock.system(), FIXED_CLOCK, () -> {}, faceLane,
                () -> false, null, d -> {});
        InstanceLaunchCoordinator.LaunchOutcome qemuOutcome = ordinaryCoordinator.launch(ordinary);
        assertTrue(qemuOutcome instanceof InstanceLaunchCoordinator.LaunchOutcome.Launched);
        assertEquals(1, qemuCalls.get());
        assertEquals(0, faceLane.arms.get());
    }

    private static InstanceLaunchCoordinator coordinator(
            InstanceLifecycle.FileStore store,
            File filesDir,
            boolean qaEnabled,
            InstanceStrategyLauncher.UserModeKernelLaunch umlLaunch,
            RecordingUmlFaceLane faceLane,
            RecordingUi ui) {
        return new InstanceLaunchCoordinator(
                store,
                new FakeSettings(),
                new RecordingForeground(),
                ui,
                new StubCopy(),
                new NoOpDiagnostics(),
                UmlInstanceLauncher::takeLastCleanupResult,
                filesDir,
                () -> "64GB RAM",
                () -> false,
                params -> { throw new AssertionError("AVF must not run for USER_MODE_KERNEL"); },
                params -> { throw new AssertionError("QEMU must not run for USER_MODE_KERNEL"); },
                instanceId -> { throw new UnsupportedOperationException(); },
                instanceId -> { throw new UnsupportedOperationException(); },
                unarmedFacts(),
                Optional::empty,
                event -> {},
                LaneClock.system(),
                FIXED_CLOCK,
                () -> {},
                faceLane,
                () -> qaEnabled,
                umlLaunch,
                diagnostic -> {});
    }

    private static ExecutionSession awaitRegisteredSession(String instanceId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            ExecutionSession session = ExecutionSessionRegistry.get(instanceId);
            if (session != null) {
                return session;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("ExecutionSession was not registered within " + timeoutMs + "ms");
    }

    private static boolean isStopped(InstanceLifecycle.FileStore store, String instanceId) {
        try {
            return store.load(instanceId).state() == InstanceLifecycle.State.STOPPED;
        } catch (IOException failure) {
            return false;
        }
    }

    private static void awaitCondition(
            java.util.function.BooleanSupplier condition, long timeoutMs, String failureMessage)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail(failureMessage);
    }

    private static byte[] buildMinimalAlpineTarGz() throws IOException {
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(rawOut)) {
            writeTarDir(gzip, "bin/");
            writeTarFile(gzip, "bin/busybox", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8), 0755);
            writeTarDir(gzip, "usr/");
            writeTarDir(gzip, "usr/bin/");
            writeTarFile(gzip, "usr/bin/vi", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8), 0755);
            gzip.write(new byte[1024]);
        }
        return rawOut.toByteArray();
    }

    private static void writeTarDir(OutputStream out, String name) throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        writeOctal(header, 100, 8, 0755);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, 0);
        writeOctal(header, 136, 12, 1_700_000_000L);
        header[156] = '5';
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        header[263] = '0';
        header[264] = '0';
        finalizeChecksum(header);
        out.write(header);
    }

    private static void writeTarFile(OutputStream out, String name, byte[] data, int mode)
            throws IOException {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        writeOctal(header, 100, 8, mode);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, data.length);
        writeOctal(header, 136, 12, 1_700_000_000L);
        header[156] = '0';
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        header[263] = '0';
        header[264] = '0';
        finalizeChecksum(header);
        out.write(header);
        out.write(data);
        int rem = data.length % 512;
        if (rem != 0) {
            out.write(new byte[512 - rem]);
        }
    }

    private static void writeOctal(byte[] header, int offset, int length, long value) {
        String formatted = Long.toOctalString(value);
        int digits = length - 1;
        for (int i = 0; i < digits; i++) {
            int pos = offset + i;
            int srcIdx = formatted.length() - digits + i;
            header[pos] = (byte) (srcIdx < 0 ? '0' : formatted.charAt(srcIdx));
        }
        header[offset + digits] = 0;
    }

    private static void finalizeChecksum(byte[] header) {
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        long sum = 0;
        for (byte b : header) {
            sum += (b & 0xFF);
        }
        writeOctal(header, 148, 7, sum);
        header[155] = ' ';
    }

    private static final class FakeUmlProcessHandle implements UmlProcessHandle {
        private final long pid;
        private final CountDownLatch exitGate;
        private final int exitCode;
        final AtomicBoolean alive = new AtomicBoolean(true);
        final AtomicInteger resizeCols = new AtomicInteger();
        final AtomicInteger resizeRows = new AtomicInteger();
        volatile boolean ignoreSignals;

        private final PipedOutputStream hostPtyWrite;
        private final PipedInputStream guestPtyRead;
        private final PipedOutputStream guestPtyWrite;
        private final PipedInputStream hostPtyRead;

        private final PipedOutputStream hostCtrlWrite;
        private final PipedInputStream guestCtrlRead;
        private final PipedOutputStream guestCtrlWrite;
        private final PipedInputStream hostCtrlRead;

        FakeUmlProcessHandle(long pid, CountDownLatch exitGate, int exitCode) throws IOException {
            this.pid = pid;
            this.exitGate = exitGate;
            this.exitCode = exitCode;
            this.hostPtyWrite = new PipedOutputStream();
            this.guestPtyRead = new PipedInputStream(hostPtyWrite, 16 * 1024);
            this.guestPtyWrite = new PipedOutputStream();
            this.hostPtyRead = new PipedInputStream(guestPtyWrite, 16 * 1024);

            this.hostCtrlWrite = new PipedOutputStream();
            this.guestCtrlRead = new PipedInputStream(hostCtrlWrite, 16 * 1024);
            this.guestCtrlWrite = new PipedOutputStream();
            this.hostCtrlRead = new PipedInputStream(guestCtrlWrite, 16 * 1024);
        }

        InputStream guestPtyIn() { return guestPtyRead; }
        OutputStream guestPtyOut() { return guestPtyWrite; }
        InputStream guestControlIn() { return guestCtrlRead; }
        OutputStream guestControlOut() { return guestCtrlWrite; }

        @Override public long pid() { return pid; }
        @Override public boolean isAlive() { return alive.get(); }

        @Override
        public int waitFor() throws InterruptedException {
            exitGate.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exitGate.await(Math.max(0L, timeout), unit);
        }

        @Override
        public void destroy() {
            if (!ignoreSignals) {
                alive.set(false);
                exitGate.countDown();
            }
        }

        @Override
        public void destroyForcibly() {
            if (!ignoreSignals) {
                alive.set(false);
                exitGate.countDown();
            }
        }

        @Override public OutputStream terminalInput() { return hostPtyWrite; }
        @Override public InputStream terminalOutput() { return hostPtyRead; }
        @Override public OutputStream controlInput() { return hostCtrlWrite; }
        @Override public InputStream controlOutput() { return hostCtrlRead; }

        @Override
        public boolean resizeTerminal(int columns, int rows) {
            resizeCols.set(columns);
            resizeRows.set(rows);
            return true;
        }

        @Override
        public void closeChannels() {
            closeQuietly(hostPtyWrite);
            closeQuietly(hostPtyRead);
            closeQuietly(guestPtyWrite);
            closeQuietly(guestPtyRead);
            closeQuietly(hostCtrlWrite);
            closeQuietly(hostCtrlRead);
            closeQuietly(guestCtrlWrite);
            closeQuietly(guestCtrlRead);
        }

        private static void closeQuietly(java.io.Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class DummyOwnedSession implements ExecutionSession, RuntimeOwnedSession {
        volatile boolean allowStop;
        @Override public OutputStream terminalInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream terminalOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream controlInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public void terminate() {}
        @Override public int waitFor() { return 0; }
        @Override public void close() {}
        @Override public StopResult stopRuntime(long graceMs) {
            return allowStop ? StopResult.CONFIRMED : StopResult.UNCONFIRMED;
        }
        @Override public boolean isRuntimeAlive() { return !allowStop; }
    }

    private static final class DummyQemuSession implements ExecutionSession, RuntimeOwnedSession {
        private final CountDownLatch release;
        DummyQemuSession(CountDownLatch release) { this.release = release; }
        @Override public OutputStream terminalInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream terminalOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream controlInput() { return new ByteArrayOutputStream(); }
        @Override public InputStream controlOutput() { return new ByteArrayInputStream(new byte[0]); }
        @Override public void terminate() {}
        @Override public int waitFor() throws InterruptedException { release.await(); return 0; }
        @Override public void close() {}
        @Override public FullLinuxContract.CapabilityMatrix capabilityMatrix() {
            return FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline();
        }
        @Override public StopResult stopRuntime(long graceMs) { return StopResult.CONFIRMED; }
        @Override public boolean isRuntimeAlive() { return false; }
    }

    private static final class RecordingForeground
            implements InstanceLaunchCoordinator.ForegroundServiceHandle {
        @Override public void start(String id, String title, String badge, BatteryPolicy p, String g) {}
        @Override public void stop(String id, String gen) {}
    }

    private static final class RecordingUi implements InstanceLaunchCoordinator.LaunchUi {
        final List<String> failures = new ArrayList<>();
        final List<InstanceLaunchCoordinator.LaunchNavigation> successes = new ArrayList<>();
        @Override public void runOnUi(Runnable update) { update.run(); }
        @Override public void resetCreateButtons() {}
        @Override public void showStatus(String message) {}
        @Override public void showAttached(InstanceLifecycle.Instance instance) {}
        @Override public void showLaunchBusyStatus() {}
        @Override public synchronized void showLaunchSuccess(
                InstanceLaunchCoordinator.LaunchNavigation navigation) {
            successes.add(navigation);
        }
        @Override public synchronized void surfaceLaunchFailure(String message) {
            failures.add(message);
        }
        @Override public void showInstances() {}
    }

    private static final class FakeSettings implements InstanceLaunchCoordinator.LaunchSettings {
        @Override public void setLastActiveInstanceId(String instanceId) {}
        @Override public BatteryPolicy batteryPolicy() { return BatteryPolicy.BALANCED; }
        @Override public boolean isAcceleratorLaneEnabled() { return false; }
    }

    private static final class StubCopy implements InstanceLaunchCoordinator.LaunchCopy {
        @Override public String detailMessage(Throwable failure) { return failure.getMessage(); }
        @Override public String guestExitedStatus() { return "exited"; }
        @Override public String launchStartedHardwareStatus() { return "hardware"; }
        @Override public String launchDegradedToSoftwareStatus(String reason) { return reason; }
    }

    private static final class NoOpDiagnostics implements InstanceLaunchCoordinator.LaunchDiagnostics {
        @Override public void logAcceleratorSnapshot(String line) {}
        @Override public void logLaunchFailure(String failureType) {}
    }

    private static final class RecordingUmlFaceLane implements InstanceLaunchCoordinator.UmlFaceLane {
        final AtomicInteger arms = new AtomicInteger();
        @Override public UmlFaceLauncher arm(String instanceId, boolean gateEnabled) {
            arms.incrementAndGet();
            return null;
        }
        @Override public void disarm(String instanceId) {}
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
}
