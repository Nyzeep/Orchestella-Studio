package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        roundTripsUserConfigHardwareAndProvisioningEvidence(testDirectory);
        rejectsImportWhenTargetVolumeExists(testDirectory);
        rejectsImportWhenInstanceIdAlreadyExists(testDirectory);
        rejectsDuplicateKnownArchiveEntries(testDirectory);
        rejectsUnsafeArchiveEntryNames(testDirectory);
        rejectsOversizedMetadataAndChecksumEntries(testDirectory);
        rejectsTruncatedArchiveWithoutLeavingFiles(testDirectory);
        rollsBackNewRootVolumeWhenStoreSaveFails(testDirectory);
        rejectsUnknownStrategyUnrecognizedFormatAndVolumeConflictsOnImportAndResize(testDirectory);
        rejectsTruncatedMetadataJsonArchiveWithoutLeavingFiles(testDirectory);
        roundTripsKernelProfileThroughSafArchive(testDirectory);

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

    private static void roundTripsUserConfigHardwareAndProvisioningEvidence(Path testDirectory)
            throws Exception {
        Path caseDir = testDirectory.resolve("roundtrip-config-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("config-vol.raw");
        createSparseFileWithSentinel(
                rootVolume, 1024L * 1024L, "CONFIG_DATA".getBytes(StandardCharsets.UTF_8));

        VmHardwareConfig hardwareConfig =
                new VmHardwareConfig(4, 4096, 16L * 1024L * 1024L * 1024L, false, "console=ttyS0");
        InstanceUserConfig userConfig =
                new InstanceUserConfig("alice", "SecurePass123", true, "lab-host");
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                rootVolume,
                hardwareConfig,
                userConfig);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        String rootfsSha256 = "a".repeat(64);
        controller.recordProvisioning(
                instance.id(),
                new InstanceLifecycle.ProvisioningEvidence(1, rootfsSha256, 1_789_000_050_000L));

        byte[] archiveBytes = exportArchive(controller, instance.id());

        Path restoredVolume = caseDir.resolve("restored").resolve("rootfs.raw");
        String importedId = UUID.randomUUID().toString();
        InstanceLifecycle.Instance restored = controller.importFromArchive(
                new ByteArrayInputStream(archiveBytes), restoredVolume, importedId);

        assertEquals(importedId, restored.id(), "restored uses requested id");
        assertEquals(InstanceLifecycle.State.STOPPED, restored.state(), "restored is STOPPED");
        assertEquals("alpine", restored.distro().id(), "restored distro");
        assertEquals("lab-host", restored.hostname(), "hostname round-trip");
        assertEquals("alice", restored.defaultUser(), "default user round-trip");
        assertTrue(restored.hasSudo(), "sudo choice round-trip");
        assertEquals(hardwareConfig, restored.hardwareConfig(), "hardware config round-trip");
        assertTrue(restored.isProvisionedFor(rootfsSha256), "provisioning evidence round-trip");
        assertEquals("", restored.guestPassword(), "imported guest password stays unknown");
        assertTrue(!restored.bridgeToken().equals(instance.bridgeToken()),
                "imported instance gets a new bridge token");
        assertEquals(1024L * 1024L, Files.size(restoredVolume), "restored volume size");
    }

    private static void rejectsImportWhenTargetVolumeExists(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("target-exists-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("vol.raw");
        createSparseFileWithSentinel(
                rootVolume, 1024L * 1024L, "EXPORTED".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        byte[] archiveBytes = exportArchive(controller, instance.id());

        Path target = caseDir.resolve("existing.raw");
        byte[] sentinel = "KEEP_EXISTING_DATA".getBytes(StandardCharsets.UTF_8);
        Files.write(target, sentinel);

        IOException failure = assertThrows(
                IOException.class,
                () -> controller.importFromArchive(
                        new ByteArrayInputStream(archiveBytes), target, UUID.randomUUID().toString()),
                "import must refuse to overwrite an existing root volume");
        assertTrue(failure.getMessage().contains("overwrite"), "message explains overwrite refusal");
        assertEquals(
                new String(sentinel, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(target), StandardCharsets.UTF_8),
                "existing volume stays byte-for-byte intact");
    }

    private static void rejectsImportWhenInstanceIdAlreadyExists(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("duplicate-instance-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("vol.raw");
        createSparseFileWithSentinel(
                rootVolume, 1024L * 1024L, "EXPORTED".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        byte[] archiveBytes = exportArchive(controller, instance.id());

        String duplicatedId = UUID.randomUUID().toString();
        Path firstTarget = caseDir.resolve("first").resolve("rootfs.raw");
        controller.importFromArchive(
                new ByteArrayInputStream(archiveBytes), firstTarget, duplicatedId);

        Path secondTarget = caseDir.resolve("second").resolve("rootfs.raw");
        IOException failure = assertThrows(
                IOException.class,
                () -> controller.importFromArchive(
                        new ByteArrayInputStream(archiveBytes), secondTarget, duplicatedId),
                "import must refuse an already registered instance id");
        assertTrue(failure.getMessage().contains("already exists"), "message explains duplicate id");
        assertFalse(Files.exists(secondTarget), "duplicate target volume not created");
    }

    private static void rejectsDuplicateKnownArchiveEntries(Path testDirectory) throws Exception {
        for (String duplicateName : List.of(
                StorageContract.ROOT_VOLUME_FILE_NAME,
                StorageContract.METADATA_FILE_NAME,
                StorageContract.CHECKSUMS_FILE_NAME)) {
            // ZipOutputStream forbids duplicate names, so build the stored-entry ZIP by hand.
            byte[] zipBytes = storedZipWithDuplicateEntries(
                    duplicateName, "duplicate-content".getBytes(StandardCharsets.UTF_8));

            Path caseDir = testDirectory.resolve("duplicate-entry-"
                    + duplicateName.replace('.', '-'));
            Files.createDirectories(caseDir);
            InstanceLifecycle.FileStore store =
                    new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
            InstanceLifecycle.Controller controller =
                    new InstanceLifecycle.Controller(store, CLOCK);
            Path target = caseDir.resolve("restore").resolve("rootfs.raw");

            IOException failure = assertThrows(
                    IOException.class,
                    () -> controller.importFromArchive(
                            new ByteArrayInputStream(zipBytes),
                            target,
                            UUID.randomUUID().toString()),
                    "duplicate entry must be rejected: " + duplicateName);
            assertTrue(failure.getMessage().contains("duplicate"),
                    "duplicate message for " + duplicateName);
            assertFalse(Files.exists(target), "no volume after duplicate entry: " + duplicateName);
        }
    }

    private static void rejectsUnsafeArchiveEntryNames(Path testDirectory) throws Exception {
        for (String unsafeName : List.of("../escape.raw", "/absolute.raw", "nested/root.raw", "nested\\root.raw")) {
            ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
                zip.putNextEntry(new ZipEntry(unsafeName));
                zip.write("unsafe".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            Path caseDir = testDirectory.resolve("unsafe-name-" + Math.abs(unsafeName.hashCode()));
            Files.createDirectories(caseDir);
            InstanceLifecycle.FileStore store =
                    new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
            InstanceLifecycle.Controller controller =
                    new InstanceLifecycle.Controller(store, CLOCK);
            Path target = caseDir.resolve("restore").resolve("rootfs.raw");

            IOException failure = assertThrows(
                    IOException.class,
                    () -> controller.importFromArchive(
                            new ByteArrayInputStream(zipBytes.toByteArray()),
                            target,
                            UUID.randomUUID().toString()),
                    "unsafe entry name must be rejected: " + unsafeName);
            assertTrue(failure.getMessage().contains("unsafe") || failure.getMessage().contains("invalid"),
                    "unsafe message for " + unsafeName);
            assertFalse(Files.exists(target), "no volume after unsafe entry: " + unsafeName);
        }
    }

    private static void rejectsOversizedMetadataAndChecksumEntries(Path testDirectory) throws Exception {
        assertOversizedEntryRejected(
                testDirectory, StorageContract.METADATA_FILE_NAME, 1024 * 1024 + 1);
        assertOversizedEntryRejected(
                testDirectory, StorageContract.CHECKSUMS_FILE_NAME, 64 * 1024 + 1);
    }

    private static void assertOversizedEntryRejected(
            Path testDirectory, String entryName, int size) throws Exception {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(new byte[size]);
            zip.closeEntry();
        }

        Path caseDir = testDirectory.resolve("oversized-" + entryName.replace('.', '-'));
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);
        Path target = caseDir.resolve("restore").resolve("rootfs.raw");

        IOException failure = assertThrows(
                IOException.class,
                () -> controller.importFromArchive(
                        new ByteArrayInputStream(zipBytes.toByteArray()),
                        target,
                        UUID.randomUUID().toString()),
                "oversized entry must be rejected: " + entryName);
        assertTrue(failure.getMessage().contains("exceeds"), "size limit message for " + entryName);
        assertFalse(Files.exists(target), "no volume after oversized entry: " + entryName);
    }

    private static void rejectsTruncatedArchiveWithoutLeavingFiles(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("truncated-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = caseDir.resolve("vol.raw");
        createSparseFileWithSentinel(
                rootVolume, 2L * 1024L * 1024L, "TRUNCATE_ME".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        byte[] archiveBytes = exportArchive(controller, instance.id());
        byte[] truncated = java.util.Arrays.copyOf(archiveBytes, archiveBytes.length / 2);

        Path target = caseDir.resolve("restore").resolve("rootfs.raw");
        assertThrows(
                IOException.class,
                () -> controller.importFromArchive(
                        new ByteArrayInputStream(truncated), target, UUID.randomUUID().toString()),
                "truncated archive must fail closed");
        assertFalse(Files.exists(target), "truncated import leaves no target volume");
        Path parent = target.getParent();
        try (var stream = Files.list(parent)) {
            assertEquals(0L, stream.count(), "truncated import leaves no temp files");
        }
    }

    private static void rollsBackNewRootVolumeWhenStoreSaveFails(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("save-failure-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore sourceStore =
                new InstanceLifecycle.FileStore(caseDir.resolve("source-metadata"));
        InstanceLifecycle.Controller sourceController =
                new InstanceLifecycle.Controller(sourceStore, CLOCK);

        Path rootVolume = caseDir.resolve("vol.raw");
        createSparseFileWithSentinel(
                rootVolume, 1024L * 1024L, "SAVE_FAILURE".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance instance = sourceController.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        sourceController.beginProvisioning(instance.id());
        sourceController.completeProvisioning(instance.id());
        byte[] archiveBytes = exportArchive(sourceController, instance.id());

        FailingSaveStore failingStore = new FailingSaveStore();
        Path target = caseDir.resolve("failed-save").resolve("rootfs.raw");

        IOException failure = assertThrows(
                IOException.class,
                () -> InstanceStorageManager.importFromArchive(
                        new ByteArrayInputStream(archiveBytes),
                        target,
                        UUID.randomUUID().toString(),
                        failingStore,
                        CLOCK),
                "store.save failure must propagate");
        assertTrue(failure.getMessage().contains("simulated"), "original save failure preserved");
        assertFalse(Files.exists(target), "root volume rolled back after save failure");
        Path parent = target.getParent();
        try (var stream = Files.list(parent)) {
            assertEquals(0L, stream.count(), "no half-instance files remain after save failure");
        }
    }

    private static void rejectsUnknownStrategyUnrecognizedFormatAndVolumeConflictsOnImportAndResize(
            Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("policy-volume-conflict-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        // An instance carrying an unknown strategy stays exportable for backup/recovery,
        // while importing an archive with unknown strategy/format or QCOW2 container magic fails closed.
        Path umlVol = caseDir.resolve("uml.raw");
        createSparseFileWithSentinel(umlVol, 1024L * 1024L, "UML_RAW_DATA".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance umlInstance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                umlVol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE));
        controller.beginProvisioning(umlInstance.id());
        controller.completeProvisioning(umlInstance.id());

        java.util.Properties unknownProps = umlInstance.toProperties();
        unknownProps.setProperty("execution.strategy", "FUTURE_EXEC_V9");
        InstanceLifecycle.Instance unknownInstance = InstanceLifecycle.Instance.fromProperties(unknownProps);
        store.save(unknownInstance);
        byte[] unknownArchive = exportArchive(controller, umlInstance.id());
        assertTrue(unknownArchive.length > 0, "unknown strategy instance remains exportable");

        Path rejectedTarget = caseDir.resolve("rejected").resolve("rootfs.raw");
        IOException unknownStrategyFailure = assertThrows(
                IOException.class,
                () -> controller.importFromArchive(
                        new ByteArrayInputStream(unknownArchive),
                        rejectedTarget,
                        UUID.randomUUID().toString()),
                "archive with unknown execution strategy must be rejected on import");
        assertTrue(unknownStrategyFailure.getMessage().contains("UNKNOWN_EXECUTION_STRATEGY"),
                "diagnoses unknown strategy on import");
        assertFalse(Files.exists(rejectedTarget), "rejected import creates no root volume");

        // QCOW2 header disguised as root-volume.raw must fail resize and archive import without touching source.
        Path qcow2Vol = caseDir.resolve("spoofed-qcow2.raw");
        byte[] qcow2Header = new byte[] { 0x51, 0x46, 0x49, (byte) 0xFB, 0x00, 0x00, 0x00, 0x03 };
        Files.write(qcow2Vol, qcow2Header);
        InstanceLifecycle.Instance spoofed = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                qcow2Vol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.SOFTWARE_VIRTUALIZATION,
                        StorageContract.RootVolumeFormat.RAW_SPARSE));
        controller.beginProvisioning(spoofed.id());
        controller.completeProvisioning(spoofed.id());

        StorageContract.StorageSpaceChecker checker = path -> 100L * 1024L * 1024L;
        IOException resizeConflict = assertThrows(
                IOException.class,
                () -> controller.resizeRootVolume(spoofed.id(), 2L * 1024L * 1024L, checker),
                "resize must refuse QCOW2 container");
        assertTrue(resizeConflict.getMessage().contains("VOLUME_FORMAT_CONFLICT"),
                "resize conflict carries diagnostic code");
        assertEquals((long) qcow2Header.length, Files.size(qcow2Vol), "original conflicted file size untouched");

        byte[] spoofedArchive = exportArchive(controller, spoofed.id());
        IOException importConflict = assertThrows(
                IOException.class,
                () -> controller.importFromArchive(
                        new ByteArrayInputStream(spoofedArchive),
                        rejectedTarget,
                        UUID.randomUUID().toString()),
                "archive with QCOW2 root volume must fail import");
        assertTrue(importConflict.getMessage().contains("VOLUME_FORMAT_CONFLICT"),
                "import conflict carries diagnostic code");
        assertFalse(Files.exists(rejectedTarget), "conflicted import creates no root volume");
    }

    private static void rejectsTruncatedMetadataJsonArchiveWithoutLeavingFiles(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("truncated-metadata-json-test");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVol = caseDir.resolve("uml-trunc.raw");
        createSparseFileWithSentinel(rootVol, 1024L * 1024L, "TRUNC_JSON_DATA".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"),
                rootVol,
                VmHardwareConfig.defaultConfig(),
                InstanceUserConfig.defaultRoot(),
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE));
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        byte[] validArchive = exportArchive(controller, instance.id());
        byte[] volBytes = null;
        byte[] metaBytes = null;
        try (java.util.zip.ZipInputStream zin =
                new java.util.zip.ZipInputStream(new ByteArrayInputStream(validArchive))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (StorageContract.ROOT_VOLUME_FILE_NAME.equals(entry.getName())) {
                    volBytes = zin.readAllBytes();
                } else if (StorageContract.METADATA_FILE_NAME.equals(entry.getName())) {
                    metaBytes = zin.readAllBytes();
                }
            }
        }
        assertTrue(volBytes != null && metaBytes != null, "valid archive contains volume and metadata");
        String fullJson = new String(metaBytes, StandardCharsets.UTF_8);
        int strategyKeyIdx = fullJson.indexOf("\"executionStrategy\"");
        assertTrue(strategyKeyIdx > 0, "exported metadata includes executionStrategy");
        byte[] truncatedMeta = fullJson.substring(0, strategyKeyIdx + 8).getBytes(StandardCharsets.UTF_8);

        byte[] checksumsBytes = new StorageContract.ChecksumManifest(java.util.Map.of(
                        StorageContract.ROOT_VOLUME_FILE_NAME, StorageContract.sha256Hex(volBytes),
                        StorageContract.METADATA_FILE_NAME, StorageContract.sha256Hex(truncatedMeta)))
                .format()
                .getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream corruptedZip = new ByteArrayOutputStream();
        try (ZipOutputStream zout = new ZipOutputStream(corruptedZip)) {
            zout.putNextEntry(new ZipEntry(StorageContract.ROOT_VOLUME_FILE_NAME));
            zout.write(volBytes);
            zout.closeEntry();
            zout.putNextEntry(new ZipEntry(StorageContract.METADATA_FILE_NAME));
            zout.write(truncatedMeta);
            zout.closeEntry();
            zout.putNextEntry(new ZipEntry(StorageContract.CHECKSUMS_FILE_NAME));
            zout.write(checksumsBytes);
            zout.closeEntry();
        }

        Path rejectedTarget = caseDir.resolve("restore").resolve("rootfs.raw");
        Exception failure = assertThrows(
                Exception.class,
                () -> controller.importFromArchive(
                        new ByteArrayInputStream(corruptedZip.toByteArray()),
                        rejectedTarget,
                        UUID.randomUUID().toString()),
                "truncated instance-metadata.json must fail closed");
        assertTrue(
                failure instanceof IOException || failure instanceof IllegalArgumentException,
                "expected IOException or IllegalArgumentException, got: " + failure.getClass().getName());
        assertFalse(Files.exists(rejectedTarget), "truncated JSON import creates no root volume");
        assertEquals(1, store.list().size(), "failed import does not mutate instance store");
    }

    /** Builds a stored-entry ZIP that intentionally repeats one entry name twice. */
    private static byte[] storedZipWithDuplicateEntries(String name, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(content);
        long crcValue = crc.getValue();

        List<Integer> localOffsets = new ArrayList<>();
        for (int copy = 0; copy < 2; copy++) {
            localOffsets.add(out.size());
            writeLeInt(out, 0x04034b50);
            writeLeShort(out, 20);
            writeLeShort(out, 0);
            writeLeShort(out, 0);
            writeLeShort(out, 0);
            writeLeShort(out, 0);
            writeLeInt(out, (int) crcValue);
            writeLeInt(out, content.length);
            writeLeInt(out, content.length);
            writeLeShort(out, nameBytes.length);
            writeLeShort(out, 0);
            out.write(nameBytes);
            out.write(content);
        }

        int centralDirectoryStart = out.size();
        for (int offset : localOffsets) {
            writeLeInt(out, 0x02014b50);
            writeLeShort(out, 20);
            writeLeShort(out, 20);
            writeLeShort(out, 0);
            writeLeShort(out, 0);
            writeLeShort(out, 0);
            writeLeShort(out, 0);
            writeLeInt(out, (int) crcValue);
            writeLeInt(out, content.length);
            writeLeInt(out, content.length);
            writeLeShort(out, nameBytes.length);
            writeLeShort(out, 0);
            writeLeShort(out, 0);
            writeLeShort(out, 0);
            writeLeShort(out, 0);
            writeLeInt(out, 0);
            writeLeInt(out, offset);
            out.write(nameBytes);
        }
        int centralDirectorySize = out.size() - centralDirectoryStart;

        writeLeInt(out, 0x06054b50);
        writeLeShort(out, 0);
        writeLeShort(out, 0);
        writeLeShort(out, 2);
        writeLeShort(out, 2);
        writeLeInt(out, centralDirectorySize);
        writeLeInt(out, centralDirectoryStart);
        writeLeShort(out, 0);
        return out.toByteArray();
    }

    private static void writeLeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeLeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void roundTripsKernelProfileThroughSafArchive(Path testDirectory) throws Exception {
        Path caseDir = testDirectory.resolve("kernel-profile-saf");
        Files.createDirectories(caseDir);
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(caseDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path sourceVolume = caseDir.resolve("deb-src.raw");
        createSparseFileWithSentinel(sourceVolume, 1024L * 1024L, "DEBIAN_EXT4_SENTINEL".getBytes(StandardCharsets.UTF_8));
        InstanceLifecycle.Instance created = controller.create(
                new InstanceLifecycle.DistroReference("debian", "13"),
                sourceVolume,
                VmHardwareConfig.defaultConfig(),
                new InstanceUserConfig("root", "qa-pass-1234", false, "qa-uml-debian"),
                ExecutionPolicyContract.InstanceExecutionIdentity.explicit(
                        FullLinuxContract.ExecutionStrategy.USER_MODE_KERNEL,
                        StorageContract.RootVolumeFormat.RAW_SPARSE),
                "full-smp-arm64");
        controller.beginProvisioning(created.id());
        controller.completeProvisioning(created.id());

        byte[] archive = exportArchive(controller, created.id());
        Path restoredVolume = caseDir.resolve("deb-restored.raw");
        InstanceLifecycle.Instance restored = controller.importFromArchive(
                new ByteArrayInputStream(archive), restoredVolume);
        assertEquals("full-smp-arm64", restored.kernelProfile(), "SAF archive preserves kernelProfile");
        assertEquals(created.executionIdentity(), restored.executionIdentity(),
                "SAF archive preserves executionIdentity");
        assertEquals("full-smp-arm64", store.load(restored.id()).kernelProfile(),
                "persisted restored instance retains kernelProfile");
    }

    private static byte[] exportArchive(InstanceLifecycle.Controller controller, String instanceId)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        controller.exportToArchive(instanceId, output);
        return output.toByteArray();
    }

    private static final class FailingSaveStore implements InstanceLifecycle.Store {
        @Override
        public void save(InstanceLifecycle.Instance instance) throws IOException {
            throw new IOException("simulated store save failure");
        }

        @Override
        public InstanceLifecycle.Instance load(String id) throws IOException {
            throw new IOException("instance metadata not found");
        }

        @Override
        public List<InstanceLifecycle.Instance> list() {
            return List.of();
        }

        @Override
        public void delete(String id) {
        }
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

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
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
