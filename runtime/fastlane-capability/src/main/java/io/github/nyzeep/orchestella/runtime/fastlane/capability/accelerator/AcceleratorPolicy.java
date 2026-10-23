package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import java.util.Objects;

/**
 * Pure selection policy for the accelerator lane.
 *
 * <p>Only a non-protected AVF VM with a present image, a usable vsock channel,
 * a matching compiler triple and an allowed thermal/battery budget may run
 * beside QEMU. Protected VMs are control-plane/prewarm only and never count as
 * accelerators. Resource budgeting stays in AX-01's ledger; this policy only
 * consumes the observed facts.
 */
public final class AcceleratorPolicy {
    private AcceleratorPolicy() {
    }

    public static AcceleratorDecision decide(
            AcceleratorFacts facts, String requiredCompilerTriple) {
        Objects.requireNonNull(facts, "facts");

        if (!facts.avfPresent() || !facts.nonProtectedSupported() || facts.protectedOnly()) {
            return AcceleratorDecision.qemuOnly(
                    AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED);
        }
        if (!facts.acceleratorImagePresent()) {
            return AcceleratorDecision.qemuOnly(
                    AcceleratorReasonCode.ACCEL_IMAGE_MISSING);
        }
        if (!facts.vsockSupported()) {
            return AcceleratorDecision.qemuOnly(
                    AcceleratorReasonCode.ACCEL_VSOCK_UNAVAILABLE);
        }
        if (!compilerMatches(facts.compilerTriple(), requiredCompilerTriple)) {
            return AcceleratorDecision.qemuOnly(
                    AcceleratorReasonCode.COMPILER_MISMATCH);
        }
        if (!thermalAllows(facts.thermalState()) || !batteryAllows(facts.batteryState())) {
            return AcceleratorDecision.qemuOnly(
                    AcceleratorReasonCode.THERMAL_BUDGET);
        }
        return AcceleratorDecision.available();
    }

    private static boolean compilerMatches(String actual, String required) {
        return actual != null
                && required != null
                && !actual.isBlank()
                && !required.isBlank()
                && actual.equals(required);
    }

    private static boolean thermalAllows(ThermalState state) {
        return state == ThermalState.NOMINAL || state == ThermalState.LIGHT;
    }

    private static boolean batteryAllows(BatteryState state) {
        return state == BatteryState.OK || state == BatteryState.CHARGING;
    }
}
