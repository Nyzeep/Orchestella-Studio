package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * TDD Unit tests for DistroMirrorProvisioner (Ticket 01).
 * Verifies mirror URL resolution, package manager config text generation for Debian, Alpine, Arch,
 * and target config path determination.
 */
public class DistroMirrorProvisionerTest {

    @Test
    public void targetConfigPathsAreCorrect() {
        assertEquals("/etc/apk/repositories", DistroMirrorProvisioner.getTargetConfigPath("alpine"));
        assertEquals("/etc/apt/sources.list", DistroMirrorProvisioner.getTargetConfigPath("debian"));
        assertEquals("/etc/apt/sources.list", DistroMirrorProvisioner.getTargetConfigPath("ubuntu"));
        assertEquals("/etc/apt/sources.list", DistroMirrorProvisioner.getTargetConfigPath("kali"));
        assertEquals("/etc/pacman.d/mirrorlist", DistroMirrorProvisioner.getTargetConfigPath("arch"));
    }

    @Test
    public void alpineOfficialAndMirrorsRepositoriesGeneration() {
        // Alpine Official
        String official = DistroMirrorProvisioner.generateMirrorConfig("alpine", "3.24.1", DistroMirrorProvisioner.MirrorSource.OFFICIAL);
        assertTrue(official.contains("https://dl-cdn.alpinelinux.org/alpine/v3.24/main"));
        assertTrue(official.contains("https://dl-cdn.alpinelinux.org/alpine/v3.24/community"));

        // Alpine TUNA
        String tuna = DistroMirrorProvisioner.generateMirrorConfig("alpine", "3.24.1", DistroMirrorProvisioner.MirrorSource.TUNA);
        assertTrue(tuna.contains("https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/main"));
        assertTrue(tuna.contains("https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/community"));

        // Alpine USTC
        String ustc = DistroMirrorProvisioner.generateMirrorConfig("alpine", "3.24.1", DistroMirrorProvisioner.MirrorSource.USTC);
        assertTrue(ustc.contains("https://mirrors.ustc.edu.cn/alpine/v3.24/main"));
        assertTrue(ustc.contains("https://mirrors.ustc.edu.cn/alpine/v3.24/community"));

        // Alpine ALIYUN
        String aliyun = DistroMirrorProvisioner.generateMirrorConfig("alpine", "3.24.1", DistroMirrorProvisioner.MirrorSource.ALIYUN);
        assertTrue(aliyun.contains("https://mirrors.aliyun.com/alpine/v3.24/main"));
        assertTrue(aliyun.contains("https://mirrors.aliyun.com/alpine/v3.24/community"));
    }

    @Test
    public void debianOfficialAndMirrorsSourcesListGeneration() {
        // Debian Official
        String official = DistroMirrorProvisioner.generateMirrorConfig("debian", "13", DistroMirrorProvisioner.MirrorSource.OFFICIAL);
        assertTrue(official.contains("http://deb.debian.org/debian/ trixie main contrib non-free non-free-firmware"));
        assertTrue(official.contains("http://security.debian.org/debian-security trixie-security main contrib non-free non-free-firmware"));

        // Debian TUNA
        String tuna = DistroMirrorProvisioner.generateMirrorConfig("debian", "13", DistroMirrorProvisioner.MirrorSource.TUNA);
        assertTrue(tuna.contains("https://mirrors.tuna.tsinghua.edu.cn/debian/ trixie main contrib non-free non-free-firmware"));
        assertTrue(tuna.contains("https://mirrors.tuna.tsinghua.edu.cn/debian-security trixie-security main contrib non-free non-free-firmware"));

        // Debian USTC
        String ustc = DistroMirrorProvisioner.generateMirrorConfig("debian", "13", DistroMirrorProvisioner.MirrorSource.USTC);
        assertTrue(ustc.contains("https://mirrors.ustc.edu.cn/debian/ trixie main contrib non-free non-free-firmware"));
        assertTrue(ustc.contains("https://mirrors.ustc.edu.cn/debian-security trixie-security main contrib non-free non-free-firmware"));

        // Debian ALIYUN
        String aliyun = DistroMirrorProvisioner.generateMirrorConfig("debian", "13", DistroMirrorProvisioner.MirrorSource.ALIYUN);
        assertTrue(aliyun.contains("https://mirrors.aliyun.com/debian/ trixie main contrib non-free non-free-firmware"));
        assertTrue(aliyun.contains("https://mirrors.aliyun.com/debian-security trixie-security main contrib non-free non-free-firmware"));
    }

    @Test
    public void archMirrorlistGeneration() {
        String tuna = DistroMirrorProvisioner.generateMirrorConfig("arch", "latest", DistroMirrorProvisioner.MirrorSource.TUNA);
        assertTrue(tuna.contains("Server = https://mirrors.tuna.tsinghua.edu.cn/archlinux/$repo/os/$arch"));

        String ustc = DistroMirrorProvisioner.generateMirrorConfig("arch", "latest", DistroMirrorProvisioner.MirrorSource.USTC);
        assertTrue(ustc.contains("Server = https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch"));
    }

    @Test
    public void resolveMirrorUrlsList() {
        List<String> urls = DistroMirrorProvisioner.resolveMirrorUrls("alpine", "3.24.1", DistroMirrorProvisioner.MirrorSource.TUNA);
        assertNotNull(urls);
        assertEquals(2, urls.size());
        assertEquals("https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/main", urls.get(0));
        assertEquals("https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/community", urls.get(1));
    }
}
