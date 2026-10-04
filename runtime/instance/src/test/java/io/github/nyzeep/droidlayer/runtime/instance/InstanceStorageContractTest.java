package io.github.nyzeep.droidlayer.runtime.instance;

import io.github.nyzeep.droidlayer.runtime.contract.StorageContract;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;

/**
 * Contract tests for instance disk resize, space preflight, and one-way exports
 * to directories or archive packages (ADR-0007, Ticket 33).
 */
public final class InstanceStorageContractTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("expected one approved test directory argument");
        }
        Path testDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        resetDirectory(testDirectory);

        exportsRootVolumeMetadataAndChecksumsToDirectory(testDirectory);
        exportsRootVolumeMetadataAndChecksumsToArchive(testDirectory);
        resizesRootVolumeAndPreservesExistingData(testDirectory);
        blocksResizeWhenStorageSpaceInsufficient(testDirectory);
        rejectsExportAndResizeWhenInstanceIsRunning(testDirectory);
        rejectsDiskShrinking(testDirectory);
        failsClosedWhenRootVolumeIsMissing(testDirectory);
        failsClosedWhenExportDestinationHasInsufficientSpace(testDirectory);

        System.out.println("INSTANCE_STORAGE_CONTRACT_TEST_OK");
    }

    private static void exportsRootVolumeMetadataAndChecksumsToDirectory(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("export-dir-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("alpine-vol.raw");
        createSparseFileWithSentinel(rootVolume, 1024L * 1024L, "SENTINEL_ROOT_BLOCK".getBytes(StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        Path exportDestination = caseDir.resolve("exported-backup");
        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;

        StorageContract.InstanceExportMetadata metadata =
                controller.exportToDirectory(instance.id(), exportDestination, checker);

        assertEquals(instance.id(), metadata.instanceId(), "metadata instanceId");
        assertEquals("alpine", metadata.distroId(), "metadata distroId");
        assertEquals("3.24.1", metadata.distroVersion(), "metadata distroVersion");
        assertEquals(1024L * 1024L, metadata.diskSizeBytes(), "metadata diskSizeBytes");

        // Verify destination files exist
        assertTrue(Files.isRegularFile(exportDestination.resolve("root-volume.raw")), "root-volume.raw exists");
        assertTrue(Files.isRegularFile(exportDestination.resolve("instance-metadata.json")), "instance-metadata.json exists");
        assertTrue(Files.isRegularFile(exportDestination.resolve("SHA256SUMS")), "SHA256SUMS exists");

        // Verify checksums and content using verification helper
        StorageContract.InstanceExportMetadata verified =
                InstanceStorageManager.verifyDirectoryExport(exportDestination);
        assertEquals(metadata, verified, "verified metadata matches exported metadata");
    }

    private static void exportsRootVolumeMetadataAndChecksumsToArchive(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("export-archive-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("archive-vol.raw");
        createSparseFileWithSentinel(rootVolume, 2L * 1024L * 1024L, "ARCHIVE_DATA_BLOCK".getBytes(StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        ByteArrayOutputStream archiveOut = new ByteArrayOutputStream();
        StorageContract.InstanceExportMetadata metadata = controller.exportToArchive(instance.id(), archiveOut);

        byte[] archiveBytes = archiveOut.toByteArray();
        assertTrue(archiveBytes.length > 0, "archive output bytes > 0");

        StorageContract.InstanceExportMetadata verified =
                InstanceStorageManager.verifyArchiveExport(new ByteArrayInputStream(archiveBytes));
        assertEquals(metadata, verified, "verified archive metadata matches");
    }

    private static void resizesRootVolumeAndPreservesExistingData(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("resize-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("resize-vol.raw");
        byte[] sentinel = "ORIGINAL_PRE_RESIZE_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        long initialSize = 10L * 1024L * 1024L; // 10 MiB
        createSparseFileWithSentinel(rootVolume, initialSize, sentinel);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        long targetSize = 25L * 1024L * 1024L; // 25 MiB
        StorageContract.StorageSpaceChecker checker = path -> 500L * 1024L * 1024L;

        controller.resizeRootVolume(instance.id(), targetSize, checker);

        // Check new file size
        assertEquals(targetSize, Files.size(rootVolume), "volume expanded to target size");

        // Verify initial sentinel data preserved byte-for-byte
        byte[] readBackSentinel = new byte[sentinel.length];
        try (RandomAccessFile raf = new RandomAccessFile(rootVolume.toFile(), "r")) {
            raf.seek(0);
            raf.readFully(readBackSentinel);
        }
        assertEquals(new String(sentinel, StandardCharsets.UTF_8),
                new String(readBackSentinel, StandardCharsets.UTF_8),
                "existing volume data preserved intact");
    }

    private static void blocksResizeWhenStorageSpaceInsufficient(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("enospc-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("enospc-vol.raw");
        long initialSize = 10L * 1024L * 1024L; // 10 MiB
        createSparseFileWithSentinel(rootVolume, initialSize, "DATA".getBytes(StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        long targetSize = 50L * 1024L * 1024L; // delta = 40 MiB
        // Checker returns only 5 MiB available
        StorageContract.StorageSpaceChecker insufficientChecker = path -> 5L * 1024L * 1024L;

        StorageContract.InsufficientStorageException ex = assertThrows(
                StorageContract.InsufficientStorageException.class,
                () -> controller.resizeRootVolume(instance.id(), targetSize, insufficientChecker),
                "must block resize when host storage space is insufficient");

        assertEquals(40L * 1024L * 1024L, ex.requiredBytes(), "required delta");
        assertEquals(5L * 1024L * 1024L, ex.availableBytes(), "available bytes");

        // Volume size must remain unchanged
        assertEquals(initialSize, Files.size(rootVolume), "volume length unchanged after blocked resize");
    }

    private static void rejectsExportAndResizeWhenInstanceIsRunning(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("running-safety-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("running-vol.raw");
        createSparseFileWithSentinel(rootVolume, 1024L * 1024L, "RUNNING".getBytes(StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        controller.start(instance.id());

        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;

        assertThrows(
                IllegalStateException.class,
                () -> controller.resizeRootVolume(instance.id(), 2L * 1024L * 1024L, checker),
                "resize on RUNNING instance must be rejected");

        assertThrows(
                IllegalStateException.class,
                () -> controller.exportToDirectory(instance.id(), caseDir.resolve("export"), checker),
                "exportToDirectory on RUNNING instance must be rejected");

        assertThrows(
                IllegalStateException.class,
                () -> controller.exportToArchive(instance.id(), new ByteArrayOutputStream()),
                "exportToArchive on RUNNING instance must be rejected");
    }

    private static void rejectsDiskShrinking(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("shrink-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("shrink-vol.raw");
        long initialSize = 10L * 1024L * 1024L;
        createSparseFileWithSentinel(rootVolume, initialSize, "DATA".getBytes(StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;

        assertThrows(
                IllegalArgumentException.class,
                () -> controller.resizeRootVolume(instance.id(), initialSize - 1024L, checker),
                "shrinking disk must be rejected");

        assertThrows(
                IllegalArgumentException.class,
                () -> controller.resizeRootVolume(instance.id(), initialSize, checker),
                "resizing to identical size must be rejected");
    }

    private static void failsClosedWhenRootVolumeIsMissing(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("missing-vol-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path nonExistent = caseDir.resolve("nonexistent.raw");
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), nonExistent);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;

        assertThrows(
                IOException.class,
                () -> controller.exportToDirectory(instance.id(), caseDir.resolve("export"), checker),
                "export must fail when volume does not exist");

        assertThrows(
                IOException.class,
                () -> controller.resizeRootVolume(instance.id(), 20L * 1024L * 1024L, checker),
                "resize must fail when volume does not exist");
    }

    private static void failsClosedWhenExportDestinationHasInsufficientSpace(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("export-enospc-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("vol.raw");
        long size = 5L * 1024L * 1024L;
        createSparseFileWithSentinel(rootVolume, size, "DATA".getBytes(StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        StorageContract.StorageSpaceChecker insufficientChecker = path -> 1024L; // only 1 KiB available

        assertThrows(
                StorageContract.InsufficientStorageException.class,
                () -> controller.exportToDirectory(instance.id(), caseDir.resolve("export"), insufficientChecker),
                "export must fail when destination storage space is insufficient");
    }

    private static void createSparseFileWithSentinel(Path path, long size, byte[] sentinel) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.setLength(size);
            raf.seek(0);
            raf.write(sentinel);
        }
    }

    private static void resetDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
            return;
        }
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {}
            });
        }
        Files.createDirectories(directory);
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
