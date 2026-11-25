package io.github.nyzeep.orchestella.audio;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Seam interface for low-latency audio stream data sources.
 */
public interface AudioSourceSeam extends Closeable {

    InputStream openStream() throws IOException;

    /**
     * Resets any dead or half-open transport state before a recovery reconnect
     * without permanently closing the source.
     */
    default void resetConnection() {}

    boolean isConnected();

    @Override
    void close() throws IOException;
}
