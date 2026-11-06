package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.UmlFaceLauncher.Health;
import io.github.nyzeep.orchestella.UmlFaceLauncher.KeepAlive;
import io.github.nyzeep.orchestella.UmlFaceLauncher.LaunchParams;
import io.github.nyzeep.orchestella.UmlFaceLauncher.StartCode;
import io.github.nyzeep.orchestella.UmlFaceLauncher.StartResult;
import io.github.nyzeep.orchestella.UmlFaceLauncher.UmlProcess;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * B2 follow-up ② (UML face product wiring, step one): the lifecycle and
 * width-supply semantics of {@link UmlFaceLauncher}, the face the fabric slot
 * injects into the B2 orchestration. These tests lock the honest-degradation
 * contract: the gate off is a zero-side-effect refusal, missing kernel assets
 * and spawn failures answer fixed codes with width 0 and never fake a running
 * state, an unwired compile channel keeps the face unroutable, the width is
 * the kernel's effective SMP width ({@code ncpus=} semantics from the device
 * round), and the keep-alive (foreground service + wake lock, the anti-freeze
 * hard prerequisite) is held exactly over the kernel's lifetime.
 */
public final class UmlFaceLauncherTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File libDir;
    private File filesDir;

    @Before
    public void createDirs() throws IOException {
        libDir = tmp.newFolder("native-lib");
        filesDir = tmp.newFolder("files");
    }

    // --- fakes ------------------------------------------------------------------

    /** Kernel process stand-in with explicit death control. */
    private static final class FakeProcess implements UmlProcess {
        boolean alive = true;
        boolean destroyed;
        boolean destroyedForcibly;
        boolean exitWithinGrace = true;
        int waitCalls;

        @Override public boolean isAlive() {
            return alive;
        }

        @Override public void destroy() {
            destroyed = true;
            if (exitWithinGrace) {
                alive = false;
            }
        }

        @Override public void destroyForcibly() {
            destroyedForcibly = true;
            alive = false;
        }

        @Override public boolean waitForExit(long timeoutMs) throws InterruptedException {
            waitCalls++;
            return !alive;
        }
    }

    private static final class SpawnRecord {
        List<String> command;
        Map<String, String> environment;
        File workingDir;
    }

    private static final class FakeSpawner implements UmlFaceLauncher.ProcessSpawner {
        final List<SpawnRecord> spawns = new ArrayList<>();
        final List<FakeProcess> processes = new ArrayList<>();
        private final IOException failure;

        FakeSpawner() {
            this.failure = null;
        }

        FakeSpawner(IOException failure) {
            this.failure = failure;
        }

        @Override public UmlProcess spawn(
                List<String> command, Map<String, String> environment, File workingDir)
                throws IOException {
            if (failure != null) {
                throw failure;
            }
            SpawnRecord record = new SpawnRecord();
            record.command = new ArrayList<>(command);
            record.environment = environment;
            record.workingDir = workingDir;
            spawns.add(record);
            FakeProcess process = new FakeProcess();
            processes.add(process);
            return process;
        }

        FakeProcess lastProcess() {
            return processes.get(processes.size() - 1);
        }

        SpawnRecord lastSpawn() {
            return spawns.get(spawns.size() - 1);
        }
    }

    private static final class FakeKeepAlive implements KeepAlive {
        int acquires;
        int releases;

        @Override public void acquire() {
            acquires++;
        }

        @Override public void release() {
            releases++;
        }
    }

    private static final class RecordingChannel implements UmlFaceLauncher.Channel {
        final List<String> executions = new ArrayList<>();

        @Override public void execute(int grantedWidth, FabricOrchestrator.WorkItem item) {
            executions.add(grantedWidth + ":" + (item == null ? "null" : "item"));
        }
    }

    // --- fixture helpers ----------------------------------------------------------

    /** Assets present, as the device window's prepare flow would place them. */
    private void placeAssets() throws IOException {
        assertTrue(new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME).createNewFile());
        assertTrue(new File(libDir, UmlFaceLauncher.STUB_LIB_NAME).createNewFile());
        assertTrue(new File(filesDir, UmlFaceLauncher.ROOTFS_DIR_NAME).mkdirs());
    }

    private UmlFaceLauncher.LaunchParams params(int ncpus, String... extraArgs) {
        return new UmlFaceLauncher.LaunchParams(512, ncpus, java.util.Arrays.asList(extraArgs));
    }

    private UmlFaceLauncher.LaunchParams paramsWithMem(int memMb, int ncpus) {
        return new UmlFaceLauncher.LaunchParams(memMb, ncpus, java.util.Collections.emptyList());
    }

    private UmlFaceLauncher.Environment environment() {
        return new UmlFaceLauncher.Environment() {
            @Override public File nativeLibraryDir() {
                return libDir;
            }

            @Override public File filesDir() {
                return filesDir;
            }
        };
    }

    private UmlFaceLauncher launcher(
            boolean enabled, UmlFaceLauncher.LaunchParams params,
            FakeSpawner spawner, FakeKeepAlive keepAlive, UmlFaceLauncher.Channel channel) {
        return new UmlFaceLauncher(enabled, environment(), params, keepAlive, spawner, channel);
    }

    private static final FabricOrchestrator.WorkItem ITEM = new FabricOrchestrator.WorkItem() {
    };

    // --- gate: off means zero side effects ----------------------------------------

    @Test
    public void gateOffStartIsAZeroSideEffectRefusal() {
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(false, params(4), spawner, keepAlive, null);

        StartResult result = face.start();

        assertFalse(result.started());
        assertEquals(StartCode.GATE_DISABLED, result.code());
        assertNull(result.detail());
        assertEquals(0, spawner.spawns.size());
        assertEquals(0, keepAlive.acquires);
        assertEquals(0, keepAlive.releases);
        assertEquals(0, face.width());
        assertEquals(Health.NOT_RUNNING, face.probe());
        assertEquals(UmlFaceLauncher.State.STOPPED, face.state());
    }

    // --- missing assets: the injectable degradation path ---------------------------

    @Test
    public void missingKernelDegradesToTheFixedCodeWithZeroWidth() {
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        // Nothing placed: the kernel binaries are not in the repository — the
        // device window's prepare flow lays them down.

        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, null);
        StartResult result = face.start();

        assertFalse(result.started());
        assertEquals(StartCode.MISSING_ASSET, result.code());
        assertEquals(UmlFaceLauncher.KERNEL_LIB_NAME, result.detail());
        assertEquals(0, spawner.spawns.size());
        assertEquals(0, keepAlive.acquires);
        assertEquals(0, face.width());
        assertEquals(UmlFaceLauncher.State.STOPPED, face.state());
    }

    @Test
    public void missingStubAndRootfsDegradeTheSameWay() throws IOException {
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        FakeSpawner spawner = new FakeSpawner();

        // Kernel present, stub missing.
        assertTrue(new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME).createNewFile());
        UmlFaceLauncher noStub = launcher(true, params(0), spawner, keepAlive, null);
        StartResult stubResult = noStub.start();
        assertEquals(StartCode.MISSING_ASSET, stubResult.code());
        assertEquals(UmlFaceLauncher.STUB_LIB_NAME, stubResult.detail());

        // Stub present too, rootfs missing.
        assertTrue(new File(libDir, UmlFaceLauncher.STUB_LIB_NAME).createNewFile());
        UmlFaceLauncher noRootfs = launcher(true, params(0), spawner, keepAlive, null);
        StartResult rootfsResult = noRootfs.start();
        assertEquals(StartCode.MISSING_ASSET, rootfsResult.code());
        assertEquals(UmlFaceLauncher.ROOTFS_DIR_NAME, rootfsResult.detail());

        assertEquals(0, spawner.spawns.size());
        assertEquals(0, keepAlive.acquires);
    }

    // --- the proven start shape ----------------------------------------------------

    @Test
    public void startBuildsThePrototypeCommandLineAndAcquiresTheKeepAlive()
            throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(
                true, params(4), spawner, keepAlive, new RecordingChannel());

        StartResult result = face.start();

        assertTrue(result.started());
        assertEquals(StartCode.STARTED, result.code());
        SpawnRecord spawn = spawner.lastSpawn();
        assertEquals(new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME).getAbsolutePath(),
                spawn.command.get(0));
        // The prototype's proven argument list, in order, with the SMP knob.
        List<String> expected = java.util.Arrays.asList(
                "mem=512M",
                "rw",
                "init=/um-init",
                "con=null",
                "con0=fd:0,fd:1",
                "stub_exe=" + new File(libDir, UmlFaceLauncher.STUB_LIB_NAME).getAbsolutePath(),
                "rootfstype=hostfs",
                "rootflags=" + new File(filesDir, UmlFaceLauncher.ROOTFS_DIR_NAME).getAbsolutePath(),
                "seccomp=on",
                "panic=1",
                "ncpus=4");
        assertEquals(expected, spawn.command.subList(1, spawn.command.size()));
        // Guest RAM backing files land in the app-writable tmp dir.
        assertEquals(filesDir.getAbsolutePath(), spawn.environment.get("TMPDIR"));
        assertEquals(filesDir.getAbsolutePath(), spawn.environment.get("HOME"));
        assertEquals("/system/bin:/system/xbin:/vendor/bin", spawn.environment.get("PATH"));
        assertEquals(filesDir, spawn.workingDir);
        // Keep-alive over the kernel's lifetime, released only on stop.
        assertEquals(1, keepAlive.acquires);
        assertEquals(0, keepAlive.releases);
        assertEquals(4, face.width());
        assertEquals(Health.HEALTHY, face.probe());
        assertEquals(UmlFaceLauncher.State.RUNNING, face.state());
    }

    @Test
    public void hostSingleCoreShapeSendsNoNcpusKnobAndSuppliesWidthOne()
            throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(
                true, params(0), spawner, keepAlive, new RecordingChannel());

        assertTrue(face.start().started());

        for (String arg : spawner.lastSpawn().command) {
            assertFalse("an UP kernel must not see the ncpus knob",
                    arg.startsWith("ncpus="));
        }
        // The omitted knob is the host-1 single-core default: width 1.
        assertEquals(1, face.width());
    }

    @Test
    public void extraArgsLandAfterTheDefaultsForDeviceWindowTuning() throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(
                true, params(2, "seccomp=off", "con0=null,fd:1"), spawner, keepAlive, null);

        assertTrue(face.start().started());

        List<String> command = spawner.lastSpawn().command;
        assertEquals("seccomp=off", command.get(command.size() - 2));
        assertEquals("con0=null,fd:1", command.get(command.size() - 1));
        assertTrue(command.contains("ncpus=2"));
    }

    @Test
    public void launchParamsRejectInvalidValues() {
        try {
            paramsWithMem(0, 1);
            fail("memMb must be positive");
        } catch (IllegalArgumentException expected) {
            // Fixed parameter contract.
        }
        try {
            paramsWithMem(-1, 1);
            fail("memMb must be positive");
        } catch (IllegalArgumentException expected) {
            // Fixed parameter contract.
        }
        try {
            new UmlFaceLauncher.LaunchParams(512, -1, java.util.Collections.emptyList());
            fail("ncpus must be non-negative");
        } catch (IllegalArgumentException expected) {
            // Fixed parameter contract.
        }
    }

    // --- spawn failure: honest degradation ------------------------------------------

    @Test
    public void spawnFailureDegradesToTheFixedCodeWithoutKeepAlive() throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner(new IOException("exec refused"));
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, null);

        StartResult result = face.start();

        assertFalse(result.started());
        assertEquals(StartCode.SPAWN_FAILED, result.code());
        assertEquals(0, keepAlive.acquires);
        assertEquals(0, face.width());
        assertEquals(Health.NOT_RUNNING, face.probe());
        assertEquals(UmlFaceLauncher.State.STOPPED, face.state());
    }

    // --- double start and restart ----------------------------------------------------

    @Test
    public void doubleStartIsRefusedWithTheFixedCodeAndDoesNotSpawnTwice()
            throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, null);

        assertTrue(face.start().started());
        StartResult again = face.start();

        assertFalse(again.started());
        assertEquals(StartCode.ALREADY_RUNNING, again.code());
        assertEquals(1, spawner.spawns.size());
        assertEquals(1, keepAlive.acquires);
    }

    @Test
    public void restartAfterStopSpawnsAFreshProcess() throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(
                true, params(4), spawner, keepAlive, new RecordingChannel());

        assertTrue(face.start().started());
        face.stop();
        StartResult restarted = face.start();

        // The rebuild path (orchestrator arm(UML) after a restart) gets a
        // fresh kernel and its width back.
        assertTrue(restarted.started());
        assertEquals(2, spawner.spawns.size());
        assertEquals(2, keepAlive.acquires);
        assertEquals(1, keepAlive.releases);
        assertEquals(4, face.width());
    }

    // --- stop: grace, escalation, release -------------------------------------------

    @Test
    public void stopTerminatesWithinGraceReleasesKeepAliveAndZeroesWidth()
            throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, null);
        assertTrue(face.start().started());

        face.stop();

        FakeProcess process = spawner.lastProcess();
        assertTrue(process.destroyed);
        assertFalse(process.destroyedForcibly);
        assertEquals(1, keepAlive.releases);
        assertEquals(0, face.width());
        assertEquals(Health.NOT_RUNNING, face.probe());
        assertEquals(UmlFaceLauncher.State.STOPPED, face.state());
    }

    @Test
    public void stopEscalatesToDestroyForciblyWhenTheGraceExpires() throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, null);
        assertTrue(face.start().started());
        spawner.lastProcess().exitWithinGrace = false;

        face.stop();

        FakeProcess process = spawner.lastProcess();
        assertTrue(process.destroyed);
        assertTrue(process.destroyedForcibly);
        assertEquals(1, keepAlive.releases);
        assertEquals(UmlFaceLauncher.State.STOPPED, face.state());
    }

    @Test
    public void stopIsIdempotentWhenAlreadyStopped() {
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, null);

        face.stop();
        face.stop();

        assertEquals(0, spawner.spawns.size());
        assertEquals(0, keepAlive.releases);
    }

    // --- width honesty: unwired channel and kernel death ------------------------------

    @Test
    public void unwiredChannelKeepsTheFaceAtZeroWidthAndUnroutable() throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, null);
        assertTrue(face.start().started());

        // Running with a healthy kernel, but no compile channel wired yet:
        // the orchestration must not route work into a face that cannot serve
        // it, and a stray dispatch hits the fixed FaceException contract.
        assertEquals(0, face.width());
        try {
            face.faceWorker().execute(FabricWidthDecision.Face.UML, 4, ITEM);
            fail("an unwired face must not execute work");
        } catch (FabricOrchestrator.FaceException expected) {
            assertEquals(UmlFaceLauncher.CHANNEL_UNWIRED_DETAIL, expected.getMessage());
        }
    }

    @Test
    public void wiredChannelExecutesAdmittedWorkThroughTheSeam() throws Exception {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        RecordingChannel channel = new RecordingChannel();
        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, channel);
        assertTrue(face.start().started());

        face.faceWorker().execute(FabricWidthDecision.Face.UML, 3, ITEM);

        assertEquals(1, channel.executions.size());
        assertEquals("3:item", channel.executions.get(0));
    }

    @Test
    public void faceWorkerRefusesWithTheFixedDetailWhenTheFaceIsNotRunning() {
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(
                true, params(4), spawner, keepAlive, new RecordingChannel());

        try {
            face.faceWorker().execute(FabricWidthDecision.Face.UML, 4, ITEM);
            fail("a stopped face must not execute work");
        } catch (FabricOrchestrator.FaceException expected) {
            assertEquals(UmlFaceLauncher.FACE_NOT_RUNNING_DETAIL, expected.getMessage());
        }
    }

    @Test
    public void aDeadKernelReadsZeroWidthAndTheProbeReapsIt() throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, null);
        assertTrue(face.start().started());
        spawner.lastProcess().alive = false;

        assertEquals(0, face.width());
        assertEquals(Health.PROCESS_DEAD, face.probe());
        // The lazy reap released the keep-alive (never held for a dead kernel)
        // and the next observation reads the clean stopped state.
        assertEquals(1, keepAlive.releases);
        assertEquals(Health.NOT_RUNNING, face.probe());
        assertEquals(UmlFaceLauncher.State.STOPPED, face.state());
    }

    // --- the fabric slot's supplier view ----------------------------------------------

    @Test
    public void widthSupplierTracksTheLifecycleForTheFabricSlot() throws IOException {
        placeAssets();
        FakeSpawner spawner = new FakeSpawner();
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher.Channel channel = new RecordingChannel();
        UmlFaceLauncher face = launcher(true, params(4), spawner, keepAlive, channel);
        java.util.function.IntSupplier width = face.widthSupplier();

        // Down: the fabric slot's umlFaceWidth reads 0 (the B2 contract).
        assertEquals(0, width.getAsInt());

        assertTrue(face.start().started());
        assertEquals(4, width.getAsInt());

        face.stop();
        assertEquals(0, width.getAsInt());
    }
}
