package io.github.nyzeep.droidlayer.runtime.engineavf;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-side minimal 9P2000.L client: the mount-side half of the share
 * contract. It speaks the exact wire format the guest kernel's {@code
 * trans=fd} mount would and asserts against the server's replies, so the
 * self-built server is validated from the protocol side, not by internals.
 */
final class TestNinePClient {
    static final int EROFS = 30;
    static final int ENOENT = 2;
    static final int EACCES = 13;
    static final int ENOSYS = 38;

    private static final byte T_VERSION = 100;
    private static final byte R_VERSION = 101;
    private static final byte R_LERROR = 7;
    private static final byte T_STATFS = 8;
    private static final byte R_STATFS = 9;
    private static final byte T_LOPEN = 12;
    private static final byte R_LOPEN = 13;
    private static final byte T_GETATTR = 24;
    private static final byte R_GETATTR = 25;
    private static final byte T_READDIR = 40;
    private static final byte R_READDIR = 41;
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
    private static final byte T_MKDIR = 72;
    private static final byte T_LCREATE = 14;
    private static final byte T_SETATTR = 26;
    private static final byte T_UNLINKAT = 74;

    private final InputStream inbound;
    private final OutputStream outbound;
    private int nextTag = 1;

    TestNinePClient(InputStream inbound, OutputStream outbound) {
        this.inbound = inbound;
        this.outbound = outbound;
    }

    String version() throws IOException {
        int tag = nextTag();
        ByteBuffer request = new ByteBuffer(T_VERSION, tag);
        request.int32(65536);
        request.string("9P2000.L");
        Response response = roundTrip(request);
        assertEqualsType(R_VERSION, response);
        response.int32(); // msize
        return response.string();
    }

    void attach(int fid, String aname) throws IOException {
        int tag = nextTag();
        ByteBuffer request = new ByteBuffer(T_ATTACH, tag);
        request.uint32(fid);
        request.uint32(0xFFFFFFFFL);
        request.string("");
        request.string(aname);
        request.uint32(0);
        Response response = roundTrip(request);
        assertEqualsType(R_ATTACH, response);
    }

    /** Walks {@code names} from fid to newFid; returns the walked qid count. */
    int walk(int fid, int newFid, String... names) throws IOException {
        int tag = nextTag();
        ByteBuffer request = new ByteBuffer(T_WALK, tag);
        request.uint32(fid);
        request.uint32(newFid);
        request.uint16(names.length);
        for (String name : names) {
            request.string(name);
        }
        Response response = roundTrip(request);
        if (response.type == R_LERROR) {
            throw new NinePError(response.int32());
        }
        assertEqualsType(R_WALK, response);
        return response.uint16();
    }

    void lopen(int fid) throws IOException {
        int tag = nextTag();
        ByteBuffer request = new ByteBuffer(T_LOPEN, tag);
        request.uint32(fid);
        request.int32(0); // O_RDONLY
        Response response = roundTrip(request);
        if (response.type == R_LERROR) {
            throw new NinePError(response.int32());
        }
        assertEqualsType(R_LOPEN, response);
    }

    byte[] read(int fid, long offset, int count) throws IOException {
        int tag = nextTag();
        ByteBuffer request = new ByteBuffer(T_READ, tag);
        request.uint32(fid);
        request.int64(offset);
        request.int32(count);
        Response response = roundTrip(request);
        if (response.type == R_LERROR) {
            throw new NinePError(response.int32());
        }
        assertEqualsType(R_READ, response);
        return response.bytes(response.int32());
    }

    List<String> readDirNames(int fid) throws IOException {
        List<String> names = new ArrayList<>();
        long offset = 0;
        while (true) {
            int tag = nextTag();
            ByteBuffer request = new ByteBuffer(T_READDIR, tag);
            request.uint32(fid);
            request.int64(offset);
            request.int32(8192);
            Response response = roundTrip(request);
            if (response.type == R_LERROR) {
                throw new NinePError(response.int32());
            }
            assertEqualsType(R_READDIR, response);
            int count = response.int32();
            if (count == 0) {
                return names;
            }
            int end = response.position() + count;
            while (response.position() < end) {
                response.skipQid();
                offset = response.int64();
                response.skipBytes(1); // entry type
                names.add(response.string());
            }
        }
    }

    Attr getattr(int fid) throws IOException {
        int tag = nextTag();
        ByteBuffer request = new ByteBuffer(T_GETATTR, tag);
        request.uint32(fid);
        request.int64(0x7df);
        Response response = roundTrip(request);
        if (response.type == R_LERROR) {
            throw new NinePError(response.int32());
        }
        assertEqualsType(R_GETATTR, response);
        Attr attr = new Attr();
        response.int64(); // valid mask
        response.skipQid();
        attr.mode = response.int32();
        response.int32(); // uid
        response.int32(); // gid
        response.int64(); // nlink
        response.int64(); // rdev
        attr.size = response.int64();
        return attr;
    }

    void clunk(int fid) throws IOException {
        int tag = nextTag();
        ByteBuffer request = new ByteBuffer(T_CLUNK, tag);
        request.uint32(fid);
        Response response = roundTrip(request);
        assertEqualsType(R_CLUNK, response);
    }

    int flush() throws IOException {
        int tag = nextTag();
        ByteBuffer request = new ByteBuffer(T_FLUSH, tag);
        request.uint16(1);
        Response response = roundTrip(request);
        if (response.type == R_LERROR) {
            return response.int32();
        }
        assertEqualsType(R_FLUSH, response);
        return 0;
    }

    int statfsType() throws IOException {
        int tag = nextTag();
        ByteBuffer request = new ByteBuffer(T_STATFS, tag);
        request.uint32(1);
        Response response = roundTrip(request);
        if (response.type == R_LERROR) {
            throw new NinePError(response.int32());
        }
        assertEqualsType(R_STATFS, response);
        return response.int32();
    }

    /** Sends a mutating request and asserts the physical EROFS rejection. */
    void writeIsRejectedWithErofs(ByteBuffer request) throws IOException {
        Response response = roundTrip(request);
        assertEqualsType(R_LERROR, response);
        int errno = response.int32();
        assertEquals(EROFS, errno, "write-class operation must be refused EROFS");
    }

    ByteBuffer writeRequest(int fid, byte[] data) {
        ByteBuffer request = new ByteBuffer(T_WRITE, nextTag());
        request.uint32(fid);
        request.int64(0);
        request.int32(data.length);
        request.bytes(data);
        return request;
    }

    ByteBuffer mkdirRequest(int fid, String name) {
        ByteBuffer request = new ByteBuffer(T_MKDIR, nextTag());
        request.uint32(fid);
        request.string(name);
        request.int32(0755);
        request.uint32(0);
        request.uint32(0);
        return request;
    }

    ByteBuffer lcreateRequest(int fid) {
        ByteBuffer request = new ByteBuffer(T_LCREATE, nextTag());
        request.uint32(fid);
        request.string("new.txt");
        request.int32(0);
        request.int32(0644);
        request.uint32(0);
        request.uint32(0);
        return request;
    }

    ByteBuffer setattrRequest(int fid) {
        ByteBuffer request = new ByteBuffer(T_SETATTR, nextTag());
        request.uint32(fid);
        request.int32(0);
        request.int32(0);
        request.uint32(0);
        request.uint32(0);
        request.int64(0);
        request.int64(0);
        request.int64(0);
        request.int64(0);
        request.int64(0);
        request.int64(0);
        request.int64(0);
        return request;
    }

    ByteBuffer removeRequest(int fid) {
        ByteBuffer request = new ByteBuffer(T_REMOVE, nextTag());
        request.uint32(fid);
        return request;
    }

    ByteBuffer unlinkatRequest(int dirFid, String name) {
        ByteBuffer request = new ByteBuffer(T_UNLINKAT, nextTag());
        request.uint32(dirFid);
        request.string(name);
        request.uint32(0);
        return request;
    }

    private Response roundTrip(ByteBuffer request) throws IOException {
        byte[] encoded = request.toBytes();
        outbound.write(encoded);
        outbound.flush();
        byte[] sizeBytes = readBytes(4);
        int size = (sizeBytes[0] & 0xFF)
                | ((sizeBytes[1] & 0xFF) << 8)
                | ((sizeBytes[2] & 0xFF) << 16)
                | ((sizeBytes[3] & 0xFF) << 24);
        byte[] message = readBytes(size - 4);
        return new Response(message);
    }

    private byte[] readBytes(int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = inbound.read(buffer, offset, length - offset);
            if (count < 0) {
                throw new EOFException("9p stream ended inside a message");
            }
            offset += count;
        }
        return buffer;
    }

    private int nextTag() {
        return nextTag++;
    }

    private static void assertEqualsType(byte expected, Response response) throws IOException {
        if (response.type != expected) {
            if (response.type == R_LERROR) {
                throw new NinePError(response.int32());
            }
            throw new IOException("expected message type " + expected
                    + " but got " + response.type);
        }
    }

    static void assertEquals(Object expected, Object actual, String description) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new IllegalStateException(description
                    + ": expected=[" + expected + "], actual=[" + actual + "]");
        }
    }

    static final class NinePError extends IOException {
        private static final long serialVersionUID = 1L;

        final int errno;

        NinePError(int errno) {
            super("9p error " + errno);
            this.errno = errno;
        }
    }

    static final class Attr {
        int mode;
        long size;
    }

    /** Little-endian 9p request builder with tag framing (type[1] tag[2]). */
    static final class ByteBuffer {
        private final java.nio.ByteBuffer buffer =
                java.nio.ByteBuffer.allocate(1 << 16)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        private final byte type;
        private final int tag;

        ByteBuffer(byte type, int tag) {
            this.type = type;
            this.tag = tag;
            buffer.put(type);
            buffer.putShort((short) (tag & 0xFFFF));
        }

        ByteBuffer int32(int value) {
            buffer.putInt(value);
            return this;
        }

        ByteBuffer int64(long value) {
            buffer.putLong(value);
            return this;
        }

        ByteBuffer uint16(int value) {
            buffer.putShort((short) (value & 0xFFFF));
            return this;
        }

        ByteBuffer uint32(long value) {
            buffer.putInt((int) (value & 0xFFFFFFFFL));
            return this;
        }

        ByteBuffer string(String value) {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            buffer.putShort((short) encoded.length);
            buffer.put(encoded);
            return this;
        }

        ByteBuffer bytes(byte[] value) {
            buffer.put(value);
            return this;
        }

        byte[] toBytes() {
            int bodyLength = buffer.position();
            byte[] message = new byte[4 + bodyLength];
            message[0] = (byte) (bodyLength + 4);
            message[1] = (byte) ((bodyLength + 4) >> 8);
            message[2] = (byte) ((bodyLength + 4) >> 16);
            message[3] = (byte) ((bodyLength + 4) >> 24);
            java.nio.ByteBuffer view = buffer.duplicate();
            view.flip();
            view.get(message, 4, bodyLength);
            return message;
        }
    }

    /** One parsed reply: type plus a little-endian body cursor. */
    static final class Response {
        private final java.nio.ByteBuffer body;
        final byte type;

        Response(byte[] message) {
            this.type = message[0];
            this.body = java.nio.ByteBuffer.wrap(message)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            body.position(1);
        }

        int position() {
            return body.position();
        }

        int int32() {
            return body.getInt();
        }

        long int64() {
            return body.getLong();
        }

        int uint16() {
            return body.getShort() & 0xFFFF;
        }

        String string() {
            int length = body.getShort() & 0xFFFF;
            byte[] encoded = new byte[length];
            body.get(encoded);
            return new String(encoded, StandardCharsets.UTF_8);
        }

        byte[] bytes(int length) {
            byte[] data = new byte[length];
            body.get(data);
            return data;
        }

        void skipBytes(int count) {
            body.position(body.position() + count);
        }

        void skipQid() {
            skipBytes(13);
        }
    }
}
