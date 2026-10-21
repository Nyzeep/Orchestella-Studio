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

    @FunctionalInterface
    interface Mover {
        void move(Path source, Path destination, boolean replace) throws IOException;
    }

    private final Path directory;
    private final boolean overwrite;
    private final Mover mover;
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
        this(directory, overwrite, FileExportSink::moveFile);
    }

    FileExportSink(Path directory, boolean overwrite, Mover mover) {
        this.directory = directory;
        this.overwrite = overwrite;
        this.mover = mover;
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
        Path archiveBackup = null;
        Path manifestBackup = null;
        try {
            if (overwrite && Files.exists(archiveFinal)) {
                archiveBackup = backup(archiveFinal);
            }
            if (overwrite && Files.exists(manifestFinal)) {
                manifestBackup = backup(manifestFinal);
            }
            mover.move(archiveTemp, archiveFinal, overwrite);
            publishedArchive = true;
            mover.move(manifestTemp, manifestFinal, overwrite);
            publishedManifest = true;
            committed = true;
        } catch (IOException failure) {
            if (publishedArchive) {
                deleteQuietly(archiveFinal);
            }
            if (publishedManifest) {
                deleteQuietly(manifestFinal);
            }
            // The exporter calls abort() after a failed commit(); clear these so
            // abort cannot delete the files restored below.
            publishedArchive = false;
            publishedManifest = false;
            IOException rollbackFailure = null;
            try {
                restore(archiveBackup, archiveFinal);
            } catch (IOException restoreFailure) {
                rollbackFailure = restoreFailure;
            }
            try {
                restore(manifestBackup, manifestFinal);
            } catch (IOException restoreFailure) {
                if (rollbackFailure == null) {
                    rollbackFailure = restoreFailure;
                } else {
                    rollbackFailure.addSuppressed(restoreFailure);
                }
            }
            if (rollbackFailure != null) {
                final IOException combined = new IOException(
                        "commit failed and the previous export could not be restored");
                combined.addSuppressed(failure);
                combined.addSuppressed(rollbackFailure);
                throw combined;
            }
            throw failure;
        }
        deleteQuietly(archiveBackup);
        deleteQuietly(manifestBackup);
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

    private Path backup(Path target) throws IOException {
        final Path backup = Files.createTempFile(directory, ".fastlane-backup-", ".part");
        try {
            Files.move(target, backup,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            deleteQuietly(backup);
            throw failure;
        }
        return backup;
    }

    private static void restore(Path backup, Path target) throws IOException {
        if (backup == null) {
            return;
        }
        try {
            Files.move(backup, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            // The old file is still at the .fastlane-backup-*.part path; the
            // caller must be told that rollback failed, not just that commit did.
            throw new IOException("could not restore the previous export", failure);
        }
    }

    private static void moveFile(Path source, Path destination, boolean replace)
            throws IOException {
        if (replace) {
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
