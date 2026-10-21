package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.util.Arrays;
import java.util.Objects;

/** Immutable decoded frame; payload bytes are copied on the way in and out. */
public final class TerminalFrame {
    private final TerminalFrameType type;
    private final int sessionId;
    private final byte[] payload;

    public TerminalFrame(TerminalFrameType type, int sessionId, byte[] payload) {
        this.type = Objects.requireNonNull(type, "type");
        if (sessionId < 0) {
            throw new IllegalArgumentException("sessionId must not be negative");
        }
        if (type.connectionLevel() && sessionId != 0) {
            throw new IllegalArgumentException(type + " requires sessionId 0");
        }
        if (!type.connectionLevel() && type != TerminalFrameType.ERROR && sessionId == 0) {
            throw new IllegalArgumentException(type + " requires a positive sessionId");
        }
        this.sessionId = sessionId;
        this.payload = payload == null ? new byte[0] : payload.clone();
        if (this.payload.length > Fln1Payloads.MAX_FRAME_PAYLOAD) {
            throw new IllegalArgumentException("payload exceeds the frame limit");
        }
    }

    public static TerminalFrame of(
            TerminalFrameType type, int sessionId, byte[] payload) {
        return new TerminalFrame(type, sessionId, payload);
    }

    public TerminalFrameType type() {
        return type;
    }

    public int sessionId() {
        return sessionId;
    }

    public int payloadLength() {
        return payload.length;
    }

    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TerminalFrame that)) {
            return false;
        }
        return sessionId == that.sessionId
                && type == that.type
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * type.hashCode() + sessionId) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "TerminalFrame{type=" + type
                + ", sessionId=" + sessionId
                + ", payloadLength=" + payload.length
                + '}';
    }
}
