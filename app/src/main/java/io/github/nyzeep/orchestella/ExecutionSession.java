package io.github.nyzeep.orchestella;

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
     * Best-effort teardown of session-owned resources; never throws. A session
     * that owns a runtime process also stops it with a bounded wait; channel-only
     * implementations keep their previous behavior.
     */
    void close();

    /** Stops the VM itself after an explicit force-stop action. */
    default void terminate() throws IOException {
        throw new IOException("This session cannot be forcibly stopped");
    }

    /**
     * Optional strategy-neutral display control contract for graphics desktop mode.
     * Null when the session does not provide Wayland display negotiation.
     */
    default io.github.nyzeep.orchestella.runtime.contract.DisplayControl displayControl() {
        return null;
    }

    /**
     * Optional strategy-neutral Wayland graphics and input channel.
     * Null when the session does not provide a Wayland graphics stream.
     */
    default io.github.nyzeep.orchestella.runtime.contract.WaylandChannel waylandChannel() {
        return null;
    }

    /** Connects the separate display transport using capabilities established by the guest probe. */
    default boolean connectDisplay(boolean keyboard, boolean unicodeText, boolean pointer) { return false; }
    default boolean reconnectDisplay(boolean keyboard, boolean unicodeText, boolean pointer) {
        return connectDisplay(keyboard, unicodeText, pointer);
    }

    default String displayStatus() { return "显示数据面不可用"; }

    interface ServiceForward extends AutoCloseable {
        int hostPort();
        boolean isActive();
        @Override void close() throws IOException;
    }

    default ServiceForward openServiceForward(int guestPort) throws IOException {
        throw new IOException("本地服务转发在当前执行策略尚未验证");
    }

    default io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.CapabilityMatrix capabilityMatrix() {
        return io.github.nyzeep.orchestella.runtime.contract.FullLinuxContract.CapabilityMatrix.softwareVirtualizationBaseline();
    }

    /**
     * Optional strategy-neutral BridgeClient for host-initiated control commands (such as clipboard sync).
     * Null when the session does not provide a control channel.
     */
    default io.github.nyzeep.orchestella.runtime.contract.BridgeClient bridgeClient() {
        return null;
    }

    /**
     * Retires a failed control transport after its reader has ended, without
     * affecting the VM or terminal. Call once on a worker thread for an explicit
     * UI re-entry. True means a fresh control client can be obtained and must
     * authenticate again; it does not mean control is ready. Unsupported, live,
     * or unsafe-to-retire channels return false without resetting their caches.
     */
    default boolean recoverControlChannel() {
        return false;
    }

    /**
     * Registers a strategy-neutral BridgeControl.Clipboard callback for guest-initiated clipboard sync.
     */
    default void registerClipboardBridge(io.github.nyzeep.orchestella.runtime.contract.BridgeControl.Clipboard clipboard) {
    }

    /**
     * Returns the active clipboard bridge registered on this session.
     */
    default io.github.nyzeep.orchestella.runtime.contract.BridgeControl.Clipboard clipboardBridge() {
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

