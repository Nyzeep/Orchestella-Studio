package io.github.nyzeep.orchestella.runtime.engineuml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Safely extracts whitelisted tools from a gzipped SVR4 newc ({@code 070701}) cpio
 * initramfs archive (such as {@code initramfs.cpio.gz}) without spawning host shell tools.
 */
public final class CpioNewcToolExtractor {
    private static final String NEWC_MAGIC = "070701";
    private static final int HEADER_BYTES = 110;
    private static final int MAX_NAME_BYTES = 4096;
    private static final long MAX_ENTRY_BYTES = 32L * 1024L * 1024L;

    private CpioNewcToolExtractor() {}

    /**
     * Extracts {@code requiredEntries} from {@code gzippedCpio} into {@code destinationRoot},
     * marking {@code executableEntries} readable/executable for all users. Fails closed if any
     * entry path escapes {@code destinationRoot} or if any required entry is absent.
     */
    public static void extractSelected(
            InputStream gzippedCpio,
            Path destinationRoot,
            Set<String> requiredEntries,
            Set<String> executableEntries)
            throws IOException {
        Objects.requireNonNull(gzippedCpio, "gzippedCpio");
        Objects.requireNonNull(destinationRoot, "destinationRoot");
        Objects.requireNonNull(requiredEntries, "requiredEntries");
        Objects.requireNonNull(executableEntries, "executableEntries");
        if (requiredEntries.isEmpty()) {
            throw new IllegalArgumentException("requiredEntries must not be empty");
        }

        Path normalizedRoot = destinationRoot.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);
        Set<String> remaining = new HashSet<>(requiredEntries);

        try (GZIPInputStream in = new GZIPInputStream(gzippedCpio, 64 * 1024)) {
            byte[] header = new byte[HEADER_BYTES];
            byte[] buffer = new byte[64 * 1024];
            while (readFully(in, header, HEADER_BYTES)) {
                String magic = new String(header, 0, 6, StandardCharsets.US_ASCII);
                if (!NEWC_MAGIC.equals(magic)) {
                    throw new IOException("invalid cpio newc magic: " + magic);
                }
                long fileSize = parseHexField(header, 54);
                int nameSize = Math.toIntExact(parseHexField(header, 94));
                if (nameSize <= 0 || nameSize > MAX_NAME_BYTES) {
                    throw new IOException("invalid cpio entry name size: " + nameSize);
                }
                if (fileSize < 0 || fileSize > MAX_ENTRY_BYTES) {
                    throw new IOException("oversized cpio entry payload: " + fileSize);
                }

                byte[] nameBytes = new byte[nameSize];
                if (!readFully(in, nameBytes, nameSize)) {
                    throw new IOException("truncated cpio entry name");
                }
                skipPadding(in, HEADER_BYTES + nameSize, buffer);

                int nameLen = nameBytes.length;
                if (nameLen > 0 && nameBytes[nameLen - 1] == 0) {
                    nameLen--;
                }
                String rawName = new String(nameBytes, 0, nameLen, StandardCharsets.UTF_8);
                if ("TRAILER!!!".equals(rawName)) {
                    break;
                }

                String cleanName = sanitizeRelativeEntryPath(rawName);
                if (remaining.contains(cleanName)) {
                    Path target = normalizedRoot.resolve(cleanName).normalize();
                    if (!target.startsWith(normalizedRoot) || target.equals(normalizedRoot)) {
                        throw new IOException("unsafe cpio output path: " + rawName);
                    }
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(
                            target,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                        copyExact(in, out, fileSize, buffer);
                    }
                    boolean executable = executableEntries.contains(cleanName);
                    applyPermissions(target, executable);
                    remaining.remove(cleanName);
                } else {
                    skipExact(in, fileSize, buffer);
                }
                skipPadding(in, fileSize, buffer);
            }
        }

        if (!remaining.isEmpty()) {
            throw new IOException("missing required cpio entries: " + remaining);
        }
    }

    private static String sanitizeRelativeEntryPath(String raw) throws IOException {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0 || raw.indexOf('\\') >= 0) {
            throw new IOException("unsafe cpio entry name: " + raw);
        }
        String trimmed = raw;
        while (trimmed.startsWith("./")) {
            trimmed = trimmed.substring(2);
        }
        if (trimmed.startsWith("/")) {
            throw new IOException("unsafe absolute cpio entry name: " + raw);
        }
        for (String segment : trimmed.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new IOException("unsafe traversal cpio entry name: " + raw);
            }
        }
        return trimmed;
    }

    private static void applyPermissions(Path target, boolean executable) {
        if (executable) {
            target.toFile().setReadable(true, false);
            target.toFile().setExecutable(true, false);
        } else {
            target.toFile().setReadable(true, false);
        }
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ);
            if (executable) {
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
            }
            Files.setPosixFilePermissions(target, perms);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static long parseHexField(byte[] header, int offset) throws IOException {
        String hex = new String(header, offset, 8, StandardCharsets.US_ASCII);
        try {
            return Long.parseUnsignedLong(hex, 16);
        } catch (NumberFormatException bad) {
            throw new IOException("invalid cpio hex header field: " + hex, bad);
        }
    }

    private static boolean readFully(InputStream in, byte[] dst, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(dst, off, len - off);
            if (n < 0) {
                if (off == 0) {
                    return false;
                }
                throw new IOException("truncated cpio stream");
            }
            off += n;
        }
        return true;
    }

    private static void copyExact(InputStream in, OutputStream out, long size, byte[] buffer)
            throws IOException {
        long rem = size;
        while (rem > 0L) {
            int n = in.read(buffer, 0, (int) Math.min(rem, buffer.length));
            if (n < 0) {
                throw new IOException("truncated cpio entry payload");
            }
            out.write(buffer, 0, n);
            rem -= n;
        }
    }

    private static void skipExact(InputStream in, long size, byte[] buffer) throws IOException {
        long rem = size;
        while (rem > 0L) {
            int n = in.read(buffer, 0, (int) Math.min(rem, buffer.length));
            if (n < 0) {
                throw new IOException("truncated cpio skipped payload");
            }
            rem -= n;
        }
    }

    private static void skipPadding(InputStream in, long byteCount, byte[] buffer)
            throws IOException {
        long pad = (4L - (byteCount % 4L)) % 4L;
        if (pad > 0L) {
            skipExact(in, pad, buffer);
        }
    }
}
