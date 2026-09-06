package io.github.nyzeep.droidlayer.guestassets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GuestAgentProcessTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @TempDir Path temporaryDirectory;

    @Test
    void acceptsAProvisionedTokenAndNegotiatesProtocolVersionOne() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(executable, tokenFile, "DROIDLAYER/HELLO 1 1 " + TOKEN + "\n");

        assertEquals(0, result.exitCode(), result.standardError());
        assertEquals("DROIDLAYER/READY 1\n", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void rejectsAnIncorrectTokenWithoutStartingAProtocolSession() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);
        String incorrectToken =
                "1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        ProcessResult result =
                runAgent(
                        executable,
                        tokenFile,
                        "DROIDLAYER/HELLO 1 1 " + incorrectToken + "\n");

        assertEquals(3, result.exitCode());
        assertEquals("DROIDLAYER/ERROR unauthorized\n", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void rejectsAProtocolRangeThatDoesNotIncludeVersionOne() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(executable, tokenFile, "DROIDLAYER/HELLO 2 3 " + TOKEN + "\n");

        assertEquals(2, result.exitCode());
        assertEquals("DROIDLAYER/ERROR unsupported-version\n", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void refusesToStartWhenTheProvisionedTokenHasAnInvalidFormat() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, "public-default-token\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(executable, tokenFile, "DROIDLAYER/HELLO 1 1 " + TOKEN + "\n");

        assertEquals(65, result.exitCode());
        assertEquals("", result.standardOutput());
        assertEquals("DROIDLAYER/ERROR token-invalid\n", result.standardError());
    }

    @Test
    void refusesToStartWhenTheProvisionedTokenFileIsMissing() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("missing.token");

        ProcessResult result =
                runAgent(executable, tokenFile, "DROIDLAYER/HELLO 1 1 " + TOKEN + "\n");

        assertEquals(65, result.exitCode());
        assertEquals("", result.standardOutput());
        assertEquals("DROIDLAYER/ERROR token-unavailable\n", result.standardError());
    }

    private Path compileAgent() throws Exception {
        Path projectDirectory = Path.of(System.getProperty("guestAssetsProjectDir"));
        Path source = projectDirectory.resolve("agent/guest-agent.c");
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path executable = temporaryDirectory.resolve(windows ? "guest-agent.exe" : "guest-agent");
        String compiler = System.getenv().getOrDefault("CC", "gcc");
        ProcessResult result =
                runProcess(
                        List.of(
                                compiler,
                                "-std=c11",
                                "-Wall",
                                "-Wextra",
                                "-Werror",
                                source.toString(),
                                "-o",
                                executable.toString()),
                        null);
        assertEquals(0, result.exitCode(), result.standardError());
        return executable;
    }

    private static ProcessResult runAgent(Path executable, Path tokenFile, String input)
            throws Exception {
        return runProcess(
                List.of(executable.toString(), "--token-file", tokenFile.toString()), input);
    }

    private static ProcessResult runProcess(List<String> command, String input) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(command)).start();
        if (input != null) {
            process.getOutputStream().write(input.getBytes(StandardCharsets.US_ASCII));
        }
        process.getOutputStream().close();
        String standardOutput = readFully(process.getInputStream());
        String standardError = readFully(process.getErrorStream());
        int exitCode = process.waitFor();
        return new ProcessResult(
                exitCode,
                standardOutput.replace("\r\n", "\n"),
                standardError.replace("\r\n", "\n"));
    }

    private static String readFully(java.io.InputStream input) throws IOException {
        return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
    }

    private record ProcessResult(int exitCode, String standardOutput, String standardError) {}
}
