package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable accelerator decision. The JSON form is the stable contract shared
 * by logs and future UI; it contains fixed enum names only, never device
 * identifiers, paths, tokens or exception text.
 */
public record AcceleratorDecision(
        AcceleratorMode mode,
        AcceleratorReasonCode reasonCode,
        List<String> degradedCapabilities) {

    public static final String CAP_ACCELERATOR = "accelerator";
    public static final String CAP_DISTCC = "distcc";
    private static final Set<String> CAPABILITIES = Set.of(CAP_ACCELERATOR, CAP_DISTCC);
    private static final List<String> DEFAULT_DEGRADED = List.of(CAP_ACCELERATOR, CAP_DISTCC);

    public AcceleratorDecision {
        mode = Objects.requireNonNull(mode, "mode");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        degradedCapabilities = List.copyOf(
                Objects.requireNonNull(degradedCapabilities, "degradedCapabilities"));
        for (String capability : degradedCapabilities) {
            if (!CAPABILITIES.contains(capability)) {
                throw new IllegalArgumentException("unknown degraded capability");
            }
        }
        if (mode == AcceleratorMode.QEMU_PLUS_ACCEL) {
            if (reasonCode != AcceleratorReasonCode.ACCEL_AVAILABLE) {
                throw new IllegalArgumentException("QEMU_PLUS_ACCEL requires ACCEL_AVAILABLE");
            }
            if (!degradedCapabilities.isEmpty()) {
                throw new IllegalArgumentException("QEMU_PLUS_ACCEL cannot degrade capabilities");
            }
        } else {
            if (reasonCode == AcceleratorReasonCode.ACCEL_AVAILABLE) {
                throw new IllegalArgumentException("QEMU_ONLY requires a fallback reason");
            }
            if (degradedCapabilities.isEmpty()) {
                throw new IllegalArgumentException("QEMU_ONLY must name degraded capabilities");
            }
        }
    }

    public static AcceleratorDecision available() {
        return new AcceleratorDecision(
                AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE,
                List.of());
    }

    public static AcceleratorDecision qemuOnly(AcceleratorReasonCode reasonCode) {
        return new AcceleratorDecision(
                AcceleratorMode.QEMU_ONLY,
                reasonCode,
                DEFAULT_DEGRADED);
    }

    /** Stable compact JSON; key order and enum names are part of the contract. */
    public String toJson() {
        StringBuilder json = new StringBuilder(128);
        json.append("{\"mode\":\"").append(mode.name())
                .append("\",\"reasonCode\":\"").append(reasonCode.name())
                .append("\",\"degradedCapabilities\":[");
        for (int index = 0; index < degradedCapabilities.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(degradedCapabilities.get(index)).append('"');
        }
        return json.append("]}").toString();
    }
}
