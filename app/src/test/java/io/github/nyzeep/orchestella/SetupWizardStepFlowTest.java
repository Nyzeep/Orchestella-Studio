package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM tests for Setup Wizard step progression and regression state machine (Ticket 53, US-28).
 */
public final class SetupWizardStepFlowTest {

    private HostHardwareProfile profile;
    private SetupWizardModel wizard;

    @Before
    public void setUp() {
        profile = new HostHardwareProfile(
                8,
                8L * 1024L * 1024L * 1024L,
                4L * 1024L * 1024L * 1024L,
                512L * 1024L * 1024L,
                false);
        wizard = new SetupWizardModel(profile, SetupWizardModel.Step.STEP_DETECT, SetupWizardModel.DistroOption.UBUNTU, null);
    }

    @Test
    public void normalThreeStepProgressionFlow() {
        // Step 1: Detect
        assertEquals(SetupWizardModel.Step.STEP_DETECT, wizard.getCurrentStep());
        assertEquals(1, wizard.getStepIndex());
        assertFalse(wizard.canGoBack());
        assertTrue(wizard.canGoNext());

        // Step 1 -> Step 2
        SetupWizardModel.Step next = wizard.nextStep();
        assertEquals(SetupWizardModel.Step.STEP_DISTRO, next);
        assertEquals(2, wizard.getStepIndex());
        assertTrue(wizard.canGoBack());
        assertTrue(wizard.canGoNext());

        // Step 2 -> Step 3
        next = wizard.nextStep();
        assertEquals(SetupWizardModel.Step.STEP_CONFIG, next);
        assertEquals(3, wizard.getStepIndex());
        assertTrue(wizard.canGoBack());
        assertFalse(wizard.canGoNext());

        // Cannot advance past Step 3
        try {
            wizard.nextStep();
            fail("Must throw when advancing beyond Step 3");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Cannot advance"));
        }
    }

    @Test
    public void stepBackwardFlow() {
        wizard.goToStep(SetupWizardModel.Step.STEP_CONFIG);
        assertEquals(SetupWizardModel.Step.STEP_CONFIG, wizard.getCurrentStep());

        SetupWizardModel.Step prev = wizard.previousStep();
        assertEquals(SetupWizardModel.Step.STEP_DISTRO, prev);

        prev = wizard.previousStep();
        assertEquals(SetupWizardModel.Step.STEP_DETECT, prev);

        try {
            wizard.previousStep();
            fail("Must throw when retreating before Step 1");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Cannot retreat"));
        }
    }

    @Test
    public void downloadingLocksStepTransitions() {
        wizard.goToStep(SetupWizardModel.Step.STEP_CONFIG);
        wizard.setDownloading(true);

        assertFalse("Cannot go back while downloading", wizard.canGoBack());
        assertFalse("Cannot go next while downloading", wizard.canGoNext());

        // goToStep ignored while downloading
        wizard.goToStep(SetupWizardModel.Step.STEP_DETECT);
        assertEquals(SetupWizardModel.Step.STEP_CONFIG, wizard.getCurrentStep());
    }
}
