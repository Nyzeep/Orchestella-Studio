package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
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
        assertTrue(multiTier.contains("mirrors.aliyun.com/ubuntu/"));
        // Tier 2: TUNA
        assertTrue(multiTier.contains("mirrors.tuna.tsinghua.edu.cn/ubuntu/"));
        // Tier 3: Official
        assertTrue(multiTier.contains("archive.ubuntu.com/ubuntu/"));
    }

    @Test
    public void archMultiTierMirrorlistGeneration() {
        String multiTier = DistroMirrorProvisioner.generateMultiTierMirrorConfig("arch", "latest");
        assertTrue(multiTier.contains("Server = https://mirrors.aliyun.com/archlinux/$repo/os/$arch"));
        assertTrue(multiTier.contains("Server = https://mirrors.tuna.tsinghua.edu.cn/archlinux/$repo/os/$arch"));
        assertTrue(multiTier.contains("Server = https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch"));
        assertTrue(multiTier.contains("Server = https://geo.mirror.pkgbuild.com/$repo/os/$arch"));
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
        assertTrue(multiTier.contains("https://mirrors.aliyun.com/almalinux/"));
        assertTrue(multiTier.contains("https://mirrors.tuna.tsinghua.edu.cn/almalinux/"));
        assertTrue(multiTier.contains("https://repo.almalinux.org/almalinux/"));
    }

    @Test
    public void rockyLinuxMultiTierRepoGeneration() {
        String multiTier = DistroMirrorProvisioner.generateMultiTierMirrorConfig("rocky", "9");
        assertTrue(multiTier.contains("https://mirrors.aliyun.com/rocky/"));
        assertTrue(multiTier.contains("https://mirrors.tuna.tsinghua.edu.cn/rocky/"));
        assertTrue(multiTier.contains("https://dl.rockylinux.org/pub/rocky/"));
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
    public void resolveMirrorUrlsList() {
        List<String> urls = DistroMirrorProvisioner.resolveMirrorUrls("ubuntu", "24.04", DistroMirrorProvisioner.MirrorSource.MULTI_TIER);
        assertNotNull(urls);
        assertTrue(urls.size() >= 3);
        assertTrue(urls.contains("https://mirrors.aliyun.com/ubuntu/"));
        assertTrue(urls.contains("https://mirrors.tuna.tsinghua.edu.cn/ubuntu/"));
        assertTrue(urls.contains("http://archive.ubuntu.com/ubuntu/"));
    }
}
