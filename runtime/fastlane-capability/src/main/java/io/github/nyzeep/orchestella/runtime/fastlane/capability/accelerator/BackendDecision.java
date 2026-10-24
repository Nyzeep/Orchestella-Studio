package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import java.util.Objects;

/**
 * One backend's verdict inside a single selection pass.
 *
 * <p>{@code reasonCode} is {@code ACCEL_AVAILABLE} exactly when this backend
 * was selected; every other fixed code names why it was passed over. The JSON
 * form carries fixed enum names only, never device identifiers, paths, tokens
 * or exception text.
 */
public record BackendDecision(CompileBackend backend, AcceleratorReasonCode reasonCode) {

    public BackendDecision {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public boolean selected() {
        return reasonCode == AcceleratorReasonCode.ACCEL_AVAILABLE;
    }
}
