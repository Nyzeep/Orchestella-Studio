package io.github.nyzeep.orchestella.runtime.engineuml;

import io.github.nyzeep.orchestella.runtime.contract.BridgeControl;
import io.github.nyzeep.orchestella.runtime.contract.BridgeHandshake;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Byte-stream Bridge v1 protocol adapter for standalone UML control channels.
 *
 * <p>Unlike a single-handshake transport, this adapter honors repeated {@code HELLO}
 * re-authentication lines emitted by {@link io.github.nyzeep.orchestella.runtime.contract.BridgeClient}
 * before each command and rejects unauthenticated commands fail-closed.
 */
public final class UmlBridgeAdapter {
    private static final int MAX_CONTROL_LINE_BYTES = 512;

    private final InputStream inbound;
    private final OutputStream outbound;
    private final String expectedToken;
    private final BridgeControl.Terminal terminal;
    private final BridgeControl.Power power;
    private final BridgeControl.Notification notification;
    private final BridgeControl.UrlOpener urlOpener;

    public UmlBridgeAdapter(
            InputStream inbound,
            OutputStream outbound,
            String expectedToken,
            BridgeControl.Terminal terminal,
            BridgeControl.Power power) {
        this(inbound, outbound, expectedToken, terminal, power, null, null);
    }

    public UmlBridgeAdapter(
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
     * Serves Bridge v1 requests until EOF or a protocol rejection.
     *
     * @return {@code 0} on clean EOF, {@code 1} malformed handshake, {@code 2} unsupported version,
     *     {@code 3} unauthorized token, {@code 4} rejected/unauthenticated control command.
     */
    public int serve() throws IOException {
        boolean authenticated = false;
        while (true) {
            String line = readControlLine();
            if (line == null) {
                return 0;
            }
            if (isHelloLine(line)) {
                BridgeHandshake.Result handshake =
                        BridgeHandshake.negotiate(line, expectedToken);
                write(handshake.wireResponse());
                if (!handshake.accepted()) {
                    return handshakeExitCode(handshake.rejection());
                }
                authenticated = true;
                continue;
            }
            if (!authenticated) {
                write("ORCHESTELLA/ERROR unauthenticated\n");
                return 4;
            }
            BridgeControl.Result result =
                    BridgeControl.dispatch(line, terminal, power, notification, urlOpener);
            write(result.wireResponse());
            if (!result.accepted()) {
                return 4;
            }
        }
    }

    private static boolean isHelloLine(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("ORCHESTELLA/HELLO ")
                || trimmed.startsWith("DROIDLAYER/HELLO ")
                || "ORCHESTELLA/HELLO".equals(trimmed)
                || "DROIDLAYER/HELLO".equals(trimmed);
    }

    private String readControlLine() throws IOException {
        byte[] bytes = new byte[MAX_CONTROL_LINE_BYTES];
        int length = 0;
        while (length < bytes.length) {
            int value = inbound.read();
            if (value == -1) {
                return length == 0 ? null : "";
            }
            bytes[length++] = (byte) value;
            if (value == '\n') {
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
        }
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
