package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Capabilities;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Connection;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Exception;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Limits;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Role;
import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Transport;
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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

/**
 * AX-06 host-only end-to-end: real distcc client -> loopback TCP -> AXP1 HOST
 * -> loopback AXP1 GUEST simulator -> real {@code distccd --inetd}.
 *
 * <p>This proves the AXP1 framing, auth, triple check and stdio pump with the
 * real distcc protocol on this host. It does not prove the Android/vsock or
 * device path. Tests skip when distcc is not installed.
 */
public final class Axp1DistccHostIntegrationTest {
    private static final Path DISTCC = Path.of("/usr/bin/distcc");
    private static final Path DISTCCD = Path.of("/usr/bin/distccd");
    private static final Path CC = Path.of("/usr/bin/cc");
    private static final InetAddress LOOPBACK = loopback();
    private static final List<String> REPORT = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void requireHostTools() {
        assumeTrue("distcc not installed", Files.isExecutable(DISTCC));
        assumeTrue("distccd not installed", Files.isExecutable(DISTCCD));
        assumeTrue("cc not installed", Files.isExecutable(CC));
    }

    @Test(timeout = 120_000)
    public void realDistccCompileThroughAxp1ProducesARunnableObject() throws Exception {
        CompileResult result = runCase(Mode.SERVE);

        assertEquals(OutcomeKind.COMPLETED, result.outcome());
        assertEquals(DistccTcpVsockBridge.ConnectionStats.NO_FAILURE, result.failureCode());
        assertTrue(result.bytesFromClient() > 0L);
        assertTrue(result.bytesToClient() > 0L);
        record("success", result);
    }

    @Test(timeout = 120_000)
    public void wrongTokenFallsBackToALocalObject() throws Exception {
        CompileResult result = runCase(Mode.WRONG_TOKEN);

        assertEquals(OutcomeKind.CHANNEL_FAILED, result.outcome());
        assertEquals(Axp1DistccChannelFactory.AXP1_AUTH_FAILED, result.failureCode());
        assertRunnableFallback(result);
        record("wrong-token", result);
    }

    @Test(timeout = 120_000)
    public void handshakeTimeoutFallsBackToALocalObject() throws Exception {
        CompileResult result = runCase(Mode.SILENT);

        assertEquals(OutcomeKind.CHANNEL_FAILED, result.outcome());
        assertEquals(Axp1DistccChannelFactory.AXP1_HANDSHAKE_TIMEOUT, result.failureCode());
        assertRunnableFallback(result);
        record("handshake-timeout", result);
    }

    @Test(timeout = 120_000)
    public void compilerMismatchFallsBackToALocalObject() throws Exception {
        CompileResult result = runCase(Mode.MISMATCH);

        assertEquals(OutcomeKind.CHANNEL_FAILED, result.outcome());
        assertEquals(Axp1DistccChannelFactory.COMPILER_MISMATCH, result.failureCode());
        assertRunnableFallback(result);
        record("compiler-mismatch", result);
    }

    @Test(timeout = 120_000)
    public void acceleratorExitFallsBackToALocalObject() throws Exception {
        CompileResult result = runCase(Mode.EXIT_AFTER_HANDSHAKE);

        assertEquals(OutcomeKind.CHANNEL_FAILED, result.outcome());
        assertEquals(Axp1DistccChannelFactory.AXP1_CLOSED, result.failureCode());
        assertRunnableFallback(result);
        record("accelerator-exit", result);
    }

    private static void assertRunnableFallback(CompileResult result) {
        assertTrue("fallback object is empty", result.objectSize() > 0L);
        assertEquals("42\n", result.output());
    }

    private static CompileResult runCase(Mode mode) throws Exception {
        String token = AcceleratorUplink.newToken();
        String triple = command(CC.toString(), "-dumpmachine").trim();
        String arch = triple.split("-")[0];
        Axp1Capabilities hostCaps = capabilities(triple, arch);
        Axp1Capabilities guestCaps = mode == Mode.MISMATCH
                ? capabilities("aarch64-linux-gnu", "aarch64")
                : hostCaps;
        Axp1Limits limits = new Axp1Limits(
                mode == Mode.SILENT ? 400L : 3_000L, 4 * 1_048_576, 1_048_576);
        Path work = Files.createTempDirectory("fastlane-axp1-distcc-");
        try {
            Files.writeString(
                    work.resolve("hello.c"),
                    "#include <stdio.h>\n"
                            + "int main(void) { printf(\"42\\n\"); return 0; }\n",
                    StandardCharsets.UTF_8);
            Files.createDirectories(work.resolve(".distcc"));
            List<DistccTcpVsockBridge.ConnectionStats> stats = new CopyOnWriteArrayList<>();
            long compileStarted = System.nanoTime();
            try (Accelerator accelerator = new Accelerator(mode, token, guestCaps, limits);
                    DistccTcpVsockBridge bridge = new DistccTcpVsockBridge(
                            LOOPBACK,
                            0,
                            new Axp1DistccChannelFactory(
                                    () -> connect(accelerator.port()),
                                    token,
                                    hostCaps,
                                    triple,
                                    limits),
                            4,
                            0,
                            30_000,
                            stats::add)) {
                bridge.start();
                ProcessResult compile = run(
                        work,
                        Map.of(
                                "DISTCC_HOSTS", "127.0.0.1:" + bridge.port(),
                                "DISTCC_FALLBACK", "1",
                                "DISTCC_DIR", work.resolve(".distcc").toString()),
                        DISTCC.toString(),
                        CC.toString(),
                        "-O2",
                        "-Wall",
                        "-c",
                        "hello.c",
                        "-o",
                        "hello.o");
                assertEquals("distcc client failed: " + compile.output(),
                        0, compile.exitCode());
                Path object = work.resolve("hello.o");
                assertTrue("object file is missing; distcc said: " + compile.output(),
                        Files.exists(object));
                long objectSize = Files.size(object);
                assertTrue("object file is empty", objectSize > 0L);

                ProcessResult link = run(
                        work, Map.of(), CC.toString(), "hello.o", "-o", "hello.remote");
                assertEquals("link failed: " + link.output(), 0, link.exitCode());
                ProcessResult run = run(
                        work, Map.of(), work.resolve("hello.remote").toString());
                assertEquals("run failed: " + run.output(), 0, run.exitCode());

                Path localObject = work.resolve("hello.local.o");
                ProcessResult localCompile = run(
                        work,
                        Map.of(),
                        CC.toString(),
                        "-O2",
                        "-Wall",
                        "-c",
                        "hello.c",
                        "-o",
                        "hello.local.o");
                assertEquals("local compile failed: " + localCompile.output(),
                        0, localCompile.exitCode());
                ProcessResult localLink = run(
                        work, Map.of(), CC.toString(), "hello.local.o", "-o", "hello.local");
                assertEquals("local link failed: " + localLink.output(),
                        0, localLink.exitCode());
                ProcessResult localRun = run(
                        work, Map.of(), work.resolve("hello.local").toString());
                assertEquals("local run failed: " + localRun.output(), 0, localRun.exitCode());

                waitForStats(stats);
                DistccTcpVsockBridge.ConnectionStats observed = stats.get(0);
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - compileStarted);
                return new CompileResult(
                        run.output(),
                        objectSize,
                        hash(object),
                        hash(localObject),
                        elapsedMs,
                        observed.outcome() == DistccTcpVsockBridge.Outcome.COMPLETED
                                ? OutcomeKind.COMPLETED : OutcomeKind.CHANNEL_FAILED,
                        observed.failureCode(),
                        observed.bytesFromClient(),
                        observed.bytesToClient());
            }
        } finally {
            deleteRecursively(work);
        }
    }

    private static void waitForStats(List<DistccTcpVsockBridge.ConnectionStats> stats)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (stats.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("bridge did not report a connection", !stats.isEmpty());
    }

    private static ProcessResult run(
            Path work, Map<String, String> extra, String... command) throws Exception {
        List<String> processCommand = new ArrayList<>(List.of(command));
        ProcessBuilder builder = new ProcessBuilder(processCommand);
        builder.directory(work.toFile());
        builder.redirectErrorStream(true);
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("TMPDIR", "/tmp");
        builder.environment().putAll(extra);
        Process process = builder.start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue("command timed out", process.waitFor(90, TimeUnit.SECONDS));
        return new ProcessResult(process.exitValue(), output);
    }

    private static String command(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(output, 0, process.exitValue());
        return output;
    }

    private static Axp1Capabilities capabilities(String triple, String arch) {
        return Axp1Capabilities.builder()
                .distcc(true)
                .compilerTriple(triple)
                .arch(arch)
                .maxFrame(1_048_576)
                .build();
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
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

    private static String hash(Path path) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
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

    private static synchronized void record(String name, CompileResult result) {
        REPORT.add("{\"case\":\"" + name
                + "\",\"outcome\":\"" + result.outcome()
                + "\",\"failureCode\":\"" + json(result.failureCode())
                + "\",\"objectSha256\":\"" + result.objectHash()
                + "\",\"localSha256\":\"" + result.localHash()
                + "\",\"hashEqual\":" + result.objectHash().equals(result.localHash())
                + ",\"durationMs\":" + result.durationMs()
                + ",\"bytesFromClient\":" + result.bytesFromClient()
                + ",\"bytesToClient\":" + result.bytesToClient()
                + "}");
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @AfterClass
    public static void writeReport() throws IOException {
        String path = System.getProperty("fastlane.distcc.report", "");
        if (path.isBlank() || REPORT.isEmpty()) {
            return;
        }
        Path target = Path.of(path);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                target,
                "[\n" + String.join(",\n", REPORT) + "\n]\n",
                StandardCharsets.UTF_8);
    }

    private enum Mode {
        SERVE,
        WRONG_TOKEN,
        SILENT,
        MISMATCH,
        EXIT_AFTER_HANDSHAKE
    }

    private enum OutcomeKind {
        COMPLETED,
        CHANNEL_FAILED
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record CompileResult(
            String output,
            long objectSize,
            String objectHash,
            String localHash,
            long durationMs,
            OutcomeKind outcome,
            String failureCode,
            long bytesFromClient,
            long bytesToClient) {
    }

    /** Host-only stand-in for the accelerator side of the AXP1 channel. */
    private static final class Accelerator implements Closeable {
        private final Mode mode;
        private final String expectedToken;
        private final Axp1Capabilities capabilities;
        private final Axp1Limits limits;
        private final ServerSocket server;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final Thread acceptThread;
        private volatile boolean running = true;

        Accelerator(
                Mode mode,
                String token,
                Axp1Capabilities capabilities,
                Axp1Limits limits) throws IOException {
            this.mode = mode;
            this.expectedToken = mode == Mode.WRONG_TOKEN
                    ? AcceleratorUplink.newToken() : token;
            this.capabilities = capabilities;
            this.limits = limits;
            this.server = new ServerSocket(0, 16, LOOPBACK);
            this.acceptThread = new Thread(this::acceptLoop, "axp1-test-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = server.accept();
                    sockets.add(socket);
                    Thread worker = new Thread(() -> handle(socket), "axp1-test-conn");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException failure) {
                    if (!running) {
                        return;
                    }
                }
            }
        }

        private void handle(Socket socket) {
            try {
                if (mode == Mode.SILENT) {
                    Thread.sleep(5_000L);
                    return;
                }
                Axp1Connection connection = Axp1Connection.accept(
                        transport(socket),
                        Axp1Role.GUEST,
                        expectedToken,
                        capabilities,
                        limits);
                if (mode == Mode.EXIT_AFTER_HANDSHAKE) {
                    connection.close();
                    return;
                }
                try (DistccdInetdChannel distccd = DistccdInetdChannel.start(
                        DISTCCD, Map.of("PATH", "/usr/bin:/bin", "TMPDIR", "/tmp"))) {
                    Axp1DistccAccelerator.serve(connection, distccd);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // Authentication failures and peer exits are expected cases.
            } finally {
                sockets.remove(socket);
                closeQuietly(socket);
            }
        }

        private static Axp1Transport transport(Socket socket) {
            return new Axp1Transport() {
                @Override
                public InputStream input() {
                    try {
                        return socket.getInputStream();
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                }

                @Override
                public OutputStream output() {
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

        @Override
        public void close() {
            running = false;
            closeQuietly(server);
            for (Socket socket : sockets) {
                closeQuietly(socket);
            }
            try {
                acceptThread.join(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private static void closeQuietly(Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Test teardown.
            }
        }
    }
}
