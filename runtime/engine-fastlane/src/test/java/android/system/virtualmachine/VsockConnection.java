package android.system.virtualmachine;

import java.io.InputStream;
import java.io.OutputStream;

/** One fake vsock connection owned by a ParcelFileDescriptor fixture. */
public final class VsockConnection implements AutoCloseable {
    private final InputStream input;
    private final OutputStream output;
    private final Runnable closer;

    public VsockConnection(InputStream input, OutputStream output, Runnable closer) {
        this.input = input;
        this.output = output;
        this.closer = closer;
    }

    public InputStream getInputStream() {
        return input;
    }

    public OutputStream getOutputStream() {
        return output;
    }

    @Override
    public void close() {
        if (closer != null) {
            closer.run();
        }
    }
}
