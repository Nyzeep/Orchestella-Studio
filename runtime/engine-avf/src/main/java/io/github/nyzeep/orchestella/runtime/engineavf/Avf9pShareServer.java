package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Original, bounded read-only 9P2000.L server on the existing independent data stream.
 * Each call owns one negotiated session and releases its fids and directory anchor on every exit.
 * The transport owner retains ownership of the input/output streams.
 *
 * <p>Filesystem access requires SecureDirectoryStream. The export root and all its ancestors are
 * opened without following links; all subsequent lookup/open operations are relative to pinned
 * directory handles. Symlinks and special files are refused. A provider without secure relative
 * directory operations returns ENOSYS at attach, without a path-based fallback. The pinned root
 * defines this session's export, including if the host later renames that directory.
 *
 * <p>This does not grant host access, verify a Downloads grant, or guarantee an immutable host
 * snapshot. The caller must supply the already authorized root. Host-created hard links and
 * concurrent changes to regular-file contents remain properties of that authorized tree.
 * Server requests are processed serially; Tflush acknowledges only after the preceding request
 * has finished, so no response for that old tag can follow Rflush.
 */
public final class Avf9pShareServer {
    private static final byte T_VERSION = 100, R_VERSION = 101, T_AUTH = 102;
    private static final byte T_ATTACH = 104, R_ATTACH = 105;
    private static final byte R_LERROR = 7, T_STATFS = 8, R_STATFS = 9;
    private static final byte T_LOPEN = 12, R_LOPEN = 13, T_LCREATE = 14;
    private static final byte T_SYMLINK = 16, T_MKNOD = 18, T_RENAME = 20;
    private static final byte T_GETATTR = 24, R_GETATTR = 25, T_SETATTR = 26;
    private static final byte T_XATTRCREATE = 32, T_READDIR = 40, R_READDIR = 41;
    private static final byte T_FSYNC = 50, R_FSYNC = 51, T_LINK = 70, T_MKDIR = 72;
    private static final byte T_UNLINKAT = 74, T_RENAMEAT = 76;
    private static final byte T_FLUSH = 108, R_FLUSH = 109, T_WALK = 110, R_WALK = 111;
    private static final byte T_READ = 116, R_READ = 117, T_WRITE = 118;
    private static final byte T_CLUNK = 120, R_CLUNK = 121, T_REMOVE = 122;
    private static final int ENOENT = 2, EIO = 5, EBADF = 9, EACCES = 13, EBUSY = 16;
    private static final int ENOTDIR = 20, EISDIR = 21, EINVAL = 22, EMFILE = 24, EROFS = 30, ENOSYS = 38;
    private static final int HEADER_BYTES = 7, MIN_MESSAGE_SIZE = 256, MAX_MESSAGE_SIZE = 65536;
    private static final int MAX_FIDS = 256, MAX_QIDS = 4096, MAX_DIRECTORY_ENTRIES = 4096;
    private static final int MAX_PATH_COMPONENTS = 64;
    private static final long NOFID = 0xffffffffL;
    private static final int O_ACCMODE = 3, O_CREAT = 0x40, O_EXCL = 0x80;
    private static final int O_TRUNC = 0x200, O_APPEND = 0x400, O_TMPFILE = 0x410000;
    private final SharedDirectoryContract.SharedDirectorySpec spec;
    private final Path root;

    public Avf9pShareServer(SharedDirectoryContract.SharedDirectorySpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.root = Paths.get(spec.hostPath()).toAbsolutePath().normalize();
    }

    private static final class Fid {
        final List<String> names;
        boolean directory;
        SeekableByteChannel channel;
        List<String> entries;
        boolean open;
        Fid(List<String> names, boolean directory) {
            this.names = List.copyOf(names);
            this.directory = directory;
        }
        void close() { closeQuietly(channel); channel = null; entries = null; open = false; }
    }

    private static final class Session {
        final Map<Long, Fid> fids = new HashMap<>();
        final Map<List<String>, Long> qids = new HashMap<>();
        SecureDirectoryStream<Path> anchor;
        int messageSize = MAX_MESSAGE_SIZE;
        boolean negotiated;
        long nextQid = 1;
        Fid fid(long number) {
            Fid fid = fids.get(number);
            if (fid == null) throw new ServerError(EBADF);
            return fid;
        }
        void newFid(long number) {
            if (number == NOFID) throw new ServerError(EINVAL);
            if (fids.containsKey(number)) throw new ServerError(EBUSY);
            if (fids.size() >= MAX_FIDS) throw new ServerError(EMFILE);
        }
        long qid(List<String> names) {
            Long existing = qids.get(names);
            if (existing != null) return existing;
            if (qids.size() >= MAX_QIDS) throw new ServerError(EMFILE);
            long value = nextQid++;
            qids.put(List.copyOf(names), value);
            return value;
        }
        void clear() {
            for (Fid fid : fids.values()) fid.close();
            fids.clear(); qids.clear(); nextQid = 1;
            closeQuietly(anchor); anchor = null;
        }
    }

    private static final class ServerError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int errno;
        ServerError(int errno) { this.errno = errno; }
    }

    public void serve(InputStream inbound, OutputStream outbound) throws IOException {
        Objects.requireNonNull(inbound, "inbound");
        Objects.requireNonNull(outbound, "outbound");
        Session session = new Session();
        try {
            while (true) {
                byte[] header = readFully(inbound, 4, true);
                if (header == null) return;
                long size = Integer.toUnsignedLong(leInt(header, 0));
                if (size < HEADER_BYTES || size > session.messageSize) {
                    throw new IOException("invalid 9p frame size");
                }
                byte[] message = readFully(inbound, (int) size - 4, false);
                byte type = message[0];
                int tag = (message[1] & 255) | ((message[2] & 255) << 8);
                byte[] response;
                try {
                    if (type == T_VERSION) {
                        session.clear();
                        session.negotiated = false;
                    }
                    if ((type == T_VERSION) != (tag == 65535)) throw new ServerError(EINVAL);
                    if (type != T_VERSION && !session.negotiated) throw new ServerError(EINVAL);
                    validatePayload(type, message);
                    response = dispatch(session, type, message);
                } catch (ServerError error) {
                    response = lerror(error.errno);
                } catch (IOException error) {
                    response = lerror(error instanceof NoSuchFileException ? ENOENT : EACCES);
                } catch (DirectoryIteratorException error) {
                    response = lerror(EIO);
                } catch (UnsupportedOperationException | SecurityException error) {
                    response = lerror(ENOSYS);
                }
                if (response.length > session.messageSize) response = lerror(EINVAL);
                response[5] = message[1]; response[6] = message[2];
                outbound.write(response);
                outbound.flush();
            }
        } finally {
            session.clear();
        }
    }

    private static void validatePayload(byte type, byte[] message) {
        Reader r = new Reader(message);
        switch (type) {
            case T_VERSION: r.u32(); r.string(); break;
            case T_ATTACH: r.u32(); r.u32(); r.string(); r.string(); r.u32(); break;
            case T_WALK:
                r.u32(); r.u32(); int count = r.u16();
                if (count > 16) throw new ServerError(EINVAL);
                for (int i = 0; i < count; i++) r.string();
                break;
            case T_LOPEN: case T_FSYNC: r.u32(); r.u32(); break;
            case T_READ: case T_READDIR: r.u32(); r.i64(); r.u32(); break;
            case T_GETATTR: r.u32(); r.i64(); break;
            case T_STATFS: case T_CLUNK: r.u32(); break;
            case T_FLUSH: r.u16(); break;
            default: return; // unknown/write operations are refused before host I/O
        }
        r.end();
    }

    private byte[] dispatch(Session s, byte type, byte[] message) throws IOException {
        Reader r = new Reader(message);
        switch (type) {
            case T_VERSION: return version(s, r);
            case T_ATTACH: return attach(s, r);
            case T_WALK: return walk(s, r);
            case T_LOPEN: return lopen(s, r);
            case T_READ: return read(s, r);
            case T_READDIR: return readdir(s, r);
            case T_GETATTR: return getattr(s, r);
            case T_STATFS:
                s.fid(r.u32());
                // No path-based FileStore query outside the pinned export. Capacity is unknown.
                return new Writer(R_STATFS).u32(0).u32(4096).i64(0).i64(0).i64(0)
                        .i64(0).i64(0).i64(0x646c3970L).u32(255).frame();
            case T_CLUNK:
                long id = r.u32(); Fid fid = s.fids.remove(id);
                if (fid == null) throw new ServerError(EBADF);
                fid.close(); return new Writer(R_CLUNK).frame();
            case T_FLUSH: r.u16(); return new Writer(R_FLUSH).frame();
            case T_FSYNC: s.fid(r.u32()); r.u32(); return new Writer(R_FSYNC).frame();
            case T_REMOVE:
                // 9p remove consumes its fid even when the read-only removal fails.
                if (message.length == 7) {
                    Fid removed = s.fids.remove(r.u32());
                    if (removed != null) removed.close();
                }
                throw new ServerError(EROFS);
            case T_WRITE: case T_LCREATE: case T_SYMLINK: case T_MKNOD: case T_RENAME:
            case T_SETATTR: case T_XATTRCREATE: case T_LINK: case T_MKDIR:
            case T_UNLINKAT: case T_RENAMEAT:
            case 114: case 126: // legacy create/wstat, never mutable
                throw new ServerError(EROFS);
            case T_AUTH: default: throw new ServerError(ENOSYS);
        }
    }

    private static byte[] version(Session s, Reader r) {
        long requested = r.u32(); String version = r.string();
        if (requested < MIN_MESSAGE_SIZE) throw new ServerError(EINVAL);
        s.clear();
        s.messageSize = (int) Math.min(requested, MAX_MESSAGE_SIZE);
        s.negotiated = "9P2000.L".equals(version);
        return new Writer(R_VERSION).u32(s.messageSize).string(s.negotiated ? "9P2000.L" : "unknown").frame();
    }

    private byte[] attach(Session s, Reader r) throws IOException {
        long id = r.u32(), afid = r.u32(); r.string(); String name = r.string(); r.u32();
        if (afid != NOFID) throw new ServerError(EINVAL);
        if (!spec.mountTag().equals(name) && !"/".equals(name)) throw new ServerError(ENOENT);
        s.newFid(id);
        if (s.anchor == null) s.anchor = openAnchor();
        List<String> names = List.of();
        BasicFileAttributes attr = attributes(s, names);
        Writer response = new Writer(R_ATTACH).qid(s, names, attr);
        s.fids.put(id, new Fid(names, true));
        return response.frame();
    }

    /** Pin the root from the filesystem anchor, refusing symlinks in every ancestor. */
    private SecureDirectoryStream<Path> openAnchor() throws IOException {
        DirectoryStream<Path> opened = Files.newDirectoryStream(root.getRoot());
        if (!(opened instanceof SecureDirectoryStream<?>)) {
            opened.close(); throw new ServerError(ENOSYS);
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) opened;
        boolean transferred = false;
        try {
            for (Path part : root) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
                SecureDirectoryStream<Path> previous = current;
                current = next;
                previous.close();
            }
            transferred = true;
            return current;
        } finally {
            if (!transferred) closeQuietly(current);
        }
    }

    private static final class DirectoryHandle implements AutoCloseable {
        final SecureDirectoryStream<Path> stream;
        final boolean owned;
        DirectoryHandle(SecureDirectoryStream<Path> stream, boolean owned) { this.stream = stream; this.owned = owned; }
        @Override public void close() throws IOException { if (owned) stream.close(); }
    }

    private static DirectoryHandle directory(Session s, List<String> names) throws IOException {
        if (s.anchor == null) throw new ServerError(EBADF);
        SecureDirectoryStream<Path> current = s.anchor;
        boolean owned = false, transferred = false;
        try {
            for (String name : names) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(Paths.get(name), LinkOption.NOFOLLOW_LINKS);
                if (owned) closeQuietly(current);
                current = next; owned = true;
            }
            transferred = true;
            return new DirectoryHandle(current, owned);
        } finally {
            if (!transferred && owned) closeQuietly(current);
        }
    }

    private static BasicFileAttributes attributes(Session s, List<String> names) throws IOException {
        if (names.isEmpty()) return s.anchor.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
        try (DirectoryHandle parent = directory(s, names.subList(0, names.size() - 1))) {
            BasicFileAttributes attr = parent.stream.getFileAttributeView(Paths.get(names.get(names.size() - 1)),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS).readAttributes();
            if (attr.isSymbolicLink() || (!attr.isDirectory() && !attr.isRegularFile())) throw new ServerError(EACCES);
            return attr;
        }
    }

    private static void validName(String name) {
        if (name.isEmpty() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.indexOf(':') >= 0
                || name.getBytes(StandardCharsets.UTF_8).length > 255) throw new ServerError(ENOENT);
    }

    private static byte[] walk(Session s, Reader r) throws IOException {
        long id = r.u32(), newId = r.u32(); int count = r.u16();
        Fid source = s.fid(id);
        if (source.open) throw new ServerError(EBUSY);
        if (newId != id) s.newFid(newId);
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < count; i++) { String name = r.string(); validName(name); steps.add(name); }
        List<String> names = new ArrayList<>(source.names);
        BasicFileAttributes attr = attributes(s, names);
        List<long[]> walked = new ArrayList<>();
        for (String step : steps) {
            try {
                if (!attr.isDirectory()) throw new ServerError(ENOTDIR);
                if ("..".equals(step)) { if (!names.isEmpty()) names.remove(names.size() - 1); }
                else if (!".".equals(step)) {
                    if (names.size() >= MAX_PATH_COMPONENTS) throw new ServerError(EINVAL);
                    names.add(step);
                }
                attr = attributes(s, names);
                walked.add(qid(s, names, attr));
            } catch (IOException | ServerError error) {
                if (walked.isEmpty()) throw error;
                break;
            }
        }
        Writer response = new Writer(R_WALK).u16(walked.size());
        for (long[] value : walked) response.qid(value);
        // A partial walk does not establish or alter newfid.
        if (walked.size() == count) {
            Fid previous = s.fids.put(newId, new Fid(names, attr.isDirectory()));
            if (previous != null) previous.close();
        }
        return response.frame();
    }

    private static byte[] lopen(Session s, Reader r) throws IOException {
        Fid fid = s.fid(r.u32()); long flags = r.u32();
        if ((flags & O_ACCMODE) != 0 || (flags & (O_CREAT | O_EXCL | O_TRUNC | O_APPEND)) != 0
                || (flags & O_TMPFILE) == O_TMPFILE) throw new ServerError(EROFS);
        if (fid.open) throw new ServerError(EBUSY);
        BasicFileAttributes attr = attributes(s, fid.names);
        fid.directory = attr.isDirectory();
        if (fid.directory) {
            fid.entries = listing(s, fid.names);
        } else {
            SeekableByteChannel opened = null;
            try {
                try (DirectoryHandle parent = directory(s, fid.names.subList(0, fid.names.size() - 1))) {
                    opened = parent.stream.newByteChannel(Paths.get(fid.names.get(fid.names.size() - 1)),
                            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                }
                fid.channel = opened;
                opened = null; // transfer ownership only after the directory handle closes
            } finally {
                closeQuietly(opened);
            }
        }
        try {
            Writer response = new Writer(R_LOPEN).qid(s, fid.names, attr).u32(0);
            fid.open = true;
            return response.frame();
        } catch (RuntimeException error) {
            fid.close(); throw error;
        }
    }

    private static byte[] read(Session s, Reader r) throws IOException {
        Fid fid = s.fid(r.u32()); long offset = r.i64(), count = r.u32();
        if (offset < 0) throw new ServerError(EINVAL);
        if (fid.directory) throw new ServerError(EISDIR);
        if (!fid.open || fid.channel == null) throw new ServerError(EBADF);
        int capacity = (int) Math.min(count, s.messageSize - 11L);
        ByteBuffer bytes = ByteBuffer.allocate(capacity);
        fid.channel.position(offset);
        int read = capacity == 0 ? 0 : fid.channel.read(bytes);
        bytes.flip();
        byte[] data = new byte[Math.max(read, 0)]; bytes.get(data);
        return new Writer(R_READ).u32(data.length).bytes(data).frame();
    }

    private static List<String> listing(Session s, List<String> names) throws IOException {
        List<String> entries = new ArrayList<>();
        try (DirectoryHandle parent = directory(s, names);
                SecureDirectoryStream<Path> scan = parent.stream.newDirectoryStream(Paths.get("."), LinkOption.NOFOLLOW_LINKS)) {
            int inspected = 0;
            for (Path entry : scan) {
                if (++inspected > MAX_DIRECTORY_ENTRIES) throw new ServerError(EMFILE);
                String name = entry.getFileName().toString(); validName(name);
                BasicFileAttributes attr = scan.getFileAttributeView(Paths.get(name), BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!attr.isSymbolicLink() && (attr.isDirectory() || attr.isRegularFile())) entries.add(name);
            }
        }
        Collections.sort(entries);
        return entries;
    }

    private static byte[] readdir(Session s, Reader r) throws IOException {
        Fid fid = s.fid(r.u32()); long offset = r.i64(), count = r.u32();
        if (offset < 0) throw new ServerError(EINVAL);
        if (!fid.directory) throw new ServerError(ENOTDIR);
        if (!fid.open || fid.entries == null) throw new ServerError(EBADF);
        int capacity = (int) Math.min(count, s.messageSize - 11L);
        Writer entries = new Writer();
        if (offset < fid.entries.size()) {
            for (int index = (int) offset; index < fid.entries.size(); index++) {
                String name = fid.entries.get(index);
                int size = 24 + name.getBytes(StandardCharsets.UTF_8).length;
                if (entries.size() + size > capacity) break;
                List<String> path = new ArrayList<>(fid.names); path.add(name);
                BasicFileAttributes attr = attributes(s, path);
                entries.qid(s, path, attr).i64(index + 1L).u8(attr.isDirectory() ? 4 : 8).string(name);
            }
        }
        return new Writer(R_READDIR).u32(entries.size()).bytes(entries.raw()).frame();
    }

    private static byte[] getattr(Session s, Reader r) throws IOException {
        Fid fid = s.fid(r.u32()); long requested = r.i64();
        BasicFileAttributes attr = attributes(s, fid.names);
        long size = attr.isDirectory() ? 0 : (fid.channel == null ? attr.size() : fid.channel.size());
        Writer response = new Writer(R_GETATTR).i64(requested & 0x77fL).qid(s, fid.names, attr)
                .u32(attr.isDirectory() ? 0x416d : 0x8124).u32(0).u32(0)
                .i64(attr.isDirectory() ? 2 : 1).i64(0).i64(size).i64(4096).i64(size / 512 + (size % 512 == 0 ? 0 : 1));
        timestamp(response, attr.lastAccessTime()); timestamp(response, attr.lastModifiedTime());
        // BasicFileAttributes has no POSIX ctime; ctime/btime/gen/data_version are not advertised.
        response.i64(0).i64(0).i64(0).i64(0).i64(0).i64(0);
        return response.frame();
    }

    private static void timestamp(Writer w, FileTime time) {
        java.time.Instant instant = time.toInstant(); w.i64(instant.getEpochSecond()).i64(instant.getNano());
    }
    private static long[] qid(Session s, List<String> names, BasicFileAttributes attr) {
        return new long[] {attr.isDirectory() ? 128 : 0, attr.lastModifiedTime().toMillis() / 1000, s.qid(names)};
    }
    private static byte[] lerror(int errno) { return new Writer(R_LERROR).u32(errno).frame(); }
    private static void closeQuietly(AutoCloseable resource) {
        if (resource != null) {
            try { resource.close(); } catch (Exception ignored) { /* all other owners still close */ }
        }
    }

    private static byte[] readFully(InputStream input, int length, boolean boundaryAllowed) throws IOException {
        byte[] bytes = new byte[length]; int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) {
                if (offset == 0 && boundaryAllowed) return null;
                throw new IOException("share stream ended inside a message");
            }
            if (count == 0) throw new IOException("share stream made no progress");
            offset += count;
        }
        return bytes;
    }
    private static int leInt(byte[] b, int i) {
        return (b[i] & 255) | ((b[i + 1] & 255) << 8) | ((b[i + 2] & 255) << 16) | ((b[i + 3] & 255) << 24);
    }
    private static final class Reader {
        final byte[] bytes; int at = 3;
        Reader(byte[] bytes) { this.bytes = bytes; }
        void require(int count) { if (count < 0 || count > bytes.length - at) throw new ServerError(EINVAL); }
        void end() { if (at != bytes.length) throw new ServerError(EINVAL); }
        long u32() { require(4); long value = Integer.toUnsignedLong(leInt(bytes, at)); at += 4; return value; }
        int u16() { require(2); int value = (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8); at += 2; return value; }
        long i64() { long low = u32(), high = u32(); return low | (high << 32); }
        String string() {
            int length = u16(); require(length);
            try {
                String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, at, length)).toString();
                at += length;
                if (value.indexOf('\0') >= 0) throw new ServerError(EINVAL);
                return value;
            } catch (CharacterCodingException error) { throw new ServerError(EINVAL); }
        }
    }
    private static final class Writer {
        final ByteBuffer buffer = ByteBuffer.allocate(MAX_MESSAGE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        Writer() {}
        Writer(byte type) { buffer.put(type).putShort((short) 0); }
        void require(int bytes) { if (bytes < 0 || bytes > buffer.remaining() - 4) throw new ServerError(EINVAL); }
        Writer u8(int value) { require(1); buffer.put((byte) value); return this; }
        Writer u16(int value) { require(2); buffer.putShort((short) value); return this; }
        Writer u32(long value) { require(4); buffer.putInt((int) value); return this; }
        Writer i64(long value) { require(8); buffer.putLong(value); return this; }
        Writer bytes(byte[] bytes) { require(bytes.length); buffer.put(bytes); return this; }
        Writer string(String text) { byte[] value = text.getBytes(StandardCharsets.UTF_8); if (value.length > 65535) throw new ServerError(EINVAL); return u16(value.length).bytes(value); }
        Writer qid(Session s, List<String> names, BasicFileAttributes attr) { return qid(Avf9pShareServer.qid(s, names, attr)); }
        Writer qid(long[] value) { return u8((int) value[0]).u32(value[1]).i64(value[2]); }
        int size() { return buffer.position(); }
        byte[] raw() { ByteBuffer view = buffer.duplicate(); view.flip(); byte[] result = new byte[view.remaining()]; view.get(result); return result; }
        byte[] frame() { byte[] body = raw(); return ByteBuffer.allocate(4 + body.length).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + body.length).put(body).array(); }
    }
}
