package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import java.util.Objects;

/**
 * Telemetry policy.
 *
 * <p>{@link #PRODUCTION} records the ten low-frequency stage events with debug
 * level {@code NONE} and keeps metric sampling off. {@link #QA_FULL} enables
 * {@code FULL} debug and resource metrics; every record is marked
 * {@code protectionWeakened:true}. {@link #DISABLED} performs no collection at
 * all (no clock reads, no queue, no sink call).
 */
public final class FastLaneTelemetryConfig {
    public static final FastLaneTelemetryConfig PRODUCTION =
            new FastLaneTelemetryConfig(true, FastLaneDebugLevel.NONE, false);
    public static final FastLaneTelemetryConfig DISABLED =
            new FastLaneTelemetryConfig(false, FastLaneDebugLevel.NONE, false);
    public static final FastLaneTelemetryConfig QA_FULL =
            new FastLaneTelemetryConfig(true, FastLaneDebugLevel.FULL, true, true);

    private final boolean enabled;
    private final FastLaneDebugLevel debugLevel;
    private final boolean metricsEnabled;

    /**
     * Production constructible policy. {@code FULL} is rejected here; only the
     * explicitly named {@link #QA_FULL} policy can enable it.
     */
    public FastLaneTelemetryConfig(
            boolean enabled, FastLaneDebugLevel debugLevel, boolean metricsEnabled) {
        this(enabled, debugLevel, metricsEnabled, false);
    }

    private FastLaneTelemetryConfig(
            boolean enabled,
            FastLaneDebugLevel debugLevel,
            boolean metricsEnabled,
            boolean qaFull) {
        this.enabled = enabled;
        this.debugLevel = Objects.requireNonNull(debugLevel, "debugLevel");
        this.metricsEnabled = metricsEnabled;
        if (debugLevel == FastLaneDebugLevel.FULL && !qaFull) {
            throw new IllegalArgumentException("FULL debug is QA-only; use QA_FULL");
        }
        if (!enabled && debugLevel == FastLaneDebugLevel.FULL) {
            throw new IllegalArgumentException("disabled telemetry cannot use FULL debug");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public FastLaneDebugLevel debugLevel() {
        return debugLevel;
    }

    public boolean metricsEnabled() {
        return metricsEnabled;
    }
}
