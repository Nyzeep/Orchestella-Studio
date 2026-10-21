package io.github.nyzeep.orchestella.runtime.fastlane;

import java.util.Objects;

/** Typed failure at the protected Microdroid Fast Lane boundary. */
public final class FastLaneException extends Exception {
    private static final long serialVersionUID = 1L;

    public static final String CONFIG_INVALID = "CONFIG_INVALID";
    public static final String HIDDEN_API_BLOCKED = "HIDDEN_API_BLOCKED";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String PROTECTED_UNAVAILABLE = "PROTECTED_UNAVAILABLE";
    public static final String PAYLOAD_VERIFY_FAILED = "PAYLOAD_VERIFY_FAILED";
    public static final String VM_CREATE_FAILED = "VM_CREATE_FAILED";
    public static final String VSOCK_FAILED = "VSOCK_FAILED";
    public static final String STOP_UNCONFIRMED = "STOP_UNCONFIRMED";

    private final String reason;

    public FastLaneException(String reason, String message) {
        this(reason, message, null);
    }

    public FastLaneException(String reason, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String reason() {
        return reason;
    }
}
