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
        socNameAloneIsNeverABlocklist();
        extraBlockedHintCatchesUnknownSocNames();
        supportedButUnauthorizedDeviceNeedsTheWizardAndKeepsTheSoftwareBadge();
        protectedOnlyModeCannotEnterAuthorizationWizard();
        unknownModeIsDistinctFromAuthorization();
        nonProtectedModeStillUsesThePermissionGate();
        platformAccessStatesAreTypedAndActionable();
        platformAccessNeverBypassesThePermissionGate();
        managerPresenceWithoutFeatureNeedsAuthorizationNotUndeclared();
        managerAbsenceWithoutFeatureStaysUndeclared();
        reasonsNeverNameExecutionEngines();
        numericOnePropertyIsRecognizedAsTruthy();
    }

    private static AvfPlatformFacts platformFacts(
            AvfPlatformFacts.AccessState state,
            AvfCapabilityEvaluator.PermissionState manage,
            AvfCapabilityEvaluator.PermissionState custom) {
        return platformFacts(state, manage, custom, false);
    }

    private static AvfPlatformFacts platformFacts(
            AvfPlatformFacts.AccessState state,
            AvfCapabilityEvaluator.PermissionState manage,
            AvfCapabilityEvaluator.PermissionState custom,
            boolean managerPresent) {
        return new AvfPlatformFacts(
                state,
                AvfPlatformFacts.ExemptionState.INSTALLED,
                manage,
                custom,
                mode(AvfCapabilityEvaluator.VmSupport.SUPPORTED,
                        state == AvfPlatformFacts.AccessState.AVAILABLE
                                || state == AvfPlatformFacts.AccessState.PERMISSION_DENIED
                                ? AvfCapabilityEvaluator.VmSupport.SUPPORTED
                                : AvfCapabilityEvaluator.VmSupport.UNSUPPORTED),
                managerPresent);
    }

    private static void platformAccessStatesAreTypedAndActionable() {
        AvfCapabilityEvaluator.AvfReadiness hidden = AvfCapabilityEvaluator.evaluate(
                readyDevice()
                        .platformFacts(platformFacts(
                                AvfPlatformFacts.AccessState.HIDDEN_API_BLOCKED,
                                AvfCapabilityEvaluator.PermissionState.GRANTED,
                                AvfCapabilityEvaluator.PermissionState.GRANTED))
                        .build());
        assertTrue(hidden.status() == AvfCapabilityEvaluator.AvfStatus.HIDDEN_API_BLOCKED,
                "hidden API blocked has its own status, not MODE_UNKNOWN");
        assertFalse(hidden.wizardEligible(), "authorization cannot fix a hidden API block");
        assertTrue(hidden.reasons().stream().anyMatch(reason -> reason.contains("隐藏 API")),
                "the hidden API reason is actionable");

        AvfCapabilityEvaluator.AvfReadiness reflection = AvfCapabilityEvaluator.evaluate(
                readyDevice()
                        .platformFacts(platformFacts(
                                AvfPlatformFacts.AccessState.REFLECTION_UNAVAILABLE,
                                AvfCapabilityEvaluator.PermissionState.GRANTED,
                                AvfCapabilityEvaluator.PermissionState.GRANTED))
                        .build());
        assertTrue(reflection.status() == AvfCapabilityEvaluator.AvfStatus.REFLECTION_UNAVAILABLE,
                "a missing platform surface is its own status");
        assertFalse(reflection.wizardEligible(), "authorization cannot add platform classes");

        AvfCapabilityEvaluator.AvfReadiness modeUnknown = AvfCapabilityEvaluator.evaluate(
                readyDevice()
                        .platformFacts(platformFacts(
                                AvfPlatformFacts.AccessState.MODE_UNKNOWN,
                                AvfCapabilityEvaluator.PermissionState.GRANTED,
                                AvfCapabilityEvaluator.PermissionState.GRANTED))
                        .build());
        assertTrue(modeUnknown.status() == AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN,
                "an unreadable platform stays MODE_UNKNOWN");

        AvfCapabilityEvaluator.AvfReadiness available = AvfCapabilityEvaluator.evaluate(
                readyDevice()
                        .platformFacts(platformFacts(
                                AvfPlatformFacts.AccessState.AVAILABLE,
                                AvfCapabilityEvaluator.PermissionState.GRANTED,
                                AvfCapabilityEvaluator.PermissionState.GRANTED))
                        .build());
        assertTrue(available.status() == AvfCapabilityEvaluator.AvfStatus.READY,
                "available with both grants is READY");
    }

    private static void platformAccessNeverBypassesThePermissionGate() {
        AvfCapabilityEvaluator.AvfReadiness denied = AvfCapabilityEvaluator.evaluate(
                readyDevice()
                        .platformFacts(platformFacts(
                                AvfPlatformFacts.AccessState.PERMISSION_DENIED,
                                AvfCapabilityEvaluator.PermissionState.DENIED,
                                AvfCapabilityEvaluator.PermissionState.GRANTED))
                        .build());
        assertTrue(denied.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                "a denied permission is an authorization request");
        assertTrue(denied.reasons().stream().anyMatch(reason -> reason.contains("MANAGE_VIRTUAL_MACHINE")),
                "the missing permission is named");

        AvfCapabilityEvaluator.AvfReadiness granted = AvfCapabilityEvaluator.evaluate(
                readyDevice()
                        .platformFacts(platformFacts(
                                AvfPlatformFacts.AccessState.PERMISSION_DENIED,
                                AvfCapabilityEvaluator.PermissionState.GRANTED,
                                AvfCapabilityEvaluator.PermissionState.GRANTED))
                        .build());
        assertTrue(granted.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                "a platform denial after grants is still not a silent READY");
        assertTrue(granted.reasons().stream().anyMatch(reason -> reason.contains("系统策略")),
                "a post-grant denial explains the platform policy");
    }

    private static void managerPresenceWithoutFeatureNeedsAuthorizationNotUndeclared() {
        AvfCapabilityEvaluator.AvfHostFacts.Builder facts =
                new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        35, List.of("arm64-v8a"), null, false,
                        AvfCapabilityEvaluator.PermissionState.DENIED,
                        AvfCapabilityEvaluator.PermissionState.DENIED);
        AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                facts.platformFacts(platformFacts(
                        AvfPlatformFacts.AccessState.PERMISSION_DENIED,
                        AvfCapabilityEvaluator.PermissionState.DENIED,
                        AvfCapabilityEvaluator.PermissionState.DENIED,
                        true))
                        .build());

        assertTrue(readiness.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION,
                "a present manager service on an OEM ROM without the feature string needs authorization");
        assertTrue(readiness.wizardEligible(), "authorization can resolve the permission gate");
        assertTrue(readiness.reasons().stream().anyMatch(reason -> reason.contains("MANAGE_VIRTUAL_MACHINE")),
                "the missing permission is named");
        assertFalse(readiness.reasons().stream().anyMatch(reason -> reason.contains("设备未声明")),
                "a present manager must not be reported as an undeclared platform");

        AvfCapabilityEvaluator.AvfReadiness unreadable = AvfCapabilityEvaluator.evaluate(
                new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        35, List.of("arm64-v8a"), null, false,
                        AvfCapabilityEvaluator.PermissionState.GRANTED,
                        AvfCapabilityEvaluator.PermissionState.GRANTED)
                        .platformFacts(platformFacts(
                                AvfPlatformFacts.AccessState.MODE_UNKNOWN,
                                AvfCapabilityEvaluator.PermissionState.GRANTED,
                                AvfCapabilityEvaluator.PermissionState.GRANTED,
                                true))
                        .build());
        assertTrue(unreadable.status() == AvfCapabilityEvaluator.AvfStatus.MODE_UNKNOWN,
                "a present manager with an unreadable mode stays MODE_UNKNOWN");
    }

    private static void managerAbsenceWithoutFeatureStaysUndeclared() {
        AvfCapabilityEvaluator.AvfReadiness readiness = AvfCapabilityEvaluator.evaluate(
                new AvfCapabilityEvaluator.AvfHostFacts.Builder(
                        35, List.of("arm64-v8a"), null, false,
                        AvfCapabilityEvaluator.PermissionState.DENIED,
                        AvfCapabilityEvaluator.PermissionState.DENIED)
                        .platformFacts(platformFacts(
                                AvfPlatformFacts.AccessState.PERMISSION_DENIED,
                                AvfCapabilityEvaluator.PermissionState.DENIED,
                                AvfCapabilityEvaluator.PermissionState.DENIED,
                                false))
                        .build());

        assertTrue(readiness.status() == AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED,
                "without a feature, property, or manager service the platform is undeclared");
        assertFalse(readiness.wizardEligible(), "there is nothing to authorize");
        assertTrue(readiness.reasons().stream().anyMatch(reason -> reason.contains("设备未声明")),
                "the absence is named");
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

    private static void socNameAloneIsNeverABlocklist() {
        // A hardcoded SoC list was a fifth truth source. Only platform facts and
        // caller-supplied device facts may refuse; the model string alone may not.
        AvfCapabilityEvaluator.AvfReadiness readiness =
                AvfCapabilityEvaluator.evaluate(readyDevice().socModel("SM8750").build());

        assertTrue(
                readiness.status() == AvfCapabilityEvaluator.AvfStatus.READY,
                "a SoC model must not be refused by a hardcoded name list");
        assertTrue(readiness.wizardEligible(), "the platform facts keep the wizard eligible");
        assertFalse(
                readiness.reasons().stream().anyMatch(reason -> reason.toLowerCase().contains("sm8750")),
                "no hardcoded SoC reason may surface");
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
                readyDevice().socModel("SM8750")
                        .extraBlockedHints(List.of("SM8750")).build())) {
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
