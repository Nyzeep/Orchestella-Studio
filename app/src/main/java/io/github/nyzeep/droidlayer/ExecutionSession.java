package io.github.nyzeep.droidlayer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Strategy-neutral handle over one running Full Linux instance session
 * (ADR-0002): the UX layer consumes exactly this interface and never sees
 * which execution strategy — hardware virtualization or software emulation —
 * is backing it. Implementations live in the launcher layer beside their
 * strategy-specific transports.
 *
 * <p>Stream direction convention: an <em>input</em> stream carries data into
 * the guest (the host writes), an <em>output</em> stream carries data out of
 * the guest (the host reads). The terminal pair is the raw console; the
 * control pair speaks the Bridge v1 protocol.
 */
public interface ExecutionSession {
    /** Blocks until the guest exits; returns its exit status. */
    int waitFor() throws InterruptedException;

    /**
     * The guest console's input stream — the host writes shell keystrokes
     * here. Connects (with bounded retries) while the guest boots.
     */
    OutputStream terminalInput() throws IOException;

    /**
     * The guest console's output stream — the host reads the console
     * transcript here. Connects (with bounded retries) while the guest boots.
     */
    InputStream terminalOutput() throws IOException;

    /**
     * The Bridge control channel's input stream — the host writes the
     * host-initiated protocol (HELLO, PING, RESIZE) here. Connects (with
     * bounded retries) while the guest boots.
     */
    OutputStream controlInput() throws IOException;

    /**
     * The Bridge control channel's output stream — the host reads protocol
     * replies and guest-initiated commands here. Connects (with bounded
     * retries) while the guest boots.
     */
    InputStream controlOutput() throws IOException;

    /**
     * Best-effort teardown of the session-owned transports; never throws.
     * The guest VM lifecycle is not affected — only the host-side channels.
     */
    void close();

    /**
     * Optional strategy-neutral display control contract for graphics desktop mode.
     * Null when the session does not provide Wayland display negotiation.
     */
    default io.github.nyzeep.droidlayer.runtime.contract.DisplayControl displayControl() {
        return null;
    }

    /**
     * Optional strategy-neutral Wayland graphics and input channel.
     * Null when the session does not provide a Wayland graphics stream.
     */
    default io.github.nyzeep.droidlayer.runtime.contract.WaylandChannel waylandChannel() {
        return null;
    }

    /**
     * Optional strategy-neutral BridgeClient for host-initiated control commands (such as clipboard sync).
     * Null when the session does not provide a control channel.
     */
    default io.github.nyzeep.droidlayer.runtime.contract.BridgeClient bridgeClient() {
        return null;
    }

    /**
     * Registers a strategy-neutral BridgeControl.Clipboard callback for guest-initiated clipboard sync.
     */
    default void registerClipboardBridge(io.github.nyzeep.droidlayer.runtime.contract.BridgeControl.Clipboard clipboard) {
    }

    /**
     * Returns the active clipboard bridge registered on this session.
     */
    default io.github.nyzeep.droidlayer.runtime.contract.BridgeControl.Clipboard clipboardBridge() {
        return null;
    }

    /**
     * Atomically marks that a bridge serving thread has started for this session.
     * Returns true if successfully marked (caller should run the loop), or false if already running.
     */
    default boolean markBridgeServing() {
        return false;
    }
}

