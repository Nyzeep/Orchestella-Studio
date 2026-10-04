package io.github.nyzeep.droidlayer;

import android.net.Uri;
import io.github.nyzeep.droidlayer.runtime.contract.StorageContract;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceLifecycle;
import io.github.nyzeep.droidlayer.runtime.instance.InstanceStorageManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter integrating Instance export backups with Android's Storage Access Framework (SAF).
 *
 * <p>Enables single-direction exports of an instance (root volume raw image + metadata JSON + SHA256SUMS)
 * to user-selected SAF Document URIs (as a packaged archive) or directory tree targets (ADR-0007).
 */
public final class AndroidSafExportAdapter {

    /**
     * Seam for opening output streams to user-selected SAF document URIs.
     */
    public interface DocumentStreamOpener {
        OutputStream openOutputStream(Uri uri) throws IOException;
    }

    /**
     * Seam for creating child documents in user-selected SAF tree directories.
     */
    public interface TreeDirectoryWriter {
        OutputStream openChildOutputStream(Uri treeUri, String mimeType, String displayName) throws IOException;
    }

    private final DocumentStreamOpener streamOpener;
    private final TreeDirectoryWriter directoryWriter;
    private final Clock clock;

    public AndroidSafExportAdapter(DocumentStreamOpener streamOpener, Clock clock) {
        this(streamOpener, null, clock);
    }

    public AndroidSafExportAdapter(android.content.Context context, Clock clock) {
        this(
                uri -> {
                    OutputStream stream = context.getContentResolver().openOutputStream(uri);
                    if (stream == null) {
                        throw new IOException("Failed to open stream for URI: " + uri);
                    }
                    return stream;
                },
                (treeUri, mimeType, displayName) -> {
                    Uri childUri = android.provider.DocumentsContract.createDocument(
                            context.getContentResolver(), treeUri, mimeType, displayName);
                    if (childUri == null) {
                        throw new IOException("Failed to create document in SAF tree: " + displayName);
                    }
                    OutputStream stream = context.getContentResolver().openOutputStream(childUri);
                    if (stream == null) {
                        throw new IOException("Failed to open stream for created document: " + childUri);
                    }
                    return stream;
                },
                clock);
    }

    public AndroidSafExportAdapter(
            DocumentStreamOpener streamOpener,
            TreeDirectoryWriter directoryWriter,
            Clock clock) {
        this.streamOpener = Objects.requireNonNull(streamOpener, "streamOpener");
        this.directoryWriter = directoryWriter;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Exports an instance into an archive package via a user-selected SAF Document URI.
     */
    public StorageContract.InstanceExportMetadata exportToDocumentUri(
            InstanceLifecycle.Instance instance,
            Uri documentUri) throws IOException {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(documentUri, "documentUri");

        try (OutputStream outputStream = streamOpener.openOutputStream(documentUri)) {
            if (outputStream == null) {
                throw new IOException("Cannot open output stream for SAF URI: " + documentUri);
            }
            return InstanceStorageManager.exportToArchive(instance, outputStream, clock);
        }
    }

    /**
     * Exports an instance into individual files inside a user-selected SAF Directory Tree URI.
     */
    public StorageContract.InstanceExportMetadata exportToTreeUri(
            InstanceLifecycle.Instance instance,
            Uri treeUri) throws IOException {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(treeUri, "treeUri");
        if (directoryWriter == null) {
            throw new UnsupportedOperationException("TreeDirectoryWriter is not configured");
        }

        if (instance.state() == InstanceLifecycle.State.RUNNING) {
            throw new IllegalStateException("cannot export a RUNNING instance");
        }
        if (instance.state() == InstanceLifecycle.State.PROVISIONING) {
            throw new IllegalStateException("cannot export a PROVISIONING instance");
        }

        Path volumePath = instance.rootVolume();
        if (!Files.isRegularFile(volumePath)) {
            throw new IOException("root volume file does not exist: " + volumePath);
        }

        long volumeSize = Files.size(volumePath);

        // 1. Write root-volume.raw to SAF tree directory
        MessageDigest volumeDigest;
        try {
            volumeDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(volumePath);
                OutputStream out = directoryWriter.openChildOutputStream(
                        treeUri, "application/octet-stream", StorageContract.ROOT_VOLUME_FILE_NAME)) {
            if (out == null) {
                throw new IOException("Cannot create root-volume in SAF tree: " + treeUri);
            }
            int read;
            while ((read = in.read(buffer)) != -1) {
                volumeDigest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        String volumeSha256 = StorageContract.bytesToHex(volumeDigest.digest());

        // 2. Write instance-metadata.json
        long now = clock.millis();
        StorageContract.InstanceExportMetadata metadata = new StorageContract.InstanceExportMetadata(
                instance.id(),
                instance.distro().id(),
                instance.distro().version(),
                instance.runtimeClass().name(),
                instance.state().name(),
                volumeSize,
                volumeSha256,
                instance.createdAtMillis(),
                instance.updatedAtMillis(),
                now);

        byte[] metadataBytes = metadata.toJson().getBytes(StandardCharsets.UTF_8);
        String metadataSha256 = StorageContract.sha256Hex(metadataBytes);

        try (OutputStream out = directoryWriter.openChildOutputStream(
                treeUri, "application/json", StorageContract.METADATA_FILE_NAME)) {
            if (out == null) {
                throw new IOException("Cannot create metadata in SAF tree: " + treeUri);
            }
            out.write(metadataBytes);
        }

        // 3. Write SHA256SUMS
        Map<String, String> checksumEntries = new LinkedHashMap<>();
        checksumEntries.put(StorageContract.ROOT_VOLUME_FILE_NAME, volumeSha256);
        checksumEntries.put(StorageContract.METADATA_FILE_NAME, metadataSha256);
        StorageContract.ChecksumManifest manifest = new StorageContract.ChecksumManifest(checksumEntries);
        byte[] checksumBytes = manifest.format().getBytes(StandardCharsets.UTF_8);

        try (OutputStream out = directoryWriter.openChildOutputStream(
                treeUri, "text/plain", StorageContract.CHECKSUMS_FILE_NAME)) {
            if (out == null) {
                throw new IOException("Cannot create checksums in SAF tree: " + treeUri);
            }
            out.write(checksumBytes);
        }

        return metadata;
    }
}
