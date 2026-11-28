package io.github.nyzeep.orchestella.runtime.engineuml;

import java.util.Objects;

/** Immutable single-pass UML boot plan produced by {@link UmlBootPlanResolver}. */
public record UmlBootPlan(
        Status status,
        ResolutionKind resolutionKind,
        BootMode bootMode,
        RefusalCode refusalCode,
        UmlDistroRuntimeProfile runtimeProfile,
        String detail,
        String recoveryGuidance) {

    public enum Status {
        ALLOWED,
        REFUSED
    }

    public enum ResolutionKind {
        DETERMINISTIC,
        LEGACY_DEFAULT_PROFILE,
        REFUSED_DETERMINISTIC,
        REFUSED_RECOVERY_REQUIRED
    }

    public enum BootMode {
        FIRST_PROVISION,
        ASSEMBLED_REBOOT
    }

    public enum RefusalCode {
        NONE,
        CHANNEL_NOT_ALLOWED,
        UNKNOWN_KERNEL_PROFILE,
        CONFIG_MISMATCH,
        DISTRO_UNSUPPORTED_ON_PROFILE,
        MISSING_ASSEMBLED_ROOT_VOLUME,
        INVALID_ROOT_VOLUME
    }

    public UmlBootPlan {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(resolutionKind, "resolutionKind");
        Objects.requireNonNull(refusalCode, "refusalCode");
        detail = detail == null ? "" : detail;
        recoveryGuidance = recoveryGuidance == null ? "" : recoveryGuidance;
        if (status == Status.ALLOWED) {
            Objects.requireNonNull(bootMode, "bootMode");
            Objects.requireNonNull(runtimeProfile, "runtimeProfile");
            if (refusalCode != RefusalCode.NONE) {
                throw new IllegalArgumentException("allowed plan must have RefusalCode.NONE");
            }
        } else {
            if (bootMode != null) {
                throw new IllegalArgumentException("refused plan must not declare bootMode");
            }
            if (refusalCode == RefusalCode.NONE) {
                throw new IllegalArgumentException("refused plan must declare a refusalCode");
            }
        }
    }

    public boolean isAllowed() {
        return status == Status.ALLOWED;
    }

    public boolean requireRootfsSeed() {
        return isAllowed() && bootMode == BootMode.FIRST_PROVISION;
    }
}
