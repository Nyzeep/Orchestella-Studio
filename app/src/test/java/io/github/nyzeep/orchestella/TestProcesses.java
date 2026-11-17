package io.github.nyzeep.orchestella;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cross-platform child processes for tests that need a real OS process. */
public final class TestProcesses {
    private TestProcesses() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
    }

    /**
     * A process that stays alive until killed. POSIX uses a shell that ignores
     * SIGTERM so the forced-stop path is exercised; Windows has no equivalent
     * signal, so a plain Java sleeper covers the bounded-stop path.
     */
    public static Process longRunning() throws IOException {
        if (isWindows()) return javaChild(Sleeper.class.getName());
        return new ProcessBuilder("/bin/sh", "-c", "trap '' TERM; read x").start();
    }

    /** A real process that has already exited by the time it is observed. */
    public static Process exitImmediately() throws IOException {
        return javaChild(ExitImmediately.class.getName());
    }

    /**
     * A child java command whose classpath survives the Windows CreateProcess
     * 32 KiB command-line ceiling: the test runtime classpath exceeds it, so
     * on Windows the classpath travels through a JVM @argfile instead of the
     * command line. POSIX keeps the plain {@code -cp} form.
     */
    public static List<String> javaChildCommand(String mainClass, String... args)
            throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        if (isWindows()) {
            Path argfile = Files.createTempFile("orchestella-test-cp", ".args");
            argfile.toFile().deleteOnExit();
            String classpath = System.getProperty("java.class.path");
            String quoted = '"' + classpath
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"") + '"';
            Files.write(argfile,
                    ("-cp " + quoted + "\n").getBytes(StandardCharsets.UTF_8));
            command.add("@" + argfile.toAbsolutePath());
        } else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
        }
        command.add(mainClass);
        for (String argument : args) command.add(argument);
        return command;
    }

    private static Process javaChild(String mainClass) throws IOException {
        return new ProcessBuilder(javaChildCommand(mainClass)).start();
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(30_000L);
        }
    }

    public static final class ExitImmediately {
        public static void main(String[] args) {
        }
    }
}
