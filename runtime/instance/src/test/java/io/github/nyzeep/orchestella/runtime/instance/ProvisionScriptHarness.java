package io.github.nyzeep.orchestella.runtime.instance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Test harness for the first-boot provision hardening suite: it locates the
 * repository root, extracts POSIX shell functions from the product provision
 * script (and its generated initramfs copy) and executes them under a real
 * {@code sh} so the JVM tests exercise the shipped source, not a re-implementation.
 *
 * <p>Multi-step scenarios are written to fixture script files and run as
 * {@code sh <fixture> args...}. Inline {@code sh -c} bodies are kept only for
 * the three quote-free one-liners below: Windows argument quoting mangles
 * double quotes nested inside {@code $( )} substitutions, which silently
 * truncates the body at the first inner quote.
 *
 * <p>The harness needs a POSIX {@code sh} on PATH (GitHub runners and Git Bash
 * both provide one); callers {@code Assume} the suite away when it is missing.
 */
final class ProvisionScriptHarness {

    private static final String PRODUCT_SCRIPT = "guest-assets/scripts/orchestella-guest-provision.sh";
    private static final String GENERATED_INIT = "guest-assets/initramfs/init";

    record ShResult(int exitCode, String stdout, String stderr) {
    }

    private ProvisionScriptHarness() {
    }

    /** Walks up from the working directory to the checkout that owns settings.gradle.kts. */
    static Path repositoryRoot() throws IOException {
        String start = System.getProperty("user.dir");
        Path current = Paths.get(start).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
                    && Files.isRegularFile(current.resolve(PRODUCT_SCRIPT))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("no repository root (settings.gradle.kts) above " + start);
    }

    static String readProductScript() throws IOException {
        return Files.readString(repositoryRoot().resolve(PRODUCT_SCRIPT), StandardCharsets.UTF_8);
    }

    static String readGeneratedInit() throws IOException {
        return Files.readString(repositoryRoot().resolve(GENERATED_INIT), StandardCharsets.UTF_8);
    }

    /**
     * Extracts one {@code name() { ... }} definition together with the comment
     * lines that document it, so a drift between the product script and the
     * generated init also covers the semantics carried in those comments.
     */
    static String extractFunction(String source, String functionName) {
        List<String> lines = source.lines().collect(java.util.stream.Collectors.toList());
        int definition = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).equals(functionName + "() {")) {
                definition = i;
                break;
            }
        }
        if (definition < 0) {
            throw new AssertionError("function not found in source: " + functionName);
        }
        int bodyEnd = -1;
        for (int i = definition + 1; i < lines.size(); i++) {
            if (lines.get(i).equals("}")) {
                bodyEnd = i;
                break;
            }
        }
        if (bodyEnd < 0) {
            throw new AssertionError("unterminated function in source: " + functionName);
        }
        int blockStart = definition;
        while (blockStart > 0 && lines.get(blockStart - 1).startsWith("#")) {
            blockStart--;
        }
        StringBuilder block = new StringBuilder();
        for (int i = blockStart; i <= bodyEnd; i++) {
            block.append(lines.get(i)).append('\n');
        }
        return block.toString();
    }

    /** Writes the extracted definitions into one sourcable fixture file. */
    static Path writeFunctionsFixture(Path directory, String... functionBlocks) throws IOException {
        StringBuilder file = new StringBuilder();
        for (String block : functionBlocks) {
            file.append(block).append('\n');
        }
        Path fixture = directory.resolve("functions.sh");
        Files.writeString(fixture, file.toString(), StandardCharsets.UTF_8);
        return fixture;
    }

    /** Writes one scenario as a runnable fixture script. */
    static Path writeScenario(Path directory, String name, String body) throws IOException {
        Path fixture = directory.resolve(name);
        Files.writeString(fixture, body, StandardCharsets.UTF_8);
        return fixture;
    }

    static boolean shAvailable() {
        try {
            return runInline("true").exitCode() == 0;
        } catch (RuntimeException | IOException failed) {
            return false;
        }
    }

    /** Writes a fake tool whose only behavior is exiting with the given code. */
    static void writeFakeTool(Path binDirectory, String name, int exitCode) throws IOException {
        Files.createDirectories(binDirectory);
        Path tool = binDirectory.resolve(name);
        Files.writeString(tool, "#!/bin/sh\nexit " + exitCode + "\n", StandardCharsets.UTF_8);
        // Real POSIX filesystems need the exec bit; Git Bash's noacl mount already
        // treats the file as executable and chmod succeeds as a no-op there.
        ShResult chmod = runInline("chmod +x \"$1\"", toShPath(tool));
        if (chmod.exitCode() != 0) {
            throw new AssertionError("chmod +x failed for " + tool + ": " + chmod.stderr());
        }
    }

    /** Forward-slash absolute form that both Git Bash and Linux sh accept as an argument. */
    static String toShPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** Runs {@code sh <fixture> args...} with optional extra environment entries. */
    static ShResult runScript(Path fixture, Map<String, String> environment, String... args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add("sh");
        command.add(toShPath(fixture));
        command.addAll(List.of(args));
        return runCommand(environment, command);
    }

    /**
     * Runs one quote-safe inline body ({@code sh -c body args...}); no quotes
     * inside {@code $( )} — see the class comment for why.
     */
    static ShResult runInline(String quoteSafeBody, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("sh");
        command.add("-c");
        command.add(quoteSafeBody);
        command.add("sh");
        command.addAll(List.of(args));
        return runCommand(Map.of(), command);
    }

    private static ShResult runCommand(Map<String, String> environment, List<String> command)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(environment);
        Process process;
        try {
            process = builder.start();
        } catch (IOException cannotStart) {
            throw new IOException("sh is not executable on this host", cannotStart);
        }
        try {
            if (!process.waitFor(90L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("sh invocation hung: " + command);
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ShResult(process.exitValue(), stdout, stderr);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running sh", interrupted);
        }
    }

    /** PATH with one fixture bin directory prepended, in the child process's own separator form. */
    static String pathWithPrepended(Path binDirectory, String separator) {
        String existing = System.getenv("PATH");
        return toShPath(binDirectory) + separator + (existing == null ? "" : existing);
    }
}
