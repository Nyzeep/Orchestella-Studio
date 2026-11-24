package io.github.nyzeep.orchestella.ui.screens.wizard

import io.github.nyzeep.orchestella.HostHardwareProfile
import io.github.nyzeep.orchestella.InstanceCreationWizardModel
import io.github.nyzeep.orchestella.UmlReleaseEvidenceCatalog
import io.github.nyzeep.orchestella.runtime.capability.DeviceCapabilityEvaluator
import io.github.nyzeep.orchestella.runtime.capability.ExecutionStrategyAdmissionEvaluator
import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM contract tests for W3-05 (R-05) wizard execution-strategy admission,
 * device support-floor refusal, capability-intersection gating, preflight fallback,
 * ride-along isolation, and Roborazzi default-facts compatibility.
 */
class WizardAdmissionProjectionTest {

    private val profile = HostHardwareProfile(
        8,
        8L * 1024L * 1024L * 1024L,
        4L * 1024L * 1024L * 1024L,
        512L * 1024L * 1024L,
        false,
    )

    private val compliantFloor = DeviceCapabilityEvaluator.evaluate(
        DeviceCapabilityEvaluator.DeviceProfile(
            35,
            listOf("arm64-v8a"),
            8L * 1024L * 1024L * 1024L,
            false,
        ),
    )

    private val readyPreflight = ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
        ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.READY,
        4096,
        18L,
        "ready",
    )

    private val greenEvidence = ExecutionStrategyAdmissionEvaluator.PromotionEvidence(
        /* phase1SignedOff */ true,
        /* host4kMatrixPassed */ true,
        /* host16kMatrixPassed */ true,
        /* acrApproved */ true,
        /* acrReference */ "ACR-20261123",
        /* fullPkgPayloadGatePassed */ true,
        /* noticeAttributionClosed */ true,
        /* verifiedHostPageSizes */ setOf(4096, 16384),
        ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
            "cold-4k", 30, 2100L, 3800L, 9000L, 12500L, 0, 0, 0,
        ),
        ExecutionStrategyAdmissionEvaluator.PairedPerformanceBucket(
            "warm-4k", 30, 800L, 1600L, 4200L, 5800L, 0, 0, 0,
        ),
    )

    @Test
    fun defaultWizardFactsHideStrategyCardSoLegacyPreviewsStayUnchanged() {
        val model = InstanceCreationWizardModel("ubuntu", "24.04", profile)
        val state = WizardUiState.of(model, WizardFacts(availableBytes = 32L * 1024L * 1024L * 1024L))

        assertNull(state.deviceFloorRefusal)
        assertNull(state.hardware.executionStrategy)
        assertTrue(state.canProceed)
    }

    @Test
    fun belowSupportFloorRefusesInstanceCreationAndBlocksProceed() {
        val lowRamFloor = DeviceCapabilityEvaluator.evaluate(
            DeviceCapabilityEvaluator.DeviceProfile(
                35,
                listOf("arm64-v8a"),
                2L * 1024L * 1024L * 1024L,
                false,
            ),
        )
        val model = InstanceCreationWizardModel(
            "ubuntu",
            "24.04",
            profile,
            InstanceCreationWizardModel.CreationStep.STEP_STARTUP_TASKS,
        )
        val state = WizardUiState.of(
            model,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = lowRamFloor,
                promotionEvidence = greenEvidence,
                umlPreflight = readyPreflight,
            ),
        )

        assertNotNull(state.deviceFloorRefusal)
        assertTrue(state.deviceFloorRefusal!!.contains("3 GiB"))
        assertFalse(state.canProceed)
        assertEquals(
            ExecutionPolicyContract.PreflightCode.DEVICE_FLOOR_UNSUPPORTED,
            state.hardware.executionStrategy!!.preflightCode,
        )
    }

    @Test
    fun currentRepositoryEvidenceKeepsStandaloneUmlLockedHonestAndFailClosed() {
        val current = UmlReleaseEvidenceCatalog.current()
        assertNotNull(current.coldProvisioned())
        assertNotNull(current.warmBoot())
        assertTrue(current.coldProvisioned()!!.speedupOverQemuRatio() >= 0.80)
        assertTrue(current.warmBoot()!!.speedupOverQemuRatio() >= 0.80)

        val model = InstanceCreationWizardModel("alpine", "3.22.2", profile)
        val state = WizardUiState.of(
            model,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = compliantFloor,
                promotionEvidence = current,
                umlPreflight = readyPreflight,
                allowConditionalRecommendation = true,
            ),
        )

        val strategy = state.hardware.executionStrategy!!
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.LOCKED, strategy.offerMode)
        assertFalse(strategy.umlSelectable)
        assertEquals(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION, strategy.selectedStrategy)
        assertEquals(ExecutionPolicyContract.PreflightCode.PROMOTION_GATE_LOCKED, strategy.preflightCode)
        assertTrue(strategy.notice.contains("ACR-20261123"))
        assertTrue(strategy.notice.contains("16 KB"))
    }

    @Test
    fun unlocksOptInAndConditionalRecommendationForMatchingCliAlpineWhenAllGatesPass() {
        val model = InstanceCreationWizardModel("alpine", "3.22.2", profile)
        val optInState = WizardUiState.of(
            model,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = compliantFloor,
                promotionEvidence = greenEvidence,
                umlPreflight = readyPreflight,
                allowConditionalRecommendation = false,
            ),
        )
        val optInStrategy = optInState.hardware.executionStrategy!!
        assertEquals(ExecutionStrategyAdmissionEvaluator.OfferMode.OPT_IN, optInStrategy.offerMode)
        assertTrue(optInStrategy.umlSelectable)
        assertEquals(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION, optInStrategy.selectedStrategy)

        model.selectExecutionStrategy(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL)
        val selectedState = WizardUiState.of(
            model,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = compliantFloor,
                promotionEvidence = greenEvidence,
                umlPreflight = readyPreflight,
                allowConditionalRecommendation = false,
            ),
        )
        assertEquals(
            FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            selectedState.hardware.executionStrategy!!.selectedStrategy,
        )
        assertTrue(
            selectedState.hardware.executionStrategy!!.activeCapabilityMatrix
                .supports(FullLinuxContract.Capability.TERMINAL),
        )
        assertFalse(
            selectedState.hardware.executionStrategy!!.activeCapabilityMatrix.supportsSharedDirectory(),
        )

        val recommendedModel = InstanceCreationWizardModel("alpine", "3.22.2", profile)
        val recommendedState = WizardUiState.of(
            recommendedModel,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = compliantFloor,
                promotionEvidence = greenEvidence,
                umlPreflight = readyPreflight,
                allowConditionalRecommendation = true,
            ),
        )
        assertEquals(
            ExecutionStrategyAdmissionEvaluator.OfferMode.CONDITIONAL_RECOMMENDED,
            recommendedState.hardware.executionStrategy!!.offerMode,
        )
        assertEquals(
            FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
            recommendedState.hardware.executionStrategy!!.selectedStrategy,
        )
    }

    @Test
    fun retainsExistingStrategyWhenUnverifiedCapabilitiesAreRequested() {
        // 1. Shared directory toggled on clears prior UML selection and retains existing strategy.
        val alpine = InstanceCreationWizardModel("alpine", "3.22.2", profile)
        alpine.selectExecutionStrategy(FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL)
        alpine.setMountHostSharedDirectory(true)
        assertNull(alpine.selectedExecutionStrategy)

        val sharedDirState = WizardUiState.of(
            alpine,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = compliantFloor,
                promotionEvidence = greenEvidence,
                umlPreflight = readyPreflight,
                allowConditionalRecommendation = true,
            ),
        )
        assertFalse(sharedDirState.hardware.executionStrategy!!.umlSelectable)
        assertEquals(
            ExecutionPolicyContract.PreflightCode.CAPABILITY_INTERSECTION_UNSATISFIED,
            sharedDirState.hardware.executionStrategy!!.preflightCode,
        )
        assertTrue(sharedDirState.hardware.executionStrategy!!.notice.contains("共享目录"))

        // 2. Debian desktop/container/systemd retains existing strategy.
        val debian = InstanceCreationWizardModel("debian", "13", profile)
        debian.setOptionalProfiles(desktop = true, podman = true, editor = false)
        val debianState = WizardUiState.of(
            debian,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = compliantFloor,
                promotionEvidence = greenEvidence,
                umlPreflight = readyPreflight,
                allowConditionalRecommendation = true,
            ),
        )
        assertFalse(debianState.hardware.executionStrategy!!.umlSelectable)
        assertEquals(
            FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
            debianState.hardware.executionStrategy!!.selectedStrategy,
        )

        // 3. Explicit GPU requirement on Alpine retains existing strategy.
        val gpuAlpine = InstanceCreationWizardModel("alpine", "3.22.2", profile)
        val gpuState = WizardUiState.of(
            gpuAlpine,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = compliantFloor,
                promotionEvidence = greenEvidence,
                umlPreflight = readyPreflight,
                allowConditionalRecommendation = true,
                requiresGpuOverride = true,
            ),
        )
        assertFalse(gpuState.hardware.executionStrategy!!.umlSelectable)
        assertTrue(gpuState.hardware.executionStrategy!!.notice.contains("GPU"))
    }

    @Test
    fun preflightIncompleteOrTimeoutRetainsExistingStrategyWithExplicitReason() {
        val model = InstanceCreationWizardModel("alpine", "3.22.2", profile)
        val incompleteState = WizardUiState.of(
            model,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = compliantFloor,
                promotionEvidence = greenEvidence,
                umlPreflight = ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.INCOMPLETE,
                    -1,
                    0L,
                    "独立 UML 预检尚未完成",
                ),
                allowConditionalRecommendation = true,
            ),
        )
        assertTrue(incompleteState.canProceed)
        assertFalse(incompleteState.hardware.executionStrategy!!.umlSelectable)
        assertEquals(
            ExecutionPolicyContract.PreflightCode.UML_PREFLIGHT_INCOMPLETE,
            incompleteState.hardware.executionStrategy!!.preflightCode,
        )
        assertEquals(
            ExecutionPolicyContract.FallbackReason.UML_PREFLIGHT_INCOMPLETE,
            incompleteState.hardware.executionStrategy!!.fallbackReason,
        )

        val timeoutState = WizardUiState.of(
            model,
            WizardFacts(
                availableBytes = 32L * 1024L * 1024L * 1024L,
                deviceFloor = compliantFloor,
                promotionEvidence = greenEvidence,
                umlPreflight = ExecutionStrategyAdmissionEvaluator.StandaloneUmlPreflight(
                    ExecutionStrategyAdmissionEvaluator.StandalonePreflightStatus.TIMED_OUT,
                    -1,
                    1500L,
                    "preflight-timeout-1500ms",
                ),
                allowConditionalRecommendation = true,
            ),
        )
        val strategy = timeoutState.hardware.executionStrategy!!
        assertTrue(timeoutState.canProceed)
        assertFalse(strategy.umlSelectable)
        assertEquals(FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION, strategy.selectedStrategy)
        assertEquals(ExecutionPolicyContract.PreflightCode.UML_PREFLIGHT_TIMEOUT, strategy.preflightCode)
        assertEquals(ExecutionPolicyContract.FallbackReason.UML_PREFLIGHT_TIMEOUT, strategy.fallbackReason)
        assertTrue(strategy.notice.contains("超时"))
    }
}
