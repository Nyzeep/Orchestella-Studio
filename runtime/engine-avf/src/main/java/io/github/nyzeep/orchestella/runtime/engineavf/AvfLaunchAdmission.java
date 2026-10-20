package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pre-bootstrap admission for the AVF v1 profile. It runs before any rootfs or
 * initramfs assembly so a configuration AVF cannot express fails once, early,
 * and without the shared bootstrap cost — the strategy chain can then fall
 * back to QEMU without a second assembly.
 *
 * <p>AVF v1 is fixed at one vCPU. The pinned SystemApi has no exact-count CPU
 * setter, so a larger request is rejected rather than silently reduced or
 * mapped to {@code MATCH_HOST}. Every hardware field is either expressed in
 * the plan or recorded as {@link Disposition#NOT_EXPRESSIBLE}; nothing is
 * silently dropped.
 */
public final class AvfLaunchAdmission {
    /** The conservative memory used when a caller has no hardware config. */
    public static final long FALLBACK_MEMORY_MB = 512L;

    private AvfLaunchAdmission() {}

    public enum Disposition {
        /** The plan carries this value to the AVF config. */
        EXPRESSED,
        /** AVF v1 has no API for this field; the value is recorded, not applied. */
        NOT_EXPRESSIBLE
    }

    /** One hardware field and what the plan did with it. */
    public record FieldDecision(String field, Disposition disposition, String detail) {
        public FieldDecision {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(disposition, "disposition");
            detail = detail == null ? "" : detail;
        }
    }

    /** A fully admitted launch, before any asset assembly. */
    public record Plan(
            long memoryMb,
            int cpuCount,
            List<String> extraKernelArguments,
            List<FieldDecision> fields,
            List<String> diagnostics) {
        public Plan {
            extraKernelArguments = List.copyOf(extraKernelArguments);
            fields = List.copyOf(fields);
            diagnostics = List.copyOf(diagnostics);
        }

        /** The decision for one field, or null when the field is not part of the config. */
        public FieldDecision field(String name) {
            for (FieldDecision decision : fields) {
                if (decision.field().equals(name)) return decision;
            }
            return null;
        }
    }

    /** A typed refusal; reason is a stable {@link AvfEngineException} code. */
    public record Rejection(String reason, String field, String detail) {
        public Rejection {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(field, "field");
            detail = detail == null ? "" : detail;
        }
    }

    /** Exactly one of plan/rejection is present. */
    public static final class Result {
        private final Plan plan;
        private final Rejection rejection;

        private Result(Plan plan, Rejection rejection) {
            this.plan = plan;
            this.rejection = rejection;
        }

        public boolean accepted() {
            return plan != null;
        }

        public Plan plan() {
            return plan;
        }

        public Rejection rejection() {
            return rejection;
        }
    }

    /**
     * Evaluates one launch. {@code rootVolumeBytes} is the observed raw root
     * size, or a negative value when it does not exist yet (first boot).
     */
    public static Result evaluate(VmHardwareConfig config, AvfHostFacts facts, long rootVolumeBytes) {
        AvfHostFacts host = facts == null ? AvfHostFacts.unknown() : facts;
        if (host.nonProtectedVm() == AvfHostFacts.Support.UNSUPPORTED) {
            return reject(AvfEngineException.HARDWARE_UNSUPPORTED, "nonProtectedVm",
                    "the host declares no non-protected VM support");
        }
        if (config == null) {
            List<FieldDecision> fields = new ArrayList<>();
            fields.add(new FieldDecision("memory", Disposition.EXPRESSED,
                    "fallback " + FALLBACK_MEMORY_MB + " MB"));
            fields.add(diskDecision(rootVolumeBytes));
            fields.add(new FieldDecision("gpuAccelerationEnabled", Disposition.NOT_EXPRESSIBLE,
                    "AVF v1 has no guest GPU acceleration"));
            return new Result(new Plan(FALLBACK_MEMORY_MB, 1, List.of(), fields,
                    List.of("hardware-config: absent, using the conservative fallback")), null);
        }
        if (config.cpuCores() != 1) {
            return reject(AvfEngineException.HARDWARE_UNSUPPORTED, "cpuCores",
                    "AVF v1 cannot express " + config.cpuCores() + " vCPUs");
        }

        List<FieldDecision> fields = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        fields.add(new FieldDecision("memory", Disposition.EXPRESSED,
                config.ramMegabytes() + " MB -> setMemoryBytes"));
        fields.add(diskDecision(rootVolumeBytes));
        fields.add(new FieldDecision("gpuAccelerationEnabled", Disposition.NOT_EXPRESSIBLE,
                "AVF v1 has no guest GPU acceleration"));
        if (config.gpuAccelerationEnabled()) {
            diagnostics.add("GPU acceleration requested but not expressible; capability stays UNSUPPORTED");
        }

        List<String> extraArguments = List.of();
        if (config.extraKernelArguments() != null) {
            String invalid = firstControlCharacter(config.extraKernelArguments());
            if (invalid != null) {
                return reject(AvfEngineException.INVALID_LAUNCH_CONFIG, "extraKernelArguments",
                        "control character U+" + invalid.toUpperCase(Locale.ROOT)
                                + " is not allowed in the guest cmdline");
            }
            extraArguments = splitArguments(config.extraKernelArguments());
            fields.add(new FieldDecision("extraKernelArguments", Disposition.EXPRESSED,
                    extraArguments.size() + " argument(s) appended to the guest cmdline"));
        }

        return new Result(new Plan(config.ramMegabytes(), 1, extraArguments, fields, diagnostics), null);
    }

    private static FieldDecision diskDecision(long rootVolumeBytes) {
        String observed = rootVolumeBytes >= 0L
                ? "observedRootVolumeBytes=" + rootVolumeBytes
                : "observedRootVolumeBytes=unknown";
        return new FieldDecision("diskCapacityBytes", Disposition.NOT_EXPRESSIBLE,
                "AVF has no independent disk-size setter; " + observed);
    }

    private static List<String> splitArguments(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return List.of();
        return List.of(trimmed.split("\\s+"));
    }

    /** Returns the first control character as a hex string, or null. */
    private static String firstControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) {
                return String.format(Locale.ROOT, "%02x", (int) character);
            }
        }
        return null;
    }

    private static Result reject(String reason, String field, String detail) {
        return new Result(null, new Rejection(reason, field, detail));
    }
}
