package io.github.nyzeep.orchestella.audio;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One established, duplex byte-stream connection handed to an {@link AudioSourceSeam}
 * without the source opening it itself (G-05 attach path).
 *
 * <p>The engine assembly layer (QemuInstanceLauncher) bridges the guest's
 * virtio-serial audio port to local consumers; established links arrive here so the
 * source never has to know how the transport was created. Pure JVM type: tests feed
 * pipe-backed implementations.
 */
public interface StreamConnection extends Closeable {

    /** The guest-facing data stream (PCM from the guest relay); blocks until data or EOF. */
    InputStream inputStream() throws IOException;

    /**
     * The write-back stream. The audio loop never writes PCM commands into it; closing
     * it is the only expected use, so a transport without a real write side may throw.
     */
    OutputStream outputStream() throws IOException;

    @Override
    void close() throws IOException;
}
