package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, synchronous Fast Lane event recorder.
 *
 * <p>There is no background thread and no unbounded buffer: {@link #mark} appends
 * to a fixed-capacity deque, and {@link #flush} writes the queued JSON lines.
 * When the deque is full the oldest non-stop event is evicted, so STOP_* evidence
 * survives a burst. A dropped event increments {@link #droppedEvents()} and is
 * never silently counted as recorded.
 *
 * <p>Elapsed times are launch-relative and non-decreasing. A backwards clock is
 * clamped and counted in {@link #clockRegressions()} rather than producing a
 * time-reversed record. {@link #notObserved()} lists the stages that never
 * happened; it does not invent them.
 *
 * <p>{@link FastLaneTelemetryConfig#DISABLED} performs no clock reads, queue
 * allocation or sink calls. The production configuration records stage events
 * only; resource metrics are opt-in and sampled at stage boundaries.
 */
public final class FastLaneTelemetry {
    public static final int DEFAULT_CAPACITY = 128;
    public static final int MAX_CAPACITY = 1024;

    /**
     * Testable one-line destination. Implementations must append the line
     * atomically or throw before appending; throwing after a partial append can
     * duplicate that event on the next flush.
     */
    @FunctionalInterface
    public interface LineSink {
        void writeLine(String line) throws IOException;
    }

    private final String instanceId;
    private final Clock clock;
    private final FastLaneTelemetryConfig config;
    private final int capacity;
    private final ArrayDeque<FastLaneEvent> queue = new ArrayDeque<>();
    private final EnumSet<FastLaneEventType> observed = EnumSet.noneOf(FastLaneEventType.class);
    private final FastLaneMetrics metrics;
    /** Serializes flushes without holding the recorder monitor during sink I/O. */
    private final Object flushLock = new Object();

    private Instant startedAt;
    private long lastElapsedMs;
    private long droppedEvents;
    private long clockRegressions;
    private String lastSinkError;

    public FastLaneTelemetry(
            String instanceId,
            Clock clock,
            FastLaneTelemetryConfig config,
            int capacity) {
        this.instanceId = Redactor.opaqueId(instanceId);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.config = Objects.requireNonNull(config, "config");
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("capacity must be 1.." + MAX_CAPACITY);
        }
        this.capacity = capacity;
        this.metrics = config.enabled() && config.metricsEnabled() ? new FastLaneMetrics() : null;
    }

    public static FastLaneTelemetry production(String instanceId) {
        return new FastLaneTelemetry(
                instanceId, Clock.systemUTC(), FastLaneTelemetryConfig.PRODUCTION, DEFAULT_CAPACITY);
    }

    public static FastLaneTelemetry qaFull(String instanceId) {
        return new FastLaneTelemetry(
                instanceId, Clock.systemUTC(), FastLaneTelemetryConfig.QA_FULL, DEFAULT_CAPACITY);
    }

    public static FastLaneTelemetry disabled(String instanceId) {
        return new FastLaneTelemetry(
                instanceId, Clock.systemUTC(), FastLaneTelemetryConfig.DISABLED, DEFAULT_CAPACITY);
    }

    public FastLaneTelemetryConfig config() {
        return config;
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    /** Starts the launch-relative clock. Repeated calls keep the first start. */
    public synchronized void start() {
        if (!config.enabled() || startedAt != null) {
            return;
        }
        startedAt = clock.instant();
        lastElapsedMs = 0L;
    }

    /** Returns the mutable metric collector, or {@code null} when sampling is off. */
    public FastLaneMetrics metrics() {
        return metrics;
    }

    public synchronized boolean mark(FastLaneEventType type) {
        return mark(type, FastLaneEventStatus.OK);
    }

    public synchronized boolean mark(FastLaneEventType type, FastLaneEventStatus status) {
        return record(type, status, null);
    }

    public synchronized boolean markStopConfirmed(FastLaneStopDiagnostics diagnostics) {
        return record(FastLaneEventType.STOP_CONFIRMED, FastLaneEventStatus.OK,
                Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public synchronized boolean markStopUnconfirmed(FastLaneStopDiagnostics diagnostics) {
        return record(FastLaneEventType.STOP_UNCONFIRMED, FastLaneEventStatus.FAILED,
                Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    private boolean record(
            FastLaneEventType type,
            FastLaneEventStatus status,
            FastLaneStopDiagnostics stop) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        if (!config.enabled()) {
            return false;
        }
        FastLaneEvent event = new FastLaneEvent(
                type,
                instanceId,
                nextElapsedMs(),
                status,
                config.debugLevel(),
                metrics == null ? null : metrics.snapshot(),
                stop);
        enqueue(event);
        observed.add(type);
        return true;
    }

    private long nextElapsedMs() {
        Instant now = clock.instant();
        if (startedAt == null) {
            startedAt = now;
            lastElapsedMs = 0L;
            return 0L;
        }
        long raw = Duration.between(startedAt, now).toMillis();
        if (raw < 0L || raw < lastElapsedMs) {
            clockRegressions++;
        }
        long elapsed = Math.max(lastElapsedMs, Math.max(0L, raw));
        lastElapsedMs = elapsed;
        return elapsed;
    }

    private void enqueue(FastLaneEvent event) {
        if (queue.size() >= capacity) {
            evictOne();
        }
        queue.addLast(event);
    }

    /**
     * Evicts one event, preferring non-stop events, then STOP_REQUESTED, then
     * STOP_CONFIRMED. STOP_UNCONFIRMED is the last to go, so the diagnostic that
     * explains an unconfirmed stop survives a burst.
     */
    private void evictOne() {
        FastLaneEvent victim = null;
        for (FastLaneEvent candidate : queue) {
            if (!candidate.type().isStop()) {
                victim = candidate;
                break;
            }
        }
        if (victim == null) {
            for (FastLaneEvent candidate : queue) {
                if (candidate.type() == FastLaneEventType.STOP_REQUESTED) {
                    victim = candidate;
                    break;
                }
            }
        }
        if (victim == null) {
            for (FastLaneEvent candidate : queue) {
                if (candidate.type() == FastLaneEventType.STOP_CONFIRMED) {
                    victim = candidate;
                    break;
                }
            }
        }
        if (victim == null) {
            victim = queue.peekFirst();
        }
        if (victim != null) {
            queue.remove(victim);
            droppedEvents++;
        }
    }

    /**
     * Writes every queued event as one JSON line and clears the queue. Events that
     * were not written because the sink failed stay queued in their original order.
     * The recorder monitor is released while the sink runs, so a slow sink does not
     * block {@link #mark}; new events recorded during the flush stay queued for the
     * next flush. Returns the number of successfully written lines; never throws on
     * sink failure.
     */
    public int flush(LineSink sink) {
        Objects.requireNonNull(sink, "sink");
        synchronized (flushLock) {
            List<FastLaneEvent> batch;
            synchronized (this) {
                if (!config.enabled() || queue.isEmpty()) {
                    return 0;
                }
                batch = new ArrayList<>(queue);
                queue.clear();
            }
            int written = 0;
            try {
                for (; written < batch.size(); written++) {
                    sink.writeLine(batch.get(written).toJsonLine());
                }
                return written;
            } catch (IOException | RuntimeException failure) {
                synchronized (this) {
                    lastSinkError = failure.getClass().getSimpleName();
                    for (int i = batch.size() - 1; i >= written; i--) {
                        queue.addFirst(batch.get(i));
                    }
                    while (queue.size() > capacity) {
                        evictOne();
                    }
                }
                return written;
            }
        }
    }

    public synchronized int queuedEvents() {
        return queue.size();
    }

    public synchronized long droppedEvents() {
        return droppedEvents;
    }

    public synchronized long clockRegressions() {
        return clockRegressions;
    }

    /** Class name of the last sink failure, or {@code null}; never a message. */
    public synchronized String lastSinkError() {
        return lastSinkError;
    }

    /** Stages never observed, in enum order. Missing is reported, never invented. */
    public synchronized List<FastLaneEventType> notObserved() {
        if (!config.enabled()) {
            return Collections.emptyList();
        }
        List<FastLaneEventType> missing = new ArrayList<>();
        for (FastLaneEventType type : FastLaneEventType.values()) {
            if (!observed.contains(type)) {
                missing.add(type);
            }
        }
        return Collections.unmodifiableList(missing);
    }

    /** Compact report that pairs observed/missing stages with the drop count. */
    public synchronized String summaryJson() {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"schema\":").append(FastLaneEvent.SCHEMA_VERSION)
                .append(",\"instanceId\":\"").append(Redactor.escapeJson(instanceId))
                .append("\",\"enabled\":").append(config.enabled())
                .append(",\"observed\":[");
        boolean first = true;
        for (FastLaneEventType type : FastLaneEventType.values()) {
            if (!observed.contains(type)) continue;
            if (!first) json.append(',');
            json.append('"').append(type.name()).append('"');
            first = false;
        }
        json.append("],\"notObserved\":[");
        first = true;
        for (FastLaneEventType type : notObserved()) {
            if (!first) json.append(',');
            json.append('"').append(type.name()).append('"');
            first = false;
        }
        json.append("],\"dropped\":").append(droppedEvents)
                .append(",\"clockRegressions\":").append(clockRegressions)
                .append('}');
        return json.toString();
    }
}
