package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Reference sink with temp-file + atomic-move semantics. Production SAF adapters
 * follow the same contract: nothing user-visible exists until commit().
 */
public final class FileExportSink implements ExportSink, Closeable {
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path directory;
    private final boolean overwrite;
    private Path archiveFinal;
    private Path manifestFinal;
    private Path archiveTemp;
    private Path manifestTemp;
    private OutputStream archiveStream;
    private OutputStream manifestStream;
    private boolean publishedArchive;
    private boolean publishedManifest;
    private boolean committed;

    public FileExportSink(Path directory, boolean overwrite) {
        this.directory = directory;
        this.overwrite = overwrite;
    }

    @Override
    public synchronized OutputStream openArchive(String suggestedName) throws IOException {
        if (archiveStream != null) {
            throw new IOException("archive stream is already open");
        }
        archiveFinal = target(suggestedName);
        archiveTemp = Files.createTempFile(directory, ".fastlane-export-", ".part");
        setPermissions(archiveTemp);
        archiveStream = Files.newOutputStream(
                archiveTemp, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return archiveStream;
    }

    @Override
    public synchronized OutputStream openManifest(String suggestedName) throws IOException {
        if (manifestStream != null) {
            throw new IOException("manifest stream is already open");
        }
        manifestFinal = target(suggestedName);
        manifestTemp = Files.createTempFile(directory, ".fastlane-manifest-", ".part");
        setPermissions(manifestTemp);
        manifestStream = Files.newOutputStream(
                manifestTemp, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return manifestStream;
    }

    @Override
    public synchronized void commit() throws IOException {
        closeStreams();
        if (archiveTemp == null || manifestTemp == null) {
            throw new IOException("archive and manifest must both be written");
        }
        if (!overwrite && (Files.exists(archiveFinal) || Files.exists(manifestFinal))) {
            abort();
            throw new IOException("refusing to overwrite an existing export");
        }
        try {
            move(manifestTemp, manifestFinal);
            publishedManifest = true;
            move(archiveTemp, archiveFinal);
            publishedArchive = true;
            committed = true;
        } catch (IOException failure) {
            if (publishedArchive) {
                Files.deleteIfExists(archiveFinal);
            }
            if (publishedManifest) {
                Files.deleteIfExists(manifestFinal);
            }
            throw failure;
        }
    }

    @Override
    public synchronized void abort() {
        closeStreams();
        deleteQuietly(archiveTemp);
        deleteQuietly(manifestTemp);
        if (!committed) {
            if (publishedArchive) {
                deleteQuietly(archiveFinal);
            }
            if (publishedManifest) {
                deleteQuietly(manifestFinal);
            }
        }
        archiveTemp = null;
        manifestTemp = null;
    }

    @Override
    public void close() {
        if (!committed) {
            abort();
        }
    }

    private Path target(String name) throws IOException {
        if (!StoragePaths.isSafeRelative(name)) {
            throw new IOException("unsafe export name");
        }
        return directory.resolve(name);
    }

    private void move(Path source, Path destination) throws IOException {
        if (overwrite) {
            Files.move(source, destination,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private void closeStreams() {
        if (archiveStream != null) {
            try {
                archiveStream.close();
            } catch (IOException ignored) {
                // abort/commit still proceed; the temp file is removed.
            }
            archiveStream = null;
        }
        if (manifestStream != null) {
            try {
                manifestStream.close();
            } catch (IOException ignored) {
                // Same as above.
            }
            manifestStream = null;
        }
    }

    private static void setPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems keep their defaults.
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; the caller already has a failure.
        }
    }
}
