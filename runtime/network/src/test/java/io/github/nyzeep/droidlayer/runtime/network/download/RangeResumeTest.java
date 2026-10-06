package io.github.nyzeep.droidlayer.runtime.network.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * JVM unit tests for HTTP Range resumption, 206 partial content appending,
 * 200 OK fallback truncation, and 416 range reset (Ticket 51 AC-1).
 */
public final class RangeResumeTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private static byte[] createDeterministicPayload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + 17) & 0xFF);
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
    public void resumesDownloadOnHttp206AfterMidwayDisconnect() throws Exception {
        int totalSize = 10_000;
        byte[] payload = createDeterministicPayload(totalSize);
        String expectedHash = sha256Hex(payload);

        Path destFile = tempFolder.newFile("downloaded.bin").toPath();
        Files.deleteIfExists(destFile);

        try (MockHttpServer server = new MockHttpServer(payload)) {
            // First run: server will drop connection after 4,000 bytes
            server.setDropAfterBytes(4_000);

            ResilientDownloadEngine engine = new ResilientDownloadEngine(
                    2000, 2000, 0, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

            List<Long> progressUpdates = new ArrayList<>();
            DownloadProgressCallback cb1 = (downloaded, total, speed, eta, mirror) -> {
                progressUpdates.add(downloaded);
            };

            // First run fails after 4000 bytes
            boolean failedAsExpected = false;
            try {
                engine.download(server.url(), destFile, expectedHash, cb1);
            } catch (IOException e) {
                failedAsExpected = true;
            }
            assertTrue("First download should fail on socket drop", failedAsExpected);

            Path partFile = destFile.resolveSibling(destFile.getFileName().toString() + ".part");
            assertTrue(".part file must exist on disk", Files.exists(partFile));
            assertEquals("Existing part file size must equal 4000", 4000L, Files.size(partFile));

            // Second run: server returns normal 206 Partial Content for range
            server.setMode(MockHttpServer.Mode.STANDARD);

            List<Long> resumeProgress = new ArrayList<>();
            DownloadProgressCallback cb2 = (downloaded, total, speed, eta, mirror) -> {
                resumeProgress.add(downloaded);
            };

            engine.download(server.url(), destFile, expectedHash, cb2);

            assertTrue("Final destination file must exist", Files.exists(destFile));
            assertFalse(".part file must be cleaned up / moved", Files.exists(partFile));
            assertEquals(totalSize, Files.size(destFile));
            assertArrayEquals(payload, Files.readAllBytes(destFile));

            // Verify that Range header was sent in the second request
            List<Map<String, String>> requests = server.getReceivedHeaders();
            assertTrue(requests.size() >= 2);
            Map<String, String> secondRequestHeaders = requests.get(1);
            assertEquals("bytes=4000-", secondRequestHeaders.get("range"));
        }
    }

    @Test
    public void fallbacksToFullDownloadWhenServerResponds200OkInsteadOf206() throws Exception {
        int totalSize = 8_000;
        byte[] payload = createDeterministicPayload(totalSize);
        String expectedHash = sha256Hex(payload);

        Path destFile = tempFolder.newFile("fallback.bin").toPath();
        Files.deleteIfExists(destFile);

        // Pre-create part file with 3000 bytes of arbitrary prefix
        Path partFile = destFile.resolveSibling(destFile.getFileName().toString() + ".part");
        Files.write(partFile, new byte[3000]);

        try (MockHttpServer server = new MockHttpServer(payload)) {
            // Server ignores range and returns 200 OK
            server.setMode(MockHttpServer.Mode.NO_RANGE_SUPPORT);

            ResilientDownloadEngine engine = new ResilientDownloadEngine(
                    2000, 2000, 1, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

            engine.download(server.url(), destFile, expectedHash, (d, t, s, eta, m) -> {});

            assertTrue("Final destination file must exist", Files.exists(destFile));
            assertFalse(".part file must be removed", Files.exists(partFile));
            assertEquals(totalSize, Files.size(destFile));
            assertArrayEquals(payload, Files.readAllBytes(destFile));
        }
    }

    @Test
    public void resetsAndRetriesWhenServerReturns416RangeNotSatisfiable() throws Exception {
        int totalSize = 6_000;
        byte[] payload = createDeterministicPayload(totalSize);
        String expectedHash = sha256Hex(payload);

        Path destFile = tempFolder.newFile("resetted.bin").toPath();
        Files.deleteIfExists(destFile);

        // Pre-create invalid part file that exceeds the total size (8000 > 6000)
        Path partFile = destFile.resolveSibling(destFile.getFileName().toString() + ".part");
        Files.write(partFile, new byte[8000]);

        try (MockHttpServer server = new MockHttpServer(payload)) {
            server.setMode(MockHttpServer.Mode.STANDARD);

            ResilientDownloadEngine engine = new ResilientDownloadEngine(
                    2000, 2000, 1, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

            engine.download(server.url(), destFile, expectedHash, (d, t, s, eta, m) -> {});

            assertTrue("Final file must exist", Files.exists(destFile));
            assertEquals(totalSize, Files.size(destFile));
            assertArrayEquals(payload, Files.readAllBytes(destFile));

            // Check that first request triggered 416 and second request restarted without range
            List<Map<String, String>> requests = server.getReceivedHeaders();
            assertTrue("Expected at least 2 requests", requests.size() >= 2);
            assertEquals("bytes=8000-", requests.get(0).get("range"));
            assertFalse(requests.get(1).containsKey("range"));
        }
    }
}
