package android.system.virtualmachine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Test-only stand-in for the object the real {@code connectVsock} returns.
 * The reflective adapter adapts whatever object comes back by looking up
 * {@code getInputStream()}/{@code getOutputStream()}, exactly as it must do
 * against the real SDK surface.
 */
public final class VsockConnection {
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
}
