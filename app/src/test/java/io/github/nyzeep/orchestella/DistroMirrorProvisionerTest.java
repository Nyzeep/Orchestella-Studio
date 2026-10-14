package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * TDD Unit tests for DistroMirrorProvisioner (Phase 5.0 R2).
 * Verifies multi-tier and single-source mirror configuration generation for all 7 official distros,
 * Docker daemon mirror generation, and config path resolution.
 */
public class DistroMirrorProvisionerTest {

    @Test
    public void targetConfigPathsAreCorrectForAllSevenDistros() {
        assertEquals("/etc/apt/sources.list", DistroMirrorProvisioner.getTargetConfigPath("ubuntu"));
        assertEquals("/etc/apt/sources.list", DistroMirrorProvisioner.getTargetConfigPath("debian"));
        assertEquals("/etc/yum.repos.d/fedora.repo", DistroMirrorProvisioner.getTargetConfigPath("fedora"));
        assertEquals("/etc/pacman.d/mirrorlist", DistroMirrorProvisioner.getTargetConfigPath("arch"));
        assertEquals("/etc/zypp/repos.d/opensuse.repo", DistroMirrorProvisioner.getTargetConfigPath("opensuse"));
        assertEquals("/etc/yum.repos.d/almalinux.repo", DistroMirrorProvisioner.getTargetConfigPath("almalinux"));
        assertEquals("/etc/yum.repos.d/rocky.repo", DistroMirrorProvisioner.getTargetConfigPath("rocky"));
        assertEquals("/etc/docker/daemon.json", DistroMirrorProvisioner.getDockerConfigPath());
    }

    @Test
    public void debianMultiTierSourcesListGeneration() {
        String multiTier = DistroMirrorProvisioner.generateMultiTierMirrorConfig("debian", "13");
        // Tier 1: Aliyun Cloud
        assertTrue(multiTier.contains("mirrors.aliyun.com/debian/"));
        // Tier 2: Tsinghua TUNA
        assertTrue(multiTier.contains("mirrors.tuna.tsinghua.edu.cn/debian/"));
        // Tier 3: Official
        assertTrue(multiTier.contains("deb.debian.org/debian/"));
    }

    @Test
    public void ubuntuMultiTierSourcesListGeneration() {
        String multiTier = DistroMirrorProvisioner.generateMultiTierMirrorConfig("ubuntu", "24.04");
        // Tier 1: Aliyun
        assertTrue(multiTier.contains("mirrors.aliyun.com/ubuntu-ports/"));
        // Tier 2: TUNA
        assertTrue(multiTier.contains("mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/"));
        // Tier 3: Official
        assertTrue(multiTier.contains("ports.ubuntu.com/ubuntu-ports/"));
        assertTrue(multiTier.contains("noble-security"));
        assertFalse(multiTier.contains("archive.ubuntu.com"));
    }

    @Test
    public void archMultiTierMirrorlistGeneration() {
        String multiTier = DistroMirrorProvisioner.generateMultiTierMirrorConfig("arch", "latest");
        assertTrue(multiTier.contains("Server = https://mirrors.aliyun.com/archlinuxarm/$arch/$repo"));
        assertTrue(multiTier.contains("Server = https://mirrors.tuna.tsinghua.edu.cn/archlinuxarm/$arch/$repo"));
        assertTrue(multiTier.contains("Server = https://mirrors.ustc.edu.cn/archlinuxarm/$arch/$repo"));
        assertTrue(multiTier.contains("Server = https://ca.us.mirror.archlinuxarm.org/$arch/$repo"));
    }

    @Test
    public void fedoraMultiTierRepoGeneration() {
        String multiTier = DistroMirrorProvisioner.generateMultiTierMirrorConfig("fedora", "41");
        assertTrue(multiTier.contains("https://mirrors.aliyun.com/fedora/releases/"));
        assertTrue(multiTier.contains("https://mirrors.tuna.tsinghua.edu.cn/fedora/releases/"));
        assertTrue(multiTier.contains("https://download.fedoraproject.org/pub/fedora/linux/releases/"));
    }

    @Test
    public void openSuseMultiTierRepoGeneration() {
        String multiTier = DistroMirrorProvisioner.generateMultiTierMirrorConfig("opensuse", "tumbleweed");
        assertTrue(multiTier.contains("https://mirrors.aliyun.com/opensuse/tumbleweed/repo/oss/"));
        assertTrue(multiTier.contains("https://mirrors.tuna.tsinghua.edu.cn/opensuse/tumbleweed/repo/oss/"));
        assertTrue(multiTier.contains("https://download.opensuse.org/tumbleweed/repo/oss/"));
    }

    @Test
    public void almaLinuxMultiTierRepoGeneration() {
        String multiTier = DistroMirrorProvisioner.generateMultiTierMirrorConfig("almalinux", "9");
        assertTrue(multiTier.contains("https://repo.almalinux.org/almalinux/"));
        assertTrue(multiTier.contains("$releasever/BaseOS/$basearch/os/"));
        assertTrue(multiTier.contains("$releasever/AppStream/$basearch/os/"));
        assertTrue(multiTier.contains("gpgcheck=1"));
        assertTrue(multiTier.contains("gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-AlmaLinux-9"));
    }

    @Test
    public void rockyLinuxMultiTierRepoGeneration() {
        String multiTier = DistroMirrorProvisioner.generateMultiTierMirrorConfig("rocky", "9");
        assertTrue(multiTier.contains("https://dl.rockylinux.org/pub/rocky/"));
        assertTrue(multiTier.contains("$releasever/BaseOS/$basearch/os/"));
        assertTrue(multiTier.contains("$releasever/AppStream/$basearch/os/"));
        assertTrue(multiTier.contains("gpgcheck=1"));
        assertTrue(multiTier.contains("gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-Rocky-9"));
    }

    @Test
    public void dockerDaemonJsonGeneration() {
        String json = DistroMirrorProvisioner.generateDockerDaemonJson();
        assertTrue(json.contains("registry-mirrors"));
        assertTrue(json.contains("https://docker.xuanyuan.me"));
        assertTrue(json.contains("https://docker.1ms.run"));
        assertTrue(json.contains("https://docker.m.daocloud.io"));
        assertTrue(json.contains("https://registry-1.docker.io"));
    }

    @Test
    public void armRepositoriesRejectX86LayoutAndKeepSignatureChecks() {
        String ubuntu = DistroMirrorProvisioner.generateMirrorConfig("ubuntu", "22.04", DistroMirrorProvisioner.MirrorSource.OFFICIAL);
        assertTrue(ubuntu.contains("ports.ubuntu.com/ubuntu-ports/ jammy"));
        assertTrue(ubuntu.contains("jammy-security"));
        assertFalse(ubuntu.contains("archive.ubuntu.com"));
        assertFalse(ubuntu.contains("trusted=yes"));
        String arch = DistroMirrorProvisioner.generateMirrorConfig("arch", "latest", DistroMirrorProvisioner.MirrorSource.OFFICIAL);
        assertTrue(arch.contains("ca.us.mirror.archlinuxarm.org/$arch/$repo"));
        assertFalse(arch.contains("$repo/os/$arch"));
        assertFalse(arch.contains("SigLevel = Never"));
    }

    @Test
    public void leapCatalogIsNotSilentlyChangedToTumbleweed() {
        String leap = DistroMirrorProvisioner.generateMirrorConfig("opensuse", "16.0", DistroMirrorProvisioner.MirrorSource.MULTI_TIER);
        assertTrue(leap.contains("baseurl=https://cdn.opensuse.org/distribution/leap/16.0/repo/oss/$basearch/"));
        assertTrue(leap.contains("gpgkey=https://cdn.opensuse.org/distribution/leap/16.0/repo/oss/$basearch/repodata/repomd.xml.key"));
        assertFalse(leap.contains("tumbleweed"));
        assertTrue(leap.contains("gpgcheck=1"));
        assertFalse(leap.contains("gpgcheck=0"));
    }

    @Test
    public void rpmSingleMirrorsIncludeBaseDependenciesAndReleaseSignatureKeys() {
        for (DistroMirrorProvisioner.MirrorSource source : DistroMirrorProvisioner.MirrorSource.values()) {
            for (String distro : new String[] {"almalinux", "rocky"}) {
                String repo = DistroMirrorProvisioner.generateMirrorConfig(distro, "9", source);
                assertTrue(repo.contains("$releasever/BaseOS/$basearch/os/"));
                assertTrue(repo.contains("$releasever/AppStream/$basearch/os/"));
                assertTrue(repo.contains("gpgcheck=1"));
                assertTrue(repo.contains("gpgkey=file:///etc/pki/rpm-gpg/"));
                assertFalse(repo.contains("gpgcheck=0"));
            }
            String fedora = DistroMirrorProvisioner.generateMirrorConfig("fedora", "43", source);
            assertTrue(fedora.contains("[updates]"));
            assertTrue(fedora.contains("/updates/$releasever/Everything/$basearch/"));
            assertTrue(fedora.contains("gpgkey=file:///etc/pki/rpm-gpg/RPM-GPG-KEY-fedora-$releasever-$basearch"));
        }
    }

    @Test
    public void alpineTargetAndBranchMatchInstalledDistribution() {
        assertEquals("/etc/apk/repositories", DistroMirrorProvisioner.getTargetConfigPath("alpine"));
        String alpine = DistroMirrorProvisioner.generateMirrorConfig("alpine", "3.23.6", DistroMirrorProvisioner.MirrorSource.OFFICIAL);
        assertTrue(alpine.contains("/v3.23/main"));
        assertTrue(alpine.contains("/v3.23/community"));
        assertFalse(alpine.contains("/edge/"));
    }

    @Test
    public void resolveMirrorUrlsList() {
        List<String> urls = DistroMirrorProvisioner.resolveMirrorUrls("ubuntu", "24.04", DistroMirrorProvisioner.MirrorSource.MULTI_TIER);
        assertNotNull(urls);
        assertTrue(urls.size() >= 3);
        assertTrue(urls.contains("https://mirrors.aliyun.com/ubuntu-ports/"));
        assertTrue(urls.contains("https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/"));
        assertTrue(urls.contains("http://ports.ubuntu.com/ubuntu-ports/"));
    }
}
