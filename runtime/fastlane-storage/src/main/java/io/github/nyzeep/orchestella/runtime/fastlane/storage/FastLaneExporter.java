package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

/**
 * Streams a guest tar through a policy filter into an {@link ExportSink}. The
 * archive is canonicalised (PAX when needed) and hashed while it is written; the
 * manifest is written only after the archive is complete, and commit is last.
 */
public final class FastLaneExporter {
    private final ExportPolicy policy;

    public FastLaneExporter(ExportPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public ExportResult export(
            InputStream tarStream,
            ExportSink sink,
            ExportOptions options,
            CancellationToken token) throws StorageException {
        Objects.requireNonNull(tarStream, "tarStream");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(token, "token");
        try {
            final OutputStream archive = sink.openArchive(options.archiveName());
            final CountingOutputStream counting = new CountingOutputStream(archive);
            final MessageDigest archiveDigest = sha256();
            final DigestOutputStream digestStream = new DigestOutputStream(counting, archiveDigest);
            final OutputStream encoded = options.gzip()
                    ? new GZIPOutputStream(digestStream, 64 * 1024)
                    : digestStream;
            final TarWriter writer = new TarWriter(encoded);
            final ExportManifest manifest = new ExportManifest();

            final TarReader reader = new TarReader(tarStream);
            TarEntry entry;
            while ((entry = reader.next()) != null) {
                checkCancelled(token);
                if (policy.isDenied(entry.path())) {
                    continue;  // next() skips the entry body.
                }
                if (entry.type() == TarEntry.Type.DIRECTORY) {
                    writer.addDirectory(entry.path(), 0755, entry.modifiedTime());
                    continue;
                }
                final MessageDigest fileDigest = sha256();
                writer.addFile(
                        entry.path(),
                        new TarEntryStream(reader, fileDigest, token),
                        entry.size(),
                        0644,
                        entry.modifiedTime());
                manifest.add(new ExportManifest.Entry(
                        entry.path(),
                        entry.size(),
                        HexFormat.of().formatHex(fileDigest.digest()),
                        entry.modifiedTime()));
            }
            writer.finish();
            if (encoded instanceof GZIPOutputStream) {
                ((GZIPOutputStream) encoded).finish();
            }
            encoded.flush();
            digestStream.flush();
            counting.flush();
            final byte[] archiveHash = archiveDigest.digest();

            final byte[] manifestBytes =
                    manifest.toJson().getBytes(StandardCharsets.UTF_8);
            try (OutputStream manifestStream = sink.openManifest(options.manifestName())) {
                manifestStream.write(manifestBytes);
                manifestStream.flush();
            }
            sink.commit();
            return new ExportResult(
                    manifest,
                    options.archiveName(),
                    counting.count(),
                    HexFormat.of().formatHex(archiveHash));
        } catch (StorageIOException failure) {
            abortQuietly(sink);
            throw failure.storage();
        } catch (StorageException failure) {
            abortQuietly(sink);
            throw failure;
        } catch (IOException failure) {
            abortQuietly(sink);
            throw StorageException.io(failure);
        }
    }

    private static void abortQuietly(ExportSink sink) {
        try {
            sink.abort();
        } catch (IOException ignored) {
            // The original failure is the one the caller needs.
        }
    }

    private static void checkCancelled(CancellationToken token) throws StorageException {
        if (token.isCancelled()) {
            throw new StorageException(StorageFailure.CANCELLED, "operation cancelled");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(OutputStream output) {
            super(output);
        }

        @Override
        public void write(int value) throws IOException {
            out.write(value);
            ++count;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            out.write(buffer, offset, length);
            count += length;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        long count() {
            return count;
        }
    }
}
