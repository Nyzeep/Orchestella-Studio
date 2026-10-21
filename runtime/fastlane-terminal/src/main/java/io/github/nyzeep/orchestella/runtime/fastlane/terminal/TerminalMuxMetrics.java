package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

/** Aggregate counters; contains no terminal content. */
public record TerminalMuxMetrics(
        long sessionsOpened,
        long sessionsClosed,
        long framesIn,
        long framesOut,
        long bytesIn,
        long bytesOut,
        long creditStalls,
        long protocolErrors) {

    public TerminalMuxMetrics {
        if (sessionsOpened < 0
                || sessionsClosed < 0
                || framesIn < 0
                || framesOut < 0
                || bytesIn < 0
                || bytesOut < 0
                || creditStalls < 0
                || protocolErrors < 0) {
            throw new IllegalArgumentException("metrics must not be negative");
        }
    }

    public static TerminalMuxMetrics empty() {
        return new TerminalMuxMetrics(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
}
