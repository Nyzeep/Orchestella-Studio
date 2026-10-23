package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

/** Handshake, flow-control and frame limits for one AXP1 connection. */
public record Axp1Limits(long handshakeTimeoutMs, int windowBytes, int maxFrame) {
    public static final long DEFAULT_HANDSHAKE_TIMEOUT_MS = 3_000L;
    public static final int DEFAULT_WINDOW_BYTES = 4 * 1_048_576;
    public static final int DEFAULT_MAX_FRAME = 1_048_576;

    public Axp1Limits {
        if (handshakeTimeoutMs <= 0L) {
            throw new IllegalArgumentException("handshakeTimeoutMs must be positive");
        }
        if (windowBytes < 1) {
            throw new IllegalArgumentException("windowBytes must be positive");
        }
        if (maxFrame < 1 || maxFrame > Axp1Codec.ABSOLUTE_MAX_FRAME) {
            throw new IllegalArgumentException("maxFrame outside the AXP1 range");
        }
    }

    public static Axp1Limits defaults() {
        return new Axp1Limits(DEFAULT_HANDSHAKE_TIMEOUT_MS, DEFAULT_WINDOW_BYTES, DEFAULT_MAX_FRAME);
    }
}
