package io.github.nyzeep.droidlayer.audio;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Seam interface for low-latency audio stream data sources.
 */
public interface AudioSourceSeam extends Closeable {

    InputStream openStream() throws IOException;

    boolean isConnected();

    @Override
    void close() throws IOException;
}
