package io.github.nyzeep.orchestella.runtime.instance.duallane;

/**
 * One secret-free lane event: fixed name, lane, fixed code and duration only.
 */
public record LaneEvent(String name, LaneId lane, String code, long durationMs) {
    public static final String LANE_STARTED = "lane_started";
    public static final String LANE_READY = "lane_ready";
    public static final String LANE_STOPPED = "lane_stopped";
    public static final String LANE_LOST = "lane_lost";
    public static final String BUDGET_REJECTED = "budget_rejected";

    public LaneEvent {
        if (name == null || lane == null || code == null) {
            throw new IllegalArgumentException("event fields");
        }
        if (durationMs < 0L) {
            throw new IllegalArgumentException("durationMs");
        }
    }
}
