package io.github.nyzeep.droidlayer.runtime.contract;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strategy-neutral contract tests for storage limits, space preflight, disk resize spec,
 * export metadata JSON, and checksum manifest (ADR-0007, Ticket 33).
 */
public final class StorageContractTest {

    public static void main(String[] args) throws Exception {
        validatesStorageLimitsAndConstants();
        diskResizeSpecRejectsShrinkingAndNonPositive();
        preflightBlocksWhenUsableSpaceIsInsufficient();
        preflightPassesWhenUsableSpaceIsSufficient();
        instanceExportMetadataJsonRoundtripAndEscaping();
        checksumManifestFormattingAndParsing();
        sha256HexComputesAccurateDigests();
        System.out.println("STORAGE_CONTRACT_TEST_OK");
    }

    private static void validatesStorageLimitsAndConstants() {
        assertEquals("root-volume.raw", StorageContract.ROOT_VOLUME_FILE_NAME, "root volume name");
        assertEquals("instance-metadata.json", StorageContract.METADATA_FILE_NAME, "metadata name");
        assertEquals("SHA256SUMS", StorageContract.CHECKSUMS_FILE_NAME, "checksum manifest name");
        assertEquals(2L * 1024L * 1024L * 1024L, StorageContract.DEFAULT_ROOT_VOLUME_BYTES, "default size 2 GiB");
        assertEquals(512L * 1024L * 1024L, StorageContract.MIN_ROOT_VOLUME_BYTES, "min size 512 MiB");
        assertTrue(StorageContract.MAX_ROOT_VOLUME_BYTES > StorageContract.DEFAULT_ROOT_VOLUME_BYTES, "max > default");
    }

    private static void diskResizeSpecRejectsShrinkingAndNonPositive() {
        long currentSize = 2L * 1024L * 1024L * 1024L;

        // Valid expansion
        long targetSize = 4L * 1024L * 1024L * 1024L;
        StorageContract.DiskResizeSpec spec = new StorageContract.DiskResizeSpec(currentSize, targetSize);
        assertEquals(currentSize, spec.currentSizeBytes(), "current size");
        assertEquals(targetSize, spec.targetSizeBytes(), "target size");
        assertEquals(2L * 1024L * 1024L * 1024L, spec.additionalBytesNeeded(), "additional bytes");

        // Shrinking prohibited
        assertThrows(
                IllegalArgumentException.class,
                () -> new StorageContract.DiskResizeSpec(currentSize, currentSize - 1024L),
                "disk shrinking must be rejected");

        // Same size prohibited
        assertThrows(
                IllegalArgumentException.class,
                () -> new StorageContract.DiskResizeSpec(currentSize, currentSize),
                "resizing to identical size must be rejected");

        // Non-positive current size prohibited
        assertThrows(
                IllegalArgumentException.class,
                () -> new StorageContract.DiskResizeSpec(0, targetSize),
                "zero current size must be rejected");

        // Non-positive target size prohibited
        assertThrows(
                IllegalArgumentException.class,
                () -> new StorageContract.DiskResizeSpec(currentSize, 0),
                "zero target size must be rejected");

        // Exceeding maximum limit prohibited
        assertThrows(
                IllegalArgumentException.class,
                () -> new StorageContract.DiskResizeSpec(currentSize, StorageContract.MAX_ROOT_VOLUME_BYTES + 1L),
                "target size above maximum must be rejected");
    }

    private static void preflightBlocksWhenUsableSpaceIsInsufficient() {
        Path targetDir = Path.of("test/dir");
        Path volumePath = targetDir.resolve("root-volume.raw");
        long usableSpace = 1000L;

        StorageContract.StorageSpaceChecker checker = path -> usableSpace;

        // Creation space check
        StorageContract.InsufficientStorageException creationEx = assertThrows(
                StorageContract.InsufficientStorageException.class,
                () -> StorageContract.StoragePreflight.checkCreationSpace(checker, targetDir, 2000L),
                "creation must throw InsufficientStorageException when space < required");
        assertEquals(2000L, creationEx.requiredBytes(), "required bytes");
        assertEquals(usableSpace, creationEx.availableBytes(), "available bytes");
        assertEquals(targetDir, creationEx.path(), "target path");
        assertTrue(creationEx.getMessage().contains("ENOSPC prevention"), "message mentions ENOSPC");

        // Resize space check
        long currentSize = 1000L;
        long targetSize = 2500L; // delta = 1500 > 1000
        StorageContract.InsufficientStorageException resizeEx = assertThrows(
                StorageContract.InsufficientStorageException.class,
                () -> StorageContract.StoragePreflight.checkResizeSpace(checker, volumePath, currentSize, targetSize),
                "resize must throw InsufficientStorageException when space < delta");
        assertEquals(1500L, resizeEx.requiredBytes(), "required delta bytes");
        assertEquals(usableSpace, resizeEx.availableBytes(), "available bytes");
    }

    private static void preflightPassesWhenUsableSpaceIsSufficient() throws IOException {
        Path targetDir = Path.of("test/dir");
        Path volumePath = targetDir.resolve("root-volume.raw");
        long usableSpace = 10_000_000_000L;

        StorageContract.StorageSpaceChecker checker = path -> usableSpace;

        // Creation space check passes
        StorageContract.StoragePreflight.checkCreationSpace(checker, targetDir, 2_000_000_000L);

        // Resize space check passes
        StorageContract.StoragePreflight.checkResizeSpace(checker, volumePath, 2_000_000_000L, 4_000_000_000L);
    }

    private static void instanceExportMetadataJsonRoundtripAndEscaping() {
        String sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        StorageContract.InstanceExportMetadata metadata = new StorageContract.InstanceExportMetadata(
                "inst-uuid-1234",
                "alpine",
                "3.24.1",
                "FULL_LINUX",
                "STOPPED",
                2147483648L,
                sha256,
                1789000000000L,
                1789000100000L,
                1789000200000L);

        String json = metadata.toJson();
        assertTrue(json.contains("\"instanceId\": \"inst-uuid-1234\""), "contains instanceId");
        assertTrue(json.contains("\"diskSizeBytes\": 2147483648"), "contains diskSizeBytes");
        assertTrue(json.contains("\"sha256RootVolume\": \"" + sha256 + "\""), "contains sha256RootVolume");

        StorageContract.InstanceExportMetadata parsed = StorageContract.InstanceExportMetadata.fromJson(json);
        assertEquals(metadata, parsed, "roundtrip equals");
        assertEquals(metadata.hashCode(), parsed.hashCode(), "hashcode equals");

        // Rejects invalid hash format
        assertThrows(
                IllegalArgumentException.class,
                () -> new StorageContract.InstanceExportMetadata(
                        "id", "alpine", "3.24.1", "FULL_LINUX", "STOPPED",
                        1024L, "invalid-hash", 1L, 1L, 1L),
                "invalid sha256 format must be rejected");

        // Rejects missing field in JSON
        assertThrows(
                IllegalArgumentException.class,
                () -> StorageContract.InstanceExportMetadata.fromJson("{\"distroId\":\"alpine\"}"),
                "JSON with missing fields must be rejected");
    }

    private static void checksumManifestFormattingAndParsing() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("root-volume.raw", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        entries.put("instance-metadata.json", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

        StorageContract.ChecksumManifest manifest = new StorageContract.ChecksumManifest(entries);
        String formatted = manifest.format();
        assertTrue(formatted.contains("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  root-volume.raw"), "formatted entry 1");
        assertTrue(formatted.contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad  instance-metadata.json"), "formatted entry 2");

        StorageContract.ChecksumManifest parsed = StorageContract.ChecksumManifest.parse(formatted);
        assertEquals(manifest.entries(), parsed.entries(), "parsed matches original");
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                parsed.getHash("root-volume.raw"),
                "hash lookup");

        // Rejects malformed line
        assertThrows(
                IllegalArgumentException.class,
                () -> StorageContract.ChecksumManifest.parse("singlewordline"),
                "malformed line must be rejected");
    }

    private static void sha256HexComputesAccurateDigests() throws IOException {
        // empty string hash: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        String emptyHash = StorageContract.sha256Hex(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash, "empty SHA-256");

        // "hello world" hash: b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        byte[] helloBytes = "hello world".getBytes(StandardCharsets.UTF_8);
        String helloHash = StorageContract.sha256Hex(helloBytes);
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", helloHash, "hello world SHA-256");

        // Stream computation
        String streamHash = StorageContract.sha256Hex(new ByteArrayInputStream(helloBytes));
        assertEquals(helloHash, streamHash, "stream SHA-256 equals byte SHA-256");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " - expected: <" + expected + "> but was: <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static <T extends Throwable> T assertThrows(Class<T> expectedType, ThrowingRunnable runnable, String message) {
        try {
            runnable.run();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) {
                return expectedType.cast(actual);
            }
            throw new AssertionError(message + " - unexpected exception type: " + actual.getClass().getName(), actual);
        }
        throw new AssertionError(message + " - expected " + expectedType.getName() + " was not thrown");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
