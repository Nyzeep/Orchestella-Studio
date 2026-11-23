package io.github.nyzeep.orchestella.runtime.engineuml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

/**
 * Live handle over one spawned standalone UML guest process and its host-side
 * duplex PTY and Bridge control channels.
 */
public interface UmlProcessHandle {
    /** Host OS process id, or -1 when unavailable. */
    long pid();

    /** True while the UML kernel process has not been reaped. */
    boolean isAlive();

    /** Blocks until the UML kernel process exits and returns its exit code. */
    int waitFor() throws InterruptedException;

    /** Waits up to {@code timeout} for exit; returns true once exited. */
    boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException;

    /** Requests graceful termination (SIGTERM). */
    void destroy();

    /** Requests immediate termination (SIGKILL). */
    void destroyForcibly();

    /** Stream carrying host keystrokes into the guest PTY console. */
    OutputStream terminalInput() throws IOException;

    /** Stream carrying guest PTY console output to the host. */
    InputStream terminalOutput() throws IOException;

    /** Stream carrying host-initiated Bridge control lines into the guest. */
    OutputStream controlInput() throws IOException;

    /** Stream carrying Bridge replies and guest requests to the host. */
    InputStream controlOutput() throws IOException;

    /** Updates the host PTY window size via {@code TIOCSWINSZ}. */
    boolean resizeTerminal(int columns, int rows);

    /** Best-effort idempotent close of host-side PTY and control descriptors. */
    void closeChannels();
}
