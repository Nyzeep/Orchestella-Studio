package io.github.nyzeep.droidlayer.runtime.network.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * JVM unit tests for multi-mirror failover and exponential backoff retries (Ticket 51 AC-2).
 */
public final class MirrorFailoverTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private static byte[] createDeterministicPayload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 19 + 5) & 0xFF);
        }
        return data;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Test
    public void failsOverToSecondMirrorWhenFirstMirrorReturns500InternalError() throws Exception {
        int totalSize = 5_000;
        byte[] payload = createDeterministicPayload(totalSize);
        String expectedHash = sha256Hex(payload);

        Path destFile = tempFolder.newFile("failover_500.bin").toPath();
        Files.deleteIfExists(destFile);

        try (MockHttpServer badServer = new MockHttpServer(payload);
             MockHttpServer goodServer = new MockHttpServer(payload)) {

            badServer.setMode(MockHttpServer.Mode.RETURN_500);
            goodServer.setMode(MockHttpServer.Mode.STANDARD);

            List<Long> backoffDelays = new ArrayList<>();
            List<String> failoverEvents = new ArrayList<>();

            Sleeper recordingSleeper = delay -> backoffDelays.add(delay);

            ResilientDownloadEngine engine = new ResilientDownloadEngine(
                    2000, 2000, 3, 1000L, recordingSleeper, HttpConnectionFactory.SYSTEM);

            DownloadProgressCallback cb = new DownloadProgressCallback() {
                @Override
                public void onProgress(long downloaded, long total, double speed, long eta, String mirror) {}

                @Override
                public void onRetry(String currentMirror, int attempt, long backoffMillis, Throwable error) {
                    // captured via sleeper
                }

                @Override
                public void onMirrorFailover(String failedMirror, String nextMirror, Throwable error) {
                    failoverEvents.add(failedMirror + " -> " + nextMirror);
                }
            };

            List<String> mirrors = Arrays.asList(badServer.url(), goodServer.url());
            engine.download(mirrors, destFile, expectedHash, cb);

            // Assertions
            assertTrue(Files.exists(destFile));
            assertEquals(totalSize, Files.size(destFile));
            assertArrayEquals(payload, Files.readAllBytes(destFile));

            // Verify 3 retries occurred on the bad mirror with exponential backoff (1s, 2s, 4s)
            assertEquals(Arrays.asList(1000L, 2000L, 4000L), backoffDelays);

            // Verify failover event was triggered
            assertEquals(1, failoverEvents.size());
            assertTrue(failoverEvents.get(0).contains(badServer.url()));
            assertTrue(failoverEvents.get(0).contains(goodServer.url()));

            // Verify bad server received 4 requests (1 initial + 3 retries)
            assertEquals(4, badServer.getRequestCount());
            // Good server received 1 request
            assertEquals(1, goodServer.getRequestCount());
        }
    }

    @Test
    public void failsOverWhenFirstMirrorIsDeadPort() throws Exception {
        int totalSize = 4_000;
        byte[] payload = createDeterministicPayload(totalSize);
        String expectedHash = sha256Hex(payload);

        Path destFile = tempFolder.newFile("failover_deadport.bin").toPath();
        Files.deleteIfExists(destFile);

        try (MockHttpServer goodServer = new MockHttpServer(payload)) {
            goodServer.setMode(MockHttpServer.Mode.STANDARD);

            // Port 1 on 127.0.0.1 is rejected immediately with ConnectException
            String deadMirror = "http://127.0.0.1:1/nonexistent";

            List<Long> backoffs = new ArrayList<>();
            ResilientDownloadEngine engine = new ResilientDownloadEngine(
                    1000, 1000, 3, 1000L, backoffs::add, HttpConnectionFactory.SYSTEM);

            List<String> mirrors = Arrays.asList(deadMirror, goodServer.url());
            engine.download(mirrors, destFile, expectedHash, (d, t, s, eta, m) -> {});

            assertTrue(Files.exists(destFile));
            assertEquals(totalSize, Files.size(destFile));
            assertArrayEquals(payload, Files.readAllBytes(destFile));
            assertEquals(Arrays.asList(1000L, 2000L, 4000L), backoffs);
            assertEquals(1, goodServer.getRequestCount());
        }
    }
}
