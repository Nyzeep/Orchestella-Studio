package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.capability.ExecutionStrategyAdmissionEvaluator;
import java.util.Set;

/**
 * Current repository release-evidence snapshot consumed by {@link ExecutionStrategyAdmissionEvaluator}.
 *
 * <p>Strictly honest and fail-closed: public opt-in / conditional recommendation remains locked
 * until Phase 1 (including physical 16 KB host-page matrix), paired cold/warm benchmarks across
 * all promised buckets, ACR-20261123 approval, and NOTICE.UML attribution/source closure are
 * all verified green.
 */
public final class UmlReleaseEvidenceCatalog {
    public static final String ACR_REFERENCE = "ACR-20261123";

    private UmlReleaseEvidenceCatalog() {}

    /**
     * Returns the current honest release-gate evidence snapshot.
     * Updated only with real, reproducible device and release-gate facts.
     */
    public static ExecutionStrategyAdmissionEvaluator.PromotionEvidence current() {
        ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket cold4k =
                new ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
                        "device-4k-cold-provisioned",
                        30,
                        2285L,
                        2424L,
                        14380L,
                        14456L,
                        0,
                        0,
                        0);
        ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket warm4k =
                new ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
                        "device-4k-warm-restart",
                        30,
                        2383L,
                        2543L,
                        14372L,
                        14432L,
                        0,
                        0,
                        0);
        return new ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
                /* phase1SignedOff */ false,
                /* host4kMatrixPassed */ true,
                /* host16kMatrixPassed */ false,
                /* acrApproved */ false,
                /* acrReference */ ACR_REFERENCE,
                /* fullPkgPayloadGatePassed */ true,
                /* noticeAttributionClosed */ false,
                /* verifiedHostPageSizes */ Set.of(4096),
                /* coldProvisioned */ cold4k,
                /* warmBoot */ warm4k);
    }
}
