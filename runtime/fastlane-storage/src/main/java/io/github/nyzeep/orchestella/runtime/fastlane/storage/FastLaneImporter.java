package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * Validates a host-selected tar (optionally gzip) and streams a canonical tar to
 * the guest staging importer. Path traversal, absolute paths, symlinks, links,
 * and devices are rejected before the guest applies anything; the guest still
 * verifies the declared size and SHA-256 and rolls back its staging tree.
 */
public final class FastLaneImporter {
    public ImportResult importArchive(
            InputStream source,
            boolean gzip,
            GuestImportSink guest,
            CancellationToken token) throws StorageException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(guest, "guest");
        Objects.requireNonNull(token, "token");
        InputStream decoded = null;
        boolean ended = false;
        try {
            decoded = gzip ? new GZIPInputStream(source) : source;
            guest.begin();
            final GuestOutputStream guestOutput = new GuestOutputStream(guest);
            final MessageDigest archiveDigest = sha256();
            final DigestOutputStream digestStream =
                    new DigestOutputStream(guestOutput, archiveDigest);
            final TarWriter writer = new TarWriter(digestStream);
            final ExportManifest manifest = new ExportManifest();
            final TarReader reader = new TarReader(decoded);
            TarEntry entry;
            while ((entry = reader.next()) != null) {
                if (token.isCancelled()) {
                    throw new StorageException(StorageFailure.CANCELLED, "operation cancelled");
                }
                if (!isAllowedTopLevel(entry.path())) {
                    throw new StorageException(
                            StorageFailure.PATH_REJECTED, "import path outside home/workspace");
                }
                if (entry.type() == TarEntry.Type.DIRECTORY) {
                    writer.addDirectory(entry.path(), 0700, entry.modifiedTime());
                    continue;
                }
                final MessageDigest fileDigest = sha256();
                writer.addFile(
                        entry.path(),
                        new TarEntryStream(reader, fileDigest, token),
                        entry.size(),
                        0600,
                        entry.modifiedTime());
                manifest.add(new ExportManifest.Entry(
                        entry.path(),
                        entry.size(),
                        HexFormat.of().formatHex(fileDigest.digest()),
                        entry.modifiedTime()));
            }
            writer.finish();
            digestStream.flush();
            guestOutput.flush();
            final byte[] archiveHash = archiveDigest.digest();
            guest.end(guestOutput.count(), archiveHash);
            ended = true;
            return new ImportResult(
                    manifest,
                    guestOutput.count(),
                    HexFormat.of().formatHex(archiveHash));
        } catch (StorageIOException failure) {
            abortIfNeeded(guest, ended);
            throw failure.storage();
        } catch (StorageException failure) {
            abortIfNeeded(guest, ended);
            throw failure;
        } catch (IOException failure) {
            abortIfNeeded(guest, ended);
            throw StorageException.io(failure);
        } finally {
            // A close failure after a successful end() must not abort a committed
            // guest import. The caller owns a non-gzip source stream.
            if (gzip && decoded != null) {
                try {
                    decoded.close();
                } catch (IOException ignored) {
                    // The import already succeeded or failed with a typed reason.
                }
            }
        }
    }

    private static void abortIfNeeded(GuestImportSink guest, boolean ended) {
        if (!ended) {
            guest.abort();
        }
    }

    private static boolean isAllowedTopLevel(String path) {
        return "home".equals(path)
                || path.startsWith("home/")
                || "workspace".equals(path)
                || path.startsWith("workspace/");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class GuestOutputStream extends OutputStream {
        private final GuestImportSink guest;
        private long count;

        GuestOutputStream(GuestImportSink guest) {
            this.guest = guest;
        }

        @Override
        public void write(int value) throws IOException {
            final byte[] one = {(byte) value};
            write(one, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            guest.write(buffer, offset, length);
            count += length;
        }

        long count() {
            return count;
        }
    }
}
