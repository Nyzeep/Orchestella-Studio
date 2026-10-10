package io.github.nyzeep.orchestella;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure JVM provisioner for generating Linux package manager mirror configurations (Ticket 01).
 *
 * <p>Supports generating configuration files for:
 * <ul>
 *   <li>Debian / Ubuntu / Kali: {@code /etc/apt/sources.list}</li>
 *   <li>Alpine Linux: {@code /etc/apk/repositories}</li>
 *   <li>Arch Linux: {@code /etc/pacman.d/mirrorlist}</li>
 * </ul>
 */
public final class DistroMirrorProvisioner {

    public enum MirrorSource {
        OFFICIAL("官方默认源 (Official Upstream)"),
        TUNA("清华大学开源软件镜像站 (Tsinghua TUNA - 推荐国内极速)"),
        USTC("中国科学技术大学镜像站 (USTC)"),
        ALIYUN("阿里云开源镜像站 (Aliyun Public)");

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

    public static String getTargetConfigPath(String distroId) {
        if (distroId == null) {
            return "/etc/apt/sources.list";
        }
        String id = distroId.trim().toLowerCase(Locale.ROOT);
        switch (id) {
            case "alpine":
                return "/etc/apk/repositories";
            case "arch":
                return "/etc/pacman.d/mirrorlist";
            case "debian":
            case "ubuntu":
            case "kali":
            default:
                return "/etc/apt/sources.list";
        }
    }

    public static String generateMirrorConfig(String distroId, MirrorSource source) {
        return generateMirrorConfig(distroId, "latest", source);
    }

    public static String generateMirrorConfig(String distroId, String distroVersion, MirrorSource source) {
        Objects.requireNonNull(source, "source");
        String id = distroId != null ? distroId.trim().toLowerCase(Locale.ROOT) : "debian";
        switch (id) {
            case "alpine":
                return generateAlpineRepositories(distroVersion, source);
            case "arch":
                return generateArchMirrorlist(source);
            case "ubuntu":
                return generateUbuntuSources(distroVersion, source);
            case "debian":
            case "kali":
            default:
                return generateDebianSources(distroVersion, source);
        }
    }

    public static List<String> resolveMirrorUrls(String distroId, String distroVersion, MirrorSource source) {
        Objects.requireNonNull(source, "source");
        String id = distroId != null ? distroId.trim().toLowerCase(Locale.ROOT) : "debian";
        List<String> list = new ArrayList<>();
        if ("alpine".equals(id)) {
            String branch = parseAlpineBranch(distroVersion);
            String base;
            switch (source) {
                case TUNA:
                    base = "https://mirrors.tuna.tsinghua.edu.cn/alpine";
                    break;
                case USTC:
                    base = "https://mirrors.ustc.edu.cn/alpine";
                    break;
                case ALIYUN:
                    base = "https://mirrors.aliyun.com/alpine";
                    break;
                case OFFICIAL:
                default:
                    base = "https://dl-cdn.alpinelinux.org/alpine";
                    break;
            }
            list.add(base + "/" + branch + "/main");
            list.add(base + "/" + branch + "/community");
        } else if ("arch".equals(id)) {
            switch (source) {
                case TUNA:
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/archlinux/$repo/os/$arch");
                    break;
                case USTC:
                    list.add("https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch");
                    break;
                case ALIYUN:
                    list.add("https://mirrors.aliyun.com/archlinux/$repo/os/$arch");
                    break;
                case OFFICIAL:
                default:
                    list.add("https://geo.mirror.pkgbuild.com/$repo/os/$arch");
                    break;
            }
        } else if ("ubuntu".equals(id)) {
            switch (source) {
                case TUNA:
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/ubuntu/");
                    break;
                case USTC:
                    list.add("https://mirrors.ustc.edu.cn/ubuntu/");
                    break;
                case ALIYUN:
                    list.add("https://mirrors.aliyun.com/ubuntu/");
                    break;
                case OFFICIAL:
                default:
                    list.add("http://archive.ubuntu.com/ubuntu/");
                    break;
            }
        } else {
            // Debian
            switch (source) {
                case TUNA:
                    list.add("https://mirrors.tuna.tsinghua.edu.cn/debian/");
                    break;
                case USTC:
                    list.add("https://mirrors.ustc.edu.cn/debian/");
                    break;
                case ALIYUN:
                    list.add("https://mirrors.aliyun.com/debian/");
                    break;
                case OFFICIAL:
                default:
                    list.add("http://deb.debian.org/debian/");
                    break;
            }
        }
        return Collections.unmodifiableList(list);
    }

    private static String parseAlpineBranch(String version) {
        if (version == null || version.trim().isEmpty() || "latest".equalsIgnoreCase(version)) {
            return "v3.24";
        }
        String v = version.trim();
        if (v.startsWith("v")) {
            v = v.substring(1);
        }
        String[] parts = v.split("\\.");
        if (parts.length >= 2) {
            return "v" + parts[0] + "." + parts[1];
        }
        return "v" + v;
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

    private static String generateAlpineRepositories(String distroVersion, MirrorSource source) {
        String branch = parseAlpineBranch(distroVersion);
        String base;
        switch (source) {
            case TUNA:
                base = "https://mirrors.tuna.tsinghua.edu.cn/alpine";
                break;
            case USTC:
                base = "https://mirrors.ustc.edu.cn/alpine";
                break;
            case ALIYUN:
                base = "https://mirrors.aliyun.com/alpine";
                break;
            case OFFICIAL:
            default:
                base = "https://dl-cdn.alpinelinux.org/alpine";
                break;
        }
        return base + "/" + branch + "/main\n"
                + base + "/" + branch + "/community\n";
    }

    private static String generateDebianSources(String distroVersion, MirrorSource source) {
        String codename = parseDebianCodename(distroVersion);
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
        String server;
        switch (source) {
            case TUNA:
                server = "Server = https://mirrors.tuna.tsinghua.edu.cn/archlinux/$repo/os/$arch";
                break;
            case USTC:
                server = "Server = https://mirrors.ustc.edu.cn/archlinux/$repo/os/$arch";
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
}
