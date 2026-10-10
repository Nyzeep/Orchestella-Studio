package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.distro.DistroManifest;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure JVM tests for distro selection, manifest binding, and Alpine package suite in Setup Wizard.
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
        assertTrue(option.getTag().contains("极"));
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
        assertTrue(option.getTag().contains("稳如磐石") || option.getTag().contains("完整生态"));
        assertTrue(option.getDescription().contains("apt"));
        assertEquals("debian-13-rootfs.tar.xz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("debian", manifest.id());
        assertEquals("13", manifest.version());
        assertEquals("aarch64", manifest.architecture());
        assertEquals(90_332_396L, manifest.sizeBytes());
    }

    @Test
    public void ubuntuDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.UBUNTU;
        assertEquals("ubuntu", option.getId());
        assertEquals("Ubuntu Linux", option.getDisplayName());
        assertEquals("24.04", option.getVersion());
        assertTrue(option.getTag().contains("工业标准"));
        assertEquals("ubuntu-base-24.04-base-arm64.tar.gz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("ubuntu", manifest.id());
        assertEquals("24.04", manifest.version());
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
    public void fedoraDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.FEDORA;
        assertEquals("fedora", option.getId());
        assertEquals("Fedora Linux", option.getDisplayName());
        assertEquals("41", option.getVersion());
        assertTrue(option.getTag().contains("前沿创新"));
        assertEquals("Fedora-Container-Minimal-Base-41-aarch64.tar.xz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("fedora", manifest.id());
        assertEquals("41", manifest.version());
        assertEquals("aarch64", manifest.architecture());
    }

    @Test
    public void openSuseDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.OPENSUSE;
        assertEquals("opensuse", option.getId());
        assertEquals("openSUSE Tumbleweed", option.getDisplayName());
        assertEquals("tumbleweed", option.getVersion());
        assertTrue(option.getTag().contains("稳定滚更"));
        assertEquals("openSUSE-Tumbleweed-ARM-JeOS-rootfs.aarch64.tar.xz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("opensuse", manifest.id());
        assertEquals("tumbleweed", manifest.version());
        assertEquals("aarch64", manifest.architecture());
    }

    @Test
    public void kaliDistroManifestAndMetadata() {
        SetupWizardModel.DistroOption option = SetupWizardModel.DistroOption.KALI;
        assertEquals("kali", option.getId());
        assertEquals("Kali Linux", option.getDisplayName());
        assertEquals("rolling", option.getVersion());
        assertTrue(option.getTag().contains("渗透测试"));
        assertEquals("kali-linux-rolling-arm64-rootfs.tar.xz", option.getRootfsFileName());

        DistroManifest manifest = option.manifest();
        assertNotNull(manifest);
        assertEquals("kali", manifest.id());
        assertEquals("rolling", manifest.version());
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
        assertEquals(SetupWizardModel.DistroOption.ALPINE, wizard.getSelectedDistro());
        assertEquals("alpine", wizard.getSelectedManifest().id());

        wizard.selectDistro(SetupWizardModel.DistroOption.DEBIAN);
        assertEquals(SetupWizardModel.DistroOption.DEBIAN, wizard.getSelectedDistro());
        assertEquals("debian", wizard.getSelectedManifest().id());
        assertEquals("13", wizard.getSelectedManifest().version());

        wizard.selectDistro(SetupWizardModel.DistroOption.UBUNTU);
        assertEquals(SetupWizardModel.DistroOption.UBUNTU, wizard.getSelectedDistro());
        assertEquals("ubuntu", wizard.getSelectedManifest().id());

        wizard.selectDistro(SetupWizardModel.DistroOption.ARCH);
        assertEquals(SetupWizardModel.DistroOption.ARCH, wizard.getSelectedDistro());
        assertEquals("arch", wizard.getSelectedManifest().id());

        wizard.selectDistro(SetupWizardModel.DistroOption.ALPINE);
        assertEquals(SetupWizardModel.DistroOption.ALPINE, wizard.getSelectedDistro());
        assertEquals("alpine", wizard.getSelectedManifest().id());
    }

    @Test
    public void alpineEssentialPackageSuiteDefaultsAndToggle() {
        assertTrue(wizard.isAlpineEssentialSuiteEnabled());
        assertTrue(wizard.isAlpinePackageSelected("bash"));
        assertTrue(wizard.isAlpinePackageSelected("curl"));
        assertTrue(wizard.isAlpinePackageSelected("sudo"));

        String defaultScript = wizard.generateAlpineProvisioningScript();
        assertTrue(defaultScript.contains("apk add --no-cache"));
        assertTrue(defaultScript.contains("bash"));
        assertTrue(defaultScript.contains("chsh -s /bin/bash root"));

        // Toggle a package off
        wizard.toggleAlpinePackage("nano", false);
        assertFalse(wizard.isAlpinePackageSelected("nano"));
        assertFalse(wizard.generateAlpineProvisioningScript().contains("nano"));

        // Toggle essential suite completely off
        wizard.setAlpineEssentialSuiteEnabled(false);
        assertFalse(wizard.isAlpineEssentialSuiteEnabled());
        assertEquals("", wizard.generateAlpineProvisioningScript());
    }
}
