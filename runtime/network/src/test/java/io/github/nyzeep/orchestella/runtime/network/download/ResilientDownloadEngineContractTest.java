package io.github.nyzeep.orchestella.runtime.network.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Pure JVM Contract Test runner for ResilientDownloadEngine (ADR-0019, spec-m4, Ticket 51 AC-1 to AC-5).
 * Executable via {@code main(String[] args)} in Gradle JavaExec and CI suites.
 */
public final class ResilientDownloadEngineContractTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Running ResilientDownloadEngineContractTest scenarios...");

        testHttp206RangeResume();
        testHttp200Fallback();
        testHttp416ResetAndRetry();
        testFailoverOn500();
        testFailoverOnDeadPort();
        testSha256TamperingPurge();
        testProgressAndEtaReporting();
        testAlreadyDownloadedSkip();
        testMirrorPriorityList();

        System.out.println("RESILIENT_DOWNLOAD_ENGINE_CONTRACT_TEST_OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

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

    private static void testHttp206RangeResume() throws Exception {
        Path tempDir = Files.createTempDirectory("resume-test");
        try {
            int totalSize = 10_000;
            byte[] payload = createDeterministicPayload(totalSize);
            String hash = sha256Hex(payload);
            Path dest = tempDir.resolve("resumed.bin");

            try (MockHttpServer server = new MockHttpServer(payload)) {
                server.setDropAfterBytes(4_000);
                ResilientDownloadEngine engine = new ResilientDownloadEngine(
                        2000, 2000, 0, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

                boolean failed = false;
                try {
                    engine.download(server.url(), dest, hash, null);
                } catch (IOException e) {
                    failed = true;
                }
                check(failed, "Initial attempt must fail at 4000 bytes");

                Path part = tempDir.resolve("resumed.bin.part");
                check(Files.exists(part), ".part file must exist");
                check(Files.size(part) == 4000L, ".part file size must be 4000 bytes");

                // Resume
                server.setMode(MockHttpServer.Mode.STANDARD);
                engine.download(server.url(), dest, hash, null);

                check(Files.exists(dest), "Final destination file must exist");
                check(!Files.exists(part), ".part file must be gone");
                check(Files.size(dest) == totalSize, "File size must match total");
                check(Arrays.equals(payload, Files.readAllBytes(dest)), "Payload contents must match exactly");

                List<Map<String, String>> reqs = server.getReceivedHeaders();
                check(reqs.size() >= 2, "Server must receive at least 2 requests");
                check("bytes=4000-".equals(reqs.get(1).get("range")), "Second request must contain Range: bytes=4000-");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testHttp200Fallback() throws Exception {
        Path tempDir = Files.createTempDirectory("fallback-test");
        try {
            int totalSize = 6_000;
            byte[] payload = createDeterministicPayload(totalSize);
            String hash = sha256Hex(payload);
            Path dest = tempDir.resolve("fallback.bin");
            Path part = tempDir.resolve("fallback.bin.part");
            Files.write(part, new byte[2000]);

            try (MockHttpServer server = new MockHttpServer(payload)) {
                server.setMode(MockHttpServer.Mode.NO_RANGE_SUPPORT);
                ResilientDownloadEngine engine = new ResilientDownloadEngine(
                        2000, 2000, 1, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

                engine.download(server.url(), dest, hash, null);

                check(Files.exists(dest), "Final destination file must exist");
                check(Files.size(dest) == totalSize, "Size must match total payload");
                check(Arrays.equals(payload, Files.readAllBytes(dest)), "File content must be fully rewritten");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testHttp416ResetAndRetry() throws Exception {
        Path tempDir = Files.createTempDirectory("reset-test");
        try {
            int totalSize = 5_000;
            byte[] payload = createDeterministicPayload(totalSize);
            String hash = sha256Hex(payload);
            Path dest = tempDir.resolve("reset.bin");
            Path part = tempDir.resolve("reset.bin.part");
            Files.write(part, new byte[7000]); // Cursor beyond file size

            try (MockHttpServer server = new MockHttpServer(payload)) {
                server.setMode(MockHttpServer.Mode.STANDARD);
                ResilientDownloadEngine engine = new ResilientDownloadEngine(
                        2000, 2000, 1, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

                engine.download(server.url(), dest, hash, null);

                check(Files.exists(dest), "Destination file must exist after reset");
                check(Files.size(dest) == totalSize, "Destination size must match total");
                check(Arrays.equals(payload, Files.readAllBytes(dest)), "Contents must match");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testFailoverOn500() throws Exception {
        Path tempDir = Files.createTempDirectory("failover-test");
        try {
            int totalSize = 4_000;
            byte[] payload = createDeterministicPayload(totalSize);
            String hash = sha256Hex(payload);
            Path dest = tempDir.resolve("failover.bin");

            try (MockHttpServer bad = new MockHttpServer(payload);
                 MockHttpServer good = new MockHttpServer(payload)) {

                bad.setMode(MockHttpServer.Mode.RETURN_500);
                good.setMode(MockHttpServer.Mode.STANDARD);

                List<Long> backoffs = new ArrayList<>();
                ResilientDownloadEngine engine = new ResilientDownloadEngine(
                        2000, 2000, 3, 1000L, backoffs::add, HttpConnectionFactory.SYSTEM);

                engine.download(Arrays.asList(bad.url(), good.url()), dest, hash, null);

                check(Files.exists(dest), "Destination must exist after failover");
                check(Arrays.equals(payload, Files.readAllBytes(dest)), "Payload must match");
                check(backoffs.equals(Arrays.asList(1000L, 2000L, 4000L)), "Backoff delays must be 1s, 2s, 4s");
                check(bad.getRequestCount() == 4, "Bad server must receive 4 requests");
                check(good.getRequestCount() == 1, "Good server must receive 1 request");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testFailoverOnDeadPort() throws Exception {
        Path tempDir = Files.createTempDirectory("deadport-test");
        try {
            int totalSize = 3_000;
            byte[] payload = createDeterministicPayload(totalSize);
            String hash = sha256Hex(payload);
            Path dest = tempDir.resolve("deadport.bin");

            try (MockHttpServer good = new MockHttpServer(payload)) {
                good.setMode(MockHttpServer.Mode.STANDARD);
                String dead = "http://127.0.0.1:1/nonexistent";

                List<Long> backoffs = new ArrayList<>();
                ResilientDownloadEngine engine = new ResilientDownloadEngine(
                        1000, 1000, 3, 1000L, backoffs::add, HttpConnectionFactory.SYSTEM);

                engine.download(Arrays.asList(dead, good.url()), dest, hash, null);

                check(Files.exists(dest), "Destination must exist");
                check(Arrays.equals(payload, Files.readAllBytes(dest)), "Contents must match");
                check(good.getRequestCount() == 1, "Good server must receive 1 request");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testSha256TamperingPurge() throws Exception {
        Path tempDir = Files.createTempDirectory("tamper-test");
        try {
            int totalSize = 5_000;
            byte[] orig = createDeterministicPayload(totalSize);
            String expectedHash = sha256Hex(orig);

            byte[] tampered = orig.clone();
            tampered[100] = (byte) (tampered[100] ^ 0xFF);

            Path dest = tempDir.resolve("tampered.bin");
            Path part = tempDir.resolve("tampered.bin.part");

            try (MockHttpServer server = new MockHttpServer(tampered)) {
                ResilientDownloadEngine engine = new ResilientDownloadEngine(
                        2000, 2000, 0, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

                boolean threwChecksum = false;
                try {
                    engine.download(server.url(), dest, expectedHash, null);
                } catch (ChecksumMismatchException e) {
                    threwChecksum = true;
                    check(expectedHash.equals(e.getExpectedHash()), "Expected hash must match");
                }
                check(threwChecksum, "Must throw ChecksumMismatchException on tampered payload");
                check(!Files.exists(part), "Part file must be physically purged on checksum mismatch");
                check(!Files.exists(dest), "Destination file must not exist");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testProgressAndEtaReporting() throws Exception {
        Path tempDir = Files.createTempDirectory("progress-test");
        try {
            int totalSize = 20_000;
            byte[] payload = createDeterministicPayload(totalSize);
            String hash = sha256Hex(payload);
            Path dest = tempDir.resolve("progress.bin");

            try (MockHttpServer server = new MockHttpServer(payload)) {
                ResilientDownloadEngine engine = new ResilientDownloadEngine(
                        2000, 2000, 0, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

                List<Long> bytesList = new ArrayList<>();
                List<Long> totals = new ArrayList<>();
                DownloadProgressCallback cb = (d, t, s, eta, m) -> {
                    bytesList.add(d);
                    totals.add(t);
                };

                engine.download(server.url(), dest, hash, cb);

                check(bytesList.size() >= 2, "Must report at least initial and final progress");
                check(bytesList.get(bytesList.size() - 1) == totalSize, "Final progress must match total bytes");
                check(totals.get(totals.size() - 1) == totalSize, "Final total must match total bytes");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testAlreadyDownloadedSkip() throws Exception {
        Path tempDir = Files.createTempDirectory("skip-test");
        try {
            int totalSize = 2_000;
            byte[] payload = createDeterministicPayload(totalSize);
            String hash = sha256Hex(payload);
            Path dest = tempDir.resolve("cached.bin");
            Files.write(dest, payload);

            try (MockHttpServer server = new MockHttpServer(payload)) {
                ResilientDownloadEngine engine = new ResilientDownloadEngine(
                        2000, 2000, 0, 0, Sleeper.NO_OP, HttpConnectionFactory.SYSTEM);

                List<Long> progress = new ArrayList<>();
                engine.download(server.url(), dest, hash, (d, t, s, eta, m) -> progress.add(d));

                check(server.getRequestCount() == 0, "No network request should be sent when file matches");
                check(progress.size() == 1 && progress.get(0) == totalSize, "Progress should report complete immediately");
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testMirrorPriorityList() {
        String githubUrl = "https://github.com/Nyzeep/Orchestella-Studio/releases/download/v0.3.0-alpha/alpine.tar.gz";
        List<String> list = MirrorPriorityList.forGitHubRelease(githubUrl);
        check(list.size() == 3, "GitHub mirror list must have 3 endpoints");
        check(list.get(0).equals(githubUrl), "First must be official GitHub URL");
        check(list.get(1).contains("download.fastgit.org"), "Second must be global CDN URL");
        check(list.get(2).contains("ghproxy.net"), "Third must be domestic proxy URL");
    }

    private static void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(ResilientDownloadEngineContractTest::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {}
    }
}
