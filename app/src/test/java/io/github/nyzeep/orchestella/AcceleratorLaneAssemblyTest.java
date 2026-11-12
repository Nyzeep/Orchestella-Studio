package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneException;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneHandle;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneLaunchSpec;
import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneVirtualMachineApi;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFactProvider;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorFacts;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.AcceleratorReasonCode;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.BatteryState;
import io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator.ThermalState;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEvent;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRejectCode;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRequest;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneRuntime;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneState;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneStopOutcome;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** AX-08: the assembly is the only gate that may arm the accelerator lane. */
public final class AcceleratorLaneAssemblyTest {
    private static final String TRIPLE = "aarch64-linux-gnu";
    private static final LaneBudget HOST_BUDGET = new LaneBudget(8, 8192L, 128L, true);
    private static final LaneRequest QEMU_REQUEST = new LaneRequest(LaneId.QEMU, 4, 4096L, 32L);
    private static final LaneRequest ACCEL_REQUEST = new LaneRequest(LaneId.ACCEL, 2, 2048L, 16L);

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // --- feature flag off: today's behaviour, exactly ---------------------------

    @Test
    public void flagOffNeverReadsPlatformFacts() {
        Fixture fixture = new Fixture();
        fixture.facts = () -> {
            throw new AssertionError("facts must not be read while the lane is off");
        };

        AcceleratorLaneAssembly.Result result = fixture.assemble(false);

        fixture.assertHealth();
        assertFalse(result.decision().isPresent());
        assertFalse(result.acceleratorArmed());
        assertEquals("", result.decisionJson());
        assertEquals(0, fixture.factReads.get());
    }

    @Test
    public void flagOffStartsOnlyQemuAndSpendsNoAcceleratorBudget() {
        Fixture fixture = new Fixture();
        fixture.assemble(false);

        fixture.coordinator().startAll();

        fixture.assertHealth();
        assertEquals(1, fixture.qemuStarts.get());
        assertEquals(0, fixture.accelStarts.get());
        assertEquals(LaneState.DISABLED, fixture.coordinator().state(LaneId.ACCEL));
        assertFalse(fixture.coordinator().hasLease(LaneId.ACCEL));
        assertEquals(4, fixture.coordinator().ledger().snapshot().cpuCores());
        assertEquals(4096L, fixture.coordinator().ledger().snapshot().ramMiB());
        assertEquals(32L, fixture.coordinator().ledger().snapshot().diskGiB());
    }

    @Test
    public void flagOffRejectsAnyAcceleratorAdmission() {
        Fixture fixture = new Fixture();
        AcceleratorLaneAssembly.Result result = fixture.assemble(false);

        assertFalse(result.coordinator().admission().admit(1024L));
    }

    // --- feature flag on: the policy still decides ------------------------------

    @Test
    public void noAvfYieldsQemuOnly() {
        Fixture fixture = new Fixture();
        fixture.facts = () -> facts(false, false, false, true, true,
                ThermalState.NOMINAL, BatteryState.OK);

        AcceleratorLaneAssembly.Result result = fixture.assemble(true);
        fixture.coordinator().startAll();

        fixture.assertHealth();
        assertEquals(0, fixture.accelStarts.get());
        // PVM-01: with no backend candidate at all the verdict is the honest
        // terminal state; the CUSTOM_VM branch's NO_NON_PROTECTED refusal is
        // kept in the decision's rejection list.
        assertEquals(AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND,
                result.decision().orElseThrow().reasonCode());
        assertFalse(result.acceleratorArmed());
        assertTrue(result.decisionJson().contains("\"mode\":\"QEMU_ONLY\""));
    }

    @Test
    public void protectedOnlyDeviceYieldsQemuOnly() {
        Fixture fixture = new Fixture();
        fixture.facts = () -> facts(true, false, true, true, true,
                ThermalState.NOMINAL, BatteryState.OK);

        AcceleratorLaneAssembly.Result result = fixture.assemble(true);
        fixture.coordinator().startAll();

        fixture.assertHealth();
        assertEquals(0, fixture.accelStarts.get());
        // PVM-01: these facts claim no usable protected VM either, so no
        // backend is a candidate.
        assertEquals(AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND,
                result.decision().orElseThrow().reasonCode());
    }

    @Test
    public void fullyQualifiedDeviceStartsBothLanes() {
        Fixture fixture = new Fixture();
        fixture.facts = () -> facts(true, true, false, true, true,
                ThermalState.NOMINAL, BatteryState.CHARGING);

        AcceleratorLaneAssembly.Result result = fixture.assemble(true);
        fixture.coordinator().startAll();

        fixture.assertHealth();
        assertTrue(result.acceleratorArmed());
        assertEquals(1, fixture.qemuStarts.get());
        assertEquals(1, fixture.accelStarts.get());
        assertEquals(LaneState.STARTING, fixture.coordinator().state(LaneId.QEMU));
        assertEquals(LaneState.STARTING, fixture.coordinator().state(LaneId.ACCEL));
        assertTrue(fixture.coordinator().hasLease(LaneId.ACCEL));
        assertEquals(6, fixture.coordinator().ledger().snapshot().cpuCores());
    }

    // --- B2 wiring: the fabric orchestration consumes the C1 route contract -----

    @Test
    public void fabricOrchestratorConsumesTheC1RouteContractOverTheCoordinatorLedger() {
        FakeSession qemuSession = new FakeSession();
        FakeLane accelLane = new FakeLane();
        List<LaneEvent> events = new ArrayList<>();
        // The C1 product flow: an 8-core host's QEMU request is the clamped
        // concurrency budget of 2, the accel lane reserves 1, and the envelope
        // is exactly their sum.
        LaneRequest qemuRequest = new LaneRequest(
                LaneId.QEMU, FabricWidthDecision.qemuLaneBudgetCores(8), 4096L, 32L);
        LaneRequest accelRequest = new LaneRequest(LaneId.ACCEL, 1, 2048L, 16L);
        LaneBudget hostBudget = new LaneBudget(
                qemuRequest.cpuCores() + accelRequest.cpuCores(),
                qemuRequest.ramMiB() + accelRequest.ramMiB(),
                qemuRequest.diskGiB() + accelRequest.diskGiB(),
                true);

        AcceleratorLaneAssembly.Result result = AcceleratorLaneAssembly.assemble(
                new AcceleratorLaneAssembly.Config(true, TRIPLE, 50L, 50L),
                new AcceleratorLaneAssembly.Inputs(
                        hostBudget,
                        qemuRequest,
                        accelRequest,
                        () -> AcceleratorLaneAssembly.qemuLane(qemuSession, QemuReadinessProbe.absent()),
                        () -> accelLane,
                        new FakeClock(),
                        events::add,
                        () -> facts(true, true, false, true, true,
                                ThermalState.NOMINAL, BatteryState.CHARGING)));
        assertTrue(result.acceleratorArmed());
        result.coordinator().startAll();
        assertEquals(
                hostBudget.cpuCores(), result.coordinator().ledger().snapshot().cpuCores());

        List<String> nativeExecutions = new ArrayList<>();
        java.util.Map<FabricWidthDecision.Face, FabricOrchestrator.FaceWorker> workers =
                new java.util.EnumMap<>(FabricWidthDecision.Face.class);
        workers.put(FabricWidthDecision.Face.HOST_NATIVE,
                (face, width, item) -> nativeExecutions.add(face + ":" + width));

        FabricOrchestrator armed = AcceleratorLaneAssembly.fabricOrchestrator(
                true, result, qemuRequest.cpuCores(), accelRequest.cpuCores(),
                () -> 0, workers);

        // Wide-parallel work (4-way) routes off the QEMU face onto the native
        // face at its ledger-derived width (the accel lane's 1 core; the
        // shipped envelope leaves no headroom beyond the two reservations).
        FabricOrchestrator.Outcome wide = armed.dispatch(new FabricOrchestrator.WorkItem() { }, 4);
        assertTrue(wide.admitted());
        assertEquals(FabricWidthDecision.Face.HOST_NATIVE, wide.route().face());
        assertEquals(1, wide.route().width());
        assertEquals("HOST_NATIVE:1", nativeExecutions.get(0));

        // The gate-off wiring answers the fixed code with zero side effects.
        FabricOrchestrator gated = AcceleratorLaneAssembly.fabricOrchestrator(
                false, result, qemuRequest.cpuCores(), accelRequest.cpuCores(),
                () -> 0, workers);
        FabricOrchestrator.Outcome gateOff =
                gated.dispatch(new FabricOrchestrator.WorkItem() { }, 4);
        assertFalse(gateOff.admitted());
        assertNull(gateOff.route());
        assertEquals(FabricOrchestrator.Code.GATE_DISABLED, gateOff.code());
        assertEquals(1, nativeExecutions.size());
    }

    // --- B2 follow-up ②: the UML face's product wiring ----------------------------

    @Test
    public void umlFaceWiringFeedsTheFabricOrchestratorSlotWithWidthAndWorker()
            throws Exception {
        File libDir = tmp.newFolder("uml-native-lib");
        File filesDir = tmp.newFolder("uml-files");
        File kernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        File stub = new File(libDir, UmlFaceLauncher.STUB_LIB_NAME);
        assertTrue(kernel.createNewFile());
        assertTrue(stub.createNewFile());
        // The launcher's asset pre-check requires the executable bit; POSIX
        // filesystems honor it (CI Linux), while Windows canExecute() is
        // always true for existing files, so this is a no-op there.
        kernel.setExecutable(true);
        stub.setExecutable(true);
        assertTrue(new File(filesDir, UmlFaceLauncher.ROOTFS_DIR_NAME).mkdirs());

        // The C2-shaped product flow: an 8-core host's QEMU request is the
        // clamped 2, the accel lane reserves 1, and the ledger's remaining 5
        // cores are the headroom the UML face's kernel width lives in.
        LaneRequest qemuRequest = new LaneRequest(
                LaneId.QEMU, FabricWidthDecision.qemuLaneBudgetCores(8), 4096L, 32L);
        LaneRequest accelRequest = new LaneRequest(LaneId.ACCEL, 1, 2048L, 16L);
        LaneBudget hostBudget = new LaneBudget(8, 8192L, 128L, true);
        Fixture fixture = new Fixture();
        fixture.hostBudget = hostBudget;
        fixture.qemuRequest = qemuRequest;
        fixture.accelRequest = accelRequest;
        AcceleratorLaneAssembly.Result result = fixture.assemble(true);
        result.coordinator().startAll();

        FakeKeepAlive keepAlive = new FakeKeepAlive();
        AtomicInteger spawns = new AtomicInteger();
        UmlFaceLauncher.ProcessSpawner spawner = (command, environment, workingDir) -> {
            spawns.incrementAndGet();
            return new UmlFaceLauncher.UmlProcess() {
                @Override public boolean isAlive() {
                    return true;
                }

                @Override public void destroy() {
                }

                @Override public void destroyForcibly() {
                }

                @Override public boolean waitForExit(long timeoutMs) {
                    return true;
                }
            };
        };
        List<String> channelExecutions = new ArrayList<>();
        UmlFaceLauncher.Channel channel =
                (grantedWidth, item) -> channelExecutions.add("UML:" + grantedWidth);

        UmlFaceLauncher face = new UmlFaceLauncher(
                true,
                new UmlFaceLauncher.Environment() {
                    @Override public File nativeLibraryDir() {
                        return libDir;
                    }

                    @Override public File filesDir() {
                        return filesDir;
                    }
                },
                new UmlFaceLauncher.LaunchParams(
                        512, 4, java.util.Collections.emptyList()),
                keepAlive,
                spawner,
                channel);

        // Face down: the fabric slot's umlFaceWidth reads 0, and since no
        // other wired face exists the dispatch is a genuine outage, not the
        // wide-parallel policy refusal.
        java.util.Map<FabricWidthDecision.Face, FabricOrchestrator.FaceWorker> workers =
                new java.util.EnumMap<>(FabricWidthDecision.Face.class);
        workers.put(FabricWidthDecision.Face.UML, face.faceWorker());
        FabricOrchestrator orchestrator = AcceleratorLaneAssembly.fabricOrchestrator(
                true, result, qemuRequest.cpuCores(), accelRequest.cpuCores(),
                face.widthSupplier(), workers);
        FabricOrchestrator.Outcome faceDown =
                orchestrator.dispatch(new FabricOrchestrator.WorkItem() { }, 4);
        assertFalse(faceDown.admitted());
        assertEquals(
                FabricWidthDecision.RejectCode.NO_FACE_AVAILABLE,
                faceDown.route().rejectCode());
        assertEquals(0, spawns.get());

        // Face up: the kernel's effective SMP width feeds the slot, is
        // clamped to the ledger headroom (5), and the wide dispatch lands on
        // the UML face through the injected worker — the QEMU face is never
        // considered for wide work.
        assertTrue(face.start().started());
        assertEquals(1, spawns.get());
        assertEquals(1, keepAlive.acquires);
        FabricOrchestrator.Outcome wide =
                orchestrator.dispatch(new FabricOrchestrator.WorkItem() { }, 8);
        assertTrue(wide.admitted());
        assertEquals(FabricWidthDecision.Face.UML, wide.route().face());
        assertEquals(4, wide.route().width());
        assertEquals(1, channelExecutions.size());
        assertEquals("UML:4", channelExecutions.get(0));
        // The keep-alive is still held: the kernel is running.
        assertEquals(0, keepAlive.releases);
    }

    @Test
    public void umlFaceGateOffStartIsAZeroSideEffectRefusal() throws Exception {
        File libDir = tmp.newFolder("uml-gate-lib");
        File filesDir = tmp.newFolder("uml-gate-files");

        UmlFaceLauncher face = AcceleratorLaneAssembly.umlFace(
                false,
                new UmlFaceLauncher.Environment() {
                    @Override public File nativeLibraryDir() {
                        return libDir;
                    }

                    @Override public File filesDir() {
                        return filesDir;
                    }
                },
                UmlFaceLauncher.LaunchParams.defaults(),
                new FakeKeepAlive());

        UmlFaceLauncher.StartResult result = face.start();

        assertFalse(result.started());
        assertEquals(UmlFaceLauncher.StartCode.GATE_DISABLED, result.code());
        assertEquals(0, face.width());
        assertEquals(UmlFaceLauncher.Health.NOT_RUNNING, face.probe());
    }

    @Test
    public void umlFaceSlotSelectsTheLaunchShapeFromTheBuildFlag() throws Exception {
        // ACR 2026-11-12 D4 wiring: the slot assembles the launch shape the
        // build asked for — the §4 built-in-initramfs fast shape when the
        // build opted in (-Porchestella.uml.fastProfile=true), the exact
        // byte-identical runner shape otherwise. The shape is observable
        // through the asset precheck: with kernel+stub placed but no rootfs,
        // the runner shape answers the fixed MISSING_ASSET(rootfs) refusal
        // while the fast INITRAMFS shape passes the precheck and attempts the
        // spawn (which honestly fails on the empty stand-in file — no kernel
        // binary is needed here).
        File libDir = tmp.newFolder("uml-shape-lib");
        File filesDir = tmp.newFolder("uml-shape-files");
        File kernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        File stub = new File(libDir, UmlFaceLauncher.STUB_LIB_NAME);
        assertTrue(kernel.createNewFile());
        assertTrue(stub.createNewFile());
        // The launcher's asset pre-check requires the executable bit; POSIX
        // filesystems honor it (CI Linux), while Windows canExecute() is
        // always true for existing files, so this is a no-op there.
        kernel.setExecutable(true);
        stub.setExecutable(true);
        // No rootfs directory: the runner shape's third precheck leg,
        // deliberately absent.

        UmlFaceLauncher face = AcceleratorLaneAssembly.umlFace(
                true,
                new UmlFaceLauncher.Environment() {
                    @Override public File nativeLibraryDir() {
                        return libDir;
                    }

                    @Override public File filesDir() {
                        return filesDir;
                    }
                },
                UmlFaceLauncher.LaunchParams.defaults(),
                new FakeKeepAlive());

        UmlFaceLauncher.StartResult result = face.start();

        if (BuildConfig.UML_FAST_PROFILE) {
            // The fast INITRAMFS shape needs no external root: the precheck
            // passes and the spawn attempt fails honestly on the stand-in
            // file (every spawn failure is the fixed SPAWN_FAILED code).
            assertEquals(UmlFaceLauncher.StartCode.SPAWN_FAILED, result.code());
        } else {
            // The runner shape requires the rootfs directory: the fixed
            // refusal names it before any spawn attempt.
            assertEquals(UmlFaceLauncher.StartCode.MISSING_ASSET, result.code());
            assertEquals(UmlFaceLauncher.ROOTFS_DIR_NAME, result.detail());
        }
        // Either way the face never fakes a running state.
        assertEquals(0, face.width());
        assertEquals(UmlFaceLauncher.Health.NOT_RUNNING, face.probe());
        assertEquals(UmlFaceLauncher.State.STOPPED, face.state());
    }

    // --- Lane A (2026-11-13): the spawner-injecting umlFace slot -----------------

    @Test
    public void spawnerInjectingUmlFaceSlotKeepsTheLockedWiringAndExposesTheDrainSeam()
            throws Exception {
        // The 5-argument slot is the 4-argument slot plus the exec seam: the
        // same double gate, the same required keep-alive, the launch shape
        // still following the build flag. The spawner is the launcher's own
        // reserved injection seam ("tests and the device window own exec") —
        // the wiring owner uses it to attach the console drain, because the
        // fast shape's console rides fd 1 and an unread pipe would stall the
        // kernel on a full buffer. UmlFaceLauncher itself stays untouched.
        File libDir = tmp.newFolder("uml-spawner-lib");
        File filesDir = tmp.newFolder("uml-spawner-files");
        File kernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        File stub = new File(libDir, UmlFaceLauncher.STUB_LIB_NAME);
        assertTrue(kernel.createNewFile());
        assertTrue(stub.createNewFile());
        // The launcher's asset pre-check requires the executable bit; POSIX
        // filesystems honor it (CI Linux), while Windows canExecute() is
        // always true for existing files, so this is a no-op there.
        kernel.setExecutable(true);
        stub.setExecutable(true);
        // The runner shape needs the rootfs directory too; the INITRAMFS
        // shape ignores it — creating it keeps this test shape-proof.
        assertTrue(new File(filesDir, UmlFaceLauncher.ROOTFS_DIR_NAME).mkdirs());

        AtomicInteger spawns = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<List<String>> spawnedCommand =
                new java.util.concurrent.atomic.AtomicReference<>();
        UmlFaceLauncher.ProcessSpawner spawner = (command, environment, workingDir) -> {
            spawns.incrementAndGet();
            spawnedCommand.set(command);
            return new UmlFaceLauncher.UmlProcess() {
                @Override public boolean isAlive() {
                    return true;
                }

                @Override public void destroy() {
                }

                @Override public void destroyForcibly() {
                }

                @Override public boolean waitForExit(long timeoutMs) {
                    return true;
                }
            };
        };
        FakeKeepAlive keepAlive = new FakeKeepAlive();

        UmlFaceLauncher face = AcceleratorLaneAssembly.umlFace(
                true,
                new UmlFaceLauncher.Environment() {
                    @Override public File nativeLibraryDir() {
                        return libDir;
                    }

                    @Override public File filesDir() {
                        return filesDir;
                    }
                },
                UmlFaceLauncher.LaunchParams.defaults(),
                keepAlive,
                spawner);

        assertTrue(face.start().started());
        assertEquals("the injected spawner is the exec seam", 1, spawns.get());
        assertEquals("the kernel image is exec'd from the native library dir",
                kernel.getAbsolutePath(), spawnedCommand.get().get(0));
        assertEquals("the keep-alive is acquired exactly once", 1, keepAlive.acquires);
        // Shape-proof liveness: whichever shape the build flag selected, the
        // face is up, healthy, and honest about it.
        assertEquals(UmlFaceLauncher.Health.HEALTHY, face.probe());
    }

    @Test
    public void spawnerInjectingUmlFaceSlotGateOffIsAZeroSideEffectRefusal() throws Exception {
        File libDir = tmp.newFolder("uml-spawner-gate-lib");
        File filesDir = tmp.newFolder("uml-spawner-gate-files");
        AtomicInteger spawns = new AtomicInteger();
        UmlFaceLauncher.ProcessSpawner spawner = (command, environment, workingDir) -> {
            spawns.incrementAndGet();
            throw new AssertionError("a gate-off face never spawns");
        };
        FakeKeepAlive keepAlive = new FakeKeepAlive();

        UmlFaceLauncher face = AcceleratorLaneAssembly.umlFace(
                false,
                new UmlFaceLauncher.Environment() {
                    @Override public File nativeLibraryDir() {
                        return libDir;
                    }

                    @Override public File filesDir() {
                        return filesDir;
                    }
                },
                UmlFaceLauncher.LaunchParams.defaults(),
                keepAlive,
                spawner);

        UmlFaceLauncher.StartResult result = face.start();

        assertFalse(result.started());
        assertEquals(UmlFaceLauncher.StartCode.GATE_DISABLED, result.code());
        assertEquals("zero side effects: the spawner is never consulted", 0, spawns.get());
        assertEquals(0, face.width());
        assertEquals(0, keepAlive.acquires);
        assertEquals(UmlFaceLauncher.Health.NOT_RUNNING, face.probe());
    }

    // --- Gate 1 (2026-11-13): the compile-channel-injecting umlFace slot -------------

    @Test
    public void compileChannelInjectingUmlFaceSlotWiresTheChannelThroughTheSeam()
            throws Exception {
        // The 6-argument slot is the 5-argument slot plus the compile Channel:
        // the same double gate, the same required keep-alive, the launch shape
        // still following the build flag. UmlFaceLauncher stays byte-identical;
        // the channel arrives through the constructor's existing seam, and the
        // face's locked width honesty does the flip: a wired face reads the
        // kernel's real width while up, where the unwired runner shape reads 0.
        File libDir = tmp.newFolder("uml-channel-lib");
        File filesDir = tmp.newFolder("uml-channel-files");
        File kernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        File stub = new File(libDir, UmlFaceLauncher.STUB_LIB_NAME);
        assertTrue(kernel.createNewFile());
        assertTrue(stub.createNewFile());
        kernel.setExecutable(true);
        stub.setExecutable(true);
        assertTrue(new File(filesDir, UmlFaceLauncher.ROOTFS_DIR_NAME).mkdirs());
        FakeKeepAlive keepAlive = new FakeKeepAlive();
        UmlFaceLauncher.UmlProcess process = new UmlFaceLauncher.UmlProcess() {
            @Override public boolean isAlive() {
                return true;
            }

            @Override public void destroy() {
            }

            @Override public void destroyForcibly() {
            }

            @Override public boolean waitForExit(long timeoutMs) {
                return true;
            }
        };

        List<String> executed = new ArrayList<>();
        UmlFaceLauncher.Channel channel = (grantedWidth, item) ->
                executed.add(grantedWidth + ":" + (item != null));

        UmlFaceLauncher face = AcceleratorLaneAssembly.umlFace(
                true,
                environment(libDir, filesDir),
                UmlFaceLauncher.LaunchParams.defaults(),
                keepAlive,
                (command, environment, workingDir) -> process,
                channel);

        assertTrue(face.start().started());
        // The width-honesty flip: runner shape + wired channel reads real width.
        assertEquals(1, face.width());
        // The dispatch reaches the channel through the launcher's own execute.
        FabricOrchestrator.WorkItem item = new FabricOrchestrator.WorkItem() {
        };
        face.faceWorker().execute(FabricWidthDecision.Face.UML, 1, item);
        assertEquals(List.of("1:true"), executed);
        face.stop();
    }

    @Test
    public void compileChannelSlotWithNullChannelIsTheHistoricalUnwiredShape()
            throws Exception {
        // null degenerates to the five-argument slot shape-for-shape: both
        // slots answer the same launch shape (whatever the build flag selected
        // — the tests hold under both gradle variants) and the same honest
        // width (runner shape reads 0 unwired; the INITRAMFS shape reads 1
        // while up, the ACR D4 rule the launcher owns).
        File libDir = tmp.newFolder("uml-channel-null-lib");
        File filesDir = tmp.newFolder("uml-channel-null-files");
        File kernel = new File(libDir, UmlFaceLauncher.KERNEL_LIB_NAME);
        File stub = new File(libDir, UmlFaceLauncher.STUB_LIB_NAME);
        assertTrue(kernel.createNewFile());
        assertTrue(stub.createNewFile());
        kernel.setExecutable(true);
        stub.setExecutable(true);
        assertTrue(new File(filesDir, UmlFaceLauncher.ROOTFS_DIR_NAME).mkdirs());

        List<List<String>> spawnedCommands = new ArrayList<>();
        UmlFaceLauncher.ProcessSpawner spawner = (command, environment, workingDir) -> {
            spawnedCommands.add(command);
            return new UmlFaceLauncher.UmlProcess() {
                @Override public boolean isAlive() {
                    return true;
                }

                @Override public void destroy() {
                }

                @Override public void destroyForcibly() {
                }

                @Override public boolean waitForExit(long timeoutMs) {
                    return true;
                }
            };
        };
        UmlFaceLauncher.Environment dirs = environment(libDir, filesDir);

        UmlFaceLauncher fiveArg = AcceleratorLaneAssembly.umlFace(
                true, dirs, UmlFaceLauncher.LaunchParams.defaults(),
                new FakeKeepAlive(), spawner);
        UmlFaceLauncher sixArgNull = AcceleratorLaneAssembly.umlFace(
                true, dirs, UmlFaceLauncher.LaunchParams.defaults(),
                new FakeKeepAlive(), spawner, null);

        assertTrue(fiveArg.start().started());
        assertTrue(sixArgNull.start().started());
        assertEquals("the launch shape is identical through both slots",
                spawnedCommands.get(0), spawnedCommands.get(1));
        assertEquals("the width honesty is identical through both slots",
                fiveArg.width(), sixArgNull.width());
        fiveArg.stop();
        sixArgNull.stop();
    }

    private static UmlFaceLauncher.Environment environment(File libDir, File filesDir) {
        return new UmlFaceLauncher.Environment() {
            @Override public File nativeLibraryDir() {
                return libDir;
            }

            @Override public File filesDir() {
                return filesDir;
            }
        };
    }

    @Test
    public void imageMissingYieldsQemuOnly() {
        Fixture fixture = new Fixture();
        fixture.facts = () -> facts(true, true, false, false, true,
                ThermalState.NOMINAL, BatteryState.OK);

        AcceleratorLaneAssembly.Result result = fixture.assemble(true);
        fixture.coordinator().startAll();

        fixture.assertHealth();
        assertEquals(0, fixture.accelStarts.get());
        assertEquals(AcceleratorReasonCode.ACCEL_IMAGE_MISSING,
                result.decision().orElseThrow().reasonCode());
    }

    @Test
    public void compilerTripleMismatchYieldsQemuOnly() {
        Fixture fixture = new Fixture();
        // The image was built for a different triple than the guest's client.
        fixture.facts = () -> AcceleratorFacts.builder()
                .avfPresent(true)
                .nonProtectedSupported(true)
                .acceleratorImagePresent(true)
                .vsockSupported(true)
                .compilerTriple("arm-linux-gnueabihf")
                .thermalState(ThermalState.NOMINAL)
                .batteryState(BatteryState.OK)
                .build();

        AcceleratorLaneAssembly.Result result = fixture.assemble(true);
        fixture.coordinator().startAll();

        fixture.assertHealth();
        assertEquals(0, fixture.accelStarts.get());
        assertEquals(AcceleratorReasonCode.COMPILER_MISMATCH,
                result.decision().orElseThrow().reasonCode());
    }

    @Test
    public void thermalBudgetYieldsQemuOnly() {
        Fixture fixture = new Fixture();
        fixture.facts = () -> facts(true, true, false, true, true,
                ThermalState.SEVERE, BatteryState.OK);

        AcceleratorLaneAssembly.Result result = fixture.assemble(true);
        fixture.coordinator().startAll();

        fixture.assertHealth();
        assertEquals(0, fixture.accelStarts.get());
        assertEquals(AcceleratorReasonCode.THERMAL_BUDGET,
                result.decision().orElseThrow().reasonCode());
    }

    @Test
    public void unarmedDecisionStillRejectsTheAcceleratorReservation() {
        Fixture fixture = new Fixture();
        fixture.facts = () -> facts(false, false, false, true, true,
                ThermalState.NOMINAL, BatteryState.OK);

        fixture.assemble(true);
        DualLaneCoordinator.StartReport report = fixture.coordinator().startAll();

        assertEquals(LaneRejectCode.ACCELERATOR_UNAVAILABLE, report.rejectCode());
        assertFalse(fixture.hasEvent(LaneEvent.LANE_STARTED, LaneId.ACCEL));
    }

    @Test
    public void unreadableFactsRefuseTheLaneInsteadOfCrashingTheLaunch() {
        Fixture fixture = new Fixture();
        fixture.facts = () -> {
            throw new IllegalStateException("platform read failed");
        };

        AcceleratorLaneAssembly.Result result = fixture.assemble(true);
        fixture.coordinator().startAll();

        fixture.assertHealth();
        assertEquals(0, fixture.accelStarts.get());
        // Unknown facts claim nothing, so no backend can even be a candidate.
        assertEquals(AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND,
                result.decision().orElseThrow().reasonCode());
    }

    // --- readiness is a signal, not liveness ------------------------------------

    @Test
    public void readinessNotSetKeepsQemuInStarting() {
        Fixture fixture = new Fixture();
        fixture.qemuProbe = QemuReadinessProbe.absent();
        fixture.assemble(false);

        fixture.coordinator().startAll();

        fixture.assertHealth();
        assertEquals(LaneState.STARTING, fixture.coordinator().state(LaneId.QEMU));
        assertFalse(fixture.hasEvent(LaneEvent.LANE_READY, LaneId.QEMU));
    }

    // --- protected pool wiring --------------------------------------------------

    @Test
    public void protectedPoolSharesTheLedgerAndStaysOffByDefault() {
        Fixture fixture = new Fixture();
        AcceleratorLaneAssembly.Result result = fixture.assemble(false);

        ProtectedWarmPool pool = AcceleratorLaneAssembly.protectedPool(
                new NoVirtualMachineApi(),
                instanceId -> null,
                result);

        try {
            pool.acquire();
            fail("a zero-capacity pool must refuse");
        } catch (ProtectedWarmPool.PoolException expected) {
            assertEquals(ProtectedWarmPool.Code.DISABLED, expected.code());
        }
    }

    // --- PVM-06 wiring: the compile worker pool ---------------------------------

    @Test
    public void workerPoolStaysOffByDefaultAndNeverTouchesThePlatform() {
        Fixture fixture = new Fixture();
        AcceleratorLaneAssembly.Result result = fixture.assemble(false);

        ProtectedWorkerPool pool = AcceleratorLaneAssembly.workerPool(
                new NoVirtualMachineApi(),
                (instanceId, topology) -> null,
                result,
                ProtectedWorkerPool.Config.defaults());
        try {
            pool.acquire(new WorkerPool.Job() {
            });
            fail("a zero-capacity worker pool must refuse");
        } catch (WorkerPool.WorkerPoolException expected) {
            assertEquals(WorkerPool.Code.DISABLED, expected.code());
        }
        assertEquals(0, pool.health().capacity());
        assertEquals(0, pool.stats().activeInstances());
    }

    @Test
    public void workerPoolGrowthIsBoundedByTheCoordinatorLedger() {
        Fixture fixture = new Fixture();
        fixture.facts = () -> facts(true, true, false, true, true,
                ThermalState.NOMINAL, BatteryState.CHARGING);
        AcceleratorLaneAssembly.Result result = fixture.assemble(true);
        fixture.coordinator().startAll();
        // Both lanes reserved 4 (QEMU) + 2 (ACCEL) of the 8-vCPU host budget;
        // the worker pool must count its own footprint against the same ledger.
        assertEquals(6, fixture.coordinator().ledger().snapshot().cpuCores());

        ProtectedWorkerPool pool = AcceleratorLaneAssembly.workerPool(
                new NoVirtualMachineApi(),
                (instanceId, topology) -> null,
                result,
                ProtectedWorkerPool.Config.builder()
                        .capacity(1)
                        .memoryMbPerInstance(1024L)
                        .build());
        try {
            WorkerPool.ResizeResult refused = pool.resize(3);
            assertFalse(refused.accepted());
            assertEquals(LaneRejectCode.CPU_EXCEEDED, refused.rejectReason());
            assertEquals(1, pool.health().capacity());

            WorkerPool.ResizeResult accepted = pool.resize(2);
            assertTrue(accepted.accepted());
            assertEquals(2, pool.health().capacity());
            // Lazy slots: resize pre-checks the ledger but never starts a VM.
            assertEquals(0, pool.stats().activeInstances());
        } finally {
            pool.close();
        }
    }

    private static AcceleratorFacts facts(
            boolean avfPresent,
            boolean nonProtected,
            boolean protectedOnly,
            boolean imagePresent,
            boolean vsock,
            ThermalState thermal,
            BatteryState battery) {
        return AcceleratorFacts.builder()
                .avfPresent(avfPresent)
                .nonProtectedSupported(nonProtected)
                .protectedOnly(protectedOnly)
                .acceleratorImagePresent(imagePresent)
                .vsockSupported(vsock)
                .compilerTriple(imagePresent ? TRIPLE : "")
                .thermalState(thermal)
                .batteryState(battery)
                .build();
    }

    private static final class Fixture {
        final AtomicInteger qemuStarts = new AtomicInteger();
        final AtomicInteger accelStarts = new AtomicInteger();
        final AtomicInteger factReads = new AtomicInteger();
        final FakeSession session = new FakeSession();
        final FakeLane accelLane = new FakeLane();
        final List<LaneEvent> events = new ArrayList<>();
        QemuReadinessProbe qemuProbe = QemuReadinessProbe.absent();
        AcceleratorFactProvider facts = () -> AcceleratorFacts.unknown();
        // Overridable for the UML-face flow (a host budget wider than the two
        // lane reservations leaves the ledger headroom the UML face lives in).
        LaneBudget hostBudget = HOST_BUDGET;
        LaneRequest qemuRequest = QEMU_REQUEST;
        LaneRequest accelRequest = ACCEL_REQUEST;
        private AcceleratorLaneAssembly.Result result;

        AcceleratorLaneAssembly.Result assemble(boolean flag) {
            result = AcceleratorLaneAssembly.assemble(
                    new AcceleratorLaneAssembly.Config(flag, TRIPLE, 50L, 50L),
                    new AcceleratorLaneAssembly.Inputs(
                            hostBudget,
                            qemuRequest,
                            accelRequest,
                            () -> {
                                qemuStarts.incrementAndGet();
                                return AcceleratorLaneAssembly.qemuLane(session, qemuProbe);
                            },
                            () -> {
                                accelStarts.incrementAndGet();
                                return accelLane;
                            },
                            new FakeClock(),
                            events::add,
                            () -> {
                                factReads.incrementAndGet();
                                return facts.read();
                            }));
            return result;
        }

        DualLaneCoordinator coordinator() {
            return result.coordinator();
        }

        void assertHealth() {
            if (result == null) {
                fail("assembly result missing");
            }
        }

        boolean hasEvent(String name, LaneId lane) {
            for (LaneEvent event : events) {
                if (event.name().equals(name) && event.lane() == lane) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The UML face's keep-alive seam recording acquire/release only. */
    private static final class FakeKeepAlive implements UmlFaceLauncher.KeepAlive {
        int acquires;
        int releases;

        @Override public void acquire() {
            acquires++;
        }

        @Override public void release() {
            releases++;
        }
    }

    private static final class FakeClock implements LaneClock {
        long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(long millis) {
            nanos += Math.max(0L, millis) * 1_000_000L;
        }
    }

    private static final class FakeLane implements LaneRuntime {
        @Override
        public LaneId laneId() {
            return LaneId.ACCEL;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public LaneStopOutcome stop(long graceMs) {
            return LaneStopOutcome.CONFIRMED;
        }
    }

    private static final class NoVirtualMachineApi implements FastLaneVirtualMachineApi {
        @Override
        public FastLaneHandle start(FastLaneLaunchSpec spec) throws FastLaneException {
            throw new FastLaneException(
                    FastLaneException.VM_CREATE_FAILED,
                    "a zero-capacity pool never starts a virtual machine");
        }

        @Override
        public FastLaneChannel connect(FastLaneHandle handle, int port) throws FastLaneException {
            throw new FastLaneException(
                    FastLaneException.VSOCK_FAILED,
                    "a zero-capacity pool never connects a channel");
        }
    }

    private static final class FakeSession implements RuntimeOwnedSession {
        boolean alive = true;

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public OutputStream terminalInput() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream terminalOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream controlInput() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream controlOutput() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public void close() {
            // No resources in this fake.
        }

        @Override
        public RuntimeProcessSupervisor.StopResult stopRuntime(long graceMs) {
            alive = false;
            return RuntimeProcessSupervisor.StopResult.CONFIRMED;
        }

        @Override
        public boolean isRuntimeAlive() {
            return alive;
        }
    }
}
