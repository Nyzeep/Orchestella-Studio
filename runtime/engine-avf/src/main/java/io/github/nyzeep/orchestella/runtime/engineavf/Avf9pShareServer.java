package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The self-built read-only 9P2000.L file server behind the AVF data plane
 * (ADR-0009 control/data separation, ADR-0010 read-only exposure whitelist,
 * ADR-0014 self-engineering stance: this is an original implementation from
 * the 9p behavior specification — no third-party 9p code is embedded).
 *
 * <p>Transport-agnostic by design: one 9p session runs over any byte stream
 * the caller supplies (the AVF engine supplies the vsock share channel; the
 * guest kernel's 9p {@code trans=fd} mount is the client). The served tree is
 * the shared directory's host path, and every state-changing operation is
 * rejected with {@code EROFS} — the same physical read-only boundary the
 * QEMU strategy gets from {@code readonly=on} virtio-9p. Path resolution is
 * containment-checked at every walk step so a name can never escape the
 * shared directory (including through symlinks).
 *
 * <p>Implemented 9P2000.L operations (the key set for read-only browsing):
 * version, attach, walk, lopen, read, readdir, getattr, statfs, clunk,
 * flush, fsync; write-class operations (write/lcreate/mkdir/mknod/symlink/
 * link/rename/renameat/unlinkat/remove/setattr) are answered {@code EROFS};
 * everything else is answered {@code ENOSYS}.
 */
public final class Avf9pShareServer {
    /** Wire message types (9P2000.L numbering). */
    private static final byte T_VERSION = 100;
    private static final byte R_VERSION = 101;
    private static final byte T_AUTH = 102;
    private static final byte R_LERROR = 7;
    private static final byte T_STATFS = 8;
    private static final byte R_STATFS = 9;
    private static final byte T_LOPEN = 12;
    private static final byte R_LOPEN = 13;
    private static final byte T_LCREATE = 14;
    private static final byte T_SYMLINK = 16;
    private static final byte T_MKNOD = 18;
    private static final byte T_RENAME = 20;
    private static final byte T_GETATTR = 24;
    private static final byte R_GETATTR = 25;
    private static final byte T_SETATTR = 26;
    private static final byte T_XATTRWALK = 30;
    private static final byte T_XATTRCREATE = 32;
    private static final byte T_READDIR = 40;
    private static final byte R_READDIR = 41;
    private static final byte T_FSYNC = 50;
    private static final byte R_FSYNC = 51;
    private static final byte T_LINK = 70;
    private static final byte T_MKDIR = 72;
    private static final byte T_UNLINKAT = 74;
    private static final byte T_RENAMEAT = 76;
    private static final byte T_FLUSH = 108;
    private static final byte R_FLUSH = 109;
    private static final byte T_WALK = 110;
    private static final byte R_WALK = 111;
    private static final byte T_READ = 116;
    private static final byte R_READ = 117;
    private static final byte T_WRITE = 118;
    private static final byte T_CLUNK = 120;
    private static final byte R_CLUNK = 121;
    private static final byte T_REMOVE = 122;
    private static final byte T_ATTACH = (byte) 132;
    private static final byte R_ATTACH = (byte) 133;

    /** errno values carried in Rlerror. */
    private static final int EPERM = 1;
    private static final int ENOENT = 2;
    private static final int EBADF = 9;
    private static final int EACCES = 13;
    private static final int ENOTDIR = 20;
    private static final int EISDIR = 21;
    private static final int EINVAL = 22;
    private static final int EROFS = 30;
    private static final int ENOSYS = 38;

    private static final int HEADER_BYTES = 7; // size[4] + type[1]
    private static final int IOHDRSZ = 24; // .L per-request overhead the kernel reserves
    private static final int MAX_MESSAGE_SIZE = 65536;
    private static final int HARD_MESSAGE_LIMIT = 1 << 20;
    private static final int NOFID = -1; // read as unsigned 0xFFFFFFFF
    private static final int QTDIR = 0x80;
    private static final int QTFILE = 0x00;
    private static final int QTSYMLINK = 0x03;
    private static final int MODE_DIR = 0x4000;
    private static final int MODE_FILE = 0x8000;
    private static final int MODE_SYMLINK = 0xA000;
    private static final int READ_MODE_BITS = 0444;
    private static final int READ_EXECUTE_DIR_BITS = 0555;
    private static final int O_ACCMODE = 0b11;
    private static final int O_RDONLY = 0;
    private static final int O_WRONLY = 1;
    private static final int O_RDWR = 2;
    private static final int O_EXEC = 3;
    private static final int O_CREAT = 0x40;
    private static final int O_TRUNC = 0x200;
    private static final int O_APPEND = 0x400;

    private final SharedDirectoryContract.SharedDirectorySpec spec;
    private final Path root;
    private Path rootReal;

    /** One negotiated 9p session's per-connection state. */
    private final class Session {
        private final Map<Long, Fid> fids = new HashMap<>();
        private final Map<Path, Long> qidPaths = new HashMap<>();
        private final AtomicLong nextQidPath = new AtomicLong(1);
        private int messageSize = MAX_MESSAGE_SIZE;

        Fid fid(long number) {
            Fid fid = fids.get(number);
            if (fid == null) {
                throw new ServerError(EBADF, "unknown fid");
            }
            return fid;
        }

        long qidPathFor(Path contained) {
            return qidPaths.computeIfAbsent(contained, key -> nextQidPath.getAndIncrement());
        }
    }

    /** One open fid: its contained path plus optional open file state. */
    private static final class Fid {
        final Path contained;
        final boolean directory;
        FileChannel channel;
        List<String> directorySnapshot;

        Fid(Path contained, boolean directory) {
            this.contained = contained;
            this.directory = directory;
        }
    }

    /** Internal failure carrying the errno to report in Rlerror. */
    private static final class ServerError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        final int errno;

        ServerError(int errno, String detail) {
            super(detail);
            this.errno = errno;
        }
    }

    public Avf9pShareServer(SharedDirectoryContract.SharedDirectorySpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.root = Paths.get(spec.hostPath());
    }

    /**
     * Serves exactly one 9p session on the given streams until the peer
     * closes the connection. One mount = one session; concurrent sessions
     * would each need their own call.
     */
    public void serve(InputStream inbound, OutputStream outbound) throws IOException {
        Objects.requireNonNull(inbound, "inbound");
        Objects.requireNonNull(outbound, "outbound");
        Session session = new Session();
        while (true) {
            byte[] sizeBytes = readFully(inbound, 4, true);
            if (sizeBytes == null) {
                return; // clean EOF at a message boundary: the mount went away
            }
            int size = leInt(sizeBytes, 0);
            if (size < HEADER_BYTES || size > HARD_MESSAGE_LIMIT) {
                return; // framing corruption: end the session rather than guess
            }
            byte[] message = new byte[size - 4];
            readFully(inbound, message);
            byte type = message[0];
            byte[] response;
            try {
                response = dispatch(session, type, message);
            } catch (ServerError failure) {
                response = lerror(failure.errno);
            }
            outbound.write(response);
            outbound.flush();
        }
    }

    private byte[] dispatch(Session session, byte type, byte[] message) {
        if (type == T_VERSION) {
            return version(session, message);
        }
        if (type == T_AUTH) {
            // No authentication backend: the client attaches with afid=NOFID.
            throw new ServerError(ENOSYS, "auth not required");
        }
        if (type == T_ATTACH) {
            return attach(session, message);
        }
        if (type == T_WALK) {
            return walk(session, message);
        }
        if (type == T_LOPEN) {
            return lopen(session, message);
        }
        if (type == T_READ) {
            return read(session, message);
        }
        if (type == T_READDIR) {
            return readDir(session, message);
        }
        if (type == T_GETATTR) {
            return getattr(session, message);
        }
        if (type == T_STATFS) {
            return statfs(session, message);
        }
        if (type == T_CLUNK) {
            return clunk(session, message);
        }
        if (type == T_FLUSH) {
            return flush(message);
        }
        if (type == T_FSYNC) {
            return fsync(session, message);
        }
        if (type == T_WRITE || type == T_LCREATE || type == T_MKDIR || type == T_MKNOD
                || type == T_SYMLINK || type == T_LINK || type == T_RENAME
                || type == T_RENAMEAT || type == T_UNLINKAT || type == T_REMOVE
                || type == T_SETATTR || type == T_XATTRCREATE) {
            // The physical read-only boundary: every mutating operation is
            // refused before touching the host file system (ADR-0010).
            throw new ServerError(EROFS, "read-only share");
        }
        // Legacy (.u/.9p) operations and anything unimplemented (including
        // xattr queries: the share advertises no extended attributes).
        throw new ServerError(ENOSYS, "unsupported operation " + type);
    }

    private byte[] version(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        int requested = reader.int32();
        String requestedVersion = reader.string();
        session.messageSize = Math.min(Math.max(requested, HEADER_BYTES), MAX_MESSAGE_SIZE);
        session.fids.clear();
        String negotiated = "9P2000.L".equals(requestedVersion) ? "9P2000.L" : "unknown";
        Writer writer = new Writer(R_VERSION);
        writer.int32(session.messageSize);
        writer.string(negotiated);
        return writer.toBytes();
    }

    private byte[] attach(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        long fidNumber = reader.uint32();
        long afid = reader.uint32();
        reader.string(); // uname: identity mapping is fixed (root view)
        String aname = reader.string();
        if (afid != 0xFFFFFFFFL) {
            throw new ServerError(EINVAL, "attach requires afid=NOFID (no auth backend)");
        }
        boolean namedTag = spec.mountTag().equals(aname);
        boolean rootedTag = "/".equals(aname);
        if (!namedTag && !rootedTag) {
            throw new ServerError(ENOENT, "unknown attach name " + aname);
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServerError(ENOENT, "shared directory is unavailable");
        }
        try {
            rootReal = root.toRealPath();
        } catch (IOException unavailable) {
            throw new ServerError(ENOENT, "shared directory is unavailable");
        }
        if (session.fids.containsKey(fidNumber)) {
            throw new ServerError(EINVAL, "fid already in use");
        }
        session.fids.put(fidNumber, new Fid(root, true));
        Writer writer = new Writer(R_ATTACH);
        writer.qid(qidOf(session, root, true, false));
        return writer.toBytes();
    }

    private byte[] walk(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        long fidNumber = reader.uint32();
        long newFidNumber = reader.uint32();
        int nameCount = reader.uint16();
        Fid current = session.fid(fidNumber);
        if (nameCount > 16) {
            throw new ServerError(EACCES, "too many walk elements");
        }
        if (newFidNumber != fidNumber && session.fids.containsKey(newFidNumber)) {
            throw new ServerError(EINVAL, "new fid already in use");
        }
        Path walked = current.contained;
        List<long[]> walkedQids = new ArrayList<>();
        for (int index = 0; index < nameCount; index++) {
            String element = reader.string();
            Path next;
            try {
                next = resolveWalkStep(walked, element);
            } catch (ServerError stepFailure) {
                if (walkedQids.isEmpty()) {
                    throw stepFailure;
                }
                break; // partial walk: the client sees fewer qids than names
            }
            boolean directory = Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS);
            boolean symbolic = Files.isSymbolicLink(next);
            walked = next;
            walkedQids.add(qidOf(session, walked, directory, symbolic));
        }
        Fid result = new Fid(walked, Files.isDirectory(walked, LinkOption.NOFOLLOW_LINKS));
        if (newFidNumber == fidNumber) {
            session.fids.put(fidNumber, result);
        } else {
            session.fids.put(newFidNumber, result);
        }
        Writer writer = new Writer(R_WALK);
        writer.uint16(walkedQids.size());
        for (long[] qid : walkedQids) {
            writer.qid(qid);
        }
        return writer.toBytes();
    }

    private byte[] lopen(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        long fidNumber = reader.uint32();
        int flags = reader.int32();
        Fid fid = session.fid(fidNumber);
        int accessMode = flags & O_ACCMODE;
        boolean writeIntent = accessMode == O_WRONLY || accessMode == O_RDWR
                || (flags & (O_TRUNC | O_APPEND | O_CREAT)) != 0;
        if (writeIntent) {
            throw new ServerError(EROFS, "read-only share");
        }
        if (fid.directory && accessMode != O_RDONLY) {
            throw new ServerError(EISDIR, "directory requires read-only open");
        }
        long[] qid = qidOf(session, fid.contained, fid.directory, isSymlink(fid.contained));
        if (fid.directory) {
            fid.directorySnapshot = listContained(fid.contained);
        } else {
            try {
                fid.channel = FileChannel.open(fid.contained, StandardOpenOption.READ);
            } catch (IOException unreadable) {
                throw new ServerError(EACCES, "file is not readable");
            }
        }
        Writer writer = new Writer(R_LOPEN);
        writer.qid(qid);
        writer.int32(0); // iounit 0: the client derives it from msize - IOHDRSZ
        return writer.toBytes();
    }

    private byte[] read(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        long fidNumber = reader.uint32();
        long offset = reader.int64();
        int requested = reader.int32();
        Fid fid = session.fid(fidNumber);
        if (fid.directory) {
            throw new ServerError(EISDIR, "directories are read with readdir");
        }
        if (fid.channel == null) {
            throw new ServerError(EBADF, "fid is not open");
        }
        int capacity = Math.min(requested, session.messageSize - 9);
        if (capacity <= 0) {
            throw new ServerError(EINVAL, "invalid read size");
        }
        ByteBuffer buffer = ByteBuffer.allocate(capacity);
        int count;
        try {
            count = fid.channel.read(buffer, offset);
        } catch (IOException unreadable) {
            throw new ServerError(EACCES, "read failed");
        }
        byte[] data = new byte[Math.max(count, 0)];
        buffer.flip();
        buffer.get(data);
        Writer writer = new Writer(R_READ);
        writer.int32(data.length);
        writer.bytes(data);
        return writer.toBytes();
    }

    private byte[] readDir(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        long fidNumber = reader.uint32();
        long offset = reader.int64();
        int requested = reader.int32();
        Fid fid = session.fid(fidNumber);
        if (!fid.directory) {
            throw new ServerError(ENOTDIR, "readdir requires a directory");
        }
        if (requested <= 0) {
            throw new ServerError(EINVAL, "invalid readdir size");
        }
        if (fid.directorySnapshot == null) {
            fid.directorySnapshot = listContained(fid.contained);
        }
        List<String> snapshot = fid.directorySnapshot;
        int capacity = Math.min(requested, session.messageSize - 9);
        byte[] body = new byte[capacity];
        int used = 0;
        int index = (int) Math.max(offset, 0);
        while (index < snapshot.size()) {
            String name = snapshot.get(index);
            Path entry = resolveWalkStep(fid.contained, name);
            boolean directory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
            boolean symbolic = Files.isSymbolicLink(entry);
            int entrySize = 13 + 8 + 1 + 2 + utf8Length(name);
            if (used + entrySize > capacity) {
                break;
            }
            Writer entryWriter = new Writer();
            entryWriter.qid(qidOf(session, entry, directory, symbolic));
            entryWriter.int64(index + 1); // resume offset: the next entry's index
            entryWriter.uint8(directory ? QTDIR : (symbolic ? QTSYMLINK : QTFILE));
            entryWriter.string(name);
            byte[] encoded = entryWriter.rawBytes();
            System.arraycopy(encoded, 0, body, used, encoded.length);
            used += encoded.length;
            index++;
        }
        Writer writer = new Writer(R_READDIR);
        writer.int32(used);
        writer.append(body, used);
        return writer.toBytes();
    }

    private byte[] getattr(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        long fidNumber = reader.uint32();
        reader.int64(); // request_mask: we always answer with the basic set
        Fid fid = session.fid(fidNumber);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    fid.contained, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unreadable) {
            throw new ServerError(ENOENT, "attributes unavailable");
        }
        boolean directory = attributes.isDirectory();
        long size = directory ? 0 : attributes.size();
        long mode = directory
                ? (MODE_DIR | READ_EXECUTE_DIR_BITS)
                : (Files.isSymbolicLink(fid.contained)
                        ? (MODE_SYMLINK | 0777)
                        : (MODE_FILE | READ_MODE_BITS));
        Writer writer = new Writer(R_GETATTR);
        writer.int64(0x7df); // valid: mode..blocks except ino (qid.path serves it)
        writer.qid(qidOf(session, fid.contained, directory, isSymlink(fid.contained)));
        writer.int32((int) mode);
        writer.int32(0); // uid: fixed root view
        writer.int32(0); // gid
        writer.int64(directory ? 2 : 1); // nlink
        writer.int64(0); // rdev
        writer.int64(size);
        writer.int64(4096); // blksize
        writer.int64((size + 511) / 512); // blocks
        writer.int64(seconds(attributes.lastAccessTime()));
        writer.int64(seconds(attributes.lastModifiedTime()));
        writer.int64(seconds(attributes.creationTime()));
        writer.int64(0); // btime
        writer.int64(0); // gen
        writer.int64(0); // data_version
        return writer.toBytes();
    }

    private byte[] statfs(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        session.fid(reader.uint32());
        long blocks = 0;
        long free = 0;
        long available = 0;
        try {
            java.nio.file.FileStore store = Files.getFileStore(root);
            blocks = Math.max(store.getTotalSpace() / 4096, 0);
            free = Math.max(store.getUnallocatedSpace() / 4096, 0);
            available = Math.max(store.getUsableSpace() / 4096, 0);
        } catch (IOException unsupported) {
            // statfs stays best-effort: zeros mean "unknown" to the client
        }
        Writer writer = new Writer(R_STATFS);
        writer.int32(0); // fs type: unspecified
        writer.int32(4096); // bsize
        writer.int64(blocks);
        writer.int64(free);
        writer.int64(available);
        writer.int64(0); // files
        writer.int64(0); // ffree
        writer.int64(0x646C3970L); // fsid ("dl9p")
        writer.int32(255); // namelen
        return writer.toBytes();
    }

    private byte[] clunk(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        long fidNumber = reader.uint32();
        Fid fid = session.fids.remove(fidNumber);
        if (fid == null) {
            throw new ServerError(EBADF, "unknown fid");
        }
        closeQuietly(fid.channel);
        return new Writer(R_CLUNK).toBytes();
    }

    private byte[] flush(byte[] message) {
        Reader reader = new Reader(message, 3);
        reader.uint16(); // oldtag: nothing is in flight to cancel
        return new Writer(R_FLUSH).toBytes();
    }

    private byte[] fsync(Session session, byte[] message) {
        Reader reader = new Reader(message, 3);
        Fid fid = session.fid(reader.uint32());
        if (fid.channel != null) {
            try {
                fid.channel.force(false);
            } catch (IOException ignored) {
                // read-only data: nothing dirty to flush
            }
        }
        return new Writer(R_FSYNC).toBytes();
    }

    /**
     * Resolves one walk step inside the shared tree. Containment is enforced
     * both lexically and against real paths, so absolute names, NUL bytes and
     * symlinks pointing outside the share can never escape; {@code ..} is
     * clamped at the share root the way an export root must be.
     */
    private Path resolveWalkStep(Path current, String element) {
        if (element.isEmpty() || element.contains("\0") || element.contains("/")) {
            throw new ServerError(ENOENT, "invalid name");
        }
        if ("..".equals(element)) {
            Path parent = current.getParent();
            if (parent == null || !parent.startsWith(root)) {
                return root; // ".." at the export root stays at the root
            }
            return parent;
        }
        if (".".equals(element)) {
            return current;
        }
        Path target = current.resolve(element).normalize();
        if (!target.startsWith(root)) {
            throw new ServerError(EACCES, "path escapes the shared directory");
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ServerError(ENOENT, "no such file: " + element);
        }
        try {
            Path real = target.toRealPath();
            if (!real.startsWith(rootReal)) {
                throw new ServerError(EACCES, "path escapes the shared directory");
            }
        } catch (IOException unreadable) {
            throw new ServerError(ENOENT, "no such file: " + element);
        }
        return target;
    }

    private List<String> listContained(Path directory) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                names.add(entry.getFileName().toString());
            }
        } catch (IOException unreadable) {
            throw new ServerError(EACCES, "directory is not readable");
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    private boolean isSymlink(Path contained) {
        return Files.isSymbolicLink(contained);
    }

    private long[] qidOf(Session session, Path contained, boolean directory, boolean symbolic) {
            byte type = (byte) (directory ? QTDIR : (symbolic ? QTSYMLINK : QTFILE));
        long version = 0;
        try {
            version = Files.getLastModifiedTime(contained, LinkOption.NOFOLLOW_LINKS)
                    .toMillis() / 1000L;
        } catch (IOException ignored) {
            // a zero version is fine for unreadable metadata
        }
        return new long[] {type, version, session.qidPathFor(contained)};
    }

    private static int seconds(FileTime time) {
        return (int) Math.max(time.toMillis() / 1000L, 0);
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // teardown is best-effort
            }
        }
    }

    private static byte[] lerror(int errno) {
        Writer writer = new Writer(R_LERROR);
        writer.int32(errno);
        return writer.toBytes();
    }

    /**
     * Reads exactly {@code length} bytes. At a clean EOF before the first
     * byte of a frame header, returns null (session end); a mid-frame or
     * mid-message EOF is a protocol fault and throws.
     */
    private static byte[] readFully(InputStream inbound, int length, boolean boundaryAllowed)
            throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = inbound.read(buffer, offset, length - offset);
            if (count < 0) {
                if (offset == 0 && boundaryAllowed) {
                    return null;
                }
                throw new IOException("share stream ended inside a message");
            }
            offset += count;
        }
        return buffer;
    }

    private static void readFully(InputStream inbound, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int count = inbound.read(target, offset, target.length - offset);
            if (count < 0) {
                throw new IOException("share stream ended inside a message");
            }
            offset += count;
        }
    }

    private static int leInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static int utf8Length(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * Little-endian wire cursor over one message. Parsing starts after
     * type[1] + tag[2]: the server answers strictly in order, so tags are
     * skipped rather than tracked.
     */
    private static final class Reader {
        private final byte[] message;
        private int position;

        Reader(byte[] message, int start) {
            this.message = message;
            this.position = start;
        }

        int int32() {
            int value = leInt(message, position);
            position += 4;
            return value;
        }

        long uint32() {
            return int32() & 0xFFFFFFFFL;
        }

        int uint16() {
            int value = (message[position] & 0xFF) | ((message[position + 1] & 0xFF) << 8);
            position += 2;
            return value;
        }

        long int64() {
            long low = uint32();
            long high = uint32();
            return (high << 32) | low;
        }

        String string() {
            int length = uint16();
            String value = new String(message, position, length,
                    java.nio.charset.StandardCharsets.UTF_8);
            position += length;
            return value;
        }
    }

    /** Little-endian wire builder for one response message. */
    private static final class Writer {
        private ByteBuffer buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);

        Writer() {}

        Writer(byte type) {
            buffer.put(type);
        }

        private void ensure(int extra) {
            if (buffer.remaining() < extra) {
                int needed = buffer.position() + extra;
                int grown = Math.max(needed, buffer.capacity() * 2);
                ByteBuffer larger = ByteBuffer.allocate(grown).order(ByteOrder.LITTLE_ENDIAN);
                buffer.flip();
                larger.put(buffer);
                buffer = larger;
            }
        }

        Writer int32(int value) {
            ensure(4);
            buffer.putInt(value);
            return this;
        }

        Writer int64(long value) {
            ensure(8);
            buffer.putLong(value);
            return this;
        }

        Writer uint16(int value) {
            ensure(2);
            buffer.putShort((short) (value & 0xFFFF));
            return this;
        }

        Writer uint8(int value) {
            ensure(1);
            buffer.put((byte) (value & 0xFF));
            return this;
        }

        Writer string(String value) {
            byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ensure(2 + encoded.length);
            buffer.putShort((short) encoded.length);
            buffer.put(encoded);
            return this;
        }

        Writer bytes(byte[] value) {
            ensure(value.length);
            buffer.put(value);
            return this;
        }

        Writer qid(long[] qid) {
            ensure(13);
            buffer.put((byte) qid[0]);
            buffer.putInt((int) qid[1]);
            buffer.putLong(qid[2]);
            return this;
        }

        Writer append(byte[] value, int length) {
            ensure(length);
            buffer.put(value, 0, length);
            return this;
        }

        byte[] toBytes() {
            int bodyLength = buffer.position();
            byte[] message = new byte[4 + bodyLength];
            message[0] = (byte) (bodyLength + 4);
            message[1] = (byte) ((bodyLength + 4) >> 8);
            message[2] = (byte) ((bodyLength + 4) >> 16);
            message[3] = (byte) ((bodyLength + 4) >> 24);
            ByteBuffer view = buffer.duplicate();
            view.flip();
            view.get(message, 4, bodyLength);
            return message;
        }

        /** The body only, without the size prefix (readdir entry payloads). */
        byte[] rawBytes() {
            ByteBuffer view = buffer.duplicate();
            view.flip();
            byte[] body = new byte[view.remaining()];
            view.get(body);
            return body;
        }
    }
}
