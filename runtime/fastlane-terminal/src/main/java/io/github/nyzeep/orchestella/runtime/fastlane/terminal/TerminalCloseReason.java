package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

/** Why a session reached CLOSED. */
public enum TerminalCloseReason {
    LOCAL_CLOSE,
    REMOTE_CLOSE,
    EOF,
    PROTOCOL_ERROR,
    VM_STOPPED,
    TRANSPORT_CLOSED
}
