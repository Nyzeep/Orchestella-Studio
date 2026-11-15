package io.github.nyzeep.orchestella.terminal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Measurement harness for the Rust VT prototype (R01).
 *
 * <p>Two measurements, both host-only (x86_64 JVM):
 *
 * <ol>
 *   <li>JNI crossing cost: a native empty method and a {@code feed} of zero bytes,
 *       1,000,000 calls each, 5 rounds, median reported in ns/call.
 *   <li>Functional smoke: feed one fixture through the real shim, copy the display
 *       rows into direct buffers, print row count and a digest of the cell stream.
 * </ol>
 *
 * <p>These numbers are <em>not</em> ARM64 device numbers. The arm64 JNI cost must be
 * measured on a device as part of R02.
 */
public final class RustJniProbe {
    static {
        String lib = System.getProperty("rust.jni.lib");
        if (lib == null || lib.isEmpty()) {
            throw new IllegalStateException("set -Drust.jni.lib=/absolute/path/to/liborchestella_vt_jni.so");
        }
        System.load(lib);
    }

    public static native long nativeNew(int cols, int rows);

    public static native int nativeFeed(long handle, ByteBuffer buffer, int len);

    public static native int nativeCopyRows(
            long handle, int first, int count,
            ByteBuffer cp, ByteBuffer fg, ByteBuffer bg, ByteBuffer attrs);

    public static native int nativeFree(long handle);

    public static native int nativeEmpty();

    public static void main(String[] args) throws Exception {
        int cols = args.length > 1 ? Integer.parseInt(args[1]) : 90;
        int rows = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        int calls = args.length > 3 ? Integer.parseInt(args[3]) : 1_000_000;

        overhead("nativeEmpty", calls, () -> nativeEmpty());
        ByteBuffer empty = ByteBuffer.allocateDirect(64);
        long handle = nativeNew(cols, rows);
        require(handle != 0, "nativeNew returned 0");
        overhead("feed(0B)", calls, () -> nativeFeed(handle, empty.duplicate(), 0));
        require(nativeFree(handle) == 0, "nativeFree failed");

        if (args.length > 0) {
            smoke(Paths.get(args[0]), cols, rows);
        }
    }

    private interface Invocation {
        int invoke();
    }

    private static void overhead(String label, int calls, Invocation invocation) {
        int warmup = Math.max(10_000, calls / 10);
        for (int i = 0; i < warmup; i++) {
            require(invocation.invoke() == 0, label + " warmup failed");
        }
        double[] nsPerCall = new double[5];
        for (int round = 0; round < nsPerCall.length; round++) {
            long started = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                if (invocation.invoke() != 0) {
                    throw new IllegalStateException(label + " failed at call " + i);
                }
            }
            nsPerCall[round] = (System.nanoTime() - started) / (double) calls;
        }
        double[] sorted = nsPerCall.clone();
        Arrays.sort(sorted);
        System.out.printf("jni %-12s ns_per_call_median=%.2f rounds=%s calls=%d%n",
                label, sorted[sorted.length / 2], Arrays.toString(nsPerCall), calls);
    }

    private static void smoke(Path fixture, int cols, int rows) throws Exception {
        byte[] bytes = Files.readAllBytes(fixture);
        ByteBuffer input = ByteBuffer.allocateDirect(bytes.length);
        input.put(bytes).flip();

        long handle = nativeNew(cols, rows);
        require(handle != 0, "nativeNew returned 0");
        int fed = nativeFeed(handle, input, bytes.length);
        require(fed == 0, "nativeFeed failed: " + fed);

        int displayRows = rows;
        ByteBuffer cp = ByteBuffer.allocateDirect(displayRows * cols * 4).order(ByteOrder.nativeOrder());
        ByteBuffer fg = ByteBuffer.allocateDirect(displayRows * cols * 4).order(ByteOrder.nativeOrder());
        ByteBuffer bg = ByteBuffer.allocateDirect(displayRows * cols * 4).order(ByteOrder.nativeOrder());
        ByteBuffer attrs = ByteBuffer.allocateDirect(displayRows * cols);
        int copied = nativeCopyRows(handle, 0, displayRows, cp, fg, bg, attrs);
        require(copied > 0, "nativeCopyRows wrote nothing: " + copied);

        IntBuffer cpView = cp.order(ByteOrder.nativeOrder()).asIntBuffer();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] word = new byte[4];
        for (int i = 0; i < copied * cols; i++) {
            int value = cpView.get(i);
            word[0] = (byte) (value >>> 24);
            word[1] = (byte) (value >>> 16);
            word[2] = (byte) (value >>> 8);
            word[3] = (byte) value;
            digest.update(word);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        String sha = new String(hex).toLowerCase();
        String expected = sha256(bytes);
        System.out.printf("jni smoke fixture=%s bytes=%d cols=%d rows_copied=%d cp_sha256=%s%n",
                fixture.getFileName(), bytes.length, cols, copied, sha);
        System.out.printf("jni smoke input_sha256=%s%n", expected);
        require(nativeFree(handle) == 0, "nativeFree failed");

        // End-to-end: one feed call + one copy_rows call per round, which is the
        // batch model the R02 seam assumes.
        double[] mibPerSecond = new double[5];
        for (int round = 0; round < mibPerSecond.length; round++) {
            long fresh = nativeNew(cols, rows);
            require(fresh != 0, "nativeNew returned 0");
            input.rewind();
            long started = System.nanoTime();
            require(nativeFeed(fresh, input, bytes.length) == 0, "nativeFeed failed");
            require(nativeCopyRows(fresh, 0, displayRows, cp, fg, bg, attrs) > 0,
                    "nativeCopyRows wrote nothing");
            long elapsed = System.nanoTime() - started;
            require(nativeFree(fresh) == 0, "nativeFree failed");
            mibPerSecond[round] = (bytes.length / 1048576.0) / (elapsed / 1_000_000_000.0);
        }
        double[] sortedRates = mibPerSecond.clone();
        Arrays.sort(sortedRates);
        System.out.printf("jni e2e fixture=%s mib_per_s_median=%.1f rounds=%s%n",
                fixture.getFileName(), sortedRates[sortedRates.length / 2],
                Arrays.toString(mibPerSecond));
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return new String(hex).toLowerCase();
        } catch (Exception failure) {
            throw new IOException("sha256 unavailable", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private RustJniProbe() {
        // Utility class.
    }
}
