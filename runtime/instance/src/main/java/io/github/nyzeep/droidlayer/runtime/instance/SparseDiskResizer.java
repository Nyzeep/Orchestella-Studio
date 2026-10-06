package io.github.nyzeep.droidlayer.runtime.instance;

import io.github.nyzeep.droidlayer.runtime.contract.StorageContract;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Fast, safe sparse disk expansion engine for Full Linux instances (Ticket 50 / ADR-0007 / ADR-0019).
 *
 * <p>Enforces strict safety boundaries:
 * <ul>
 *   <li>Only allows expansion when the instance is in the {@link InstanceLifecycle.State#STOPPED} state.</li>
 *   <li>Strictly forbids disk shrinking or equal-size operations, throwing
 *       {@link IllegalArgumentException} with "Disk shrinking is not supported".</li>
 *   <li>Preflights available host storage space on the hosting filesystem partition to prevent ENOSPC.</li>
 *   <li>Leverages Java NIO {@link FileChannel#position(long)} to create a sparse hole without allocating
 *       physical blocks for the expanded space, preserving sparse file efficiency on ext4/f2fs.</li>
 * </ul>
 */
public final class SparseDiskResizer {

    private SparseDiskResizer() {}

    /**
     * Resizes a raw disk image file at {@code diskPath} to {@code targetSizeBytes}.
     *
     * @param diskPath path to the disk image file
     * @param state lifecycle state of the instance owning this disk
     * @param targetSizeBytes target logical size in bytes (must be strictly greater than current size)
     * @param spaceChecker space checker for inspecting host storage partition free space
     * @throws IOException on I/O failure or if host storage space is insufficient
     * @throws IllegalStateException if instance is not in {@link InstanceLifecycle.State#STOPPED} state
     * @throws IllegalArgumentException if target size is less than or equal to current size, or exceeds maximum
     */
    public static void resize(
            Path diskPath,
            InstanceLifecycle.State state,
            long targetSizeBytes,
            StorageContract.StorageSpaceChecker spaceChecker) throws IOException {
        Objects.requireNonNull(diskPath, "diskPath");
        Objects.requireNonNull(state, "state");
        StorageContract.StorageSpaceChecker checker = spaceChecker != null
                ? spaceChecker
                : new StorageContract.DefaultStorageSpaceChecker();

        if (state != InstanceLifecycle.State.STOPPED) {
            throw new IllegalStateException(
                    "Cannot resize disk unless instance is STOPPED (current state: " + state + ")");
        }

        if (!Files.isRegularFile(diskPath)) {
            throw new NoSuchFileException(diskPath.toString());
        }

        long currentSizeBytes = Files.size(diskPath);
        if (targetSizeBytes <= currentSizeBytes) {
            throw new IllegalArgumentException("Disk shrinking is not supported");
        }

        if (targetSizeBytes > StorageContract.MAX_ROOT_VOLUME_BYTES) {
            throw new IllegalArgumentException(
                    "target size (" + targetSizeBytes + " B) exceeds maximum supported limit ("
                            + StorageContract.MAX_ROOT_VOLUME_BYTES + " B)");
        }

        long deltaBytes = targetSizeBytes - currentSizeBytes;
        long usableSpace = checker.getUsableSpace(diskPath);
        if (usableSpace < deltaBytes) {
            throw new StorageContract.InsufficientStorageException(diskPath, deltaBytes, usableSpace);
        }

        // Fast sparse expansion: position to (targetSizeBytes - 1) and write a single 0x00 byte.
        // On Linux ext4/f2fs filesystems, this creates a sparse hole without physical block allocation.
        try (FileChannel channel = FileChannel.open(diskPath, StandardOpenOption.WRITE)) {
            channel.position(targetSizeBytes - 1);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
    }

    /**
     * Resizes a raw disk image file using default host storage space checking.
     */
    public static void resize(
            Path diskPath,
            InstanceLifecycle.State state,
            long targetSizeBytes) throws IOException {
        resize(diskPath, state, targetSizeBytes, new StorageContract.DefaultStorageSpaceChecker());
    }

    /**
     * Resizes the root volume of an {@link InstanceLifecycle.Instance}.
     */
    public static void resize(
            InstanceLifecycle.Instance instance,
            long targetSizeBytes,
            StorageContract.StorageSpaceChecker spaceChecker) throws IOException {
        Objects.requireNonNull(instance, "instance");
        resize(instance.rootVolume(), instance.state(), targetSizeBytes, spaceChecker);
    }

    /**
     * Resizes the root volume of an {@link InstanceLifecycle.Instance} using default host space checking.
     */
    public static void resize(
            InstanceLifecycle.Instance instance,
            long targetSizeBytes) throws IOException {
        Objects.requireNonNull(instance, "instance");
        resize(instance.rootVolume(), instance.state(), targetSizeBytes, new StorageContract.DefaultStorageSpaceChecker());
    }
}
