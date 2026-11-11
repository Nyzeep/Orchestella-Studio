package io.github.nyzeep.orchestella;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The official-shape minirootfs fixture shared by the transport-wiring tests
 * ({@code RootfsTransportContractTest}, {@code RootfsDownloadResumeContractTest}):
 * a small tar built in memory with a minimal ustar writer plus gzip — never a
 * real rootfs. The shape mirrors the official Alpine minirootfs 3.22.2 aarch64
 * tarball first-hand on 2026-11-12 ({@code UmlKernelAssetsRootfsSelfCheckTest}
 * holds the same fixture for the L3 contract tests): {@code ./}-prefixed
 * names, all 17 FHS top-level directories as explicit directory entries,
 * {@code etc/alpine-release} = {@code 3.22.2}, {@code etc/hostname} =
 * {@code localhost}, no {@code .PKGINFO}, and the apk installed-db grammar in
 * {@code lib/apk/db/installed} — every implemented L3 stage passes on it.
 */
final class MiniRootfsFixture {

    /** The pinned upstream version the fixture's identity file declares. */
    static final String VERSION = "3.22.2";
    static final String HOSTNAME = "localhost";

    /** The apk installed-db grammar: single-char keys, blank-line stanza separators. */
    private static final String INSTALLED_DB = String.join("\n",
            "C:Q1alumIAzRIMQo4v1lHoHsUyY7cGk=",
            "P:alpine-baselayout",
            "V:3.7.0-r0",
            "A:aarch64",
            "S:8224",
            "T:Alpine base dir structure and init scripts",
            "",
            "P:alpine-keys",
            "V:2.5-r0",
            "T:Alpine Linux repository keys",
            "",
            "P:apk-tools",
            "V:2.14.9-r2",
            "T:Alpine Package Keeper - package management tools",
            "",
            "P:busybox",
            "V:1.37.0-r20",
            "T:Size optimized toolbox of many common UNIX utilities",
            "",
            "P:ca-certificates-bundle",
            "V:20250619-r0",
            "T:Pre-generated ca-certificates bundle",
            "",
            "P:musl",
            "V:1.2.5-r9",
            "T:the musl c library (libc) implementation",
            "");

    /** The 17 top-level directories the official tarball ships as explicit dir entries. */
    private static final List<String> OFFICIAL_TOP_LEVEL = List.of(
            "bin", "dev", "etc", "home", "lib", "media", "mnt", "opt", "proc",
            "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var");

    /** One fixture entry: tar name, typeflag, payload. */
    record Spec(String name, char type, byte[] content) {
        Spec(String name, char type, String text) {
            this(name, type, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private MiniRootfsFixture() {
    }

    /** The official-shape entries — every implemented L3 stage passes on the gzip of these. */
    static List<Spec> officialShape() {
        List<Spec> entries = new ArrayList<>();
        entries.add(new Spec("./", '5', ""));
        for (String top : OFFICIAL_TOP_LEVEL) {
            entries.add(new Spec("./" + top + "/", '5', ""));
        }
        entries.add(new Spec("./etc/alpine-release", '0', VERSION + "\n"));
        entries.add(new Spec("./etc/hostname", '0', HOSTNAME + "\n"));
        entries.add(new Spec("./lib/apk/db/installed", '0', INSTALLED_DB));
        entries.add(new Spec("./usr/bin/binary-helper", '0', "fixture-elf-bytes"));
        entries.add(new Spec("./bin/sh", '2', "/bin/busybox"));
        return entries;
    }

    /** The official-shape entries minus one top-level directory — L3 rejects it. */
    static List<Spec> officialShapeWithoutTopLevelDir(String topDir) {
        List<Spec> entries = new ArrayList<>(officialShape());
        entries.removeIf(spec -> spec.name().equals("./" + topDir + "/"));
        return entries;
    }

    /** The official-shape fixture, gzipped — a byte-for-byte valid downloadable artifact. */
    static byte[] gzipTar() throws IOException {
        return gzipTar(officialShape());
    }

    /** Any fixture entry list, gzipped. */
    static byte[] gzipTar(List<Spec> entries) throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream zipper = new java.util.zip.GZIPOutputStream(gz)) {
            zipper.write(tarBytes(entries));
        }
        return gz.toByteArray();
    }

    private static byte[] tarBytes(List<Spec> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Spec spec : entries) {
            byte[] header = new byte[512];
            putString(header, 0, 100, spec.name());
            // Links carry their target in the linkname field, not the data.
            boolean isLink = spec.type() == '1' || spec.type() == '2';
            long size = (spec.type() == '5' || isLink) ? 0L : spec.content().length;
            putOctal(header, 100, 8, spec.type() == '5' ? 0755 : 0644);
            putOctal(header, 108, 8, 0); // uid
            putOctal(header, 116, 8, 0); // gid
            putOctal(header, 124, 12, size);
            putOctal(header, 136, 12, 0); // mtime
            for (int i = 148; i < 156; i++) {
                header[i] = ' '; // the checksum field reads as spaces while summing
            }
            header[156] = (byte) spec.type();
            putString(header, 157, 100,
                    isLink ? new String(spec.content(), StandardCharsets.UTF_8) : "");
            putString(header, 257, 6, "ustar"); // POSIX ustar magic; version follows
            header[263] = '0';
            header[264] = '0';
            putString(header, 265, 32, "fixture");
            putString(header, 297, 32, "fixture");
            putOctal(header, 148, 8, checksum(header));
            out.write(header);
            // Only entries whose declared size is non-zero carry data blocks;
            // a link's target lives in the linkname field, not the data.
            if (size > 0) {
                out.write(spec.content());
                int pad = (512 - (spec.content().length % 512)) % 512;
                for (int i = 0; i < pad; i++) {
                    out.write(0);
                }
            }
        }
        out.write(new byte[1024]); // the two zero end blocks
        return out.toByteArray();
    }

    private static void putString(byte[] header, int offset, int maxLength, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxLength) {
            throw new IllegalArgumentException("fixture name longer than the header field");
        }
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private static void putOctal(byte[] header, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        int digits = length - 1; // one trailing NUL
        if (octal.length() > digits) {
            throw new IllegalArgumentException("fixture number does not fit the field");
        }
        for (int i = 0; i < digits; i++) {
            header[offset + i] = (byte) (i < digits - octal.length() ? '0' : octal.charAt(
                    i - (digits - octal.length())));
        }
        header[offset + length - 1] = 0;
    }

    private static long checksum(byte[] header) {
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? 0x20 : (header[i] & 0xFF);
        }
        return sum;
    }

    /** The SHA-256 of the given bytes as lowercase hex. */
    static String sha256Hex(byte[] bytes) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
