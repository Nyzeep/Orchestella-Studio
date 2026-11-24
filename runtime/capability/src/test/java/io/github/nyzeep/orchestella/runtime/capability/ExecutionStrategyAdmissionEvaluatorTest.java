package io.github.nyzeep.orchestella.runtime.capability;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import java.util.List;
import java.util.Set;

/**
 * Contract tests for {@link ExecutionStrategyAdmissionEvaluator} (W3-05 / R-05):
 * verifies positive opt-in / conditional recommendation when every gate passes,
 * and fail-closed locking for missing data, below-threshold performance,
 * capability-gap requests, device support floor refusal, preflight incomplete/timeout,
 * and recommendation revocation.
 */
public final class ExecutionStrategyAdmissionEvaluatorTest {
    private static final long THREE_GIB = 3L * 1024L * 1024L * 1024L;
    private static final long EIGHT_GIB = 8L * 1024L * 1024L * 1024L;

    public static void main(String[] args) {
        refusesCreationWhenDeviceBelowSupportFloor();
        unlocksOptInAndConditionalRecommendationWhenAllGatesPass();
        locksWhenBenchmarkDataMissingOrInsufficientPairs();
        locksWhenColdOrWarmP95ExceedsThresholds();
        locksWhenSpeedupOverQemuBelowTwentyPercent();
        locksWhenUnexplainedTimeoutOccurred();
        locksWhenPhase1Or16kMatrixOrAcrOrPayloadOrNoticeIncomplete();
        retainsExistingStrategyWithReasonWhenUmlPreflightIncompleteOrTimedOut();
        retainsExistingStrategyWhenWorkloadRequiresUnverifiedCapabilities();
        locksRecommendationWhenRevokedWithoutBlockingFloorCreation();
    }

    private static DeviceCapabilityEvaluator.Assessment supportedDevice() {
        return DeviceCapabilityEvaluator.evaluate(
                new DeviceCapabilityEvaluator.DeviceProfile(35, List.of("arm64-v8a"), EIGHT_GIB, false));
    }

    private static ExecutionStrategyAdmissionEvaluator.PromotionEvidence allGreenEvidence() {
        return new ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
                true,
                true,
                true,
                true,
                "ACR-20261123",
                true,
                true,
                Set.of(4096, 16384),
                new ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
                        "cold_provisioned", 30, 800L, 1_500L, 6_000L, 8_000L, 0, 0, 0),
                new ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
                        "warm_restart", 30, 600L, 1_100L, 4_500L, 6_000L, 0, 0, 0));
    }

    private static ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight readyPreflight4k() {
        return new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
                4096,
                45L,
                "standalone UML assets and 4 KB host ready");
    }

    private static ExecutionStrategyAdmissionEvaluator.WorkloadRequirements alpineCliWorkload() {
        return new ExecutionStrategyAdmissionEvaluator.WorkloadRequirements(
                "alpine", true, false, false, false, false, false, false);
    }

    private static void refusesCreationWhenDeviceBelowSupportFloor() {
        DeviceCapabilityEvaluator.Assessment lowRam = DeviceCapabilityEvaluator.evaluate(
                new DeviceCapabilityEvaluator.DeviceProfile(35, List.of("arm64-v8a"), THREE_GIB - 1L, false));
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict verdict =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        lowRam,
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        allGreenEvidence(),
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        false);
        assertFalse(verdict.canCreateInstance(), "below-floor device must be refused creation");
        assertEquals("至少需要 3 GiB 内存", verdict.creationRefusalReason(), "floor refusal reason");
        assertEquals(
                ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED,
                verdict.offerMode(),
                "below-floor offer mode");
        assertEquals(
                ExecutionPolicyContract.PreflightCode.DEVICE_FLOOR_UNSUPPORTED,
                verdict.preflightVerdict().code(),
                "device floor preflight code");
        assertTrue(verdict.selectableStrategies().isEmpty(), "no strategy selectable below floor");
    }

    private static void unlocksOptInAndConditionalRecommendationWhenAllGatesPass() {
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict optIn =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        allGreenEvidence(),
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        false,
                        false);
        assertTrue(optIn.canCreateInstance(), "supported device can create");
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.OPT_IN, optIn.offerMode(), "opt-in mode");
        assertEquals(
                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                optIn.defaultStrategy(),
                "opt-in keeps existing default");
        assertTrue(
                optIn.selectableStrategies().contains(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL),
                "opt-in offers UML");
        assertTrue(
                optIn.umlCapabilityMatrix().supports(FullLinuxContract.Capability.TERMINAL),
                "UML verified matrix exposes terminal");
        assertFalse(
                optIn.umlCapabilityMatrix().supports(FullLinuxContract.Capability.GPU_HARDWARE_ACCELERATION),
                "UML matrix keeps GPU false");
        assertFalse(
                optIn.umlCapabilityMatrix().supports(FullLinuxContract.Capability.SHARED_DIRECTORY),
                "UML matrix keeps shared directory false");

        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict recommended =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        allGreenEvidence(),
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        false);
        assertEquals(
                ExecutionStrategyAdmissionEvaluator.OfferMode.CONDITIONAL_RECOMMENDED,
                recommended.offerMode(),
                "conditional recommendation mode");
        assertEquals(
                FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                recommended.defaultStrategy(),
                "conditional recommendation defaults to UML for matching CLI");
        assertTrue(recommended.recommendationActive(), "recommendation active");
    }

    private static void locksWhenBenchmarkDataMissingOrInsufficientPairs() {
        ExecutionStrategyAdmissionEvaluator.PromotionEvidence missingWarm =
                new ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
                        true, true, true, true, "ACR-20261123", true, true, Set.of(4096, 16384),
                        allGreenEvidence().coldProvisioned(),
                        null);
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict missingVerdict =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        missingWarm,
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        false);
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, missingVerdict.offerMode(), "missing warm locks");
        assertTrue(hasBlocker(missingVerdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.BENCHMARK_EVIDENCE_MISSING),
                "missing benchmark blocker");

        ExecutionStrategyAdmissionEvaluator.PromotionEvidence only29Pairs =
                new ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
                        true, true, true, true, "ACR-20261123", true, true, Set.of(4096, 16384),
                        new ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
                                "cold_provisioned", 29, 800L, 1_500L, 6_000L, 8_000L, 0, 0, 0),
                        allGreenEvidence().warmBoot());
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict underSampled =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        only29Pairs,
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        false);
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, underSampled.offerMode(), "29 pairs locks");
        assertTrue(hasBlocker(underSampled, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.INSUFFICIENT_SAMPLE_PAIRS),
                "insufficient pairs blocker");
    }

    private static void locksWhenColdOrWarmP95ExceedsThresholds() {
        ExecutionStrategyAdmissionEvaluator.PromotionEvidence slowCold =
                new ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
                        true, true, true, true, "ACR-20261123", true, true, Set.of(4096, 16384),
                        new ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
                                "cold_provisioned", 30, 8_000L, 10_001L, 15_000L, 20_000L, 0, 0, 0),
                        allGreenEvidence().warmBoot());
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict slowColdVerdict =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        slowCold,
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        false);
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, slowColdVerdict.offerMode(), "cold p95 > 10s locks");
        assertTrue(hasBlocker(slowColdVerdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.COLD_P95_EXCEEDED),
                "cold p95 blocker");

        ExecutionStrategyAdmissionEvaluator.PromotionEvidence slowWarm =
                new ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
                        true, true, true, true, "ACR-20261123", true, true, Set.of(4096, 16384),
                        allGreenEvidence().coldProvisioned(),
                        new ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
                                "warm_restart", 30, 2_000L, 3_001L, 5_000L, 8_000L, 0, 0, 0));
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict slowWarmVerdict =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        slowWarm,
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        false);
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, slowWarmVerdict.offerMode(), "warm p95 > 3s locks");
        assertTrue(hasBlocker(slowWarmVerdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.WARM_P95_EXCEEDED),
                "warm p95 blocker");
    }

    private static void locksWhenSpeedupOverQemuBelowTwentyPercent() {
        // 19.9% faster than QEMU (8010 vs 10000 -> 0.199 < 0.20) must remain locked.
        ExecutionStrategyAdmissionEvaluator.PromotionEvidence weakSpeedup =
                new ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
                        true, true, true, true, "ACR-20261123", true, true, Set.of(4096, 16384),
                        new ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
                                "cold_provisioned", 30, 7_000L, 8_010L, 9_000L, 10_000L, 0, 0, 0),
                        allGreenEvidence().warmBoot());
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict verdict =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        weakSpeedup,
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        false);
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, verdict.offerMode(), "<20% speedup locks");
        assertTrue(hasBlocker(verdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.SPEEDUP_BELOW_THRESHOLD),
                "speedup blocker");
    }

    private static void locksWhenUnexplainedTimeoutOccurred() {
        ExecutionStrategyAdmissionEvaluator.PromotionEvidence timedOut =
                new ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
                        true, true, true, true, "ACR-20261123", true, true, Set.of(4096, 16384),
                        new ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
                                "cold_provisioned", 30, 800L, 1_500L, 6_000L, 8_000L, 1, 0, 1),
                        allGreenEvidence().warmBoot());
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict verdict =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        timedOut,
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        false);
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, verdict.offerMode(), "unexplained timeout locks");
        assertTrue(hasBlocker(verdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.UNEXPLAINED_TIMEOUT),
                "timeout blocker");
    }

    private static void locksWhenPhase1Or16kMatrixOrAcrOrPayloadOrNoticeIncomplete() {
        ExecutionStrategyAdmissionEvaluator.PromotionEvidence pending16kAndAcr =
                new ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
                        false, true, false, false, "ACR-20261123", false, false, Set.of(4096),
                        allGreenEvidence().coldProvisioned(),
                        allGreenEvidence().warmBoot());
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict verdict =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        pending16kAndAcr,
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        false);
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, verdict.offerMode(), "incomplete governance locks");
        assertTrue(hasBlocker(verdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.PHASE1_NOT_SIGNED_OFF), "phase1");
        assertTrue(hasBlocker(verdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.HOST_16K_MATRIX_MISSING), "16k");
        assertTrue(hasBlocker(verdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.ACR_NOT_APPROVED), "acr");
        assertTrue(hasBlocker(verdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.PAYLOAD_GATE_FAILED), "payload");
        assertTrue(hasBlocker(verdict, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.NOTICE_ATTRIBUTION_OPEN), "notice");
    }

    private static void retainsExistingStrategyWithReasonWhenUmlPreflightIncompleteOrTimedOut() {
        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight timedOut =
                new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                        ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.TIMED_OUT,
                        4096,
                        3_000L,
                        "preflight exceeded 3000ms budget");
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict timeoutVerdict =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        allGreenEvidence(),
                        timedOut,
                        alpineCliWorkload(),
                        true,
                        false);
        assertTrue(timeoutVerdict.canCreateInstance(), "preflight timeout still allows creation on existing strategy");
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, timeoutVerdict.offerMode(), "timeout keeps locked");
        assertEquals(
                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                timeoutVerdict.defaultStrategy(),
                "timeout retains existing strategy");
        assertEquals(
                ExecutionPolicyContract.PreflightCode.UML_PREFLIGHT_TIMEOUT,
                timeoutVerdict.preflightVerdict().code(),
                "timeout preflight code");
        assertEquals(
                ExecutionPolicyContract.FallbackReason.UML_PREFLIGHT_TIMEOUT,
                timeoutVerdict.fallbackReason(),
                "timeout fallback reason");
        assertFalse(timeoutVerdict.userVisibleNotice().isBlank(), "timeout notice shown");

        ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight incomplete =
                new ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                        ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.INCOMPLETE,
                        4096,
                        10L,
                        "host page size probe pending");
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict incompleteVerdict =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                        allGreenEvidence(),
                        incomplete,
                        alpineCliWorkload(),
                        true,
                        false);
        assertEquals(
                FullLinuxContract.ExecutionStrategy.HARDWARE_VIRTUALIZATION,
                incompleteVerdict.defaultStrategy(),
                "incomplete retains existing applicable strategy");
        assertEquals(
                ExecutionPolicyContract.PreflightCode.UML_PREFLIGHT_INCOMPLETE,
                incompleteVerdict.preflightVerdict().code(),
                "incomplete preflight code");
        assertEquals(
                ExecutionPolicyContract.FallbackReason.UML_PREFLIGHT_INCOMPLETE,
                incompleteVerdict.fallbackReason(),
                "incomplete fallback reason");
    }

    private static void retainsExistingStrategyWhenWorkloadRequiresUnverifiedCapabilities() {
        List<ExecutionStrategyAdmissionEvaluator.WorkloadRequirements> incompatible = List.of(
                new ExecutionStrategyAdmissionEvaluator.WorkloadRequirements(
                        "debian", false, true, false, false, false, false, false),
                new ExecutionStrategyAdmissionEvaluator.WorkloadRequirements(
                        "alpine", true, false, true, false, false, false, false),
                new ExecutionStrategyAdmissionEvaluator.WorkloadRequirements(
                        "alpine", true, false, false, true, false, false, false),
                new ExecutionStrategyAdmissionEvaluator.WorkloadRequirements(
                        "alpine", true, false, false, false, true, false, false),
                new ExecutionStrategyAdmissionEvaluator.WorkloadRequirements(
                        "alpine", true, false, false, false, false, true, false),
                new ExecutionStrategyAdmissionEvaluator.WorkloadRequirements(
                        "alpine", true, false, false, false, false, false, true));

        for (ExecutionStrategyAdmissionEvaluator.WorkloadRequirements req : incompatible) {
            ExecutionStrategyAdmissionEvaluator.AdmissionVerdict verdict =
                    ExecutionStrategyAdmissionEvaluator.evaluate(
                            supportedDevice(),
                            FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                            allGreenEvidence(),
                            readyPreflight4k(),
                            req,
                            true,
                            false);
            assertTrue(verdict.canCreateInstance(), "incompatible UML workload still creates on existing strategy");
            assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, verdict.offerMode(), "must stay locked");
            assertEquals(
                    FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                    verdict.defaultStrategy(),
                    "must retain existing strategy");
            assertEquals(
                    ExecutionPolicyContract.PreflightCode.CAPABILITY_INTERSECTION_UNSATISFIED,
                    verdict.preflightVerdict().code(),
                    "capability intersection code");
            assertFalse(verdict.userVisibleNotice().isBlank(), "gap explanation must be shown");
        }
    }

    private static void locksRecommendationWhenRevokedWithoutBlockingFloorCreation() {
        ExecutionStrategyAdmissionEvaluator.AdmissionVerdict revoked =
                ExecutionStrategyAdmissionEvaluator.evaluate(
                        supportedDevice(),
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        allGreenEvidence(),
                        readyPreflight4k(),
                        alpineCliWorkload(),
                        true,
                        true);
        assertTrue(revoked.canCreateInstance(), "revocation does not block normal creation");
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, revoked.offerMode(), "revocation locks new offer");
        assertFalse(revoked.recommendationActive(), "recommendation inactive");
        assertEquals(
                FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                revoked.defaultStrategy(),
                "revocation restores existing default");
        assertTrue(hasBlocker(revoked, ExecutionStrategyAdmissionEvaluator.GateBlockerCode.RECOMMENDATION_REVOKED),
                "revoked blocker recorded");
    }

    private static boolean hasBlocker(
            ExecutionStrategyAdmissionEvaluator.AdmissionVerdict verdict,
            ExecutionStrategyAdmissionEvaluator.GateBlockerCode code) {
        return verdict.blockers().stream().anyMatch(b -> b.code() == code);
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
