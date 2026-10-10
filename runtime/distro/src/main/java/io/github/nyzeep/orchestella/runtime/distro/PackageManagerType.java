package io.github.nyzeep.orchestella.runtime.distro;

import java.util.Collection;
import java.util.Objects;

/**
 * Strategy-neutral representation of Linux package managers and default execution commands.
 */
public enum PackageManagerType {
    APT("apt") {
        @Override
        public String defaultUpdateCommand() {
            return "apt-get update";
        }

        @Override
        public String defaultInstallCommand(Collection<String> packages) {
            return "apt-get install -y " + joinPackages(packages);
        }
    },
    PACMAN("pacman") {
        @Override
        public String defaultUpdateCommand() {
            return "pacman -Sy";
        }

        @Override
        public String defaultInstallCommand(Collection<String> packages) {
            return "pacman -S --noconfirm " + joinPackages(packages);
        }
    },
    DNF("dnf") {
        @Override
        public String defaultUpdateCommand() {
            return "dnf check-update";
        }

        @Override
        public String defaultInstallCommand(Collection<String> packages) {
            return "dnf install -y " + joinPackages(packages);
        }
    },
    ZYPPER("zypper") {
        @Override
        public String defaultUpdateCommand() {
            return "zypper refresh";
        }

        @Override
        public String defaultInstallCommand(Collection<String> packages) {
            return "zypper --non-interactive install -y " + joinPackages(packages);
        }
    };

    private final String commandName;

    PackageManagerType(String commandName) {
        this.commandName = commandName;
    }

    public String commandName() {
        return commandName;
    }

    public abstract String defaultUpdateCommand();

    public abstract String defaultInstallCommand(Collection<String> packages);

    public static PackageManagerType forDistroId(String distroId) {
        Objects.requireNonNull(distroId, "distroId must not be null");
        return switch (distroId.toLowerCase()) {
            case "ubuntu", "debian" -> APT;
            case "arch" -> PACMAN;
            case "fedora", "almalinux", "rocky" -> DNF;
            case "opensuse" -> ZYPPER;
            default -> throw new IllegalArgumentException("unsupported package manager for distro: " + distroId);
        };
    }

    private static String joinPackages(Collection<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }
        return String.join(" ", packages);
    }
}
