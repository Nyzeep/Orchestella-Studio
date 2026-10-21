package android.os;

import android.system.virtualmachine.VsockConnection;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** PFD boundary fixture: no getInputStream/getOutputStream duck-typed API. */
public final class ParcelFileDescriptor implements AutoCloseable {
    private static final class Resource {
        private final InputStream input;
        private final OutputStream output;
        private final AutoCloseable closer;
        private int owners = 1;
        private int released;

        private Resource(InputStream input, OutputStream output, AutoCloseable closer) {
            this.input = input;
            this.output = output;
            this.closer = closer;
        }
    }

    private final Resource resource;
    private boolean closed;
    private int closeCalls;
    public boolean failDup;
    public boolean failInput;
    public boolean failOutput;
    public boolean failClose;
    public boolean denyStreams;

    public ParcelFileDescriptor(VsockConnection socket) {
        this(socket.getInputStream(), socket.getOutputStream(), socket);
    }

    public ParcelFileDescriptor(InputStream input, OutputStream output, AutoCloseable closer) {
        resource = new Resource(input, output, closer);
    }

    private ParcelFileDescriptor(Resource resource) {
        this.resource = resource;
        resource.owners++;
    }

    public synchronized ParcelFileDescriptor dup() throws IOException {
        if (failDup || closed) {
            throw new IOException("fixture dup failed");
        }
        ParcelFileDescriptor duplicate = new ParcelFileDescriptor(resource);
        duplicate.failOutput = failOutput;
        duplicate.failClose = failClose;
        return duplicate;
    }

    @Override
    public synchronized void close() throws IOException {
        closeCalls++;
        if (closed) {
            return;
        }
        closed = true;
        resource.released++;
        if (--resource.owners == 0 && resource.closer != null) {
            try {
                resource.closer.close();
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException(failure);
            }
        }
        if (failClose) {
            throw new IOException("fixture close failed");
        }
    }

    public boolean isClosedForTest() {
        return closed;
    }

    public int closeCallsForTest() {
        return closeCalls;
    }

    public int releasedForTest() {
        return resource.released;
    }

    public static final class AutoCloseInputStream extends FilterInputStream {
        private final ParcelFileDescriptor owner;

        public AutoCloseInputStream(ParcelFileDescriptor owner) throws IOException {
            super(owner.resource.input);
            this.owner = owner;
            if (owner.denyStreams) {
                throw new SecurityException("PFD stream permission revoked");
            }
            if (owner.failInput) {
                throw new IOException("fixture input construction failed");
            }
        }

        @Override
        public void close() throws IOException {
            owner.close();
        }
    }

    public static final class AutoCloseOutputStream extends FilterOutputStream {
        private final ParcelFileDescriptor owner;

        public AutoCloseOutputStream(ParcelFileDescriptor owner) throws IOException {
            super(owner.resource.output);
            this.owner = owner;
            if (owner.failOutput) {
                throw new IOException("fixture output construction failed");
            }
        }

        @Override
        public void close() throws IOException {
            owner.close();
        }
    }
}
