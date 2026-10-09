package io.github.nyzeep.orchestella.runtime.contract;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Strategy-neutral host-side Bridge v1 client. The host initiates the
 * handshake and the command lines; the guest agent answers READY / PONG /
 * OK resize, or {@code DROIDLAYER/ERROR} with a rejection kind. Transport
 * adapters supply plain byte streams and stay out of the protocol.
 */
public final class BridgeClient {
    private static final int MAX_RESPONSE_LINE_BYTES = 512 * 1024;

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
        write("ORCHESTELLA/HELLO 1 1 " + token + "\n");
        return classify(readResponseLine());
    }

    /** Sends one keepalive ping and classifies the agent's response. */
    public synchronized Result ping() throws IOException {
        write("ORCHESTELLA/PING\n");
        return classify(readResponseLine());
    }

    /** Requests a terminal grid size and classifies the agent's response. */
    public synchronized Result resize(int columns, int rows) throws IOException {
        if (columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("terminal size must be positive");
        }
        write("ORCHESTELLA/RESIZE " + columns + " " + rows + "\n");
        return classify(readResponseLine());
    }

    /** Requests a power action (shutdown/poweroff) and classifies the agent's response. */
    public synchronized Result power(BridgeControl.PowerAction action) throws IOException {
        Objects.requireNonNull(action, "action");
        write("ORCHESTELLA/POWER " + action.wireValue() + "\n");
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

    /** Requests showing an Android system notification and classifies the agent's response. */
    public synchronized Result notify(String title, String message) throws IOException {
        if (title == null || title.isEmpty() || message == null || message.isEmpty()) {
            throw new IllegalArgumentException("title and message must not be empty");
        }
        String encodedTitle = java.net.URLEncoder.encode(title, StandardCharsets.UTF_8);
        String encodedMessage = java.net.URLEncoder.encode(message, StandardCharsets.UTF_8);
        write("ORCHESTELLA/NOTIFY " + encodedTitle + " " + encodedMessage + "\n");
        return classify(readResponseLine());
    }

    /** Requests opening an HTTP/HTTPS URL on the host and classifies the agent's response. */
    public synchronized Result openUrl(String url) throws IOException {
        if (url == null || url.length() > 2048 || !url.matches("^[hH][tT][tT][pP][sS]?://.+")) {
            throw new IllegalArgumentException("invalid url: " + url);
        }
        write("ORCHESTELLA/OPEN-URL " + url + "\n");
        return classify(readResponseLine());
    }

    /** Requests setting the display mode (resolution and scale) and classifies the agent's response. */
    public synchronized Result displayMode(int width, int height, double scale) throws IOException {
        if (width < 100 || width > 7680 || height < 100 || height > 7680) {
            throw new IllegalArgumentException("display dimensions must be between 100 and 7680: " + width + "x" + height);
        }
        if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < 0.1 || scale > 5.0) {
            throw new IllegalArgumentException("display scale must be between 0.1 and 5.0: " + scale);
        }
        write("ORCHESTELLA/DISPLAY MODE " + width + " " + height + " " + scale + "\n");
        return classify(readResponseLine());
    }

    /** Requests dynamic display resize and classifies the agent's response. */
    public synchronized Result displayResize(int width, int height) throws IOException {
        if (width < 100 || width > 7680 || height < 100 || height > 7680) {
            throw new IllegalArgumentException("display dimensions must be between 100 and 7680: " + width + "x" + height);
        }
        write("ORCHESTELLA/DISPLAY RESIZE " + width + " " + height + "\n");
        return classify(readResponseLine());
    }

    /** Requests setting the target display frame rate and classifies the agent's response. */
    public synchronized Result displayFps(int fps) throws IOException {
        if (fps < 1 || fps > 240) {
            throw new IllegalArgumentException("fps must be between 1 and 240: " + fps);
        }
        write("ORCHESTELLA/DISPLAY FPS " + fps + "\n");
        return classify(readResponseLine());
    }

    /** Requests launching or ensuring the graphical desktop compositor on the guest. */
    public synchronized Result desktopLaunch() throws IOException {
        write("ORCHESTELLA/DISPLAY LAUNCH\n");
        return classify(readResponseLine());
    }

    /** Requests sending clipboard text to the agent and classifies the agent's response. */
    public synchronized Result clipboardPut(String text) throws IOException {
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
        if (utf8Bytes.length > BridgeControl.MAX_CLIPBOARD_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("clipboard payload exceeds 256KB limit: " + utf8Bytes.length + " bytes");
        }
        String base64 = Base64.getEncoder().encodeToString(utf8Bytes);
        write("ORCHESTELLA/CLIPBOARD PUT base64:" + base64 + "\n");
        return classify(readResponseLine());
    }

    /** Requests retrieving current clipboard text from the agent and classifies the response. */
    public synchronized Result clipboardGet() throws IOException {
        write("ORCHESTELLA/CLIPBOARD GET\n");
        return classify(readResponseLine());
    }

    private void write(String line) throws IOException {
        outbound.write(line.getBytes(StandardCharsets.US_ASCII));
        outbound.flush();
    }

    private String readResponseLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (buffer.size() < MAX_RESPONSE_LINE_BYTES) {
            int value = inbound.read();
            if (value == -1) {
                if (buffer.size() == 0) {
                    throw new EOFException("bridge response stream ended");
                }
                return buffer.toString(StandardCharsets.US_ASCII);
            }
            buffer.write(value);
            if (value == '\n') {
                return buffer.toString(StandardCharsets.US_ASCII);
            }
        }
        return "";
    }

    private static Result classify(String line) {
        if (line == null) {
            return Result.rejected("malformed-response");
        }
        String normalized = line;
        if (normalized.startsWith("DROIDLAYER/")) {
            normalized = "ORCHESTELLA/" + normalized.substring("DROIDLAYER/".length());
        }
        if ("ORCHESTELLA/READY 1\n".equals(normalized)) {
            return Result.accepted("ready");
        }
        if ("ORCHESTELLA/PONG 1\n".equals(normalized)) {
            return Result.accepted("pong");
        }
        if ("ORCHESTELLA/OK resize\n".equals(normalized)) {
            return Result.accepted("ok-resize");
        }
        if ("ORCHESTELLA/OK power\n".equals(normalized)) {
            return Result.accepted("ok-power");
        }
        if ("ORCHESTELLA/OK notify\n".equals(normalized)) {
            return Result.accepted("ok-notify");
        }
        if ("ORCHESTELLA/OK open-url\n".equals(normalized)) {
            return Result.accepted("ok-open-url");
        }
        if ("ORCHESTELLA/OK display\n".equals(normalized)) {
            return Result.accepted("ok-display");
        }
        if ("ORCHESTELLA/OK clipboard\n".equals(normalized)) {
            return Result.accepted("ok-clipboard");
        }
        if (normalized.startsWith("ORCHESTELLA/OK clipboard base64:") && normalized.endsWith("\n")) {
            String base64 = normalized.substring("ORCHESTELLA/OK clipboard base64:".length(), normalized.length() - 1);
            if (base64.isEmpty()) {
                return Result.accepted("ok-clipboard", "");
            }
            try {
                byte[] decoded = Base64.getDecoder().decode(base64);
                String text = new String(decoded, StandardCharsets.UTF_8);
                return Result.accepted("ok-clipboard", text);
            } catch (IllegalArgumentException e) {
                return Result.rejected("malformed-response");
            }
        }
        if (normalized.startsWith("ORCHESTELLA/ERROR ") && normalized.endsWith("\n")) {
            String kind = normalized.substring("ORCHESTELLA/ERROR ".length(), normalized.length() - 1);
            return Result.rejected(kind);
        }
        return Result.rejected("malformed-response");
    }

    /** One classified agent response. */
    public static final class Result {
        private final boolean accepted;
        private final String responseKind;
        private final String payload;

        private Result(boolean accepted, String responseKind) {
            this(accepted, responseKind, null);
        }

        private Result(boolean accepted, String responseKind, String payload) {
            this.accepted = accepted;
            this.responseKind = responseKind;
            this.payload = payload;
        }

        static Result accepted(String responseKind) {
            return new Result(true, responseKind, null);
        }

        static Result accepted(String responseKind, String payload) {
            return new Result(true, responseKind, payload);
        }

        static Result rejected(String responseKind) {
            return new Result(false, responseKind, null);
        }

        public boolean accepted() {
            return accepted;
        }

        /** Accepted kinds: ready, pong, ok-resize, ok-clipboard. Rejected kinds: the agent's error kind. */
        public String responseKind() {
            return responseKind;
        }

        /** Optional decoded response payload (e.g. text for clipboard get), or null if not applicable. */
        public String payload() {
            return payload;
        }
    }
}
