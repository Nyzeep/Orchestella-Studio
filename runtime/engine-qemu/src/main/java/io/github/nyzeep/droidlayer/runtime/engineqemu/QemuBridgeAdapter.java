package io.github.nyzeep.droidlayer.runtime.engineqemu;

import io.github.nyzeep.droidlayer.runtime.contract.BridgeControl;
import io.github.nyzeep.droidlayer.runtime.contract.BridgeHandshake;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * QEMU's adapter for an already-connected Bridge control byte stream.
 *
 * <p>The adapter deliberately knows only about bytes. QEMU serial, virtio, sockets, and terminal
 * presentation are selected outside this class; the shared protocol behavior remains in the Full
 * Linux Contract.
 */
public final class QemuBridgeAdapter {
    private static final int MAX_CONTROL_LINE_BYTES = 512;

    private final InputStream inbound;
    private final OutputStream outbound;
    private final String expectedToken;
    private final BridgeControl.Terminal terminal;
    private final BridgeControl.Power power;
    private final BridgeControl.Notification notification;
    private final BridgeControl.UrlOpener urlOpener;

    public QemuBridgeAdapter(
            InputStream inbound,
            OutputStream outbound,
            String expectedToken,
            BridgeControl.Terminal terminal) {
        this(inbound, outbound, expectedToken, terminal, null, null, null);
    }

    public QemuBridgeAdapter(
            InputStream inbound,
            OutputStream outbound,
            String expectedToken,
            BridgeControl.Terminal terminal,
            BridgeControl.Power power,
            BridgeControl.Notification notification,
            BridgeControl.UrlOpener urlOpener) {
        this.inbound = Objects.requireNonNull(inbound, "inbound");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
        this.expectedToken = Objects.requireNonNull(expectedToken, "expectedToken");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.power = power;
        this.notification = notification;
        this.urlOpener = urlOpener;
    }

    /**
     * Serves one authenticated Bridge session. Return values mirror the guest v1 protocol's stable
     * rejection categories; I/O failures remain I/O failures and are never converted to success.
     */
    public int serve() throws IOException {
        String handshakeLine = readControlLine();
        BridgeHandshake.Result handshake = BridgeHandshake.negotiate(handshakeLine, expectedToken);
        write(handshake.wireResponse());
        if (!handshake.accepted()) {
            return handshakeExitCode(handshake.rejection());
        }

        while (true) {
            String controlLine = readControlLine();
            if (controlLine == null) {
                return 0;
            }
            BridgeControl.Result result =
                    BridgeControl.dispatch(controlLine, terminal, power, notification, urlOpener);
            write(result.wireResponse());
            if (!result.accepted()) {
                return 4;
            }
        }
    }

    private String readControlLine() throws IOException {
        byte[] bytes = new byte[MAX_CONTROL_LINE_BYTES];
        int length = 0;
        while (length < bytes.length) {
            int value = inbound.read();
            if (value == -1) {
                return length == 0 ? null : invalidPartialLine();
            }
            bytes[length++] = (byte) value;
            if (value == '\n') {
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
        }
        return invalidOversizedLine();
    }

    private static String invalidPartialLine() {
        return "";
    }

    private static String invalidOversizedLine() {
        return "";
    }

    private void write(String response) throws IOException {
        outbound.write(response.getBytes(StandardCharsets.US_ASCII));
        outbound.flush();
    }

    private static int handshakeExitCode(BridgeHandshake.Reason reason) {
        return switch (reason) {
            case MALFORMED_HANDSHAKE -> 1;
            case UNSUPPORTED_VERSION -> 2;
            case UNAUTHORIZED -> 3;
        };
    }
}
