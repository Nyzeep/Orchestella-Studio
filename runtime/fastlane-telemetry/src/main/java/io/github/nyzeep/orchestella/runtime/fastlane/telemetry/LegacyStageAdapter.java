package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only adapter to the existing ticket-27 production stage recorder
 * ({@code LaunchTimeline}, schema 2). This module does not depend on the app
 * module and does not modify ticket 27; the host supplies a sink that forwards
 * to {@code LaunchTimeline.mark(Stage)}.
 *
 * <p>Only stages with an exact semantic equivalent are mapped. Payload,
 * terminal, proxy, export and distcc readiness have no ticket-27 stage, so they
 * are reported as {@code not_observed} by the legacy recorder instead of being
 * forced into an unrelated stage.
 */
public final class LegacyStageAdapter {
    /** Host-side bridge to {@code LaunchTimeline.mark(Stage)}. */
    @FunctionalInterface
    public interface LegacyStageSink {
        void mark(String stageName, long elapsedMs);
    }

    private static final Map<FastLaneEventType, String> MAPPING = Map.of(
            FastLaneEventType.FASTLANE_REQUESTED, "LAUNCH_START",
            FastLaneEventType.VM_CREATED, "VM_CREATED",
            FastLaneEventType.STOP_REQUESTED, "STOP_REQUESTED",
            FastLaneEventType.STOP_CONFIRMED, "STOP_CONFIRMED",
            FastLaneEventType.STOP_UNCONFIRMED, "STOP_UNCONFIRMED");

    private LegacyStageAdapter() {
    }

    /** The ticket-27 stage name for an event, or empty when there is no equivalent. */
    public static Optional<String> legacyStage(FastLaneEventType type) {
        return Optional.ofNullable(MAPPING.get(Objects.requireNonNull(type, "type")));
    }

    /** Forwards one event when a legacy stage exists; returns whether it was forwarded. */
    public static boolean forward(FastLaneEvent event, LegacyStageSink sink) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(sink, "sink");
        String stage = MAPPING.get(event.type());
        if (stage == null) {
            return false;
        }
        sink.mark(stage, event.elapsedMs());
        return true;
    }

    /** Ticket-27 stage names this adapter can emit, for schema cross-checks. */
    public static List<String> mappedStages() {
        return List.copyOf(MAPPING.values());
    }
}
