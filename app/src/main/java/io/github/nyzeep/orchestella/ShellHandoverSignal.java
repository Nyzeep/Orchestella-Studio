package io.github.nyzeep.orchestella;

import java.util.List;

/**
 * Streaming detector for the guest shell handover on the terminal console.
 *
 * <p>W1-04 (R-08): the guest initramfs hands the console to the shell right
 * after one of two honest markers, both already shipped by
 * {@code guest-assets/initramfs/runtime-entry.sh} and both printed on every
 * boot path:
 *
 * <ul>
 *   <li>first provisioning: {@code DROIDLAYER_PROVISIONING_COMPLETE version=1 …}</li>
 *   <li>every restart of a provisioned root volume:
 *       {@code DROIDLAYER_ROOT_VOLUME_RESTART version=1 …}</li>
 * </ul>
 *
 * <p>Observing either marker is the closest host-side proxy for "the first
 * prompt/command echo is about to be ready" that stays truthful without new
 * guest-side contracts: the detection closes the launch timeline's
 * T0 → SHELL_READY chain. Like {@link ProvisioningSignal}, this detector
 * consumes decoded terminal output chunk by chunk, keeps only bounded matcher
 * state (never the transcript), requires the marker to start a line and be
 * followed by whitespace or end of stream, tolerates chunks that split a
 * marker at any boundary, and reports at most one detection.
 */
public final class ShellHandoverSignal {

    /** Line-start markers whose observation proves the guest shell handover. */
    private static final List<String> MARKER_PREFIXES = List.of(
            ProvisioningSignal.MARKER_PREFIX,
            "DROIDLAYER_ROOT_VOLUME_RESTART version=1");

    private enum State {
        SEEKING,
        MATCHING_PREFIX,
        AWAITING_DELIMITER,
        DETECTED
    }

    /** Independent matcher per marker; a chunk may carry partial matches of both. */
    private final Matcher[] matchers;

    private boolean detected;

    private static final class Matcher {
        final String prefix;
        State state = State.SEEKING;
        int matched;
        boolean atLineStart = true;

        Matcher(String prefix) {
            this.prefix = prefix;
        }
    }

    ShellHandoverSignal() {
        this.matchers = new Matcher[MARKER_PREFIXES.size()];
        for (int index = 0; index < this.matchers.length; index++) {
            this.matchers[index] = new Matcher(MARKER_PREFIXES.get(index));
        }
    }

    /**
     * Consumes one decoded chunk.
     *
     * @return {@code true} exactly when this chunk completes the first accepted marker
     */
    public boolean accept(String chunk) {
        if (chunk == null || detected) {
            return false;
        }
        for (int index = 0; index < chunk.length(); index++) {
            char character = chunk.charAt(index);
            for (Matcher matcher : matchers) {
                if (matcher.state == State.DETECTED) {
                    continue;
                }
                switch (matcher.state) {
                    case SEEKING:
                        if (matcher.atLineStart && character == matcher.prefix.charAt(0)) {
                            matcher.state = State.MATCHING_PREFIX;
                            matcher.matched = 1;
                        }
                        break;
                    case MATCHING_PREFIX:
                        if (character == matcher.prefix.charAt(matcher.matched)) {
                            matcher.matched++;
                            if (matcher.matched == matcher.prefix.length()) {
                                matcher.state = State.AWAITING_DELIMITER;
                            }
                        } else {
                            // A candidate that began mid-line can never be rescued by a later
                            // character: the contract requires the marker to start a line.
                            matcher.state = State.SEEKING;
                            matcher.matched = 0;
                        }
                        break;
                    case AWAITING_DELIMITER:
                        if (Character.isWhitespace(character)) {
                            matcher.state = State.DETECTED;
                            detected = true;
                            return true;
                        }
                        matcher.state = State.SEEKING;
                        matcher.matched = 0;
                        break;
                    case DETECTED:
                    default:
                        break;
                }
            }
            boolean lineStart = character == '\n' || character == '\r';
            for (Matcher matcher : matchers) {
                matcher.atLineStart = lineStart;
            }
            if (detected) {
                return true;
            }
        }
        return false;
    }

    /**
     * Signals end of stream. A marker whose delimiter was the end of the stream is accepted here.
     *
     * @return {@code true} exactly when a pending marker is completed by end of input
     */
    public boolean finish() {
        if (detected) {
            return false;
        }
        for (Matcher matcher : matchers) {
            if (matcher.state == State.AWAITING_DELIMITER) {
                matcher.state = State.DETECTED;
                detected = true;
                return true;
            }
        }
        return false;
    }

    /** Whether a marker has already been accepted. */
    public boolean detected() {
        return detected;
    }
}
