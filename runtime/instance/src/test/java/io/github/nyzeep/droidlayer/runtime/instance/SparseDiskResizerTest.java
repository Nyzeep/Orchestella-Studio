package io.github.nyzeep.droidlayer.runtime.instance;

import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import io.github.nyzeep.droidlayer.runtime.contract.StorageContract;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;

/** Unit tests for {@link SparseDiskResizer} covering all boundary conditions. */
public final class SparseDiskResizerTest {

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("sparse-disk-resizer-test-");
        try {
            testSuccessfulSparseExpansionAndDataPreservation(tempDir);
            testStateMustBeStopped(tempDir);
            testShrinkingThrowsIllegalArgumentException(tempDir);
            testEqualSizeThrowsIllegalArgumentException(tempDir);
            testInsufficientHostStorageSpace(tempDir);
            testNonExistentFile(tempDir);
            testExceedsMaximumSupportedLimit(tempDir);
            testInstanceOverload(tempDir);
            System.out.println("SPARSE_DISK_RESIZER_TEST_OK");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testSuccessfulSparseExpansionAndDataPreservation(Path root) throws Exception {
        Path disk = root.resolve("expand-test.raw");
        long initialSize = 1024L * 1024L; // 1 MiB
        byte[] payload = "DROIDLAYER_SPARSE_DISK_HEADER_SIG".getBytes(StandardCharsets.UTF_8);

        createFileWithPayload(disk, initialSize, payload);

        long targetSize = 5L * 1024L * 1024L; // 5 MiB
        StorageContract.StorageSpaceChecker checker = path -> 500L * 1024L * 1024L; // 500 MiB free

        SparseDiskResizer.resize(disk, InstanceLifecycle.State.STOPPED, targetSize, checker);

        assertEquals(targetSize, Files.size(disk), "file size after expansion");

        // Verify existing payload at the start is preserved byte-for-byte
        byte[] readHeader = new byte[payload.length];
        try (RandomAccessFile raf = new RandomAccessFile(disk.toFile(), "r")) {
            raf.seek(0);
            raf.readFully(readHeader);
        }
        assertEquals(new String(payload, StandardCharsets.UTF_8),
                new String(readHeader, StandardCharsets.UTF_8),
                "existing payload preserved");

        // Verify final byte is 0
        try (RandomAccessFile raf = new RandomAccessFile(disk.toFile(), "r")) {
            raf.seek(targetSize - 1);
            int lastByte = raf.read();
            assertEquals(0, lastByte, "last byte should be zero");
        }
    }

    private static void testStateMustBeStopped(Path root) throws Exception {
        Path disk = root.resolve("state-test.raw");
        createFileWithPayload(disk, 1024L * 1024L, new byte[] {1, 2, 3});

        InstanceLifecycle.State[] forbiddenStates = {
                InstanceLifecycle.State.RUNNING,
                InstanceLifecycle.State.PROVISIONING,
                InstanceLifecycle.State.CREATED,
                InstanceLifecycle.State.INTERRUPTED,
                InstanceLifecycle.State.CRASHED
        };

        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;

        for (InstanceLifecycle.State forbidden : forbiddenStates) {
            try {
                SparseDiskResizer.resize(disk, forbidden, 2L * 1024L * 1024L, checker);
                fail("Expected IllegalStateException for state: " + forbidden);
            } catch (IllegalStateException expected) {
                // PASS
            }
            assertEquals(1024L * 1024L, Files.size(disk), "file size unchanged on rejected state: " + forbidden);
        }
    }

    private static void testShrinkingThrowsIllegalArgumentException(Path root) throws Exception {
        Path disk = root.resolve("shrink-test.raw");
        long initialSize = 10L * 1024L * 1024L; // 10 MiB
        createFileWithPayload(disk, initialSize, new byte[] {42});

        long smallerTarget = initialSize - 1024L;
        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;

        try {
            SparseDiskResizer.resize(disk, InstanceLifecycle.State.STOPPED, smallerTarget, checker);
            fail("Expected IllegalArgumentException for shrinking");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Disk shrinking is not supported"),
                    "Exception message must state 'Disk shrinking is not supported': " + e.getMessage());
        }

        assertEquals(initialSize, Files.size(disk), "file size unchanged after rejected shrinking");
    }

    private static void testEqualSizeThrowsIllegalArgumentException(Path root) throws Exception {
        Path disk = root.resolve("equal-test.raw");
        long initialSize = 4L * 1024L * 1024L;
        createFileWithPayload(disk, initialSize, new byte[] {7});

        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;

        try {
            SparseDiskResizer.resize(disk, InstanceLifecycle.State.STOPPED, initialSize, checker);
            fail("Expected IllegalArgumentException for equal target size");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Disk shrinking is not supported"),
                    "Exception message must state 'Disk shrinking is not supported': " + e.getMessage());
        }

        assertEquals(initialSize, Files.size(disk), "file size unchanged after rejected equal size");
    }

    private static void testInsufficientHostStorageSpace(Path root) throws Exception {
        Path disk = root.resolve("enospc-test.raw");
        long initialSize = 1024L * 1024L;
        createFileWithPayload(disk, initialSize, new byte[] {9});

        long targetSize = 10L * 1024L * 1024L; // delta = 9 MiB
        StorageContract.StorageSpaceChecker insufficientChecker = path -> 5L * 1024L * 1024L; // only 5 MiB available

        try {
            SparseDiskResizer.resize(disk, InstanceLifecycle.State.STOPPED, targetSize, insufficientChecker);
            fail("Expected InsufficientStorageException when usable space < delta");
        } catch (StorageContract.InsufficientStorageException expected) {
            assertEquals(9L * 1024L * 1024L, expected.requiredBytes(), "required bytes matches delta");
        }

        assertEquals(initialSize, Files.size(disk), "file size unchanged on ENOSPC");
    }

    private static void testNonExistentFile(Path root) throws Exception {
        Path disk = root.resolve("does-not-exist.raw");
        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;

        try {
            SparseDiskResizer.resize(disk, InstanceLifecycle.State.STOPPED, 10L * 1024L * 1024L, checker);
            fail("Expected IOException / NoSuchFileException when disk file does not exist");
        } catch (IOException expected) {
            // PASS
        }
    }

    private static void testExceedsMaximumSupportedLimit(Path root) throws Exception {
        Path disk = root.resolve("max-limit-test.raw");
        createFileWithPayload(disk, 1024L * 1024L, new byte[] {1});

        StorageContract.StorageSpaceChecker checker = path -> Long.MAX_VALUE;
        long overLimit = StorageContract.MAX_ROOT_VOLUME_BYTES + 1L;

        try {
            SparseDiskResizer.resize(disk, InstanceLifecycle.State.STOPPED, overLimit, checker);
            fail("Expected IllegalArgumentException when target size exceeds maximum limit");
        } catch (IllegalArgumentException expected) {
            // PASS
        }
    }

    private static void testInstanceOverload(Path root) throws Exception {
        Path disk = root.resolve("instance-overload.raw");
        long initialSize = 2L * 1024L * 1024L;
        createFileWithPayload(disk, initialSize, new byte[] {123});

        Path metadataDir = root.resolve("meta");
        Files.createDirectories(metadataDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(metadataDir);
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, java.time.Clock.systemUTC());

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), disk);
        controller.beginProvisioning(instance.id());
        InstanceLifecycle.Instance stopped = controller.completeProvisioning(instance.id());

        long targetSize = 8L * 1024L * 1024L;
        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;

        SparseDiskResizer.resize(stopped, targetSize, checker);
        assertEquals(targetSize, Files.size(disk), "instance disk expanded via instance overload");
    }

    private static void createFileWithPayload(Path file, long totalSize, byte[] payload) throws IOException {
        Files.deleteIfExists(file);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.write(payload);
            raf.setLength(totalSize);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " - expected: " + expected + ", actual: " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void fail(String message) {
        throw new AssertionError("Test failed: " + message);
    }
}
