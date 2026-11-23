package io.github.nyzeep.orchestella.runtime.engineuml;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Pure read-only inspector for per-instance raw root-volume disk slots (ADR-0007).
 *
 * <p>Distinguishes absent/empty placeholder slots (safe for initial ext4 provisioning),
 * formatted ext4 volumes (superblock magic {@code 0xEF53} at offset {@code 0x438}),
 * container format conflicts (QCOW2/VMDK/directory), and unrecognized non-empty data
 * (never silently overwritten).
 */
public final class UmlVolumeInspector {
    public static final int EXT4_SUPERBLOCK_MAGIC_OFFSET = 0x438;
    public static final long MIN_EXT4_VOLUME_BYTES = 2048L;
    private static final byte[] CREATION_SIGNATURE_BYTES =
            "ORCHESTELLA_EMPTY_ROOT_VOLUME_V2\n".getBytes(StandardCharsets.US_ASCII);
    private static final int PROBE_WINDOW_BYTES = 4096;

    public enum VolumeState {
        ABSENT,
        EMPTY_OR_PLACEHOLDER,
        EXT4_READY,
        FORMAT_CONFLICT,
        UNRECOGNIZED_NON_EMPTY
    }

    public record VolumeInspection(VolumeState state, long sizeBytes, String detail) {
        public VolumeInspection {
            Objects.requireNonNull(state, "state");
            detail = detail == null ? "" : detail;
        }

        public boolean isReadyToProvision() {
            return state == VolumeState.ABSENT || state == VolumeState.EMPTY_OR_PLACEHOLDER;
        }

        public boolean isExt4Ready() {
            return state == VolumeState.EXT4_READY;
        }
    }

    private UmlVolumeInspector() {}

    /** Inspects {@code rootVolume} without mutating any file or metadata. */
    public static VolumeInspection inspect(Path rootVolume) {
        if (rootVolume == null) {
            return new VolumeInspection(VolumeState.FORMAT_CONFLICT, 0L, "root volume path is null");
        }
        if (!Files.exists(rootVolume, LinkOption.NOFOLLOW_LINKS)) {
            return new VolumeInspection(VolumeState.ABSENT, 0L, "root volume absent");
        }
        if (Files.isSymbolicLink(rootVolume)) {
            return new VolumeInspection(
                    VolumeState.FORMAT_CONFLICT, 0L, "root volume path must not be a symlink");
        }
        ExecutionPolicyContract.PreflightVerdict formatConflict =
                ExecutionPolicyContract.detectVolumeFormatConflict(
                        rootVolume,
                        ExecutionPolicyContract.VolumeFormatDeclaration.of(
                                StorageContract.RootVolumeFormat.RAW_SPARSE));
        if (formatConflict != null) {
            return new VolumeInspection(
                    VolumeState.FORMAT_CONFLICT, 0L, formatConflict.detail());
        }
        if (!Files.isRegularFile(rootVolume, LinkOption.NOFOLLOW_LINKS)) {
            return new VolumeInspection(
                    VolumeState.FORMAT_CONFLICT, 0L, "root volume is not a regular file");
        }

        try {
            long size = Files.size(rootVolume);
            if (size == 0L) {
                return new VolumeInspection(
                        VolumeState.EMPTY_OR_PLACEHOLDER, 0L, "zero-byte sparse slot");
            }
            byte[] head = readHead(rootVolume, PROBE_WINDOW_BYTES);
            if (hasExt4SuperblockMagic(size, head)) {
                return new VolumeInspection(
                        VolumeState.EXT4_READY, size, "valid ext2/3/4 superblock magic");
            }
            if (isAllZeroOrCreationPlaceholder(head)) {
                return new VolumeInspection(
                        VolumeState.EMPTY_OR_PLACEHOLDER,
                        size,
                        "empty or creation-signature sparse placeholder");
            }
            return new VolumeInspection(
                    VolumeState.UNRECOGNIZED_NON_EMPTY,
                    size,
                    "non-empty volume lacks ext4 superblock magic at 0x438");
        } catch (IOException unreadable) {
            return new VolumeInspection(
                    VolumeState.FORMAT_CONFLICT,
                    0L,
                    "unable to read root volume: " + unreadable.getMessage());
        }
    }

    /** Fails closed unless {@code rootVolume} is a regular raw file bearing ext4 magic {@code 0xEF53}. */
    public static void requireValidExt4Superblock(Path rootVolume) throws IOException {
        VolumeInspection inspection = inspect(rootVolume);
        if (inspection.state() != VolumeState.EXT4_READY) {
            throw new IOException(
                    "invalid ext4 root volume (" + inspection.state() + "): " + inspection.detail());
        }
    }

    private static byte[] readHead(Path path, int maxBytes) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            int toRead = (int) Math.min(maxBytes, channel.size());
            ByteBuffer buf = ByteBuffer.allocate(toRead);
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) {
                    break;
                }
            }
            byte[] out = new byte[buf.position()];
            buf.flip();
            buf.get(out);
            return out;
        }
    }

    private static boolean hasExt4SuperblockMagic(long totalSize, byte[] head) {
        if (totalSize < MIN_EXT4_VOLUME_BYTES
                || head.length <= EXT4_SUPERBLOCK_MAGIC_OFFSET + 1) {
            return false;
        }
        return (head[EXT4_SUPERBLOCK_MAGIC_OFFSET] & 0xFF) == 0x53
                && (head[EXT4_SUPERBLOCK_MAGIC_OFFSET + 1] & 0xFF) == 0xEF;
    }

    private static boolean isAllZeroOrCreationPlaceholder(byte[] head) {
        int start = 0;
        if (startsWith(head, CREATION_SIGNATURE_BYTES)) {
            start = CREATION_SIGNATURE_BYTES.length;
        }
        for (int i = start; i < head.length; i++) {
            if (head[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
