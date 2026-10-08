package io.github.nyzeep.droidlayer.guestassets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GuestAssetsShellScriptTest {

    private static Path getShellDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("shell");
        }
        return Path.of("shell");
    }

    private static Path getScriptsDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("scripts");
        }
        return Path.of("scripts");
    }

    private static Path getInitramfsDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("initramfs");
        }
        return Path.of("initramfs");
    }

    @Test
    void promptScriptExistsAndHasLfLineEndingsOnly() throws IOException {
        Path promptScript = getShellDir().resolve("droidlayer-prompt.sh");
        assertTrue(Files.exists(promptScript), "droidlayer-prompt.sh must exist");
        assertTrue(Files.size(promptScript) > 0, "droidlayer-prompt.sh must not be empty");

        byte[] bytes = Files.readAllBytes(promptScript);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "droidlayer-prompt.sh must use LF line endings only");
        assertTrue(content.startsWith("#!/"), "droidlayer-prompt.sh must start with shebang");
    }

    @Test
    void promptScriptImplementsTwoLineStarshipStructureAndColors() throws IOException {
        Path promptScript = getShellDir().resolve("droidlayer-prompt.sh");
        String content = Files.readString(promptScript, StandardCharsets.UTF_8);

        // Path in cyan (36m)
        assertTrue(content.contains("36m"), "Prompt must render working directory in cyan (36m)");
        // Git branch in yellow (33m)
        assertTrue(content.contains("33m"), "Prompt must render git branch in yellow (33m)");
        // Exit code error symbol in red (31m) and ✘
        assertTrue(content.contains("31m"), "Prompt must render error state in red (31m)");
        assertTrue(content.contains("✘"), "Prompt must render ✘ error indicator on non-zero exit");
        // Compact modern prompt arrow ❯ in green (32m) and magenta (35m) for root
        assertTrue(content.contains("❯"), "Prompt must render compact modern arrow ❯");
        assertTrue(content.contains("32m"), "Prompt must render user arrow in green (32m)");
        assertTrue(content.contains("35m"), "Prompt must render root arrow in magenta (35m)");

        // Two-line structure with newline
        assertTrue(content.contains("\\n"), "Prompt must be a two-line structure separated by newline");

        // Universal compatibility with bash and sh/ash
        assertTrue(content.contains("BASH_VERSION"), "Prompt must detect BASH_VERSION for bash mode");
        assertTrue(content.contains("PROMPT_COMMAND"), "Prompt must use PROMPT_COMMAND in bash");
        assertTrue(content.contains("PS1"), "Prompt must export PS1");
    }

    @Test
    void promptScriptHasZeroExternalBinaryDependencies() throws IOException {
        Path promptScript = getShellDir().resolve("droidlayer-prompt.sh");
        String content = Files.readString(promptScript, StandardCharsets.UTF_8);

        // Git lookup must parse .git/HEAD natively without fork/exec of git binary
        assertFalse(content.contains("$(git "), "Prompt must not invoke external git binary");
        assertFalse(content.contains("`git "), "Prompt must not invoke external git binary");
        assertTrue(content.contains("/HEAD") && content.contains(".git"),
                "Prompt must parse git HEAD directly for speed < 2ms");
    }

    @Test
    void aliasesScriptExistsAndDefinesRequiredAliases() throws IOException {
        Path aliasesScript = getShellDir().resolve("aliases.sh");
        assertTrue(Files.exists(aliasesScript), "aliases.sh must exist");
        assertTrue(Files.size(aliasesScript) > 0, "aliases.sh must not be empty");

        byte[] bytes = Files.readAllBytes(aliasesScript);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "aliases.sh must use LF line endings only");
        assertTrue(content.startsWith("#!/"), "aliases.sh must start with shebang");

        List<String> requiredAliases = List.of(
                "alias ll='ls -lah --color=auto'",
                "alias la='ls -A --color=auto'",
                "alias l='ls -CF --color=auto'",
                "alias grep='grep --color=auto'",
                "alias df='df -h'",
                "alias free='free -m'");
        for (String expected : requiredAliases) {
            assertTrue(content.contains(expected), "aliases.sh must contain: " + expected);
        }
    }

    @Test
    void unifiedEnvScriptExportsStandardizedLsColors() throws IOException {
        Path envScript = getShellDir().resolve("00-droidlayer-env.sh");
        assertTrue(Files.exists(envScript), "00-droidlayer-env.sh must exist");

        byte[] bytes = Files.readAllBytes(envScript);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "00-droidlayer-env.sh must use LF line endings only");
        assertTrue(content.startsWith("#!/"), "00-droidlayer-env.sh must start with shebang");

        assertTrue(content.contains("export LS_COLORS="), "Must export LS_COLORS environment variable");
        // Directories: bright blue bold (01;34)
        assertTrue(content.contains("di=01;34"), "LS_COLORS must set directories to bright blue bold (di=01;34)");
        // Executables: bright green bold (01;32)
        assertTrue(content.contains("ex=01;32"), "LS_COLORS must set executables to bright green bold (ex=01;32)");
        // Symlinks: cyan bold (01;36)
        assertTrue(content.contains("ln=01;36"), "LS_COLORS must set symlinks to cyan bold (ln=01;36)");

        // Archive / compression formats
        List<String> archiveFormats = List.of("*.tar=", "*.tgz=", "*.zip=", "*.gz=", "*.zst=", "*.bz2=", "*.xz=");
        for (String arch : archiveFormats) {
            assertTrue(content.contains(arch), "LS_COLORS must configure archive format: " + arch);
        }

        // Source code extensions
        List<String> sourceExts = List.of("*.c=", "*.cpp=", "*.rs=", "*.py=", "*.go=", "*.java=", "*.js=");
        for (String ext : sourceExts) {
            assertTrue(content.contains(ext), "LS_COLORS must configure source extension: " + ext);
        }

        // Must wire aliases and prompt
        assertTrue(content.contains("aliases.sh"), "00-droidlayer-env.sh must link aliases.sh");
        assertTrue(content.contains("droidlayer-prompt.sh"), "00-droidlayer-env.sh must link droidlayer-prompt.sh");
        assertTrue(content.contains("http_proxy"), "00-droidlayer-env.sh must configure http_proxy");
    }

    @Test
    void desktopSetupScriptDeploysShellProfiles() throws IOException {
        Path setupScript = getScriptsDir().resolve("droidlayer-desktop-setup.sh");
        String content = Files.readString(setupScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("/etc/profile.d/00-droidlayer-env.sh"),
                "droidlayer-desktop-setup.sh must deploy /etc/profile.d/00-droidlayer-env.sh");
        assertTrue(content.contains("droidlayer-prompt.sh"),
                "droidlayer-desktop-setup.sh must deploy droidlayer-prompt.sh");
        assertTrue(content.contains("aliases.sh"),
                "droidlayer-desktop-setup.sh must deploy aliases.sh");
    }

    @Test
    void initramfsInitProvisionsShellProfilesForAlpineAndDebian() throws IOException {
        Path initScript = getInitramfsDir().resolve("init");
        String content = Files.readString(initScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("install_guest_shell_profile"),
                "init must define install_guest_shell_profile helper");
        assertTrue(content.contains("/etc/profile.d/00-droidlayer-env.sh"),
                "init must provision /etc/profile.d/00-droidlayer-env.sh");
        assertTrue(content.contains("di=01;34"),
                "init profile must include standardized LS_COLORS di=01;34");
        assertTrue(content.contains("ex=01;32"),
                "init profile must include standardized LS_COLORS ex=01;32");
        assertTrue(content.contains("serial-getty@ttyAMA0.service.d/autologin.conf"),
                "init must configure serial-getty autologin for systemd Debian");
    }
}
