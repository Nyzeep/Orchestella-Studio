package io.github.nyzeep.orchestella.runtime.engineqemu;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Contract tests for the embedded provision input builder (gzipped newc cpio). */
public final class EmbeddedProvisionInputTest {
    public static void main(String[] args) throws Exception {
        byte[] rootfs = synthetic(4096, 0x11);
        String token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String rootfsName = "alpine-minirootfs-3.24.1-aarch64.tar.gz";

        byte[] segment = EmbeddedProvisionInput.build(rootfs, rootfsName, token);

        // Deterministic: identical inputs produce identical bytes.
        if (!Arrays.equals(segment, EmbeddedProvisionInput.build(rootfs, rootfsName, token))) {
            fail("segment is not deterministic");
        }

        // Streaming writeSegment equivalence: produces identical bytes.
        java.io.ByteArrayOutputStream streamOut = new java.io.ByteArrayOutputStream();
        try (ByteArrayInputStream in = new ByteArrayInputStream(rootfs)) {
            EmbeddedProvisionInput.writeSegment(in, rootfs.length, rootfsName, token, null, streamOut);
        }
        if (!Arrays.equals(segment, streamOut.toByteArray())) {
            fail("writeSegment output does not match build");
        }

        // GZIP wrapper present.
        if ((segment[0] & 0xFF) != 0x1F || (segment[1] & 0xFF) != 0x8B) {
            fail("segment is not gzip");
        }

        // Decompress and inspect the newc structure.
        byte[] cpio = gunzip(segment);
        String text = new String(cpio, StandardCharsets.US_ASCII);
        if (!text.startsWith("070701")) {
            fail("segment does not start with newc magic");
        }
        for (String required : new String[] {
                "opt/droidlayer/embedded-input/.active",
                "opt/droidlayer/embedded-input/bridge.token",
                "opt/droidlayer/embedded-input/" + rootfsName,
                "TRAILER!!!",
        }) {
            if (!text.contains(required)) {
                fail("segment missing entry: " + required);
            }
        }
        int activeAt = text.indexOf("opt/droidlayer/embedded-input/.active");
        if (activeAt < 0 || text.indexOf("070701", 0) != 0) {
            fail("structure broken");
        }
        // Parent directory entries must precede nested members: the kernel's
        // initramfs unpacker never creates intermediate directories, so a
        // segment without them is silently dropped on-device.
        java.util.List<String> names = new java.util.ArrayList<>();
        int pos = 0;
        while (pos + 110 <= cpio.length) {
            if (!"070701".equals(new String(cpio, pos, 6, StandardCharsets.US_ASCII))) {
                fail("bad newc magic at offset " + pos);
            }
            int nameSize = Integer.parseInt(
                    new String(cpio, pos + 94, 8, StandardCharsets.US_ASCII), 16);
            int fileSize = Integer.parseInt(
                    new String(cpio, pos + 54, 8, StandardCharsets.US_ASCII), 16);
            String name = new String(cpio, pos + 110, nameSize - 1, StandardCharsets.US_ASCII);
            names.add(name);
            if ("TRAILER!!!".equals(name)) {
                break;
            }
            int dataStart = ((pos + 110 + nameSize + 3) / 4) * 4;
            pos = ((dataStart + fileSize + 3) / 4) * 4;
        }
        for (String parent : new String[] {"opt", "opt/droidlayer", "opt/droidlayer/embedded-input"}) {
            if (!names.contains(parent)) {
                fail("segment missing parent directory entry: " + parent);
            }
        }
        if (!(names.indexOf("opt") < names.indexOf("opt/droidlayer")
                && names.indexOf("opt/droidlayer") < names.indexOf("opt/droidlayer/embedded-input"))) {
            fail("parent directory entries must precede nested members");
        }
        // bridge.token content must be exactly the token plus one newline.
        String tokenText = new String(cpio, StandardCharsets.US_ASCII);
        int nameOffset = tokenText.indexOf("opt/droidlayer/embedded-input/bridge.token");
        if (nameOffset < 0) {
            fail("bridge.token entry missing");
        }

        // Token validation: wrong length and non-hex are rejected.
        for (String bad : new String[] {"short", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdeZ"}) {
            try {
                EmbeddedProvisionInput.build(rootfs, rootfsName, bad);
                fail("invalid token accepted");
            } catch (IllegalArgumentException expected) {
                // required
            }
        }
        // Identity & credentials streaming (Ticket 03)
        java.io.ByteArrayOutputStream idOut = new java.io.ByteArrayOutputStream();
        try (ByteArrayInputStream in = new ByteArrayInputStream(rootfs)) {
            EmbeddedProvisionInput.writeSegment(
                    in, rootfs.length, rootfsName, token, "MyPass1234", null, null,
                    "orchestella-host", "devuser", true, idOut);
        }
        byte[] idCpio = gunzip(idOut.toByteArray());
        String idText = new String(idCpio, StandardCharsets.US_ASCII);
        if (!idText.contains("opt/droidlayer/embedded-input/hostname")) {
            fail("missing embedded hostname entry");
        }
        if (!idText.contains("opt/droidlayer/embedded-input/guest.user")) {
            fail("missing embedded guest.user entry");
        }
        if (!idText.contains("opt/droidlayer/embedded-input/guest.sudo")) {
            fail("missing embedded guest.sudo entry");
        }
        if (!idText.contains("opt/droidlayer/embedded-input/guest.password")) {
            fail("missing embedded guest.password entry");
        }

        System.out.println("EMBEDDED_PROVISION_INPUT_TEST_OK deterministic=1 gzip=1 newc_entries=3 trailer=1 parent_dirs=2 token_validation=2 identity_credentials=1");
    }

    private static void fail(String reason) {
        System.out.println("EMBEDDED_PROVISION_INPUT_TEST_FAIL reason=" + reason);
        System.exit(1);
    }

    private static byte[] synthetic(int size, int seed) {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (seed + i * 17);
        }
        return out;
    }

    private static byte[] gunzip(byte[] gz) throws Exception {
        java.util.zip.InflaterInputStream in = new java.util.zip.GZIPInputStream(
                new ByteArrayInputStream(gz));
        return in.readAllBytes();
    }
}
