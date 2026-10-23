package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** AX-08: the assembly is the only gate that may arm the accelerator lane. */
public final class AcceleratorLaneAssemblyTest {
    private static final String TRIPLE = "aarch64-linux-gnu";
    private static final LaneBudget HOST_BUDGET = new LaneBudget(8, 8192L, 128L, true);
    private static final LaneRequest QEMU_REQUEST = new LaneRequest(LaneId.QEMU, 4, 4096L, 32L);
    private static final LaneRequest ACCEL_REQUEST = new LaneRequest(LaneId.ACCEL, 2, 2048L, 16L);

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
        assertEquals(AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED,
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
        assertEquals(AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED,
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
        assertEquals(AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED,
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
        private AcceleratorLaneAssembly.Result result;

        AcceleratorLaneAssembly.Result assemble(boolean flag) {
            result = AcceleratorLaneAssembly.assemble(
                    new AcceleratorLaneAssembly.Config(flag, TRIPLE, 50L, 50L),
                    new AcceleratorLaneAssembly.Inputs(
                            HOST_BUDGET,
                            QEMU_REQUEST,
                            ACCEL_REQUEST,
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
