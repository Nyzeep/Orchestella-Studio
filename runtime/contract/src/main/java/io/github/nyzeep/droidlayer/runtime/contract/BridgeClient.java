package io.github.nyzeep.droidlayer.runtime.contract;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Strategy-neutral host-side Bridge v1 client. The host initiates the
 * handshake and the command lines; the guest agent answers READY / PONG /
 * OK resize, or {@code DROIDLAYER/ERROR} with a rejection kind. Transport
 * adapters supply plain byte streams and stay out of the protocol.
 */
public final class BridgeClient {
    private static final int MAX_RESPONSE_LINE_BYTES = 512;

    private final InputStream inbound;
    private final OutputStream outbound;

    public BridgeClient(InputStream inbound, OutputStream outbound) {
        this.inbound = Objects.requireNonNull(inbound, "inbound");
        this.outbound = Objects.requireNonNull(outbound, "outbound");
    }

    /** Sends the v1 handshake and classifies the agent's single response line. */
    public synchronized Result handshake(String token) throws IOException {
        if (!BridgeHandshake.isCanonicalToken(token)) {
            throw new IllegalArgumentException("bridge token must be 64 lowercase hexadecimal characters");
        }
        write("DROIDLAYER/HELLO 1 1 " + token + "\n");
        return classify(readResponseLine());
    }

    /** Sends one keepalive ping and classifies the agent's response. */
    public synchronized Result ping() throws IOException {
        write("DROIDLAYER/PING\n");
        return classify(readResponseLine());
    }

    /** Requests a terminal grid size and classifies the agent's response. */
    public synchronized Result resize(int columns, int rows) throws IOException {
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("terminal size must be positive");
        }
        write("DROIDLAYER/RESIZE " + columns + " " + rows + "\n");
        return classify(readResponseLine());
    }

    /** Requests a power action (shutdown/poweroff) and classifies the agent's response. */
    public synchronized Result power(BridgeControl.PowerAction action) throws IOException {
        Objects.requireNonNull(action, "action");
        write("DROIDLAYER/POWER " + action.wireValue() + "\n");
        return classify(readResponseLine());
    }

    /** Requests a power action by wire name ("shutdown" or "poweroff"). */
    public synchronized Result power(String action) throws IOException {
        BridgeControl.PowerAction parsed = BridgeControl.PowerAction.fromWire(action);
        if (parsed == null) {
            throw new IllegalArgumentException("unsupported power action: " + action);
        }
        return power(parsed);
    }

    private void write(String line) throws IOException {
        outbound.write(line.getBytes(StandardCharsets.US_ASCII));
        outbound.flush();
    }

    private String readResponseLine() throws IOException {
        byte[] bytes = new byte[MAX_RESPONSE_LINE_BYTES];
        int length = 0;
        while (length < bytes.length) {
            int value = inbound.read();
            if (value == -1) {
                if (length == 0) {
                    throw new EOFException("bridge response stream ended");
                }
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
            bytes[length++] = (byte) value;
            if (value == '\n') {
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            }
        }
        return "";
    }

    private static Result classify(String line) {
        if ("DROIDLAYER/READY 1\n".equals(line)) {
            return Result.accepted("ready");
        }
        if ("DROIDLAYER/PONG 1\n".equals(line)) {
            return Result.accepted("pong");
        }
        if ("DROIDLAYER/OK resize\n".equals(line)) {
            return Result.accepted("ok-resize");
        }
        if ("DROIDLAYER/OK power\n".equals(line)) {
            return Result.accepted("ok-power");
        }
        if (line != null && line.startsWith("DROIDLAYER/ERROR ") && line.endsWith("\n")) {
            String kind = line.substring("DROIDLAYER/ERROR ".length(), line.length() - 1);
            return Result.rejected(kind);
        }
        return Result.rejected("malformed-response");
    }

    /** One classified agent response. */
    public static final class Result {
        private final boolean accepted;
        private final String responseKind;

        private Result(boolean accepted, String responseKind) {
            this.accepted = accepted;
            this.responseKind = responseKind;
        }

        static Result accepted(String responseKind) {
            return new Result(true, responseKind);
        }

        static Result rejected(String responseKind) {
            return new Result(false, responseKind);
        }

        public boolean accepted() {
            return accepted;
        }

        /** Accepted kinds: ready, pong, ok-resize. Rejected kinds: the agent's error kind. */
        public String responseKind() {
            return responseKind;
        }
    }
}
