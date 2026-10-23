package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import java.util.Objects;

/**
 * Runtime observations that decide whether the accelerator lane may run beside
 * QEMU. These are facts, not a device-name allowlist; protected-only devices
 * never become accelerators.
 *
 * <p>{@code compilerTriple} is the accelerator image's compiler identity, not
 * the host compiler. The decision receives the required host triple
 * separately so the comparison stays pure and explicit.
 */
public record AcceleratorFacts(
        boolean avfPresent,
        boolean nonProtectedSupported,
        boolean protectedOnly,
        boolean acceleratorImagePresent,
        boolean vsockSupported,
        String compilerTriple,
        ThermalState thermalState,
        BatteryState batteryState) {

    public AcceleratorFacts {
        compilerTriple = Objects.requireNonNull(compilerTriple, "compilerTriple");
        thermalState = Objects.requireNonNull(thermalState, "thermalState");
        batteryState = Objects.requireNonNull(batteryState, "batteryState");
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Nothing has been observed: every capability stays unavailable. */
    public static AcceleratorFacts unknown() {
        return builder().build();
    }

    /** Mutable builder; each setter returns this builder. */
    public static final class Builder {
        private boolean avfPresent;
        private boolean nonProtectedSupported;
        private boolean protectedOnly;
        private boolean acceleratorImagePresent;
        private boolean vsockSupported;
        private String compilerTriple = "";
        private ThermalState thermalState = ThermalState.UNKNOWN;
        private BatteryState batteryState = BatteryState.UNKNOWN;

        public Builder avfPresent(boolean value) {
            avfPresent = value;
            return this;
        }

        public Builder nonProtectedSupported(boolean value) {
            nonProtectedSupported = value;
            return this;
        }

        public Builder protectedOnly(boolean value) {
            protectedOnly = value;
            return this;
        }

        public Builder acceleratorImagePresent(boolean value) {
            acceleratorImagePresent = value;
            return this;
        }

        public Builder vsockSupported(boolean value) {
            vsockSupported = value;
            return this;
        }

        public Builder compilerTriple(String value) {
            compilerTriple = Objects.requireNonNull(value, "compilerTriple");
            return this;
        }

        public Builder thermalState(ThermalState value) {
            thermalState = Objects.requireNonNull(value, "thermalState");
            return this;
        }

        public Builder batteryState(BatteryState value) {
            batteryState = Objects.requireNonNull(value, "batteryState");
            return this;
        }

        public AcceleratorFacts build() {
            return new AcceleratorFacts(
                    avfPresent,
                    nonProtectedSupported,
                    protectedOnly,
                    acceleratorImagePresent,
                    vsockSupported,
                    compilerTriple,
                    thermalState,
                    batteryState);
        }
    }
}
