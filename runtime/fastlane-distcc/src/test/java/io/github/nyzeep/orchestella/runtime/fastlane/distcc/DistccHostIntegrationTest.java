package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;

/**
 * Host-only integration check: a real distcc client talks TCP to the bridge,
 * the bridge opens a channel backed by a real {@code distccd --inetd} process,
 * and the returned object file is linked and executed.
 *
 * <p>This is explicitly host-only. It proves the byte bridge preserves a real
 * distcc exchange on this machine; it does not prove the Android/Bionic
 * payload path. Skipped when distcc is not installed.
 */
public final class DistccHostIntegrationTest {
    @Test(timeout = 120000)
    public void realDistccCompileRoundTripsThroughTheBridge() throws Exception {
        Path distcc = Path.of("/usr/bin/distcc");
        Path distccd = Path.of("/usr/bin/distccd");
        Path cc = Path.of("/usr/bin/cc");
        Assume.assumeTrue("distcc not installed", Files.isExecutable(distcc));
        Assume.assumeTrue("distccd not installed", Files.isExecutable(distccd));
        Assume.assumeTrue("cc not installed", Files.isExecutable(cc));

        Path work = Files.createTempDirectory("fastlane-distcc-host");
        try {
            Files.writeString(
                    work.resolve("fastlane_lib.c"),
                    "int fastlane_add(int a, int b) { return a + b; }\n",
                    StandardCharsets.UTF_8);
            Files.writeString(
                    work.resolve("fastlane_main.c"),
                    "#include <stdio.h>\n"
                            + "int fastlane_add(int a, int b);\n"
                            + "int main(void) { printf(\"%d\\n\", fastlane_add(40, 2)); return 0; }\n",
                    StandardCharsets.UTF_8);
            Files.createDirectories(work.resolve(".distcc"));

            List<DistccTcpVsockBridge.ConnectionStats> stats = new java.util.concurrent.CopyOnWriteArrayList<>();
            try (DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                    java.net.InetAddress.getLoopbackAddress(),
                    0,
                    () -> spawnInetd(distccd),
                    4,
                    0,
                    30000,
                    stats::add)) {
                bridge.start();
                ProcessBuilder compileBuilder = new ProcessBuilder(
                        distcc.toString(), cc.toString(), "-c", "fastlane_lib.c", "-o", "fastlane_lib.o");
                compileBuilder.directory(work.toFile());
                compileBuilder.redirectErrorStream(true);
                compileBuilder.environment().put("PATH", "/usr/bin:/bin");
                compileBuilder.environment().put("DISTCC_HOSTS", "127.0.0.1:" + bridge.port());
                compileBuilder.environment().put("DISTCC_FALLBACK", "0");
                compileBuilder.environment().put("DISTCC_DIR", work.resolve(".distcc").toString());
                Process compile = compileBuilder.start();
                compile.getOutputStream().close();
                boolean finished = compile.waitFor(90, TimeUnit.SECONDS);
                String output = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue("distcc client timed out; output=" + output, finished);
                assertEquals("distcc client failed: " + output, 0, compile.exitValue());
                assertTrue("object file is empty", Files.size(work.resolve("fastlane_lib.o")) > 0);

                Process link = new ProcessBuilder(
                                cc.toString(), "fastlane_main.c", "fastlane_lib.o", "-o", "fastlane_app")
                        .directory(work.toFile())
                        .redirectErrorStream(true)
                        .start();
                assertTrue(link.waitFor(30, TimeUnit.SECONDS));
                String linkOutput =
                        new String(link.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals("link failed: " + linkOutput, 0, link.exitValue());

                Process run = new ProcessBuilder(work.resolve("fastlane_app").toString())
                        .directory(work.toFile())
                        .redirectErrorStream(true)
                        .start();
                assertTrue(run.waitFor(30, TimeUnit.SECONDS));
                String runOutput = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals("42\n", runOutput);
            }
            assertTrue("bridge did not report a completed job", stats.stream()
                    .anyMatch(item -> item.outcome() == DistccTcpVsockBridge.Outcome.COMPLETED));
            assertTrue("no request bytes were forwarded", stats.stream()
                    .anyMatch(item -> item.bytesFromClient() > 0 && item.bytesToClient() > 0));
        } finally {
            deleteRecursively(work);
        }
    }

    private static FastLaneChannel spawnInetd(Path distccd) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(distccd.toString(), "--inetd");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("PATH", "/usr/bin:/bin");
        // distccd drops root privileges in inetd mode; /tmp is writable by the
        // unprivileged uid, while the JUnit temp directory is 0700.
        builder.environment().put("TMPDIR", "/tmp");
        Process process = builder.start();
        return new FastLaneChannel() {
            @Override
            public InputStream inputStream() {
                return process.getInputStream();
            }

            @Override
            public OutputStream outputStream() {
                return process.getOutputStream();
            }

            @Override
            public void close() throws IOException {
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                }
            }
        };
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
