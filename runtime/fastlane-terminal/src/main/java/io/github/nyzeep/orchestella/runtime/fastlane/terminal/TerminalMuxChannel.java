package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Stream adapter for one framed {@code FLN1} connection.
 *
 * <p>It creates no threads: the caller owns the reader thread (or drives
 * {@link #pumpOnce()} from a test). Closing is idempotent and closes both
 * streams after a best-effort CLOSE for every session. A malformed frame is
 * connection-fatal, as FLN1 §7 requires: the stream is never resynchronised.
 */
public final class TerminalMuxChannel implements Closeable {
    private final TerminalMux mux;
    private final TerminalCodec codec;
    private final InputStream input;
    private final OutputStream output;
    private boolean closed;

    private TerminalMuxChannel(
            TerminalMux mux, TerminalCodec codec, InputStream input, OutputStream output) {
        this.mux = Objects.requireNonNull(mux, "mux");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
    }

    /**
     * Creates a mux and its FLN1 stream adapter. The sink captures the output
     * stream directly, so no {@code this} reference escapes construction.
     */
    public static TerminalMuxChannel open(
            TerminalMux.Role role,
            int maxSessions,
            TerminalMux.Listener listener,
            InputStream input,
            OutputStream output) {
        Objects.requireNonNull(output, "output");
        Fln1TerminalCodec codec = new Fln1TerminalCodec(role);
        TerminalMux.FrameSink sink = new TerminalMux.FrameSink() {
            @Override
            public void write(TerminalFrame frame) throws IOException {
                writeTo(output, codec, frame);
            }

            @Override
            public void releaseSession(int sessionId) {
                codec.releaseSession(sessionId);
            }
        };
        TerminalMux mux = new TerminalMux(role, maxSessions, sink, listener);
        return new TerminalMuxChannel(mux, codec, input, output);
    }

    public TerminalMux mux() {
        return mux;
    }

    /** Reads one frame; returns null at a clean EOF. */
    public TerminalFrame readFrame() throws IOException {
        return codec.read(input);
    }

    /**
     * Reads and dispatches one frame; returns false at a clean EOF. A clean
     * EOF or a malformed frame closes every session, because the transport
     * cannot deliver the peer's remaining CLOSE/EOF frames.
     */
    public boolean pumpOnce() throws IOException {
        try {
            TerminalFrame frame = codec.read(input);
            if (frame == null) {
                mux.abortAll(TerminalCloseReason.TRANSPORT_CLOSED);
                return false;
            }
            mux.onFrame(frame);
            return true;
        } catch (TerminalProtocolException failure) {
            mux.abortAll(TerminalCloseReason.PROTOCOL_ERROR);
            throw failure;
        } catch (IOException failure) {
            mux.abortAll(TerminalCloseReason.TRANSPORT_CLOSED);
            throw failure;
        }
    }

    /** Drains the stream synchronously; returns the number of frames handled. */
    public int pumpAll() throws IOException {
        int frames = 0;
        while (pumpOnce()) {
            frames++;
        }
        return frames;
    }

    /** Signals that the VM is gone; no CLOSE frames are written. */
    public void vmStopped() {
        synchronized (this) {
            closed = true;
        }
        mux.vmStopped();
        closeStreamsQuietly();
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        IOException failure = null;
        try {
            mux.closeAll(TerminalCloseReason.LOCAL_CLOSE);
        } catch (IOException closeFailure) {
            failure = closeFailure;
        }
        failure = closeStream(output, failure);
        failure = closeStream(input, failure);
        if (failure != null) {
            throw failure;
        }
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private void closeStreamsQuietly() {
        closeQuietly(output);
        closeQuietly(input);
    }

    private static void writeTo(OutputStream output, TerminalCodec codec, TerminalFrame frame)
            throws IOException {
        synchronized (output) {
            codec.write(output, frame);
            output.flush();
        }
    }

    private static IOException closeStream(Closeable stream, IOException primary) {
        try {
            stream.close();
        } catch (IOException failure) {
            if (primary == null) {
                return failure;
            }
            if (primary != failure) {
                primary.addSuppressed(failure);
            }
        }
        return primary;
    }

    private static void closeQuietly(Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The transport is already gone; there is nothing useful to do.
        }
    }
}
