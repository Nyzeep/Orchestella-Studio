package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable accelerator decision. The JSON form is the stable contract shared
 * by logs and future UI; it contains fixed enum names only, never device
 * identifiers, paths, tokens or exception text.
 *
 * <p>{@code backend} names the backend this verdict is about: the selected
 * backend when armed, otherwise the last backend that was actually evaluated
 * and refused ({@code NONE} when no backend was a candidate, and in the
 * legacy factories that predate backends). {@code rejections} keeps every
 * refusal the ordered pass produced, in evaluation order, so an earlier
 * backend's reason is never lost to the final verdict.
 */
public record AcceleratorDecision(
        AcceleratorMode mode,
        AcceleratorReasonCode reasonCode,
        List<String> degradedCapabilities,
        CompileBackend backend,
        List<BackendDecision> rejections) {

    public static final String CAP_ACCELERATOR = "accelerator";
    public static final String CAP_DISTCC = "distcc";
    private static final Set<String> CAPABILITIES = Set.of(CAP_ACCELERATOR, CAP_DISTCC);
    private static final List<String> DEFAULT_DEGRADED = List.of(CAP_ACCELERATOR, CAP_DISTCC);

    public AcceleratorDecision {
        mode = Objects.requireNonNull(mode, "mode");
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        degradedCapabilities = List.copyOf(
                Objects.requireNonNull(degradedCapabilities, "degradedCapabilities"));
        backend = Objects.requireNonNull(backend, "backend");
        rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
        for (String capability : degradedCapabilities) {
            if (!CAPABILITIES.contains(capability)) {
                throw new IllegalArgumentException("unknown degraded capability");
            }
        }
        for (BackendDecision rejection : rejections) {
            if (rejection.selected()) {
                throw new IllegalArgumentException("rejections must describe refusals");
            }
        }
        if (mode == AcceleratorMode.QEMU_PLUS_ACCEL) {
            if (reasonCode != AcceleratorReasonCode.ACCEL_AVAILABLE) {
                throw new IllegalArgumentException("QEMU_PLUS_ACCEL requires ACCEL_AVAILABLE");
            }
            if (!degradedCapabilities.isEmpty()) {
                throw new IllegalArgumentException("QEMU_PLUS_ACCEL cannot degrade capabilities");
            }
            if (backend == CompileBackend.NONE) {
                throw new IllegalArgumentException("QEMU_PLUS_ACCEL must name its backend");
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

    /**
     * Legacy three-argument form kept for the pre-PVM call sites: an armed
     * decision defaults to the backend that world had (the non-protected
     * custom VM), a refused one to {@code NONE}, with an empty rejection list.
     */
    public AcceleratorDecision(
            AcceleratorMode mode,
            AcceleratorReasonCode reasonCode,
            List<String> degradedCapabilities) {
        this(mode, reasonCode, degradedCapabilities,
                mode == AcceleratorMode.QEMU_PLUS_ACCEL
                        ? CompileBackend.CUSTOM_VM
                        : CompileBackend.NONE,
                List.of());
    }

    public static AcceleratorDecision available() {
        return available(CompileBackend.CUSTOM_VM);
    }

    public static AcceleratorDecision available(CompileBackend backend) {
        return available(backend, List.of());
    }

    public static AcceleratorDecision available(
            CompileBackend backend, List<BackendDecision> rejections) {
        return new AcceleratorDecision(
                AcceleratorMode.QEMU_PLUS_ACCEL,
                AcceleratorReasonCode.ACCEL_AVAILABLE,
                List.of(),
                backend,
                rejections);
    }

    public static AcceleratorDecision qemuOnly(AcceleratorReasonCode reasonCode) {
        return new AcceleratorDecision(
                AcceleratorMode.QEMU_ONLY,
                reasonCode,
                DEFAULT_DEGRADED,
                CompileBackend.NONE,
                List.of());
    }

    public static AcceleratorDecision qemuOnly(
            CompileBackend backend,
            AcceleratorReasonCode reasonCode,
            List<BackendDecision> rejections) {
        return new AcceleratorDecision(
                AcceleratorMode.QEMU_ONLY,
                reasonCode,
                DEFAULT_DEGRADED,
                backend,
                rejections);
    }

    /** Stable compact JSON; key order and enum names are part of the contract. */
    public String toJson() {
        StringBuilder json = new StringBuilder(160);
        json.append("{\"mode\":\"").append(mode.name())
                .append("\",\"reasonCode\":\"").append(reasonCode.name())
                .append("\",\"backend\":\"").append(backend.name())
                .append("\",\"degradedCapabilities\":[");
        appendNames(json, degradedCapabilities);
        json.append("],\"rejections\":[");
        for (int index = 0; index < rejections.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            BackendDecision rejection = rejections.get(index);
            json.append("{\"backend\":\"").append(rejection.backend().name())
                    .append("\",\"reasonCode\":\"").append(rejection.reasonCode().name())
                    .append("\"}");
        }
        return json.append("]}").toString();
    }

    private static void appendNames(StringBuilder json, List<String> names) {
        for (int index = 0; index < names.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(names.get(index)).append('"');
        }
    }
}
