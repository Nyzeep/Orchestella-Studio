package io.github.nyzeep.orchestella.runtime.fastlane;

/**
 * Canonical protected-lane reason codes.
 *
 * <p>Every layer (preflight, full report, host runner, device matrix) writes
 * one of these names. Legacy spellings are accepted only at the input edge and
 * are normalized by {@link ProtectedFailure#fromLegacy(String)}.
 */
public enum ProtectedFailureCode {
    TOOLBOX_EXEC_BLOCKED_BY_PLATFORM(ProtectedFailureClass.PLATFORM_BLOCKED),
    TERMINAL_ERROR(ProtectedFailureClass.LIFECYCLE),
    PERMISSION_DENIED(ProtectedFailureClass.PERMISSION),
    HIDDEN_API_BLOCKED(ProtectedFailureClass.PERMISSION),
    SCHEMA_INVALID(ProtectedFailureClass.SCHEMA),
    CONFIG_INVALID(ProtectedFailureClass.SCHEMA),
    VM_CREATE_FAILED(ProtectedFailureClass.LIFECYCLE),
    READY_TIMEOUT(ProtectedFailureClass.LIFECYCLE),
    HELLO_FAILED(ProtectedFailureClass.LIFECYCLE),
    VSOCK_FAILED(ProtectedFailureClass.LIFECYCLE),
    PROCESS_DIED(ProtectedFailureClass.LIFECYCLE),
    STOP_UNCONFIRMED(ProtectedFailureClass.STOP),
    DRAIN_TIMEOUT(ProtectedFailureClass.STOP),
    PAYLOAD_VERIFY_FAILED(ProtectedFailureClass.INSTALL),
    PROTECTED_UNAVAILABLE(ProtectedFailureClass.INSTALL),
    FASTLANE_UNKNOWN(ProtectedFailureClass.UNKNOWN);

    private final ProtectedFailureClass failureClass;

    ProtectedFailureCode(ProtectedFailureClass failureClass) {
        this.failureClass = failureClass;
    }

    public ProtectedFailureClass failureClass() {
        return failureClass;
    }
}
