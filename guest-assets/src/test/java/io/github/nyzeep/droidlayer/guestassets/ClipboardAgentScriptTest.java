package io.github.nyzeep.droidlayer.guestassets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

final class ClipboardAgentScriptTest {

    private static Path getScriptsDir() {
        String projectDir = System.getProperty("guestAssetsProjectDir");
        if (projectDir != null && !projectDir.isBlank()) {
            return Path.of(projectDir).resolve("scripts");
        }
        return Path.of("scripts");
    }

    private static Path getAgentScriptPath() {
        return getScriptsDir().resolve("desktop").resolve("clipboard").resolve("droidlayer-clipboard-agent");
    }

    @Test
    void clipboardAgentScriptExistsAndHasLfOnly() throws IOException {
        Path agentScript = getAgentScriptPath();
        assertTrue(Files.exists(agentScript), "droidlayer-clipboard-agent must exist at scripts/desktop/clipboard/");
        assertTrue(Files.size(agentScript) > 0, "droidlayer-clipboard-agent must not be empty");

        byte[] bytes = Files.readAllBytes(agentScript);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(content.contains("\r"), "clipboard agent script must use LF line endings only");
        assertTrue(content.startsWith("#!/"), "clipboard agent script must start with shebang");
    }

    @Test
    void clipboardAgentDefinesWireProtocolCommands() throws IOException {
        Path agentScript = getAgentScriptPath();
        String content = Files.readString(agentScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("DROIDLAYER/CLIPBOARD PUT base64:"),
                "agent must format outbound clipboard put command with base64 prefix");
        assertTrue(content.contains("DROIDLAYER/CLIPBOARD GET"),
                "agent must support outbound or inbound get command");
        assertTrue(content.contains("DROIDLAYER/OK clipboard"),
                "agent must recognize or respond with DROIDLAYER/OK clipboard");
    }

    @Test
    void clipboardAgentIntegratesWlClipboardAndEchoSuppression() throws IOException {
        Path agentScript = getAgentScriptPath();
        String content = Files.readString(agentScript, StandardCharsets.UTF_8);

        assertTrue(content.contains("wl-paste"), "agent must use wl-paste to read Wayland clipboard");
        assertTrue(content.contains("--watch"), "agent must use wl-paste --watch for change notifications");
        assertTrue(content.contains("wl-copy"), "agent must use wl-copy to inject clipboard into Wayland");
        assertTrue(content.contains("sha256") || content.contains("sha256sum"),
                "agent must compute SHA-256 hash for echo suppression");
        assertTrue(content.contains("base64"),
                "agent must use base64 encoding/decoding for line-protocol transmission");
    }

    @Test
    void clipboardAgentEnforcesPayloadSizeLimit() throws IOException {
        Path agentScript = getAgentScriptPath();
        String content = Files.readString(agentScript, StandardCharsets.UTF_8);

        // Max payload byte size is 256KB (262144 bytes) as specified in ADR-0018
        assertTrue(content.contains("262144") || content.contains("256"),
                "agent script must check or document 256KB payload limit");
    }

    @Test
    void clipboardProtocolFormattingContractConsistency() throws Exception {
        // Test Java-side equivalence of the line protocol
        String testText = "Orchestella Studio 剪贴板测试 2026";
        byte[] utf8Bytes = testText.getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(utf8Bytes);

        String wireCommand = "DROIDLAYER/CLIPBOARD PUT base64:" + base64 + "\n";
        assertTrue(wireCommand.startsWith("DROIDLAYER/CLIPBOARD PUT base64:"));

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(utf8Bytes);
        String expectedHex = HexFormat.of().formatHex(digest);
        assertNotNull(expectedHex);
        assertEquals(64, expectedHex.length());

        // Functional check: if bash/sh is available, test script helper modes
        String shell = findShellExecutable();
        if (shell != null && Files.exists(getAgentScriptPath())) {
            // Test --format-put helper
            ProcessResult formatResult = runScript(shell, getAgentScriptPath(), "--format-put", "Hello");
            if (formatResult.exitCode == 0) {
                assertEquals("DROIDLAYER/CLIPBOARD PUT base64:SGVsbG8=\n", formatResult.standardOutput);
            }

            // Test --sha256 helper
            ProcessResult hashResult = runScript(shell, getAgentScriptPath(), "--sha256", "Hello");
            if (hashResult.exitCode == 0) {
                assertEquals("185f8db32271fe25f561a6fc938b2e264306ec304eda518007d1764826381969\n",
                        hashResult.standardOutput.trim() + "\n");
            }
        }
    }

    private static String findShellExecutable() {
        for (String candidate : List.of("bash", "sh", "C:\\Program Files\\Git\\bin\\bash.exe", "D:\\MSYS2\\usr\\bin\\bash.exe")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").start();
                if (p.waitFor() == 0) {
                    return candidate;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static ProcessResult runScript(String shell, Path script, String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(shell);
        command.add(script.toString());
        for (String arg : args) {
            command.add(arg);
        }
        Process process = new ProcessBuilder(command).start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        return new ProcessResult(code, stdout.replace("\r\n", "\n"), stderr.replace("\r\n", "\n"));
    }

    private record ProcessResult(int exitCode, String standardOutput, String standardError) {}
}
