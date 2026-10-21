package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.IOException;

/** Typed terminal-multiplexer failure; messages never contain terminal content. */
public final class TerminalProtocolException extends IOException {
    private static final long serialVersionUID = 1L;

    private final TerminalErrorCode code;

    public TerminalProtocolException(TerminalErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public TerminalProtocolException(TerminalErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public TerminalErrorCode code() {
        return code;
    }
}
