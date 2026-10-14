package io.github.nyzeep.orchestella.runtime.engineavf;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent standard 9P2000.L client for the public stream seam. */
final class StandardNinePClient {
    static final int ENOENT = 2, EROFS = 30;
    private final InputStream input;
    private final OutputStream output;
    private int tag = 1;
    private int msize = 8192;
    StandardNinePClient(InputStream input, OutputStream output) { this.input = input; this.output = output; }
    static final class NinePError extends IOException {
        private static final long serialVersionUID = 1L;
        final int errno;
        NinePError(int errno) { super("9p errno=" + errno); this.errno = errno; }
    }
    static void assertEquals(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(description + ": " + expected + " != " + actual);
    }
    static final class Attr { final long size; Attr(long size) { this.size = size; } }
    private static byte[] data(Object... values) throws IOException { return StandardReadonlyNinePTest.payload(values); }
    private ByteBuffer request(int type, int expected, byte[] payload) throws IOException {
        return request(type, expected, payload, tag++);
    }
    private ByteBuffer request(int type, int expected, byte[] payload, int requestTag) throws IOException {
        output.write(StandardReadonlyNinePTest.frame(type, requestTag, payload)); output.flush();
        byte[] header = exact(7); ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int size = h.getInt(), responseType = h.get() & 255, responseTag = h.getShort() & 65535;
        if (size < 7 || size > msize || responseTag != requestTag) throw new IOException("invalid standard response size/tag");
        ByteBuffer body = ByteBuffer.wrap(exact(size - 7)).order(ByteOrder.LITTLE_ENDIAN);
        if (responseType == 7) { if (body.remaining() != 4) throw new IOException("invalid Rlerror size"); throw new NinePError(body.getInt()); }
        if (responseType != expected) throw new IOException("unexpected standard opcode " + responseType);
        return body;
    }
    private byte[] exact(int length) throws IOException {
        byte[] data = new byte[length]; int count = 0;
        while (count < length) { int n = input.read(data, count, length - count); if (n <= 0) throw new EOFException("short response"); count += n; }
        return data;
    }
    String version() throws IOException {
        ByteBuffer response = request(100, 101, data(msize, "9P2000.L"), 65535);
        msize = response.getInt(); return string(response);
    }
    void attach(int fid, String name) throws IOException { ByteBuffer b = request(104, 105, data(fid, -1, "root", name, 0)); if (b.remaining() != 13) throw new IOException("bad Rattach"); }
    int walk(int fid, int newfid, String... names) throws IOException {
        Object[] values = new Object[3 + names.length]; values[0] = fid; values[1] = newfid; values[2] = (short) names.length;
        System.arraycopy(names, 0, values, 3, names.length);
        ByteBuffer b = request(110, 111, data(values)); int count = b.getShort() & 65535;
        if (b.remaining() != count * 13) throw new IOException("bad Rwalk length"); return count;
    }
    void lopen(int fid) throws IOException { ByteBuffer b = request(12, 13, data(fid, 0)); if (b.remaining() != 17) throw new IOException("bad Rlopen"); }
    byte[] read(int fid, long offset, int count) throws IOException {
        ByteBuffer b = request(116, 117, data(fid, offset, count)); int bytes = b.getInt();
        if (bytes < 0 || bytes != b.remaining()) throw new IOException("bad Rread length"); byte[] result = new byte[bytes]; b.get(result); return result;
    }
    Attr getattr(int fid) throws IOException {
        ByteBuffer b = request(24, 25, data(fid, 0x7ffL)); if (b.remaining() != 153) throw new IOException("bad Rgetattr length");
        b.position(8 + 13 + 12 + 16); return new Attr(b.getLong());
    }
    List<String> readDirNames(int fid) throws IOException {
        List<String> result = new ArrayList<>(); long cookie = 0;
        while (true) {
            ByteBuffer b = request(40, 41, data(fid, cookie, msize - 11)); int bytes = b.getInt();
            if (bytes != b.remaining()) throw new IOException("bad Rreaddir length");
            if (bytes == 0) return result;
            while (b.hasRemaining()) {
                if (b.remaining() < 24) throw new IOException("short directory entry");
                b.position(b.position() + 13); long next = b.getLong(); int type = b.get() & 255;
                if (next <= cookie || (type != 4 && type != 8)) throw new IOException("bad dir cookie/type");
                cookie = next; result.add(string(b));
            }
        }
    }
    void clunk(int fid) throws IOException { if (request(120, 121, data(fid)).hasRemaining()) throw new IOException("bad Rclunk"); }
    void mutationRejected(int type, byte[] payload) throws IOException {
        try { request(type, -1, payload); throw new AssertionError("write accepted"); }
        catch (NinePError failure) { assertEquals(EROFS, failure.errno, "read-only error"); }
    }
    private static String string(ByteBuffer b) throws IOException {
        if (b.remaining() < 2) throw new IOException("short string"); int count = b.getShort() & 65535;
        if (count > b.remaining()) throw new IOException("short string"); byte[] bytes = new byte[count]; b.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
