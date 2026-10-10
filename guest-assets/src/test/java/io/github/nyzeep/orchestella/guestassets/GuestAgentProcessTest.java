package io.github.nyzeep.orchestella.guestassets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
        assumeFalse(isWindows(), "the authenticated session hands off to the Linux guest shell");
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(executable, tokenFile, "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\n");

        assertEquals(0, result.exitCode(), result.standardError());
        assertEquals("ORCHESTELLA/READY 1\n", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void routesTerminalInputToTheGuestShellAfterAValidHandshake() throws Exception {
        assumeFalse(isWindows(), "the terminal handoff targets the Linux guest shell");
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(
                        executable,
                        tokenFile,
                        "ORCHESTELLA/HELLO 1 1 "
                                + TOKEN
                                + "\n"
                                + "printf 'ORCHESTELLA_TERMINAL_SENTINEL\\n'\n"
                                + "exit\n");

        assertEquals(0, result.exitCode(), result.standardError());
        assertEquals(
                "ORCHESTELLA/READY 1\nORCHESTELLA_TERMINAL_SENTINEL\n",
                result.standardOutput());
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
                        "ORCHESTELLA/HELLO 1 1 " + incorrectToken + "\n");

        assertEquals(3, result.exitCode());
        assertEquals("ORCHESTELLA/ERROR unauthorized\n", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void rejectsAProtocolRangeThatDoesNotIncludeVersionOne() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(executable, tokenFile, "ORCHESTELLA/HELLO 2 3 " + TOKEN + "\n");

        assertEquals(2, result.exitCode());
        assertEquals("ORCHESTELLA/ERROR unsupported-version\n", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void rejectsAnInvalidVersionRangeBeforeStartingATerminalSession() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(executable, tokenFile, "ORCHESTELLA/HELLO -1 1 " + TOKEN + "\n");

        assertEquals(1, result.exitCode());
        assertEquals("ORCHESTELLA/ERROR malformed-handshake\n", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void servesPingOnTheConfiguredControlChannel() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(
                        executable,
                        tokenFile,
                        "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\nORCHESTELLA/PING\n",
                        "--terminal-device",
                        "control-terminal-device");

        assertEquals(0, result.exitCode(), result.standardError());
        assertEquals("ORCHESTELLA/READY 1\nORCHESTELLA/PONG 1\n", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void acceptsLegacyDroidlayerHandshakeForCompatibility() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(
                        executable,
                        tokenFile,
                        "DROIDLAYER/HELLO 1 1 " + TOKEN + "\nDROIDLAYER/PING\n",
                        "--terminal-device",
                        "control-terminal-device");

        assertEquals(0, result.exitCode(), result.standardError());
        assertEquals("ORCHESTELLA/READY 1\nORCHESTELLA/PONG 1\n", result.standardOutput());
        assertEquals("", result.standardError());
    }

    @Test
    void refusesToStartWhenTheProvisionedTokenHasAnInvalidFormat() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, "public-default-token\n", StandardCharsets.US_ASCII);

        ProcessResult result =
                runAgent(executable, tokenFile, "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\n");

        assertEquals(65, result.exitCode());
        assertEquals("", result.standardOutput());
        assertEquals("ORCHESTELLA/ERROR token-invalid\n", result.standardError());
    }

    @Test
    void refusesToStartWhenTheProvisionedTokenFileIsMissing() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("missing.token");

        ProcessResult result =
                runAgent(executable, tokenFile, "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\n");

        assertEquals(65, result.exitCode());
        assertEquals("", result.standardOutput());
        assertEquals("ORCHESTELLA/ERROR token-unavailable\n", result.standardError());
    }

    private Path compileAgent() throws Exception {
        Path projectDirectory = Path.of(System.getProperty("guestAssetsProjectDir"));
        Path source = projectDirectory.resolve("agent/guest-agent.c");
        Path executable = temporaryDirectory.resolve(isWindows() ? "guest-agent.exe" : "guest-agent");
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

    private static ProcessResult runAgent(
            Path executable, Path tokenFile, String input, String... additionalArguments)
            throws Exception {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--token-file");
        command.add(tokenFile.toString());
        for (String additionalArgument : additionalArguments) {
            command.add(additionalArgument);
        }
        return runProcess(command, input);
    }

    private static ProcessResult runProcess(List<String> command, String input) throws Exception {
        Process process = new ProcessBuilder(new ArrayList<>(command)).start();
        if (input != null) {
            try {
                process.getOutputStream().write(input.getBytes(StandardCharsets.US_ASCII));
                process.getOutputStream().flush();
            } catch (IOException ignored) {
                // If child process terminates before reading input (e.g. exit on validation failure),
                // writing to stdin may fail with broken pipe.
            }
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Child process may have already exited and closed its stdin pipe.
        }
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

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private record ProcessResult(int exitCode, String standardOutput, String standardError) {}
}
