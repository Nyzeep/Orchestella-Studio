package io.github.nyzeep.orchestella;

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

    private static Path getGuestAssetsInitramfsInit() {
        Path direct = Paths.get("guest-assets", "initramfs", "init");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path parent = Paths.get("..", "guest-assets", "initramfs", "init");
        if (Files.isRegularFile(parent)) {
            return parent;
        }
        return direct;
    }

    private static Path getGuestInitResource() {
        Path direct = Paths.get("src", "main", "resources", "guest_init");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path parent = Paths.get("app", "src", "main", "resources", "guest_init");
        if (Files.isRegularFile(parent)) {
            return parent;
        }
        return direct;
    }

    @Test
    public void packagedPromptAssetExistsAndUsesLfLineEndingsOnly() throws IOException {
        Path promptAsset = getAssetsShellDir().resolve("orchestella-prompt.sh");
        assertTrue("orchestella-prompt.sh must exist in app assets", Files.exists(promptAsset));
        assertTrue("orchestella-prompt.sh must not be empty", Files.size(promptAsset) > 0);

        byte[] bytes = Files.readAllBytes(promptAsset);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse("Prompt asset must not contain CR line endings", content.contains("\r"));
        assertTrue("Prompt asset must start with POSIX shebang", content.startsWith("#!/bin/sh"));
    }

    @Test
    public void packagedPromptAssetContainsTwoLineStarshipStyling() throws IOException {
        Path promptAsset = getAssetsShellDir().resolve("orchestella-prompt.sh");
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
        Path envAsset = getAssetsShellDir().resolve("00-orchestella-env.sh");
        assertTrue("00-orchestella-env.sh must exist in app assets", Files.exists(envAsset));
        assertTrue("00-orchestella-env.sh must not be empty", Files.size(envAsset) > 0);

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
        assertTrue("00-orchestella-env.sh must wire aliases.sh", content.contains("aliases.sh"));
        assertTrue("00-orchestella-env.sh must wire orchestella-prompt.sh", content.contains("orchestella-prompt.sh"));
    }

    @Test
    public void packagedAssetsMatchGuestAssetsSourceExactBytes() throws IOException {
        Path sourceDir = getGuestAssetsShellDir();
        Path targetDir = getAssetsShellDir();

        // guest-assets/shell/ 是唯一权威源；syncGuestShellAssets 构建时同步到 APK
        // assets。逐脚本遍历而非硬编码清单：新增脚本自动纳入校验，删除任意一个
        // 现有脚本（含 20-orchestella-terminal-integration.sh）都会在此拦截。
        assertTrue("guest-assets/shell authoritative source must exist: " + sourceDir,
                Files.isDirectory(sourceDir));
        assertTrue("APK assets/shell target must exist: " + targetDir, Files.isDirectory(targetDir));
        List<Path> scripts = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> entries = Files.list(sourceDir)) {
            entries.filter(path -> path.getFileName().toString().endsWith(".sh"))
                    .sorted()
                    .forEach(scripts::add);
        }
        assertTrue("Expected all 8 guest shell scripts, found " + scripts.size(), scripts.size() >= 8);
        for (Path src : scripts) {
            Path tgt = targetDir.resolve(src.getFileName().toString());
            assertTrue("Packaged asset must exist: " + tgt, Files.exists(tgt));
            assertArrayEquals(
                    "Asset byte contents must match source verbatim: " + src.getFileName(),
                    Files.readAllBytes(src),
                    Files.readAllBytes(tgt));
        }
    }

    @Test
    public void guestInitTemplatesEmbedCanonicalTerminalIntegrationHook() throws IOException {
        Path hook = getGuestAssetsShellDir().resolve("20-orchestella-terminal-integration.sh");
        assertTrue("Canonical terminal hook must exist in guest-assets/shell", Files.exists(hook));
        String canonical = new String(Files.readAllBytes(hook), StandardCharsets.UTF_8);
        assertFalse("Canonical hook must use LF line endings only", canonical.contains("\r"));
        // PS2 归一化门：剥除自身包装后必须先记录 set 状态，仅 unset 才回填 '> '。
        assertTrue("Canonical hook must keep the ${PS2+x} set-state guard", canonical.contains("${PS2+x}"));

        // guest-assets/scripts/sync-guest-init.py 将 hook 逐字节嵌入 initramfs/init 与
        // app/src/main/resources/guest_init；两个生成模板不得与权威源漂移。
        Path init = getGuestAssetsInitramfsInit();
        assertTrue("initramfs/init must exist: " + init, Files.exists(init));
        assertTrue("initramfs/init must embed the canonical terminal hook bytes",
                new String(Files.readAllBytes(init), StandardCharsets.UTF_8).contains(canonical));

        Path resource = getGuestInitResource();
        assertTrue("guest_init APK template must exist (run sync-guest-init.py --prepare-resource): " + resource,
                Files.exists(resource));
        String resourceContent = new String(Files.readAllBytes(resource), StandardCharsets.UTF_8);
        assertFalse("guest_init template must use LF line endings only", resourceContent.contains("\r"));
        assertTrue("guest_init template must embed the canonical terminal hook bytes",
                resourceContent.contains(canonical));
    }
}
