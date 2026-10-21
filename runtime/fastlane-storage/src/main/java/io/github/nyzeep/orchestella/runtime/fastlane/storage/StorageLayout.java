package io.github.nyzeep.orchestella.runtime.fastlane.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Canonical encrypted-storage layout: home/workspace/tmp/cache/exports plus a
 * version marker. Initialization is idempotent and refuses symlinked roots,
 * subdirectories, or markers.
 */
public final class StorageLayout {
    public static final String MARKER_NAME = ".fastlane-layout";
    public static final int LAYOUT_VERSION = 1;

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final Path root;

    private StorageLayout(Path root) {
        this.root = root;
    }

    public static StorageLayout initialize(Path root) throws StorageException {
        if (root == null || !root.isAbsolute()) {
            throw new StorageException(StorageFailure.LAYOUT_INVALID, "storage root must be absolute");
        }
        final Path normalized = root.normalize();
        try {
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException(
                        StorageFailure.LAYOUT_INVALID, "storage root is not a directory");
            }
            if (Files.isSymbolicLink(normalized)) {
                throw new StorageException(
                        StorageFailure.PATH_REJECTED, "storage root must not be a symlink");
            }
            Files.createDirectories(normalized);
            if (Files.isSymbolicLink(normalized)) {
                throw new StorageException(
                        StorageFailure.PATH_REJECTED, "storage root must not be a symlink");
            }
            setPermissions(normalized, DIRECTORY_PERMISSIONS);
            final StorageLayout layout = new StorageLayout(normalized);
            for (String name : List.of("home", "workspace", "tmp", "cache", "exports")) {
                ensureDirectory(normalized.resolve(name));
            }
            final Path marker = normalized.resolve(MARKER_NAME);
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(marker)) {
                    throw new StorageException(
                            StorageFailure.PATH_REJECTED, "layout marker must not be a symlink");
                }
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    throw new StorageException(
                            StorageFailure.LAYOUT_INVALID, "layout marker is not a file");
                }
                if (Files.size(marker) > 64) {
                    throw new StorageException(
                            StorageFailure.LAYOUT_INVALID, "layout marker is too large");
                }
                final String content = Files.readString(marker, StandardCharsets.UTF_8);
                if (!"version=1\n".equals(content)) {
                    throw new StorageException(
                            StorageFailure.LAYOUT_INVALID, "unsupported layout version");
                }
            } else {
                writeMarker(marker);
            }
            return layout;
        } catch (IOException failure) {
            throw StorageException.io(failure);
        }
    }

    private static void writeMarker(Path marker) throws IOException, StorageException {
        final Path temporary = Files.createTempFile(
                marker.getParent(), ".fastlane-layout", ".tmp");
        try {
            setPermissions(temporary, FILE_PERMISSIONS);
            Files.writeString(temporary, "version=1\n", StandardCharsets.UTF_8);
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    private static void ensureDirectory(Path path) throws IOException, StorageException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path)) {
                throw new StorageException(
                        StorageFailure.PATH_REJECTED, "storage subdirectory is a symlink");
            }
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException(
                        StorageFailure.LAYOUT_INVALID, "storage subdirectory is not a directory");
            }
            return;
        }
        Files.createDirectory(path);
        setPermissions(path, DIRECTORY_PERMISSIONS);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystems keep their defaults; the caller still validates paths.
        }
    }

    public Path root() {
        return root;
    }

    public Path home() {
        return root.resolve("home");
    }

    public Path workspace() {
        return root.resolve("workspace");
    }

    public Path tmp() {
        return root.resolve("tmp");
    }

    public Path cache() {
        return root.resolve("cache");
    }

    public Path exports() {
        return root.resolve("exports");
    }

    /** Lexical resolution; rejects absolute and traversal paths. */
    public Path resolve(String relative) throws StorageException {
        if (!StoragePaths.isSafeRelative(relative)) {
            throw new StorageException(StorageFailure.PATH_REJECTED, "unsafe relative path");
        }
        final Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new StorageException(StorageFailure.PATH_REJECTED, "path escapes the storage root");
        }
        return path;
    }

    /** Resolution plus NOFOLLOW checks on every component; symlinks are rejected. */
    public Path resolveNoSymlink(String relative) throws StorageException {
        final Path path = resolve(relative);
        Path current = root;
        for (String component : StoragePaths.components(relative)) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException(StorageFailure.PATH_REJECTED, "path does not exist");
            }
            if (Files.isSymbolicLink(current)) {
                throw new StorageException(StorageFailure.PATH_REJECTED, "symlink in storage path");
            }
        }
        return path;
    }
}
