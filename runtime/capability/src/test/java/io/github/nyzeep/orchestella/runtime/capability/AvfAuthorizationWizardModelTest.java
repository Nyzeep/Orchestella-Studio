package io.github.nyzeep.orchestella.runtime.capability;

import java.util.List;

/**
 * Authorization wizard state machine (ADR-0003): capability detection first,
 * Shizuku guidance preferred, ADB command fallback, unsupported devices never
 * see any guidance step.
 */
public final class AvfAuthorizationWizardModelTest {
    private static final String APPLICATION_ID = "io.github.nyzeep.orchestella";

    public static void main(String[] arguments) {
        manageOnlyConfirmationDoesNotCompleteTheWizard();
        unsupportedReadinessNeverOpensTheWizard();
        readyReadinessNeedsNoWizardSteps();
        needsAuthorizationStartsWithShizukuGuidance();
        adbFallbackRoundTrip();
        confirmAuthorizedOnlyAdvancesWhenPermissionIsGranted();
        declineClosesTheWizard();
        adbGrantCommandIsExact();
    }

    private static void manageOnlyConfirmationDoesNotCompleteTheWizard() {
        AvfAuthorizationWizardModel started = AvfAuthorizationWizardModel.fromAssessment(
                readiness(AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION)).start();
        assertTrue(started.confirmAuthorized(true).step()
                        == AvfAuthorizationWizardModel.Step.SHIZUKU_GUIDANCE,
                "legacy MANAGE-only confirmation cannot prove CUSTOM was granted");
    }

    private static AvfCapabilityEvaluator.AvfReadiness readiness(
            AvfCapabilityEvaluator.AvfStatus status) {
        return switch (status) {
            case READY -> AvfCapabilityEvaluator.AvfReadiness.ready(
                    io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.CapabilityMatrix
                            .softwareVirtualizationBaseline());
            case NEEDS_AUTHORIZATION -> AvfCapabilityEvaluator.AvfReadiness.needsAuthorization(
                    List.of("需要虚拟机管理授权"));
            case UNSUPPORTED -> AvfCapabilityEvaluator.AvfReadiness.unsupported(List.of("不受支持"));
        };
    }

    private static void unsupportedReadinessNeverOpensTheWizard() {
        AvfAuthorizationWizardModel model = AvfAuthorizationWizardModel.fromAssessment(readiness(
                AvfCapabilityEvaluator.AvfStatus.UNSUPPORTED));

        assertTrue(model.step() == AvfAuthorizationWizardModel.Step.NOT_SHOWN,
                "unsupported device never sees the wizard");
        try {
            model.start();
            fail("start() must be forbidden while unsupported");
        } catch (IllegalStateException expected) {
            // required
        }
    }

    private static void readyReadinessNeedsNoWizardSteps() {
        AvfAuthorizationWizardModel model = AvfAuthorizationWizardModel.fromAssessment(readiness(
                AvfCapabilityEvaluator.AvfStatus.READY));

        assertTrue(model.step() == AvfAuthorizationWizardModel.Step.NOT_SHOWN,
                "an already-authorized device needs no wizard");
    }

    private static void needsAuthorizationStartsWithShizukuGuidance() {
        AvfAuthorizationWizardModel model = AvfAuthorizationWizardModel.fromAssessment(readiness(
                AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION));
        AvfAuthorizationWizardModel started = model.start();

        assertTrue(started.step() == AvfAuthorizationWizardModel.Step.SHIZUKU_GUIDANCE,
                "Shizuku guidance comes first per ADR-0003");
        try {
            started.start();
            fail("start() twice must be forbidden");
        } catch (IllegalStateException expected) {
            // required
        }
    }

    private static void adbFallbackRoundTrip() {
        AvfAuthorizationWizardModel started = AvfAuthorizationWizardModel.fromAssessment(readiness(
                        AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION))
                .start();
        AvfAuthorizationWizardModel adb = started.chooseAdbFallback();

        assertTrue(adb.step() == AvfAuthorizationWizardModel.Step.ADB_GUIDANCE,
                "ADB fallback step is reachable");
        AvfAuthorizationWizardModel back = adb.backToShizuku();
        assertTrue(back.step() == AvfAuthorizationWizardModel.Step.SHIZUKU_GUIDANCE,
                "the user can return to the Shizuku step");
    }

    private static void confirmAuthorizedOnlyAdvancesWhenPermissionIsGranted() {
        AvfAuthorizationWizardModel started = AvfAuthorizationWizardModel.fromAssessment(readiness(
                        AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION))
                .start();

        AvfAuthorizationWizardModel stillWaiting = started.confirmAuthorized(false);
        assertTrue(stillWaiting.step() == AvfAuthorizationWizardModel.Step.SHIZUKU_GUIDANCE,
                "a failed re-check stays on the current step");

        assertTrue(started.confirmAuthorized(true, false).step()
                        == AvfAuthorizationWizardModel.Step.SHIZUKU_GUIDANCE,
                "CUSTOM missing keeps guidance open");
        assertTrue(started.confirmAuthorized(false, true).step()
                        == AvfAuthorizationWizardModel.Step.SHIZUKU_GUIDANCE,
                "MANAGE missing keeps guidance open");
        AvfAuthorizationWizardModel authorized = started.confirmAuthorized(true, true);
        assertTrue(authorized.step() == AvfAuthorizationWizardModel.Step.AUTHORIZED,
                "a successful re-check completes the wizard");
    }

    private static void declineClosesTheWizard() {
        AvfAuthorizationWizardModel declined = AvfAuthorizationWizardModel.fromAssessment(readiness(
                        AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION))
                .start()
                .decline();

        assertTrue(declined.step() == AvfAuthorizationWizardModel.Step.CLOSED, "decline closes");
        try {
            declined.chooseAdbFallback();
            fail("a closed wizard must not transition again");
        } catch (IllegalStateException expected) {
            // required
        }
    }

    private static void adbGrantCommandIsExact() {
        String command = AvfAuthorizationWizardModel.adbGrantCommand(APPLICATION_ID);

        assertEquals(
                "adb shell pm grant " + APPLICATION_ID + " android.permission.MANAGE_VIRTUAL_MACHINE\n"
                        + "adb shell pm grant " + APPLICATION_ID + " android.permission.USE_CUSTOM_VIRTUAL_MACHINE",
                command,
                "copy-ready ADB grant command");
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            fail(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            fail(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void fail(String reason) {
        System.out.println("AVF_AUTHORIZATION_WIZARD_MODEL_TEST_FAIL reason=" + reason);
        System.exit(1);
    }
}
