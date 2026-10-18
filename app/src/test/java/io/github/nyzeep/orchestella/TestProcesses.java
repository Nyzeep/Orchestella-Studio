package io.github.nyzeep.orchestella;

import java.io.IOException;
import java.nio.file.Paths;
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

    private static Process javaChild(String mainClass) throws IOException {
        return new ProcessBuilder(javaExecutable(), "-cp",
                System.getProperty("java.class.path"), mainClass).start();
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
