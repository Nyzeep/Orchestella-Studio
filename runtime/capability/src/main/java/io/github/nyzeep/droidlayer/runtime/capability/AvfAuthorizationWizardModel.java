package io.github.nyzeep.droidlayer.runtime.capability;

import java.util.Objects;

/**
 * Immutable authorization wizard state machine (ADR-0003): detection first,
 * Shizuku guidance preferred, ADB command fallback with a copy-ready grant
 * command; unsupported devices never enter any guidance step.
 */
public final class AvfAuthorizationWizardModel {
    /** Wizard steps; NOT_SHOWN and AUTHORIZED/CLOSED are terminal entry/exit states. */
    public enum Step {
        NOT_SHOWN,
        SHIZUKU_GUIDANCE,
        ADB_GUIDANCE,
        AUTHORIZED,
        CLOSED
    }

    private static final String GRANT_COMMAND_TEMPLATE =
            "adb shell pm grant %s " + AvfCapabilityEvaluator.MANAGE_VIRTUAL_MACHINE_PERMISSION;

    private final Step step;
    private final boolean startable;

    private AvfAuthorizationWizardModel(Step step, boolean startable) {
        this.step = Objects.requireNonNull(step, "step");
        this.startable = startable;
    }

    /** Builds the initial model from one readiness verdict. */
    public static AvfAuthorizationWizardModel fromAssessment(
            AvfCapabilityEvaluator.AvfReadiness readiness) {
        Objects.requireNonNull(readiness, "readiness");
        boolean startable =
                readiness.status() == AvfCapabilityEvaluator.AvfStatus.NEEDS_AUTHORIZATION;
        return new AvfAuthorizationWizardModel(Step.NOT_SHOWN, startable);
    }

    public Step step() {
        return step;
    }

    /** Whether {@link #start()} may open the wizard for the current verdict. */
    public boolean canStart() {
        return step == Step.NOT_SHOWN && startable;
    }

    public AvfAuthorizationWizardModel start() {
        if (!canStart()) {
            throw new IllegalStateException("wizard cannot start in step " + step);
        }
        // ADR-0003: Shizuku first, ADB command fallback second.
        return new AvfAuthorizationWizardModel(Step.SHIZUKU_GUIDANCE, startable);
    }

    public AvfAuthorizationWizardModel chooseAdbFallback() {
        requireStep(Step.SHIZUKU_GUIDANCE, "chooseAdbFallback");
        return new AvfAuthorizationWizardModel(Step.ADB_GUIDANCE, startable);
    }

    public AvfAuthorizationWizardModel backToShizuku() {
        requireStep(Step.ADB_GUIDANCE, "backToShizuku");
        return new AvfAuthorizationWizardModel(Step.SHIZUKU_GUIDANCE, startable);
    }

    /**
     * Re-checks the permission after the user completes an authorization
     * path; a failed re-check stays on the current step.
     */
    public AvfAuthorizationWizardModel confirmAuthorized(boolean permissionNowGranted) {
        requireGuidanceStep("confirmAuthorized");
        if (!permissionNowGranted) {
            return this;
        }
        return new AvfAuthorizationWizardModel(Step.AUTHORIZED, startable);
    }

    public AvfAuthorizationWizardModel decline() {
        requireGuidanceStep("decline");
        return new AvfAuthorizationWizardModel(Step.CLOSED, startable);
    }

    /** The exact, copy-ready ADB grant command for this application id. */
    public static String adbGrantCommand(String applicationId) {
        Objects.requireNonNull(applicationId, "applicationId");
        if (applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId must not be blank");
        }
        return String.format(GRANT_COMMAND_TEMPLATE, applicationId);
    }

    private void requireGuidanceStep(String action) {
        if (step != Step.SHIZUKU_GUIDANCE && step != Step.ADB_GUIDANCE) {
            throw new IllegalStateException(action + " is forbidden in step " + step);
        }
    }

    private void requireStep(Step required, String action) {
        if (step != required) {
            throw new IllegalStateException(action + " is forbidden in step " + step);
        }
    }
}
