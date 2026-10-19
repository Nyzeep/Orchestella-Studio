package io.github.nyzeep.orchestella;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.orchestella.runtime.instance.InstanceUserConfig;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pure JVM unit tests for {@link AndroidSafImportAdapter} (Phase 5.0 Task 4).
 *
 * Verifies:
 * <ul>
 *   <li>Cold backup import and restoration from SAF document URI;</li>
 *   <li>On-the-fly SHA-256 self-check and integrity verification;</li>
 *   <li>Zero disk leakage: temporary files cleaned up on checksum failure;</li>
 *   <li>Missing required files rejection.</li>
 * </ul>
 */
public class AndroidSafImportAdapterTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    private Path workDir;
    private InstanceLifecycle.FileStore store;
    private InstanceLifecycle.Controller controller;

    private static Uri dummyUri() {
        return android.net.TestUri.create("content://dummy-import");
    }

    @Before
    public void setUp() throws Exception {
        workDir = tempFolder.newFolder("saf-import-test").toPath();
        store = new InstanceLifecycle.FileStore(workDir.resolve("metadata"));
        controller = new InstanceLifecycle.Controller(store, CLOCK);
    }

    private static void createSparseFile(Path path, long size, byte[] sentinel) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.setLength(size);
            raf.seek(0);
            raf.write(sentinel);
        }
    }

    private byte[] createExportedArchiveBytes(byte[] payload) throws Exception {
        Path origVolume = workDir.resolve("orig-vol.raw");
        createSparseFile(origVolume, 512L * 1024L, payload);

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("debian", "12.7"), origVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        AndroidSafExportAdapter exportAdapter = new AndroidSafExportAdapter(uri -> captured, CLOCK);
        exportAdapter.exportToDocumentUri(controller.updateHardwareConfig(instance.id(), instance.hardwareConfig()), dummyUri());
        return captured.toByteArray();
    }

    @Test
    public void importsValidArchiveSuccessfullyWithSha256Verification() throws Exception {
        byte[] payload = "MAGIC_ORCHESTELLA_LINUX_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        byte[] archiveBytes = createExportedArchiveBytes(payload);

        Path targetDir = workDir.resolve("restored-instance");
        Path targetVolume = targetDir.resolve("rootfs.raw");

        AndroidSafImportAdapter.DocumentStreamReader reader = uri -> new ByteArrayInputStream(archiveBytes);
        AndroidSafImportAdapter importAdapter = new AndroidSafImportAdapter(reader, CLOCK);

        InstanceLifecycle.Instance restored = importAdapter.importFromDocumentUri(dummyUri(), targetVolume, controller);

        assertNotNull(restored);
        assertEquals(InstanceLifecycle.State.STOPPED, restored.state());
        assertEquals("debian", restored.distro().id());
        assertEquals("12.7", restored.distro().version());
        assertTrue(Files.exists(targetVolume));
        assertEquals(512L * 1024L, Files.size(targetVolume));

        // Verify payload on disk matches
        byte[] readBuffer = new byte[payload.length];
        try (RandomAccessFile raf = new RandomAccessFile(targetVolume.toFile(), "r")) {
            raf.readFully(readBuffer);
        }
        assertEquals(new String(payload, StandardCharsets.UTF_8), new String(readBuffer, StandardCharsets.UTF_8));

        // Verify stored in instance store
        InstanceLifecycle.Instance loaded = store.load(restored.id());
        assertNotNull(loaded);
        assertEquals(restored.id(), loaded.id());
    }

    @Test
    public void preservesUserConfigHardwareAndProvisioningEvidenceAcrossSafRoundTrip() throws Exception {
        Path origVolume = workDir.resolve("configured-vol.raw");
        createSparseFile(origVolume, 512L * 1024L, "CONFIGURED_PAYLOAD".getBytes(StandardCharsets.UTF_8));

        VmHardwareConfig hardwareConfig =
                new VmHardwareConfig(4, 4096, 16L * 1024L * 1024L * 1024L, false, "console=ttyS0");
        InstanceUserConfig userConfig =
                new InstanceUserConfig("alice", "SecurePass123", true, "lab-host");
        InstanceLifecycle.Instance original = controller.create(
                new InstanceLifecycle.DistroReference("debian", "12.7"),
                origVolume,
                hardwareConfig,
                userConfig);
        controller.beginProvisioning(original.id());
        controller.completeProvisioning(original.id());
        String rootfsSha256 = "c".repeat(64);
        original = controller.recordProvisioning(
                original.id(),
                new InstanceLifecycle.ProvisioningEvidence(1, rootfsSha256, 1_789_000_050_000L));

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        new AndroidSafExportAdapter(uri -> captured, CLOCK)
                .exportToDocumentUri(original, dummyUri());

        Path targetVolume = workDir.resolve("configured-restore").resolve("rootfs.raw");
        AndroidSafImportAdapter importAdapter = new AndroidSafImportAdapter(
                uri -> new ByteArrayInputStream(captured.toByteArray()), CLOCK);

        InstanceLifecycle.Instance restored =
                importAdapter.importFromDocumentUri(dummyUri(), targetVolume, controller);

        assertEquals("lab-host", restored.hostname());
        assertEquals("alice", restored.defaultUser());
        assertTrue(restored.hasSudo());
        assertEquals(hardwareConfig, restored.hardwareConfig());
        assertTrue(restored.isProvisionedFor(rootfsSha256));
        assertEquals("", restored.guestPassword());
        assertFalse(restored.bridgeToken().equals(original.bridgeToken()));
        assertEquals(512L * 1024L, Files.size(targetVolume));
    }

    @Test
    public void rejectsCorruptedArchiveWithSha256MismatchAndCleansUpTempFiles() throws Exception {
        byte[] payload = "VALID_DATA".getBytes(StandardCharsets.UTF_8);
        byte[] validArchive = createExportedArchiveBytes(payload);

        // Tamper with archive bytes: reconstruct a zip with mismatched volume
        ByteArrayOutputStream tamperedZip = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(tamperedZip)) {
            // 1. Corrupted volume entry
            zos.putNextEntry(new ZipEntry(StorageContract.ROOT_VOLUME_FILE_NAME));
            zos.write("TAMPERED_GARBAGE_PAYLOAD".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 2. Metadata expecting original hash
            String fakeHash = "a".repeat(64);
            String fakeMetaHash = "b".repeat(64);
            zos.putNextEntry(new ZipEntry(StorageContract.METADATA_FILE_NAME));
            String dummyMeta = "{\"instanceId\":\"fake\",\"distroId\":\"debian\",\"distroVersion\":\"12.7\",\"runtimeClass\":\"FULL_LINUX\",\"state\":\"STOPPED\",\"volumeSizeBytes\":100,\"sha256RootVolume\":\"" + fakeHash + "\",\"createdAtMillis\":0,\"updatedAtMillis\":0,\"exportedAtMillis\":0}";
            byte[] metaBytes = dummyMeta.getBytes(StandardCharsets.UTF_8);
            String actualMetaHash = StorageContract.sha256Hex(metaBytes);
            zos.write(metaBytes);
            zos.closeEntry();

            // 3. Manifest with fake volume hash but valid meta hash
            zos.putNextEntry(new ZipEntry(StorageContract.CHECKSUMS_FILE_NAME));
            String manifest = fakeHash + "  root-volume.raw\n" + actualMetaHash + "  instance-metadata.json\n";
            zos.write(manifest.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        Path targetVolume = workDir.resolve("failed-restore").resolve("rootfs.raw");
        AndroidSafImportAdapter.DocumentStreamReader reader = uri -> new ByteArrayInputStream(tamperedZip.toByteArray());
        AndroidSafImportAdapter importAdapter = new AndroidSafImportAdapter(reader, CLOCK);

        try {
            importAdapter.importFromDocumentUri(dummyUri(), targetVolume, controller);
            fail("Expected IOException on tampered checksum mismatch");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("mismatch"));
        }

        // Verify target volume was NOT created and no lingering .tmp files in parent dir
        assertFalse(Files.exists(targetVolume));
        if (targetVolume.getParent() != null && Files.exists(targetVolume.getParent())) {
            try (var stream = Files.list(targetVolume.getParent())) {
                assertEquals("No lingering temporary files should remain", 0, stream.count());
            }
        }
    }

    @Test
    public void rejectsArchiveMissingRequiredEntries() throws Exception {
        ByteArrayOutputStream incompleteZip = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(incompleteZip)) {
            zos.putNextEntry(new ZipEntry(StorageContract.ROOT_VOLUME_FILE_NAME));
            zos.write("SOME_VOLUME".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            // Missing METADATA and CHECKSUMS
        }

        Path targetVolume = workDir.resolve("incomplete-restore").resolve("rootfs.raw");
        AndroidSafImportAdapter.DocumentStreamReader reader = uri -> new ByteArrayInputStream(incompleteZip.toByteArray());
        AndroidSafImportAdapter importAdapter = new AndroidSafImportAdapter(reader, CLOCK);

        try {
            importAdapter.importFromDocumentUri(dummyUri(), targetVolume, controller);
            fail("Expected IOException on incomplete archive");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("missing") || expected.getMessage().contains("Archive is missing"));
        }

        assertFalse(Files.exists(targetVolume));
    }

    @Test
    public void importsArchiveToStorageDirectoryWithGeneratedVolume() throws Exception {
        byte[] payload = "MAGIC_DIR_IMPORT_PAYLOAD".getBytes(StandardCharsets.UTF_8);
        byte[] archiveBytes = createExportedArchiveBytes(payload);

        Path storageDir = workDir.resolve("auto-storage");
        AndroidSafImportAdapter.DocumentStreamReader reader = uri -> new ByteArrayInputStream(archiveBytes);
        AndroidSafImportAdapter importAdapter = new AndroidSafImportAdapter(reader, CLOCK);

        InstanceLifecycle.Instance restored = importAdapter.importArchiveToStorage(dummyUri(), storageDir, controller);

        assertNotNull(restored);
        assertEquals(InstanceLifecycle.State.STOPPED, restored.state());
        assertTrue(Files.exists(restored.rootVolume()));
        assertEquals(512L * 1024L, Files.size(restored.rootVolume()));
        assertTrue(restored.rootVolume().startsWith(storageDir));
    }
}
