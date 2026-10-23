package io.github.nyzeep.orchestella.runtime.fastlane.distcc;

import io.github.nyzeep.orchestella.runtime.fastlane.distcc.axp1.Axp1Connection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AX-06: accelerator-side AXP1 to {@code distccd --inetd} pump.
 *
 * <p>The caller owns the accepted AXP1 GUEST connection (vsock or a host-only
 * test transport). This class only moves DATA bytes in both directions and
 * preserves the half-close: request EOF closes distccd stdin, response EOF is
 * sent back as AXP1 EOF. stderr never enters this path.
 */
public final class Axp1DistccAccelerator {
    private static final int BUFFER_BYTES = 16 * 1024;

    private Axp1DistccAccelerator() {
    }

    public static void serve(Axp1Connection connection, DistccdInetdChannel distccd)
            throws IOException {
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread toDistccd = new Thread(() -> {
            try {
                copy(connection.dataInput(), distccd.outputStream());
                distccd.outputStream().close();
            } catch (IOException error) {
                failure.compareAndSet(null, error);
                connection.close();
            }
        }, "axp1-distcc-stdin");
        toDistccd.setDaemon(true);
        toDistccd.start();

        try {
            copy(distccd.inputStream(), connection.dataOutput());
            connection.dataOutput().flush();
            connection.closeWrite();
        } catch (IOException error) {
            failure.compareAndSet(null, error);
            throw error;
        } finally {
            join(toDistccd);
            connection.close();
            distccd.close();
        }

        IOException error = failure.get();
        if (error != null) {
            throw error;
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            output.write(buffer, 0, read);
            output.flush();
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
