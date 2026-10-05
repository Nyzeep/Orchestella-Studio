package io.github.nyzeep.droidlayer.guestassets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DesktopSetupScriptTest {

    private static Path getScriptsDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("scripts");
        }
        return Path.of("scripts");
    }

    @Test
    void setupScriptExistsAndHasLfLineEndingsOnly() throws IOException {
        Path setupScript = getScriptsDir().resolve("droidlayer-desktop-setup.sh");
        assertTrue(Files.exists(setupScript), "droidlayer-desktop-setup.sh must exist");
        assertTrue(Files.size(setupScript) > 0, "droidlayer-desktop-setup.sh must not be empty");

        byte[] bytes = Files.readAllBytes(setupScript);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "setup script must use LF line endings only");
        assertTrue(content.startsWith("#!/"), "setup script must start with shebang");
    }

    @Test
    void launchScriptExistsAndHasLfLineEndingsOnly() throws IOException {
        Path launchScript = getScriptsDir().resolve("droidlayer-desktop-launch.sh");
        assertTrue(Files.exists(launchScript), "droidlayer-desktop-launch.sh must exist");
        assertTrue(Files.size(launchScript) > 0, "droidlayer-desktop-launch.sh must not be empty");

        byte[] bytes = Files.readAllBytes(launchScript);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "launch script must use LF line endings only");
        assertTrue(content.startsWith("#!/"), "launch script must start with shebang");
    }

    @Test
    void verifiesAlpineDistributionBranchingAndPackageList() throws IOException {
        Path setupScript = getScriptsDir().resolve("droidlayer-desktop-setup.sh");
        String content = Files.readString(setupScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("alpine"), "setup script must contain Alpine distribution branch");
        assertTrue(content.contains("apk add"), "setup script must use apk add for Alpine");
        assertTrue(content.contains("--no-cache"), "apk add must use --no-cache");

        List<String> alpinePackages = List.of("labwc", "sfwbar", "foot", "xwayland", "font-noto-cjk");
        for (String pkg : alpinePackages) {
            assertTrue(content.contains(pkg), "Alpine package list must include: " + pkg);
        }
    }

    @Test
    void verifiesDebianDistributionBranchingAndPackageList() throws IOException {
        Path setupScript = getScriptsDir().resolve("droidlayer-desktop-setup.sh");
        String content = Files.readString(setupScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("debian") || content.contains("trixie"),
                "setup script must contain Debian distribution branch");
        assertTrue(content.contains("apt-get install"), "setup script must use apt-get install for Debian");
        assertTrue(content.contains("DEBIAN_FRONTEND=noninteractive"),
                "apt-get must use DEBIAN_FRONTEND=noninteractive for unattended installation");
        assertTrue(content.contains("--no-install-recommends"),
                "apt-get must use --no-install-recommends to keep footprint minimal");

        List<String> debianPackages = List.of("labwc", "sfwbar", "foot", "xwayland", "fonts-noto-cjk");
        for (String pkg : debianPackages) {
            assertTrue(content.contains(pkg), "Debian package list must include: " + pkg);
        }
    }

    @Test
    void verifiesRuntimeDirectoryAndUserInitialization() throws IOException {
        Path setupScript = getScriptsDir().resolve("droidlayer-desktop-setup.sh");
        String content = Files.readString(setupScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("/run/user/1000"), "setup script must initialize runtime directory /run/user/1000");
        assertTrue(content.contains("chmod 700") || content.contains("0700"),
                "runtime directory must have 0700 permissions");
        assertTrue(content.contains(".config/labwc"), "setup script must deploy labwc user configuration");
        assertTrue(content.contains(".config/sfwbar"), "setup script must deploy sfwbar user configuration");
        assertTrue(content.contains(".config/foot"), "setup script must deploy foot user configuration");
    }

    @Test
    void verifiesIdempotencyAndUnattendedGuards() throws IOException {
        Path setupScript = getScriptsDir().resolve("droidlayer-desktop-setup.sh");
        String content = Files.readString(setupScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("--check") || content.contains("--dry-run"),
                "setup script must support non-interactive check or dry-run mode for idempotency verification");
        assertFalse(content.contains("read -p"), "setup script must not have interactive read prompts");
    }

    @Test
    void verifiesLaunchScriptWaylandEnvironmentAndComponents() throws IOException {
        Path launchScript = getScriptsDir().resolve("droidlayer-desktop-launch.sh");
        String content = Files.readString(launchScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("XDG_RUNTIME_DIR"), "launch script must export XDG_RUNTIME_DIR");
        assertTrue(content.contains("WAYLAND_DISPLAY"), "launch script must export WAYLAND_DISPLAY");
        assertTrue(content.contains("wayland-0"), "launch script must set WAYLAND_DISPLAY to wayland-0");
        assertTrue(content.contains("labwc"), "launch script must launch labwc compositor");
    }
}
