package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import java.util.Objects;

/** A handshake or upstream failure; messages are generic and never name the target. */
public final class ProxyException extends Exception {
    private static final long serialVersionUID = 1L;

    private final ProxyFailure failure;

    public ProxyException(ProxyFailure failure, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public ProxyException(ProxyFailure failure, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public ProxyFailure failure() {
        return failure;
    }
}
