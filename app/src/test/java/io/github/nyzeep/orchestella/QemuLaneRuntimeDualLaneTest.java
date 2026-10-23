package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneBudget;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneClock;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneEvent;
import io.github.nyzeep.orchestella.runtime.instance.duallane.LaneId;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** Regression: QEMU liveness is not readiness; no signal means STARTING. */
public final class QemuLaneRuntimeDualLaneTest {
    private static final LaneBudget BUDGET = new LaneBudget(8, 8192L, 128L, true);
    private static final LaneRequest QEMU_REQUEST = new LaneRequest(LaneId.QEMU, 4, 4096L, 32L);
    private static final LaneRequest ACCEL_REQUEST = new LaneRequest(LaneId.ACCEL, 2, 2048L, 16L);

    @Test
    public void defaultConstructorNeverReportsReady() {
        FakeSession session = new FakeSession();
        QemuLaneRuntime runtime = new QemuLaneRuntime(session);

        assertTrue(runtime.isAlive());
        assertFalse(runtime.isReady());
    }

    @Test
    public void aliveWithoutReadinessSignalStaysStarting() {
        Fixture fixture = new Fixture();
        fixture.ready.set(false);

        fixture.coordinator.startAll();

        assertEquals(LaneState.STARTING, fixture.coordinator.state(LaneId.QEMU));
        assertFalse(fixture.hasEvent(LaneEvent.LANE_READY, LaneId.QEMU));
    }

    @Test
    public void readinessSignalMovesQemuToReady() {
        Fixture fixture = new Fixture();
        fixture.ready.set(true);

        fixture.coordinator.startAll();

        assertEquals(LaneState.READY, fixture.coordinator.state(LaneId.QEMU));
        assertTrue(fixture.hasEvent(LaneEvent.LANE_READY, LaneId.QEMU));
    }

    @Test
    public void deathBeforeReadyMarksLostAndStopsAccelerator() {
        Fixture fixture = new Fixture();
        fixture.ready.set(false);
        fixture.session.alive = false;

        fixture.coordinator.startAll();

        assertEquals(LaneState.LOST, fixture.coordinator.state(LaneId.QEMU));
        assertEquals(1, fixture.accel.stopCalls);
        assertTrue(fixture.hasEvent(LaneEvent.LANE_LOST, LaneId.QEMU));
    }

    private static final class Fixture {
        final AtomicBoolean ready = new AtomicBoolean(false);
        final FakeSession session = new FakeSession();
        final FakeAccelLane accel = new FakeAccelLane();
        final List<LaneEvent> events = new ArrayList<>();
        final DualLaneCoordinator coordinator;

        Fixture() {
            coordinator = new DualLaneCoordinator(
                    BUDGET,
                    QEMU_REQUEST,
                    ACCEL_REQUEST,
                    () -> new QemuLaneRuntime(session, ready::get),
                    () -> accel,
                    new DualLaneCoordinator.Config(50L, 50L),
                    new FakeClock(),
                    events::add);
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

    private static final class FakeAccelLane implements LaneRuntime {
        int stopCalls;

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
            return false;
        }

        @Override
        public LaneStopOutcome stop(long graceMs) {
            stopCalls++;
            return LaneStopOutcome.CONFIRMED;
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
