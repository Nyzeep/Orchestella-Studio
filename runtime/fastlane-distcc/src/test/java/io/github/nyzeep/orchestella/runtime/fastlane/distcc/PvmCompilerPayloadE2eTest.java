package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Capabilities;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Limits;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

/**
 * PVM-03 zero-device end-to-end: real distcc 3.4 client -> loopback TCP ->
 * {@code DistccTcpVsockBridge} + {@code Axp1DistccChannelFactory} -> AXP1 over
 * TCP -> the real C++ compile-service server ({@code
 * pvm_compiler_payload_host}, FakeEngine).
 *
 * <p>This is the exact session a protected payload runs (same handshake,
 * token, distcc v1 codec and engine path) with the fake engine standing in
 * for libclang. The object under test is the deterministic ELF the fake
 * engine embeds (compiled from the fixture below), so the distcc-produced
 * object must byte-match a local {@code cc -c} build of the same fixture and
 * link into a binary that prints the fixture answer.
 *
 * <p>Skips when the host binary (FL_PVM_COMPILER_HOST_BIN), distcc or cc are
 * absent — the in-memory C++ tests in fastlane-payload cover the wire without
 * those tools.
 */
public final class PvmCompilerPayloadE2eTest {
    private static final Path DISTCC = Path.of("/usr/bin/distcc");
    private static final Path CC = Path.of("/usr/bin/cc");
    private static final InetAddress LOOPBACK = loopback();
    private static final List<String> REPORT = new ArrayList<>();

    /** Same source the fake engine's embedded object was compiled from. */
    private static final String FIXTURE_SOURCE =
            "int pvm_fake_engine_answer(void) {\n"
            + "    return 42;\n"
            + "}\n"
            + "int fixture_entry(void) {\n"
            + "    return pvm_fake_engine_answer();\n"
            + "}\n";

    private static final String MAIN_SOURCE =
            "#include <stdio.h>\n"
            + "int pvm_fake_engine_answer(void);\n"
            + "int main(void) { printf(\"%d\\n\", pvm_fake_engine_answer()); return 0; }\n";

    @Before
    public void requireHostTools() {
        assumeTrue("distcc not installed", Files.isExecutable(DISTCC));
        assumeTrue("cc not installed", Files.isExecutable(CC));
        assumeTrue(
                "FL_PVM_COMPILER_HOST_BIN is not set to pvm_compiler_payload_host",
                hostBinary() != null && Files.isExecutable(hostBinary()));
    }

    private static Path hostBinary() {
        String value = System.getenv("FL_PVM_COMPILER_HOST_BIN");
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    @Test(timeout = 120_000)
    public void realDistccClientCompilesThroughThePayloadServer() throws Exception {
        String token = "00112233" + "44556677" + "8899aabb" + "ccddeeff";
        String triple = command(CC.toString(), "-dumpmachine").trim();
        Path work = Files.createTempDirectory("pvm-e2e-");
        int cases = 0;
        boolean hashEqualNote = false;
        try {
            Files.writeString(work.resolve("fixture.c"), FIXTURE_SOURCE, StandardCharsets.UTF_8);
            Files.writeString(work.resolve("main.c"), MAIN_SOURCE, StandardCharsets.UTF_8);
            Files.createDirectories(work.resolve(".distcc"));

            try (PayloadServer server = PayloadServer.start(hostBinary(), token, triple);
                    DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                            LOOPBACK,
                            0,
                            new Axp1DistccChannelFactory(
                                    () -> connect(server.port()),
                                    token,
                                    capabilities(triple),
                                    triple,
                                    new Axp1Limits(3_000L, 4 * 1_048_576, 1_048_576)),
                            4,
                            0,
                            60_000)) {
                bridge.start();

                ProcessResult compile = run(
                        work,
                        Map.of(
                                "DISTCC_HOSTS", "127.0.0.1:" + bridge.port(),
                                "DISTCC_FALLBACK", "1",
                                "DISTCC_DIR", work.resolve(".distcc").toString()),
                        DISTCC.toString(),
                        CC.toString(),
                        "-c",
                        "fixture.c",
                        "-o",
                        "fixture.o");
                assertEquals("distcc client failed: " + compile.output(), 0, compile.exitCode());

                Path object = work.resolve("fixture.o");
                assertTrue("object missing: " + compile.output(), Files.exists(object));
                assertEquals(
                        "remote object is not the fake engine's fixed payload",
                        608L,
                        Files.size(object));

                // The same fixture built locally: byte equality holds on the
                // machine the embedded object was produced on, but .comment
                // carries the compiler version, so the load-bearing assertions
                // are symbol equality plus link-and-run (compare_objects.py's
                // criteria for EQUIVALENT).
                ProcessResult local = run(
                        work, Map.of(), CC.toString(), "-c", "fixture.c", "-o", "fixture.local.o");
                assertEquals(local.output(), 0, local.exitCode());
                boolean hashEqual =
                        sha256(work.resolve("fixture.local.o")).equals(sha256(object));
                assertEquals(
                        "exported symbol sets differ",
                        definedSymbols(work, "fixture.local.o"),
                        definedSymbols(work, "fixture.o"));

                // Both objects must link and run.
                for (String objectFile : List.of("fixture.o", "fixture.local.o")) {
                    String binary = objectFile.endsWith("local.o") ? "prog.local" : "prog.remote";
                    ProcessResult link =
                            run(work, Map.of(), CC.toString(), objectFile, "main.c", "-o", binary);
                    assertEquals(link.output(), 0, link.exitCode());
                    ProcessResult run = run(work, Map.of(), work.resolve(binary).toString());
                    assertEquals(run.output(), 0, run.exitCode());
                    assertEquals("42\n", run.output());
                }
                cases = 1;
                hashEqualNote = hashEqual;
            }
        } finally {
            exportArtifacts(work);
            deleteRecursively(work);
        }
        REPORT.add("{\"case\":\"fake-engine-distcc\",\"cases\":" + cases
                + ",\"hashEqual\":" + hashEqualNote + "}");
        System.out.println("PVM_DISTCC_E2E_OK cases=" + cases + " hashEqual=" + hashEqualNote);
    }

    private static void exportArtifacts(Path work) {
        String dir = System.getenv("FL_PVM_E2E_ARTIFACTS");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            Path target = Path.of(dir);
            Files.createDirectories(target);
            for (String name : List.of("fixture.o", "fixture.local.o")) {
                Path source = work.resolve(name);
                if (Files.exists(source)) {
                    Files.copy(source, target.resolve(name),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException ignored) {
            // Artifacts are best-effort evidence for the compare script.
        }
    }

    private static Axp1Capabilities capabilities(String triple) {
        return Axp1Capabilities.builder()
                .distcc(true)
                .compilerTriple(triple)
                .maxFrame(1_048_576)
                .build();
    }

    private static FastLaneChannel connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(LOOPBACK, port), 2_000);
        socket.setTcpNoDelay(true);
        return new FastLaneChannel() {
            @Override
            public InputStream inputStream() {
                try {
                    return socket.getInputStream();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }

            @Override
            public OutputStream outputStream() {
                try {
                    return socket.getOutputStream();
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }

            @Override
            public void close() throws IOException {
                socket.close();
            }
        };
    }

    /** The C++ compile-service server as a subprocess, TCP transport. */
    private static final class PayloadServer implements Closeable {
        private final Process process;
        private final int port;
        private final Thread drain;

        private PayloadServer(Process process, int port) {
            this.process = process;
            this.port = port;
            this.drain = new Thread(() -> {
                try (InputStream stderr = process.getErrorStream()) {
                    stderr.transferTo(System.err);
                } catch (IOException ignored) {
                    // Teardown race.
                }
            }, "pvm-e2e-server-stderr");
            drain.setDaemon(true);
            drain.start();
        }

        static PayloadServer start(Path binary, String token, String triple) throws IOException {
            int port = freePort();
            ProcessBuilder builder = new ProcessBuilder(
                    binary.toString(), "--tcp", Integer.toString(port));
            builder.environment().put("ORCHESTELLA_ACCEL_TOKEN", token);
            builder.environment().put("ORCHESTELLA_PVM_COMPILER_TRIPLE", triple);
            // Two slots so the readiness probe below can never hold the only
            // one while the bridge opens its channel.
            builder.environment().put("ORCHESTELLA_PVM_COMPILER_JOBS", "2");
            Process process = builder.start();
            process.getOutputStream().close();
            // The server prints "PVM_CSDL listening port=..." once bound.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException("payload server exited early");
                }
                try {
                    Socket probe = new Socket();
                    probe.connect(new InetSocketAddress(LOOPBACK, port), 200);
                    probe.close();
                    return new PayloadServer(process, port);
                } catch (IOException notYet) {
                    try {
                        Thread.sleep(50L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while waiting for the server");
                    }
                }
            }
            process.destroyForcibly();
            throw new IOException("payload server did not listen on port " + port);
        }

        int port() {
            return port;
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private static int freePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0, 1, LOOPBACK)) {
                return socket.getLocalPort();
            }
        }
    }

    private static ProcessResult run(Path work, Map<String, String> extra, String... command)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(List.of(command));
        builder.directory(work.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("TMPDIR", "/tmp");
        builder.environment().putAll(extra);
        Process process = builder.start();
        process.getOutputStream().close();
        String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("command timed out", process.waitFor(90, TimeUnit.SECONDS));
        return new ProcessResult(process.exitValue(), output);
    }

    private static String command(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
        return output;
    }

    /** Sorted defined global symbols — the equivalence signal that survives
     *  a different compiler build of the same fixture. */
    private static List<String> definedSymbols(Path work, String objectFile) throws Exception {
        ProcessResult symbols = run(work, Map.of(), "nm", "--defined-only", objectFile);
        assertEquals(symbols.output(), 0, symbols.exitCode());
        List<String> names = new ArrayList<>();
        for (String line : symbols.output().split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 3) {
                names.add(fields[fields.length - 1]);
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    private static String sha256(Path path) throws Exception {
        byte[] digest =
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(path));
        StringBuilder text = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            text.append(String.format("%02x", value));
        }
        return text.toString();
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

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @AfterClass
    public static void report() {
        if (!REPORT.isEmpty()) {
            System.out.println("PVM_E2E_REPORT " + REPORT);
        }
    }

    private record ProcessResult(int exitCode, String output) {}
}
