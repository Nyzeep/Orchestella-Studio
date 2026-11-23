package io.github.nyzeep.orchestella.runtime.engineuml;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class CpioNewcToolExtractorTest {
    @Rule public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void extractsOnlyWhitelistedEntriesFromGzippedNewcCpio() throws Exception {
        Map<String, byte[]> archiveEntries = new LinkedHashMap<>();
        archiveEntries.put("sbin/mkfs.ext4", "FAKE_MKFS_ELF".getBytes(StandardCharsets.US_ASCII));
        archiveEntries.put("etc/mke2fs.conf", "[defaults]\nblocksize = 4096\n".getBytes(StandardCharsets.US_ASCII));
        archiveEntries.put("bin/busybox", "FAKE_BUSYBOX_ELF".getBytes(StandardCharsets.US_ASCII));
        archiveEntries.put("init", "ignored".getBytes(StandardCharsets.US_ASCII));
        byte[] cpioGz = buildNewcGz(archiveEntries);

        Path outDir = tempFolder.newFolder("extracted").toPath();
        CpioNewcToolExtractor.extractSelected(
                new ByteArrayInputStream(cpioGz),
                outDir,
                Set.of("sbin/mkfs.ext4", "etc/mke2fs.conf", "bin/busybox"),
                Set.of("sbin/mkfs.ext4", "bin/busybox"));

        assertArrayEquals(
                archiveEntries.get("sbin/mkfs.ext4"),
                Files.readAllBytes(outDir.resolve("sbin/mkfs.ext4")));
        assertArrayEquals(
                archiveEntries.get("etc/mke2fs.conf"),
                Files.readAllBytes(outDir.resolve("etc/mke2fs.conf")));
        assertArrayEquals(
                archiveEntries.get("bin/busybox"),
                Files.readAllBytes(outDir.resolve("bin/busybox")));
        assertTrue(!Files.exists(outDir.resolve("init")));
    }

    @Test
    public void failsClosedWhenRequiredEntryMissingOrPathUnsafe() throws Exception {
        byte[] incomplete = buildNewcGz(Map.of("sbin/mkfs.ext4", new byte[] {1, 2, 3}));
        Path outDir = tempFolder.newFolder("missing").toPath();
        IOException missing = assertThrows(
                IOException.class,
                () -> CpioNewcToolExtractor.extractSelected(
                        new ByteArrayInputStream(incomplete),
                        outDir,
                        Set.of("sbin/mkfs.ext4", "bin/busybox"),
                        Set.of("sbin/mkfs.ext4", "bin/busybox")));
        assertTrue(missing.getMessage().contains("bin/busybox"));

        byte[] traversal = buildNewcGz(Map.of("../evil", new byte[] {1}));
        IOException badPath = assertThrows(
                IOException.class,
                () -> CpioNewcToolExtractor.extractSelected(
                        new ByteArrayInputStream(traversal),
                        outDir,
                        Set.of("../evil"),
                        Set.of()));
        assertTrue(badPath.getMessage().contains("unsafe"));
    }

    private static byte[] buildNewcGz(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream cpio = new ByteArrayOutputStream();
        int inode = 1;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            writeNewcEntry(cpio, entry.getKey(), entry.getValue(), 0100755, inode++);
        }
        writeNewcEntry(cpio, "TRAILER!!!", new byte[0], 0, 0);

        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(cpio.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void writeNewcEntry(
            ByteArrayOutputStream out, String name, byte[] data, int mode, int ino)
            throws IOException {
        byte[] nameBytes = (name + "\0").getBytes(StandardCharsets.UTF_8);
        String header = String.format(
                "070701%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x%08x",
                ino, mode, 0, 0, 1, 0, data.length, 0, 0, 0, 0, nameBytes.length, 0);
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(nameBytes);
        pad4(out, 110 + nameBytes.length);
        out.write(data);
        pad4(out, data.length);
    }

    private static void pad4(ByteArrayOutputStream out, int len) {
        int rem = len % 4;
        if (rem != 0) {
            for (int i = 0; i < 4 - rem; i++) {
                out.write(0);
            }
        }
    }
}
