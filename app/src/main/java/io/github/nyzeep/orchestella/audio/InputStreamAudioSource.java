package io.github.nyzeep.orchestella.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Adapter wrapping an existing {@link InputStream} as an {@link AudioSourceSeam}.
 */
public final class InputStreamAudioSource implements AudioSourceSeam {

    private final InputStream inputStream;
    private boolean closed = false;

    public InputStreamAudioSource(InputStream inputStream) {
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream");
    }

    @Override
    public InputStream openStream() throws IOException {
        if (closed) {
            throw new IOException("Stream already closed");
        }
        return inputStream;
    }

    @Override
    public boolean isConnected() {
        return !closed;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        inputStream.close();
    }
}
