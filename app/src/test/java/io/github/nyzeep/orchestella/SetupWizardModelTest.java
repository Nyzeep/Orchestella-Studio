package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM tests for SetupWizardModel state machine and hardware allocation (Ticket 53, US-28).
 */
public final class SetupWizardModelTest {

    private HostHardwareProfile profile;
    private SetupWizardModel wizard;

    @Before
    public void setUp() {
        // Mock a typical modern phone: 8 cores, 8GB total RAM, 4GB available RAM
        profile = new HostHardwareProfile(
                8,
                8L * 1024L * 1024L * 1024L,
                4L * 1024L * 1024L * 1024L,
                512L * 1024L * 1024L,
                false);
        wizard = new SetupWizardModel(profile, SetupWizardModel.Step.STEP_DETECT, SetupWizardModel.DistroOption.ALPINE, null);
    }

    @Test
    public void initialPropertiesConformToHostProfile() {
        assertEquals(SetupWizardModel.Step.STEP_DETECT, wizard.getCurrentStep());
        assertEquals(1, wizard.getStepIndex());
        assertEquals(3, wizard.getTotalSteps());
        assertEquals(SetupWizardModel.DistroOption.ALPINE, wizard.getSelectedDistro());

        // Recommended config derived automatically: 7 vCPUs (8 - 1), recommended RAM
        VmHardwareConfig config = wizard.getHardwareConfig();
        assertNotNull(config);
        assertEquals(7, config.cpuCores());
        assertEquals(profile.recommendedRamMegabytes(), config.ramMegabytes());
        assertEquals(VmHardwareConfig.DEFAULT_DISK_CAPACITY_BYTES, config.diskCapacityBytes());
        assertTrue(config.gpuAccelerationEnabled());

        assertEquals(HostHardwareProfile.SafetyZone.GREEN, wizard.safetyZone());
        assertFalse(wizard.isDownloading());
        assertFalse(wizard.isCompleted());
    }

    @Test
    public void updatingHardwareConfigReflectsInModelAndCalculatesSafetyZone() {
        wizard.updateCpuCores(4);
        assertEquals(4, wizard.getHardwareConfig().cpuCores());

        wizard.updateRamMegabytes(2048);
        assertEquals(2048, wizard.getHardwareConfig().ramMegabytes());
        assertEquals(HostHardwareProfile.SafetyZone.GREEN, wizard.safetyZone());

        wizard.updateDiskCapacityBytes(16L * 1024L * 1024L * 1024L);
        assertEquals(16L * 1024L * 1024L * 1024L, wizard.getHardwareConfig().diskCapacityBytes());

        wizard.updateGpuAcceleration(false);
        assertFalse(wizard.getHardwareConfig().gpuAccelerationEnabled());
    }

    @Test
    public void excessiveRamAllocationEntersRedZone() {
        // Set RAM higher than totalMem - 2048MB, e.g. 7000MB on an 8192MB device
        wizard.updateRamMegabytes(7000);
        assertEquals(HostHardwareProfile.SafetyZone.RED, wizard.safetyZone());
    }

    @Test
    public void markCompletedUpdatesState() {
        wizard.setDownloading(true);
        assertTrue(wizard.isDownloading());
        assertFalse(wizard.isCompleted());

        wizard.markCompleted();
        assertFalse(wizard.isDownloading());
        assertTrue(wizard.isCompleted());
    }

    @Test
    public void hardwareSpecsSummaryFormatting() {
        wizard.updateCpuCores(4);
        wizard.updateRamMegabytes(3072);
        wizard.updateDiskCapacityBytes(8L * 1024L * 1024L * 1024L);
        wizard.updateGpuAcceleration(true);

        assertEquals("4 vCPU · 3072 MB · 8 GB · GPU 加速", wizard.hardwareSpecsSummary());
    }

    @Test
    public void defaultUserConfigDefaultsToRootAndOrchestellaBrand() {
        assertEquals("root", wizard.getUserConfig().username());
        assertEquals("1234", wizard.getUserConfig().password());
        assertEquals("orchestella", wizard.getUserConfig().hostname());
        assertTrue(wizard.getUserConfig().hasSudo());
        assertFalse(wizard.isCustomUserEnabled());
    }

    @Test
    public void enableCustomUserValidatesUsernameAndSetsState() {
        assertFalse(wizard.enableCustomUser("9invalid", "pass", true, "workstation"));
        assertFalse(wizard.isCustomUserEnabled());

        assertTrue(wizard.enableCustomUser("dev", "DevSecret2026", true, "workstation"));
        assertTrue(wizard.isCustomUserEnabled());
        assertEquals("dev", wizard.getUserConfig().username());
        assertEquals("DevSecret2026", wizard.getUserConfig().password());
        assertEquals("workstation", wizard.getUserConfig().hostname());
        assertTrue(wizard.getUserConfig().hasSudo());

        // Skip resets back to defaultRoot
        wizard.skipToDefaultRoot();
        assertFalse(wizard.isCustomUserEnabled());
        assertEquals("root", wizard.getUserConfig().username());
        assertEquals("1234", wizard.getUserConfig().password());
        assertEquals("orchestella", wizard.getUserConfig().hostname());
    }
}
