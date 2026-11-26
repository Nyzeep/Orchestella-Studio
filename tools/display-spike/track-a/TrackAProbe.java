package io.github.nyzeep.orchestella.spike.w405a;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class TrackAProbe extends Instrumentation {
    private static final int BAR_SIZE = 1024 * 1024;
    private static final String TAG = "W405A";
    private static final String HOST_SEED = "HOST_SEED_405";
    private static final String QEMU_LIBRARY = "libdroidlayer_qemu.so";

    private final StringBuilder output = new StringBuilder("\n");
    private String mode;

    private static native long openMap(String path, int size);
    private static native boolean markerEquals(long mapping, int size, int offset, String marker);
    private static native int closeMap(long mapping, int size);

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mode = arguments.getString("mode", "normal");
        start();
    }

    @Override
    public void onStart() {
        boolean passed = false;
        try {
            System.loadLibrary("w405a_host_map");
            passed = runProbe(getTargetContext());
        } catch (Exception | LinkageError failure) {
            emit("host_fatal=" + failure.getClass().getSimpleName());
        }
        emit("host_complete=" + (passed ? "pass" : "fail"));
        Bundle result = new Bundle();
        result.putString("stream", output.toString());
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private boolean runProbe(Context context) throws IOException {
        emit("host_api=" + Build.VERSION.SDK_INT + " host_kernel=" + Os.uname().release
                + " host_page=" + Os.sysconf(OsConstants._SC_PAGESIZE)
                + " host_app_nonroot=" + bit(Os.getuid() >= 10000));
        emit("host_app_context=" + procContext("/proc/self/attr/current"));

        Path executable = Paths.get(context.getApplicationInfo().nativeLibraryDir).resolve(QEMU_LIBRARY);
        emit("host_packed_qemu_available=" + bit(Files.isExecutable(executable)));
        if (!Files.isExecutable(executable)) {
            return false;
        }

        if ("device-list".equals(mode)) {
            return inspectDevices(executable);
        }
        boolean invalidDevice = "invalid-device".equals(mode);
        if (!invalidDevice && !"normal".equals(mode)) {
            emit("host_mode=invalid");
            return false;
        }
        emit("host_mode=" + mode);

        Path work = Files.createTempDirectory(context.getCacheDir().toPath(), "w405a-");
        Path image = work.resolve("Image");
        Path initrd = work.resolve("probe-init.cpio.gz");
        Path backing = work.resolve("bar2.bin");
        Path console = work.resolve("console.txt");
        Path pidFile = work.resolve("qemu.pid");
        Process process = null;
        long mapping = 0;
        boolean passed = false;
        boolean unmapped = false;
        boolean removed = false;
        boolean processStopped = false;
        String stage = "copy-guest-assets";
        try {
            copyAsset(context, image);
            copyAsset(context, initrd);
            stage = "private-backing";
            seedBacking(backing);
            stage = "host-map";
            mapping = openMap(backing.toString(), BAR_SIZE);
            emit("host_mmap_errno=" + (mapping < 0 ? -mapping : 0));
            if (mapping <= 0 || !markerEquals(mapping, BAR_SIZE, 0, HOST_SEED)) {
                return false;
            }

            stage = "qemu-exec";
            String type = invalidDevice ? "w405a-invalid-device" : "ivshmem-plain";
            process = new ProcessBuilder(
                    executable.toString(),
                    "-machine", "virt,gic-version=3",
                    "-accel", "tcg",
                    "-cpu", "max",
                    "-smp", "2",
                    "-m", "256M",
                    "-nodefaults",
                    "-pidfile", pidFile.toString(),
                    "-no-reboot",
                    "-display", "none",
                    "-monitor", "none",
                    "-serial", "stdio",
                    "-kernel", image.toString(),
                    "-initrd", initrd.toString(),
                    "-append", "console=ttyAMA0 rdinit=/init panic=-1",
                    "-object", "memory-backend-file,id=shmmem,share=on,size=1M,mem-path=" + backing,
                    "-device", type + ",memdev=shmmem")
                    .directory(work.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(console.toFile())
                    .start();
            process.getOutputStream().close();
            emit("host_qemu_context=" + childContext(process, pidFile));

            stage = "qemu-wait";
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                emit("host_qemu_timeout=1");
                return false;
            }
            int exitCode = process.exitValue();
            emit("host_qemu_exit=" + exitCode);
            stage = "diagnostics";
            boolean guestPassed = recordConsole(console);
            boolean sawRoot = markerEquals(mapping, BAR_SIZE, 64, "GUEST_ROOT_405");
            boolean sawUser = markerEquals(mapping, BAR_SIZE, 128, "GUEST_USER_405");
            emit("host_observed_guest_root=" + bit(sawRoot) + " host_observed_guest_user=" + bit(sawUser));
            passed = invalidDevice ? exitCode != 0 && !guestPassed && !sawRoot && !sawUser
                    : exitCode == 0 && guestPassed && sawRoot && sawUser;
        } catch (ErrnoException failure) {
            emit("host_failure_stage=" + stage + " host_failure_errno=" + failure.errno);
        } catch (IOException | InterruptedException failure) {
            emit("host_failure_stage=" + stage + " host_failure=" + failure.getClass().getSimpleName());
            if (failure.getMessage() != null && failure.getMessage().contains("error=13")) {
                emit("host_exec_errno=13");
            }
        } finally {
            processStopped = stopProcess(process);
            int unmapErrno = mapping > 0 ? closeMap(mapping, BAR_SIZE) : 0;
            unmapped = unmapErrno == 0;
            removed = removeProbeFiles(work, image, initrd, backing, console, pidFile);
            emit("host_qemu_stopped=" + bit(processStopped)
                    + " host_munmap_errno=" + unmapErrno
                    + " host_backing_removed=" + bit(!Files.exists(backing))
                    + " host_probe_dir_removed=" + bit(removed));
        }
        return passed && processStopped && unmapped && removed;
    }

    private boolean inspectDevices(Path executable) throws IOException {
        Process process = new ProcessBuilder(executable.toString(), "-device", "help")
                .redirectErrorStream(true).start();
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                process.getInputStream(), StandardCharsets.US_ASCII))) {
            String output = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            emit("model_ivshmem_plain=" + bit(output.contains("name \"ivshmem-plain\"")));
            emit("model_virtio_gpu=" + bit(output.contains("name \"virtio-gpu-device\"")));
            emit("model_virtio_gpu_gl=" + bit(output.contains("virtio-gpu-gl-") || output.contains("virtio-vga-gl")));
            emit("model_rutabaga=" + bit(output.contains("rutabaga")));
            emit("model_vhost_user_gpu=" + bit(output.contains("vhost-user-gpu") || output.contains("vhost-user-vga")));
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            stopProcess(process);
        }
    }

    private void seedBacking(Path backing) throws IOException, ErrnoException {
        try (RandomAccessFile file = new RandomAccessFile(backing.toFile(), "rw")) {
            Os.fchmod(file.getFD(), 0600);
            file.setLength(BAR_SIZE);
            file.write(HOST_SEED.getBytes(StandardCharsets.US_ASCII));
            file.getFD().sync();
            StructStat stat = Os.fstat(file.getFD());
            emit(String.format(Locale.ROOT, "host_backing_bytes=%d host_backing_mode=%04o host_backing_uid_matches=%d",
                    stat.st_size, stat.st_mode & 0777, bit(stat.st_uid == Os.getuid())));
        }
        try {
            byte[] label = Os.getxattr(backing.toString(), "security.selinux");
            emit("host_backing_context=" + selinuxType(new String(label, StandardCharsets.US_ASCII)));
        } catch (ErrnoException unavailable) {
            emit("host_backing_context_errno=" + unavailable.errno);
        }
    }

    private boolean recordConsole(Path console) throws IOException {
        boolean guestPassed = false;
        try (BufferedReader reader = Files.newBufferedReader(console, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("W405A ")) {
                    String event = line.substring(6);
                    emit(event);
                    guestPassed |= "guest_complete=pass".equals(event);
                } else if (line.contains("not a valid device model name")) {
                    emit("host_qemu_diagnostic=unknown-device");
                } else if (line.contains("Permission denied")) {
                    emit("host_qemu_diagnostic=permission-denied");
                } else if (line.contains("could not open") || line.contains("Could not open")) {
                    emit("host_qemu_diagnostic=open-failed");
                } else if (line.contains("memory-backend-file") || line.contains("ivshmem-plain")) {
                    emit("host_qemu_diagnostic=backend-or-device");
                } else if (line.contains("Kernel panic")) {
                    if (line.contains("No working init found")) {
                        emit("host_guest_diagnostic=missing-init");
                    } else if (line.contains("VFS:")) {
                        emit("host_guest_diagnostic=root-mount-failed");
                    } else {
                        var status = java.util.regex.Pattern.compile("exitcode=0x[0-9a-fA-F]{1,8}").matcher(line);
                        emit("host_guest_diagnostic=kernel-panic"
                                + (status.find() ? " " + status.group() : ""));
                    }
                } else if (line.contains("Initramfs unpacking failed")) {
                    emit("host_guest_diagnostic=initramfs-unpack-failed");
                } else if (line.contains("Failed to execute /init")) {
                    var error = java.util.regex.Pattern.compile("error -[0-9]+").matcher(line);
                    emit("host_guest_diagnostic=init-exec-failed"
                            + (error.find() ? " " + error.group().replace(' ', '=') : ""));
                } else if (line.contains("Run /init as init process")) {
                    emit("host_guest_diagnostic=init-exec-started");
                } else if (line.contains("CANNOT LINK EXECUTABLE")) {
                    emit("host_qemu_diagnostic=linker-rejected");
                }
            }
        }
        return guestPassed;
    }

    private static void copyAsset(Context context, Path destination) throws IOException {
        try (InputStream input = context.getAssets().open(destination.getFileName().toString())) {
            Files.copy(input, destination);
        }
    }

    private static boolean stopProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return true;
        }
        process.destroyForcibly();
        try {
            return process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean removeProbeFiles(Path directory, Path... files) {
        boolean removed = true;
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                removed = false;
            }
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            return false;
        }
        return removed;
    }

    private static String childContext(Process child, Path pidFile) throws InterruptedException {
        for (int tries = 0; tries < 100 && child.isAlive(); ++tries) {
            if (Files.isRegularFile(pidFile)) {
                try (BufferedReader input = Files.newBufferedReader(pidFile, StandardCharsets.US_ASCII)) {
                    String pid = input.readLine();
                    if (pid != null && pid.matches("[1-9][0-9]*")) {
                        return procContext("/proc/" + pid + "/attr/current");
                    }
                } catch (IOException ignored) {
                    return "unavailable";
                }
            }
            Thread.sleep(20);
        }
        return "unavailable";
    }

    private static String procContext(String path) {
        try (BufferedReader input = Files.newBufferedReader(Paths.get(path), StandardCharsets.US_ASCII)) {
            return selinuxType(input.readLine());
        } catch (IOException inaccessible) {
            return "unavailable";
        }
    }

    private static String selinuxType(String label) {
        if (label == null) {
            return "unavailable";
        }
        String[] components = label.split(":");
        return components.length >= 3 && components[2].matches("[a-z0-9_]+")
                ? components[2] : "unavailable";
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private void emit(String event) {
        Log.i(TAG, event);
        output.append(TAG).append(' ').append(event).append('\n');
    }
}
