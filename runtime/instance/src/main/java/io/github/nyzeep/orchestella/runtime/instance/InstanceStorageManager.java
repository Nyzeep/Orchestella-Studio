package io.github.nyzeep.orchestella.runtime.instance;

import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Manages storage operations for Full Linux instances: disk expansion with space preflight
 * and one-way export backups to directories or archive streams (ADR-0007).
 */
public final class InstanceStorageManager {

    private InstanceStorageManager() {}

    /**
     * Expands the raw root-volume disk image of an instance to {@code targetSizeBytes}.
     *
     * <p>Preflight checks verify available host storage space to prevent ENOSPC overcommit.
     * Disk shrinking is strictly rejected. Only non-running instances may be resized.
     *
     * @param instance instance whose root volume is to be resized
     * @param targetSizeBytes target volume size in bytes (must be strictly greater than current size)
     * @param spaceChecker seam for checking available host storage space
     * @throws IOException on I/O error or if host storage space is insufficient
     * @throws IllegalStateException if instance is currently RUNNING or PROVISIONING
     * @throws IllegalArgumentException if target size is not greater than current size
     */
    public static void resizeRootVolume(
            InstanceLifecycle.Instance instance,
            long targetSizeBytes,
            StorageContract.StorageSpaceChecker spaceChecker) throws IOException {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(spaceChecker, "spaceChecker");

        if (instance.state() == InstanceLifecycle.State.RUNNING) {
            throw new IllegalStateException("cannot resize disk of a RUNNING instance");
        }
        if (instance.state() == InstanceLifecycle.State.PROVISIONING) {
            throw new IllegalStateException("cannot resize disk of a PROVISIONING instance");
        }

        Path volumePath = instance.rootVolume();
        if (!Files.isRegularFile(volumePath)) {
            throw new IOException("root volume file does not exist: " + volumePath);
        }

        long currentSizeBytes = Files.size(volumePath);
        StorageContract.DiskResizeSpec.validate(currentSizeBytes, targetSizeBytes);
        StorageContract.StoragePreflight.checkResizeSpace(spaceChecker, volumePath, currentSizeBytes, targetSizeBytes);

        try (RandomAccessFile raf = new RandomAccessFile(volumePath.toFile(), "rw")) {
            raf.setLength(targetSizeBytes);
        }
    }

    /**
     * Exports an instance (root-volume.raw, instance-metadata.json, and SHA256SUMS) to a directory.
     *
     * @param instance instance to export
     * @param destinationDirectory target directory
     * @param spaceChecker seam for checking available destination storage space
     * @param clock clock for timestamps
     * @return export metadata
     * @throws IOException on I/O failure or insufficient destination space
     * @throws IllegalStateException if instance is RUNNING or PROVISIONING
     */
    public static StorageContract.InstanceExportMetadata exportToDirectory(
            InstanceLifecycle.Instance instance,
            Path destinationDirectory,
            StorageContract.StorageSpaceChecker spaceChecker,
            Clock clock) throws IOException {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(destinationDirectory, "destinationDirectory");
        Objects.requireNonNull(spaceChecker, "spaceChecker");
        Objects.requireNonNull(clock, "clock");

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
        Files.createDirectories(destinationDirectory);
        // Preflight destination storage space (volume size + 2MB safety margin for metadata)
        StorageContract.StoragePreflight.checkCreationSpace(
                spaceChecker, destinationDirectory, volumeSize + 2L * 1024L * 1024L);

        Path targetVolume = destinationDirectory.resolve(StorageContract.ROOT_VOLUME_FILE_NAME);
        MessageDigest volumeDigest = createSha256Digest();

        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(volumePath);
                OutputStream out = Files.newOutputStream(
                        targetVolume,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                volumeDigest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        String volumeSha256 = StorageContract.bytesToHex(volumeDigest.digest());

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

        Path metadataPath = destinationDirectory.resolve(StorageContract.METADATA_FILE_NAME);
        String metadataJson = metadata.toJson();
        Files.writeString(metadataPath, metadataJson, StandardCharsets.UTF_8);

        String metadataSha256 = StorageContract.sha256Hex(metadataJson.getBytes(StandardCharsets.UTF_8));

        Map<String, String> checksumEntries = new LinkedHashMap<>();
        checksumEntries.put(StorageContract.ROOT_VOLUME_FILE_NAME, volumeSha256);
        checksumEntries.put(StorageContract.METADATA_FILE_NAME, metadataSha256);
        StorageContract.ChecksumManifest manifest = new StorageContract.ChecksumManifest(checksumEntries);

        Path checksumPath = destinationDirectory.resolve(StorageContract.CHECKSUMS_FILE_NAME);
        Files.writeString(checksumPath, manifest.format(), StandardCharsets.UTF_8);

        return metadata;
    }

    /**
     * Exports an instance into an archive output stream in ZIP format.
     *
     * <p>The ZIP stream contains:
     * <ol>
     *   <li>{@value StorageContract#ROOT_VOLUME_FILE_NAME}</li>
     *   <li>{@value StorageContract#METADATA_FILE_NAME}</li>
     *   <li>{@value StorageContract#CHECKSUMS_FILE_NAME}</li>
     * </ol>
     *
     * @param instance instance to export
     * @param destinationStream destination output stream
     * @param clock clock for timestamps
     * @return export metadata
     * @throws IOException on I/O error
     * @throws IllegalStateException if instance is RUNNING or PROVISIONING
     */
    public static StorageContract.InstanceExportMetadata exportToArchive(
            InstanceLifecycle.Instance instance,
            OutputStream destinationStream,
            Clock clock) throws IOException {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(destinationStream, "destinationStream");
        Objects.requireNonNull(clock, "clock");

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
        ZipOutputStream zip = new ZipOutputStream(destinationStream);

        // 1. Stream root-volume.raw into ZIP entry and compute SHA-256
        ZipEntry volumeEntry = new ZipEntry(StorageContract.ROOT_VOLUME_FILE_NAME);
        zip.putNextEntry(volumeEntry);

        MessageDigest volumeDigest = createSha256Digest();
        byte[] buffer = new byte[65536];
        try (InputStream in = Files.newInputStream(volumePath)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                volumeDigest.update(buffer, 0, read);
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
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

        ZipEntry metadataEntry = new ZipEntry(StorageContract.METADATA_FILE_NAME);
        zip.putNextEntry(metadataEntry);
        zip.write(metadataBytes);
        zip.closeEntry();

        // 3. Write SHA256SUMS
        Map<String, String> checksumEntries = new LinkedHashMap<>();
        checksumEntries.put(StorageContract.ROOT_VOLUME_FILE_NAME, volumeSha256);
        checksumEntries.put(StorageContract.METADATA_FILE_NAME, metadataSha256);
        StorageContract.ChecksumManifest manifest = new StorageContract.ChecksumManifest(checksumEntries);

        byte[] checksumBytes = manifest.format().getBytes(StandardCharsets.UTF_8);
        ZipEntry checksumEntry = new ZipEntry(StorageContract.CHECKSUMS_FILE_NAME);
        zip.putNextEntry(checksumEntry);
        zip.write(checksumBytes);
        zip.closeEntry();

        zip.finish();
        zip.flush();

        return metadata;
    }

    /**
     * Verifies the integrity of an exported directory bundle.
     *
     * @param directory directory containing the exported files
     * @return verified export metadata
     * @throws IOException on verification failure or missing files
     */
    public static StorageContract.InstanceExportMetadata verifyDirectoryExport(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path volumePath = directory.resolve(StorageContract.ROOT_VOLUME_FILE_NAME);
        Path metadataPath = directory.resolve(StorageContract.METADATA_FILE_NAME);
        Path checksumPath = directory.resolve(StorageContract.CHECKSUMS_FILE_NAME);

        if (!Files.isRegularFile(volumePath)) {
            throw new IOException("missing exported root-volume: " + volumePath);
        }
        if (!Files.isRegularFile(metadataPath)) {
            throw new IOException("missing exported metadata: " + metadataPath);
        }
        if (!Files.isRegularFile(checksumPath)) {
            throw new IOException("missing exported checksum manifest: " + checksumPath);
        }

        String checksumContent = Files.readString(checksumPath, StandardCharsets.UTF_8);
        StorageContract.ChecksumManifest manifest = StorageContract.ChecksumManifest.parse(checksumContent);

        String expectedVolumeHash = manifest.getHash(StorageContract.ROOT_VOLUME_FILE_NAME);
        if (expectedVolumeHash == null) {
            throw new IOException("SHA256SUMS does not include root-volume.raw");
        }
        String actualVolumeHash;
        try (InputStream in = Files.newInputStream(volumePath)) {
            actualVolumeHash = StorageContract.sha256Hex(in);
        }
        if (!expectedVolumeHash.equalsIgnoreCase(actualVolumeHash)) {
            throw new IOException("root-volume.raw checksum mismatch: expected "
                    + expectedVolumeHash + ", got " + actualVolumeHash);
        }

        String expectedMetaHash = manifest.getHash(StorageContract.METADATA_FILE_NAME);
        if (expectedMetaHash == null) {
            throw new IOException("SHA256SUMS does not include instance-metadata.json");
        }
        String metadataContent = Files.readString(metadataPath, StandardCharsets.UTF_8);
        String actualMetaHash = StorageContract.sha256Hex(metadataContent.getBytes(StandardCharsets.UTF_8));
        if (!expectedMetaHash.equalsIgnoreCase(actualMetaHash)) {
            throw new IOException("instance-metadata.json checksum mismatch: expected "
                    + expectedMetaHash + ", got " + actualMetaHash);
        }

        StorageContract.InstanceExportMetadata metadata =
                StorageContract.InstanceExportMetadata.fromJson(metadataContent);
        if (!metadata.sha256RootVolume().equalsIgnoreCase(actualVolumeHash)) {
            throw new IOException("metadata sha256RootVolume does not match volume hash");
        }

        return metadata;
    }

    /**
     * Verifies the integrity of an exported archive stream in ZIP format.
     *
     * @param archiveInputStream input stream containing the exported ZIP
     * @return verified export metadata
     * @throws IOException on verification failure
     */
    public static StorageContract.InstanceExportMetadata verifyArchiveExport(InputStream archiveInputStream)
            throws IOException {
        Objects.requireNonNull(archiveInputStream, "archiveInputStream");

        byte[] volumeBytes = null;
        String volumeSha256 = null;
        String metadataContent = null;
        String metadataSha256 = null;
        String checksumContent = null;

        try (ZipInputStream zip = new ZipInputStream(archiveInputStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] temp = new byte[8192];
                int read;
                while ((read = zip.read(temp)) != -1) {
                    buffer.write(temp, 0, read);
                }
                byte[] entryData = buffer.toByteArray();

                if (StorageContract.ROOT_VOLUME_FILE_NAME.equals(entry.getName())) {
                    volumeBytes = entryData;
                    volumeSha256 = StorageContract.sha256Hex(volumeBytes);
                } else if (StorageContract.METADATA_FILE_NAME.equals(entry.getName())) {
                    metadataContent = new String(entryData, StandardCharsets.UTF_8);
                    metadataSha256 = StorageContract.sha256Hex(entryData);
                } else if (StorageContract.CHECKSUMS_FILE_NAME.equals(entry.getName())) {
                    checksumContent = new String(entryData, StandardCharsets.UTF_8);
                }
                zip.closeEntry();
            }
        }

        if (volumeBytes == null) {
            throw new IOException("archive is missing " + StorageContract.ROOT_VOLUME_FILE_NAME);
        }
        if (metadataContent == null) {
            throw new IOException("archive is missing " + StorageContract.METADATA_FILE_NAME);
        }
        if (checksumContent == null) {
            throw new IOException("archive is missing " + StorageContract.CHECKSUMS_FILE_NAME);
        }

        StorageContract.ChecksumManifest manifest = StorageContract.ChecksumManifest.parse(checksumContent);
        String expectedVolumeHash = manifest.getHash(StorageContract.ROOT_VOLUME_FILE_NAME);
        if (!Objects.equals(expectedVolumeHash, volumeSha256)) {
            throw new IOException("archive root-volume checksum mismatch");
        }

        String expectedMetaHash = manifest.getHash(StorageContract.METADATA_FILE_NAME);
        if (!Objects.equals(expectedMetaHash, metadataSha256)) {
            throw new IOException("archive metadata checksum mismatch");
        }

        StorageContract.InstanceExportMetadata metadata =
                StorageContract.InstanceExportMetadata.fromJson(metadataContent);
        if (!metadata.sha256RootVolume().equalsIgnoreCase(volumeSha256)) {
            throw new IOException("archive metadata sha256RootVolume mismatch");
        }

        return metadata;
    }

    /**
     * Imports an instance from an archive ZIP stream, streaming the root volume to {@code targetRootVolume},
     * performing on-the-fly SHA-256 integrity verification, and registering a new independent instance.
     *
     * @param archiveInputStream input stream containing the backup archive
     * @param targetRootVolume destination path for the raw root-volume disk
     * @param store instance store to save the restored instance
     * @param clock clock for timestamps
     * @return newly restored instance in STOPPED state
     * @throws IOException on I/O error or if SHA-256 integrity verification fails
     */
    public static InstanceLifecycle.Instance importFromArchive(
            InputStream archiveInputStream,
            Path targetRootVolume,
            InstanceLifecycle.Store store,
            Clock clock) throws IOException {
        return importFromArchive(archiveInputStream, targetRootVolume, null, store, clock);
    }

    /**
     * Imports an instance from an archive ZIP stream with an explicit instance ID.
     */
    public static InstanceLifecycle.Instance importFromArchive(
            InputStream archiveInputStream,
            Path targetRootVolume,
            String targetInstanceId,
            InstanceLifecycle.Store store,
            Clock clock) throws IOException {
        Objects.requireNonNull(archiveInputStream, "archiveInputStream");
        Objects.requireNonNull(targetRootVolume, "targetRootVolume");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(clock, "clock");

        if (targetRootVolume.getParent() != null) {
            Files.createDirectories(targetRootVolume.getParent());
        }

        Path tempVolume = targetRootVolume.resolveSibling(
                targetRootVolume.getFileName().toString() + ".import.tmp." + System.nanoTime());

        byte[] metadataBytes = null;
        String checksumContent = null;
        String computedVolumeHash = null;

        try {
            try (ZipInputStream zip = new ZipInputStream(archiveInputStream)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (StorageContract.ROOT_VOLUME_FILE_NAME.equals(name)) {
                        MessageDigest digest = createSha256Digest();
                        try (OutputStream out = Files.newOutputStream(
                                tempVolume,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE)) {
                            byte[] buffer = new byte[65536];
                            int read;
                            while ((read = zip.read(buffer)) != -1) {
                                digest.update(buffer, 0, read);
                                out.write(buffer, 0, read);
                            }
                        }
                        computedVolumeHash = StorageContract.bytesToHex(digest.digest());
                    } else if (StorageContract.METADATA_FILE_NAME.equals(name)) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] temp = new byte[8192];
                        int read;
                        while ((read = zip.read(temp)) != -1) {
                            buffer.write(temp, 0, read);
                        }
                        metadataBytes = buffer.toByteArray();
                    } else if (StorageContract.CHECKSUMS_FILE_NAME.equals(name)) {
                        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        byte[] temp = new byte[8192];
                        int read;
                        while ((read = zip.read(temp)) != -1) {
                            buffer.write(temp, 0, read);
                        }
                        checksumContent = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                    }
                    zip.closeEntry();
                }
            }

            if (computedVolumeHash == null || !Files.exists(tempVolume)) {
                throw new IOException("Archive is missing " + StorageContract.ROOT_VOLUME_FILE_NAME);
            }
            if (metadataBytes == null) {
                throw new IOException("Archive is missing " + StorageContract.METADATA_FILE_NAME);
            }
            if (checksumContent == null) {
                throw new IOException("Archive is missing " + StorageContract.CHECKSUMS_FILE_NAME);
            }

            StorageContract.ChecksumManifest manifest;
            try {
                manifest = StorageContract.ChecksumManifest.parse(checksumContent);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid SHA256SUMS manifest: " + e.getMessage(), e);
            }
            String expectedVolumeHash = manifest.getHash(StorageContract.ROOT_VOLUME_FILE_NAME);
            if (expectedVolumeHash == null || !expectedVolumeHash.equalsIgnoreCase(computedVolumeHash)) {
                throw new IOException("archive root-volume checksum mismatch: expected "
                        + expectedVolumeHash + ", got " + computedVolumeHash);
            }

            String computedMetadataHash = StorageContract.sha256Hex(metadataBytes);
            String expectedMetaHash = manifest.getHash(StorageContract.METADATA_FILE_NAME);
            if (expectedMetaHash == null || !expectedMetaHash.equalsIgnoreCase(computedMetadataHash)) {
                throw new IOException("archive metadata checksum mismatch: expected "
                        + expectedMetaHash + ", got " + computedMetadataHash);
            }

            String metadataJson = new String(metadataBytes, StandardCharsets.UTF_8);
            StorageContract.InstanceExportMetadata metadata;
            try {
                metadata = StorageContract.InstanceExportMetadata.fromJson(metadataJson);
            } catch (Exception e) {
                throw new IOException("Invalid instance metadata format: " + e.getMessage(), e);
            }
            if (!metadata.sha256RootVolume().equalsIgnoreCase(computedVolumeHash)) {
                throw new IOException("archive metadata sha256RootVolume mismatch");
            }

            try {
                Files.move(tempVolume, targetRootVolume, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tempVolume, targetRootVolume, StandardCopyOption.REPLACE_EXISTING);
            }

            String instanceId = (targetInstanceId != null && !targetInstanceId.trim().isEmpty())
                    ? targetInstanceId.trim()
                    : java.util.UUID.randomUUID().toString();

            InstanceLifecycle.DistroReference distro = new InstanceLifecycle.DistroReference(
                    metadata.distroId(),
                    metadata.distroVersion()
            );

            io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.RuntimeClass runtimeClass;
            try {
                runtimeClass = io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.RuntimeClass.valueOf(metadata.runtimeClass());
            } catch (Exception e) {
                runtimeClass = io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.RuntimeClass.FULL_LINUX;
            }

            InstanceLifecycle.Instance instance = InstanceLifecycle.Instance.restore(
                    instanceId,
                    distro,
                    targetRootVolume,
                    runtimeClass,
                    InstanceLifecycle.State.STOPPED,
                    io.github.nyzeep.orchestella.runtime.contract.VmHardwareConfig.defaultConfig(),
                    clock
            );

            store.save(instance);
            return instance;
        } finally {
            try {
                Files.deleteIfExists(tempVolume);
            } catch (IOException ignored) {
            }
        }
    }

    private static MessageDigest createSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
