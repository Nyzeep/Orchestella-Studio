package io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1;

import java.io.IOException;
import java.util.Objects;

/**
 * Typed AXP1 failure. Messages are fixed strings: they never carry the token,
 * capability values, source bytes or the peer's expected value.
 */
public final class Axp1Exception extends IOException {
    private static final long serialVersionUID = 1L;
    private final Axp1Error code;

    public Axp1Exception(Axp1Error code, String message) {
        super(Objects.requireNonNull(message, "message"));
        this.code = Objects.requireNonNull(code, "code");
    }

    public Axp1Exception(Axp1Error code, String message, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Axp1Error code() {
        return code;
    }
}
