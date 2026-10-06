package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

/**
 * Verifies that guest shell assets (Starship prompt, aliases, LS_COLORS) are packaged
 * into Android application assets with Unix LF line endings and matching source content.
 */
public final class GuestShellAssetsPackagingTest {

    private static Path getAssetsShellDir() {
        // Resolve app/src/main/assets/shell whether executed from project root or :app
        Path direct = Paths.get("src", "main", "assets", "shell");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path fromRoot = Paths.get("app", "src", "main", "assets", "shell");
        if (Files.isDirectory(fromRoot)) {
            return fromRoot;
        }
        return direct;
    }

    private static Path getGuestAssetsShellDir() {
        Path direct = Paths.get("guest-assets", "shell");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path parent = Paths.get("..", "guest-assets", "shell");
        if (Files.isDirectory(parent)) {
            return parent;
        }
        return direct;
    }

    @Test
    public void packagedPromptAssetExistsAndUsesLfLineEndingsOnly() throws IOException {
        Path promptAsset = getAssetsShellDir().resolve("droidlayer-prompt.sh");
        assertTrue("droidlayer-prompt.sh must exist in app assets", Files.exists(promptAsset));
        assertTrue("droidlayer-prompt.sh must not be empty", Files.size(promptAsset) > 0);

        byte[] bytes = Files.readAllBytes(promptAsset);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse("Prompt asset must not contain CR line endings", content.contains("\r"));
        assertTrue("Prompt asset must start with POSIX shebang", content.startsWith("#!/bin/sh"));
    }

    @Test
    public void packagedPromptAssetContainsTwoLineStarshipStyling() throws IOException {
        Path promptAsset = getAssetsShellDir().resolve("droidlayer-prompt.sh");
        byte[] bytes = Files.readAllBytes(promptAsset);
        String content = new String(bytes, StandardCharsets.UTF_8);

        // Path (cyan \e[36m), Git (yellow \e[33m), Exit code (red \e[31m and ✘)
        assertTrue("Must include cyan working directory coloring", content.contains("36m"));
        assertTrue("Must include yellow git branch coloring", content.contains("33m"));
        assertTrue("Must include red exit code coloring", content.contains("31m"));
        assertTrue("Must include ✘ symbol for non-zero exit", content.contains("✘"));

        // Modern arrow ❯ (green \e[32m for user, magenta \e[35m for root)
        assertTrue("Must include modern arrow ❯", content.contains("❯"));
        assertTrue("Must include green arrow for normal users", content.contains("32m"));
        assertTrue("Must include magenta arrow for root user", content.contains("35m"));

        // Universal bash & ash support
        assertTrue("Must support BASH_VERSION mode", content.contains("BASH_VERSION"));
        assertTrue("Must support ash/sh fallback", content.contains("PS1"));
    }

    @Test
    public void packagedAliasesAssetExistsAndDefinesRequiredAliases() throws IOException {
        Path aliasesAsset = getAssetsShellDir().resolve("aliases.sh");
        assertTrue("aliases.sh must exist in app assets", Files.exists(aliasesAsset));
        assertTrue("aliases.sh must not be empty", Files.size(aliasesAsset) > 0);

        byte[] bytes = Files.readAllBytes(aliasesAsset);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse("Aliases asset must not contain CR line endings", content.contains("\r"));
        assertTrue("Aliases asset must start with POSIX shebang", content.startsWith("#!/bin/sh"));

        List<String> requiredAliases = List.of(
                "alias ll='ls -lah --color=auto'",
                "alias la='ls -A --color=auto'",
                "alias l='ls -CF --color=auto'",
                "alias grep='grep --color=auto'",
                "alias df='df -h'",
                "alias free='free -m'");
        for (String expected : requiredAliases) {
            assertTrue("Aliases must define: " + expected, content.contains(expected));
        }
    }

    @Test
    public void packagedEnvAssetExportsStandardizedLsColors() throws IOException {
        Path envAsset = getAssetsShellDir().resolve("00-droidlayer-env.sh");
        assertTrue("00-droidlayer-env.sh must exist in app assets", Files.exists(envAsset));
        assertTrue("00-droidlayer-env.sh must not be empty", Files.size(envAsset) > 0);

        byte[] bytes = Files.readAllBytes(envAsset);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse("Env asset must not contain CR line endings", content.contains("\r"));

        assertTrue("Must export LS_COLORS", content.contains("export LS_COLORS="));
        assertTrue("LS_COLORS must set directories to bright blue bold (di=01;34)", content.contains("di=01;34"));
        assertTrue("LS_COLORS must set executables to bright green bold (ex=01;32)", content.contains("ex=01;32"));
        assertTrue("LS_COLORS must set symlinks to cyan bold (ln=01;36)", content.contains("ln=01;36"));

        // Compression archives
        assertTrue("LS_COLORS must color .tar archives", content.contains("*.tar=01;31"));
        assertTrue("LS_COLORS must color .zip archives", content.contains("*.zip=01;33"));
        assertTrue("LS_COLORS must color .gz archives", content.contains("*.gz=01;31"));
        assertTrue("LS_COLORS must color .zst archives", content.contains("*.zst=01;31"));

        // Source code extensions
        assertTrue("LS_COLORS must color C source", content.contains("*.c="));
        assertTrue("LS_COLORS must color Rust source", content.contains("*.rs="));
        assertTrue("LS_COLORS must color Python source", content.contains("*.py="));
        assertTrue("LS_COLORS must color Go source", content.contains("*.go="));
        assertTrue("LS_COLORS must color Java source", content.contains("*.java="));
        assertTrue("LS_COLORS must color JavaScript source", content.contains("*.js="));

        // Links to prompt and aliases
        assertTrue("00-droidlayer-env.sh must wire aliases.sh", content.contains("aliases.sh"));
        assertTrue("00-droidlayer-env.sh must wire droidlayer-prompt.sh", content.contains("droidlayer-prompt.sh"));
    }

    @Test
    public void packagedAssetsMatchGuestAssetsSourceExactBytes() throws IOException {
        Path sourceDir = getGuestAssetsShellDir();
        Path targetDir = getAssetsShellDir();

        if (Files.isDirectory(sourceDir)) {
            List<String> files = List.of("droidlayer-prompt.sh", "aliases.sh", "00-droidlayer-env.sh");
            for (String file : files) {
                Path src = sourceDir.resolve(file);
                Path tgt = targetDir.resolve(file);
                assertTrue("Source script must exist: " + src, Files.exists(src));
                assertTrue("Packaged asset must exist: " + tgt, Files.exists(tgt));
                assertArrayEquals(
                        "Asset byte contents must match source verbatim: " + file,
                        Files.readAllBytes(src),
                        Files.readAllBytes(tgt));
            }
        }
    }
}
