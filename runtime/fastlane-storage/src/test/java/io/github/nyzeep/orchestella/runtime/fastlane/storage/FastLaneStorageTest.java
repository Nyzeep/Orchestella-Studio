package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class FastLaneStorageTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private static byte[] buildTar(Map<String, byte[]> files, Set<String> directories)
            throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final TarWriter writer = new TarWriter(output);
        for (String directory : directories) {
            writer.addDirectory(directory, 0755, 1700000000L);
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            writer.addFile(file.getKey(), new ByteArrayInputStream(file.getValue()),
                    file.getValue().length, 0644, 1700000000L);
        }
        writer.finish();
        return output.toByteArray();
    }

    private static byte[] gzip(byte[] data) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(data);
        }
        return output.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gzip.readAllBytes();
        }
    }

    private static Map<String, byte[]> readTar(byte[] tar) throws Exception {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final TarReader reader = new TarReader(new ByteArrayInputStream(tar));
        TarEntry entry;
        while ((entry = reader.next()) != null) {
            if (entry.type() != TarEntry.Type.FILE) {
                continue;
            }
            final ByteArrayOutputStream content = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = reader.read(buffer, 0, buffer.length)) >= 0) {
                content.write(buffer, 0, read);
            }
            files.put(entry.path(), content.toByteArray());
        }
        return files;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    public void exportsTarAndManifest() throws Exception {
        final byte[] input = buildTar(
                Map.of("home/a.txt", "hello".getBytes(StandardCharsets.UTF_8),
                        "home/empty", new byte[0],
                        "workspace/b.bin", new byte[] {1, 2, 3}),
                Set.of("home", "home/dir", "workspace"));
        final MemorySink sink = new MemorySink();
        final ExportResult result = new FastLaneExporter(ExportPolicy.standard()).export(
                new ByteArrayInputStream(input), sink, ExportOptions.tar("backup"),
                CancellationToken.none());
        assertTrue(sink.committed);
        assertFalse(sink.aborted);
        assertEquals(3, result.manifest().entries().size());
        assertEquals(8L, result.manifest().totalBytes());
        assertEquals(sha256(sink.archive()), result.archiveSha256());
        assertEquals(result.manifest().entries(),
                ExportManifest.fromJson(new String(sink.manifest(), StandardCharsets.UTF_8)).entries());
        final Map<String, byte[]> files = readTar(sink.archive());
        assertEquals(3, files.size());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), files.get("home/a.txt"));
        assertArrayEquals(new byte[0], files.get("home/empty"));
        assertArrayEquals(new byte[] {1, 2, 3}, files.get("workspace/b.bin"));
        final ExportManifest manifest = result.manifest();
        assertEquals(sha256("hello".getBytes(StandardCharsets.UTF_8)),
                manifest.entries().get(0).sha256());
    }

    @Test
    public void skipsDeniedNames() throws Exception {
        final byte[] input = buildTar(
                Map.of("home/id_rsa", "secret".getBytes(StandardCharsets.UTF_8),
                        "home/a.txt", "ok".getBytes(StandardCharsets.UTF_8),
                        "home/session.sock", new byte[0]),
                Set.of("home"));
        final MemorySink sink = new MemorySink();
        final ExportResult result = new FastLaneExporter(ExportPolicy.standard()).export(
                new ByteArrayInputStream(input), sink, ExportOptions.tar("backup"),
                CancellationToken.none());
        assertEquals(1, result.manifest().entries().size());
        assertEquals("home/a.txt", result.manifest().entries().get(0).path());
        assertFalse(readTar(sink.archive()).containsKey("home/id_rsa"));
        assertFalse(readTar(sink.archive()).containsKey("home/session.sock"));
    }

    @Test
    public void exportsGzip() throws Exception {
        final byte[] input = buildTar(
                Map.of("home/a.txt", "zip".getBytes(StandardCharsets.UTF_8)), Set.of("home"));
        final MemorySink sink = new MemorySink();
        final ExportResult result = new FastLaneExporter(ExportPolicy.standard()).export(
                new ByteArrayInputStream(input), sink, ExportOptions.tarGz("backup"),
                CancellationToken.none());
        final byte[] raw = gunzip(sink.archive());
        assertArrayEquals("zip".getBytes(StandardCharsets.UTF_8), readTar(raw).get("home/a.txt"));
        assertEquals(sha256(sink.archive()), result.archiveSha256());
    }

    @Test
    public void cancellationAbortsWithoutCommit() throws Exception {
        final byte[] input = buildTar(
                Map.of("home/a.txt", "hello".getBytes(StandardCharsets.UTF_8)), Set.of("home"));
        final MemorySink sink = new MemorySink();
        final StorageException failure = assertThrows(StorageException.class,
                () -> new FastLaneExporter(ExportPolicy.standard()).export(
                        new ByteArrayInputStream(input), sink, ExportOptions.tar("backup"),
                        () -> true));
        assertEquals(StorageFailure.CANCELLED, failure.failure());
        assertTrue(sink.aborted);
        assertFalse(sink.committed);
    }

    @Test
    public void sinkFailureAborts() throws Exception {
        final byte[] input = buildTar(
                Map.of("home/a.txt", "hello".getBytes(StandardCharsets.UTF_8)), Set.of("home"));
        final FailingSink sink = new FailingSink(100);
        final StorageException failure = assertThrows(StorageException.class,
                () -> new FastLaneExporter(ExportPolicy.standard()).export(
                        new ByteArrayInputStream(input), sink, ExportOptions.tar("backup"),
                        CancellationToken.none()));
        assertEquals(StorageFailure.IO_FAILED, failure.failure());
        assertTrue(sink.aborted);
        assertFalse(sink.committed);
    }

    @Test
    public void fileSinkDoesNotOverwrite() throws Exception {
        final Path directory = temporary.newFolder("exports").toPath();
        final byte[] input = buildTar(
                Map.of("home/a.txt", "first".getBytes(StandardCharsets.UTF_8)), Set.of("home"));
        final FileExportSink first = new FileExportSink(directory, false);
        new FastLaneExporter(ExportPolicy.standard()).export(
                new ByteArrayInputStream(input), first, ExportOptions.tar("backup"),
                CancellationToken.none());
        first.close();
        assertTrue(Files.exists(directory.resolve("backup.tar")));
        assertTrue(Files.exists(directory.resolve("backup.manifest.json")));
        try (var listing = Files.list(directory)) {
            assertFalse(listing.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }

        final byte[] before = Files.readAllBytes(directory.resolve("backup.tar"));
        final FileExportSink second = new FileExportSink(directory, false);
        final StorageException failure = assertThrows(StorageException.class,
                () -> new FastLaneExporter(ExportPolicy.standard()).export(
                        new ByteArrayInputStream(input), second, ExportOptions.tar("backup"),
                        CancellationToken.none()));
        assertEquals(StorageFailure.IO_FAILED, failure.failure());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("backup.tar")));
    }

    @Test
    public void importerDoesNotAbortAfterSuccessfulEnd() throws Exception {
        final byte[] input = buildTar(
                Map.of("home/a.txt", "closed".getBytes(StandardCharsets.UTF_8)), Set.of("home"));
        final AtomicBoolean closeAttempted = new AtomicBoolean();
        final InputStream failingClose = new FilterInputStream(
                new ByteArrayInputStream(gzip(input))) {
            @Override
            public void close() throws IOException {
                closeAttempted.set(true);
                throw new IOException("injected close failure");
            }
        };
        final MemoryGuest guest = new MemoryGuest();
        final ImportResult result = new FastLaneImporter().importArchive(
                failingClose, true, guest, CancellationToken.none());
        assertTrue(closeAttempted.get());
        assertTrue(guest.ended);
        assertFalse(guest.aborted);
        assertEquals(1, result.manifest().entries().size());
    }

    @Test
    public void fileSinkRestoresPreviousExportWhenCommitFails() throws Exception {
        final Path directory = temporary.newFolder("rollback").toPath();
        Files.writeString(directory.resolve("backup.tar"), "old-archive");
        Files.writeString(directory.resolve("backup.manifest.json"), "old-manifest");
        final byte[] input = buildTar(
                Map.of("home/a.txt", "new".getBytes(StandardCharsets.UTF_8)), Set.of("home"));
        final AtomicInteger moves = new AtomicInteger();
        final FileExportSink.Mover failingMover = (source, destination, replace) -> {
            if (moves.incrementAndGet() == 2) {
                throw new IOException("injected move failure");
            }
            if (replace) {
                Files.move(source, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            }
        };
        final FileExportSink sink = new FileExportSink(directory, true, failingMover);
        final StorageException failure = assertThrows(StorageException.class,
                () -> new FastLaneExporter(ExportPolicy.standard()).export(
                        new ByteArrayInputStream(input), sink, ExportOptions.tar("backup"),
                        CancellationToken.none()));
        assertEquals(StorageFailure.IO_FAILED, failure.failure());
        assertEquals("old-archive", Files.readString(directory.resolve("backup.tar")));
        assertEquals("old-manifest", Files.readString(directory.resolve("backup.manifest.json")));
        try (var listing = Files.list(directory)) {
            assertFalse(listing.anyMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    public void fileSinkOverwritesPreviousExport() throws Exception {
        final Path directory = temporary.newFolder("overwrite").toPath();
        Files.writeString(directory.resolve("backup.tar"), "old-archive");
        Files.writeString(directory.resolve("backup.manifest.json"), "old-manifest");
        final byte[] input = buildTar(
                Map.of("home/a.txt", "new".getBytes(StandardCharsets.UTF_8)), Set.of("home"));
        final FileExportSink sink = new FileExportSink(directory, true);
        new FastLaneExporter(ExportPolicy.standard()).export(
                new ByteArrayInputStream(input), sink, ExportOptions.tar("backup"),
                CancellationToken.none());
        assertTrue(Files.size(directory.resolve("backup.tar")) > "old-archive".length());
        assertTrue(Files.readString(directory.resolve("backup.manifest.json"))
                .contains("home/a.txt"));
    }

    @Test
    public void importsAndEmitsManifest() throws Exception {
        final byte[] input = buildTar(
                Map.of("home/a.txt", "hello".getBytes(StandardCharsets.UTF_8),
                        "home/empty", new byte[0],
                        "workspace/b.bin", new byte[]{9, 8, 7}),
                Set.of("home", "workspace"));
        final MemoryGuest guest = new MemoryGuest();
        final ImportResult result = new FastLaneImporter().importArchive(
                new ByteArrayInputStream(input), false, guest, CancellationToken.none());
        assertTrue(guest.began);
        assertTrue(guest.ended);
        assertFalse(guest.aborted);
        assertEquals(3, result.manifest().entries().size());
        assertEquals(8L, result.manifest().totalBytes());
        assertEquals(guest.data().length, result.archiveBytes());
        assertEquals(sha256(guest.data()), result.archiveSha256());
        assertArrayEquals(guestDataHash(guest), guest.hash());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                readTar(guest.data()).get("home/a.txt"));
    }

    private static byte[] guestDataHash(MemoryGuest guest) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return digest.digest(guest.data());
    }

    @Test
    public void importsGzip() throws Exception {
        final byte[] input = buildTar(
                Map.of("home/a.txt", "zipped".getBytes(StandardCharsets.UTF_8)), Set.of("home"));
        final MemoryGuest guest = new MemoryGuest();
        final ImportResult result = new FastLaneImporter().importArchive(
                new ByteArrayInputStream(gzip(input)), true, guest, CancellationToken.none());
        assertEquals(1, result.manifest().entries().size());
        assertArrayEquals("zipped".getBytes(StandardCharsets.UTF_8),
                readTar(guest.data()).get("home/a.txt"));
    }

    @Test
    public void importRejectsUnsafePaths() throws Exception {
        assertImportRejected(rawTar(new RawEntry("../evil", '0', "x")));
        assertImportRejected(rawTar(new RawEntry("/etc/passwd", '0', "x")));
        assertImportRejected(rawTar(new RawEntry("etc/passwd", '0', "x")));
        assertImportRejected(rawTar(new RawEntry("home/link", '2', "")));
    }

    private void assertImportRejected(byte[] tar) {
        final MemoryGuest guest = new MemoryGuest();
        final StorageException failure = assertThrows(StorageException.class,
                () -> new FastLaneImporter().importArchive(
                        new ByteArrayInputStream(tar), false, guest, CancellationToken.none()));
        assertEquals(StorageFailure.PATH_REJECTED, failure.failure());
        assertTrue(guest.aborted);
        assertFalse(guest.ended);
    }

    @Test
    public void importCancellationAborts() throws Exception {
        final byte[] input = buildTar(
                Map.of("home/a.txt", "hello".getBytes(StandardCharsets.UTF_8)), Set.of("home"));
        final MemoryGuest guest = new MemoryGuest();
        final StorageException failure = assertThrows(StorageException.class,
                () -> new FastLaneImporter().importArchive(
                        new ByteArrayInputStream(input), false, guest, () -> true));
        assertEquals(StorageFailure.CANCELLED, failure.failure());
        assertTrue(guest.aborted);
        assertFalse(guest.ended);
    }

    @Test
    public void largeFileRoundTrip() throws Exception {
        final byte[] big = new byte[8 * 1024 * 1024];
        for (int index = 0; index < big.length; index++) {
            big[index] = (byte) (index % 251);
        }
        final byte[] input = buildTar(Map.of("home/big.bin", big), Set.of("home"));
        final MemoryGuest guest = new MemoryGuest();
        final ImportResult result = new FastLaneImporter().importArchive(
                new ByteArrayInputStream(input), false, guest, CancellationToken.none());
        assertEquals(big.length, result.manifest().entries().get(0).size());
        assertArrayEquals(big, readTar(guest.data()).get("home/big.bin"));
    }

    private record RawEntry(String name, char type, String content) {}

    private static byte[] rawTar(RawEntry... entries) throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (RawEntry entry : entries) {
            final byte[] content = entry.content().getBytes(StandardCharsets.UTF_8);
            final byte[] header = new byte[512];
            final byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0, header, 0, Math.min(name.length, 100));
            putOctal(header, 100, 8, 0644);
            putOctal(header, 108, 8, 0);
            putOctal(header, 116, 8, 0);
            putOctal(header, 124, 12, content.length);
            putOctal(header, 136, 12, 0);
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
            output.write(content);
            final int padding = (512 - content.length % 512) % 512;
            output.write(new byte[padding]);
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

    private static final class MemorySink implements ExportSink {
        private final ByteArrayOutputStream archive = new ByteArrayOutputStream();
        private final ByteArrayOutputStream manifest = new ByteArrayOutputStream();
        private boolean committed;
        private boolean aborted;

        @Override
        public OutputStream openArchive(String suggestedName) {
            return archive;
        }

        @Override
        public OutputStream openManifest(String suggestedName) {
            return manifest;
        }

        @Override
        public void commit() {
            committed = true;
        }

        @Override
        public void abort() {
            aborted = true;
        }

        byte[] archive() {
            return archive.toByteArray();
        }

        byte[] manifest() {
            return manifest.toByteArray();
        }
    }

    private static final class FailingSink implements ExportSink {
        private final int limit;
        private boolean aborted;
        private boolean committed;

        FailingSink(int limit) {
            this.limit = limit;
        }

        @Override
        public OutputStream openArchive(String suggestedName) {
            return new OutputStream() {
                private int written;

                @Override
                public void write(int value) throws IOException {
                    if (++written > limit) {
                        throw new IOException("sink full");
                    }
                }

                @Override
                public void write(byte[] buffer, int offset, int length) throws IOException {
                    written += length;
                    if (written > limit) {
                        throw new IOException("sink full");
                    }
                }
            };
        }

        @Override
        public OutputStream openManifest(String suggestedName) {
            return OutputStream.nullOutputStream();
        }

        @Override
        public void commit() {
            committed = true;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }

    private static final class MemoryGuest implements GuestImportSink {
        private final ByteArrayOutputStream data = new ByteArrayOutputStream();
        private boolean began;
        private boolean ended;
        private boolean aborted;
        private long total;
        private byte[] hash;

        @Override
        public void begin() {
            began = true;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            if (aborted) {
                throw new IllegalStateException("write after abort");
            }
            data.write(buffer, offset, length);
        }

        @Override
        public void end(long totalBytes, byte[] sha256) {
            total = totalBytes;
            hash = sha256.clone();
            ended = true;
        }

        @Override
        public void abort() {
            aborted = true;
        }

        byte[] data() {
            return data.toByteArray();
        }

        byte[] hash() {
            return hash.clone();
        }
    }
}
