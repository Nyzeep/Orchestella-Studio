package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

/** Wire opcode for one multiplexed terminal frame. */
public enum TerminalFrameType {
    HELLO(0x01, true),
    OPEN_PTY(0x02, false),
    DATA(0x03, false),
    RESIZE(0x04, false),
    SIGNAL(0x05, false),
    CLOSE(0x06, false),
    EOF(0x07, false),
    PING(0x08, true),
    PONG(0x09, true),
    METRICS(0x0A, true),
    ERROR(0x0B, false),
    CREDIT(0x0C, false);

    private final int wire;
    private final boolean connectionLevel;

    TerminalFrameType(int wire, boolean connectionLevel) {
        this.wire = wire;
        this.connectionLevel = connectionLevel;
    }

    public int wire() {
        return wire;
    }

    /**
     * Connection-level frames use sessionId 0. ERROR may be either
     * connection-level or session-level; it is the only exception.
     */
    public boolean connectionLevel() {
        return connectionLevel;
    }

    public static TerminalFrameType fromWire(int wire) throws TerminalProtocolException {
        for (TerminalFrameType type : values()) {
            if (type.wire == wire) {
                return type;
            }
        }
        throw new TerminalProtocolException(
                TerminalErrorCode.UNKNOWN_TYPE, "unknown frame type: " + wire);
    }
}
