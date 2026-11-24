package io.github.nyzeep.orchestella;

import io.github.nyzeep.orchestella.runtime.contract.ExecutionPolicyContract;
import io.github.nyzeep.orchestella.runtime.contract.StorageContract;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlVolumeInspector;
import io.github.nyzeep.orchestella.runtime.instance.InstanceLifecycle;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Pure-JVM read-only disk-write and cross-strategy format compatibility verifier
 * for atomic UML -> QEMU fallback (W3-05 / R-05).
 *
 * <p>Captures a pre-launch snapshot of the instance root volume and provisioning
 * evidence, then verifies after a failed launch whether the user disk remained
 * completely unwritten and whether its format is verified compatible with the
 * fallback strategy. Any uncertainty fails closed ({@code false}).
 */
public final class UmlDiskWriteProbe {
    private static final long MAX_FULL_HASH_BYTES = 64L * 1024L * 1024L;
    private static final int HASH_BUFFER_BYTES = 64 * 1024;

    private UmlDiskWriteProbe() {}

    public record DiskSnapshot(
            Path rootVolume,
            UmlVolumeInspector.VolumeState state,
            long sizeBytes,
            long lastModifiedMillis,
            String contentSha256,
            boolean fullHashVerified,
            boolean hadProvisioningEvidence) {
        public DiskSnapshot {
            Objects.requireNonNull(state, "state");
            contentSha256 = contentSha256 == null ? "" : contentSha256;
        }
    }

    /** Captures the pre-launch state of {@code rootVolume} without mutating any file. */
    public static DiskSnapshot captureBeforeLaunch(
            Path rootVolume, InstanceLifecycle.ProvisioningEvidence evidence) {
        UmlVolumeInspector.VolumeInspection inspection = UmlVolumeInspector.inspect(rootVolume);
        long mtime = -1L;
        String contentHash = "";
        boolean fullHashVerified = false;
        if (inspection.state() == UmlVolumeInspector.VolumeState.EMPTY_OR_PLACEHOLDER
                && rootVolume != null
                && Files.isRegularFile(rootVolume, LinkOption.NOFOLLOW_LINKS)) {
            try {
                mtime = Files.getLastModifiedTime(rootVolume, LinkOption.NOFOLLOW_LINKS).toMillis();
                if (inspection.sizeBytes() <= MAX_FULL_HASH_BYTES) {
                    contentHash = sha256File(rootVolume);
                    fullHashVerified = true;
                }
            } catch (IOException unreadable) {
                return new DiskSnapshot(
                        rootVolume,
                        UmlVolumeInspector.VolumeState.FORMAT_CONFLICT,
                        -1L,
                        -1L,
                        "",
                        false,
                        evidence != null);
            }
        }
        return new DiskSnapshot(
                rootVolume,
                inspection.state(),
                inspection.sizeBytes(),
                mtime,
                contentHash,
                fullHashVerified,
                evidence != null);
    }

    /**
     * Returns {@code true} only when positive read-only evidence proves the user
     * disk has never been provisioned or written to before or during the failed launch.
     */
    public static boolean isUserDiskUnwritten(
            DiskSnapshot before,
            Path rootVolume,
            InstanceLifecycle.ProvisioningEvidence afterEvidence) {
        if (before == null || before.hadProvisioningEvidence() || afterEvidence != null) {
            return false;
        }
        if (!before.state().equals(UmlVolumeInspector.VolumeState.ABSENT)
                && !before.state().equals(UmlVolumeInspector.VolumeState.EMPTY_OR_PLACEHOLDER)) {
            return false;
        }
        UmlVolumeInspector.VolumeInspection after = UmlVolumeInspector.inspect(rootVolume);
        if (!after.isReadyToProvision()) {
            return false;
        }
        if (after.state() != before.state() || after.sizeBytes() != before.sizeBytes()) {
            return false;
        }
        if (after.state() == UmlVolumeInspector.VolumeState.ABSENT) {
            return true;
        }
        if (rootVolume == null || !Files.isRegularFile(rootVolume, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            long afterMtime =
                    Files.getLastModifiedTime(rootVolume, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (before.lastModifiedMillis() < 0L || afterMtime != before.lastModifiedMillis()) {
                return false;
            }
            if (!before.fullHashVerified()) {
                // Multi-MiB sparse placeholder without full SHA-256 already requires exact state,
                // size, and mtime equality above.
                return true;
            }
            String afterHash = sha256File(rootVolume);
            return !before.contentSha256().isEmpty() && before.contentSha256().equals(afterHash);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Verifies both raw-sparse format compatibility (ADR-0007) and whether the target
     * fallback strategy (QEMU) actually supports the instance's distribution manifest.
     */
    public static boolean isVolumeFormatCompatibleForQemuFallback(
            Path rootVolume,
            ExecutionPolicyContract.VolumeFormatDeclaration declaredFormat,
            boolean qemuSupportsDistro) {
        if (!qemuSupportsDistro) {
            return false;
        }
        if (ExecutionPolicyContract.detectVolumeFormatConflict(rootVolume, declaredFormat) != null) {
            return false;
        }
        if (declaredFormat != null
                && declaredFormat.isKnown()
                && declaredFormat.format() != StorageContract.RootVolumeFormat.RAW_SPARSE) {
            return false;
        }
        UmlVolumeInspector.VolumeInspection inspection = UmlVolumeInspector.inspect(rootVolume);
        return inspection.state() != UmlVolumeInspector.VolumeState.FORMAT_CONFLICT
                && inspection.state() != UmlVolumeInspector.VolumeState.UNRECOGNIZED_NON_EMPTY;
    }

    private static String sha256File(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.allocate(HASH_BUFFER_BYTES);
                while (true) {
                    buf.clear();
                    int read = channel.read(buf);
                    if (read <= 0) {
                        break;
                    }
                    buf.flip();
                    digest.update(buf);
                }
            }
            return StorageContract.bytesToHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
