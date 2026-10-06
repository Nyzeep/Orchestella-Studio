package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.droidlayer.runtime.distro.DistroManifest;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM tests for distro selection and manifest binding in Setup Wizard (Ticket 53, US-28).
 */
public final class SetupWizardDistroSelectionTest {

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
        wizard = new SetupWizardModel(profile, SetupWizardModel.Step.STEP_DISTRO, SetupWizardModel.DistroOption.ALPINE, null);
    }

    @Test
    public void alpineDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.ALPINE;
        assertEquals("alpine", option.getId());
        assertEquals("Alpine Linux", option.getDisplayName());
        assertEquals("3.24.1", option.getVersion());
        assertTrue(option.getTag().contains("极简轻量"));
        assertTrue(option.getDescription().contains("4MB"));
        assertEquals("alpine-minirootfs-3.24.1-aarch64.tar.gz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("alpine", manifest.id());
        assertEquals("3.24.1", manifest.version());
        assertEquals("aarch64", manifest.architecture());
        assertEquals(4_023_732L, manifest.sizeBytes());
    }

    @Test
    public void debianDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.DEBIAN;
        assertEquals("debian", option.getId());
        assertEquals("Debian Linux", option.getDisplayName());
        assertEquals("13", option.getVersion());
        assertTrue(option.getTag().contains("完整生态"));
        assertTrue(option.getDescription().contains("apt"));
        assertEquals("rootfs.tar.xz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("debian", manifest.id());
        assertEquals("13", manifest.version());
        assertEquals("aarch64", manifest.architecture());
        assertEquals(90_371_792L, manifest.sizeBytes());
    }

    @Test
    public void switchingDistroUpdatesSelectedManifest() {
        assertEquals(SetupWizardModel.DistroOption.ALPINE, wizard.getSelectedDistro());
        assertEquals("alpine", wizard.getSelectedManifest().id());

        wizard.selectDistro(SetupWizardModel.DistroOption.DEBIAN);
        assertEquals(SetupWizardModel.DistroOption.DEBIAN, wizard.getSelectedDistro());
        assertEquals("debian", wizard.getSelectedManifest().id());
        assertEquals("13", wizard.getSelectedManifest().version());

        wizard.selectDistro(SetupWizardModel.DistroOption.ALPINE);
        assertEquals(SetupWizardModel.DistroOption.ALPINE, wizard.getSelectedDistro());
        assertEquals("alpine", wizard.getSelectedManifest().id());
    }
}
