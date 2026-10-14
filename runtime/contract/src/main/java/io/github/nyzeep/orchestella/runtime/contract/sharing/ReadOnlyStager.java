package io.github.nyzeep.orchestella.runtime.contract.sharing;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Selected-file streaming transaction. No directory scans, network clients, queues or content parsers. */
public final class ReadOnlyStager implements AutoCloseable {
    public enum Kind { FILE, DIRECTORY, UNKNOWN }
    public enum MountStatus { UNVERIFIED }
    public record Limits(int maxFiles, long maxFileBytes, long maxTotalBytes, long diskQuotaBytes, int maxNameBytes) {
        public Limits {
            if (maxFiles < 1 || maxFiles > 10000 || maxFileBytes < 1 || maxTotalBytes < 1 || diskQuotaBytes < 1
                    || maxNameBytes < 1 || maxNameBytes > 240) { throw new IllegalArgumentException("Invalid staging limits"); }
        }
    }
    @FunctionalInterface public interface InputSupplier { InputStream open() throws IOException; }
    /** Supplier is a trusted source adapter for exactly sourceUri; unknown sizes are -1, hash may be null. */
    public record SelectedFile(URI sourceUri, String name, String mimeType, long declaredBytes,
                               String expectedSha256, Kind kind, InputSupplier input) { }
    public record FileInfo(String name, String mimeType, long bytes, String sha256) { }
    /** Logical catalog only: no host/cache path and no implicit physical filesystem export. */
    public record GuestShareSpec(String generationId, List<String> catalogNames, String mountTag,
                                 String guestMountPoint, boolean readOnly, MountStatus mountStatus) {
        public GuestShareSpec { catalogNames = List.copyOf(catalogNames); }
    }

    /** Cancellation also closes active source streams to unblock a nonseekable read. */
    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<InputStream> streams = ConcurrentHashMap.newKeySet();
        public void cancel() {
            cancelled.set(true);
            for (InputStream input : streams) {
                try { input.close(); } catch (IOException ignored) { /* Stage still fails and cleans up. */ }
            }
        }
        public boolean isCancelled() { return cancelled.get(); }
        private void check() throws InterruptedIOException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Staging cancelled");
            }
        }
        private void attach(InputStream input) throws IOException {
            streams.add(input);
            if (cancelled.get()) {
                streams.remove(input);
                input.close();
                check();
            }
        }
        private void detach(InputStream input) { streams.remove(input); }
    }

    private final Path root;
    private final Path session;
    private final Limits limits;
    private final List<Generation> generations = new ArrayList<>();
    private volatile Generation current;
    private volatile Cancellation active;
    private volatile boolean closed;
    private long storedBytes;

    /** Root must already exist, be owned by the caller and have no symlink/junction ancestors. */
    public ReadOnlyStager(Path ownedCacheRoot, Limits limits) throws IOException {
        root = PathBoundary.directory(ownedCacheRoot);
        this.limits = Objects.requireNonNull(limits, "limits");
        session = Files.createDirectory(root.resolve("selected-files-" + UUID.randomUUID()));
        PathBoundary.directory(session);
    }

    public Generation current() { return current; }
    public synchronized long storedBytes() { return storedBytes; }

    /** All descriptors are admitted before opening any source. Failed generation never becomes current. */
    public synchronized Generation stage(DownloadsSelection selection, List<SelectedFile> selected,
                                          Cancellation cancellation) throws IOException {
        requireOpen();
        Objects.requireNonNull(selection, "selection");
        Objects.requireNonNull(cancellation, "cancellation");
        List<SelectedFile> files = List.copyOf(Objects.requireNonNull(selected, "selected"));
        cancellation.check();
        admit(selection, files);
        PathBoundary.directory(root);
        PathBoundary.directory(session);
        String id = UUID.randomUUID().toString();
        Path pending = Files.createDirectory(session.resolve("pending-" + id));
        Path committed = session.resolve("generation-" + id);
        List<String> created = new ArrayList<>();
        List<FileInfo> infos = new ArrayList<>();
        long total = 0;
        boolean moved = false;
        boolean published = false;
        active = cancellation;
        try {
            for (SelectedFile file : files) {
                cancellation.check();
                requireOpen();
                selection.requireSelected(file.sourceUri());
                PathBoundary.directory(pending);
                Path target = pending.resolve(file.name());
                created.add(file.name());
                MessageDigest hash = sha256();
                long count = 0;
                InputStream input = Objects.requireNonNull(file.input().open(), "source stream");
                try (input) {
                    cancellation.attach(input);
                    try (FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[64 * 1024];
                        int emptyReads = 0;
                        while (true) {
                            cancellation.check();
                            int read = input.read(buffer);
                            cancellation.check();
                            if (read < 0) { break; }
                            if (read == 0) {
                                if (++emptyReads > 16) { throw new IOException("Source made no streaming progress"); }
                                continue;
                            }
                            emptyReads = 0;
                            if (read > limits.maxFileBytes() - count || read > limits.maxTotalBytes() - total
                                    || read > limits.diskQuotaBytes() - storedBytes - total) {
                                throw new IOException("Staging byte quota exceeded");
                            }
                            hash.update(buffer, 0, read);
                            ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, read);
                            while (chunk.hasRemaining()) { output.write(chunk); }
                            count += read;
                            total += read;
                        }
                        output.force(true);
                    } finally { cancellation.detach(input); }
                }
                if (file.declaredBytes() >= 0 && count != file.declaredBytes()) {
                    throw new IOException("Source ended with a different declared size");
                }
                String digest = HexFormat.of().formatHex(hash.digest());
                if (file.expectedSha256() != null && !digest.equals(file.expectedSha256().toLowerCase(Locale.ROOT))) {
                    throw new IOException("Selected source hash mismatch");
                }
                selection.requireSelected(file.sourceUri());
                if (!digest.equals(hashFile(target, cancellation))) { throw new IOException("Staged file hash mismatch"); }
                if (!target.toFile().setReadOnly()) { throw new IOException("Could not protect staged file"); }
                infos.add(new FileInfo(file.name(), file.mimeType(), count, digest));
            }
            cancellation.check();
            requireOpen();
            PathBoundary.directory(pending);
            for (SelectedFile file : files) { selection.requireSelected(file.sourceUri()); }
            Files.move(pending, committed, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
            PathBoundary.directory(committed);
            cancellation.check();
            requireOpen();
            Generation generation = new Generation(committed, List.copyOf(infos), total);
            generations.add(generation);
            storedBytes += total;
            current = generation;
            published = true;
            return generation;
        } finally {
            active = null;
            if (!published) { removeOwned(moved ? committed : pending, created); }
        }
    }

    private void admit(DownloadsSelection selection, List<SelectedFile> files) throws IOException {
        if (files.isEmpty() || files.size() > limits.maxFiles() || files.size() != selection.selectedFiles().size()) {
            throw new SecurityException("Selection count or completeness rejected");
        }
        Set<URI> uris = new HashSet<>();
        Set<String> names = new HashSet<>();
        long declared = 0;
        for (SelectedFile file : files) {
            if (file.kind() != Kind.FILE) { throw new SecurityException("Directory or unknown source rejected"); }
            selection.requireSelected(file.sourceUri());
            String name = PathBoundary.name(file.name(), limits.maxNameBytes());
            if (!uris.add(file.sourceUri()) || !names.add(name.toLowerCase(Locale.ROOT))) {
                throw new SecurityException("Duplicate selected URI or file name");
            }
            if (file.mimeType() == null || !file.mimeType().matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")) {
                throw new SecurityException("An explicit MIME type is required");
            }
            if (file.declaredBytes() < -1 || file.declaredBytes() > limits.maxFileBytes()) {
                throw new IOException("Declared selected file size rejected");
            }
            if (file.declaredBytes() >= 0) {
                if (file.declaredBytes() > limits.maxTotalBytes() - declared
                        || file.declaredBytes() > limits.diskQuotaBytes() - storedBytes - declared) {
                    throw new IOException("Declared total byte quota exceeded");
                }
                declared += file.declaredBytes();
            }
            if (file.expectedSha256() != null && !file.expectedSha256().matches("[a-fA-F0-9]{64}")) {
                throw new SecurityException("Malformed expected SHA-256");
            }
            Objects.requireNonNull(file.input(), "input");
        }
        if (!uris.equals(selection.selectedFiles())) { throw new SecurityException("Incomplete explicit selection"); }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private static String hashFile(Path path, Cancellation cancellation) throws IOException {
        PathBoundary.regularFile(path);
        MessageDigest hash = sha256();
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                cancellation.check();
                hash.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    /** Cleanup targets only names created by this transaction, without traversal or broad glob deletion. */
    private static void removeOwned(Path directory, List<String> names) throws IOException {
        PathBoundary.directory(directory);
        for (String name : names) {
            Path path = directory.resolve(name);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { continue; }
            PathBoundary.regularFile(path);
            if (!path.toFile().setWritable(true, true)) { throw new IOException("Could not release owned staged file"); }
            Files.delete(path);
        }
        Files.delete(directory);
    }

    private void requireOpen() {
        if (closed) { throw new IllegalStateException("Staging scope is closed"); }
    }

    @Override public void close() throws IOException {
        closed = true;
        Cancellation cancellation = active;
        if (cancellation != null) { cancellation.cancel(); }
        synchronized (this) {
            IOException failure = null;
            for (Generation generation : List.copyOf(generations)) {
                try { generation.close(); }
                catch (IOException error) { if (failure == null) { failure = error; } else { failure.addSuppressed(error); } }
            }
            current = null;
            if (failure != null) { throw failure; }
            if (Files.exists(session, LinkOption.NOFOLLOW_LINKS)) {
                PathBoundary.directory(session);
                Files.delete(session);
            }
        }
    }

    /** Published catalog is immutable and exposes read streams, with revocation on release/owner close. */
    public final class Generation implements AutoCloseable {
        private final Path directory;
        private final List<FileInfo> files;
        private final long bytes;
        private final Set<InputStream> readers = ConcurrentHashMap.newKeySet();
        private boolean released;
        private Generation(Path directory, List<FileInfo> files, long bytes) {
            this.directory = directory;
            this.files = files;
            this.bytes = bytes;
        }
        public String id() { return directory.getFileName().toString(); }
        public List<FileInfo> files() { return files; }
        public GuestShareSpec guestShareSpec() throws IOException {
            synchronized (ReadOnlyStager.this) {
                requireAvailable();
                PathBoundary.directory(directory);
                for (FileInfo file : files) { PathBoundary.regularFile(directory.resolve(file.name())); }
                return new GuestShareSpec(id(), files.stream().map(FileInfo::name).toList(),
                        "downloads", "/mnt/downloads", true, MountStatus.UNVERIFIED);
            }
        }
        public InputStream open(String name) throws IOException {
            synchronized (ReadOnlyStager.this) {
                requireAvailable();
                PathBoundary.name(name, limits.maxNameBytes());
                if (files.stream().noneMatch(file -> file.name().equals(name))) {
                    throw new SecurityException("File is not in this selected generation");
                }
                PathBoundary.directory(directory);
                Path path = directory.resolve(name);
                PathBoundary.regularFile(path);
                InputStream raw = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                InputStream stream = new FilterInputStream(raw) {
                    @Override public void close() throws IOException {
                        try { super.close(); } finally { readers.remove(this); }
                    }
                };
                readers.add(stream);
                return stream;
            }
        }
        public void write(String name, byte[] bytes) { throw new ReadOnlyFileSystemException(); }
        private void requireAvailable() {
            requireOpen();
            if (released) { throw new IllegalStateException("Generation is released"); }
        }
        @Override public void close() throws IOException {
            synchronized (ReadOnlyStager.this) {
                if (released) { return; }
                IOException failure = null;
                for (InputStream reader : readers) {
                    try { reader.close(); } catch (IOException error) { failure = error; }
                }
                if (failure != null) { throw failure; }
                removeOwned(directory, files.stream().map(FileInfo::name).toList());
                released = true;
                generations.remove(this);
                storedBytes -= bytes;
                if (current == this) { current = null; }
            }
        }
    }
}
