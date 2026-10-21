package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TarRoundTripTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void roundTripsFilesAndDirectories() throws Exception {
        final byte[] big = new byte[8 * 1024 * 1024];
        for (int index = 0; index < big.length; index++) {
            big[index] = (byte) (index % 251);
        }
        final String longPath = "home/" + "a".repeat(130) + "/" + "b".repeat(90);
        final String utf8 = "home/日本語/ファイル.txt";
        final String special = "home/space quote\"newline\n.txt";
        final ByteArrayOutputStream tar = new ByteArrayOutputStream();
        final TarWriter writer = new TarWriter(tar);
        writer.addDirectory("home", 0755, 1700000000L);
        writer.addDirectory("home/dir", 0755, 1700000000L);
        writer.addFile("home/empty.txt", new ByteArrayInputStream(new byte[0]), 0, 0644, 1700000000L);
        writer.addFile("home/dir/big.bin", new ByteArrayInputStream(big), big.length, 0644, 1700000000L);
        writer.addFile(longPath, new ByteArrayInputStream("long".getBytes(StandardCharsets.UTF_8)),
                4, 0644, 1700000000L);
        writer.addFile(utf8, new ByteArrayInputStream("utf8".getBytes(StandardCharsets.UTF_8)),
                4, 0644, 1700000000L);
        writer.addFile(special, new ByteArrayInputStream("special".getBytes(StandardCharsets.UTF_8)),
                7, 0644, 1700000000L);
        writer.finish();

        final Map<String, byte[]> files = new LinkedHashMap<>();
        final TarReader reader = new TarReader(new ByteArrayInputStream(tar.toByteArray()));
        TarEntry entry;
        while ((entry = reader.next()) != null) {
            if (entry.type() == TarEntry.Type.FILE) {
                final ByteArrayOutputStream content = new ByteArrayOutputStream();
                final byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = reader.read(buffer, 0, buffer.length)) >= 0) {
                    content.write(buffer, 0, read);
                }
                files.put(entry.path(), content.toByteArray());
            }
        }
        assertEquals(5, files.size());
        assertArrayEquals(new byte[0], files.get("home/empty.txt"));
        assertArrayEquals(big, files.get("home/dir/big.bin"));
        assertEquals("long", new String(files.get(longPath), StandardCharsets.UTF_8));
        assertEquals("utf8", new String(files.get(utf8), StandardCharsets.UTF_8));
        assertEquals("special", new String(files.get(special), StandardCharsets.UTF_8));
    }

    @Test
    public void gnuTarCanReadTheArchive() throws Exception {
        final Path tarBinary = Path.of("/bin/tar");
        assumeTrue("tar is required for the interop test", Files.isExecutable(tarBinary));
        final ByteArrayOutputStream tar = new ByteArrayOutputStream();
        final TarWriter writer = new TarWriter(tar);
        writer.addDirectory("home", 0755, 1700000000L);
        writer.addFile("home/a.txt", new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                5, 0644, 1700000000L);
        writer.finish();
        final Path archive = temporary.newFile("interop.tar").toPath();
        Files.write(archive, tar.toByteArray());
        final Path output = temporary.newFolder("extract").toPath();
        final Process process = new ProcessBuilder(
                tarBinary.toString(), "-xf", archive.toString(), "-C", output.toString())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor());
        assertEquals("hello", Files.readString(output.resolve("home/a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void rejectsTraversalAbsoluteAndLinks() throws Exception {
        assertRejected("../evil", '0', true);
        assertRejected("/etc/passwd", '0', true);
        assertRejected("home/../../evil", '0', true);
        assertRejected("home/link", '2', true);
        assertRejected("home/hard", '1', true);
        assertRejected("home/fifo", '6', true);
    }

    private void assertRejected(String name, char type, boolean expectPath) throws Exception {
        final byte[] tar = rawTar(new RawEntry(name, type, "x".getBytes(StandardCharsets.UTF_8)));
        final StorageException failure = assertThrows(StorageException.class, () -> {
            final TarReader reader = new TarReader(new ByteArrayInputStream(tar));
            TarEntry entry;
            while ((entry = reader.next()) != null) {
                final byte[] buffer = new byte[8];
                while (reader.read(buffer, 0, buffer.length) >= 0) {
                    // Drain.
                }
            }
        });
        assertEquals(expectPath ? StorageFailure.PATH_REJECTED : StorageFailure.UNSUPPORTED_FORMAT,
                failure.failure());
    }

    @Test
    public void rejectsTruncatedAndCorruptArchives() throws Exception {
        final byte[] valid = rawTar(new RawEntry("home/a", '0', "hello".getBytes(StandardCharsets.UTF_8)));
        final byte[] truncated = Arrays.copyOf(valid, valid.length - 1024);
        assertThrows(StorageException.class, () -> {
            final TarReader reader = new TarReader(new ByteArrayInputStream(truncated));
            TarEntry entry;
            while ((entry = reader.next()) != null) {
                final byte[] buffer = new byte[8];
                while (reader.read(buffer, 0, buffer.length) >= 0) {
                    // Drain.
                }
            }
        });

        final byte[] corrupt = valid.clone();
        corrupt[0] = 'X';
        assertThrows(StorageException.class, () -> new TarReader(
                new ByteArrayInputStream(corrupt)).next());
    }

    @Test
    public void writerRejectsUnsafePaths() {
        final TarWriter writer = new TarWriter(new ByteArrayOutputStream());
        assertThrows(StorageException.class, () -> writer.addFile(
                "../a", new ByteArrayInputStream(new byte[0]), 0, 0644, 0));
        assertThrows(StorageException.class, () -> writer.addDirectory(
                "/abs", 0755, 0));
    }

    private record RawEntry(String name, char type, byte[] content) {}

    private static byte[] rawTar(RawEntry... entries) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (RawEntry entry : entries) {
            final byte[] header = new byte[512];
            final byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, header, 0, Math.min(name.length, 100));
            putOctal(header, 100, 8, entry.type() == '5' ? 0755 : 0644);
            putOctal(header, 108, 8, 0);
            putOctal(header, 116, 8, 0);
            putOctal(header, 124, 12, entry.content().length);
            putOctal(header, 136, 12, 1700000000L);
            Arrays.fill(header, 148, 156, (byte) ' ');
            header[156] = (byte) entry.type();
            header[257] = 'u';
            header[258] = 's';
            header[259] = 't';
            header[260] = 'a';
            header[261] = 'r';
            header[263] = '0';
            header[264] = '0';
            long checksum = 0;
            for (byte value : header) {
                checksum += value & 0xFF;
            }
            putOctal(header, 148, 7, checksum);
            header[155] = ' ';
            output.write(header);
            int offset = 0;
            while (offset < entry.content().length) {
                final byte[] block = new byte[512];
                final int chunk = Math.min(512, entry.content().length - offset);
                System.arraycopy(entry.content(), offset, block, 0, chunk);
                output.write(block);
                offset += chunk;
            }
        }
        output.write(new byte[1024]);
        return output.toByteArray();
    }

    private static void putOctal(byte[] destination, int offset, int length, long value) {
        for (int index = offset + length - 2; index >= offset; index--) {
            destination[index] = (byte) ('0' + (value & 07));
            value >>= 3;
        }
        destination[offset + length - 1] = 0;
    }
}
