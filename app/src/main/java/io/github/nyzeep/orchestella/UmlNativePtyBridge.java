package io.github.nyzeep.orchestella;

import android.os.ParcelFileDescriptor;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlLaunchSpec;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessFactory;
import io.github.nyzeep.orchestella.runtime.engineuml.UmlProcessHandle;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android host-side {@link UmlProcessFactory} backed by {@code liborchestella_uml_pty.so}.
 *
 * <p>Spawns and reaps each UML guest process on a dedicated per-instance daemon thread so
 * {@code PR_SET_PDEATHSIG} stays bound to a thread that outlives the UML child.
 */
public final class UmlNativePtyBridge implements UmlProcessFactory {
    private static final int SIGTERM = 15;
    private static final int SIGKILL = 9;
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    static {
        System.loadLibrary("orchestella_uml_pty");
    }

    @Override
    public UmlProcessHandle spawn(UmlLaunchSpec spec, int initialColumns, int initialRows)
            throws IOException {
        return spawn(spec, initialColumns, initialRows, false);
    }

    UmlProcessHandle spawnNetworked(UmlLaunchSpec spec, int initialColumns, int initialRows)
            throws IOException {
        return spawn(spec, initialColumns, initialRows, true);
    }

    static ParcelFileDescriptor duplicateNetworkSocket(UmlProcessHandle handle) throws IOException {
        return handle instanceof NativeUmlProcessHandle nativeHandle
                ? nativeHandle.duplicateNetworkSocket() : null;
    }

    private UmlProcessHandle spawn(
            UmlLaunchSpec spec, int initialColumns, int initialRows, boolean networked)
            throws IOException {
        List<String> argvList = spec.argv();
        String executable = argvList.get(0);
        String[] argv = argvList.toArray(new String[0]);

        Map<String, String> envMap = spec.environment();
        List<String> envEntries = new ArrayList<>(envMap.size());
        for (Map.Entry<String, String> entry : envMap.entrySet()) {
            envEntries.add(entry.getKey() + "=" + entry.getValue());
        }
        String[] envp = envEntries.toArray(new String[0]);
        String workingDir = spec.runtimeDir().toAbsolutePath().toString();

        CompletableFuture<NativeUmlProcessHandle> spawned = new CompletableFuture<>();
        CountDownLatch exitLatch = new CountDownLatch(1);
        AtomicBoolean exited = new AtomicBoolean(false);
        AtomicBoolean callerAbandoned = new AtomicBoolean(false);
        AtomicInteger exitCode = new AtomicInteger(-1);

        Thread reaper = new Thread(() -> {
            int pid = -1;
            try {
                int[] fds = nativeSpawnUml(
                        executable, argv, envp, workingDir, initialColumns, initialRows, networked);
                if (fds == null || fds.length != 5) {
                    throw new IOException("nativeSpawnUml returned invalid descriptor tuple");
                }
                pid = fds[0];
                NativeUmlProcessHandle handle = new NativeUmlProcessHandle(
                        pid, fds[1], fds[2], fds[3], fds[4], exited, exitCode, exitLatch);
                spawned.complete(handle);
                if (callerAbandoned.get()) {
                    handle.destroyForcibly();
                    handle.closeChannels();
                }
            } catch (Throwable failure) {
                if (pid > 0) {
                    nativeSignalProcessGroup(pid, SIGKILL);
                }
                spawned.completeExceptionally(failure);
                if (pid <= 0) {
                    return;
                }
            }

            try {
                int status = nativeWaitForExit(pid);
                exitCode.set(status);
            } finally {
                exited.set(true);
                exitLatch.countDown();
            }
        }, "uml-reaper-" + THREAD_SEQ.incrementAndGet());
        reaper.setDaemon(true);
        reaper.start();

        try {
            return spawned.get();
        } catch (InterruptedException interrupted) {
            callerAbandoned.set(true);
            NativeUmlProcessHandle lateHandle =
                    spawned.isDone() && !spawned.isCompletedExceptionally()
                            ? spawned.getNow(null)
                            : null;
            if (lateHandle != null) {
                lateHandle.destroyForcibly();
                lateHandle.closeChannels();
            }
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while spawning UML process", interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("UML spawn failed", cause);
        }
    }

    private static final class NativeUmlProcessHandle implements UmlProcessHandle {
        private final int pid;
        private final Object fdLock = new Object();
        private final ParcelFileDescriptor ptyMasterPfd;
        private final ParcelFileDescriptor terminalReadPfd;
        private final ParcelFileDescriptor terminalWritePfd;
        private final ParcelFileDescriptor ctrlWritePfd;
        private final ParcelFileDescriptor ctrlReadPfd;
        private final ParcelFileDescriptor networkPfd;
        private final OutputStream terminalIn;
        private final InputStream terminalOut;
        private final OutputStream controlIn;
        private final InputStream controlOut;
        private final AtomicBoolean exited;
        private final AtomicInteger exitCode;
        private final CountDownLatch exitLatch;
        private final AtomicBoolean channelsClosed = new AtomicBoolean(false);

        NativeUmlProcessHandle(
                int pid,
                int ptyMasterFd,
                int ctrlWriteFd,
                int ctrlReadFd,
                int networkFd,
                AtomicBoolean exited,
                AtomicInteger exitCode,
                CountDownLatch exitLatch) throws IOException {
            this.pid = pid;
            this.exited = exited;
            this.exitCode = exitCode;
            this.exitLatch = exitLatch;

            ParcelFileDescriptor master = null;
            ParcelFileDescriptor readDup = null;
            ParcelFileDescriptor writeDup = null;
            ParcelFileDescriptor cWrite = null;
            ParcelFileDescriptor cRead = null;
            ParcelFileDescriptor network = networkFd >= 0 ? ParcelFileDescriptor.adoptFd(networkFd) : null;
            try {
                master = ParcelFileDescriptor.adoptFd(ptyMasterFd);
                cWrite = ParcelFileDescriptor.adoptFd(ctrlWriteFd);
                cRead = ParcelFileDescriptor.adoptFd(ctrlReadFd);
                readDup = master.dup();
                writeDup = master.dup();
                this.ptyMasterPfd = master;
                this.terminalReadPfd = readDup;
                this.terminalWritePfd = writeDup;
                this.ctrlWritePfd = cWrite;
                this.ctrlReadPfd = cRead;
                this.networkPfd = network;
                this.terminalIn = new ParcelFileDescriptor.AutoCloseOutputStream(writeDup);
                this.terminalOut = new ParcelFileDescriptor.AutoCloseInputStream(readDup);
                this.controlIn = new ParcelFileDescriptor.AutoCloseOutputStream(cWrite);
                this.controlOut = new ParcelFileDescriptor.AutoCloseInputStream(cRead);
            } catch (IOException | RuntimeException failure) {
                closeQuietly(network);
                closeQuietly(cRead);
                closeQuietly(cWrite);
                closeQuietly(writeDup);
                closeQuietly(readDup);
                closeQuietly(master);
                throw failure;
            }
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public boolean isAlive() {
            return !exited.get() && nativeIsProcessAlive(pid);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exitLatch.await();
            return exitCode.get();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exitLatch.await(Math.max(0L, timeout), unit);
        }

        @Override
        public void destroy() {
            if (exited.get()) {
                return;
            }
            nativeSignalProcessGroup(pid, SIGTERM);
        }

        @Override
        public void destroyForcibly() {
            if (exited.get()) {
                return;
            }
            nativeSignalProcessGroup(pid, SIGKILL);
        }

        @Override
        public OutputStream terminalInput() {
            return terminalIn;
        }

        @Override
        public InputStream terminalOutput() {
            return terminalOut;
        }

        @Override
        public OutputStream controlInput() {
            return controlIn;
        }

        @Override
        public InputStream controlOutput() {
            return controlOut;
        }

        @Override
        public boolean resizeTerminal(int columns, int rows) {
            synchronized (fdLock) {
                if (channelsClosed.get()) {
                    return false;
                }
                int fd = ptyMasterPfd.getFd();
                return fd >= 0 && nativeResizePty(fd, columns, rows);
            }
        }

        @Override
        public void closeChannels() {
            if (!channelsClosed.compareAndSet(false, true)) {
                return;
            }
            synchronized (fdLock) {
                closeQuietly(controlIn);
                closeQuietly(controlOut);
                closeQuietly(terminalIn);
                closeQuietly(terminalOut);
                closeQuietly(ctrlWritePfd);
                closeQuietly(ctrlReadPfd);
                closeQuietly(terminalReadPfd);
                closeQuietly(terminalWritePfd);
                closeQuietly(ptyMasterPfd);
                closeQuietly(networkPfd);
            }
        }

        private ParcelFileDescriptor duplicateNetworkSocket() throws IOException {
            synchronized (fdLock) {
                return !channelsClosed.get() && networkPfd != null ? networkPfd.dup() : null;
            }
        }

        private static void closeQuietly(java.io.Closeable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Best-effort cleanup of native descriptors.
            }
        }
    }

    private static native int[] nativeSpawnUml(
            String executable,
            String[] argv,
            String[] envp,
            String workingDir,
            int columns,
            int rows,
            boolean networked) throws IOException;

    private static native boolean nativeResizePty(int masterFd, int columns, int rows);

    private static native int nativeWaitForExit(int pid);

    private static native boolean nativeSignalProcessGroup(int pid, int signal);

    private static native boolean nativeIsProcessAlive(int pid);
}
