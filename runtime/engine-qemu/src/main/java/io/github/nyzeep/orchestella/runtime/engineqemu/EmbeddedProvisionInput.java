package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * Builds the embedded provisioning input segment appended to the packaged
 * initramfs for builders shipped without virtio-9p.
 *
 * <p>The segment is a deterministic, gzipped {@code newc}-format cpio archive
 * containing the verified rootfs tarball, the per-instance bridge token, and
 * the {@code .active} sentinel, under {@code opt/droidlayer/embedded-input/}.
 * The guest init falls back to that directory when the 9p mount is
 * unavailable and re-verifies the rootfs digest before installing.
 */
public final class EmbeddedProvisionInput {
    public static final String EMBEDDED_DIR = "opt/droidlayer/embedded-input";
    public static final String ACTIVE_SENTINEL = EMBEDDED_DIR + "/.active";

    /**
     * Hard ceiling for a credentials-only segment. The segment carries only small
     * metadata and credential files; exceeding this means a rootfs payload (or
     * another bulk artifact) leaked back into the fast path.
     */
    public static final long CREDENTIALS_ONLY_MAX_BYTES = 64 * 1024;

    private EmbeddedProvisionInput() {
    }

    /** Builds the gzipped cpio segment for one provisioning input set. */
    public static byte[] build(byte[] rootfsTarball, String rootfsFileName, String bridgeToken) {
        return build(rootfsTarball, rootfsFileName, bridgeToken, null);
    }

    /** Builds the gzipped cpio segment with optional per-instance random guest password (ADR-0010). */
    public static byte[] build(
            byte[] rootfsTarball, String rootfsFileName, String bridgeToken, String guestPassword) {
        Objects.requireNonNull(rootfsTarball, "rootfsTarball");
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(rootfsTarball)) {
            writeSegment(in, rootfsTarball.length, rootfsFileName, bridgeToken, guestPassword, gz);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        return gz.toByteArray();
    }

    /** Builds a segment using a SHA-256 pinned independently in the distro manifest. */
    public static byte[] build(
            byte[] rootfsTarball, String rootfsFileName, String bridgeToken,
            String guestPassword, String rootfsSha256) throws IOException {
        Objects.requireNonNull(rootfsTarball, "rootfsTarball");
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(rootfsTarball)) {
            writeSegment(in, rootfsTarball.length, rootfsFileName, bridgeToken, guestPassword,
                    null, null, null, null, false, null, null, null, rootfsSha256, gz);
        }
        return gz.toByteArray();
    }

    /** Streams the gzipped cpio segment to {@code targetOut} with O(1) memory overhead. */
    public static void writeSegment(
            java.io.InputStream rootfsStream,
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            OutputStream targetOut) throws IOException {
        writeSegment(rootfsStream, rootfsSize, rootfsFileName, bridgeToken, guestPassword, null, targetOut);
    }

    /** Streams the gzipped cpio segment with optional multi-distro guest init override. */
    public static void writeSegment(
            java.io.InputStream rootfsStream,
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            byte[] initScript,
            OutputStream targetOut) throws IOException {
        writeSegment(rootfsStream, rootfsSize, rootfsFileName, bridgeToken, guestPassword, initScript, null, targetOut);
    }

    /** Streams the gzipped cpio segment with optional multi-distro guest init override and proxy relay binary. */
    public static void writeSegment(
            java.io.InputStream rootfsStream,
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            byte[] initScript,
            byte[] proxyRelayBinary,
            OutputStream targetOut) throws IOException {
        writeSegment(rootfsStream, rootfsSize, rootfsFileName, bridgeToken, guestPassword, initScript, proxyRelayBinary, null, null, false, null, null, null, targetOut);
    }

    /** Streams the gzipped cpio segment with optional user identity, password, sudo and hostname. */
    public static void writeSegment(
            java.io.InputStream rootfsStream,
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            byte[] initScript,
            byte[] proxyRelayBinary,
            String hostname,
            String guestUser,
            boolean guestSudo,
            OutputStream targetOut) throws IOException {
        writeSegment(rootfsStream, rootfsSize, rootfsFileName, bridgeToken, guestPassword, initScript, proxyRelayBinary, hostname, guestUser, guestSudo, null, null, null, targetOut);
    }

    /** Streams the gzipped cpio segment with optional multi-distro guest init override, proxy relay binary, mirror config, and first-boot script. */
    public static void writeSegment(
            java.io.InputStream rootfsStream,
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            byte[] initScript,
            byte[] proxyRelayBinary,
            String mirrorConfigFileName,
            byte[] mirrorConfigFileContent,
            byte[] firstBootScript,
            OutputStream targetOut) throws IOException {
        writeSegment(rootfsStream, rootfsSize, rootfsFileName, bridgeToken, guestPassword, initScript, proxyRelayBinary, null, null, false, mirrorConfigFileName, mirrorConfigFileContent, firstBootScript, targetOut);
    }

    /** Master writeSegment method supporting credentials, identity, mirror config, and first-boot automation. */
    public static void writeSegment(
            java.io.InputStream rootfsStream,
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            byte[] initScript,
            byte[] proxyRelayBinary,
            String hostname,
            String guestUser,
            boolean guestSudo,
            String mirrorConfigFileName,
            byte[] mirrorConfigFileContent,
            byte[] firstBootScript,
            OutputStream targetOut) throws IOException {
        // Legacy callers without a manifest digest must fail closed before writing anything.
        writeSegment(rootfsStream, rootfsSize, rootfsFileName, bridgeToken, guestPassword,
                initScript, proxyRelayBinary, hostname, guestUser, guestSudo,
                mirrorConfigFileName, mirrorConfigFileContent, firstBootScript, null, targetOut);
    }

    /**
     * Streams inputs against the manifest's exact size and SHA-256. The caller must discard
     * partial output on failure and publish it only after this method returns successfully.
     */
    public static void writeSegment(
            java.io.InputStream rootfsStream,
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            byte[] initScript,
            byte[] proxyRelayBinary,
            String hostname,
            String guestUser,
            boolean guestSudo,
            String mirrorConfigFileName,
            byte[] mirrorConfigFileContent,
            byte[] firstBootScript,
            String rootfsSha256,
            OutputStream targetOut) throws IOException {
        Objects.requireNonNull(rootfsStream, "rootfsStream");
        Objects.requireNonNull(targetOut, "targetOut");
        validateInputs(rootfsSize, rootfsFileName, bridgeToken, mirrorConfigFileName, rootfsSha256);

        GZIPOutputStream gzip = new GZIPOutputStream(targetOut);
        Counting out = new Counting(gzip);
        writeHeaderEntries(out, rootfsSize, rootfsFileName, bridgeToken, guestPassword,
                hostname, guestUser, guestSudo, rootfsSha256);
        appendStream(out, EMBEDDED_DIR + "/" + rootfsFileName, rootfsStream, rootfsSize, rootfsSha256);
        writeTailEntries(out, initScript, proxyRelayBinary, mirrorConfigFileName,
                mirrorConfigFileContent, firstBootScript, rootfsFileName, rootfsSha256, rootfsSize);
        appendTrailer(out);
        gzip.finish();
        gzip.flush();
    }

    /**
     * Builds a credentials-only segment for an already provisioned instance.
     *
     * <p>It intentionally contains no rootfs tarball entry: the guest's
     * {@code provisioned-v1} path only refreshes credentials, identity, package
     * repositories and first-boot automation. The full segment remains the only
     * path that carries the distribution payload.
     */
    public static void writeCredentialsOnlySegment(
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            byte[] initScript,
            byte[] proxyRelayBinary,
            String hostname,
            String guestUser,
            boolean guestSudo,
            String mirrorConfigFileName,
            byte[] mirrorConfigFileContent,
            byte[] firstBootScript,
            String rootfsSha256,
            OutputStream targetOut) throws IOException {
        Objects.requireNonNull(targetOut, "targetOut");
        validateInputs(rootfsSize, rootfsFileName, bridgeToken, mirrorConfigFileName, rootfsSha256);

        GZIPOutputStream gzip = new GZIPOutputStream(targetOut);
        Counting out = new Counting(gzip);
        writeHeaderEntries(out, rootfsSize, rootfsFileName, bridgeToken, guestPassword,
                hostname, guestUser, guestSudo, rootfsSha256);
        writeTailEntries(out, initScript, proxyRelayBinary, mirrorConfigFileName,
                mirrorConfigFileContent, firstBootScript, rootfsFileName, rootfsSha256, rootfsSize);
        appendTrailer(out);
        gzip.finish();
        gzip.flush();
    }

    private static void validateInputs(
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String mirrorConfigFileName,
            String rootfsSha256) {
        validateToken(bridgeToken);
        validateInputFileName(rootfsFileName);
        if (rootfsSize <= 0 || rootfsSize > 0xffff_ffffL) {
            throw new IllegalArgumentException("invalid rootfs size");
        }
        if (rootfsSha256 == null || !rootfsSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("manifest rootfs SHA-256 required");
        }
        if (mirrorConfigFileName != null) {
            validateInputFileName(mirrorConfigFileName);
            if (mirrorConfigFileName.equals(rootfsFileName)) {
                throw new IllegalArgumentException("mirror config must not replace rootfs");
            }
        }
    }

    private static void writeHeaderEntries(
            Counting out,
            long rootfsSize,
            String rootfsFileName,
            String bridgeToken,
            String guestPassword,
            String hostname,
            String guestUser,
            boolean guestSudo,
            String rootfsSha256) throws IOException {
        appendDirectory(out, "opt");
        appendDirectory(out, "opt/droidlayer");
        appendDirectory(out, EMBEDDED_DIR);
        appendFile(out, ACTIVE_SENTINEL, new byte[0]);
        appendRootfsTrust(out, EMBEDDED_DIR, rootfsFileName, rootfsSha256, rootfsSize);
        appendFile(out, EMBEDDED_DIR + "/bridge.token",
                (bridgeToken + "\n").getBytes(StandardCharsets.US_ASCII));
        if (guestPassword != null && !guestPassword.trim().isEmpty()) {
            appendFile(out, EMBEDDED_DIR + "/guest.password",
                    (guestPassword.trim() + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        if (hostname != null && !hostname.trim().isEmpty()) {
            appendFile(out, EMBEDDED_DIR + "/hostname",
                    (hostname.trim() + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        if (guestUser != null && !guestUser.trim().isEmpty()) {
            appendFile(out, EMBEDDED_DIR + "/guest.user",
                    (guestUser.trim() + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        if (guestSudo) {
            appendFile(out, EMBEDDED_DIR + "/guest.sudo",
                    "1\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void writeTailEntries(
            Counting out,
            byte[] initScript,
            byte[] proxyRelayBinary,
            String mirrorConfigFileName,
            byte[] mirrorConfigFileContent,
            byte[] firstBootScript,
            String rootfsFileName,
            String rootfsSha256,
            long rootfsSize) throws IOException {
        if (mirrorConfigFileContent != null && mirrorConfigFileContent.length > 0 && mirrorConfigFileName != null) {
            appendFile(out, EMBEDDED_DIR + "/" + mirrorConfigFileName, mirrorConfigFileContent);
        }
        if (firstBootScript != null && firstBootScript.length > 0) {
            appendFile(out, EMBEDDED_DIR + "/firstboot.sh", 0100755, firstBootScript);
        }
        if (initScript != null && initScript.length > 0) {
            appendFile(out, "init", 0100755, initScript);
            appendDirectory(out, "etc");
            appendDirectory(out, "etc/droidlayer");
            appendRootfsTrust(out, "etc/droidlayer", rootfsFileName, rootfsSha256, rootfsSize);
        }
        if (proxyRelayBinary != null && proxyRelayBinary.length > 0) {
            appendDirectory(out, "opt/orchestella");
            appendFile(out, "opt/orchestella/proxy-relay", 0100755, proxyRelayBinary);
        }
    }

    private static void validateInputFileName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                || name.equals("rootfs.file") || name.equals("rootfs.sha256")
                || name.equals("rootfs.size") || name.equals("bridge.token")
                || name.startsWith("guest.") || name.equals("hostname")
                || name.equals("firstboot.sh")) {
            throw new IllegalArgumentException("input must be one non-reserved file name");
        }
    }

    private static void appendRootfsTrust(
            Counting out, String directory, String fileName, String sha256, long size)
            throws IOException {
        appendFile(out, directory + "/rootfs.file",
                (fileName + "\n").getBytes(StandardCharsets.US_ASCII));
        appendFile(out, directory + "/rootfs.sha256",
                (sha256 + "\n").getBytes(StandardCharsets.US_ASCII));
        appendFile(out, directory + "/rootfs.size",
                (Long.toString(size) + "\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static void validateToken(String token) {
        if (token == null || token.length() != 64) {
            throw new IllegalArgumentException("bridge token must be 64 characters");
        }
        for (char c : token.toCharArray()) {
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                throw new IllegalArgumentException("bridge token must be lowercase hex");
            }
        }
    }

    private static void appendDirectory(Counting out, String name) throws IOException {
        writeEntry(out, name, 040755, new byte[0]);
    }

    private static void appendFile(Counting out, String name, byte[] data) throws IOException {
        writeEntry(out, name, 0100644, data);
    }

    private static void appendFile(Counting out, String name, int mode, byte[] data) throws IOException {
        writeEntry(out, name, mode, data);
    }

    private static void writeEntryHeader(Counting out, String name, int mode, long filesize)
            throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        // newc: magic(6) + 13 octal fields as 8-char hex + name + data, all
        // 4-byte aligned. Fields: ino, mode, uid, gid, nlink, mtime, filesize,
        // devmajor, devminor, rdevmajor, rdevminor, namesize, check.
        out.writeAscii("070701");
        out.writeHex(1);                        // c_ino
        out.writeHex(mode);                     // c_mode
        out.writeHex(0);                        // c_uid
        out.writeHex(0);                        // c_gid
        out.writeHex(1);                        // c_nlink
        out.writeHex(0);                        // c_mtime (deterministic)
        out.writeHex(filesize);                 // c_filesize
        out.writeHex(0);                        // c_devmajor
        out.writeHex(0);                        // c_devminor
        out.writeHex(0);                        // c_rdevmajor
        out.writeHex(0);                        // c_rdevminor
        out.writeHex(nameBytes.length + 1);     // c_namesize (incl. NUL)
        out.writeHex(0);                        // c_check
        out.write(nameBytes);
        out.write(0);
        out.align(4);
    }

    private static void writeEntry(Counting out, String name, int mode, byte[] data)
            throws IOException {
        writeEntryHeader(out, name, mode, data.length);
        out.write(data);
        out.align(4);
    }

    private static void appendStream(
            Counting out, String name, java.io.InputStream in, long size, String expectedSha256)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        writeEntryHeader(out, name, 0100644, size);
        byte[] buf = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, toRead);
            if (read < 0) {
                throw new IOException("rootfs-size-mismatch");
            }
            if (read == 0) {
                int next = in.read();
                if (next < 0) {
                    throw new IOException("rootfs-size-mismatch");
                }
                buf[0] = (byte) next;
                read = 1;
            }
            digest.update(buf, 0, read);
            out.write(buf, 0, read);
            remaining -= read;
        }
        if (in.read() != -1) {
            throw new IOException("rootfs-size-mismatch");
        }
        byte[] expected = new byte[32];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) Integer.parseInt(expectedSha256.substring(i * 2, i * 2 + 2), 16);
        }
        if (!MessageDigest.isEqual(expected, digest.digest())) {
            throw new IOException("rootfs-checksum-mismatch");
        }
        out.align(4);
    }

    private static void appendTrailer(Counting out) throws IOException {
        writeEntry(out, "TRAILER!!!", 0, new byte[0]);
        out.align(512);
    }

    /** Output stream that tracks its byte count for cpio alignment. */
    private static final class Counting extends OutputStream {
        private final OutputStream delegate;
        private long count;

        Counting(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b) throws IOException {
            delegate.write(b);
            count += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        void writeAscii(String text) throws IOException {
            write(text.getBytes(StandardCharsets.US_ASCII));
        }

        void writeHex(long value) throws IOException {
            writeAscii(String.format("%08x", value));
        }

        void align(int boundary) throws IOException {
            while (count % boundary != 0) {
                write(0);
            }
        }
    }
}
