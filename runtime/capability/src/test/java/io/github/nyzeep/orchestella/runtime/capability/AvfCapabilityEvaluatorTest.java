package io.github.nyzeep.orchestella.runtime.capability;

import java.util.List;

/**
 * Observable AVF hardware-virtualization readiness behavior (ADR-0003 L2 gate).
 * Detection inputs are pre-read host facts; the evaluator itself stays pure JVM.
 */
public final class AvfCapabilityEvaluatorTest {
    public static void main(String[] arguments) {
        manageGrantAloneDoesNotCompletePreflight();
        bothPermissionReadsMustBeConfirmedBeforeReady();
        readyDevicePassesWithTheHardwareBadge();
        sdkBelowAndroid13IsRefusedWithoutWizard();
        nonArm64DeviceIsRefusedWithoutWizard();
        explicitFalsePropertyRefusesEvenWhenFeatureFlagExists();
        featureFlagAloneSufficesWhenPropertyIsUnknown();
        neitherPropertyNorFeatureRefuses();
        blockedSnapdragon8EliteSocSilentlyClosesTheWizard();
        extraBlockedHintCatchesUnknownSocNames();
        supportedButUnauthorizedDeviceNeedsTheWizardAndKeepsTheSoftwareBadge();
        protectedOnlyModeCannotEnterAuthorizationWizard();
        unknownModeIsDistinctFromAuthorization();
        nonProtectedModeStillUsesThePermissionGate();
        reasonsNeverNameExecutionEngines();
        numericOnePropertyIsRecognizedAsTruthy();
    }

    private static void manageGrantAloneDoesNotCompletePreflight() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        35, List.of("arm64-v8a"), "true", true, true).build());
        assertTrue(readiness.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                "legacy MANAGE-only facts must leave CUSTOM unknown and require authorization");
    }

    private static void bothPermissionReadsMustBeConfirmedBeforeReady() {
        for (AvfCapabilityEvaluator.PermissionState manage : AvfCapabilityEvaluator.PermissionState.values()) {
            for (AvfCapabilityEvaluator.PermissionState custom : AvfCapabilityEvaluator.PermissionState.values()) {
                AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                        new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                35, List.of("arm64-v8a"), "true", true, manage, custom).build());
                boolean bothGranted = manage == AvfCapabilityEvaluator.PermissionState.GRANTED
                        && custom == AvfCapabilityEvaluator.PermissionState.GRANTED;
                assertEquals(bothGranted ? AvfCapabilityEvaluator.AvfStatus.READY
                                : AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                        readiness.status(), "permission pair must be independently checked");
                if (custom != AvfCapabilityEvaluator.PermissionState.GRANTED) {
                    assertTrue(readiness.reasons().stream().anyMatch(reason -> reason.contains("USE_CUSTOM_VIRTUAL_MACHINE")),
                            "CUSTOM denial or unknown is named in the reason");
                }
                if (manage != AvfCapabilityEvaluator.PermissionState.GRANTED) {
                    assertTrue(readiness.reasons().stream().anyMatch(reason -> reason.contains("MANAGE_VIRTUAL_MACHINE")),
                            "MANAGE denial or unknown is named in the reason");
                }
            }
        }
    }

    private static void numericOnePropertyIsRecognizedAsTruthy() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(
                        new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                        35, List.of("arm64-v8a"), "1", false, true, true)
                                .socModel("MT6991")
                                .deviceModel("Test Device")
                                .build());

        assertTrue(readiness.status() == AvfCapabilityEvaluator.AvfStatus.READY, "property '1' is recognized as READY");
    }

    private static AvfCapabilityEvaluator.AvfHostFacts.Builder readyDevice() {
        return new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        35, List.of("arm64-v8a"), "true", true, true, true)
                .socModel("MT6991")
                .deviceModel("Test Device");
    }

    private static void readyDevicePassesWithTheHardwareBadge() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(readyDevice().build());

        assertTrue(readiness.status() == AvfCapabilityEvaluator.AvfStatus.READY, "ready device is READY");
        assertTrue(readiness.wizardEligible(), "ready device needs no wizard but is eligible");
        assertEquals("完整 Linux · 权限预检满足（启动待验证）", readiness.performanceBadge(),
                "preflight badge must not claim hardware execution");
    }

    private static void sdkBelowAndroid13IsRefusedWithoutWizard() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(
                        new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                        32, List.of("arm64-v8a"), "true", true, true)
                                .build());

        assertTrue(readiness.status() == AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED, "API 32 is refused");
        assertTrue(
                readiness.reasons().get(0).contains("API 33"),
                "refusal explains the Android 13 floor: " + readiness.reasons().get(0));
        assertFalse(readiness.wizardEligible(), "unsupported device must never see the wizard");
    }

    private static void nonArm64DeviceIsRefusedWithoutWizard() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(
                        new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                        35, List.of("armeabi-v7a", "x86_64"), "true", true, true)
                                .build());

        assertTrue(readiness.status() == AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED, "non-arm64 is refused");
        assertFalse(readiness.wizardEligible(), "unsupported device must never see the wizard");
    }

    private static void explicitFalsePropertyRefusesEvenWhenFeatureFlagExists() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(
                        new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                        35, List.of("arm64-v8a"), "false", true, true)
                                .build());

        assertTrue(
                readiness.status() == AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED,
                "explicit ro.virtualization.supported=false must refuse even with the feature flag");
        assertFalse(readiness.wizardEligible(), "unsupported device must never see the wizard");
    }

    private static void featureFlagAloneSufficesWhenPropertyIsUnknown() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(
                        new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                        35, List.of("arm64-v8a"), null, true, true, true)
                                .build());

        assertTrue(
                readiness.status() == AvfCapabilityEvaluator.AvfStatus.READY,
                "unknown property with a declared virtualization framework feature is ready");
    }

    private static void neitherPropertyNorFeatureRefuses() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(
                        new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                        35, List.of("arm64-v8a"), null, false, false)
                                .build());

        assertTrue(readiness.status() == AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED, "no AVF signal refuses");
        assertFalse(readiness.wizardEligible(), "unsupported device must never see the wizard");
    }

    private static void blockedSnapdragon8EliteSocSilentlyClosesTheWizard() {
        // Snapdragon 8 Elite (SM8750, Gunyah) firmware-blocks non-protected custom VMs; the
        // platform may still declare ro.virtualization.supported=true, so the SoC hint wins.
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(readyDevice().socModel("SM8750").build());

        assertTrue(
                readiness.status() == AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED,
                "blocked SoC must be reported unsupported");
        assertFalse(readiness.wizardEligible(), "blocked SoC silently closes the authorization wizard");
        assertTrue(
                readiness.capabilityMatrix().executionStrategy()
                        == io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                "blocked SoC stays on the software baseline");
    }

    private static void extraBlockedHintCatchesUnknownSocNames() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(
                        readyDevice()
                                .socModel(null)
                                .deviceModel("Xiaomi 15")
                                .extraBlockedHints(List.of("Xiaomi 15"))
                                .build());

        assertTrue(
                readiness.status() == AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED,
                "caller-supplied blocked hints participate in the gate");
    }

    private static void supportedButUnauthorizedDeviceNeedsTheWizardAndKeepsTheSoftwareBadge() {
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(
                        new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                        35, List.of("arm64-v8a"), "true", true, false)
                                .build());

        assertTrue(
                readiness.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                "missing MANAGE_VIRTUAL_MACHINE authorization needs the wizard");
        assertTrue(readiness.wizardEligible(), "authorization wizard is shown");
        assertEquals(
                "完整 Linux · 软件模拟（较慢）",
                readiness.performanceBadge(),
                "badge stays honest until authorization completes");
    }

    private static AvfCapabilityEvaluator.AvfModeFacts mode(
            AvfCapabilityEvaluator.VmSupport protectedVm,
            AvfCapabilityEvaluator.VmSupport nonProtectedVm) {
        return new AvfCapabilityEvaluator.AvfModeFacts(
                protectedVm, nonProtectedVm,
                "VirtualMachineManager.getCapabilities", "app", 1234L, "test-rom");
    }

    private static void protectedOnlyModeCannotEnterAuthorizationWizard() {
        AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                readyDevice()
                        .modeFacts(mode(
                                AvfCapabilityEvaluator.VmSupport.SUPPORTED,
                                AvfCapabilityEvaluator.VmSupport.UNSUPPORTED))
                        .build());

        assertTrue(
                readiness.status() == AvfCapabilityEvaluator.AvfStatus.MODE_UNSUPPORTED,
                "a protected-only platform cannot satisfy the non-protected adapter");
        assertFalse(readiness.wizardEligible(), "granting permissions cannot change the VM mode");
        assertTrue(
                readiness.reasons().stream().anyMatch(reason -> reason.contains("非保护型")),
                "the reason names the missing non-protected mode");
        assertTrue(
                readiness.reasons().stream().anyMatch(reason -> reason.contains("protected=支持")),
                "the protected mode remains visible as a separate fact");
    }

    private static void unknownModeIsDistinctFromAuthorization() {
        AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                readyDevice()
                        .modeFacts(AvfCapabilityEvaluator.AvfModeFacts.unknown(
                                "VirtualMachineManager.getCapabilities", "app", 1234L, "test-rom"))
                        .build());

        assertTrue(
                readiness.status() == AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN,
                "an unreadable mode is MODE_UNKNOWN, not an authorization request");
        assertFalse(readiness.wizardEligible(), "authorization cannot resolve an unreadable mode");
    }

    private static void nonProtectedModeStillUsesThePermissionGate() {
        AvfCapabilityEvaluator.AvfModeFacts both = mode(
                AvfCapabilityEvaluator.VmSupport.SUPPORTED,
                AvfCapabilityEvaluator.VmSupport.SUPPORTED);
        AvfCapabilityEvaluator.AvfReadiness unauthorized = AvfCapabilityEvaluator.evaluate(
                new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                35, List.of("arm64-v8a"), "true", true, false, false)
                        .modeFacts(both)
                        .build());
        AvfCapabilityEvaluator.AvfReadiness ready = AvfCapabilityEvaluator.evaluate(
                new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                35, List.of("arm64-v8a"), "true", true, true, true)
                        .modeFacts(both)
                        .build());

        assertTrue(
                unauthorized.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                "a supported non-protected mode still needs both grants");
        assertTrue(readinessIsReady(ready), "both grants on a supported mode are READY");
    }

    private static boolean readinessIsReady(AvfCapabilityEvaluator.AvfReadiness readiness) {
        return readiness.status() == AvfCapabilityEvaluator.AvfStatus.READY;
    }

    private static void reasonsNeverNameExecutionEngines() {
        for (AvfCapabilityEvaluator.AvfHostFacts facts : List.of(
                new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                32, List.of("arm64-v8a"), "true", true, true)
                        .build(),
                new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                                35, List.of("arm64-v8a"), "false", false, false)
                        .build(),
                readyDevice().socModel("SM8750").build())) {
            AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(facts);
            for (String reason : readiness.reasons()) {
                assertFalse(
                        reason.contains("AVF") || reason.contains("QEMU") || reason.contains("PRoot"),
                        "user-facing reasons stay strategy-neutral (backend-invisible UI): " + reason);
            }
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            fail(description);
        }
    }

    private static void assertFalse(boolean condition, String description) {
        assertTrue(!condition, description);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_CAPABILITY_EVALUATOR_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
