package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class DisplayResizeDebounceTest {

    private static final class TestScheduler implements DisplayResizeDebounce.TaskScheduler {
        private static final class ScheduledItem implements DisplayResizeDebounce.Cancellable {
            final long triggerTime;
            final Runnable task;
            boolean cancelled = false;

            ScheduledItem(long triggerTime, Runnable task) {
                this.triggerTime = triggerTime;
                this.task = task;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        }

        private long currentTime = 0;
        private final List<ScheduledItem> queue = new ArrayList<>();
        boolean shutdownCalled = false;

        @Override
        public DisplayResizeDebounce.Cancellable schedule(long delayMs, Runnable task) {
            ScheduledItem item = new ScheduledItem(currentTime + delayMs, task);
            queue.add(item);
            return item;
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
            queue.clear();
        }

        void advanceTime(long deltaMs) {
            currentTime += deltaMs;
            List<ScheduledItem> due = new ArrayList<>();
            for (ScheduledItem item : queue) {
                if (!item.cancelled && item.triggerTime <= currentTime) {
                    due.add(item);
                }
            }
            queue.removeAll(due);
            for (ScheduledItem item : due) {
                if (!item.cancelled) {
                    item.task.run();
                }
            }
        }
    }

    private static final class RecordedResize {
        final int width;
        final int height;

        RecordedResize(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    @Test
    public void singleResizeRequestDispatchesAfterDebounceWindow() {
        TestScheduler scheduler = new TestScheduler();
        List<RecordedResize> dispatches = new ArrayList<>();

        DisplayResizeDebounce debounce = new DisplayResizeDebounce(
                (w, h) -> {
                    dispatches.add(new RecordedResize(w, h));
                    return true;
                },
                200,
                scheduler
        );

        debounce.requestResize(1920, 1080);
        assertTrue(debounce.hasPendingResize());
        assertEquals(1920, debounce.getPendingWidth());
        assertEquals(1080, debounce.getPendingHeight());
        assertEquals(0, dispatches.size());

        // Advance 100ms: still within window, should not dispatch yet
        scheduler.advanceTime(100);
        assertEquals(0, dispatches.size());
        assertTrue(debounce.hasPendingResize());

        // Advance another 100ms: threshold reached
        scheduler.advanceTime(100);
        assertEquals(1, dispatches.size());
        assertEquals(1920, dispatches.get(0).width);
        assertEquals(1080, dispatches.get(0).height);
        assertFalse(debounce.hasPendingResize());
        assertEquals(1920, debounce.getLastDispatchedWidth());
        assertEquals(1080, debounce.getLastDispatchedHeight());
    }

    @Test
    public void rapidResizeRequestsDebounceToLatestDimensions() {
        TestScheduler scheduler = new TestScheduler();
        List<RecordedResize> dispatches = new ArrayList<>();

        DisplayResizeDebounce debounce = new DisplayResizeDebounce(
                (w, h) -> {
                    dispatches.add(new RecordedResize(w, h));
                    return true;
                },
                200,
                scheduler
        );

        // Sequence of rapid events simulating screen rotation animation
        debounce.requestResize(1000, 800);
        scheduler.advanceTime(50);
        debounce.requestResize(1200, 900);
        scheduler.advanceTime(50);
        debounce.requestResize(1600, 1000);
        scheduler.advanceTime(50);
        debounce.requestResize(1920, 1080);

        assertEquals(0, dispatches.size());
        assertTrue(debounce.hasPendingResize());
        assertEquals(1920, debounce.getPendingWidth());
        assertEquals(1080, debounce.getPendingHeight());

        // Let the remaining debounce period elapse
        scheduler.advanceTime(200);

        // Exactly one dispatch with the final dimensions
        assertEquals(1, dispatches.size());
        assertEquals(1920, dispatches.get(0).width);
        assertEquals(1080, dispatches.get(0).height);
        assertFalse(debounce.hasPendingResize());
    }

    @Test
    public void identicalDimensionsDoNotReTriggerDispatch() {
        TestScheduler scheduler = new TestScheduler();
        List<RecordedResize> dispatches = new ArrayList<>();

        DisplayResizeDebounce debounce = new DisplayResizeDebounce(
                (w, h) -> {
                    dispatches.add(new RecordedResize(w, h));
                    return true;
                },
                200,
                scheduler
        );

        debounce.requestResize(1920, 1080);
        scheduler.advanceTime(200);
        assertEquals(1, dispatches.size());

        // Requesting identical size should be a no-op
        debounce.requestResize(1920, 1080);
        assertFalse(debounce.hasPendingResize());
        scheduler.advanceTime(300);
        assertEquals(1, dispatches.size());
    }

    @Test
    public void flushImmediatelyExecutesPendingResize() {
        TestScheduler scheduler = new TestScheduler();
        List<RecordedResize> dispatches = new ArrayList<>();

        DisplayResizeDebounce debounce = new DisplayResizeDebounce(
                (w, h) -> {
                    dispatches.add(new RecordedResize(w, h));
                    return true;
                },
                200,
                scheduler
        );

        debounce.requestResize(1920, 1080);
        assertTrue(debounce.hasPendingResize());
        assertEquals(0, dispatches.size());

        debounce.flush();
        assertEquals(1, dispatches.size());
        assertEquals(1920, dispatches.get(0).width);
        assertEquals(1080, dispatches.get(0).height);
        assertFalse(debounce.hasPendingResize());

        // Later timer expiration should not trigger a second dispatch
        scheduler.advanceTime(300);
        assertEquals(1, dispatches.size());
    }

    @Test
    public void cancelClearsPendingResize() {
        TestScheduler scheduler = new TestScheduler();
        List<RecordedResize> dispatches = new ArrayList<>();

        DisplayResizeDebounce debounce = new DisplayResizeDebounce(
                (w, h) -> {
                    dispatches.add(new RecordedResize(w, h));
                    return true;
                },
                200,
                scheduler
        );

        debounce.requestResize(1920, 1080);
        assertTrue(debounce.hasPendingResize());

        debounce.cancel();
        assertFalse(debounce.hasPendingResize());

        scheduler.advanceTime(300);
        assertEquals(0, dispatches.size());
    }

    @Test
    public void rejectsOutOfRangeDimensions() {
        TestScheduler scheduler = new TestScheduler();
        List<RecordedResize> dispatches = new ArrayList<>();

        DisplayResizeDebounce debounce = new DisplayResizeDebounce(
                (w, h) -> {
                    dispatches.add(new RecordedResize(w, h));
                    return true;
                },
                200,
                scheduler
        );

        // Under min (< 100)
        debounce.requestResize(50, 1080);
        assertFalse(debounce.hasPendingResize());

        debounce.requestResize(1920, 99);
        assertFalse(debounce.hasPendingResize());

        // Over max (> 7680)
        debounce.requestResize(8000, 1080);
        assertFalse(debounce.hasPendingResize());

        debounce.requestResize(1920, 9000);
        assertFalse(debounce.hasPendingResize());

        assertEquals(0, dispatches.size());
    }

    @Test
    public void rejectedDispatchDoesNotPoisonLastDispatchedAndAllowsRetry() {
        TestScheduler scheduler = new TestScheduler();
        List<RecordedResize> acceptedDispatches = new ArrayList<>();
        AtomicBoolean acceptNext = new AtomicBoolean(false);

        DisplayResizeDebounce debounce = new DisplayResizeDebounce(
                (w, h) -> {
                    if (!acceptNext.get()) {
                        return false;
                    }
                    acceptedDispatches.add(new RecordedResize(w, h));
                    return true;
                },
                200,
                scheduler
        );

        debounce.requestResize(1080, 2400);
        scheduler.advanceTime(200);
        assertEquals(0, acceptedDispatches.size());
        assertEquals(0, debounce.getDispatchedCount());
        assertEquals(-1, debounce.getLastDispatchedWidth());
        assertEquals(-1, debounce.getLastDispatchedHeight());

        // Same dimensions can now be retried and accepted.
        acceptNext.set(true);
        debounce.requestResize(1080, 2400);
        assertTrue(debounce.hasPendingResize());
        scheduler.advanceTime(200);
        assertEquals(1, acceptedDispatches.size());
        assertEquals(1, debounce.getDispatchedCount());
        assertEquals(1080, debounce.getLastDispatchedWidth());
        assertEquals(2400, debounce.getLastDispatchedHeight());
    }

    @Test
    public void fitsOversizedDimensionsWithinViewportBudgetBeforeDispatch() {
        TestScheduler scheduler = new TestScheduler();
        List<RecordedResize> dispatches = new ArrayList<>();

        DisplayResizeDebounce debounce = new DisplayResizeDebounce(
                (w, h) -> {
                    dispatches.add(new RecordedResize(w, h));
                    return true;
                },
                200,
                scheduler
        );

        debounce.requestResize(1440, 3120);
        assertEquals(1390, debounce.getPendingWidth());
        assertEquals(3014, debounce.getPendingHeight());
        scheduler.advanceTime(200);

        assertEquals(1, dispatches.size());
        assertEquals(1390, dispatches.get(0).width);
        assertEquals(3014, dispatches.get(0).height);
        assertTrue((long) dispatches.get(0).width * dispatches.get(0).height <= 4_194_304L);
    }

    @Test
    public void closeShutsDownSchedulerAndDefaultExecutorThreadWithoutLeaking() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        DisplayResizeDebounce mockDebounce = new DisplayResizeDebounce((w, h) -> true, 200, scheduler);
        mockDebounce.requestResize(1920, 1080);
        mockDebounce.close();
        assertTrue(scheduler.shutdownCalled);
        assertTrue(mockDebounce.isClosed());
        assertFalse(mockDebounce.hasPendingResize());
        mockDebounce.requestResize(1280, 720);
        assertFalse(mockDebounce.hasPendingResize());

        CountDownLatch dispatched = new CountDownLatch(1);
        DisplayResizeDebounce realDebounce = new DisplayResizeDebounce((w, h) -> {
            dispatched.countDown();
            return true;
        });
        // Use flush-like path via reflection-free requestResize + short sleep only for thread spawn verification.
        realDebounce.requestResize(1280, 720);
        assertTrue(realDebounce.hasPendingResize());
        assertTrue(dispatched.await(2, TimeUnit.SECONDS));
        realDebounce.close();

        // Wait briefly for the daemon executor thread to terminate.
        long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        boolean threadAlive;
        do {
            threadAlive = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> "DisplayResizeDebounce".equals(t.getName()) && t.isAlive());
            if (!threadAlive) {
                break;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadlineNs);
        assertFalse("DisplayResizeDebounce thread must terminate after close()", threadAlive);
    }
}
