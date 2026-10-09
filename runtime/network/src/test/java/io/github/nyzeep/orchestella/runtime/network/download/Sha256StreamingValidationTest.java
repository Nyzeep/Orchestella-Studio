package io.github.nyzeep.orchestella.runtime.network.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * JVM unit tests for streaming SHA-256 integrity verification, physical file purge
 * upon corruption, and progress reporting metrics (Ticket 51 AC-3, AC-4).
 */
public final class Sha256StreamingValidationTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private static byte[] createDeterministicPayload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 13 + 3) & 0xFF);
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
    public void purgesPartFileAndThrowsExceptionWhenPayloadIsCorrupted() throws Exception {
        int totalSize = 10_000;
        byte[] originalPayload = createDeterministicPayload(totalSize);
        String expectedHash = sha256Hex(originalPayload);

        // Server sends corrupted payload
        byte[] corruptedPayload = originalPayload.clone();
        corruptedPayload[500] = (byte) (corruptedPayload[500] ^ 0xFF);

        Path destFile = tempFolder.newFile("corrupt.bin").toPath();
        Files.deleteIfExists(destFile);
        Path partFile = destFile.resolveSibling(destFile.getFileName().toString() + ".part");

        try (MockHttpServer server = new MockHttpServer(corruptedPayload)) {
            ResilientDownloadEngine engine = new ResilientDownloadEngine(
                    2000, 2000, 2, 100L, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

            try {
                engine.download(server.url(), destFile, expectedHash, (d, t, s, eta, m) -> {});
                fail("ChecksumMismatchException must be thrown on tampered data");
            } catch (ChecksumMismatchException e) {
                assertEquals(expectedHash, e.getExpectedHash());
                assertFalse("Actual hash must not equal expected hash", expectedHash.equalsIgnoreCase(e.getActualHash()));
            }

            // Both destination and temporary .part files MUST be physically deleted
            assertFalse("Part file must be physically deleted to prevent contamination", Files.exists(partFile));
            assertFalse("Destination file must not exist", Files.exists(destFile));
        }
    }

    @Test
    public void reportsSmoothProgressAndEtaMetrics() throws Exception {
        int totalSize = 50_000;
        byte[] payload = createDeterministicPayload(totalSize);
        String expectedHash = sha256Hex(payload);

        Path destFile = tempFolder.newFile("progress.bin").toPath();
        Files.deleteIfExists(destFile);

        try (MockHttpServer server = new MockHttpServer(payload)) {
            ResilientDownloadEngine engine = new ResilientDownloadEngine(
                    2000, 2000, 0, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

            List<Long> progressValues = new ArrayList<>();
            List<Long> totalValues = new ArrayList<>();
            List<String> reportedMirrors = new ArrayList<>();

            DownloadProgressCallback cb = (downloaded, total, speed, eta, mirror) -> {
                progressValues.add(downloaded);
                totalValues.add(total);
                reportedMirrors.add(mirror);
            };

            engine.download(server.url(), destFile, expectedHash, cb);

            assertTrue("Should report at least initial and final progress", progressValues.size() >= 2);
            assertEquals(Long.valueOf(totalSize), progressValues.get(progressValues.size() - 1));
            assertEquals(Long.valueOf(totalSize), totalValues.get(totalValues.size() - 1));
            assertEquals(server.url(), reportedMirrors.get(0));
            assertTrue(Files.exists(destFile));
            assertEquals(totalSize, Files.size(destFile));
        }
    }

    @Test
    public void skipsDownloadWhenDestinationAlreadyExistsWithMatchingChecksum() throws Exception {
        int totalSize = 5_000;
        byte[] payload = createDeterministicPayload(totalSize);
        String expectedHash = sha256Hex(payload);

        Path destFile = tempFolder.newFile("already_exists.bin").toPath();
        Files.write(destFile, payload);

        try (MockHttpServer server = new MockHttpServer(payload)) {
            ResilientDownloadEngine engine = new ResilientDownloadEngine(
                    2000, 2000, 0, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

            List<Long> progress = new ArrayList<>();
            engine.download(server.url(), destFile, expectedHash, (d, t, s, eta, m) -> progress.add(d));

            // Server must receive zero requests because local file is already valid!
            assertEquals(0, server.getRequestCount());
            assertEquals(1, progress.size());
            assertEquals(Long.valueOf(totalSize), progress.get(0));
        }
    }
}
