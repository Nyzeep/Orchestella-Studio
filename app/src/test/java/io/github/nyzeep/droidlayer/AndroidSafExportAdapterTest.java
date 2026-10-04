package io.github.nyzeep.droidlayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;
import io.github.nyzeep.droidlayer.runtime.contract.FullLinuxContract;
import io.github.nyzeep.droidlayer.runtime.contract.StorageContract;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceStorageManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for AndroidSafExportAdapter verifying exports to SAF document and tree URIs (AC-1, AC-2).
 */
public final class AndroidSafExportAdapterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_789_000_000_000L), ZoneOffset.UTC);

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void exportsToDocumentUriAsVerifiedArchive() throws Exception {
        Path workDir = temporaryFolder.newFolder("saf-doc").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(workDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = workDir.resolve("doc-vol.raw");
        createSparseFile(rootVolume, 1024L * 1024L, "PAYLOAD_DOC".getBytes(StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        AndroidSafExportAdapter.DocumentStreamOpener opener = uri -> capturedOutput;
        AndroidSafExportAdapter adapter = new AndroidSafExportAdapter(opener, CLOCK);

        Uri dummyUri = Uri.EMPTY;

        StorageContract.InstanceExportMetadata metadata = adapter.exportToDocumentUri(instance, dummyUri);
        assertNotNull(metadata);
        assertEquals(instance.id(), metadata.instanceId());

        byte[] archiveBytes = capturedOutput.toByteArray();
        assertTrue(archiveBytes.length > 0);

        StorageContract.InstanceExportMetadata verified =
                InstanceStorageManager.verifyArchiveExport(new ByteArrayInputStream(archiveBytes));
        assertEquals(metadata, verified);
    }

    @Test
    public void exportsToTreeUriAsThreeVerifiedFiles() throws Exception {
        Path workDir = temporaryFolder.newFolder("saf-tree").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(workDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = workDir.resolve("tree-vol.raw");
        createSparseFile(rootVolume, 1024L * 1024L, "PAYLOAD_TREE".getBytes(StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());

        Map<String, ByteArrayOutputStream> createdFiles = new LinkedHashMap<>();
        AndroidSafExportAdapter.TreeDirectoryWriter writer = (treeUri, mimeType, displayName) -> {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            createdFiles.put(displayName, stream);
            return stream;
        };
        AndroidSafExportAdapter.DocumentStreamOpener opener = uri -> new ByteArrayOutputStream();
        AndroidSafExportAdapter adapter = new AndroidSafExportAdapter(opener, writer, CLOCK);

        Uri dummyTreeUri = Uri.EMPTY;

        StorageContract.InstanceExportMetadata metadata = adapter.exportToTreeUri(instance, dummyTreeUri);
        assertNotNull(metadata);

        assertTrue("root-volume.raw must be written", createdFiles.containsKey(StorageContract.ROOT_VOLUME_FILE_NAME));
        assertTrue("instance-metadata.json must be written", createdFiles.containsKey(StorageContract.METADATA_FILE_NAME));
        assertTrue("SHA256SUMS must be written", createdFiles.containsKey(StorageContract.CHECKSUMS_FILE_NAME));

        byte[] rawBytes = createdFiles.get(StorageContract.ROOT_VOLUME_FILE_NAME).toByteArray();
        String rawSha256 = StorageContract.sha256Hex(rawBytes);

        byte[] metaBytes = createdFiles.get(StorageContract.METADATA_FILE_NAME).toByteArray();
        String metaSha256 = StorageContract.sha256Hex(metaBytes);

        String checksumContent = new String(createdFiles.get(StorageContract.CHECKSUMS_FILE_NAME).toByteArray(), StandardCharsets.UTF_8);
        StorageContract.ChecksumManifest manifest = StorageContract.ChecksumManifest.parse(checksumContent);

        assertEquals(rawSha256, manifest.getHash(StorageContract.ROOT_VOLUME_FILE_NAME));
        assertEquals(metaSha256, manifest.getHash(StorageContract.METADATA_FILE_NAME));
    }

    @Test
    public void rejectsExportWhenInstanceIsRunning() throws Exception {
        Path workDir = temporaryFolder.newFolder("saf-running").toPath();
        InstanceLifecycle.FileStore store = new InstanceLifecycle.FileStore(workDir.resolve("metadata"));
        InstanceLifecycle.Controller controller = new InstanceLifecycle.Controller(store, CLOCK);

        Path rootVolume = workDir.resolve("run-vol.raw");
        createSparseFile(rootVolume, 1024L * 1024L, "PAYLOAD".getBytes(StandardCharsets.UTF_8));

        InstanceLifecycle.Instance instance = controller.create(
                new InstanceLifecycle.DistroReference("alpine", "3.24.1"), rootVolume);
        controller.beginProvisioning(instance.id());
        controller.completeProvisioning(instance.id());
        InstanceLifecycle.Instance runningInstance = controller.start(instance.id());

        AndroidSafExportAdapter adapter = new AndroidSafExportAdapter(uri -> new ByteArrayOutputStream(), CLOCK);
        Uri dummyUri = Uri.EMPTY;

        try {
            adapter.exportToDocumentUri(runningInstance, dummyUri);
            fail("exporting running instance must fail");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("RUNNING"));
        }
    }

    private static void createSparseFile(Path path, long size, byte[] sentinel) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            raf.setLength(size);
            raf.seek(0);
            raf.write(sentinel);
        }
    }
}
