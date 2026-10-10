package io.github.nyzeep.orchestella.runtime.distro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure JVM domain module encapsulating Alpine Linux essential package recommendations
 * and provisioning command generation.
 */
public final class AlpineEssentialPackageSuite {

    public static final String DEFAULT_SHELL = "/bin/bash";
    public static final String DEFAULT_USER = "root";

    private static final List<String> RECOMMENDED_PACKAGES = Collections.unmodifiableList(Arrays.asList(
            "bash",
            "bash-completion",
            "curl",
            "wget",
            "ca-certificates",
            "sudo",
            "shadow",
            "coreutils",
            "grep",
            "sed",
            "nano",
            "procps",
            "tar"
    ));

    /**
     * Component package group for UI presentation and selection.
     */
    public record PackageGroup(
            String id,
            String title,
            String description,
            List<String> packages,
            boolean defaultEnabled
    ) {
        public PackageGroup {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(description, "description");
            packages = Collections.unmodifiableList(new ArrayList<>(packages));
        }
    }

    private static final List<PackageGroup> PACKAGE_GROUPS = Collections.unmodifiableList(Arrays.asList(
            new PackageGroup(
                    "bash_group",
                    "GNU Bash & 交互补全",
                    "告别简陋 ash，获得与桌面终端一致的交互体验",
                    Arrays.asList("bash", "bash-completion"),
                    true
            ),
            new PackageGroup(
                    "net_tools",
                    "网络传输工具",
                    "确保即刻具备 HTTPS 脚本拉取与文件下载能力",
                    Arrays.asList("curl", "wget", "ca-certificates"),
                    true
            ),
            new PackageGroup(
                    "auth_tools",
                    "特权提升与安全工具",
                    "支持标准非 root 用户及 sudo 权限流转",
                    Arrays.asList("sudo", "shadow"),
                    true
            ),
            new PackageGroup(
                    "core_tools",
                    "GNU 核心工具集",
                    "提供完整的 Linux 命令参数支持",
                    Arrays.asList("coreutils", "grep", "sed"),
                    true
            ),
            new PackageGroup(
                    "sys_tools",
                    "进程诊断与编辑器",
                    "开箱即用文本编辑与系统监控",
                    Arrays.asList("nano", "procps", "tar"),
                    true
            )
    ));

    private AlpineEssentialPackageSuite() {}

    /**
     * Returns the flat list of default recommended package names.
     */
    public static List<String> getRecommendedPackages() {
        return RECOMMENDED_PACKAGES;
    }

    /**
     * Returns the structured package groups for UI display and selection.
     */
    public static List<PackageGroup> getPackageGroups() {
        return PACKAGE_GROUPS;
    }

    /**
     * Generates the apk add command for the selected packages, filtering duplicates and empty tokens.
     */
    public static String generateInstallCommand(Collection<String> selectedPackages) {
        if (selectedPackages == null || selectedPackages.isEmpty()) {
            return "";
        }
        Set<String> sanitized = new LinkedHashSet<>();
        for (String pkg : selectedPackages) {
            if (pkg != null) {
                String trimmed = pkg.trim();
                if (!trimmed.isEmpty()) {
                    sanitized.add(trimmed);
                }
            }
        }
        if (sanitized.isEmpty()) {
            return "";
        }
        return "apk add --no-cache " + String.join(" ", sanitized);
    }

    /**
     * Generates the shell switch command defaulting to bash and root user.
     */
    public static String generateShellSwitchCommand() {
        return generateShellSwitchCommand(DEFAULT_SHELL, DEFAULT_USER);
    }

    /**
     * Generates the shell switch command for the specified shell and user.
     */
    public static String generateShellSwitchCommand(String shell, String user) {
        String targetShell = (shell != null && !shell.trim().isEmpty()) ? shell.trim() : DEFAULT_SHELL;
        String targetUser = (user != null && !user.trim().isEmpty()) ? user.trim() : DEFAULT_USER;
        return "chsh -s " + targetShell + " " + targetUser;
    }

    /**
     * Generates a combined provisioning script executing package installation followed by default shell switch.
     */
    public static String generateFullProvisioningScript(Collection<String> selectedPackages) {
        String installCmd = generateInstallCommand(selectedPackages);
        String shellSwitchCmd = generateShellSwitchCommand();
        if (installCmd.isEmpty()) {
            return shellSwitchCmd;
        }
        return installCmd + " && " + shellSwitchCmd;
    }
}
