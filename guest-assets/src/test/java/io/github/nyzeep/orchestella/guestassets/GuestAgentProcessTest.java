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
    void repeatsAValidHelloWithoutClosingTheExistingControlChannel() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);
        String hello = "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\n";
        ProcessResult result = runAgent(executable, tokenFile,
                hello + "ORCHESTELLA/PING\n" + hello + "DROIDLAYER/PING\n",
                "--terminal-device", "control-terminal-device");
        assertEquals(0, result.exitCode(), result.standardError());
        assertEquals("ORCHESTELLA/READY 1\nORCHESTELLA/PONG 1\nORCHESTELLA/READY 1\nORCHESTELLA/PONG 1\n",
                result.standardOutput());
    }

    @Test
    void reauthenticatesRepeatedHelloAndRejectsAnIncorrectToken() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);
        ProcessResult result = runAgent(executable, tokenFile,
                "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\n"
                        + "ORCHESTELLA/HELLO 1 1 " + "1" + TOKEN.substring(1) + "\n"
                        + "ORCHESTELLA/PING\n",
                "--terminal-device", "control-terminal-device");
        assertEquals(3, result.exitCode());
        assertEquals("ORCHESTELLA/READY 1\nORCHESTELLA/ERROR unauthorized\n", result.standardOutput());
    }

    @Test
    void rejectsUnsupportedOrMalformedRepeatedHello() throws Exception {
        Path executable = compileAgent();
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);
        ProcessResult version = runAgent(executable, tokenFile,
                "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\nDROIDLAYER/HELLO 2 3 " + TOKEN + "\n",
                "--terminal-device", "control-terminal-device");
        assertEquals(2, version.exitCode());
        assertEquals("ORCHESTELLA/READY 1\nORCHESTELLA/ERROR unsupported-version\n", version.standardOutput());
        ProcessResult malformed = runAgent(executable, tokenFile,
                "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\nORCHESTELLA/HELLO 1 1 " + TOKEN + " extra\n",
                "--terminal-device", "control-terminal-device");
        assertEquals(1, malformed.exitCode());
        assertEquals("ORCHESTELLA/READY 1\nORCHESTELLA/ERROR malformed-handshake\n", malformed.standardOutput());
    }

    @Test
    void dispatchesExistingPowerActionsWithFixedArgvUsingCompileTimeMockOnly() throws Exception {
        Path executable = compileAgent(true); // Never run Linux or Windows host power utilities.
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Path dispatches = temporaryDirectory.resolve("dispatches.txt");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);
        for (String action : List.of("shutdown", "poweroff")) {
            Files.deleteIfExists(dispatches);
            ProcessResult result = runProcess(List.of(executable.toString(), "--token-file", tokenFile.toString(),
                    "--terminal-device", "synthetic-terminal"),
                    "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\nORCHESTELLA/POWER " + action + "\n",
                    java.util.Map.of("ORCHESTELLA_TEST_POWER_RECORD", dispatches.toString()));
            assertEquals(0, result.exitCode(), result.standardError());
            assertEquals("ORCHESTELLA/READY 1\nORCHESTELLA/OK power\n", result.standardOutput());
            assertEquals("/sbin/poweroff\n", Files.readString(dispatches).replace("\r\n", "\n"));
        }
    }

    @Test
    void powerDispatchFailuresCannotReturnSuccessOrExecuteAnAction() throws Exception {
        Path executable = compileAgent(true);
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Path dispatches = temporaryDirectory.resolve("dispatches.txt");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);
        for (String fault : List.of("access", "fork", "pipe", "reply")) {
            Files.deleteIfExists(dispatches);
            ProcessResult result = runProcess(List.of(executable.toString(), "--token-file", tokenFile.toString(),
                    "--terminal-device", "synthetic-terminal"),
                    "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\nORCHESTELLA/POWER shutdown\nORCHESTELLA/PING\n",
                    java.util.Map.of("ORCHESTELLA_TEST_POWER_RECORD", dispatches.toString(),
                            "ORCHESTELLA_TEST_POWER_FAULT", fault));
            assertEquals("reply".equals(fault) ? 70 : 0, result.exitCode(), result.standardError());
            assertEquals("reply".equals(fault) ? "ORCHESTELLA/READY 1\n"
                    : "ORCHESTELLA/READY 1\nORCHESTELLA/ERROR power-unavailable\nORCHESTELLA/PONG 1\n",
                    result.standardOutput());
            assertEquals(0L, Files.exists(dispatches) ? Files.size(dispatches) : 0L);
        }
    }

    @Test
    void malformedPowerActionsNeverReachTheDispatchSeam() throws Exception {
        Path executable = compileAgent(true);
        Path tokenFile = temporaryDirectory.resolve("bridge.token");
        Path dispatches = temporaryDirectory.resolve("dispatches.txt");
        Files.writeString(tokenFile, TOKEN + "\n", StandardCharsets.US_ASCII);
        for (String action : List.of("reboot", "shutdown now", "poweroff extra", "shutdown;touch /tmp/sentinel")) {
            Files.deleteIfExists(dispatches);
            ProcessResult result = runProcess(List.of(executable.toString(), "--token-file", tokenFile.toString(),
                    "--terminal-device", "synthetic-terminal"),
                    "ORCHESTELLA/HELLO 1 1 " + TOKEN + "\nORCHESTELLA/POWER " + action + "\n",
                    java.util.Map.of("ORCHESTELLA_TEST_POWER_RECORD", dispatches.toString()));
            assertEquals(4, result.exitCode());
            assertEquals("ORCHESTELLA/READY 1\nORCHESTELLA/ERROR malformed-command\n", result.standardOutput());
            assertEquals(0L, Files.exists(dispatches) ? Files.size(dispatches) : 0L);
        }
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
        return compileAgent(false);
    }

    private Path compileAgent(boolean mockPower) throws Exception {
        Path projectDirectory = Path.of(System.getProperty("guestAssetsProjectDir"));
        Path source = projectDirectory.resolve("agent/guest-agent.c");
        String name = mockPower ? "guest-agent-power-mock" : "guest-agent";
        Path executable = temporaryDirectory.resolve(name + (isWindows() ? ".exe" : ""));
        String compiler = System.getenv().getOrDefault("CC", "gcc");
        List<String> command = new ArrayList<>(List.of(compiler, "-std=c11", "-Wall", "-Wextra", "-Werror"));
        if (mockPower) command.add("-DORCHESTELLA_TEST_POWER");
        command.addAll(List.of(source.toString(), "-o", executable.toString()));
        ProcessResult result = runProcess(command, null);
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
        return runProcess(command, input, java.util.Map.of());
    }

    private static ProcessResult runProcess(List<String> command, String input,
            java.util.Map<String, String> environment) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.environment().putAll(environment);
        Process process = builder.start();
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
