package io.github.nyzeep.orchestella.runtime.engineavf;

import java.util.Objects;

/**
 * Failure of the AVF execution strategy. Carries a stable reason code so the
 * launch flow can record a precise degradation reason before falling back to
 * the software strategy (ADR-0002 capability declaration discipline).
 */
public final class AvfEngineException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The reflective AVF SDK surface is absent on this device. */
    public static final String REFLECTION_UNAVAILABLE = "REFLECTION_UNAVAILABLE";
    /** The virtualization service could not be reached. */
    public static final String VM_MANAGER_UNAVAILABLE = "VM_MANAGER_UNAVAILABLE";
    /** VM creation failed for a generic, recorded reason. */
    public static final String VM_CREATE_FAILED = "VM_CREATE_FAILED";
    /** The hardware/firmware refuses non-protected custom VMs (e.g. blocked SoCs). */
    public static final String HARDWARE_UNSUPPORTED = "HARDWARE_UNSUPPORTED";
    /** The MANAGE_VIRTUAL_MACHINE authorization is missing or was revoked. */
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    /** The vsock control/terminal channel could not be established. */
    public static final String VSOCK_CONNECT_FAILED = "VSOCK_CONNECT_FAILED";

    private final String reason;

    public AvfEngineException(String reason, String message) {
        this(reason, message, null);
    }

    public AvfEngineException(String reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String reason() {
        return reason;
    }
}
