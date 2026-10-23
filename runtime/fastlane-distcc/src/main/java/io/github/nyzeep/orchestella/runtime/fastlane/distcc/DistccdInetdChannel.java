package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import io.github.nyzeep.orchestella.runtime.fastlane.FastLaneChannel;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * AX-06: accelerator-side {@code distccd --inetd} stdio pump.
 *
 * <p>The distcc protocol rides on stdin/stdout only. stderr is discarded so a
 * compiler warning or distccd diagnostic can never be mistaken for protocol
 * bytes. Stdin EOF is the distccd request terminator, so closing the returned
 * output stream half-closes the job.
 */
public final class DistccdInetdChannel implements FastLaneChannel {
    private static final long CLOSE_WAIT_MS = 2_000L;

    private final Process process;

    private DistccdInetdChannel(Process process) {
        this.process = process;
    }

    public static DistccdInetdChannel start(Path distccd, Map<String, String> environment)
            throws IOException {
        Objects.requireNonNull(distccd, "distccd");
        ProcessBuilder builder = new ProcessBuilder(distccd.toString(), "--inetd");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        if (environment != null) {
            builder.environment().putAll(environment);
        }
        return new DistccdInetdChannel(builder.start());
    }

    @Override
    public InputStream inputStream() {
        return process.getInputStream();
    }

    @Override
    public OutputStream outputStream() {
        return process.getOutputStream();
    }

    @Override
    public void close() {
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        try {
            if (!process.waitFor(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort teardown of a child process stream.
        }
    }
}
