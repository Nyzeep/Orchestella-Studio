package io.github.nyzeep.orchestella.audio;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Android LocalSocket audio source connecting to QEMU / PulseAudio UNIX domain socket
 * (default "@droidlayer-audio" in abstract namespace).
 *
 * <p>G-05: the source can also be fed by an already-established connection via
 * {@link #attach(StreamConnection)} — the engine assembly layer (QemuInstanceLauncher)
 * accepts local consumer links and bridges them to the guest's virtio-serial audio
 * port. The legacy client-connect path keeps its exact semantics when no connection
 * is attached. PCM loop closure is unverified on real devices.
 */
public final class LocalSocketAudioSource implements AudioSourceSeam {

    public static final String DEFAULT_SOCKET_NAME = "droidlayer-audio";

    private final String socketName;
    private LocalSocket localSocket;
    private InputStream inputStream;
    private StreamConnection attached;
    private boolean closed = false;

    public LocalSocketAudioSource() {
        this(DEFAULT_SOCKET_NAME);
    }

    public LocalSocketAudioSource(String socketName) {
        this.socketName = Objects.requireNonNull(socketName, "socketName");
    }

    /**
     * Feeds the source from an established connection instead of the client connect
     * path. The legacy constructor/openStream semantics are unchanged for sources
     * that never attach. Attach is one-shot: a later attach replaces the previous
     * connection (the old one is closed), and the next openStream returns the new
     * attached stream.
     */
    public synchronized void attach(StreamConnection connection) throws IOException {
        if (closed) {
            throw new IOException("LocalSocketAudioSource already closed");
        }
        // Reject null before the identity shortcut: attach(null) is a programming error.
        Objects.requireNonNull(connection, "connection");
        if (connection == attached) {
            return;
        }
        StreamConnection previous = attached;
        attached = connection;
        inputStream = null;
        if (previous != null) {
            try {
                previous.close();
            } catch (IOException ignored) {
                // Replacing a dead consumer link is best-effort; the new attach wins.
            }
        }
    }

    @Override
    public synchronized InputStream openStream() throws IOException {
        if (closed) {
            throw new IOException("LocalSocketAudioSource already closed");
        }
        if (attached != null) {
            if (inputStream == null) {
                inputStream = attached.inputStream();
            }
            return inputStream;
        }
        if (localSocket == null || !localSocket.isConnected()) {
            LocalSocket ls = new LocalSocket();
            try {
                ls.connect(new LocalSocketAddress(
                        socketName,
                        LocalSocketAddress.Namespace.ABSTRACT));
                inputStream = ls.getInputStream();
                localSocket = ls;
            } catch (IOException e) {
                try {
                    ls.close();
                } catch (IOException ignored) {
                    // Closing the half-connected socket is best-effort; the original connect
                    // failure is rethrown below and is the real error surface.
                }
                localSocket = null;
                inputStream = null;
                throw e;
            }
        }
        return inputStream;
    }

    @Override
    public synchronized boolean isConnected() {
        if (attached != null) {
            return !closed;
        }
        return localSocket != null && localSocket.isConnected();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException ignored) {
                // Best-effort close: the source is already marked closed and clearing the
                // field must happen regardless of how the stream close ended.
            }
            inputStream = null;
        }
        if (attached != null) {
            try {
                attached.close();
            } catch (IOException ignored) {
                // Same best-effort contract for the attached transport.
            }
            attached = null;
        }
        if (localSocket != null) {
            try {
                localSocket.close();
            } catch (IOException ignored) {
                // Same best-effort close for the socket half.
            }
            localSocket = null;
        }
    }

    public String getSocketName() {
        return socketName;
    }
}
