package io.github.nyzeep.droidlayer.audio;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Android LocalSocket audio source connecting to QEMU / PulseAudio UNIX domain socket
 * (default "@droidlayer-audio" in abstract namespace).
 */
public final class LocalSocketAudioSource implements AudioSourceSeam {

    public static final String DEFAULT_SOCKET_NAME = "droidlayer-audio";

    private final String socketName;
    private LocalSocket localSocket;
    private InputStream inputStream;
    private boolean closed = false;

    public LocalSocketAudioSource() {
        this(DEFAULT_SOCKET_NAME);
    }

    public LocalSocketAudioSource(String socketName) {
        this.socketName = Objects.requireNonNull(socketName, "socketName");
    }

    @Override
    public synchronized InputStream openStream() throws IOException {
        if (closed) {
            throw new IOException("LocalSocketAudioSource already closed");
        }
        if (localSocket == null || !localSocket.isConnected()) {
            localSocket = new LocalSocket();
            localSocket.connect(new LocalSocketAddress(
                    socketName,
                    LocalSocketAddress.Namespace.ABSTRACT));
            inputStream = localSocket.getInputStream();
        }
        return inputStream;
    }

    @Override
    public synchronized boolean isConnected() {
        return localSocket != null && localSocket.isConnected();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
            inputStream = null;
        }
        if (localSocket != null) {
            try {
                localSocket.close();
            } catch (IOException ignored) {
            }
            localSocket = null;
        }
    }

    public String getSocketName() {
        return socketName;
    }
}
