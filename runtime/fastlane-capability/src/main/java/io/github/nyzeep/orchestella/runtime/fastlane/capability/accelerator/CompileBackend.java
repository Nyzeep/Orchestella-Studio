package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

/**
 * A compile-service backend the accelerator lane may select. The lane is a
 * compile service with pluggable backends, not one specific VM kind.
 *
 * <p>The selection order is fixed in {@link AcceleratorPolicy}:
 * {@code PVM_PAYLOAD} → {@code CUSTOM_VM} → {@code HOST_NATIVE}.
 */
public enum CompileBackend {
    /** Protected Microdroid payload with an in-process compiler (no exec). */
    PVM_PAYLOAD,
    /** Non-protected custom VM running distccd (the pre-PVM backend). */
    CUSTOM_VM,
    /**
     * Host-side native compilation fallback. Declared for completeness and
     * left unimplemented: it has no evaluation gates yet, so the policy can
     * never select it.
     */
    HOST_NATIVE,
    /** No backend: nothing was selected and nothing was evaluated. */
    NONE
}
