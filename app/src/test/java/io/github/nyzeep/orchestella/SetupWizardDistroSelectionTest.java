package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM tests for 7 distro options, manifest binding in Setup Wizard.
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
        wizard = new SetupWizardModel(profile, SetupWizardModel.Step.STEP_DISTRO, SetupWizardModel.DistroOption.UBUNTU, null);
    }

    @Test
    public void ubuntuDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.UBUNTU;
        assertEquals("ubuntu", option.getId());
        assertEquals("Ubuntu Linux", option.getDisplayName());
        assertEquals("24.04", option.getVersion());
        assertTrue(option.getTag().contains("工业标准"));
        assertEquals("ubuntu-base-24.04.5-base-arm64.tar.gz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("ubuntu", manifest.id());
        assertEquals("24.04", manifest.version());
        assertEquals("aarch64", manifest.architecture());
    }

    @Test
    public void debianDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.DEBIAN;
        assertEquals("debian", option.getId());
        assertEquals("Debian Linux", option.getDisplayName());
        assertEquals("13", option.getVersion());
        assertTrue(option.getTag().contains("稳如磐石") || option.getTag().contains("完整生态"));
        assertTrue(option.getDescription().contains("apt"));
        assertEquals("debian-13-rootfs.tar.xz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("debian", manifest.id());
        assertEquals("13", manifest.version());
        assertEquals("aarch64", manifest.architecture());
        assertEquals(90_351_092L, manifest.sizeBytes());
    }

    @Test
    public void fedoraDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.FEDORA;
        assertEquals("fedora", option.getId());
        assertEquals("Fedora Linux", option.getDisplayName());
        assertEquals("43", option.getVersion());
        assertTrue(option.getTag().contains("前沿创新"));
        assertEquals("fedora-43-minimal-rootfs.tar.gz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("fedora", manifest.id());
        assertEquals("43", manifest.version());
        assertEquals("aarch64", manifest.architecture());
    }

    @Test
    public void archDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.ARCH;
        assertEquals("arch", option.getId());
        assertEquals("Arch Linux ARM", option.getDisplayName());
        assertEquals("rolling", option.getVersion());
        assertTrue(option.getTag().contains("极客首选"));
        assertEquals("ArchLinuxARM-aarch64-latest.tar.gz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("arch", manifest.id());
        assertEquals("aarch64", manifest.architecture());
    }

    @Test
    public void openSuseDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.OPENSUSE;
        assertEquals("opensuse", option.getId());
        assertEquals("openSUSE Leap", option.getDisplayName());
        assertEquals("15.6", option.getVersion());
        assertTrue(option.getTag().contains("严谨规范"));
        assertEquals("opensuse-leap-image.aarch64-15.6.0-lxc-Build19.146.tar.xz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("opensuse", manifest.id());
        assertEquals("15.6", manifest.version());
        assertEquals("aarch64", manifest.architecture());
    }

    @Test
    public void almaLinuxDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.ALMALINUX;
        assertEquals("almalinux", option.getId());
        assertEquals("AlmaLinux", option.getDisplayName());
        assertEquals("9", option.getVersion());
        assertTrue(option.getTag().contains("企业就绪"));
        assertEquals("AlmaLinux-9-Base-rootfs.tar.gz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("almalinux", manifest.id());
        assertEquals("9", manifest.version());
        assertEquals("aarch64", manifest.architecture());
    }

    @Test
    public void rockyDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.ROCKY;
        assertEquals("rocky", option.getId());
        assertEquals("Rocky Linux", option.getDisplayName());
        assertEquals("9.6", option.getVersion());
        assertTrue(option.getTag().contains("坚如磐石"));
        assertEquals("Rocky-9-Container-Base-9.6-20250531.0.aarch64.tar.xz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("rocky", manifest.id());
        assertEquals("9.6", manifest.version());
        assertEquals("aarch64", manifest.architecture());
    }

    @Test
    public void sevenDistroOptionsExistAndCanBeLookedUpById() {
        assertEquals(7, SetupWizardModel.DistroOption.values().length);
        for (SetupWizardModel.DistroOption option : SetupWizardModel.DistroOption.values()) {
            assertEquals(option, SetupWizardModel.DistroOption.Companion.fromId(option.getId()));
        }
    }

    @Test
    public void switchingDistroUpdatesSelectedManifest() {
        assertEquals(SetupWizardModel.DistroOption.UBUNTU, wizard.getSelectedDistro());
        assertEquals("ubuntu", wizard.getSelectedManifest().id());

        wizard.selectDistro(SetupWizardModel.DistroOption.DEBIAN);
        assertEquals(SetupWizardModel.DistroOption.DEBIAN, wizard.getSelectedDistro());
        assertEquals("debian", wizard.getSelectedManifest().id());
        assertEquals("13", wizard.getSelectedManifest().version());

        wizard.selectDistro(SetupWizardModel.DistroOption.FEDORA);
        assertEquals(SetupWizardModel.DistroOption.FEDORA, wizard.getSelectedDistro());
        assertEquals("fedora", wizard.getSelectedManifest().id());

        wizard.selectDistro(SetupWizardModel.DistroOption.ARCH);
        assertEquals(SetupWizardModel.DistroOption.ARCH, wizard.getSelectedDistro());
        assertEquals("arch", wizard.getSelectedManifest().id());

        wizard.selectDistro(SetupWizardModel.DistroOption.OPENSUSE);
        assertEquals(SetupWizardModel.DistroOption.OPENSUSE, wizard.getSelectedDistro());
        assertEquals("opensuse", wizard.getSelectedManifest().id());

        wizard.selectDistro(SetupWizardModel.DistroOption.ALMALINUX);
        assertEquals(SetupWizardModel.DistroOption.ALMALINUX, wizard.getSelectedDistro());
        assertEquals("almalinux", wizard.getSelectedManifest().id());

        wizard.selectDistro(SetupWizardModel.DistroOption.ROCKY);
        assertEquals(SetupWizardModel.DistroOption.ROCKY, wizard.getSelectedDistro());
        assertEquals("rocky", wizard.getSelectedManifest().id());
    }
}
