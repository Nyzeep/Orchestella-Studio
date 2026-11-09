package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.FabricWidthDecision.Face;
import io.github.nyzeep.orchestella.UmlFaceLauncher.LaunchParams;
import io.github.nyzeep.orchestella.UmlFaceLauncher.StartCode;
import io.github.nyzeep.orchestella.UmlFaceLauncher.UmlProcess;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudgetLedger;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRequest;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * C lane item 4: the width thermostat's supply side wired into the fabric
 * ledger. The measured verdict the tests lock: the UML face's dispatchable
 * width is {@code ncpus=N} when the knob is 2 or more, 1 for the host-1
 * single-core shape — the UML face supplies its per-device capability
 * ({@link LaunchParams#forDeviceCapability}) and the <em>ledger</em> is the
 * thermostat that clamps the supply to the headroom. Locked here: the
 * device-capability derivation, the clamp through the assembly's face-over-
 * launcher slot (the three face widths still sum to the ledger's core budget),
 * the slot's refusal to take a UML worker from anywhere but the face, and the
 * live end-to-end path — dispatch admitted on the UML face executes through
 * the {@link UmlChannel} seam at exactly the granted width, while a face that
 * is not running supplies width 0 and is honestly unroutable.
 */
public final class UmlFaceWidthSupplyTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File libDir;
    private File filesDir;

    @Before
    public void createDirs() {
        libDir = new File(tmp.getRoot(), "native-lib");
        filesDir = new File(tmp.getRoot(), "files");
        assertTrue(libDir.mkdirs());
        assertTrue(filesDir.mkdirs());
    }

    // --- fixtures -----------------------------------------------------------------

    private static final FabricOrchestrator.WorkItem ITEM = new FabricOrchestrator.WorkItem() {
    };

    /** Kernel process stand-in. */
    private static final class FakeProcess implements UmlProcess {
        boolean alive = true;

        @Override public boolean isAlive() {
            return alive;
        }

        @Override public void destroy() {
            alive = false;
        }

        @Override public void destroyForcibly() {
            alive = false;
        }

        @Override public boolean waitForExit(long timeoutMs) {
            return !alive;
        }
    }

    private static final class FakeKeepAlive implements UmlFaceLauncher.KeepAlive {
        @Override public void acquire() {
        }

        @Override public void release() {
        }
    }

    /** The face plus its compile channel stub — the wiring owner's pair. */
    private static final class LiveFace {
        final UmlFaceLauncher face;
        final UmlChannels.Stub channel;

        LiveFace(UmlFaceLauncher face, UmlChannels.Stub channel) {
            this.face = face;
            this.channel = channel;
        }
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

    /**
     * The device-window shape: assets placed (empty files suffice — the gate
     * is the historical existence precheck), a live fake kernel, the compile
     * channel wired through the {@link UmlChannels} adapter.
     */
    private LiveFace liveFace(int ncpus) throws IOException {
        File kernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        assertTrue(kernel.createNewFile());
        kernel.setExecutable(true);
        File stub = new File(libDir, UmlFaceLauncher.STUB_LIB_NAME);
        assertTrue(stub.createNewFile());
        stub.setExecutable(true);
        assertTrue(new File(filesDir, UmlFaceLauncher.ROOTFS_DIR_NAME).mkdirs());

        UmlChannels.Stub channel = new UmlChannels.Stub();
        UmlFaceLauncher face = new UmlFaceLauncher(
                true,
                environment(),
                new LaunchParams(512, ncpus, List.of()),
                new FakeKeepAlive(),
                (command, env, workingDir) -> new FakeProcess(),
                UmlChannels.asFaceChannel(channel));
        return new LiveFace(face, channel);
    }

    /** Nothing placed: the honest MISSING_ASSET degradation path. */
    private UmlFaceLauncher faceWithoutAssets(int ncpus) {
        return new UmlFaceLauncher(
                true,
                environment(),
                new LaunchParams(512, ncpus, List.of()),
                new FakeKeepAlive(),
                (command, env, workingDir) -> new FakeProcess(),
                UmlChannels.asFaceChannel(new UmlChannels.Stub()));
    }

    /**
     * An 8-core host on a QEMU-only run (the accelerator lane not armed — the
     * shipped flag-off shape): the QEMU lane reserves 2 and the native face's
     * reservation is 0, so the UML face lives in the whole ledger headroom.
     */
    private AcceleratorLaneAssembly.Result assembledResult() {
        return assembledResult(new LaneBudget(8, 8192L, 64L, true),
                new LaneRequest(LaneId.QEMU, 2, 4096L, 32L),
                new LaneRequest(LaneId.ACCEL, 2, 2048L, 16L));
    }

    private static AcceleratorLaneAssembly.Result assembledResult(
            LaneBudget hostBudget, LaneRequest qemuRequest, LaneRequest accelRequest) {
        return AcceleratorLaneAssembly.assemble(
                new AcceleratorLaneAssembly.Config(false, "", 50L, 50L),
                new AcceleratorLaneAssembly.Inputs(
                        hostBudget,
                        qemuRequest,
                        accelRequest,
                        () -> deadLane(LaneId.QEMU),
                        () -> deadLane(LaneId.ACCEL),
                        new LaneClock() {
                            @Override public long nanoTime() {
                                return 0L;
                            }

                            @Override public void sleep(long millis) {
                            }
                        },
                        event -> {
                        },
                        () -> AcceleratorFacts.unknown()));
    }

    private static LaneRuntime deadLane(LaneId id) {
        return new LaneRuntime() {
            @Override public LaneId laneId() {
                return id;
            }

            @Override public boolean isAlive() {
                return false;
            }

            @Override public boolean isReady() {
                return false;
            }

            @Override public LaneStopOutcome stop(long graceMs) {
                return LaneStopOutcome.ALREADY_STOPPED;
            }
        };
    }

    private static void reserveQuietly(LaneBudgetLedger ledger, LaneRequest request) {
        try {
            ledger.reserve(request);
        } catch (io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectedException
                failure) {
            throw new AssertionError("fixture reservation refused", failure);
        }
    }

    /**
     * Reads the widths exactly the slot derives them: the same ledger-backed
     * {@code FaceWidthSource} composed from the same pieces (ledger, the C1
     * reservation values, the face's own width supplier), sampled directly.
     * The QEMU face passes its reserved C1 value; the native face passes its
     * own reservation — 0 in this QEMU-only fixture, where the accelerator
     * lane never armed.
     */
    private static Map<Face, Integer> widthsWith(
            AcceleratorLaneAssembly.Result result, int nativeFaceWidth, UmlFaceLauncher face) {
        return FabricOrchestrator.ledgerBackedWidths(
                result.coordinator().ledger(), 2, nativeFaceWidth, face.widthSupplier())
                .budgetWidths();
    }

    private static void assertSumIsTheLedgerBudget(
            AcceleratorLaneAssembly.Result result, Map<Face, Integer> widths) {
        assertEquals(result.coordinator().ledger().budget().cpuCores(),
                widths.get(Face.QEMU_TCG) + widths.get(Face.HOST_NATIVE)
                        + widths.get(Face.UML));
    }

    // --- the verdict as launch parameters ------------------------------------------

    @Test
    public void deviceCapabilityOfTwoOrMoreSuppliesTheFullCoreCountAsNcpus() {
        assertEquals(8, LaunchParams.forDeviceCapability(8).ncpus());
        assertEquals(2, LaunchParams.forDeviceCapability(2).ncpus());
        assertEquals(8, LaunchParams.forDeviceCapability(8).effectiveWidth());
    }

    @Test
    public void singleCoreDeviceStaysOnTheHost1Shape() {
        LaunchParams params = LaunchParams.forDeviceCapability(1);
        assertEquals(0, params.ncpus());
        assertEquals(1, params.effectiveWidth());
    }

    @Test
    public void deviceCapabilityRejectsNonPositiveCoreCounts() {
        try {
            LaunchParams.forDeviceCapability(0);
            fail("zero cores is malformed");
        } catch (IllegalArgumentException expected) {
            // Fixed derivation contract.
        }
        try {
            LaunchParams.forDeviceCapability(-3);
            fail("negative cores is malformed");
        } catch (IllegalArgumentException expected) {
            // Fixed derivation contract.
        }
    }

    @Test
    public void deviceCapabilityKeepsTheProvenGuestDefaults() {
        LaunchParams params = LaunchParams.forDeviceCapability(4);
        assertEquals(512, params.memMb());
        assertTrue(params.extraArgs().isEmpty());
    }

    @Test
    public void effectiveWidthVerdictTakesNForTwoOrMoreAndOneBelow() {
        assertEquals(1, new LaunchParams(512, 0, List.of()).effectiveWidth());
        assertEquals(1, new LaunchParams(512, 1, List.of()).effectiveWidth());
        assertEquals(2, new LaunchParams(512, 2, List.of()).effectiveWidth());
        assertEquals(6, new LaunchParams(512, 6, List.of()).effectiveWidth());
    }

    // --- the ledger is the thermostat ------------------------------------------------

    @Test
    public void faceOverLauncherSlotSuppliesTheLedgerDerivation() throws IOException {
        AcceleratorLaneAssembly.Result result = assembledResult();
        reserveQuietly(result.coordinator().ledger(),
                new LaneRequest(LaneId.QEMU, 2, 4096L, 32L));
        LiveFace live = liveFace(8);
        assertTrue(live.face.start().started());

        Map<Face, Integer> snapshot = widthsWith(result, 0, live.face);

        // The face supplies 8 (ncpus=8); the 6-core headroom clamps the UML
        // face to 6, the native face has no reservation (QEMU-only fixture),
        // and the three faces sum to the ledger's core budget.
        assertEquals(6, snapshot.get(Face.UML).intValue());
        assertEquals(0, snapshot.get(Face.HOST_NATIVE).intValue());
        assertEquals(2, snapshot.get(Face.QEMU_TCG).intValue());
        assertSumIsTheLedgerBudget(result, snapshot);
    }

    @Test
    public void wideSupplyIsClampedToTheLedgerHeadroomNotOversubscribed() throws IOException {
        // A 4-core envelope with the QEMU lane's 2 reserved: the eight-core
        // face supply clamps to the 2-core headroom and the three faces still
        // sum to the budget — the thermostat is the ledger.
        AcceleratorLaneAssembly.Result result = assembledResult(
                new LaneBudget(4, 4096L, 32L, true),
                new LaneRequest(LaneId.QEMU, 2, 2048L, 16L),
                new LaneRequest(LaneId.ACCEL, 2, 1024L, 8L));
        reserveQuietly(result.coordinator().ledger(),
                new LaneRequest(LaneId.QEMU, 2, 2048L, 16L));
        LiveFace live = liveFace(8);
        assertTrue(live.face.start().started());

        Map<Face, Integer> snapshot = widthsWith(result, 0, live.face);

        assertEquals(2, snapshot.get(Face.UML).intValue());
        assertSumIsTheLedgerBudget(result, snapshot);
    }

    @Test
    public void assemblySlotRefusesACallerSuppliedUmlWorker() throws IOException {
        AcceleratorLaneAssembly.Result result = assembledResult();
        LiveFace live = liveFace(4);

        try {
            AcceleratorLaneAssembly.fabricOrchestrator(
                    true, result, 2, 2, live.face,
                    Map.of(Face.UML, (ignoredFace, width, item) -> {
                        throw new FabricOrchestrator.FaceException("caller worker");
                    }));
            fail("the UML worker comes from the face, not the caller");
        } catch (IllegalArgumentException expected) {
            // Fixed slot contract.
        }
    }

    // --- the live end-to-end path -----------------------------------------------------

    @Test
    public void runningFaceRoutesWideWorkOntoUmlAndExecutesThroughTheChannelSeam()
            throws IOException, FabricOrchestrator.FaceException,
            UmlChannel.UmlChannelException {
        AcceleratorLaneAssembly.Result result = assembledResult();
        reserveQuietly(result.coordinator().ledger(),
                new LaneRequest(LaneId.QEMU, 2, 4096L, 32L));
        LiveFace live = liveFace(8);
        assertTrue(live.face.start().started());
        live.channel.open();

        FabricOrchestrator orchestrator = AcceleratorLaneAssembly.fabricOrchestrator(
                true, result, 2, 0, live.face, Map.of());

        FabricOrchestrator.Outcome outcome = orchestrator.dispatch(ITEM, 8);

        assertTrue(outcome.admitted());
        assertSame(Face.UML, outcome.route().face());
        // The ledger clamp is the thermostat: the face supplies 8, the 6-core
        // headroom grants 6, and the channel received exactly the granted width.
        assertEquals(6, outcome.route().width());
        assertEquals(List.of("6:item"), live.channel.executions());
    }

    @Test
    public void stoppedFaceSuppliesZeroWidthAndIsHonestlyUnroutable()
            throws IOException {
        AcceleratorLaneAssembly.Result result = assembledResult();
        LiveFace live = liveFace(8);

        FabricOrchestrator orchestrator = AcceleratorLaneAssembly.fabricOrchestrator(
                true, result, 2, 0, live.face, Map.of());

        assertEquals(0, live.face.width());
        FabricOrchestrator.Outcome outcome = orchestrator.dispatch(ITEM, 8);
        assertFalse(outcome.admitted());
        assertSame(FabricWidthDecision.RejectCode.NO_FACE_AVAILABLE,
                outcome.route().rejectCode());
        assertTrue(live.channel.executions().isEmpty());
    }

    @Test
    public void stoppedFaceKeepsItsWorkerButTheLedgerKeepsTheSumHonest()
            throws IOException {
        // The slot wires the face's worker even while the face is down — the
        // width (not the worker's presence) is what gates routability, so the
        // honest "the face is down" never turns into a fake outage code.
        AcceleratorLaneAssembly.Result result = assembledResult();
        reserveQuietly(result.coordinator().ledger(),
                new LaneRequest(LaneId.QEMU, 2, 4096L, 32L));
        LiveFace live = liveFace(4);
        FabricOrchestrator orchestrator = AcceleratorLaneAssembly.fabricOrchestrator(
                true, result, 2, 0, live.face, Map.of());
        assertEquals(0, live.face.width());

        Map<Face, Integer> snapshot = widthsWith(result, 0, live.face);
        assertEquals(0, snapshot.get(Face.UML).intValue());
        assertEquals(6, snapshot.get(Face.HOST_NATIVE).intValue());
        assertSumIsTheLedgerBudget(result, snapshot);
    }

    @Test
    public void startGateKeepsTheFixedMissingAssetAnswerForTheWidthSupply() {
        // Nothing placed: the width supply stays 0 because the start refuses —
        // the reconciliation layer's honest answer is the launcher's fixed code.
        UmlFaceLauncher face = faceWithoutAssets(4);

        StartCode startResult = face.start().code();

        assertSame(StartCode.MISSING_ASSET, startResult);
        assertEquals(UmlFaceLauncher.KERNEL_LIB_NAME, face.start().detail());
        assertEquals(0, face.width());
    }
}
