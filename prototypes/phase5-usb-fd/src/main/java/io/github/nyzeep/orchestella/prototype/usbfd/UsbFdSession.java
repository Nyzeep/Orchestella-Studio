package io.github.nyzeep.orchestella.prototype.usbfd;

import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** PROTOTYPE: models Android permission and descriptor ownership, never VMM USB passthrough. */
public final class UsbFdSession implements AutoCloseable {
    public enum State { NEW, PERMISSION_PENDING, DENIED, OPEN, DETACHED, FAILED, CLOSED }
    @FunctionalInterface public interface Opener { Descriptor open() throws IOException; }
    /** Android adapter retains UsbDeviceConnection; duplicate must own a separately closeable FD. */
    public interface Descriptor extends AutoCloseable {
        int fd();
        Descriptor duplicate() throws IOException;
        @Override void close() throws IOException;
    }
    private State state = State.NEW;
    private String request;
    private Descriptor descriptor;
    private final Set<ProbeLease> leases = new HashSet<>();

    public synchronized State state() { return state; }

    public synchronized String requestPermission() {
        if (state != State.NEW && state != State.DENIED) {
            throw new IllegalStateException("Permission request is not allowed in this state");
        }
        request = UUID.randomUUID().toString();
        state = State.PERMISSION_PENDING;
        return request;
    }

    public synchronized void permissionResult(String requestId, boolean granted, Opener opener) throws IOException {
        if (state != State.PERMISSION_PENDING || !Objects.equals(request, requestId)) {
            throw new IllegalStateException("Stale or unmatched permission result");
        }
        request = null;
        if (!granted) { state = State.DENIED; return; }
        try {
            descriptor = Objects.requireNonNull(Objects.requireNonNull(opener, "opener").open(), "descriptor");
            if (descriptor.fd() < 0) { throw new IOException("Invalid granted descriptor"); }
            state = State.OPEN;
        } catch (IOException | RuntimeException error) {
            state = State.FAILED;
            try { releaseDescriptors(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw new IOException("Granted device could not be opened", error);
        }
    }

    public synchronized ProbeLease borrowForProbe() throws IOException {
        if (state != State.OPEN || descriptor == null) { throw new IllegalStateException("Authorized open descriptor required"); }
        Descriptor duplicate = Objects.requireNonNull(descriptor.duplicate(), "duplicate");
        if (duplicate == descriptor) { throw new IOException("Descriptor duplication did not create ownership"); }
        if (duplicate.fd() < 0 || duplicate.fd() == descriptor.fd()) {
            duplicate.close();
            throw new IOException("Invalid duplicate descriptor");
        }
        ProbeLease lease = new ProbeLease(duplicate);
        leases.add(lease);
        return lease;
    }

    public synchronized void detached() throws IOException {
        if (state == State.CLOSED || state == State.DETACHED) { return; }
        request = null;
        state = State.DETACHED;
        try { releaseDescriptors(); }
        catch (IOException error) { state = State.FAILED; throw error; }
    }

    /** A raw FD is a permission-scoped probe input, not a supported native guest attachment. */
    public void requireNativePassthrough() {
        throw new UnsupportedOperationException("USB VMM passthrough requires an accepted ACR and real device verification");
    }

    private void releaseDescriptors() throws IOException {
        IOException failure = null;
        for (ProbeLease lease : Set.copyOf(leases)) {
            try { lease.close(); }
            catch (IOException error) { if (failure == null) { failure = error; } else { failure.addSuppressed(error); } }
        }
        if (descriptor != null) {
            try { descriptor.close(); descriptor = null; }
            catch (IOException error) { if (failure == null) { failure = error; } else { failure.addSuppressed(error); } }
        }
        if (failure != null) { throw failure; }
    }

    @Override public synchronized void close() throws IOException {
        if (state == State.CLOSED) { return; }
        request = null;
        state = State.CLOSED;
        try { releaseDescriptors(); }
        catch (IOException error) { state = State.FAILED; throw error; }
    }

    public final class ProbeLease implements AutoCloseable {
        private final Descriptor owned;
        private boolean closed;
        private ProbeLease(Descriptor owned) { this.owned = owned; }
        public int fd() {
            synchronized (UsbFdSession.this) {
                if (closed || state != State.OPEN) { throw new IllegalStateException("FD probe lease revoked"); }
                return owned.fd();
            }
        }
        @Override public void close() throws IOException {
            synchronized (UsbFdSession.this) {
                if (!closed) { owned.close(); closed = true; leases.remove(this); }
            }
        }
    }
}
