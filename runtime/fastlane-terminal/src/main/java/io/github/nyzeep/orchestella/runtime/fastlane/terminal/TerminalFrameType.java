package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

/**
 * Logical frame kind inside the multiplexer. Wire opcodes live in
 * {@link Fln1TerminalCodec}; this enum deliberately carries no protocol byte so
 * a second wire format cannot grow back through it.
 */
public enum TerminalFrameType {
    HELLO(true),
    OPEN_PTY(false),
    DATA(false),
    RESIZE(false),
    RESIZED(false),
    SIGNAL(false),
    CLOSE(false),
    CLOSED(false),
    EOF(false),
    EXIT(false),
    PING(true),
    PONG(true),
    WINDOW(false),
    ERROR(false);

    private final boolean connectionLevel;

    TerminalFrameType(boolean connectionLevel) {
        this.connectionLevel = connectionLevel;
    }

    /**
     * Connection-level frames use session id 0. ERROR may be either
     * connection-level or session-level; it is the only exception.
     */
    public boolean connectionLevel() {
        return connectionLevel;
    }
}
