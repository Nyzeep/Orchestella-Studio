package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public final class FastLaneTelemetryTest {
    private static final Instant T0 = Instant.parse("2026-10-21T00:00:00Z");

    @Test
    public void disabledConfigDoesNotReadTheClockOrCallTheSink() {
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", new ExplodingClock(), FastLaneTelemetryConfig.DISABLED, 8);
        assertFalse(telemetry.mark(FastLaneEventType.FASTLANE_REQUESTED));
        assertEquals(0, telemetry.queuedEvents());
        assertEquals(0, telemetry.flush(line -> {
            throw new AssertionError("sink must not be called");
        }));
        assertNull(telemetry.metrics());
        assertEquals(0, telemetry.notObserved().size());
        assertTrue(telemetry.summaryJson().contains("\"enabled\":false"));
    }

    @Test
    public void stopEventsWithoutDiagnosticsAreRejected() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 8);
        telemetry.start();
        for (FastLaneEventType type : new FastLaneEventType[] {
                FastLaneEventType.STOP_CONFIRMED, FastLaneEventType.STOP_UNCONFIRMED}) {
            try {
                telemetry.mark(type);
                fail("accepted " + type + " without diagnostics");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        assertEquals(0, telemetry.queuedEvents());
    }

    @Test
    public void fullDebugPolicyCannotBeBuiltThroughTheProductionConstructor() {
        try {
            new FastLaneTelemetryConfig(true, FastLaneDebugLevel.FULL, true);
            fail("production constructor accepted FULL debug");
        } catch (IllegalArgumentException expected) {
            assertTrue(FastLaneTelemetryConfig.QA_FULL.debugLevel().weakensProtection());
        }
    }

    @Test
    public void productionRecordsStageEventsAndReportsMissingStages() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 16);
        telemetry.start();
        assertTrue(telemetry.mark(FastLaneEventType.FASTLANE_REQUESTED));
        clock.advance(25);
        assertTrue(telemetry.mark(FastLaneEventType.VM_CREATED));

        List<String> lines = flushToLines(telemetry);
        assertEquals(2, lines.size());
        assertEquals(FastLaneEventType.FASTLANE_REQUESTED,
                FastLaneEventCodec.decode(lines.get(0)).type());
        assertEquals(25L, FastLaneEventCodec.decode(lines.get(1)).elapsedMs());

        List<FastLaneEventType> missing = telemetry.notObserved();
        assertEquals(8, missing.size());
        assertTrue(missing.contains(FastLaneEventType.PAYLOAD_READY));
        assertFalse(missing.contains(FastLaneEventType.VM_CREATED));

        String summary = telemetry.summaryJson();
        assertTrue(summary.contains("\"observed\":[\"FASTLANE_REQUESTED\",\"VM_CREATED\"]"));
        assertTrue(summary.contains("\"notObserved\":[\"PAYLOAD_READY\""));
        assertTrue(summary.contains("\"dropped\":0"));
    }

    @Test
    public void elapsedTimeNeverGoesBackwards() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 16);
        telemetry.start();
        clock.advance(10);
        telemetry.mark(FastLaneEventType.VM_CREATED);
        clock.set(T0.plusMillis(5));
        telemetry.mark(FastLaneEventType.PAYLOAD_READY);
        clock.set(T0.plusMillis(20));
        telemetry.mark(FastLaneEventType.TERMINAL_READY);

        List<FastLaneEvent> events = flushToEvents(telemetry);
        assertEquals(10L, events.get(0).elapsedMs());
        assertEquals(10L, events.get(1).elapsedMs());
        assertEquals(20L, events.get(2).elapsedMs());
        assertEquals(1L, telemetry.clockRegressions());
    }

    @Test
    public void boundedQueueEvictsOldestNonStopEventFirst() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 3);
        telemetry.start();
        telemetry.mark(FastLaneEventType.VM_CREATED);
        telemetry.mark(FastLaneEventType.PAYLOAD_READY);
        telemetry.mark(FastLaneEventType.TERMINAL_READY);
        telemetry.markStopUnconfirmed(
                new FastLaneStopDiagnostics(FastLaneStopStage.PROCESS_EXIT_WAIT, 4242L, 5000L, null));

        assertEquals(3, telemetry.queuedEvents());
        assertEquals(1L, telemetry.droppedEvents());
        List<FastLaneEvent> events = flushToEvents(telemetry);
        assertEquals(3, events.size());
        assertEquals(FastLaneEventType.PAYLOAD_READY, events.get(0).type());
        assertEquals(FastLaneEventType.TERMINAL_READY, events.get(1).type());
        assertEquals(FastLaneEventType.STOP_UNCONFIRMED, events.get(2).type());
    }

    @Test
    public void stopUnconfirmedKeepsStagePidGraceAndExitCode() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 8);
        telemetry.start();
        telemetry.markStopUnconfirmed(
                new FastLaneStopDiagnostics(FastLaneStopStage.VSOCK_CLOSE, 777L, 1500L, 137));

        String line = flushToLines(telemetry).get(0);
        assertTrue(line.contains("\"event\":\"STOP_UNCONFIRMED\""));
        assertTrue(line.contains("\"status\":\"FAILED\""));
        assertTrue(line.contains("\"stage\":\"VSOCK_CLOSE\""));
        assertTrue(line.contains("\"pid\":777"));
        assertTrue(line.contains("\"graceMs\":1500"));
        assertTrue(line.contains("\"exitCode\":137"));
        assertFalse(line.contains("STOPPED"));
        assertTrue(telemetry.notObserved().contains(FastLaneEventType.STOP_CONFIRMED));
    }

    @Test
    public void stopConfirmedIsASeparateEvent() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 8);
        telemetry.start();
        telemetry.markStopConfirmed(
                new FastLaneStopDiagnostics(FastLaneStopStage.GATEWAY_EXIT, 42L, 2000L, 0));
        FastLaneEvent event = flushToEvents(telemetry).get(0);
        assertEquals(FastLaneEventType.STOP_CONFIRMED, event.type());
        assertEquals(FastLaneEventStatus.OK, event.status());
        assertEquals(Integer.valueOf(0), event.stop().exitCode());
    }

    @Test
    public void stopUnconfirmedSurvivesUntilItIsTheOnlyEventLeft() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 2);
        telemetry.start();
        telemetry.mark(FastLaneEventType.STOP_REQUESTED);
        telemetry.markStopUnconfirmed(
                new FastLaneStopDiagnostics(FastLaneStopStage.PROCESS_EXIT_WAIT, 1L, 1000L, null));
        telemetry.markStopConfirmed(
                new FastLaneStopDiagnostics(FastLaneStopStage.GATEWAY_EXIT, 1L, 1000L, 0));

        List<FastLaneEvent> events = flushToEvents(telemetry);
        assertEquals(2, events.size());
        assertEquals(FastLaneEventType.STOP_UNCONFIRMED, events.get(0).type());
        assertEquals(FastLaneEventType.STOP_CONFIRMED, events.get(1).type());
        assertEquals(1L, telemetry.droppedEvents());
    }

    @Test
    public void flushDoesNotDropEventsRecordedByTheSink() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 8);
        telemetry.start();
        telemetry.mark(FastLaneEventType.FASTLANE_REQUESTED);

        int written = telemetry.flush(line -> telemetry.mark(FastLaneEventType.PAYLOAD_READY));
        assertEquals(1, written);
        assertEquals(1, telemetry.queuedEvents());
        assertEquals(FastLaneEventType.PAYLOAD_READY,
                flushToEvents(telemetry).get(0).type());
    }

    @Test
    public void flushFailureKeepsUnwrittenEventsInOrder() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 8);
        telemetry.start();
        telemetry.mark(FastLaneEventType.FASTLANE_REQUESTED);
        telemetry.mark(FastLaneEventType.VM_CREATED);
        telemetry.mark(FastLaneEventType.PAYLOAD_READY);

        int written = telemetry.flush(new FastLaneTelemetry.LineSink() {
            private int count;

            @Override
            public void writeLine(String line) throws IOException {
                if (count++ == 0) {
                    return;
                }
                throw new IOException("disk full");
            }
        });
        assertEquals(1, written);
        assertEquals(2, telemetry.queuedEvents());
        assertEquals("IOException", telemetry.lastSinkError());

        List<FastLaneEvent> remaining = flushToEvents(telemetry);
        assertEquals(2, remaining.size());
        assertEquals(FastLaneEventType.VM_CREATED, remaining.get(0).type());
        assertEquals(FastLaneEventType.PAYLOAD_READY, remaining.get(1).type());
    }

    @Test
    public void qaFullMarksEveryEventAsWeakenedAndCollectsMetrics() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.QA_FULL, 8);
        telemetry.start();
        assertNotNull(telemetry.metrics());
        telemetry.metrics().setStartupMs(120L);
        telemetry.mark(FastLaneEventType.VM_CREATED);

        String line = flushToLines(telemetry).get(0);
        assertTrue(line.contains("\"debugLevel\":\"FULL\""));
        assertTrue(line.contains("\"protectionWeakened\":true"));
        assertTrue(line.contains("\"metrics\":{\"startupMs\":120"));
    }

    @Test
    public void concurrentMarksStayBoundedAndEveryAttemptIsCounted() throws Exception {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 64);
        telemetry.start();
        int threads = 8;
        int perThread = 100;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perThread; j++) {
                    telemetry.mark(FastLaneEventType.VM_CREATED);
                }
            });
            worker.start();
            workers.add(worker);
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (Thread worker : workers) {
            worker.join(5000L);
        }

        long accounted = telemetry.queuedEvents() + telemetry.droppedEvents();
        assertEquals((long) threads * perThread, accounted);
        assertTrue(telemetry.queuedEvents() <= 64);
        assertEquals(telemetry.queuedEvents(), flushToLines(telemetry).size());
    }

    @Test
    public void retriesProduceDistinctOrderedEvents() {
        MutableClock clock = new MutableClock(T0);
        FastLaneTelemetry telemetry = new FastLaneTelemetry(
                "i-1", clock, FastLaneTelemetryConfig.PRODUCTION, 8);
        telemetry.start();
        telemetry.mark(FastLaneEventType.STOP_REQUESTED);
        telemetry.markStopUnconfirmed(
                new FastLaneStopDiagnostics(FastLaneStopStage.PROCESS_EXIT_WAIT, 1L, 1000L, null));
        clock.advance(10);
        telemetry.markStopConfirmed(
                new FastLaneStopDiagnostics(FastLaneStopStage.GATEWAY_EXIT, 1L, 1000L, 0));

        List<FastLaneEvent> events = flushToEvents(telemetry);
        assertEquals(3, events.size());
        assertEquals(FastLaneEventType.STOP_REQUESTED, events.get(0).type());
        assertEquals(FastLaneEventType.STOP_UNCONFIRMED, events.get(1).type());
        assertEquals(FastLaneEventType.STOP_CONFIRMED, events.get(2).type());
        assertTrue(events.get(1).elapsedMs() <= events.get(2).elapsedMs());
    }

    @Test
    public void invalidCapacityIsRejected() {
        for (int capacity : new int[] {-1, 0, FastLaneTelemetry.MAX_CAPACITY + 1}) {
            try {
                new FastLaneTelemetry("i-1", Clock.systemUTC(),
                        FastLaneTelemetryConfig.PRODUCTION, capacity);
                fail("accepted capacity " + capacity);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    private static List<String> flushToLines(FastLaneTelemetry telemetry) {
        List<String> lines = new ArrayList<>();
        telemetry.flush(lines::add);
        return lines;
    }

    private static List<FastLaneEvent> flushToEvents(FastLaneTelemetry telemetry) {
        List<FastLaneEvent> events = new ArrayList<>();
        for (String line : flushToLines(telemetry)) {
            events.add(FastLaneEventCodec.decode(line));
        }
        return events;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(long millis) {
            instant = instant.plusMillis(millis);
        }

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class ExplodingClock extends Clock {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            throw new AssertionError("clock must not be read");
        }
    }
}
