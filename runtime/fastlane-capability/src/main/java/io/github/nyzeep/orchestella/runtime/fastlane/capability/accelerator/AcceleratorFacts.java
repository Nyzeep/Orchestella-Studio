package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import java.util.Objects;

/**
 * Runtime observations that decide which accelerator backend, if any, may run
 * beside QEMU. These are facts, not a device-name allowlist.
 *
 * <p>{@code compilerTriple} is the custom-VM image's compiler identity, not
 * the host compiler. The decision receives the required host triple
 * separately so the comparison stays pure and explicit. The payload fields
 * describe the protected payload's compiler; every default is the conservative
 * one, so an unobserved dimension can only ever refuse a backend, never arm
 * one.
 */
public record AcceleratorFacts(
        boolean avfPresent,
        boolean nonProtectedSupported,
        boolean protectedOnly,
        boolean acceleratorImagePresent,
        boolean vsockSupported,
        String compilerTriple,
        ThermalState thermalState,
        BatteryState batteryState,
        boolean protectedVmSupported,
        boolean payloadCompilerPresent,
        String payloadCompilerVersion,
        String payloadTargetTriple) {

    public AcceleratorFacts {
        compilerTriple = Objects.requireNonNull(compilerTriple, "compilerTriple");
        thermalState = Objects.requireNonNull(thermalState, "thermalState");
        batteryState = Objects.requireNonNull(batteryState, "batteryState");
        payloadCompilerVersion = Objects.requireNonNull(
                payloadCompilerVersion, "payloadCompilerVersion");
        payloadTargetTriple = Objects.requireNonNull(
                payloadTargetTriple, "payloadTargetTriple");
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
        private boolean protectedVmSupported;
        private boolean payloadCompilerPresent;
        private String payloadCompilerVersion = "";
        private String payloadTargetTriple = "";

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

        public Builder protectedVmSupported(boolean value) {
            protectedVmSupported = value;
            return this;
        }

        public Builder payloadCompilerPresent(boolean value) {
            payloadCompilerPresent = value;
            return this;
        }

        public Builder payloadCompilerVersion(String value) {
            payloadCompilerVersion = Objects.requireNonNull(value, "payloadCompilerVersion");
            return this;
        }

        public Builder payloadTargetTriple(String value) {
            payloadTargetTriple = Objects.requireNonNull(value, "payloadTargetTriple");
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
                    batteryState,
                    protectedVmSupported,
                    payloadCompilerPresent,
                    payloadCompilerVersion,
                    payloadTargetTriple);
        }
    }
}
