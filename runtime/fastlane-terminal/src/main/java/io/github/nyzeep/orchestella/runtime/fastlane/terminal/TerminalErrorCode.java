package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

/** Stable wire error codes shared with the payload implementation. */
public enum TerminalErrorCode {
    PROTOCOL_MAGIC(1),
    PROTOCOL_VERSION(2),
    FRAME_TOO_LARGE(3),
    UNKNOWN_TYPE(4),
    UNKNOWN_SESSION(5),
    SESSION_EXISTS(6),
    SESSION_LIMIT(7),
    SESSION_RETIRED(8),
    SESSION_CLOSED(9),
    CREDIT_EXCEEDED(10),
    CREDIT_OVERFLOW(11),
    INVALID_PAYLOAD(12),
    WRONG_ROLE(13),
    VM_STOPPED(14),
    TRUNCATED_FRAME(15),
    PROTOCOL_LENGTH(16),
    INTERNAL(17);

    private final int code;

    TerminalErrorCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TerminalErrorCode fromCode(int code) {
        for (TerminalErrorCode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return INTERNAL;
    }
}
