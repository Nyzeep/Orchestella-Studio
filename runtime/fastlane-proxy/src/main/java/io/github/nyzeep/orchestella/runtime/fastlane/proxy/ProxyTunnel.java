package io.github.nyzeep.orchestella.runtime.fastlane.proxy;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * One bidirectional byte tunnel. Socket mode supports half-close in both
 * directions; channel mode cannot half-close a vsock channel, so upstream EOF
 * shuts the target's output and downstream EOF closes the channel.
 */
final class ProxyTunnel implements Closeable {
    private final InputStream clientInput;
    private final OutputStream clientOutput;
    private final Closeable clientCloseable;
    private final Socket clientSocket;
    private final Socket target;
    private final Runnable onClose;
    private final LongAdder upstreamBytes;
    private final LongAdder downstreamBytes;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger activePumps = new AtomicInteger(2);
    private final AtomicLong lastActivityNanos = new AtomicLong(System.nanoTime());
    private Thread upstreamThread;
    private Thread downstreamThread;

    private ProxyTunnel(
            InputStream clientInput,
            OutputStream clientOutput,
            Closeable clientCloseable,
            Socket clientSocket,
            Socket target,
            Runnable onClose,
            LongAdder upstreamBytes,
            LongAdder downstreamBytes) {
        this.clientInput = clientInput;
        this.clientOutput = clientOutput;
        this.clientCloseable = clientCloseable;
        this.clientSocket = clientSocket;
        this.target = target;
        this.onClose = onClose;
        this.upstreamBytes = upstreamBytes;
        this.downstreamBytes = downstreamBytes;
    }

    static ProxyTunnel forSocket(
            Socket client,
            Socket target,
            int idleTimeoutMs,
            Runnable onClose,
            LongAdder upstreamBytes,
            LongAdder downstreamBytes) throws IOException {
        client.setSoTimeout(idleTimeoutMs);
        target.setSoTimeout(idleTimeoutMs);
        return new ProxyTunnel(
                client.getInputStream(),
                client.getOutputStream(),
                client,
                client,
                target,
                onClose,
                upstreamBytes,
                downstreamBytes);
    }

    static ProxyTunnel forChannel(
            FastLaneChannel channel,
            Socket target,
            int idleTimeoutMs,
            Runnable onClose,
            LongAdder upstreamBytes,
            LongAdder downstreamBytes) throws IOException {
        target.setSoTimeout(idleTimeoutMs);
        return new ProxyTunnel(
                channel.inputStream(),
                channel.outputStream(),
                channel,
                null,
                target,
                onClose,
                upstreamBytes,
                downstreamBytes);
    }

    void start() {
        upstreamThread = new Thread(
                () -> pump(clientInput, targetOutputStream(), true), "fastlane-proxy-up");
        downstreamThread = new Thread(
                () -> pump(targetInputStream(), clientOutput, false), "fastlane-proxy-down");
        upstreamThread.setDaemon(true);
        downstreamThread.setDaemon(true);
        upstreamThread.start();
        downstreamThread.start();
    }

    void await() throws InterruptedException {
        final Thread up = upstreamThread;
        final Thread down = downstreamThread;
        if (up != null) {
            up.join();
        }
        if (down != null) {
            down.join();
        }
    }

    long lastActivityNanos() {
        return lastActivityNanos.get();
    }

    boolean isClosed() {
        return closed.get();
    }

    private OutputStream targetOutputStream() {
        try {
            return target.getOutputStream();
        } catch (IOException failure) {
            close();
            return OutputStream.nullOutputStream();
        }
    }

    private InputStream targetInputStream() {
        try {
            return target.getInputStream();
        } catch (IOException failure) {
            close();
            return InputStream.nullInputStream();
        }
    }

    private void pump(InputStream input, OutputStream output, boolean clientToTarget) {
        final byte[] buffer = new byte[16 * 1024];
        try {
            for (;;) {
                if (closed.get()) {
                    return;
                }
                final int read = input.read(buffer);
                if (read < 0) {
                    if (clientToTarget) {
                        shutdownTargetOutput();
                    } else {
                        shutdownClientOutput();
                    }
                    return;
                }
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
                output.flush();
                lastActivityNanos.set(System.nanoTime());
                if (clientToTarget) {
                    upstreamBytes.add(read);
                } else {
                    downstreamBytes.add(read);
                }
            }
        } catch (IOException failure) {
            // A timeout, reset, or closed peer ends the tunnel; it is not a new failure class.
        } finally {
            if (activePumps.decrementAndGet() == 0) {
                close();
            }
        }
    }

    private void shutdownTargetOutput() {
        try {
            target.shutdownOutput();
        } catch (IOException failure) {
            close();
        }
    }

    private void shutdownClientOutput() {
        if (clientSocket != null) {
            try {
                clientSocket.shutdownOutput();
            } catch (IOException failure) {
                close();
            }
            return;
        }
        // A vsock channel has no half-close; the downstream is finished.
        close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(clientCloseable);
        closeQuietly(target);
        interruptIfOther(upstreamThread);
        interruptIfOther(downstreamThread);
        onClose.run();
    }

    private static void interruptIfOther(Thread thread) {
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort teardown.
        }
    }
}
