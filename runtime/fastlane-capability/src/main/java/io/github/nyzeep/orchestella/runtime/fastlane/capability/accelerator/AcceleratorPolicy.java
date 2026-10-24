package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure selection policy for the accelerator lane.
 *
 * <p>The lane is a compile service with pluggable backends, evaluated in a
 * fixed order: {@code PVM_PAYLOAD} → {@code CUSTOM_VM} → {@code HOST_NATIVE}.
 * A backend is only <em>evaluated</em> once it passes its candidacy gate; its
 * remaining gates (presence, identity, budget) produce the rejection that
 * names it. Every rejection is kept on the decision, and the verdict reports
 * the last evaluated backend's reason instead of the first failure, so an
 * earlier refusal is never lost.
 *
 * <p>{@code HOST_NATIVE} is the declared fallback but has no evaluation gates
 * yet, so it can never be selected. When no backend was even a candidate the
 * verdict is {@code ACCEL_UNAVAILABLE_NO_BACKEND} with {@code NONE}; the per
 * backend candidacy refusals stay observable in the rejection list, including
 * the {@code CUSTOM_VM} branch's {@code ACCEL_UNAVAILABLE_NO_NON_PROTECTED}.
 *
 * <p>Protected VMs are control-plane/prewarm only for the custom-VM backend;
 * for the payload backend they are the compute plane. Resource budgeting stays
 * in AX-01's ledger; this policy only consumes the observed facts.
 */
public final class AcceleratorPolicy {
    private AcceleratorPolicy() {
    }

    public static AcceleratorDecision decide(
            AcceleratorFacts facts, String requiredCompilerTriple) {
        return decide(facts, requiredCompilerTriple, null);
    }

    /**
     * @param requiredCompilerVersion the guest compiler version the payload
     *     compiler must match exactly. Null or blank leaves the version
     *     uncompared — the payload compiler still needs a non-blank version of
     *     its own, because an unverifiable identity is a mismatch.
     */
    public static AcceleratorDecision decide(
            AcceleratorFacts facts,
            String requiredCompilerTriple,
            String requiredCompilerVersion) {
        Objects.requireNonNull(facts, "facts");

        List<BackendDecision> rejections = new ArrayList<>();
        BackendDecision lastEvaluated = null;

        BackendDecision payload = evaluatePvmPayload(
                facts, requiredCompilerTriple, requiredCompilerVersion);
        if (payload.selected()) {
            return AcceleratorDecision.available(CompileBackend.PVM_PAYLOAD);
        }
        rejections.add(payload);
        if (facts.protectedVmSupported()) {
            lastEvaluated = payload;
        }

        BackendDecision customVm = evaluateCustomVm(facts, requiredCompilerTriple);
        if (customVm.selected()) {
            // The refusals collected before the selection stay observable.
            return AcceleratorDecision.available(CompileBackend.CUSTOM_VM, rejections);
        }
        rejections.add(customVm);
        if (customVmCandidate(facts)) {
            lastEvaluated = customVm;
        }

        if (lastEvaluated == null) {
            return AcceleratorDecision.qemuOnly(
                    CompileBackend.NONE,
                    AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_BACKEND,
                    rejections);
        }
        return AcceleratorDecision.qemuOnly(
                lastEvaluated.backend(), lastEvaluated.reasonCode(), rejections);
    }

    private static BackendDecision evaluatePvmPayload(
            AcceleratorFacts facts, String requiredTriple, String requiredVersion) {
        if (!facts.protectedVmSupported()) {
            return new BackendDecision(
                    CompileBackend.PVM_PAYLOAD,
                    AcceleratorReasonCode.PAYLOAD_UNAVAILABLE_NO_PROTECTED);
        }
        if (!facts.payloadCompilerPresent()) {
            return new BackendDecision(
                    CompileBackend.PVM_PAYLOAD,
                    AcceleratorReasonCode.PAYLOAD_IMAGE_MISSING);
        }
        if (!payloadIdentityMatches(facts, requiredTriple, requiredVersion)) {
            return new BackendDecision(
                    CompileBackend.PVM_PAYLOAD,
                    AcceleratorReasonCode.PAYLOAD_COMPILER_MISMATCH);
        }
        if (!budgetAllows(facts)) {
            return new BackendDecision(
                    CompileBackend.PVM_PAYLOAD,
                    AcceleratorReasonCode.THERMAL_BUDGET);
        }
        return new BackendDecision(CompileBackend.PVM_PAYLOAD, AcceleratorReasonCode.ACCEL_AVAILABLE);
    }

    private static BackendDecision evaluateCustomVm(AcceleratorFacts facts, String requiredTriple) {
        if (!customVmCandidate(facts)) {
            return new BackendDecision(
                    CompileBackend.CUSTOM_VM,
                    AcceleratorReasonCode.ACCEL_UNAVAILABLE_NO_NON_PROTECTED);
        }
        if (!facts.acceleratorImagePresent()) {
            return new BackendDecision(
                    CompileBackend.CUSTOM_VM,
                    AcceleratorReasonCode.ACCEL_IMAGE_MISSING);
        }
        if (!facts.vsockSupported()) {
            return new BackendDecision(
                    CompileBackend.CUSTOM_VM,
                    AcceleratorReasonCode.ACCEL_VSOCK_UNAVAILABLE);
        }
        if (!compilerMatches(facts.compilerTriple(), requiredTriple)) {
            return new BackendDecision(
                    CompileBackend.CUSTOM_VM,
                    AcceleratorReasonCode.COMPILER_MISMATCH);
        }
        if (!budgetAllows(facts)) {
            return new BackendDecision(
                    CompileBackend.CUSTOM_VM,
                    AcceleratorReasonCode.THERMAL_BUDGET);
        }
        return new BackendDecision(CompileBackend.CUSTOM_VM, AcceleratorReasonCode.ACCEL_AVAILABLE);
    }

    private static boolean customVmCandidate(AcceleratorFacts facts) {
        return facts.avfPresent() && facts.nonProtectedSupported() && !facts.protectedOnly();
    }

    private static boolean payloadIdentityMatches(
            AcceleratorFacts facts, String requiredTriple, String requiredVersion) {
        if (!compilerMatches(facts.payloadTargetTriple(), requiredTriple)) {
            return false;
        }
        if (facts.payloadCompilerVersion().isBlank()) {
            return false;
        }
        if (requiredVersion == null || requiredVersion.isBlank()) {
            return true;
        }
        return facts.payloadCompilerVersion().equals(requiredVersion);
    }

    private static boolean compilerMatches(String actual, String required) {
        return actual != null
                && required != null
                && !actual.isBlank()
                && !required.isBlank()
                && actual.equals(required);
    }

    private static boolean budgetAllows(AcceleratorFacts facts) {
        return thermalAllows(facts.thermalState()) && batteryAllows(facts.batteryState());
    }

    private static boolean thermalAllows(ThermalState state) {
        return state == ThermalState.NOMINAL || state == ThermalState.LIGHT;
    }

    private static boolean batteryAllows(BatteryState state) {
        return state == BatteryState.OK || state == BatteryState.CHARGING;
    }
}
