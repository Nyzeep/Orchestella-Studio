package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Wire codec seam between {@link TerminalMux} and one framed transport.
 *
 * <p>Logical {@link TerminalFrame}s carry mux session ids; the codec owns the
 * mapping to wire session ids, the magic/version header, and any frame
 * translation the wire contract requires. The only production implementation is
 * {@link Fln1TerminalCodec}; there is no second wire protocol.
 */
public interface TerminalCodec {
    /** Encodes one logical frame, including the wire header. */
    byte[] encode(TerminalFrame frame) throws IOException;

    /** Decodes exactly one complete frame from {@code wire}. */
    TerminalFrame decode(byte[] wire) throws IOException;

    /** Reads one frame; returns {@code null} only for a clean EOF at a frame boundary. */
    TerminalFrame read(InputStream input) throws IOException;

    /** Writes one logical frame. */
    default void write(OutputStream output, TerminalFrame frame) throws IOException {
        output.write(encode(frame));
    }

    /** Drops the wire mapping for a mux session that will never be used again. */
    void releaseSession(int sessionId);
}
