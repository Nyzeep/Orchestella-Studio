package io.github.nyzeep.orchestella.runtime.fastlane.capability;

import java.util.Objects;

/**
 * Pure lane decision. Fast Lane is selected only when every prerequisite and
 * every explicit requirement is satisfied; every other path returns a visible
 * FULL or UNSUPPORTED decision with a typed reason.
 */
public final class FastLaneRouter {
    private FastLaneRouter() {}

    public static FastLaneDecision decide(
            DeviceFacts facts, Workload workload, RequirementProfile requirements) {
        Objects.requireNonNull(facts, "facts");
        Workload effectiveWorkload = workload == null ? Workload.UNKNOWN : workload;
        RequirementProfile effectiveRequirements = requirements == null
                ? RequirementProfile.ofWorkload(effectiveWorkload)
                : requirements;
        FastLaneCapabilities effective = FastLaneCapabilities.v1().project(facts);

        if (effectiveWorkload == Workload.FULL_LINUX
                || effectiveRequirements.requiresFullLinux()) {
            return fallback(facts, FastLaneReason.REQUIRES_FULL_LINUX, effective);
        }
        if (effectiveWorkload == Workload.UNKNOWN) {
            return fallback(facts, FastLaneReason.UNKNOWN_WORKLOAD, effective);
        }
        if (facts.hiddenApiBlocked() == Support.YES) {
            return fallback(facts, FastLaneReason.HIDDEN_API_BLOCKED, effective);
        }
        if (facts.managePermissionGranted() == Support.NO) {
            return fallback(facts, FastLaneReason.PERMISSION_DENIED, effective);
        }
        if (facts.avfPresent() == Support.NO) {
            return fallback(facts, FastLaneReason.NO_AVF, effective);
        }
        if (facts.protectedVmSupported() == Support.NO) {
            return fallback(facts, FastLaneReason.PROTECTED_UNAVAILABLE, effective);
        }
        if (facts.avfPresent() != Support.YES
                || facts.protectedVmSupported() != Support.YES
                || facts.managePermissionGranted() != Support.YES
                || facts.hiddenApiBlocked() != Support.NO) {
            return fallback(facts, FastLaneReason.UNKNOWN_DEVICE, effective);
        }

        FastLaneReason gap = firstRequirementGap(effectiveRequirements, facts);
        if (gap != null) {
            return fallback(facts, gap, effective);
        }
        return FastLaneDecision.fast(
                effective, FastLaneCopy.explain(Lane.FAST, FastLaneReason.FAST_LANE_AVAILABLE));
    }

    /**
     * Returns the first requirement that is not satisfied. {@code null} means
     * every explicit requirement fits the Fast Lane contract.
     */
    private static FastLaneReason firstRequirementGap(
            RequirementProfile requirements, DeviceFacts facts) {
        if (requirements.terminal() && !FastLaneCapabilities.v1().terminal()) {
            return FastLaneReason.UNKNOWN_DEVICE;
        }
        if (requirements.persistentStorage() || requirements.exportImport()) {
            FastLaneReason gap = availability(
                    facts.encryptedStorageAvailable(), FastLaneReason.STORAGE_UNAVAILABLE);
            if (gap != null) {
                return gap;
            }
        }
        if (requirements.tcpProxy()) {
            FastLaneReason gap = availability(
                    facts.tcpProxyAvailable(), FastLaneReason.PROXY_UNAVAILABLE);
            if (gap != null) {
                return gap;
            }
        }
        if (requirements.sealedToolbox()) {
            FastLaneReason gap = availability(
                    facts.sealedToolboxAvailable(), FastLaneReason.TOOLBOX_UNAVAILABLE);
            if (gap != null) {
                return gap;
            }
        }
        if (requirements.distccOffload()) {
            FastLaneReason gap = availability(
                    facts.distccOffloadAvailable(), FastLaneReason.DISTCC_UNAVAILABLE);
            if (gap != null) {
                return gap;
            }
        }
        return null;
    }

    private static FastLaneReason availability(Support support, FastLaneReason missing) {
        return switch (support) {
            case YES -> null;
            case NO -> missing;
            case UNKNOWN -> FastLaneReason.UNKNOWN_DEVICE;
        };
    }

    private static FastLaneDecision fallback(
            DeviceFacts facts, FastLaneReason reason, FastLaneCapabilities effective) {
        if (facts.fullLaneAvailable() == Support.NO) {
            return FastLaneDecision.unsupported(
                    reason, effective, FastLaneCopy.explain(Lane.UNSUPPORTED, reason));
        }
        return FastLaneDecision.full(
                reason, effective, FastLaneCopy.explain(Lane.FULL, reason));
    }
}
