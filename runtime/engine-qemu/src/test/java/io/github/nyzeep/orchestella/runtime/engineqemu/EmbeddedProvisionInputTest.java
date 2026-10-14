package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Contract tests for manifest-verified, gzipped newc provisioning inputs. */
public final class EmbeddedProvisionInputTest {
    private static final String TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ROOTFS_NAME = "alpine-minirootfs-3.24.1-aarch64.tar.gz";

    public static void main(String[] args) throws Exception {
        byte[] rootfs = synthetic(4096, 0x11);
        String pinnedSha256 = sha256(rootfs);
        byte[] segment = EmbeddedProvisionInput.build(rootfs, ROOTFS_NAME, TOKEN, null, pinnedSha256);
        check(Arrays.equals(segment,
                EmbeddedProvisionInput.build(rootfs, ROOTFS_NAME, TOKEN, null, pinnedSha256)),
                "identical trusted inputs must produce deterministic output");
        check(Arrays.equals(segment, write(rootfs, rootfs.length, ROOTFS_NAME, pinnedSha256,
                TOKEN, null, null, null, null, false, null, null, null)),
                "streaming and byte-array builders must agree");
        check((segment[0] & 0xff) == 0x1f && (segment[1] & 0xff) == 0x8b, "gzip wrapper");

        Map<String, byte[]> entries = entries(segment);
        assertTrust(entries, EmbeddedProvisionInput.EMBEDDED_DIR, pinnedSha256, rootfs.length);
        check(entries.containsKey(EmbeddedProvisionInput.ACTIVE_SENTINEL), "active sentinel");
        assertEntry(entries, EmbeddedProvisionInput.EMBEDDED_DIR + "/bridge.token", TOKEN + "\n");
        check(Arrays.equals(rootfs, entries.get(EmbeddedProvisionInput.EMBEDDED_DIR + "/" + ROOTFS_NAME)),
                "rootfs bytes must survive streaming");
        java.util.List<String> names = new java.util.ArrayList<>(entries.keySet());
        check(names.indexOf("opt") < names.indexOf("opt/droidlayer")
                && names.indexOf("opt/droidlayer") < names.indexOf(EmbeddedProvisionInput.EMBEDDED_DIR)
                && names.indexOf(EmbeddedProvisionInput.EMBEDDED_DIR)
                        < names.indexOf(EmbeddedProvisionInput.ACTIVE_SENTINEL),
                "parent directories must precede nested members");

        byte[] init = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.US_ASCII);
        byte[] mirror = "https://example.invalid/alpine/main\n".getBytes(StandardCharsets.US_ASCII);
        byte[] firstboot = "#!/bin/sh\ntrue\n".getBytes(StandardCharsets.US_ASCII);
        Map<String, byte[]> configured = entries(write(rootfs, rootfs.length, ROOTFS_NAME, pinnedSha256,
                TOKEN, "SyntheticPassword24", init, "orchestella-host", "devuser", true,
                "repositories", mirror, firstboot));
        assertTrust(configured, EmbeddedProvisionInput.EMBEDDED_DIR, pinnedSha256, rootfs.length);
        assertTrust(configured, "etc/droidlayer", pinnedSha256, rootfs.length);
        assertEntry(configured, EmbeddedProvisionInput.EMBEDDED_DIR + "/guest.password", "SyntheticPassword24\n");
        assertEntry(configured, EmbeddedProvisionInput.EMBEDDED_DIR + "/guest.user", "devuser\n");
        assertEntry(configured, EmbeddedProvisionInput.EMBEDDED_DIR + "/hostname", "orchestella-host\n");
        assertEntry(configured, EmbeddedProvisionInput.EMBEDDED_DIR + "/guest.sudo", "1\n");
        check(Arrays.equals(init, configured.get("init")), "guest init override");
        check(Arrays.equals(mirror, configured.get(EmbeddedProvisionInput.EMBEDDED_DIR + "/repositories")),
                "mirror configuration");
        check(Arrays.equals(firstboot, configured.get(EmbeddedProvisionInput.EMBEDDED_DIR + "/firstboot.sh")),
                "first-boot script");
        Map<String, byte[]> unknownPassword = entries(write(rootfs, rootfs.length, ROOTFS_NAME, pinnedSha256,
                TOKEN, "", init, null, null, false, null, null, null));
        check(!unknownPassword.containsKey(EmbeddedProvisionInput.EMBEDDED_DIR + "/guest.password"),
                "unknown legacy password must not be provisioned");

        for (String bad : new String[] {"short", "a".repeat(63), "a".repeat(63) + "Z"}) {
            rejectArgument(() -> EmbeddedProvisionInput.build(rootfs, ROOTFS_NAME, bad, null, pinnedSha256));
        }
        for (String bad : new String[] {null, "", "a".repeat(63), "A".repeat(64), pinnedSha256 + "\n"}) {
            rejectArgument(() -> EmbeddedProvisionInput.build(rootfs, ROOTFS_NAME, TOKEN, null, bad));
        }
        rejectArgument(() -> EmbeddedProvisionInput.build(rootfs, ROOTFS_NAME, TOKEN));
        for (String bad : new String[] {"../rootfs.tar.gz", "..\\rootfs.tar.gz", "/rootfs.tar.gz", ".", "..",
                "", "rootfs\n.tar.gz", "rootfs\u0000.tar.gz", "C:rootfs.tar.gz", "rootfs.sha256", "bridge.token"}) {
            rejectArgument(() -> EmbeddedProvisionInput.build(rootfs, bad, TOKEN, null, pinnedSha256));
        }
        for (String badMirror : new String[] {"../repositories", "..\\repositories", "rootfs.file", "rootfs.sha256",
                "rootfs.size", "guest.password", ".active", ROOTFS_NAME}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            rejectArgument(() -> EmbeddedProvisionInput.writeSegment(new ByteArrayInputStream(rootfs),
                    rootfs.length, ROOTFS_NAME, TOKEN, null, init, null, null, null, false,
                    badMirror, mirror, null, pinnedSha256, output));
            check(output.size() == 0, "invalid archive paths must fail before output");
        }
        for (long badSize : new long[] {0, -1, 0x1_0000_0000L}) {
            rejectArgument(() -> write(rootfs, badSize, ROOTFS_NAME, pinnedSha256, TOKEN,
                    null, null, null, null, false, null, null, null));
        }
        rejectInput("rootfs-size-mismatch", () -> write(rootfs, rootfs.length - 1, ROOTFS_NAME, pinnedSha256,
                TOKEN, null, init, null, null, false, null, null, null));
        rejectInput("rootfs-size-mismatch", () -> write(rootfs, rootfs.length + 1, ROOTFS_NAME, pinnedSha256,
                TOKEN, null, init, null, null, false, null, null, null));
        byte[] altered = rootfs.clone();
        altered[altered.length / 2] ^= 1;
        rejectInput("rootfs-checksum-mismatch", () -> write(altered, rootfs.length, ROOTFS_NAME, pinnedSha256,
                TOKEN, null, init, null, null, false, null, null, null));
        rejectInput("rootfs-checksum-mismatch", () -> write(rootfs, rootfs.length, ROOTFS_NAME, "0".repeat(64),
                TOKEN, null, init, null, null, false, null, null, null));
        System.out.println("EMBEDDED_PROVISION_INPUT_TEST_OK trust_records=2 deterministic=1 size_hash_rejection=4 path_rejection=1 unknown_password=1");
    }

    private static byte[] write(byte[] rootfs, long expectedSize, String name, String expectedSha256,
            String token, String password, byte[] init, String hostname, String user, boolean sudo,
            String mirrorName, byte[] mirror, byte[] firstboot) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ByteArrayInputStream in = new ByteArrayInputStream(rootfs)) {
            EmbeddedProvisionInput.writeSegment(in, expectedSize, name, token, password, init, null,
                    hostname, user, sudo, mirrorName, mirror, firstboot, expectedSha256, output);
        }
        return output.toByteArray();
    }

    private static void assertTrust(Map<String, byte[]> entries, String directory, String sha256, long size) {
        assertEntry(entries, directory + "/rootfs.file", ROOTFS_NAME + "\n");
        assertEntry(entries, directory + "/rootfs.sha256", sha256 + "\n");
        assertEntry(entries, directory + "/rootfs.size", Long.toString(size) + "\n");
    }

    private static void assertEntry(Map<String, byte[]> entries, String name, String expected) {
        check(Arrays.equals(expected.getBytes(StandardCharsets.US_ASCII), entries.get(name)),
                "exact content mismatch: " + name);
    }

    private static Map<String, byte[]> entries(byte[] gzip) throws IOException {
        byte[] cpio;
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            cpio = in.readAllBytes();
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        int position = 0;
        while (position + 110 <= cpio.length) {
            check("070701".equals(new String(cpio, position, 6, StandardCharsets.US_ASCII)), "newc magic");
            int nameSize = Integer.parseInt(new String(cpio, position + 94, 8, StandardCharsets.US_ASCII), 16);
            int fileSize = Integer.parseInt(new String(cpio, position + 54, 8, StandardCharsets.US_ASCII), 16);
            String name = new String(cpio, position + 110, nameSize - 1, StandardCharsets.US_ASCII);
            int start = (position + 110 + nameSize + 3) / 4 * 4;
            check(start + fileSize <= cpio.length, "complete newc entry");
            check(!entries.containsKey(name), "duplicate newc entry");
            entries.put(name, Arrays.copyOfRange(cpio, start, start + fileSize));
            if ("TRAILER!!!".equals(name)) {
                return entries;
            }
            position = (start + fileSize + 3) / 4 * 4;
        }
        throw new AssertionError("newc trailer missing");
    }

    private static void rejectArgument(Operation operation) throws Exception {
        try {
            operation.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("untrusted or invalid input accepted");
    }

    private static void rejectInput(String reason, Operation operation) throws Exception {
        try {
            operation.run();
        } catch (IOException expected) {
            check(reason.equals(expected.getMessage()), "stable input rejection reason");
            return;
        }
        throw new AssertionError("altered input accepted");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) {
            throw new AssertionError(reason);
        }
    }

    private static byte[] synthetic(int size, int seed) {
        byte[] output = new byte[size];
        for (int i = 0; i < size; i++) {
            output[i] = (byte) (seed + i * 17);
        }
        return output;
    }

    private static String sha256(byte[] input) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(input)) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }

    @FunctionalInterface
    private interface Operation {
        void run() throws Exception;
    }
}
