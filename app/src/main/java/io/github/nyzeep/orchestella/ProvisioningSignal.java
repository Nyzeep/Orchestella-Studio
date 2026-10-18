package io.github.nyzeep.orchestella;

/**
 * Streaming detector for the guest Provisioning completion marker.
 *
 * <p>The guest initramfs writes {@code provisioning_version=1} and then logs exactly:
 *
 * <pre>DROIDLAYER_PROVISIONING_COMPLETE version=1 distro=... phase=rootfs runtime=pending</pre>
 *
 * <p>This detector consumes decoded terminal output chunk by chunk and keeps only a bounded
 * matcher state, never the transcript. A marker is accepted only when it starts a line and the
 * exact, case-sensitive prefix {@value #MARKER_PREFIX} is followed by whitespace or the end of the
 * stream. Chunks may split the marker at any boundary. Each instance of this class reports at most
 * one detection.
 */
public final class ProvisioningSignal {
    /** Marker contract version understood by the host. */
    public static final int MARKER_VERSION = 1;

    /** Exact case-sensitive prefix, including the supported version. */
    public static final String MARKER_PREFIX =
            "DROIDLAYER_PROVISIONING_COMPLETE version=" + MARKER_VERSION;

    private enum State {
        SEEKING,
        MATCHING_PREFIX,
        AWAITING_DELIMITER,
        DETECTED
    }

    private State state = State.SEEKING;
    private int matched;
    private boolean atLineStart = true;

    /**
     * Consumes one decoded chunk.
     *
     * @return {@code true} exactly when this chunk completes the first accepted marker
     */
    public boolean accept(String chunk) {
        if (chunk == null || state == State.DETECTED) {
            return false;
        }
        for (int index = 0; index < chunk.length(); index++) {
            char character = chunk.charAt(index);
            switch (state) {
                case SEEKING:
                    if (atLineStart && character == MARKER_PREFIX.charAt(0)) {
                        state = State.MATCHING_PREFIX;
                        matched = 1;
                    }
                    break;
                case MATCHING_PREFIX:
                    if (character == MARKER_PREFIX.charAt(matched)) {
                        matched++;
                        if (matched == MARKER_PREFIX.length()) {
                            state = State.AWAITING_DELIMITER;
                        }
                    } else {
                        // A candidate that began mid-line can never be rescued by a later
                        // character: the contract requires the marker to start a line.
                        state = State.SEEKING;
                        matched = 0;
                    }
                    break;
                case AWAITING_DELIMITER:
                    if (Character.isWhitespace(character)) {
                        state = State.DETECTED;
                        return true;
                    }
                    state = State.SEEKING;
                    matched = 0;
                    break;
                case DETECTED:
                    return false;
                default:
                    throw new IllegalStateException("unhandled detector state");
            }
            atLineStart = character == '\n' || character == '\r';
        }
        return false;
    }

    /**
     * Signals end of stream. A marker whose delimiter was the end of the stream is accepted here.
     *
     * @return {@code true} exactly when the pending marker is completed by end of input
     */
    public boolean finish() {
        if (state == State.AWAITING_DELIMITER) {
            state = State.DETECTED;
            return true;
        }
        return false;
    }

    /** Whether a marker has already been accepted. */
    public boolean detected() {
        return state == State.DETECTED;
    }
}
