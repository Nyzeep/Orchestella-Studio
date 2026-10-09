package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

        @Override
        public DisplayResizeDebounce.Cancellable schedule(long delayMs, Runnable task) {
            ScheduledItem item = new ScheduledItem(currentTime + delayMs, task);
            queue.add(item);
            return item;
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
}
