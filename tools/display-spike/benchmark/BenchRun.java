package io.github.nyzeep.orchestella.spike.w405bench;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.github.nyzeep.orchestella.NativeGlesSurfacePresenter;
import io.github.nyzeep.orchestella.runtime.contract.DisplayChannel.ConnectionState;
import io.github.nyzeep.orchestella.runtime.contract.display.RfbDisplayChannel;

final class BenchRun {
    static final String TAG = "W405Bench";
    private final Context context;
    private final NativeGlesSurfacePresenter presenter;
    private final String mode;
    private final String load;
    private final String runId;
    private final int seconds;
    private final BenchFrames frames;
    private final byte[] generation = random(16);
    private final AtomicReference<Exception> dataFailure = new AtomicReference<>();
    private volatile boolean abort;
    private volatile boolean finishing;
    private volatile boolean mappingReleased;
    private volatile Process qemu;
    private volatile Wire data;
    private Thread reader;
    private String stage = "surface";

    BenchRun(Context context, NativeGlesSurfacePresenter presenter, String mode, String load, String runId,
             int seconds, int surfaceWidth, int surfaceHeight) {
        this.context = context;
        this.presenter = presenter;
        this.mode = mode;
        this.load = load;
        this.runId = runId;
        this.seconds = seconds;
        this.frames = new BenchFrames(presenter, surfaceWidth, surfaceHeight, generation);
    }

    static native ByteBuffer mapReadOnly(String path, int bytes);
    static native int unmap(ByteBuffer buffer);

    void execute() {
        Path runDir = null;
        boolean passed = false;
        Log.i(TAG, "begin run=" + runId + " mode=" + mode + " load=" + load + " seconds=" + seconds + " view=960x540");
        try {
            if (!presenter.isReady()) throw new Failure("presenter-unavailable");
            stage = "setup";
            runDir = Files.createTempDirectory(context.getCacheDir().toPath(), "w405-");
            runExperiment(runDir);
            passed = true;
        } catch (Exception | LinkageError e) {
            String detail = describe(e);
            StackTraceElement[] trace = e.getStackTrace();
            String origin = trace.length == 0 ? "" : " source=" + trace[0].getMethodName();
            Log.e(TAG, "failure stage=" + stage + " detail=" + detail + origin);
        } finally {
            finishing = true;
            boolean stopped = stopQemu();
            if (reader != null) {
                try {
                    reader.join(4000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            frames.close();
            boolean sharedReleased = reader == null || (!reader.isAlive() && mappingReleased);
            boolean removed = runDir == null || removeRun(runDir);
            Log.i(TAG, "end run=" + runId + " outcome=" + (passed && stopped && removed && sharedReleased ? "pass" : "fail")
                    + " qemu_stopped=" + stopped + " run_removed=" + removed
                    + " mapping_released=" + sharedReleased);
        }
    }

    void abort() {
        if (!finishing) {
            abort = true;
            Process child = qemu;
            if (child != null && child.isAlive()) child.destroy();
        }
    }

    private void runExperiment(Path dir) throws Exception {
        Path backing = dir.resolve("bar2.bin");
        boolean shared = mode.equals("ivshmem");
        if (shared) {
            System.loadLibrary("w405bench_map");
            try (RandomAccessFile file = new RandomAccessFile(backing.toFile(), "rw")) {
                Os.fchmod(file.getFD(), 0600);
                file.setLength(BenchFrames.BAR_BYTES);
            }
        }
        copyAsset("Image", dir);
        copyAsset("desktop.cpio.gz", dir);
        qemu = launch(dir, shared, backing);
        String token = HexFormat.of().formatHex(random(32));
        String generationHex = HexFormat.of().formatHex(generation);
        RfbDisplayChannel rfb = null;
        try (Wire control = new Wire(openSocket(dir.resolve("control.sock")))) {
            stage = "guest-hello";
            control.expect("HELLO");
            control.send("CONFIG " + mode + " " + load + " " + generationHex + " " + token);
            stage = "guest-desktop";
            control.expect("STAGED");
            stage = "display-init";
            if (shared) {
                data = new Wire(openSocket(dir.resolve("frames.sock")));
                reader = new Thread(() -> readShared(backing), "W405SharedReader");
                reader.start();
            } else {
                rfb = RfbSource.open(() -> {
                    LocalSocket socket = openSocket(dir.resolve("rfb.sock"));
                    return new RfbDisplayChannel.Streams(socket.getInputStream(), socket.getOutputStream(), socket);
                }, token);
                rfb.addStateListener((state, message) -> {
                    if (state == ConnectionState.ERROR) {
                        Log.e(TAG, "rfb_error " + message);
                        frames.markRfbFailure();
                    }
                });
                rfb.registerFrameListener(frames::acceptRfb);
                if (!rfb.connect()) throw new Failure("rfb-connect");
            }
            frames.awaitInitial();
            stage = "measure";
            String pid = readPid(dir.resolve("qemu.pid"));
            long qemuCpuStart = cpuTicks(pid);
            long appCpuStart = android.os.Process.getElapsedCpuTime();
            RfbDisplayChannel.Statistics initialStats = rfb == null ? null : rfb.statistics();
            long start = System.nanoTime();
            long end = start + TimeUnit.SECONDS.toNanos(seconds);
            frames.start(generationHex, start, end);
            control.send("GO");
            control.expect("RUNNING");
            while (!abort && System.nanoTime() < end && dataFailure.get() == null) {
                Thread.sleep(20);
            }
            if (abort) throw new Failure("aborted");
            if (dataFailure.get() != null) throw new Failure("shared-reader-failed");
            long sampled = System.nanoTime();
            long qemuCpuEnd = cpuTicks(pid);
            long appCpuEnd = android.os.Process.getElapsedCpuTime();
            RfbDisplayChannel.Statistics finalStats = rfb == null ? null : rfb.statistics();
            control.send("STOP");
            String guest = control.line();
            if (!guest.matches("CPU [0-9]+ [0-9]+")) throw new Failure("guest-cpu-sample");
            String[] guestCpu = guest.split(" ");
            long busy = Long.parseLong(guestCpu[1]);
            long total = Long.parseLong(guestCpu[2]);
            if (busy > total || total == 0) throw new Failure("guest-cpu-delta");
            double wallSeconds = (sampled - start) / 1e9;
            double qemuPercent = 100.0 * (qemuCpuEnd - qemuCpuStart)
                    / (wallSeconds * Os.sysconf(OsConstants._SC_CLK_TCK));
            double appPercent = (appCpuEnd - appCpuStart) / (wallSeconds * 10.0);
            String rfbMetrics = "";
            if (initialStats != null && finalStats != null) {
                rfbMetrics = " rfb_frames=" + (finalStats.frames() - initialStats.frames())
                        + " rfb_bytes=" + (finalStats.receivedBytes() - initialStats.receivedBytes())
                        + " rfb_pixel_bytes=" + (finalStats.pixelBytes() - initialStats.pixelBytes());
            }
            Log.i(TAG, "result mode=" + mode + " load=" + load + " window_s=" + seconds
                    + " " + frames.measure() + " app_cpu_pct=" + decimal(appPercent)
                    + " qemu_cpu_pct=" + decimal(qemuPercent)
                    + " guest_busy_pct=" + decimal(100.0 * busy / total) + rfbMetrics);
            stage = "shutdown";
            finishing = true;
            control.send("QUIT");
            if (!qemu.waitFor(8, TimeUnit.SECONDS) || qemu.exitValue() != 0) {
                throw new Failure("guest-poweroff");
            }
        } finally {
            finishing = true;
            if (rfb != null) rfb.close();
            if (data != null) data.close();
            logFixture(dir.resolve("console.log"));
        }
    }

    private void readShared(Path backing) {
        ByteBuffer mapping = mapReadOnly(backing.toString(), BenchFrames.BAR_BYTES);
        if (mapping == null) {
            dataFailure.set(new IOException("read-only-map"));
            frames.markRfbFailure();
            return;
        }
        try {
            for (int seq = 1; !finishing && !abort; seq++) {
                data.expect("P " + seq);
                data.send("A " + frames.acceptShared(mapping, seq));
            }
        } catch (Exception problem) {
            if (!finishing && !abort) {
                String detail = describe(problem);
                Log.e(TAG, "shared_reader_error detail=" + detail);
                dataFailure.set(problem);
                frames.markRfbFailure();
            }
        } finally {
            int errno = unmap(mapping);
            mappingReleased = errno == 0;
            Log.i(TAG, "shared_unmap_errno=" + errno);
        }
    }

    private Process launch(Path dir, boolean shared, Path backing) throws IOException {
        Path qemuBinary = Path.of(context.getApplicationInfo().nativeLibraryDir, "libdroidlayer_qemu.so");
        ArrayList<String> command = new ArrayList<>(List.of(
                qemuBinary.toString(), "-machine", "virt,gic-version=3", "-accel", "tcg",
                "-cpu", "max", "-m", "1536M", "-smp", "2", "-nodefaults", "-no-reboot",
                "-nic", "none", "-display", "none", "-monitor", "none", "-serial", "stdio",
                "-pidfile", dir.resolve("qemu.pid").toString(),
                "-kernel", dir.resolve("Image").toString(),
                "-initrd", dir.resolve("desktop.cpio.gz").toString(),
                "-append", "console=ttyAMA0 rdinit=/init panic=-1",
                "-device", "virtio-serial-device"));
        addPort(command, dir, "control", "w405.control");
        if (shared) {
            addPort(command, dir, "frames", "w405.frames");
            command.addAll(List.of("-object", "memory-backend-file,id=shmmem,share=on,size=8M,mem-path=" + backing,
                    "-device", "ivshmem-plain,memdev=shmmem"));
        } else {
            addPort(command, dir, "rfb", "orchestella.display");
        }
        Process child = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(dir.resolve("console.log").toFile()).start();
        child.getOutputStream().close();
        return child;
    }

    private static void addPort(List<String> args, Path dir, String id, String guestName) {
        args.addAll(List.of("-chardev", "socket,id=" + id + ",path=" + dir.resolve(id + ".sock")
                + ",server=on,wait=off", "-device", "virtserialport,chardev=" + id + ",name=" + guestName));
    }

    private LocalSocket openSocket(Path path) throws IOException {
        for (int tries = 0; !abort && tries < 250; tries++) {
            if (qemu == null || !qemu.isAlive()) break;
            LocalSocket socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(path.toString(), LocalSocketAddress.Namespace.FILESYSTEM));
                socket.setSoTimeout(70000);
                return socket;
            } catch (IOException unavailable) {
                socket.close();
            }
            SystemClock.sleep(20);
        }
        throw new Failure("socket-connect");
    }

    private void copyAsset(String name, Path dir) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            Files.copy(input, dir.resolve(name));
        }
    }

    private static String readPid(Path file) throws IOException {
        String pid = firstLine(file).trim();
        if (!pid.matches("[1-9][0-9]*")) throw new Failure("invalid-qemu-pid");
        return pid;
    }

    private static String firstLine(Path path) throws IOException {
        try (var input = Files.newBufferedReader(path, StandardCharsets.US_ASCII)) {
            String line = input.readLine();
            if (line == null) throw new Failure("missing-process-stat");
            return line;
        }
    }

    private static long cpuTicks(String pid) throws IOException {
        String stat = firstLine(Path.of("/proc", pid, "stat"));
        String[] afterComm = stat.substring(stat.lastIndexOf(')') + 2).trim().split(" +");
        return Long.parseLong(afterComm[11]) + Long.parseLong(afterComm[12]);
    }

    private static void logFixture(Path console) {
        if (!Files.isRegularFile(console)) return;
        try (Stream<String> lines = Files.lines(console)) {
            lines.filter(line -> line.matches("W405(?:BENCH|_GUEST) [a-zA-Z0-9 _=,.-]{1,148}"))
                    .limit(40).forEach(line -> Log.i(TAG, line));
        } catch (IOException ignored) {
            Log.i(TAG, "fixture_console_unavailable");
        }
    }

    private boolean stopQemu() {
        if (qemu == null || !qemu.isAlive()) return true;
        qemu.destroyForcibly();
        try {
            return qemu.waitFor(8, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean removeRun(Path dir) {
        try (var files = Files.newDirectoryStream(dir)) {
            for (Path file : files) Files.deleteIfExists(file);
            return Files.deleteIfExists(dir);
        } catch (IOException failure) {
            return false;
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z][a-z0-9-]{0,40}")
                ? message : error.getClass().getSimpleName();
    }

    private static final class Failure extends IOException {
        Failure(String reason) { super(reason); }
    }

    private static final class Wire implements Closeable {
        private final LocalSocket socket;
        private final InputStream input;
        private final OutputStream output;

        Wire(LocalSocket socket) throws IOException {
            this.socket = socket;
            input = socket.getInputStream();
            output = socket.getOutputStream();
        }

        String line() throws IOException {
            StringBuilder line = new StringBuilder();
            for (int limit = 0; limit < 256; limit++) {
                int next = input.read();
                if (next == '\n') return line.toString();
                if (next < 32 || next > 126) throw new Failure("wire-byte");
                line.append((char) next);
            }
            throw new Failure("wire-length");
        }

        void expect(String expected) throws IOException {
            if (!expected.equals(line())) throw new Failure("unexpected-wire-message");
        }

        void send(String line) throws IOException {
            output.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
        }

        @Override public void close() throws IOException { socket.close(); }
    }
}
