package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import java.util.Objects;

/**
 * One versioned Fast Lane stage event.
 *
 * <p>The schema is additive: {@code schema} identifies the major version, and
 * consumers must ignore unknown fields. {@link FastLaneEventCodec} implements
 * that tolerance and is tested against a future field. The event has no
 * free-text message; {@code instanceId} is an opaque identifier validated by
 * {@link Redactor#opaqueId(String)}.
 */
public final class FastLaneEvent {
    public static final int SCHEMA_VERSION = 1;

    private final FastLaneEventType type;
    private final String instanceId;
    private final long elapsedMs;
    private final FastLaneEventStatus status;
    private final FastLaneDebugLevel debugLevel;
    private final FastLaneMetrics metrics;
    private final FastLaneStopDiagnostics stop;

    FastLaneEvent(
            FastLaneEventType type,
            String instanceId,
            long elapsedMs,
            FastLaneEventStatus status,
            FastLaneDebugLevel debugLevel,
            FastLaneMetrics metrics,
            FastLaneStopDiagnostics stop) {
        this.type = Objects.requireNonNull(type, "type");
        this.instanceId = Redactor.opaqueId(instanceId);
        if (elapsedMs < 0L) {
            throw new IllegalArgumentException("elapsedMs must be non-negative");
        }
        this.elapsedMs = elapsedMs;
        this.status = Objects.requireNonNull(status, "status");
        this.debugLevel = Objects.requireNonNull(debugLevel, "debugLevel");
        if (type == FastLaneEventType.STOP_UNCONFIRMED
                && (stop == null || status != FastLaneEventStatus.FAILED)) {
            throw new IllegalArgumentException("STOP_UNCONFIRMED requires diagnostics and FAILED status");
        }
        if (type == FastLaneEventType.STOP_CONFIRMED
                && (stop == null || status != FastLaneEventStatus.OK)) {
            throw new IllegalArgumentException("STOP_CONFIRMED requires diagnostics and OK status");
        }
        if (!type.isStop() && stop != null) {
            throw new IllegalArgumentException("stop diagnostics belong only to stop events");
        }
        if (type == FastLaneEventType.STOP_REQUESTED && stop != null) {
            throw new IllegalArgumentException("STOP_REQUESTED carries no stop diagnostics");
        }
        this.metrics = metrics;
        this.stop = stop;
    }

    public FastLaneEventType type() {
        return type;
    }

    public String instanceId() {
        return instanceId;
    }

    public long elapsedMs() {
        return elapsedMs;
    }

    public FastLaneEventStatus status() {
        return status;
    }

    public FastLaneDebugLevel debugLevel() {
        return debugLevel;
    }

    /** {@code null} when metric sampling is disabled or no metric was set. */
    public FastLaneMetrics metrics() {
        return metrics;
    }

    /** Present only on STOP_CONFIRMED / STOP_UNCONFIRMED. */
    public FastLaneStopDiagnostics stop() {
        return stop;
    }

    /** Serializes one event as a single JSON line without a trailing newline. */
    public String toJsonLine() {
        StringBuilder json = new StringBuilder(256);
        json.append("{\"schema\":").append(SCHEMA_VERSION)
                .append(",\"event\":\"").append(type.name())
                .append("\",\"instanceId\":\"").append(Redactor.escapeJson(instanceId))
                .append("\",\"elapsedMs\":").append(elapsedMs)
                .append(",\"status\":\"").append(status.name())
                .append("\",\"debugLevel\":\"").append(debugLevel.name())
                .append("\",\"protectionWeakened\":").append(debugLevel.weakensProtection());
        if (metrics != null && metrics.hasSamples()) {
            json.append(",\"metrics\":").append(metrics.toJson());
        }
        if (stop != null) {
            json.append(",\"stop\":").append(stop.toJson());
        }
        json.append('}');
        return json.toString();
    }
}
