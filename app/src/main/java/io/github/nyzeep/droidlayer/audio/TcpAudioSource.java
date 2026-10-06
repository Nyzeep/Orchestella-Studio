package io.github.nyzeep.droidlayer.audio;

import io.github.nyzeep.droidlayer.runtime.contract.AudioStreamContract;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;

/**
 * TCP socket audio source connecting to QEMU / PulseAudio (default 127.0.0.1:9104).
 */
public final class TcpAudioSource implements AudioSourceSeam {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = AudioStreamContract.DEFAULT_PORT; // 9104
    public static final int CONNECT_TIMEOUT_MS = 3000;

    private final String host;
    private final int port;
    private Socket socket;
    private InputStream inputStream;
    private boolean closed = false;

    public TcpAudioSource() {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public TcpAudioSource(String host, int port) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
    }

    @Override
    public synchronized InputStream openStream() throws IOException {
        if (closed) {
            throw new IOException("TcpAudioSource already closed");
        }
        if (socket == null || socket.isClosed()) {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            inputStream = socket.getInputStream();
        }
        return inputStream;
    }

    @Override
    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
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
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }
}
