package android.system.virtualmachine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** JVM socket helper behind the fixture network; not an Android AVF return type. */
public final class VsockConnection implements AutoCloseable {
    private final Socket socket;

    public VsockConnection(Socket socket) {
        this.socket = socket;
    }

    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
