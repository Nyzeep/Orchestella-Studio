package io.github.nyzeep.orchestella.runtime.engineavf;

import io.github.nyzeep.orchestella.runtime.contract.SharedDirectoryContract;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independent Linux 9p2000.L wire oracle: literal standard opcodes and seven-byte headers.
 * No TestNinePClient constants, parsers, VM mocks or private server APIs are used. */
public final class StandardReadonlyNinePTest {
    static final int NOTAG = 65535;
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    static byte[] payload(Object... fields) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Object field : fields) {
            if (field instanceof Integer i) bytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array());
            else if (field instanceof Long l) bytes.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(l).array());
            else if (field instanceof Short s) bytes.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(s).array());
            else if (field instanceof String text) {
                byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
                bytes.write(payload((short) encoded.length)); bytes.write(encoded);
            } else bytes.write((byte[]) field);
        }
        return bytes.toByteArray();
    }
    static byte[] frame(int type, int tag, byte[] body) {
        return ByteBuffer.allocate(7 + body.length).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(7 + body.length).put((byte) type).putShort((short) tag).put(body).array();
    }
    static byte[] join(byte[]... requests) throws IOException { return payload((Object[]) requests); }
    record Reply(int type, int tag, ByteBuffer body, int size) {
        int errno() { check(type == 7 && size == 11, "standard Rlerror header/length"); return body.getInt(); }
    }
    static List<Reply> replies(byte[] bytes) {
        ByteBuffer stream = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        List<Reply> replies = new ArrayList<>();
        while (stream.hasRemaining()) {
            check(stream.remaining() >= 7, "complete response header");
            int size = stream.getInt(); int type = stream.get() & 255; int tag = stream.getShort() & 65535;
            check(size >= 7 && size - 7 <= stream.remaining(), "complete declared response");
            byte[] body = new byte[size - 7]; stream.get(body);
            replies.add(new Reply(type, tag, ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN), size));
        }
        return replies;
    }
    static byte[] version(int size) throws Exception { return frame(100, NOTAG, payload(size, "9P2000.L")); }
    static List<Reply> serve(Path root, byte[] requests) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new Avf9pShareServer(SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(root.toString()))
            .serve(new ByteArrayInputStream(requests), output);
        return replies(output.toByteArray());
    }
    static void framing(Path root) throws Exception {
        List<Reply> answers = serve(root, join(version(8192), frame(254, 0x1234, new byte[0])));
        check(answers.size() == 2, "one reply per request");
        Reply v = answers.get(0);
        check(v.type == 101 && v.tag == NOTAG && v.size == 21, "Rversion standard type/tag/length");
        check(v.body.getInt() == 8192 && v.body.getShort() == 8, "Rversion negotiated msize and string");
        Reply unknown = answers.get(1);
        check(unknown.tag == 0x1234 && unknown.errno() == 38, "unknown opcode preserves tag and ENOSYS");
    }
    static void negotiationAndMalformed(Path root) throws Exception {
        check(serve(root, frame(104, 1, payload(1, -1, "root", "downloads", 0))).get(0).errno() == 22,
            "attach requires negotiation");
        List<Reply> answers = serve(root, join(version(8192), frame(100, NOTAG, new byte[0]),
            frame(100, NOTAG, payload(128, "9P2000.L")), frame(254, 4, new byte[0])));
        check(answers.get(1).errno() == 22 && answers.get(2).errno() == 22,
            "short version and unusable msize are bounded EINVAL");
        check(answers.get(3).tag == 4 && answers.get(3).errno() == 22,
            "malformed renegotiation clears session without crashing stream");
        byte[] badUtf8 = payload(8192, (short) 2, new byte[] {(byte) 0xc3, 0x28});
        check(serve(root, frame(100, NOTAG, badUtf8)).get(0).errno() == 22, "strict UTF-8");
        byte[] huge = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1_048_576).array();
        try { serve(root, huge); throw new AssertionError("overlong framing accepted"); }
        catch (IOException expected) { /* bounded before payload allocation */ }
    }
    static boolean secureProvider(Path root) throws IOException {
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(root)) {
            return directory instanceof SecureDirectoryStream<?>;
        }
    }
    static byte[] attach(int fid, int tag) throws Exception {
        return frame(104, tag, payload(fid, -1, "root", "downloads", 0));
    }
    static byte[] walk(int fid, int newfid, int tag, String... names) throws Exception {
        Object[] values = new Object[3 + names.length];
        values[0] = fid; values[1] = newfid; values[2] = (short) names.length;
        System.arraycopy(names, 0, values, 3, names.length);
        return frame(110, tag, payload(values));
    }
    static void readOnlyAndSecurePaths(Path root) throws Exception {
        Files.writeString(root.resolve("hello.txt"), "literal Downloads fixture", StandardCharsets.UTF_8);
        List<Reply> answers = serve(root, join(version(8192), attach(1, 2), walk(1, 2, 3, "hello.txt"),
            frame(12, 4, payload(2, 0)), frame(116, 5, payload(2, 0L, 4096))));
        if (!secureProvider(root)) {
            check(answers.get(1).errno() == 38, "unsupported secure provider fails closed at attach");
            return;
        }
        check(answers.get(1).type == 105 && answers.get(1).tag == 2 && answers.get(1).size == 20,
            "standard Rattach opcode/qid");
        check(answers.get(2).type == 111 && answers.get(2).body.getShort() == 1, "standard Rwalk");
        Reply read = answers.get(4);
        check(read.type == 117 && read.tag == 5, "standard Rread");
        int bytes = read.body.getInt(); byte[] content = new byte[bytes]; read.body.get(content);
        check(new String(content, StandardCharsets.UTF_8).equals("literal Downloads fixture"), "read actual fixture");
        check(read.size == bytes + 11, "Rread overhead includes tag");
    }
    static void readonlyMatrixAndMalformedReads(Path root) throws Exception {
        List<byte[]> frames = new ArrayList<>(); frames.add(version(256)); frames.add(attach(1, 2));
        int tag = 10;
        for (int type : new int[] {14, 16, 18, 20, 26, 32, 70, 72, 74, 76, 118, 122, 114, 126}) {
            frames.add(frame(type, tag++, new byte[0]));
        }
        List<Reply> answers = serve(root, join(frames.toArray(byte[][]::new)));
        for (int i = 2; i < answers.size(); i++) check(answers.get(i).errno() == 30, "readonly mutation opcode=" + (i - 2));
        if (!secureProvider(root)) return;
        answers = serve(root, join(version(256), attach(1, 2), walk(1, 2, 3, "hello.txt"),
            frame(12, 4, payload(2, 0x200)), frame(12, 5, payload(2, 0)),
            frame(116, 6, payload(2, -1L, 64)), frame(116, 7, payload(2, 0L, -1)),
            frame(116, 8, payload(2, 0L, 0)), frame(12, 9, payload(2, 0)),
            frame(50, 10, payload(2, 0)), frame(108, 11, payload((short) 7))));
        check(answers.get(3).errno() == 30, "truncate is readonly");
        check(answers.get(5).errno() == 22, "negative read offset");
        check(answers.get(6).type == 117 && answers.get(6).size <= 256, "unsigned read count clamped to msize");
        check(answers.get(7).type == 117 && answers.get(7).size == 11 && answers.get(7).body.getInt() == 0, "zero read succeeds");
        check(answers.get(8).errno() == 16, "reopen rejected without replacing channel");
        check(answers.get(9).type == 51 && answers.get(9).size == 7, "fsync standard empty reply");
        check(answers.get(10).type == 109 && answers.get(10).tag == 11, "serial flush preserves ordering/tag");
    }
    static void containment(Path root) throws Exception {
        if (!secureProvider(root)) return;
        Path outside = root.getParent().resolve(root.getFileName() + "-outside");
        Files.createDirectories(outside); Files.writeString(outside.resolve("sentinel.txt"), "outside synthetic sentinel");
        Files.createSymbolicLink(root.resolve("escape"), outside);
        Files.createDirectories(root.resolve("sub"));
        List<Reply> answers = serve(root, join(version(8192), attach(1, 2), walk(1, 2, 3, "escape"),
            walk(1, 3, 4, "sub/../escape"), walk(1, 4, 5, ".."),
            walk(1, 5, 6, "sub", "missing"), frame(24, 7, payload(5, 0x7ffL))));
        check(answers.get(2).errno() == 13 && answers.get(3).errno() == 2, "symlink and slash escape denied");
        check(answers.get(4).type == 111 && answers.get(4).body.getShort() == 1, "parent clamps to root");
        check(answers.get(5).type == 111 && answers.get(5).body.getShort() == 1, "partial walk reports completed prefix");
        check(answers.get(6).errno() == 9, "partial walk does not allocate destination fid");
    }
    static void fidAndDirectoryLimits(Path root) throws Exception {
        List<Reply> unknown = serve(root, join(frame(100, NOTAG, payload(65536, "unknown")), attach(1, 2)));
        check(unknown.get(0).type == 101 && unknown.get(1).errno() == 22, "unknown version prevents attach");
        if (!secureProvider(root)) return;
        List<byte[]> requests = new ArrayList<>(); requests.add(version(8192));
        for (int fid = 1; fid <= 257; fid++) requests.add(attach(fid, fid));
        List<Reply> limited = serve(root, join(requests.toArray(byte[][]::new)));
        check(limited.get(256).type == 105 && limited.get(257).errno() == 24, "fid count hard limit");
        check(serve(root, join(version(8192), attach(-1, 1))).get(1).errno() == 22, "NOFID cannot be allocated");
        List<Reply> listing = serve(root, join(version(256), attach(1, 2), frame(12, 3, payload(1, 0)),
            frame(40, 4, payload(1, 0L, -1)), frame(40, 5, payload(1, -1L, 64)),
            frame(40, 6, payload(1, Long.MAX_VALUE, 64)), frame(24, 7, payload(1, 0x7ffL))));
        check(listing.get(3).type == 41 && listing.get(3).size <= 256, "readdir response respects msize");
        check(listing.get(4).errno() == 22, "negative readdir cookie");
        check(listing.get(5).type == 41 && listing.get(5).body.getInt() == 0, "large cookie is EOF without integer wrap");
        check(listing.get(6).type == 25 && listing.get(6).size == 160, "standard getattr timespec response length");
        check(listing.get(6).body.getLong() == 0x77fL, "ctime is not falsely advertised as creation time");
        byte[] over = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(257).array();
        try { serve(root, join(version(256), over)); throw new AssertionError("negotiated msize not enforced"); }
        catch (IOException expected) { /* reject before allocation */ }
    }
    static void transportAndDirectoryBound(Path root) throws Exception {
        byte[] version = version(8192);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InputStream fragmented = new ByteArrayInputStream(version) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(1, length));
            }
        };
        new Avf9pShareServer(SharedDirectoryContract.SharedDirectorySpec.defaultDownloads(root.toString())).serve(fragmented, output);
        check(replies(output.toByteArray()).get(0).size == 21, "one-byte fragmented frame");
        for (int size : new int[] {0, 6, -1}) {
            try {
                serve(root, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array());
                throw new AssertionError("invalid unsigned frame length accepted");
            } catch (IOException expected) { /* protocol terminates before allocation */ }
        }
        byte[] shortString = payload(8192, (short) 5, new byte[] {'9'});
        check(serve(root, frame(100, NOTAG, shortString)).get(0).errno() == 22, "declared string exceeds frame");
        if (!secureProvider(root)) return;
        Path many = root.resolve("many"); Files.createDirectory(many);
        for (int i = 0; i < 4097; i++) Files.createFile(many.resolve("entry-" + i));
        List<Reply> bounded = serve(root, join(version(8192), attach(1, 2), walk(1, 2, 3, "many"),
            frame(12, 4, payload(2, 0))));
        check(bounded.get(3).errno() == 24, "directory enumeration has a hard entry bound");
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Files.createDirectories(root);
        framing(root);
        negotiationAndMalformed(root);
        readOnlyAndSecurePaths(root);
        readonlyMatrixAndMalformedReads(root);
        containment(root);
        fidAndDirectoryLimits(root);
        transportAndDirectoryBound(root);
        System.out.println("STANDARD_READONLY_9P_OK framing=1 malformed_negotiation=1 readonly=14"
                + " secure_provider_available=" + secureProvider(root)
                + " filesystem_read_containment_limits_run=" + secureProvider(root));
    }
}
