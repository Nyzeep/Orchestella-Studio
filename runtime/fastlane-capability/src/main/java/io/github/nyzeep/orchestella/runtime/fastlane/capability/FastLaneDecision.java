package io.github.nyzeep.orchestella.runtime.fastlane.capability;

import java.util.Objects;

/** Lane + typed reason + the effective Fast Lane capability matrix. */
public final class FastLaneDecision {
    private final Lane lane;
    private final FastLaneReason reason;
    private final FastLaneCapabilities fastLaneCapabilities;
    private final String explanation;

    public FastLaneDecision(
            Lane lane,
            FastLaneReason reason,
            FastLaneCapabilities fastLaneCapabilities,
            String explanation) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.fastLaneCapabilities =
                Objects.requireNonNull(fastLaneCapabilities, "fastLaneCapabilities");
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("explanation must not be blank");
        }
        if (lane == Lane.FAST && reason != FastLaneReason.FAST_LANE_AVAILABLE) {
            throw new IllegalArgumentException("FAST requires FAST_LANE_AVAILABLE");
        }
        if (lane != Lane.FAST && reason == FastLaneReason.FAST_LANE_AVAILABLE) {
            throw new IllegalArgumentException("non-FAST decisions need a fallback reason");
        }
    }

    public static FastLaneDecision fast(
            FastLaneCapabilities capabilities, String explanation) {
        return new FastLaneDecision(
                Lane.FAST, FastLaneReason.FAST_LANE_AVAILABLE, capabilities, explanation);
    }

    public static FastLaneDecision full(
            FastLaneReason reason, FastLaneCapabilities capabilities, String explanation) {
        return new FastLaneDecision(Lane.FULL, reason, capabilities, explanation);
    }

    public static FastLaneDecision unsupported(
            FastLaneReason reason, FastLaneCapabilities capabilities, String explanation) {
        return new FastLaneDecision(Lane.UNSUPPORTED, reason, capabilities, explanation);
    }

    public Lane lane() {
        return lane;
    }

    public FastLaneReason reason() {
        return reason;
    }

    /** What Fast Lane could offer on this device; not a claim about the chosen lane. */
    public FastLaneCapabilities fastLaneCapabilities() {
        return fastLaneCapabilities;
    }

    public String explanation() {
        return explanation;
    }

    public boolean isFast() {
        return lane == Lane.FAST;
    }

    public boolean isFull() {
        return lane == Lane.FULL;
    }

    public boolean isUnsupported() {
        return lane == Lane.UNSUPPORTED;
    }
}
