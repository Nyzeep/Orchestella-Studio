package io.github.nyzeep.orchestella;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure JVM provisioner for generating Linux package manager and container mirror configurations (ADR-0006, Phase 5.0 R2).
 *
 * <p>Supports generating multi-tier fallback and single-source configurations for all 7 official distros:
 * <ul>
 *   <li>Ubuntu LTS: {@code /etc/apt/sources.list} (Tier 1 Aliyun -> Tier 2 TUNA/USTC -> Tier 3 Official)</li>
 *   <li>Debian Stable: {@code /etc/apt/sources.list} (Tier 1 Aliyun -> Tier 2 TUNA/USTC -> Tier 3 Official)</li>
 *   <li>Fedora Workstation: {@code /etc/yum.repos.d/fedora.repo} (DNF Multi-baseurl)</li>
 *   <li>Arch Linux: {@code /etc/pacman.d/mirrorlist} (PACMAN Multi-tier Server list)</li>
 *   <li>openSUSE Tumbleweed: {@code /etc/zypp/repos.d/opensuse.repo} (ZYPPER Multi-baseurl)</li>
 *   <li>AlmaLinux: {@code /etc/yum.repos.d/almalinux.repo} (DNF Enterprise RHEL-compatible)</li>
 *   <li>Rocky Linux: {@code /etc/yum.repos.d/rocky.repo} (DNF Enterprise RHEL-compatible)</li>
 *   <li>Docker Daemon: {@code /etc/docker/daemon.json} (High-performance multi-registry mirrors)</li>
 * </ul>
 */
public final class DistroMirrorProvisioner {

    public enum MirrorSource {
        MULTI_TIER("多层级自动回退源 (国内云源 -> 教育网 -> 官方源 推荐)"),
        ALIYUN("阿里云开源镜像站 (Aliyun Public - 国内云镜像)"),
        TUNA("清华大学开源软件镜像站 (Tsinghua TUNA - 教育网骨干)"),
        USTC("中国科学技术大学镜像站 (USTC - 教育网骨干)"),
        HUAWEICLOUD("华为云开源镜像站 (Huawei Cloud)"),
        TENCENT("腾讯云开源镜像站 (Tencent Cloud)"),
        OFFICIAL("官方上游默认源 (Official Upstream)");

        private final String displayName;

        MirrorSource(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    private DistroMirrorProvisioner() {
    }

    public static String getDockerConfigPath() {
        return "/etc/docker/daemon.json";
    }

    public static String getTargetConfigPath(String distroId) {
        if (distroId == null) {
            return "/etc/apt/sources.list";
        }
        String id = distroId.trim().toLowerCase(Locale.ROOT);
        switch (id) {
            case "arch":
                return "/etc/pacman.d/mirrorlist";
            case "fedora":
                return "/etc/yum.repos.d/fedora.repo";
            case "opensuse":
                return "/etc/zypp/repos.d/opensuse.repo";
            case "almalinux":
                return "/etc/yum.repos.d/almalinux.repo";
            case "rocky":
                return "/etc/yum.repos.d/rocky.repo";
            case "ubuntu":
            case "debian":
            default:
                return "/etc/apt/sources.list";
        }
    }

    public static String getConfigFileName(String distroId) {
        String path = getTargetConfigPath(distroId);
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    public static String generateDockerDaemonJson() {
        return "{\n"
                + "  \"registry-mirrors\": [\n"
                + "    \"https://docker.xuanyuan.me\",\n"
                + "    \"https://docker.1ms.run\",\n"
                + "    \"https://docker.m.daocloud.io\",\n"
                + "    \"https://registry-1.docker.io\"\n"
                + "  ]\n"
                + "}\n";
    }

    public static String generateConfig(String distroId, MirrorSource source) {
        return generateMirrorConfig(distroId, "latest", source);
    }

    public static String generateMirrorConfig(String distroId, MirrorSource source) {
        return generateMirrorConfig(distroId, "latest", source);
    }

    public static String generateMultiTierMirrorConfig(String distroId, String distroVersion) {
        return generateMirrorConfig(distroId, distroVersion, MirrorSource.MULTI_TIER);
    }

    public static String generateMirrorConfig(String distroId, String distroVersion, MirrorSource source) {
        Objects.requireNonNull(source, "source");
        String id = distroId != null ? distroId.trim().toLowerCase(Locale.ROOT) : "debian";
        switch (id) {
            case "arch":
                return generateArchMirrorlist(source);
            case "ubuntu":
                return generateUbuntuSources(distroVersion, source);
            case "fedora":
                return generateFedoraRepo(distroVersion, source);
            case "opensuse":
                return generateOpenSuseRepo(source);
            case "almalinux":
                return generateAlmaLinuxRepo(distroVersion, source);
            case "rocky":
                return generateRockyLinuxRepo(distroVersion, source);
            case "debian":
            default:
                return generateDebianSources(distroVersion, source);
        }
    }

    public static List<String> resolveMirrorUrls(String distroId, String distroVersion, MirrorSource source) {
        Objects.requireNonNull(source, "source");
        String id = distroId != null ? distroId.trim().toLowerCase(Locale.ROOT) : "debian";
        List<String> list = new ArrayList<>();
        switch (id) {
            case "arch":
                if (source == MirrorSource.MULTI_TIER) {
                    list.add("https://mirrors.aliyun.com/archlinux/$repo/os/$arch");
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/archlinux/$repo/os/$arch");
                    list.add("https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch");
                    list.add("https://geo.mirror.pkgbuild.com/$repo/os/$arch");
                } else if (source == MirrorSource.TUNA) {
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/archlinux/$repo/os/$arch");
                } else if (source == MirrorSource.USTC) {
                    list.add("https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch");
                } else if (source == MirrorSource.ALIYUN) {
                    list.add("https://mirrors.aliyun.com/archlinux/$repo/os/$arch");
                } else {
                    list.add("https://geo.mirror.pkgbuild.com/$repo/os/$arch");
                }
                break;
            case "ubuntu":
                if (source == MirrorSource.MULTI_TIER) {
                    list.add("https://mirrors.aliyun.com/ubuntu/");
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/ubuntu/");
                    list.add("http://archive.ubuntu.com/ubuntu/");
                } else if (source == MirrorSource.TUNA) {
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/ubuntu/");
                } else if (source == MirrorSource.USTC) {
                    list.add("https://mirrors.ustc.edu.cn/ubuntu/");
                } else if (source == MirrorSource.ALIYUN) {
                    list.add("https://mirrors.aliyun.com/ubuntu/");
                } else {
                    list.add("http://archive.ubuntu.com/ubuntu/");
                }
                break;
            case "fedora":
                if (source == MirrorSource.MULTI_TIER) {
                    list.add("https://mirrors.aliyun.com/fedora/");
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/fedora/");
                    list.add("https://download.fedoraproject.org/pub/fedora/linux/");
                } else if (source == MirrorSource.TUNA) {
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/fedora/");
                } else if (source == MirrorSource.USTC) {
                    list.add("https://mirrors.ustc.edu.cn/fedora/");
                } else if (source == MirrorSource.ALIYUN) {
                    list.add("https://mirrors.aliyun.com/fedora/");
                } else {
                    list.add("https://download.fedoraproject.org/pub/fedora/linux/");
                }
                break;
            case "opensuse":
                if (source == MirrorSource.MULTI_TIER) {
                    list.add("https://mirrors.aliyun.com/opensuse/");
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/opensuse/");
                    list.add("https://download.opensuse.org/");
                } else if (source == MirrorSource.TUNA) {
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/opensuse/");
                } else if (source == MirrorSource.USTC) {
                    list.add("https://mirrors.ustc.edu.cn/opensuse/");
                } else if (source == MirrorSource.ALIYUN) {
                    list.add("https://mirrors.aliyun.com/opensuse/");
                } else {
                    list.add("https://download.opensuse.org/");
                }
                break;
            case "almalinux":
                if (source == MirrorSource.MULTI_TIER) {
                    list.add("https://mirrors.aliyun.com/almalinux/");
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/almalinux/");
                    list.add("https://repo.almalinux.org/almalinux/");
                } else if (source == MirrorSource.TUNA) {
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/almalinux/");
                } else if (source == MirrorSource.USTC) {
                    list.add("https://mirrors.ustc.edu.cn/almalinux/");
                } else if (source == MirrorSource.ALIYUN) {
                    list.add("https://mirrors.aliyun.com/almalinux/");
                } else {
                    list.add("https://repo.almalinux.org/almalinux/");
                }
                break;
            case "rocky":
                if (source == MirrorSource.MULTI_TIER) {
                    list.add("https://mirrors.aliyun.com/rocky/");
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/rocky/");
                    list.add("https://dl.rockylinux.org/pub/rocky/");
                } else if (source == MirrorSource.TUNA) {
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/rocky/");
                } else if (source == MirrorSource.USTC) {
                    list.add("https://mirrors.ustc.edu.cn/rocky/");
                } else if (source == MirrorSource.ALIYUN) {
                    list.add("https://mirrors.aliyun.com/rocky/");
                } else {
                    list.add("https://dl.rockylinux.org/pub/rocky/");
                }
                break;
            case "debian":
            default:
                if (source == MirrorSource.MULTI_TIER) {
                    list.add("https://mirrors.aliyun.com/debian/");
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/debian/");
                    list.add("http://deb.debian.org/debian/");
                } else if (source == MirrorSource.TUNA) {
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/debian/");
                } else if (source == MirrorSource.USTC) {
                    list.add("https://mirrors.ustc.edu.cn/debian/");
                } else if (source == MirrorSource.ALIYUN) {
                    list.add("https://mirrors.aliyun.com/debian/");
                } else {
                    list.add("http://deb.debian.org/debian/");
                }
                break;
        }
        return Collections.unmodifiableList(list);
    }

    private static String parseDebianCodename(String version) {
        if (version == null || version.trim().isEmpty() || "latest".equalsIgnoreCase(version)) {
            return "trixie";
        }
        String v = version.trim().toLowerCase(Locale.ROOT);
        if (v.equals("13") || v.contains("trixie")) {
            return "trixie";
        } else if (v.equals("12") || v.contains("bookworm")) {
            return "bookworm";
        } else if (v.equals("11") || v.contains("bullseye")) {
            return "bullseye";
        }
        return "trixie";
    }

    private static String parseUbuntuCodename(String version) {
        if (version == null || version.trim().isEmpty() || "latest".equalsIgnoreCase(version)) {
            return "noble";
        }
        String v = version.trim().toLowerCase(Locale.ROOT);
        if (v.contains("24.04") || v.contains("noble")) {
            return "noble";
        } else if (v.contains("22.04") || v.contains("jammy")) {
            return "jammy";
        }
        return "noble";
    }

    private static String generateDebianSources(String distroVersion, MirrorSource source) {
        String codename = parseDebianCodename(distroVersion);
        if (source == MirrorSource.MULTI_TIER) {
            return "# Orchestella Multi-Tier Mirror Configuration (Debian)\n"
                    + "# Tier 1: Aliyun Cloud (国内极速云镜像)\n"
                    + "deb https://mirrors.aliyun.com/debian/ " + codename + " main contrib non-free non-free-firmware\n"
                    + "deb https://mirrors.aliyun.com/debian/ " + codename + "-updates main contrib non-free non-free-firmware\n"
                    + "deb https://mirrors.aliyun.com/debian-security " + codename + "-security main contrib non-free non-free-firmware\n\n"
                    + "# Tier 2: Tsinghua TUNA (教育网骨干加速)\n"
                    + "deb https://mirrors.tuna.tsinghua.edu.cn/debian/ " + codename + " main contrib non-free non-free-firmware\n"
                    + "deb https://mirrors.tuna.tsinghua.edu.cn/debian-security " + codename + "-security main contrib non-free non-free-firmware\n\n"
                    + "# Tier 3: Official Upstream (官方兜底保障)\n"
                    + "deb http://deb.debian.org/debian/ " + codename + " main contrib non-free non-free-firmware\n"
                    + "deb http://security.debian.org/debian-security " + codename + "-security main contrib non-free non-free-firmware\n";
        }

        String base;
        String securityBase;
        switch (source) {
            case TUNA:
                base = "https://mirrors.tuna.tsinghua.edu.cn/debian/";
                securityBase = "https://mirrors.tuna.tsinghua.edu.cn/debian-security";
                break;
            case USTC:
                base = "https://mirrors.ustc.edu.cn/debian/";
                securityBase = "https://mirrors.ustc.edu.cn/debian-security";
                break;
            case HUAWEICLOUD:
                base = "https://repo.huaweicloud.com/debian/";
                securityBase = "https://repo.huaweicloud.com/debian-security";
                break;
            case TENCENT:
                base = "https://mirrors.cloud.tencent.com/debian/";
                securityBase = "https://mirrors.cloud.tencent.com/debian-security";
                break;
            case ALIYUN:
                base = "https://mirrors.aliyun.com/debian/";
                securityBase = "https://mirrors.aliyun.com/debian-security";
                break;
            case OFFICIAL:
            default:
                base = "http://deb.debian.org/debian/";
                securityBase = "http://security.debian.org/debian-security";
                break;
        }

        return "deb " + base + " " + codename + " main contrib non-free non-free-firmware\n"
                + "deb " + base + " " + codename + "-updates main contrib non-free non-free-firmware\n"
                + "deb " + securityBase + " " + codename + "-security main contrib non-free non-free-firmware\n";
    }

    private static String generateUbuntuSources(String distroVersion, MirrorSource source) {
        String codename = parseUbuntuCodename(distroVersion);
        if (source == MirrorSource.MULTI_TIER) {
            return "# Orchestella Multi-Tier Mirror Configuration (Ubuntu)\n"
                    + "# Tier 1: Aliyun Cloud (国内极速云镜像)\n"
                    + "deb https://mirrors.aliyun.com/ubuntu/ " + codename + " main restricted universe multiverse\n"
                    + "deb https://mirrors.aliyun.com/ubuntu/ " + codename + "-updates main restricted universe multiverse\n"
                    + "deb https://mirrors.aliyun.com/ubuntu/ " + codename + "-security main restricted universe multiverse\n\n"
                    + "# Tier 2: Tsinghua TUNA (教育网骨干加速)\n"
                    + "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ " + codename + " main restricted universe multiverse\n"
                    + "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu/ " + codename + "-updates main restricted universe multiverse\n\n"
                    + "# Tier 3: Official Upstream (官方兜底保障)\n"
                    + "deb http://archive.ubuntu.com/ubuntu/ " + codename + " main restricted universe multiverse\n"
                    + "deb http://security.ubuntu.com/ubuntu/ " + codename + "-security main restricted universe multiverse\n";
        }

        String base;
        String securityBase;
        switch (source) {
            case TUNA:
                base = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/";
                securityBase = "https://mirrors.tuna.tsinghua.edu.cn/ubuntu/";
                break;
            case USTC:
                base = "https://mirrors.ustc.edu.cn/ubuntu/";
                securityBase = "https://mirrors.ustc.edu.cn/ubuntu/";
                break;
            case HUAWEICLOUD:
                base = "https://repo.huaweicloud.com/ubuntu/";
                securityBase = "https://repo.huaweicloud.com/ubuntu/";
                break;
            case TENCENT:
                base = "https://mirrors.cloud.tencent.com/ubuntu/";
                securityBase = "https://mirrors.cloud.tencent.com/ubuntu/";
                break;
            case ALIYUN:
                base = "https://mirrors.aliyun.com/ubuntu/";
                securityBase = "https://mirrors.aliyun.com/ubuntu/";
                break;
            case OFFICIAL:
            default:
                base = "http://archive.ubuntu.com/ubuntu/";
                securityBase = "http://security.ubuntu.com/ubuntu/";
                break;
        }

        return "deb " + base + " " + codename + " main restricted universe multiverse\n"
                + "deb " + base + " " + codename + "-updates main restricted universe multiverse\n"
                + "deb " + securityBase + " " + codename + "-security main restricted universe multiverse\n";
    }

    private static String generateArchMirrorlist(MirrorSource source) {
        if (source == MirrorSource.MULTI_TIER) {
            return "## Orchestella Multi-Tier Mirrorlist (Arch Linux)\n"
                    + "## Tier 1: Aliyun Cloud (国内极速云镜像)\n"
                    + "Server = https://mirrors.aliyun.com/archlinux/$repo/os/$arch\n\n"
                    + "## Tier 2: Tsinghua TUNA & USTC (教育网骨干加速)\n"
                    + "Server = https://mirrors.tuna.tsinghua.edu.cn/archlinux/$repo/os/$arch\n"
                    + "Server = https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch\n\n"
                    + "## Tier 3: Official Upstream (官方兜底保障)\n"
                    + "Server = https://geo.mirror.pkgbuild.com/$repo/os/$arch\n";
        }

        String server;
        switch (source) {
            case TUNA:
                server = "Server = https://mirrors.tuna.tsinghua.edu.cn/archlinux/$repo/os/$arch";
                break;
            case USTC:
                server = "Server = https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch";
                break;
            case HUAWEICLOUD:
                server = "Server = https://repo.huaweicloud.com/archlinux/$repo/os/$arch";
                break;
            case TENCENT:
                server = "Server = https://mirrors.cloud.tencent.com/archlinux/$repo/os/$arch";
                break;
            case ALIYUN:
                server = "Server = https://mirrors.aliyun.com/archlinux/$repo/os/$arch";
                break;
            case OFFICIAL:
            default:
                server = "Server = https://geo.mirror.pkgbuild.com/$repo/os/$arch";
                break;
        }
        return server + "\n";
    }

    private static String generateFedoraRepo(String distroVersion, MirrorSource source) {
        if (source == MirrorSource.MULTI_TIER) {
            return "[fedora]\n"
                    + "name=Fedora $releasever - $basearch\n"
                    + "baseurl=https://mirrors.aliyun.com/fedora/releases/$releasever/Everything/$basearch/os/\n"
                    + "        https://mirrors.tuna.tsinghua.edu.cn/fedora/releases/$releasever/Everything/$basearch/os/\n"
                    + "        https://download.fedoraproject.org/pub/fedora/linux/releases/$releasever/Everything/$basearch/os/\n"
                    + "enabled=1\n"
                    + "gpgcheck=1\n\n"
                    + "[updates]\n"
                    + "name=Fedora $releasever - Updates - $basearch\n"
                    + "baseurl=https://mirrors.aliyun.com/fedora/updates/$releasever/Everything/$basearch/\n"
                    + "        https://mirrors.tuna.tsinghua.edu.cn/fedora/updates/$releasever/Everything/$basearch/\n"
                    + "        https://download.fedoraproject.org/pub/fedora/linux/updates/$releasever/Everything/$basearch/\n"
                    + "enabled=1\n"
                    + "gpgcheck=1\n";
        }
        String base = (source == MirrorSource.TUNA) ? "https://mirrors.tuna.tsinghua.edu.cn/fedora" :
                (source == MirrorSource.USTC) ? "https://mirrors.ustc.edu.cn/fedora" :
                (source == MirrorSource.ALIYUN) ? "https://mirrors.aliyun.com/fedora" :
                "https://download.fedoraproject.org/pub/fedora/linux";
        return "[fedora]\n"
                + "name=Fedora $releasever - $basearch\n"
                + "baseurl=" + base + "/releases/$releasever/Everything/$basearch/os/\n"
                + "enabled=1\n"
                + "gpgcheck=1\n";
    }

    private static String generateOpenSuseRepo(MirrorSource source) {
        if (source == MirrorSource.MULTI_TIER) {
            return "[repo-oss]\n"
                    + "name=openSUSE-Tumbleweed-Oss\n"
                    + "baseurl=https://mirrors.aliyun.com/opensuse/tumbleweed/repo/oss/\n"
                    + "        https://mirrors.tuna.tsinghua.edu.cn/opensuse/tumbleweed/repo/oss/\n"
                    + "        https://download.opensuse.org/tumbleweed/repo/oss/\n"
                    + "enabled=1\n"
                    + "gpgcheck=1\n";
        }
        String base = (source == MirrorSource.TUNA) ? "https://mirrors.tuna.tsinghua.edu.cn/opensuse" :
                (source == MirrorSource.USTC) ? "https://mirrors.ustc.edu.cn/opensuse" :
                (source == MirrorSource.ALIYUN) ? "https://mirrors.aliyun.com/opensuse" :
                "https://download.opensuse.org";
        return "[repo-oss]\n"
                + "name=openSUSE-Tumbleweed-Oss\n"
                + "baseurl=" + base + "/tumbleweed/repo/oss/\n"
                + "enabled=1\n"
                + "gpgcheck=1\n";
    }

    private static String generateAlmaLinuxRepo(String distroVersion, MirrorSource source) {
        if (source == MirrorSource.MULTI_TIER) {
            return "[baseos]\n"
                    + "name=AlmaLinux $releasever - BaseOS\n"
                    + "baseurl=https://mirrors.aliyun.com/almalinux/$releasever/BaseOS/$basearch/os/\n"
                    + "        https://mirrors.tuna.tsinghua.edu.cn/almalinux/$releasever/BaseOS/$basearch/os/\n"
                    + "        https://repo.almalinux.org/almalinux/$releasever/BaseOS/$basearch/os/\n"
                    + "enabled=1\n"
                    + "gpgcheck=1\n";
        }
        String base = (source == MirrorSource.TUNA) ? "https://mirrors.tuna.tsinghua.edu.cn/almalinux" :
                (source == MirrorSource.USTC) ? "https://mirrors.ustc.edu.cn/almalinux" :
                (source == MirrorSource.ALIYUN) ? "https://mirrors.aliyun.com/almalinux" :
                "https://repo.almalinux.org/almalinux";
        return "[baseos]\n"
                + "name=AlmaLinux $releasever - BaseOS\n"
                + "baseurl=" + base + "/$releasever/BaseOS/$basearch/os/\n"
                + "enabled=1\n"
                + "gpgcheck=1\n";
    }

    private static String generateRockyLinuxRepo(String distroVersion, MirrorSource source) {
        if (source == MirrorSource.MULTI_TIER) {
            return "[baseos]\n"
                    + "name=Rocky Linux $releasever - BaseOS\n"
                    + "baseurl=https://mirrors.aliyun.com/rocky/$releasever/BaseOS/$basearch/os/\n"
                    + "        https://mirrors.tuna.tsinghua.edu.cn/rocky/$releasever/BaseOS/$basearch/os/\n"
                    + "        https://dl.rockylinux.org/pub/rocky/$releasever/BaseOS/$basearch/os/\n"
                    + "enabled=1\n"
                    + "gpgcheck=1\n";
        }
        String base = (source == MirrorSource.TUNA) ? "https://mirrors.tuna.tsinghua.edu.cn/rocky" :
                (source == MirrorSource.USTC) ? "https://mirrors.ustc.edu.cn/rocky" :
                (source == MirrorSource.ALIYUN) ? "https://mirrors.aliyun.com/rocky" :
                "https://dl.rockylinux.org/pub/rocky";
        return "[baseos]\n"
                + "name=Rocky Linux $releasever - BaseOS\n"
                + "baseurl=" + base + "/$releasever/BaseOS/$basearch/os/\n"
                + "enabled=1\n"
                + "gpgcheck=1\n";
    }
}
