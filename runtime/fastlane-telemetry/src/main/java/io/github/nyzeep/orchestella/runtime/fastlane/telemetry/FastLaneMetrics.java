package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

/**
 * Numeric Fast Lane metrics only: counts, durations, byte totals and resource
 * gauges. There is no field for a command, path, token, keystroke or source
 * fragment, so user content cannot enter a metric record.
 *
 * <p>Sampling is opt-in ({@link FastLaneTelemetryConfig#metricsEnabled()}) and
 * happens at stage boundaries, never on a background timer. Unknown values use
 * {@code -1} and serialize as {@code null}.
 */
public final class FastLaneMetrics {
    private long startupMs = -1L;
    private long payloadReadyMs = -1L;
    private long terminalBytes;
    private long proxyBytes;
    private long distccJobs;
    private long distccFailures;
    private long distccTotalMs;
    private int cpuPercent = -1;
    private long rssBytes = -1L;

    public FastLaneMetrics() {
    }

    private FastLaneMetrics(FastLaneMetrics source) {
        this.startupMs = source.startupMs;
        this.payloadReadyMs = source.payloadReadyMs;
        this.terminalBytes = source.terminalBytes;
        this.proxyBytes = source.proxyBytes;
        this.distccJobs = source.distccJobs;
        this.distccFailures = source.distccFailures;
        this.distccTotalMs = source.distccTotalMs;
        this.cpuPercent = source.cpuPercent;
        this.rssBytes = source.rssBytes;
    }

    public synchronized void setStartupMs(long value) {
        startupMs = nonNegativeOrUnknown(value);
    }

    public synchronized void setPayloadReadyMs(long value) {
        payloadReadyMs = nonNegativeOrUnknown(value);
    }

    public synchronized void addTerminalBytes(long delta) {
        terminalBytes = addNonNegative(terminalBytes, delta);
    }

    public synchronized void addProxyBytes(long delta) {
        proxyBytes = addNonNegative(proxyBytes, delta);
    }

    public synchronized void recordDistccJob(long durationMs, boolean success) {
        distccJobs = addNonNegative(distccJobs, 1L);
        distccTotalMs = addNonNegative(distccTotalMs, durationMs);
        if (!success) {
            distccFailures = addNonNegative(distccFailures, 1L);
        }
    }

    /** Samples a resource gauge; {@code cpuPercent} is {@code -1} when unknown. */
    public synchronized void sampleResources(int cpuPercent, long rssBytes) {
        if (cpuPercent < -1 || cpuPercent > 100) {
            throw new IllegalArgumentException("cpuPercent must be -1 or 0..100");
        }
        if (rssBytes < -1L) {
            throw new IllegalArgumentException("rssBytes must be -1 or non-negative");
        }
        this.cpuPercent = cpuPercent;
        this.rssBytes = rssBytes;
    }

    /** Restores aggregate distcc counters when decoding a previously emitted event. */
    synchronized void restoreDistcc(long jobs, long failures, long totalMs) {
        if (jobs < 0L || failures < 0L || failures > jobs || totalMs < 0L) {
            throw new IllegalArgumentException("invalid distcc counters");
        }
        this.distccJobs = jobs;
        this.distccFailures = failures;
        this.distccTotalMs = totalMs;
    }

    public synchronized FastLaneMetrics snapshot() {
        return new FastLaneMetrics(this);
    }

    /** True when at least one metric was supplied. */
    public synchronized boolean hasSamples() {
        return startupMs >= 0L || payloadReadyMs >= 0L
                || terminalBytes != 0L || proxyBytes != 0L
                || distccJobs != 0L || distccTotalMs != 0L
                || cpuPercent >= 0 || rssBytes >= 0L;
    }

    public synchronized long startupMs() {
        return startupMs;
    }

    public synchronized long payloadReadyMs() {
        return payloadReadyMs;
    }

    public synchronized long terminalBytes() {
        return terminalBytes;
    }

    public synchronized long proxyBytes() {
        return proxyBytes;
    }

    public synchronized long distccJobs() {
        return distccJobs;
    }

    public synchronized long distccFailures() {
        return distccFailures;
    }

    public synchronized long distccTotalMs() {
        return distccTotalMs;
    }

    public synchronized int cpuPercent() {
        return cpuPercent;
    }

    public synchronized long rssBytes() {
        return rssBytes;
    }

    String toJson() {
        return "{\"startupMs\":" + nullable(startupMs)
                + ",\"payloadReadyMs\":" + nullable(payloadReadyMs)
                + ",\"terminalBytes\":" + terminalBytes
                + ",\"proxyBytes\":" + proxyBytes
                + ",\"distccJobs\":" + distccJobs
                + ",\"distccFailures\":" + distccFailures
                + ",\"distccTotalMs\":" + distccTotalMs
                + ",\"cpuPercent\":" + nullable(cpuPercent)
                + ",\"rssBytes\":" + nullable(rssBytes)
                + "}";
    }

    private static String nullable(long value) {
        return value < 0L ? "null" : Long.toString(value);
    }

    private static long nonNegativeOrUnknown(long value) {
        if (value < -1L) {
            throw new IllegalArgumentException("value must be -1 or non-negative");
        }
        return value;
    }

    private static long addNonNegative(long current, long delta) {
        if (delta < 0L) {
            throw new IllegalArgumentException("delta must be non-negative");
        }
        if (Long.MAX_VALUE - current < delta) {
            return Long.MAX_VALUE;
        }
        return current + delta;
    }
}
