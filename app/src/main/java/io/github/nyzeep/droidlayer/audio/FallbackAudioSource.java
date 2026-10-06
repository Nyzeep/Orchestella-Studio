package io.github.nyzeep.droidlayer.audio;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Composite audio source that attempts primary audio source (e.g. LocalSocket @droidlayer-audio)
 * and automatically falls back to secondary audio source (e.g. TCP 127.0.0.1:9104) with bounded
 * retry and backoff.
 */
public final class FallbackAudioSource implements AudioSourceSeam {

    private final AudioSourceSeam primary;
    private final AudioSourceSeam secondary;
    private final int maxAttempts;
    private final long retryDelayMs;

    private AudioSourceSeam activeSource;
    private boolean closed = false;

    public FallbackAudioSource(AudioSourceSeam primary, AudioSourceSeam secondary) {
        this(primary, secondary, 5, 500);
    }

    public FallbackAudioSource(
            AudioSourceSeam primary,
            AudioSourceSeam secondary,
            int maxAttempts,
            long retryDelayMs) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.secondary = Objects.requireNonNull(secondary, "secondary");
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelayMs = Math.max(0, retryDelayMs);
    }

    @Override
    public synchronized InputStream openStream() throws IOException {
        if (closed) {
            throw new IOException("FallbackAudioSource already closed");
        }

        IOException lastError = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (closed || Thread.currentThread().isInterrupted()) {
                break;
            }

            try {
                InputStream stream = primary.openStream();
                if (stream != null) {
                    activeSource = primary;
                    return stream;
                }
            } catch (IOException e) {
                lastError = e;
            }

            try {
                InputStream stream = secondary.openStream();
                if (stream != null) {
                    activeSource = secondary;
                    return stream;
                }
            } catch (IOException e) {
                lastError = e;
            }

            if (attempt < maxAttempts - 1 && retryDelayMs > 0) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while connecting audio stream", interrupted);
                }
            }
        }

        throw lastError != null ? lastError : new IOException("Audio sources failed to connect");
    }

    @Override
    public synchronized boolean isConnected() {
        return activeSource != null && activeSource.isConnected();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        IOException last = null;
        try {
            primary.close();
        } catch (IOException e) {
            last = e;
        }
        try {
            secondary.close();
        } catch (IOException e) {
            last = e;
        }
        activeSource = null;
        if (last != null) {
            throw last;
        }
    }

    public AudioSourceSeam getActiveSource() {
        return activeSource;
    }

    public AudioSourceSeam getPrimary() {
        return primary;
    }

    public AudioSourceSeam getSecondary() {
        return secondary;
    }
}
