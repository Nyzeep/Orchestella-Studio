package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

/** Per-session lifecycle; both halves must close before CLOSED. */
public enum TerminalSessionState {
    OPENING,
    OPEN,
    HALF_CLOSED_LOCAL,
    HALF_CLOSED_REMOTE,
    CLOSED;

    public boolean canSendData() {
        return this == OPEN || this == HALF_CLOSED_REMOTE;
    }

    public boolean canReceiveData() {
        return this == OPEN || this == HALF_CLOSED_LOCAL;
    }

    public boolean isClosed() {
        return this == CLOSED;
    }
}
