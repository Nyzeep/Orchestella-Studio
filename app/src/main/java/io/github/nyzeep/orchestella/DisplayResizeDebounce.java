package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.DisplayMode;
import io.github.nyzeep.orchestella.runtime.contract.display.DisplayViewportBudget;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Debounces rapid display resize events (such as orientation changes and split-screen animations)
 * before negotiating with the guest display controller.
 *
 * <p>Prevents cascading renegotiation freezes and excessive guest compositor re-layouts, while
 * enforcing {@link DisplayViewportBudget} constraints and bounded scheduler lifetime.
 */
public final class DisplayResizeDebounce implements AutoCloseable {

    @FunctionalInterface
    public interface ResizeDispatcher {
        boolean dispatch(int width, int height);
    }

    @FunctionalInterface
    public interface TaskScheduler {
        Cancellable schedule(long delayMs, Runnable task);

        default void shutdown() {}
    }

    public interface Cancellable {
        void cancel();
    }

    public static final long DEFAULT_DEBOUNCE_MS = 200;

    private static final class DefaultExecutorScheduler implements TaskScheduler {
        private ScheduledExecutorService executor;
        private boolean isShutdown = false;

        @Override
        public synchronized Cancellable schedule(long delayMs, Runnable task) {
            if (isShutdown) {
                return () -> {};
            }
            if (executor == null || executor.isShutdown()) {
                executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "DisplayResizeDebounce");
                    t.setDaemon(true);
                    return t;
                });
            }
            try {
                ScheduledFuture<?> future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            } catch (RejectedExecutionException rejected) {
                return () -> {};
            }
        }

        synchronized void resetIdle() {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }

        @Override
        public synchronized void shutdown() {
            isShutdown = true;
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
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
    private boolean closed = false;

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
     * Submits a candidate display size. Valid inputs are fitted to {@link DisplayViewportBudget}
     * and, if distinct from the last accepted resolution, dispatched after the debounce window.
     */
    public synchronized void requestResize(int width, int height) {
        if (closed
                || width < DisplayViewportBudget.MIN_EDGE || width > DisplayMode.MAX_DIMENSION
                || height < DisplayViewportBudget.MIN_EDGE || height > DisplayMode.MAX_DIMENSION) {
            return;
        }

        DisplayViewportBudget.FittedViewport fitted = DisplayViewportBudget.fitWithinBudget(width, height);
        int targetWidth = fitted.width();
        int targetHeight = fitted.height();

        if (targetWidth == lastDispatchedWidth && targetHeight == lastDispatchedHeight) {
            cancel();
            return;
        }

        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }

        this.pendingWidth = targetWidth;
        this.pendingHeight = targetHeight;
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
        if (closed || !hasPending) {
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

        boolean accepted = dispatcher.dispatch(w, h);
        if (accepted) {
            lastDispatchedWidth = w;
            lastDispatchedHeight = h;
            dispatchedCount++;
        }
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

    /**
     * Clears pending work and dispatch history, and releases any idle default scheduler thread.
     */
    public synchronized void reset() {
        cancel();
        lastDispatchedWidth = -1;
        lastDispatchedHeight = -1;
        if (scheduler instanceof DefaultExecutorScheduler defaultScheduler) {
            defaultScheduler.resetIdle();
        }
    }

    /**
     * Permanently closes this debounce instance and shuts down its internal scheduler.
     */
    public synchronized void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        cancel();
        scheduler.shutdown();
    }

    @Override
    public void close() {
        shutdown();
    }

    public synchronized boolean isClosed() {
        return closed;
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
