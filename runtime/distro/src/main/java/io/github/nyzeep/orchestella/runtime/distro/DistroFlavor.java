package io.github.nyzeep.orchestella.runtime.distro;

import java.util.Objects;

/**
 * Strategy-neutral representation of distro assembly flavor, init system,
 * package management, and system service adaptation.
 */
public final class DistroFlavor {

    public enum InitSystem {
        OPENRC,
        BUSYBOX,
        SYSTEMD
    }

    public static final DistroFlavor DEBIAN =
            new DistroFlavor(
                    "debian",
                    "Debian GNU/Linux",
                    InitSystem.SYSTEMD,
                    "apt",
                    "debian-13",
                    "/etc/systemd/system/orchestella-guest-agent.service",
                    "/etc/systemd/system/multi-user.target.wants/orchestella-guest-agent.service");

    public static final DistroFlavor ALPINE =
            new DistroFlavor(
                    "alpine",
                    "Alpine Linux",
                    InitSystem.OPENRC,
                    "apk",
                    "alpine-3.24.1",
                    "/etc/init.d/orchestella-guest-agent",
                    "/etc/runlevels/default/orchestella-guest-agent");

    public static final DistroFlavor UBUNTU =
            new DistroFlavor(
                    "ubuntu",
                    "Ubuntu Linux",
                    InitSystem.SYSTEMD,
                    "apt",
                    "ubuntu-24.04",
                    "/etc/systemd/system/orchestella-guest-agent.service",
                    "/etc/systemd/system/multi-user.target.wants/orchestella-guest-agent.service");

    public static final DistroFlavor ARCH =
            new DistroFlavor(
                    "arch",
                    "Arch Linux ARM",
                    InitSystem.SYSTEMD,
                    "pacman",
                    "archlinux",
                    "/etc/systemd/system/orchestella-guest-agent.service",
                    "/etc/systemd/system/multi-user.target.wants/orchestella-guest-agent.service");

    public static final DistroFlavor FEDORA =
            new DistroFlavor(
                    "fedora",
                    "Fedora Linux",
                    InitSystem.SYSTEMD,
                    "dnf",
                    "fedora-41",
                    "/etc/systemd/system/orchestella-guest-agent.service",
                    "/etc/systemd/system/multi-user.target.wants/orchestella-guest-agent.service");

    public static final DistroFlavor OPENSUSE =
            new DistroFlavor(
                    "opensuse",
                    "openSUSE Tumbleweed",
                    InitSystem.SYSTEMD,
                    "zypper",
                    "opensuse-tumbleweed",
                    "/etc/systemd/system/orchestella-guest-agent.service",
                    "/etc/systemd/system/multi-user.target.wants/orchestella-guest-agent.service");

    public static final DistroFlavor KALI =
            new DistroFlavor(
                    "kali",
                    "Kali Linux",
                    InitSystem.SYSTEMD,
                    "apt",
                    "kali-rolling",
                    "/etc/systemd/system/orchestella-guest-agent.service",
                    "/etc/systemd/system/multi-user.target.wants/orchestella-guest-agent.service");

    private final String distroId;
    private final String displayName;
    private final InitSystem initSystem;
    private final String packageManager;
    private final String provisionedDistroTag;
    private final String serviceUnitPath;
    private final String serviceEnableSymlink;

    private DistroFlavor(
            String distroId,
            String displayName,
            InitSystem initSystem,
            String packageManager,
            String provisionedDistroTag,
            String serviceUnitPath,
            String serviceEnableSymlink) {
        this.distroId = Objects.requireNonNull(distroId, "distroId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.initSystem = Objects.requireNonNull(initSystem, "initSystem");
        this.packageManager = Objects.requireNonNull(packageManager, "packageManager");
        this.provisionedDistroTag = Objects.requireNonNull(provisionedDistroTag, "provisionedDistroTag");
        this.serviceUnitPath = Objects.requireNonNull(serviceUnitPath, "serviceUnitPath");
        this.serviceEnableSymlink = Objects.requireNonNull(serviceEnableSymlink, "serviceEnableSymlink");
    }

    public static DistroFlavor forDistroId(String distroId) {
        Objects.requireNonNull(distroId, "distroId");
        return switch (distroId.toLowerCase()) {
            case "debian" -> DEBIAN;
            case "alpine" -> ALPINE;
            case "ubuntu" -> UBUNTU;
            case "arch" -> ARCH;
            case "fedora" -> FEDORA;
            case "opensuse" -> OPENSUSE;
            case "kali" -> KALI;
            default -> throw new IllegalArgumentException("unsupported distro flavor: " + distroId);
        };
    }

    public static DistroFlavor forManifest(DistroManifest manifest) {
        Objects.requireNonNull(manifest, "manifest");
        return forDistroId(manifest.id());
    }

    public String distroId() {
        return distroId;
    }

    public String displayName() {
        return displayName;
    }

    public InitSystem initSystem() {
        return initSystem;
    }

    public String packageManager() {
        return packageManager;
    }

    public String provisionedDistroTag() {
        return provisionedDistroTag;
    }

    public String serviceUnitPath() {
        return serviceUnitPath;
    }

    public String serviceEnableSymlink() {
        return serviceEnableSymlink;
    }

    public String systemdUnitContent() {
        return "[Unit]\n"
                + "Description=Orchestella Guest Agent\n"
                + "DefaultDependencies=no\n"
                + "After=systemd-udevd.service\n"
                + "Before=basic.target\n"
                + "\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "ExecStart=/usr/sbin/orchestella-guest-agent-launcher\n"
                + "Restart=always\n"
                + "RestartSec=1s\n"
                + "\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n";
    }

    public String guestAgentLauncherScript() {
        return "#!/bin/sh\n"
                + "set -eu\n"
                + "\n"
                + "mkdir -p /mnt/downloads /media/downloads\n"
                + "mount -t 9p -o trans=virtio,version=9p2000.L,ro downloads /mnt/downloads 2>/dev/null || true\n"
                + "\n"
                + "[ -c /dev/orchestella-bridge ] || exit 70\n"
                + "[ -c /dev/orchestella-terminal ] || exit 70\n"
                + "exec 3<> /dev/orchestella-bridge\n"
                + "exec /usr/sbin/orchestella-guest-agent \\\n"
                + "    --token-file /etc/orchestella/bridge.token \\\n"
                + "    --terminal-device /dev/orchestella-terminal <&3 >&3\n";
    }

    public String resolvConfContent() {
        return resolvConfContent("10.0.2.3", "8.8.8.8");
    }

    public String resolvConfContent(String primaryDns, String secondaryDns) {
        StringBuilder builder = new StringBuilder();
        if (primaryDns != null && !primaryDns.isBlank()) {
            builder.append("nameserver ").append(primaryDns).append("\n");
        }
        if (secondaryDns != null && !secondaryDns.isBlank()) {
            builder.append("nameserver ").append(secondaryDns).append("\n");
        }
        return builder.toString();
    }
}
