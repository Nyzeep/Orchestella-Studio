package io.github.nyzeep.droidlayer;

import io.github.nyzeep.droidlayer.runtime.contract.DisplayMode;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Debounces rapid display resize events (such as orientation changes and split-screen animations)
 * before negotiating with the guest display controller.
 *
 * <p>Prevents cascading renegotiation freezes and excessive guest compositor re-layouts.
 */
public final class DisplayResizeDebounce {

    @FunctionalInterface
    public interface ResizeDispatcher {
        boolean dispatch(int width, int height);
    }

    @FunctionalInterface
    public interface TaskScheduler {
        Cancellable schedule(long delayMs, Runnable task);
    }

    public interface Cancellable {
        void cancel();
    }

    public static final long DEFAULT_DEBOUNCE_MS = 200;

    private static final class DefaultExecutorScheduler implements TaskScheduler {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DisplayResizeDebounce");
            t.setDaemon(true);
            return t;
        });

        @Override
        public Cancellable schedule(long delayMs, Runnable task) {
            ScheduledFuture<?> future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
    }

    private final ResizeDispatcher dispatcher;
    private final long debounceMs;
    private final TaskScheduler scheduler;

    private int pendingWidth = -1;
    private int pendingHeight = -1;
    private boolean hasPending = false;
    private Cancellable pendingTask = null;

    private int lastDispatchedWidth = -1;
    private int lastDispatchedHeight = -1;
    private int dispatchedCount = 0;

    public DisplayResizeDebounce(ResizeDispatcher dispatcher) {
        this(dispatcher, DEFAULT_DEBOUNCE_MS, new DefaultExecutorScheduler());
    }

    public DisplayResizeDebounce(ResizeDispatcher dispatcher, long debounceMs, TaskScheduler scheduler) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        if (debounceMs < 0) {
            throw new IllegalArgumentException("debounceMs cannot be negative: " + debounceMs);
        }
        this.debounceMs = debounceMs;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Submits a candidate display size. If dimensions are valid and distinct from the last
     * dispatched resolution, dispatches after the debounce window.
     */
    public synchronized void requestResize(int width, int height) {
        if (width < DisplayMode.MIN_DIMENSION || width > DisplayMode.MAX_DIMENSION
                || height < DisplayMode.MIN_DIMENSION || height > DisplayMode.MAX_DIMENSION) {
            return;
        }

        if (width == lastDispatchedWidth && height == lastDispatchedHeight) {
            cancel();
            return;
        }

        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }

        this.pendingWidth = width;
        this.pendingHeight = height;
        this.hasPending = true;

        if (debounceMs == 0) {
            flush();
        } else {
            this.pendingTask = scheduler.schedule(debounceMs, this::flush);
        }
    }

    /**
     * Immediately executes any pending resize without waiting for debounce expiration.
     */
    public synchronized void flush() {
        if (!hasPending) {
            return;
        }
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
        int w = pendingWidth;
        int h = pendingHeight;
        hasPending = false;
        pendingWidth = -1;
        pendingHeight = -1;

        lastDispatchedWidth = w;
        lastDispatchedHeight = h;
        dispatchedCount++;
        dispatcher.dispatch(w, h);
    }

    /**
     * Cancels any pending scheduled resize request.
     */
    public synchronized void cancel() {
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
        hasPending = false;
        pendingWidth = -1;
        pendingHeight = -1;
    }

    public synchronized boolean hasPendingResize() {
        return hasPending;
    }

    public synchronized int getPendingWidth() {
        return pendingWidth;
    }

    public synchronized int getPendingHeight() {
        return pendingHeight;
    }

    public synchronized int getLastDispatchedWidth() {
        return lastDispatchedWidth;
    }

    public synchronized int getLastDispatchedHeight() {
        return lastDispatchedHeight;
    }

    public synchronized int getDispatchedCount() {
        return dispatchedCount;
    }
}
