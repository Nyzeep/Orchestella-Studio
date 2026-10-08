package io.github.nyzeep.droidlayer.runtime.engineqemu;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
        Objects.requireNonNull(rootfsStream, "rootfsStream");
        Objects.requireNonNull(rootfsFileName, "rootfsFileName");
        Objects.requireNonNull(targetOut, "targetOut");
        validateToken(bridgeToken);
        if (rootfsSize <= 0 || rootfsFileName.contains("/")) {
            throw new IllegalArgumentException("invalid rootfs input");
        }

        GZIPOutputStream gzip = new GZIPOutputStream(targetOut);
        Counting out = new Counting(gzip);
        appendDirectory(out, "opt");
        appendDirectory(out, "opt/droidlayer");
        appendDirectory(out, EMBEDDED_DIR);
        appendFile(out, ACTIVE_SENTINEL, new byte[0]);
        appendFile(out, EMBEDDED_DIR + "/bridge.token",
                (bridgeToken + "\n").getBytes(StandardCharsets.US_ASCII));
        if (guestPassword != null && !guestPassword.trim().isEmpty()) {
            appendFile(out, EMBEDDED_DIR + "/guest.password",
                    (guestPassword.trim() + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        appendStream(out, EMBEDDED_DIR + "/" + rootfsFileName, rootfsStream, rootfsSize);

        if (initScript != null && initScript.length > 0) {
            appendFile(out, "init", 0100755, initScript);
            appendDirectory(out, "etc");
            appendDirectory(out, "etc/droidlayer");
            appendFile(out, "etc/droidlayer/rootfs.file", 0100644,
                    (rootfsFileName + "\n").getBytes(StandardCharsets.US_ASCII));
            appendFile(out, "etc/droidlayer/rootfs.sha256", 0100644, new byte[0]);
        }

        appendTrailer(out);
        gzip.finish();
        gzip.flush();
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

    private static void appendStream(Counting out, String name, java.io.InputStream in, long size)
            throws IOException {
        writeEntryHeader(out, name, 0100644, size);
        byte[] buf = new byte[65536];
        long remaining = size;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, toRead);
            if (read < 0) {
                throw new IOException("unexpected EOF reading rootfs stream, expected " + size + " bytes");
            }
            out.write(buf, 0, read);
            remaining -= read;
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
