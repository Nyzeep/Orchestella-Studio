package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.engineavf.AvfEngineException;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.BatteryState;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.PvmCompilerManifest;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.ThermalState;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pure-JVM contract tests for the launch orchestration extracted from
 * MainActivity into InstanceLaunchCoordinator. Same discipline as
 * InstanceLaunchSequenceTest: real FileStore/Controller, hand-written fakes,
 * counters and latches, finally-block cleanup. Every behavioral clause of the
 * extracted orchestration is pinned here: the attach fast path, the guard
 * refusal, the mirror/firstboot provision assembly, the flag-off dispatch
 * fallback, the foreground service start×2/stop×2 call sites and arguments,
 * the watcher generation interleave (a stale or superseded watcher must not
 * terminate or repaint a newer launch) and the failure cleanup sequence
 * (ADR-0020 cleanup bridge consumed exactly once, afterLaunchFailure, failure
 * UI through ErrorCopy).
 */
public final class InstanceLaunchCoordinatorTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    // ------------------------------------------------------------------
    // Fakes (no mocking framework)
    // ------------------------------------------------------------------

    /** Records every foreground-service handle call in order, with test hooks. */
    private static final class RecordingForeground implements InstanceLaunchCoordinator.ForegroundServiceHandle {
        final List<String> events = new ArrayList<>();
        final CountDownLatch firstStop = new CountDownLatch(1);
        /** Runs while holding the record lock after a stop event is recorded. */
        volatile Runnable stopHook;
        /** Runs while holding the record lock after the second start event. */
        volatile Runnable secondStartHook;

        @Override public synchronized void start(String instanceId, String distroTitle,
                String performanceBadge, BatteryPolicy batteryPolicy, String launchGeneration) {
            events.add("start:" + instanceId + ":" + distroTitle + ":" + performanceBadge
                    + ":" + batteryPolicy + ":" + launchGeneration);
            if (events.size() == 2 && secondStartHook != null) secondStartHook.run();
        }

        @Override public synchronized void stop(String instanceId, String generation) {
            events.add("stop:" + instanceId + ":" + generation);
            firstStop.countDown();
            if (stopHook != null) stopHook.run();
        }

        synchronized List<String> snapshot() { return new ArrayList<>(events); }
    }

    /** Runs ui updates inline (deterministic) and records the presentation events. */
    private static final class RecordingUi implements InstanceLaunchCoordinator.LaunchUi {
        final List<String> events = new ArrayList<>();
        final AtomicInteger runOnUiCalls = new AtomicInteger();
        volatile InstanceLaunchCoordinator.LaunchNavigation successNavigation;

        /** Counts a paint only after its update completed, so awaits are deterministic. */
        @Override public void runOnUi(Runnable update) {
            update.run();
            runOnUiCalls.incrementAndGet();
        }

        @Override public synchronized void resetCreateButtons() { events.add("reset-buttons"); }

        @Override public synchronized void showStatus(String message) { events.add("status:" + message); }

        @Override public synchronized void showAttached(InstanceLifecycle.Instance instance) {
            events.add("attached:" + instance.id());
        }

        @Override public synchronized void showLaunchBusyStatus() { events.add("busy"); }

        @Override public synchronized void showLaunchSuccess(InstanceLaunchCoordinator.LaunchNavigation navigation) {
            successNavigation = navigation;
            events.add("success:" + navigation.getInstanceId() + ":" + navigation.getBridgeToken()
                    + ":" + navigation.getHostname() + ":" + navigation.getDefaultUser());
        }

        @Override public synchronized void surfaceLaunchFailure(String message) {
            events.add("failure:" + message);
            events.add("toast:" + message);
        }

        @Override public synchronized void showInstances() { events.add("instances"); }

        synchronized boolean hasEvent(String expected) { return events.contains(expected); }
    }

    private static final class FakeSettings implements InstanceLaunchCoordinator.LaunchSettings {
        String lastActiveInstanceId;
        int lastActiveWrites;
        final BatteryPolicy policy = BatteryPolicy.BALANCED;
        boolean acceleratorLaneEnabled;

        @Override public void setLastActiveInstanceId(String instanceId) {
            lastActiveInstanceId = instanceId;
            lastActiveWrites++;
        }

        @Override public BatteryPolicy batteryPolicy() { return policy; }

        @Override public boolean isAcceleratorLaneEnabled() { return acceleratorLaneEnabled; }
    }

    /** Deterministic copy source; markers make the shown text assertable. */
    private static final class StubCopy implements InstanceLaunchCoordinator.LaunchCopy {
        @Override public String detailMessage(Throwable failure) { return "copy:" + failure.getMessage(); }

        @Override public String guestExitedStatus() { return "copy:guest-exited"; }

        @Override public String launchStartedHardwareStatus() { return "copy:hardware"; }

        @Override public String launchDegradedToSoftwareStatus(String reason) { return "copy:degraded:" + reason; }
    }

    private static final class RecordingDiagnostics implements InstanceLaunchCoordinator.LaunchDiagnostics {
        final List<String> snapshots = new ArrayList<>();
        final List<String> failureLogs = new ArrayList<>();

        @Override public synchronized void logAcceleratorSnapshot(String line) { snapshots.add(line); }

        @Override public synchronized void logLaunchFailure(String failureType) { failureLogs.add(failureType); }
    }

    /** Queue-backed cleanup bridge; counts consumption to pin the once-only contract. */
    private static final class CleanupBridge implements InstanceLaunchCoordinator.CleanupBridge {
        private final Map<String, Deque<RuntimeProcessSupervisor.StopResult>> staged = new HashMap<>();
        final AtomicInteger consumptions = new AtomicInteger();
        /** Runs on the caller thread before the staged result is returned. */
        volatile Runnable overrideHook;

        void stage(String instanceId, RuntimeProcessSupervisor.StopResult result) {
            staged.computeIfAbsent(instanceId, key -> new ArrayDeque<>()).add(result);
        }

        @Override public RuntimeProcessSupervisor.StopResult takeLastCleanupResult(String instanceId) {
            consumptions.incrementAndGet();
            if (overrideHook != null) overrideHook.run();
            Deque<RuntimeProcessSupervisor.StopResult> deque = staged.get(instanceId);
            return deque == null ? null : deque.poll();
        }
    }

    private static class ExitSession implements ExecutionSession, RuntimeOwnedSession {
        private final int code;
        int closes;
        int terminations;
        /** Signals that the watcher reached the stop-after-exit phase. */
        final CountDownLatch stopRuntimeEntered = new CountDownLatch(1);
        ExitSession(int code) { this.code = code; }
        @Override public int waitFor() throws InterruptedException { return code; }
        @Override public java.io.InputStream terminalOutput() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream terminalInput() { return new java.io.ByteArrayOutputStream(); }
        @Override public java.io.InputStream controlOutput() { return new java.io.ByteArrayInputStream(new byte[0]); }
        @Override public java.io.OutputStream controlInput() { return new java.io.ByteArrayOutputStream(); }
        @Override public void close() { closes++; }
        @Override public void terminate() { terminations++; }
        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            stopRuntimeEntered.countDown();
            return RuntimeProcessSupervisor.StopResult.CONFIRMED;
        }
        @Override public boolean isRuntimeAlive() { return false; }
    }

    /** Session whose confirmed stop is refused: the runtime may still be alive. */
    private static class UnconfirmedExitSession extends ExitSession {
        UnconfirmedExitSession() { super(0); }
        @Override public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            return RuntimeProcessSupervisor.StopResult.UNCONFIRMED;
        }
        @Override public boolean isRuntimeAlive() { return true; }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    /** Records the ride-along UML face lane's arm/disarm calls (Lane A 2026-11-13). */
    private static final class RecordingUmlFaceLane implements InstanceLaunchCoordinator.UmlFaceLane {
        final AtomicInteger arms = new AtomicInteger();
        final AtomicInteger disarms = new AtomicInteger();
        final AtomicBoolean lastGate = new AtomicBoolean(false);
        /** When set, arm answers this face; null keeps the backend-declined shape. */
        volatile UmlFaceLauncher faceToArm;

        @Override public UmlFaceLauncher arm(String instanceId, boolean gateEnabled) {
            arms.incrementAndGet();
            lastGate.set(gateEnabled);
            return faceToArm;
        }

        @Override public void disarm(String instanceId) {
            disarms.incrementAndGet();
        }
    }

    private static final class Seams {
        final FakeSettings settings = new FakeSettings();
        final RecordingUi ui = new RecordingUi();
        final RecordingForeground foreground = new RecordingForeground();
        final StubCopy copy = new StubCopy();
        final RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        final CleanupBridge cleanup = new CleanupBridge();
        final RecordingUmlFaceLane umlFaceLane = new RecordingUmlFaceLane();
        final AtomicInteger reconciles = new AtomicInteger();
        final AtomicInteger factReads = new AtomicInteger();
        final AtomicReference<InstanceStrategyLauncher.LaunchParameters> captured = new AtomicReference<>();
        volatile boolean preferAvf;
        /**
         * The facts provider the flag-on dispatch reads; the default throws on
         * read (the flag-off tests pin that nothing reads it).
         */
        volatile AcceleratorFactProvider facts = () -> {
            throw new AssertionError("facts must not be read while the lane is off");
        };
        /** The payload manifest the flag-on snapshot reads; default refuses. */
        volatile java.util.Optional<PvmCompilerManifest> payloadManifest =
                java.util.Optional.empty();
        volatile boolean refusePayloadManifestRead = true;
    }

    private static final class Setup {
        final InstanceLifecycle.FileStore store;
        final InstanceLifecycle.Instance instance;

        Setup(InstanceLifecycle.FileStore store, InstanceLifecycle.Instance instance) {
            this.store = store;
            this.instance = instance;
        }
    }

    private Setup givenDebianInstance(String folder) throws Exception {
        Path dir = temporaryFolder.newFolder(folder).toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(dir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, Clock.systemUTC());
        return new Setup(store, controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"), dir.resolve("root-volume.raw")));
    }

    private File givenFilesDir() throws Exception {
        File filesDir = temporaryFolder.newFolder("files-" + System.nanoTime());
        assertTrue(new File(filesDir, "instances").mkdirs());
        return filesDir;
    }

    private InstanceLaunchCoordinator newCoordinator(Setup setup, File filesDir, Seams seams,
            InstanceStrategyLauncher.QemuLaunch qemuLaunch) {
        return newCoordinator(setup, filesDir, seams,
                params -> { throw new UnsupportedOperationException("avf not expected"); },
                qemuLaunch);
    }

    private InstanceLaunchCoordinator newCoordinator(Setup setup, File filesDir, Seams seams,
            InstanceStrategyLauncher.AvfLaunch avfLaunch, InstanceStrategyLauncher.QemuLaunch qemuLaunch) {
        return new InstanceLaunchCoordinator(
                setup.store,
                seams.settings,
                seams.foreground,
                seams.ui,
                seams.copy,
                seams.diagnostics,
                seams.cleanup,
                filesDir,
                () -> "64GB RAM",
                () -> seams.preferAvf,
                avfLaunch,
                qemuLaunch,
                instanceId -> { throw new UnsupportedOperationException("custom vm not expected"); },
                instanceId -> { throw new UnsupportedOperationException("pvm not expected"); },
                () -> {
                    // The original wiring constructs the provider eagerly per launch
                    // even when the lane is off; only .read() is lane-gated.
                    seams.factReads.incrementAndGet();
                    return seams.facts;
                },
                () -> {
                    if (seams.refusePayloadManifestRead) {
                        throw new UnsupportedOperationException("payload manifest not expected");
                    }
                    return seams.payloadManifest;
                },
                event -> { },
                LaneClock.system(),
                Clock.systemUTC(),
                seams.reconciles::incrementAndGet,
                seams.umlFaceLane);
    }

    /** A qemu seam that registers the session like the real launcher does. */
    private static InstanceStrategyLauncher.QemuLaunch registeringQemu(Seams seams, ExitSession session) {
        return params -> {
            seams.captured.set(params);
            ExecutionSessionRegistry.register(params.instanceId, session);
            return session;
        };
    }

    /** Blocks the watcher inside waitFor until the test releases it. */
    private static ExitSession gatedExitSession(CountDownLatch release) {
        return new ExitSession(0) {
            @Override public int waitFor() throws InterruptedException {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
                return 0;
            }
        };
    }

    /** Waits until the watcher thread reached its pending ui paint attempt. */
    private static void awaitRunOnUiCalls(RecordingUi ui, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ui.runOnUiCalls.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals("the expected paint attempts must have been reached", expected, ui.runOnUiCalls.get());
    }

    // ------------------------------------------------------------------
    // Attach fast path
    // ------------------------------------------------------------------

    @Test
    public void attachPathPointsUiAtTheRunningSessionWithoutStoreOrForegroundWrites() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("attach-store");
        ExitSession live = new ExitSession(0);
        ExecutionSessionRegistry.register(setup.instance.id(), live);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                params -> { throw new AssertionError("no launch may be attempted on the attach path"); });
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Attached.class, outcome.getClass());
            assertEquals(InstanceLifecycle.State.CREATED, setup.store.load(setup.instance.id()).state());
            assertEquals("the attach path returns before orphan reconciliation",
                    0, seams.reconciles.get());
            assertEquals(0, seams.cleanup.consumptions.get());
            assertTrue(seams.foreground.snapshot().isEmpty());
            assertEquals(List.of("reset-buttons", "attached:" + setup.instance.id()), seams.ui.events);
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
        }
    }

    // ------------------------------------------------------------------
    // Guard refusal
    // ------------------------------------------------------------------

    @Test
    public void guardRefusalReturnsBusyKeepsTheAdmittedOwnerAndReconcilesFirst() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("busy-store");
        String ownerGeneration = InstanceLaunchGuard.begin(setup.instance.id());
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                params -> { throw new AssertionError("no launch may be attempted while refused"); });
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Busy.class, outcome.getClass());
            assertEquals("the admitted owner keeps its generation",
                    ownerGeneration, InstanceLaunchGuard.generation(setup.instance.id()));
            assertTrue("the owner stays admitted", InstanceLaunchGuard.isInFlight(setup.instance.id()));
            assertEquals("orphan reconciliation still runs before the admission check",
                    1, seams.reconciles.get());
            assertEquals(InstanceLifecycle.State.CREATED, setup.store.load(setup.instance.id()).state());
            assertEquals(0, seams.cleanup.consumptions.get());
            assertTrue(seams.foreground.snapshot().isEmpty());
            assertEquals(List.of("busy", "reset-buttons"), seams.ui.events);
        } finally {
            InstanceLaunchGuard.end(setup.instance.id(), ownerGeneration);
        }
    }

    // ------------------------------------------------------------------
    // Happy path (flag off): dispatch falls back to today's launch
    // ------------------------------------------------------------------

    @Test
    public void flagOffLaunchFallsBackToTodayPathAndRunsTheFullSuccessSequence() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("happy-store");
        CountDownLatch release = new CountDownLatch(1);
        ExitSession session = gatedExitSession(release);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Launched.class, outcome.getClass());
            InstanceLaunchCoordinator.LaunchOutcome.Launched launched =
                    (InstanceLaunchCoordinator.LaunchOutcome.Launched) outcome;
            assertFalse(launched.getHardwareAccelerated());
            assertNull(launched.getDegradationReason());

            // The dispatch fallback ran today's launch through the qemu seam with the
            // assembled parameters. The provider is still constructed eagerly per
            // launch (original wiring), but its facts are never read while off.
            assertEquals("the provider is constructed eagerly exactly once",
                    1, seams.factReads.get());
            InstanceStrategyLauncher.LaunchParameters params = seams.captured.get();
            assertNotNull(params);
            assertEquals(setup.instance.id(), params.instanceId);
            assertEquals(setup.instance.bridgeToken(), params.bridgeToken);
            assertEquals("debian-13-rootfs.tar.xz", params.rootfsFileName);
            assertEquals(setup.instance.rootVolume().toString(), params.instanceRootVolume);
            assertEquals(new File(new File(filesDir, "runtime"), setup.instance.id()).getAbsolutePath(),
                    params.runtimeDirectory);
            assertEquals(new File(filesDir, "distro-inputs").getAbsolutePath(), params.distroInputsDirectory);
            assertEquals(setup.instance.guestPassword(), params.guestPassword);
            assertSame(setup.instance.hardwareConfig(), params.hardwareConfig);
            assertEquals(setup.instance.hostname(), params.hostname);
            assertEquals(setup.instance.defaultUser(), params.guestUser);
            assertTrue(params.hasSudo);

            // Metadata + settings + foreground service start #1 (launch generation).
            assertEquals(InstanceLifecycle.State.RUNNING, setup.store.load(setup.instance.id()).state());
            assertEquals(setup.instance.id(), seams.settings.lastActiveInstanceId);
            assertEquals(1, seams.settings.lastActiveWrites);
            generation = InstanceLaunchGuard.generation(setup.instance.id());
            assertNotNull(generation);
            assertEquals("the registry adopts the launch generation for its owner",
                    generation, ExecutionSessionRegistry.generation(setup.instance.id(), session));
            assertEquals("foreground start #1 carries the launch generation; start #2 the software badge",
                    List.of(
                            "start:" + setup.instance.id() + ":Debian 13:64GB RAM:BALANCED:" + generation,
                            "start:" + setup.instance.id() + ":Debian 13:QEMU 软件虚拟化:BALANCED:" + generation),
                    seams.foreground.snapshot());

            // The success UI carries the exact terminal navigation payload and no status
            // line (neither hardware nor degraded wording applies).
            assertEquals(List.of("success:" + setup.instance.id() + ":" + setup.instance.bridgeToken()
                    + ":" + setup.instance.hostname() + ":" + setup.instance.defaultUser()), seams.ui.events);
            assertEquals(setup.instance.bridgeToken(), launched.getNavigation().getBridgeToken());
            assertEquals(setup.instance.id(), launched.getNavigation().getInstanceId());
            assertEquals(setup.instance.hostname(), launched.getNavigation().getHostname());
            assertEquals(setup.instance.defaultUser(), launched.getNavigation().getDefaultUser());
            assertEquals(1, seams.reconciles.get());

            // Watcher: confirmed normal exit stops the foreground binding with the
            // session generation and repaints the dashboard.
            release.countDown();
            assertTrue(seams.foreground.firstStop.await(5, TimeUnit.SECONDS));
            awaitRunOnUiCalls(seams.ui, 2);
            assertTrue(seams.foreground.snapshot().contains(
                    "stop:" + setup.instance.id() + ":" + generation));
            assertEquals(InstanceLifecycle.State.STOPPED, setup.store.load(setup.instance.id()).state());
            assertTrue(seams.ui.hasEvent("status:copy:guest-exited"));
            assertTrue(seams.ui.hasEvent("reset-buttons"));
            assertTrue(seams.ui.hasEvent("instances"));
        } finally {
            release.countDown();
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    // ------------------------------------------------------------------
    // Provision payload assembly (mirror/firstboot file IO)
    // ------------------------------------------------------------------

    @Test
    public void provisionPayloadAssemblesMirrorAndFirstbootFromTheInstanceFiles() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("provision-store");
        byte[] mirror = "MIRROR_CFG_BYTES".getBytes(StandardCharsets.UTF_8);
        byte[] firstboot = "FIRSTBOOT_SCRIPT".getBytes(StandardCharsets.UTF_8);
        Files.write(new File(filesDir, "instances/" + setup.instance.id() + ".mirror").toPath(), mirror);
        Files.write(new File(filesDir, "instances/" + setup.instance.id() + ".firstboot").toPath(), firstboot);
        ExitSession session = new ExitSession(0);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            coordinator.launch(setup.instance);
            InstanceStrategyLauncher.LaunchParameters params = seams.captured.get();
            assertNotNull(params);
            assertTrue("mirror bytes flow into the payload",
                    Arrays.equals(mirror, params.mirrorConfigFileContent));
            assertTrue("firstboot bytes flow from the existing file",
                    Arrays.equals(firstboot, params.firstBootScript));
            assertNotNull(params.mirrorConfigFileName);
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    @Test
    public void missingFirstbootIsGeneratedFromThePipelineAndAbsentMirrorYieldsNull() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("genfirstboot-store");
        ExitSession session = new ExitSession(0);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            coordinator.launch(setup.instance);
            InstanceStrategyLauncher.LaunchParameters params = seams.captured.get();
            assertNotNull(params);
            assertNull("no mirror file means no mirror config content", params.mirrorConfigFileContent);
            byte[] expected = FirstBootProvisionPipeline
                    .generateScriptForDistro("debian").getBytes(StandardCharsets.UTF_8);
            assertTrue("firstboot is generated on the fly",
                    Arrays.equals(expected, params.firstBootScript));
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    // ------------------------------------------------------------------
    // Watcher outcomes
    // ------------------------------------------------------------------

    @Test
    public void watcherCrashedExitReportsTheExitCodeAndRecordsTheCrash() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("crash-store");
        CountDownLatch release = new CountDownLatch(1);
        ExitSession session = new ExitSession(23) {
            @Override public int waitFor() throws InterruptedException {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
                return 23;
            }
        };
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            coordinator.launch(setup.instance);
            release.countDown();
            assertTrue(seams.foreground.firstStop.await(5, TimeUnit.SECONDS));
            awaitRunOnUiCalls(seams.ui, 2);
            assertTrue("the confirmed crash stops the foreground binding",
                    seams.foreground.snapshot().stream()
                            .anyMatch(event -> event.startsWith("stop:" + setup.instance.id() + ":")));
            assertTrue("exit status paint mismatch: " + seams.ui.events,
                    seams.ui.hasEvent("status:实例异常退出（退出码 23），可查看诊断并冷恢复"));
            assertTrue(seams.ui.hasEvent("reset-buttons"));
            assertTrue(seams.ui.hasEvent("instances"));
            InstanceLifecycle.Instance crashed = setup.store.load(setup.instance.id());
            assertEquals(InstanceLifecycle.State.CRASHED, crashed.state());
            assertEquals(23, crashed.crashDiagnostic().exitCode());
            assertFalse("the completed crash retires the launch guard",
                    InstanceLaunchGuard.isInFlight(setup.instance.id()));
        } finally {
            release.countDown();
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    @Test
    public void watcherStopUnconfirmedKeepsMetadataOwnerAndRunsNoForegroundStop() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("unconfirmed-store");
        CountDownLatch release = new CountDownLatch(1);
        UnconfirmedExitSession session = new UnconfirmedExitSession() {
            @Override public int waitFor() throws InterruptedException {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch timeout");
                return 0;
            }
        };
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            coordinator.launch(setup.instance);
            release.countDown();
            awaitRunOnUiCalls(seams.ui, 2);
            assertTrue(seams.ui.hasEvent("status:停止未确认：运行时未在时限内停止，实例仍保留，请检查运行日志"));
            assertEquals("the unconfirmable owner stays registered", session,
                    ExecutionSessionRegistry.get(setup.instance.id()));
            assertEquals("RUNNING metadata is preserved",
                    InstanceLifecycle.State.RUNNING, setup.store.load(setup.instance.id()).state());
            assertTrue(InstanceLaunchGuard.isInFlight(setup.instance.id()));
            assertTrue("no foreground stop may run without a confirmed exit",
                    seams.foreground.snapshot().stream().noneMatch(event -> event.startsWith("stop:")));
        } finally {
            release.countDown();
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    @Test
    public void waitFailedWatcherKeepsTheOwnerAndShowsTheUnconfirmedExitCopy() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("waitfail-store");
        ExitSession session = new ExitSession(0) {
            @Override public int waitFor() { throw new IllegalStateException("synthetic wait failed"); }
        };
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            coordinator.launch(setup.instance);
            awaitRunOnUiCalls(seams.ui, 2);
            assertTrue("the wait-failure copy must surface",
                    seams.ui.hasEvent("status:无法确认虚拟机退出，已保留运行会话，请检查运行状态"));
            assertEquals("the owner stays registered", session,
                    ExecutionSessionRegistry.get(setup.instance.id()));
            assertEquals(InstanceLifecycle.State.RUNNING, setup.store.load(setup.instance.id()).state());
            assertTrue("no foreground stop without a confirmed exit",
                    seams.foreground.snapshot().stream().noneMatch(event -> event.startsWith("stop:")));
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    @Test
    public void staleWatcherRunsNeitherForegroundStopNorStatusUpdate() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("stale-store");
        CountDownLatch release = new CountDownLatch(1);
        ExitSession session = gatedExitSession(release);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            coordinator.launch(setup.instance);
            // Replace the registered owner before the watcher observes the exit: the
            // completion can no longer be confirmed for the old session, so the
            // watcher goes stale and must stay silent on every channel.
            generation = InstanceLaunchGuard.generation(setup.instance.id());
            ExitSession replacement = new ExitSession(0);
            ExecutionSessionRegistry.register(setup.instance.id(), replacement);
            release.countDown();
            assertTrue("the watcher must reach the stop-after-exit phase",
                    session.stopRuntimeEntered.await(5, TimeUnit.SECONDS));
            // A stale watcher paints nothing and stops nothing; the confirmed-stop
            // probe plus a short settle bound the thread's remaining work.
            Thread.sleep(200);
            assertTrue("no status line for a stale watcher",
                    seams.ui.events.stream().noneMatch(event -> event.startsWith("status:")));
            assertTrue("no foreground stop for a stale watcher",
                    seams.foreground.snapshot().stream().noneMatch(event -> event.startsWith("stop:")));
            assertSame("the replacement owner stays registered", replacement,
                    ExecutionSessionRegistry.get(setup.instance.id()));
            assertEquals("the replacement session is untouched", 0, replacement.closes);
            assertEquals("metadata stays RUNNING",
                    InstanceLifecycle.State.RUNNING, setup.store.load(setup.instance.id()).state());
        } finally {
            release.countDown();
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    @Test
    public void supersededWatcherStopsItsOwnBindingButCannotRepaintANewerLaunch() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("supersede-store");
        CountDownLatch release = new CountDownLatch(1);
        ExitSession session = gatedExitSession(release);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            coordinator.launch(setup.instance);
            generation = InstanceLaunchGuard.generation(setup.instance.id());
            // A newer launch admission supersedes the old watcher's generation: the
            // completion can no longer be confirmed, so the watcher goes stale and
            // must not stop its binding nor repaint the newer launch.
            InstanceLaunchGuard.end(setup.instance.id(), generation);
            String newerGeneration = InstanceLaunchGuard.begin(setup.instance.id());
            release.countDown();
            assertTrue("the watcher must reach the stop-after-exit phase",
                    session.stopRuntimeEntered.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            assertTrue("no foreground stop for a superseded watcher",
                    seams.foreground.snapshot().stream().noneMatch(event -> event.startsWith("stop:")));
            assertFalse("the old generation is no longer latest",
                    InstanceLaunchGuard.isLatest(setup.instance.id(), generation));
            assertTrue("the newer launch stays latest",
                    InstanceLaunchGuard.isLatest(setup.instance.id(), newerGeneration));
            assertTrue("no exit status may repaint the newer launch",
                    seams.ui.events.stream().noneMatch(event -> event.startsWith("status:")));
        } finally {
            release.countDown();
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    @Test
    public void lateSupersessionBetweenConfirmationAndPaintBlocksTheWatcherStatus() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("latesupersede-store");
        ExitSession session = new ExitSession(0);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            coordinator.launch(setup.instance);
            generation = InstanceLaunchGuard.generation(setup.instance.id());
            final String admitted = generation;
            // Hook the confirmed-exit stop: a newer launch admission is taken exactly
            // between the confirmed completion and the pending status paint.
            seams.foreground.stopHook = () -> {
                InstanceLaunchGuard.end(setup.instance.id(), admitted);
                InstanceLaunchGuard.begin(setup.instance.id());
            };
            assertTrue(seams.foreground.firstStop.await(5, TimeUnit.SECONDS));
            awaitRunOnUiCalls(seams.ui, 2);
            assertTrue("the binding stop still ran",
                    seams.foreground.snapshot().stream()
                            .anyMatch(event -> event.startsWith("stop:" + setup.instance.id() + ":")));
            assertTrue("the stale status paint must be blocked by the generation guard",
                    seams.ui.events.stream().noneMatch(event -> event.startsWith("status:")));
            assertTrue("the newer admission survives the old watcher",
                    InstanceLaunchGuard.isInFlight(setup.instance.id()));
        } finally {
            seams.foreground.stopHook = null;
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id());
        }
    }

    @Test
    public void successUiRequiresTheRegisteredOwnerIdentityBeforeNavigating() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("owner-swap-store");
        ExitSession session = new ExitSession(0);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        String generation = null;
        try {
            // Hook foreground start #2: by then a replacement session owns the
            // registry slot, so the double gate must suppress the success paint.
            seams.foreground.secondStartHook = () -> {
                try {
                    ExecutionSessionRegistry.unregister(setup.instance.id());
                    ExecutionSessionRegistry.register(setup.instance.id(), new ExitSession(0));
                } catch (java.io.IOException failure) {
                    throw new AssertionError(failure);
                }
            };
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Launched.class, outcome.getClass());
            assertTrue("the success paint must be blocked by the registry identity gate",
                    seams.ui.events.stream().noneMatch(event -> event.startsWith("success:")));
            assertTrue(seams.ui.events.stream().noneMatch(event -> event.startsWith("status:")));
            generation = InstanceLaunchGuard.generation(setup.instance.id());
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    // ------------------------------------------------------------------
    // Failure path: cleanup bridge + afterLaunchFailure + failure UI
    // ------------------------------------------------------------------

    @Test
    public void launchFailureConsumesTheCleanupBridgeOnceAndRunsTheFailureSequence() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("fail-store");
        seams.cleanup.stage(setup.instance.id(), RuntimeProcessSupervisor.StopResult.CONFIRMED);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                params -> { throw new java.io.IOException("boom"); });
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Failed.class, outcome.getClass());
            InstanceLaunchCoordinator.LaunchOutcome.Failed failed =
                    (InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome;
            assertTrue(failed.getFailure() instanceof java.io.IOException);

            assertEquals("the cleanup result is consumed exactly once", 1, seams.cleanup.consumptions.get());
            assertEquals(InstanceLifecycle.State.CRASHED, setup.store.load(setup.instance.id()).state());
            assertEquals(-1, setup.store.load(setup.instance.id()).crashDiagnostic().exitCode());
            assertFalse(InstanceLaunchGuard.isInFlight(setup.instance.id()));
            assertNull(ExecutionSessionRegistry.get(setup.instance.id()));
            assertEquals("start #1 plus the failed launch's own stop", 2,
                    seams.foreground.snapshot().size());
            assertTrue("the failed launch stops its own foreground binding",
                    seams.foreground.snapshot().get(1).startsWith("stop:" + setup.instance.id() + ":"));
            assertEquals(List.of("failure:copy:boom", "toast:copy:boom", "reset-buttons", "instances"),
                    seams.ui.events);
            assertEquals(List.of("IOException"), seams.diagnostics.failureLogs);
            assertEquals("the orphan reconciler ran before the admission", 1, seams.reconciles.get());
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id());
        }
    }

    @Test
    public void unconfirmedLaunchCleanupKeepsRunningMetadataAndTheForegroundBinding() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("unconfirmed-fail-store");
        seams.cleanup.stage(setup.instance.id(), RuntimeProcessSupervisor.StopResult.UNCONFIRMED);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                params -> { throw new java.io.IOException("boom"); });
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Failed.class, outcome.getClass());
            assertEquals("the cleanup result is consumed exactly once", 1, seams.cleanup.consumptions.get());
            assertEquals("an unconfirmed runtime keeps its RUNNING metadata",
                    InstanceLifecycle.State.RUNNING, setup.store.load(setup.instance.id()).state());
            assertNull(setup.store.load(setup.instance.id()).crashDiagnostic());
            assertTrue(InstanceLaunchGuard.isInFlight(setup.instance.id()));
            assertTrue("the foreground binding stays owned",
                    seams.foreground.snapshot().stream().noneMatch(event -> event.startsWith("stop:")));
            assertTrue("the failure UI still surfaces to the user",
                    seams.ui.hasEvent("failure:copy:boom"));
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id());
        }
    }

    @Test
    public void sessionGenerationChangeAfterDispatchFailsTheLaunchAndRecordsTheCrash() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("genchange-store");
        seams.cleanup.stage(setup.instance.id(), RuntimeProcessSupervisor.StopResult.CONFIRMED);
        ExitSession session = new ExitSession(0);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                params -> {
                    seams.captured.set(params);
                    return session; // never registered: the registry lost the race
                });
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Failed.class, outcome.getClass());
            InstanceLaunchCoordinator.LaunchOutcome.Failed failed =
                    (InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome;
            assertTrue(failed.getFailure() instanceof java.io.IOException);
            assertEquals("运行会话已变化", failed.getFailure().getMessage());
            assertEquals(1, seams.cleanup.consumptions.get());
            assertEquals(InstanceLifecycle.State.CRASHED, setup.store.load(setup.instance.id()).state());
            assertTrue("the failed binding is stopped",
                    seams.foreground.snapshot().stream()
                            .anyMatch(event -> event.startsWith("stop:" + setup.instance.id() + ":")));
            assertTrue(seams.ui.hasEvent("failure:copy:运行会话已变化"));
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id());
        }
    }

    @Test
    public void staleAdmissionCannotSurfaceTheFailureUiOfItsPredecessor() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("stale-fail-store");
        seams.cleanup.stage(setup.instance.id(), RuntimeProcessSupervisor.StopResult.CONFIRMED);
        // The bridge supersedes the admission before afterLaunchFailure and the
        // failure paint run: neither may touch the newer launch.
        seams.cleanup.overrideHook = () -> {
            String generation = InstanceLaunchGuard.generation(setup.instance.id());
            if (generation != null) {
                InstanceLaunchGuard.end(setup.instance.id(), generation);
                InstanceLaunchGuard.begin(setup.instance.id());
            }
        };
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                params -> { throw new java.io.IOException("boom"); });
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Failed.class, outcome.getClass());
            assertEquals("the newer admission keeps RUNNING metadata (afterLaunchFailure refused)",
                    InstanceLifecycle.State.RUNNING, setup.store.load(setup.instance.id()).state());
            assertTrue("no foreground stop without a current admission",
                    seams.foreground.snapshot().stream().noneMatch(event -> event.startsWith("stop:")));
            assertTrue("the failure paint is blocked by the generation guard",
                    seams.ui.events.stream().noneMatch(event -> event.startsWith("failure:")));
            assertTrue("the newer admission stays in flight",
                    InstanceLaunchGuard.isInFlight(setup.instance.id()));
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id());
        }
    }

    // ------------------------------------------------------------------
    // AVF degradation fallback
    // ------------------------------------------------------------------

    @Test
    public void avfFailureDegradesToQemuRecordsTheLedgerAndStartsWithTheSoftwareBadge() throws Exception {
        Seams seams = new Seams();
        seams.preferAvf = true;
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("degrade-store");
        ExitSession session = new ExitSession(0);
        InstanceStrategyLauncher.AvfLaunch failingAvf = params -> {
            throw new AvfEngineException("AVF_REASON_X", "avf unavailable");
        };
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                failingAvf, registeringQemu(seams, session));
        String generation = null;
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Launched.class, outcome.getClass());
            InstanceLaunchCoordinator.LaunchOutcome.Launched launched =
                    (InstanceLaunchCoordinator.LaunchOutcome.Launched) outcome;
            assertFalse(launched.getHardwareAccelerated());
            assertEquals("AVF_REASON_X", launched.getDegradationReason());
            assertTrue("the degradation ledger records the fallback",
                    new File(filesDir, "avf-degradations.txt").exists());
            // Foreground start #2 carries the software badge and the session generation.
            String sessionGeneration = ExecutionSessionRegistry.generation(setup.instance.id(), session);
            assertNotNull(sessionGeneration);
            assertEquals(2, seams.foreground.snapshot().size());
            assertEquals("start:" + setup.instance.id() + ":Debian 13:QEMU 软件虚拟化:BALANCED:" + sessionGeneration,
                    seams.foreground.snapshot().get(1));
            // Degraded wording precedes the terminal navigation.
            assertEquals(List.of("status:copy:degraded:AVF_REASON_X",
                    "success:" + setup.instance.id() + ":" + setup.instance.bridgeToken()
                            + ":" + setup.instance.hostname() + ":" + setup.instance.defaultUser()),
                    seams.ui.events);
            generation = sessionGeneration;
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id(), generation);
        }
    }

    // ------------------------------------------------------------------
    // Accelerator lane gate (only meaningful when the build flag is on)
    // ------------------------------------------------------------------

    @Test
    public void acceleratorLaneFlagGatesTheDispatchAssembly() throws Exception {
        Assume.assumeTrue(BuildConfig.ACCELERATOR_LANE_ENABLED);
        Seams seams = new Seams();
        seams.settings.acceleratorLaneEnabled = true;
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("lane-store");
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                params -> { throw new AssertionError("today's launch must not run on the lane path"); });
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Failed.class, outcome.getClass());
            InstanceLaunchCoordinator.LaunchOutcome.Failed failed =
                    (InstanceLaunchCoordinator.LaunchOutcome.Failed) outcome;
            assertTrue("the flag-on dispatch must consult the facts provider",
                    failed.getFailure() instanceof AssertionError);
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id());
        }
    }

    // ------------------------------------------------------------------
    // Ride-along UML face (Lane A 2026-11-13)
    // ------------------------------------------------------------------

    /** A fake always-alive kernel process; the fake spawner hands it back. */
    private static final class AliveUmlProcess implements UmlFaceLauncher.UmlProcess {
        volatile boolean alive = true;

        @Override public boolean isAlive() { return alive; }

        @Override public void destroy() { alive = false; }

        @Override public void destroyForcibly() { alive = false; }

        @Override public boolean waitForExit(long timeoutMs) { return true; }
    }

    /** Counts keep-alive acquire/release like the assembly test's fake. */
    private static final class CountingKeepAlive implements UmlFaceLauncher.KeepAlive {
        final AtomicInteger acquires = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        @Override public void acquire() { acquires.incrementAndGet(); }

        @Override public void release() { releases.incrementAndGet(); }
    }

    /**
     * A real fast-shape face over stand-in assets: kernel+stub executable
     * files, the §4 INITRAMFS shape (needs no external root), the always-alive
     * fake spawner — start() honestly answers STARTED and width() reads 1.
     */
    private UmlFaceLauncher givenArmedFastFace(File libDir) throws Exception {
        File kernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        File stub = new File(libDir, UmlFaceLauncher.STUB_LIB_NAME);
        assertTrue(kernel.createNewFile());
        assertTrue(stub.createNewFile());
        // POSIX CI honors the executable bit; Windows canExecute() is always
        // true for existing files (the assembly test's comment, same rule).
        kernel.setExecutable(true);
        stub.setExecutable(true);
        AliveUmlProcess process = new AliveUmlProcess();
        return new UmlFaceLauncher(
                true,
                new UmlFaceLauncher.Environment() {
                    @Override public File nativeLibraryDir() { return libDir; }

                    @Override public File filesDir() { return libDir; }
                },
                UmlFaceLauncher.LaunchParams.defaults(),
                new CountingKeepAlive(),
                (command, environment, workingDir) -> process,
                null,
                UmlFaceLauncher.FastProfile.initramfs());
    }

    /** Unarmed facts: the verdict is QEMU_ONLY and the dispatch still succeeds. */
    private static AcceleratorFactProvider unarmedFacts() {
        return () -> AcceleratorFacts.builder()
                .avfPresent(false)
                .nonProtectedSupported(false)
                .acceleratorImagePresent(false)
                .vsockSupported(false)
                .compilerTriple("")
                .thermalState(ThermalState.NOMINAL)
                .batteryState(BatteryState.OK)
                .build();
    }

    @Test
    public void gateOffLaunchNeverTouchesTheRideAlongUmlFace() throws Exception {
        Seams seams = new Seams();
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("uml-face-gateoff-store");
        CountDownLatch release = new CountDownLatch(1);
        ExitSession session = gatedExitSession(release);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Launched.class, outcome.getClass());
            // A gate-off dispatch returns a null coordinator: zero face, zero
            // new behavior — the face lane is never consulted.
            assertEquals("a gate-off dispatch arms no face at all",
                    0, seams.umlFaceLane.arms.get());
            assertFalse(seams.umlFaceLane.lastGate.get());
            assertNull("no face is bound to the status registry",
                    UmlFaceStatusRegistry.current());
            release.countDown();
            assertTrue(seams.foreground.firstStop.await(5, TimeUnit.SECONDS));
            awaitRunOnUiCalls(seams.ui, 2);
            // The watcher close point's disarm rides the same gate-off path
            // inertly: the unbound registry answers null, so it is a no-op.
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id());
        }
    }

    @Test
    public void flagOnLaunchArmsTheRideAlongFaceAndLogsTheLedgerIdentity() throws Exception {
        Assume.assumeTrue(BuildConfig.ACCELERATOR_LANE_ENABLED);
        Seams seams = new Seams();
        seams.settings.acceleratorLaneEnabled = true;
        seams.facts = unarmedFacts();
        seams.refusePayloadManifestRead = false;
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("uml-face-store");
        File libDir = temporaryFolder.newFolder("uml-face-lib");
        seams.umlFaceLane.faceToArm = givenArmedFastFace(libDir);
        CountDownLatch release = new CountDownLatch(1);
        ExitSession session = gatedExitSession(release);
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                registeringQemu(seams, session));
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Launched.class, outcome.getClass());
            assertEquals("the face lane armed exactly once", 1, seams.umlFaceLane.arms.get());
            assertTrue("the face gate is the double gate the dispatch consumed",
                    seams.umlFaceLane.lastGate.get());

            // The three-face ledger identity is logged once and the face
            // widths sum to the ledger's core budget.
            String identityLine = null;
            for (String line : seams.diagnostics.snapshots) {
                if (line.contains("fabric orchestrator constructed")) {
                    identityLine = line;
                }
            }
            assertNotNull("the fabric snapshot must be logged", identityLine);
            assertTrue("the snapshot carries all three faces", identityLine.contains("uml="));
            Matcher matcher = Pattern.compile("sum=(\\d+) ledger-budget=(\\d+)")
                    .matcher(identityLine);
            assertTrue("the snapshot carries the sum and the ledger budget", matcher.find());
            assertEquals("the three face widths sum to the ledger budget",
                    matcher.group(2), matcher.group(1));

            // The watcher's exit close point disarms the face.
            release.countDown();
            assertTrue(seams.foreground.firstStop.await(5, TimeUnit.SECONDS));
            awaitRunOnUiCalls(seams.ui, 2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (seams.umlFaceLane.disarms.get() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals("the watcher close point disarms the face",
                    1, seams.umlFaceLane.disarms.get());
        } finally {
            ExecutionSessionRegistry.unregister(setup.instance.id());
            InstanceLaunchGuard.end(setup.instance.id());
        }
    }

    @Test
    public void launchFailureAfterArmDisarmsTheRideAlongFace() throws Exception {
        Assume.assumeTrue(BuildConfig.ACCELERATOR_LANE_ENABLED);
        Seams seams = new Seams();
        seams.settings.acceleratorLaneEnabled = true;
        seams.facts = unarmedFacts();
        seams.refusePayloadManifestRead = false;
        File filesDir = givenFilesDir();
        Setup setup = givenDebianInstance("uml-face-fail-store");
        File libDir = temporaryFolder.newFolder("uml-face-fail-lib");
        seams.umlFaceLane.faceToArm = givenArmedFastFace(libDir);
        // The QEMU seam returns the session WITHOUT registering it: the
        // generation lookup after the face window fails, so the launch takes
        // its failure cleanup path — which must disarm the face too.
        InstanceStrategyLauncher.QemuLaunch unregisteredQemu = params -> {
            seams.captured.set(params);
            return new ExitSession(0);
        };
        InstanceLaunchCoordinator coordinator = newCoordinator(setup, filesDir, seams,
                unregisteredQemu);
        try {
            InstanceLaunchCoordinator.LaunchOutcome outcome = coordinator.launch(setup.instance);
            assertSame(InstanceLaunchCoordinator.LaunchOutcome.Failed.class, outcome.getClass());
            assertEquals("the face armed before the generation failure",
                    1, seams.umlFaceLane.arms.get());
            assertTrue(seams.umlFaceLane.lastGate.get());
            assertEquals("the launch failure path disarms the face",
                    1, seams.umlFaceLane.disarms.get());
            assertNull("the status registry is left unbound",
                    UmlFaceStatusRegistry.current());
        } finally {
            InstanceLaunchGuard.end(setup.instance.id());
        }
    }
}
