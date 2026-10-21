package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assume;
import org.junit.Test;

/**
 * Process-level E2E: the host mux speaks FLN1 to the real C payload over its
 * Unix-socket test transport. This proves framing, the HELLO gate, payload
 * session-id ownership and EOF/EXIT handling across languages; it does not
 * prove AF_VSOCK, Android SELinux, or devpts (DV-01/DV-02).
 *
 * <p>Enabled with {@code -Dfln1.payload=/path/to/fastlane_payload_host};
 * skipped otherwise so the JVM suite never depends on a built binary.
 */
public final class Fln1PayloadE2ETest {
    private static final String TOKEN = "e2e-token";

    @Test
    public void terminalRoundTripAgainstTheRealPayload() throws Exception {
        String binary = System.getProperty("fln1.payload", "");
        Assume.assumeTrue("set -Dfln1.payload to run the payload E2E",
                !binary.isEmpty() && Files.isExecutable(Paths.get(binary)));

        Path root = Files.createTempDirectory("fl-e2e-");
        Path apk = Files.createDirectories(root.resolve("apk"));
        Path bin = Files.createDirectories(apk.resolve("bin"));
        Path sockets = Files.createDirectories(root.resolve("sockets"));
        Path storage = Files.createDirectories(root.resolve("storage"));
        Path shell = bin.resolve("sh");
        Files.copy(Paths.get("/bin/sh"), shell, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(shell, EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE));

        ProcessBuilder builder = new ProcessBuilder(binary);
        Map<String, String> environment = builder.environment();
        environment.put("FASTLANE_TRANSPORT", "unix");
        environment.put("FASTLANE_SOCKET_DIR", sockets.toString());
        environment.put("FASTLANE_APK_ROOT", apk.toString());
        environment.put("FASTLANE_STORAGE_ROOT", storage.toString());
        environment.put("FASTLANE_AUTH_TOKEN", TOKEN);
        environment.put("FASTLANE_MAX_SESSIONS", "4");
        environment.put("FASTLANE_MAX_CONNECTIONS", "4");
        Process payload = builder.redirectErrorStream(true).start();
        Path socket = sockets.resolve("terminal.sock");
        try {
            awaitSocket(payload, socket);
            try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                channel.connect(UnixDomainSocketAddress.of(socket));
                InputStream input = Channels.newInputStream(channel);
                OutputStream output = Channels.newOutputStream(channel);

                Recorder recorder = new Recorder();
                TerminalMuxChannel mux = TerminalMuxChannel.open(
                        TerminalMux.Role.HOST, 4, recorder, input, output);
                mux.mux().sendHello(new Fln1Payloads.Hello(1, 1, TOKEN));
                mux.mux().openSession(
                        80, 24, "sh", "-c", "printf e2e-ok; exit 3");

                ExecutorService executor = Executors.newSingleThreadExecutor();
                Future<?> pump = executor.submit(() -> {
                    try {
                        while (!recorder.closed.get()) {
                            mux.pumpOnce();
                        }
                    } catch (Exception failure) {
                        recorder.failure = failure;
                    }
                });
                try {
                    pump.get(20, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                    mux.close();
                }

                if (recorder.failure != null) {
                    throw new AssertionError("payload pump failed", recorder.failure);
                }
                assertTrue(mux.mux().helloReceived());
                assertEquals("e2e-ok", recorder.data.toString(StandardCharsets.UTF_8));
                assertEquals(3, recorder.exitCode.get());
                assertEquals(TerminalSessionState.CLOSED, mux.mux().state(1));
            }
        } finally {
            payload.destroy();
            if (!payload.waitFor(5, TimeUnit.SECONDS)) {
                payload.destroyForcibly();
            }
        }
    }

    private static void awaitSocket(Process payload, Path socket) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!payload.isAlive()) {
                throw new IllegalStateException(
                        "payload exited before listening: " + payload.exitValue());
            }
            if (Files.exists(socket)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("terminal.sock did not appear");
    }

    private static final class Recorder implements TerminalMux.Listener {
        private final java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        private final AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Exception failure;

        @Override
        public void onData(int sessionId, byte[] bytes) {
            data.writeBytes(bytes);
        }

        @Override
        public void onEof(int sessionId, int code) {
            exitCode.set(code);
        }

        @Override
        public void onSessionClosed(int sessionId, TerminalCloseReason reason) {
            closed.set(true);
        }
    }
}
