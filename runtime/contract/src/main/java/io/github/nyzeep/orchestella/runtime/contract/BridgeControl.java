package io.github.nyzeep.orchestella.runtime.contract;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Strategy-neutral control behavior for an authenticated Bridge v1 byte stream.
 *
 * <p>File layout: routing ({@code dispatch*}) and the adapter seams live here; per-verb
 * request-line field parsing lives in {@link BridgeRequestParser}. The public API of this
 * class is unchanged.
 */
public final class BridgeControl {
    public static final int MAX_CLIPBOARD_PAYLOAD_BYTES = 262144;

    private BridgeControl() {}

    /** Strategy-neutral power actions supported by the Bridge v1 control channel. */
    public enum PowerAction {
        SHUTDOWN("shutdown"),
        POWEROFF("poweroff");

        private final String wireValue;

        PowerAction(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }

        public static PowerAction fromWire(String value) {
            if (value == null) {
                return null;
            }
            for (PowerAction action : values()) {
                if (action.wireValue.equals(value)) {
                    return action;
                }
            }
            return null;
        }
    }

    /**
     * Dispatches one already-authenticated control request. Handshake and transport setup happen at
     * their own seams before this method is called.
     */
    public static Result dispatch(String requestLine) {
        return dispatchInternal(requestLine, null, null, null, null, null, null);
    }

    /**
     * Dispatches a control request that operates on the clipboard seam.
     */
    public static Result dispatchClipboard(String requestLine, Clipboard clipboard) {
        if (clipboard == null) {
            throw new IllegalArgumentException("clipboard");
        }
        return dispatchInternal(requestLine, null, null, null, null, null, clipboard);
    }

    /**
     * Dispatches a control request that operates on the clipboard seam.
     */
    public static Result dispatch(String requestLine, Clipboard clipboard) {
        if (clipboard == null) {
            throw new IllegalArgumentException("clipboard");
        }
        return dispatchInternal(requestLine, null, null, null, null, null, clipboard);
    }

    /**
     * Dispatches a control request that may operate on the terminal channel. The terminal is an
     * adapter supplied at this seam; transport details remain outside this contract.
     */
    public static Result dispatch(String requestLine, Terminal terminal) {
        if (terminal == null) {
            throw new IllegalArgumentException("terminal");
        }
        return dispatchInternal(requestLine, terminal, null, null, null, null, null);
    }

    /**
     * Dispatches a control request that may operate on the power management seam.
     */
    public static Result dispatch(String requestLine, Power power) {
        if (power == null) {
            throw new IllegalArgumentException("power");
        }
        return dispatchInternal(requestLine, null, power, null, null, null, null);
    }

    /**
     * Dispatches a control request that may operate on either the terminal or power management seams.
     */
    public static Result dispatch(String requestLine, Terminal terminal, Power power) {
        if (terminal == null) {
            throw new IllegalArgumentException("terminal");
        }
        if (power == null) {
            throw new IllegalArgumentException("power");
        }
        return dispatchInternal(requestLine, terminal, power, null, null, null, null);
    }

    /**
     * Dispatches a control request that operates on the notification seam.
     */
    public static Result dispatchNotification(String requestLine, Notification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("notification");
        }
        return dispatchInternal(requestLine, null, null, notification, null, null, null);
    }

    /**
     * Dispatches a control request that operates on the url opener seam.
     */
    public static Result dispatchOpenUrl(String requestLine, UrlOpener urlOpener) {
        if (urlOpener == null) {
            throw new IllegalArgumentException("urlOpener");
        }
        return dispatchInternal(requestLine, null, null, null, urlOpener, null, null);
    }

    /**
     * Dispatches a control request that operates on the display management seam.
     */
    public static Result dispatchDisplay(String requestLine, Display display) {
        if (display == null) {
            throw new IllegalArgumentException("display");
        }
        return dispatchInternal(requestLine, null, null, null, null, display, null);
    }

    /**
     * Dispatches a control request across multiple configured bridge seams.
     */
    public static Result dispatch(String requestLine, Seams seams) {
        if (seams == null) {
            throw new IllegalArgumentException("seams");
        }
        return dispatchInternal(
                requestLine,
                seams.terminal,
                seams.power,
                seams.notification,
                seams.urlOpener,
                seams.display,
                seams.clipboard);
    }

    /**
     * Dispatches a control request across all supported bridge seams.
     */
    public static Result dispatch(
            String requestLine,
            Terminal terminal,
            Power power,
            Notification notification,
            UrlOpener urlOpener) {
        return dispatchInternal(requestLine, terminal, power, notification, urlOpener, null, null);
    }

    /**
     * Dispatches a control request across all supported bridge seams including display control.
     */
    public static Result dispatch(
            String requestLine,
            Terminal terminal,
            Power power,
            Notification notification,
            UrlOpener urlOpener,
            Display display) {
        return dispatchInternal(requestLine, terminal, power, notification, urlOpener, display, null);
    }

    /**
     * Dispatches a control request across all supported bridge seams including display and clipboard control.
     */
    public static Result dispatch(
            String requestLine,
            Terminal terminal,
            Power power,
            Notification notification,
            UrlOpener urlOpener,
            Display display,
            Clipboard clipboard) {
        return dispatchInternal(requestLine, terminal, power, notification, urlOpener, display, clipboard);
    }

    private static Result dispatchInternal(
            String requestLine,
            Terminal terminal,
            Power power,
            Notification notification,
            UrlOpener urlOpener,
            Display display,
            Clipboard clipboard) {
        if ("ORCHESTELLA/PING\n".equals(requestLine) || "ORCHESTELLA/PING\r\n".equals(requestLine)
                || "DROIDLAYER/PING\n".equals(requestLine) || "DROIDLAYER/PING\r\n".equals(requestLine)) {
            return Result.accepted("ORCHESTELLA/PONG 1\n");
        }
        if (isClipboardCommand(requestLine)) {
            return dispatchClipboardInternal(requestLine, clipboard);
        }
        if (isPowerCommand(requestLine)) {
            BridgeRequestParser.PowerRequest req = BridgeRequestParser.parsePower(requestLine);
            if (req == null) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            if (power == null) {
                return Result.rejected("ORCHESTELLA/ERROR power-unavailable\n");
            }
            return power.power(req.action)
                    ? Result.accepted("ORCHESTELLA/OK power\n")
                    : Result.rejected("ORCHESTELLA/ERROR power-unavailable\n");
        }
        if (isResizeCommand(requestLine)) {
            BridgeRequestParser.ResizeRequest resize = BridgeRequestParser.parseResize(requestLine);
            if (resize == null) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            if (terminal == null) {
                return Result.rejected("ORCHESTELLA/ERROR resize-unavailable\n");
            }
            return terminal.resize(resize.columns, resize.rows)
                    ? Result.accepted("ORCHESTELLA/OK resize\n")
                    : Result.rejected("ORCHESTELLA/ERROR resize-unavailable\n");
        }
        if (isNotifyCommand(requestLine)) {
            BridgeRequestParser.NotifyRequest req = BridgeRequestParser.parseNotify(requestLine);
            if (req == null) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            if (notification == null) {
                return Result.rejected("ORCHESTELLA/ERROR notify-unavailable\n");
            }
            return notification.notify(req.title, req.message)
                    ? Result.accepted("ORCHESTELLA/OK notify\n")
                    : Result.rejected("ORCHESTELLA/ERROR notify-unavailable\n");
        }
        if (isOpenUrlCommand(requestLine)) {
            BridgeRequestParser.OpenUrlRequest req = BridgeRequestParser.parseOpenUrl(requestLine);
            if (req == null) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            if (urlOpener == null) {
                return Result.rejected("ORCHESTELLA/ERROR open-url-unavailable\n");
            }
            return urlOpener.openUrl(req.url)
                    ? Result.accepted("ORCHESTELLA/OK open-url\n")
                    : Result.rejected("ORCHESTELLA/ERROR open-url-unavailable\n");
        }
        if (isDisplayCommand(requestLine)) {
            BridgeRequestParser.DisplayRequest req = BridgeRequestParser.parseDisplay(requestLine);
            if (req == null) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            if (display == null) {
                return Result.rejected("ORCHESTELLA/ERROR display-unavailable\n");
            }
            boolean success = false;
            switch (req.type) {
                case MODE:
                    success = display.setMode(req.width, req.height, req.scale);
                    break;
                case RESIZE:
                    success = display.resize(req.width, req.height);
                    break;
                case FPS:
                    success = display.setFps(req.fps);
                    break;
                case LAUNCH:
                    success = display.launchDesktop();
                    break;
            }
            return success
                    ? Result.accepted("ORCHESTELLA/OK display\n")
                    : Result.rejected("ORCHESTELLA/ERROR display-unavailable\n");
        }
        return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
    }

    public static boolean isClipboardCommand(String requestLine) {
        return BridgeRequestParser.matchesVerb(requestLine, "CLIPBOARD");
    }

    private static Result dispatchClipboardInternal(String requestLine, Clipboard clipboard) {
        if (requestLine == null || !requestLine.endsWith("\n")) {
            return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length < 2 || !BridgeRequestParser.isVerbField(fields[0], "CLIPBOARD")) {
            return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
        }
        String subcommand = fields[1];
        if ("PUT".equals(subcommand)) {
            if (fields.length != 3) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            String payloadField = fields[2];
            if (!payloadField.startsWith("base64:")) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            String base64Data = payloadField.substring("base64:".length());
            if (base64Data.length() > 350000) {
                return Result.rejected("ORCHESTELLA/ERROR payload-too-large\n");
            }
            byte[] decodedBytes;
            try {
                decodedBytes = Base64.getDecoder().decode(base64Data);
            } catch (IllegalArgumentException e) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            if (decodedBytes.length > MAX_CLIPBOARD_PAYLOAD_BYTES) {
                return Result.rejected("ORCHESTELLA/ERROR payload-too-large\n");
            }
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            String text;
            try {
                text = decoder.decode(ByteBuffer.wrap(decodedBytes)).toString();
            } catch (CharacterCodingException e) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            if (clipboard == null) {
                return Result.rejected("ORCHESTELLA/ERROR clipboard-unavailable\n");
            }
            return clipboard.onClipboardPut(text)
                    ? Result.accepted("ORCHESTELLA/OK clipboard\n")
                    : Result.rejected("ORCHESTELLA/ERROR clipboard-unavailable\n");
        } else if ("GET".equals(subcommand)) {
            if (fields.length != 2) {
                return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
            }
            if (clipboard == null) {
                return Result.rejected("ORCHESTELLA/ERROR clipboard-unavailable\n");
            }
            String text = clipboard.onClipboardGet();
            if (text == null || text.isEmpty()) {
                return Result.accepted("ORCHESTELLA/OK clipboard base64:\n");
            }
            byte[] utf8Bytes = text.getBytes(StandardCharsets.UTF_8);
            if (utf8Bytes.length > MAX_CLIPBOARD_PAYLOAD_BYTES) {
                return Result.rejected("ORCHESTELLA/ERROR payload-too-large\n");
            }
            String base64 = Base64.getEncoder().encodeToString(utf8Bytes);
            return Result.accepted("ORCHESTELLA/OK clipboard base64:" + base64 + "\n");
        }
        return Result.rejected("ORCHESTELLA/ERROR malformed-command\n");
    }

    public static boolean isPowerCommand(String requestLine) {
        return BridgeRequestParser.matchesVerb(requestLine, "POWER");
    }

    public static boolean isResizeCommand(String requestLine) {
        return BridgeRequestParser.matchesVerb(requestLine, "RESIZE");
    }

    public static boolean isNotifyCommand(String requestLine) {
        return BridgeRequestParser.matchesVerb(requestLine, "NOTIFY");
    }

    public static boolean isOpenUrlCommand(String requestLine) {
        return BridgeRequestParser.matchesVerb(requestLine, "OPEN-URL");
    }

    public static boolean isDisplayCommand(String requestLine) {
        return BridgeRequestParser.matchesVerb(requestLine, "DISPLAY");
    }

    /** Adapter for strategy-neutral power management actions (shutdown/poweroff). */
    @FunctionalInterface
    public interface Power {
        boolean power(PowerAction action);
    }

    /** Adapter for strategy-neutral terminal behavior required by resize. */
    @FunctionalInterface
    public interface Terminal {
        boolean resize(int columns, int rows);
    }

    /** Adapter for strategy-neutral system notification dispatching. */
    @FunctionalInterface
    public interface Notification {
        boolean notify(String title, String message);
    }

    /** Adapter for strategy-neutral browser URL opening. */
    @FunctionalInterface
    public interface UrlOpener {
        boolean openUrl(String url);
    }

    /** Adapter for strategy-neutral display mode, resize, and frame rate management. */
    public interface Display {
        boolean setMode(int width, int height, double scale);
        boolean resize(int width, int height);
        boolean setFps(int fps);

        /**
         * Requests launching or ensuring the graphical desktop compositor on the guest.
         *
         * @return true if accepted, false otherwise
         */
        default boolean launchDesktop() {
            return true;
        }

        /** Adapts a {@link DisplayControl} to the bridge display seam. */
        static Display from(DisplayControl displayControl) {
            if (displayControl == null) {
                throw new IllegalArgumentException("displayControl");
            }
            return new Display() {
                @Override
                public boolean setMode(int width, int height, double scale) {
                    return displayControl.resizeDisplay(width, height) && displayControl.updateScale(scale);
                }

                @Override
                public boolean resize(int width, int height) {
                    return displayControl.resizeDisplay(width, height);
                }

                @Override
                public boolean setFps(int fps) {
                    return displayControl.updateTargetFps(fps);
                }
            };
        }
    }

    /** Adapter for strategy-neutral clipboard synchronization. */
    public interface Clipboard {
        boolean onClipboardPut(String text);
        String onClipboardGet();
    }

    /** Aggregation of adapter seams for unified Bridge dispatching. */
    public static final class Seams {
        private final Terminal terminal;
        private final Power power;
        private final Notification notification;
        private final UrlOpener urlOpener;
        private final Display display;
        private final Clipboard clipboard;

        public Seams(
                Terminal terminal,
                Power power,
                Notification notification,
                UrlOpener urlOpener) {
            this(terminal, power, notification, urlOpener, null, null);
        }

        public Seams(
                Terminal terminal,
                Power power,
                Notification notification,
                UrlOpener urlOpener,
                Display display) {
            this(terminal, power, notification, urlOpener, display, null);
        }

        public Seams(
                Terminal terminal,
                Power power,
                Notification notification,
                UrlOpener urlOpener,
                Display display,
                Clipboard clipboard) {
            this.terminal = terminal;
            this.power = power;
            this.notification = notification;
            this.urlOpener = urlOpener;
            this.display = display;
            this.clipboard = clipboard;
        }

        public static Seams of(Clipboard clipboard) {
            return new Seams(null, null, null, null, null, clipboard);
        }

        public static Seams of(Notification notification) {
            return new Seams(null, null, notification, null, null, null);
        }

        public static Seams of(UrlOpener urlOpener) {
            return new Seams(null, null, null, urlOpener, null, null);
        }

        public static Seams of(Display display) {
            return new Seams(null, null, null, null, display, null);
        }

        public static Seams of(Notification notification, UrlOpener urlOpener) {
            return new Seams(null, null, notification, urlOpener, null, null);
        }

        public static Seams of(
                Terminal terminal,
                Power power,
                Notification notification,
                UrlOpener urlOpener) {
            return new Seams(terminal, power, notification, urlOpener, null, null);
        }

        public static Seams of(
                Terminal terminal,
                Power power,
                Notification notification,
                UrlOpener urlOpener,
                Display display) {
            return new Seams(terminal, power, notification, urlOpener, display, null);
        }

        public static Seams of(
                Terminal terminal,
                Power power,
                Notification notification,
                UrlOpener urlOpener,
                Display display,
                Clipboard clipboard) {
            return new Seams(terminal, power, notification, urlOpener, display, clipboard);
        }

        public Terminal terminal() {
            return terminal;
        }

        public Power power() {
            return power;
        }

        public Notification notification() {
            return notification;
        }

        public UrlOpener urlOpener() {
            return urlOpener;
        }

        public Display display() {
            return display;
        }

        public Clipboard clipboard() {
            return clipboard;
        }
    }

    /** Observable control result returned to the transport adapter. */
    public static final class Result {
        private final boolean accepted;
        private final String wireResponse;

        private Result(boolean accepted, String wireResponse) {
            this.accepted = accepted;
            this.wireResponse = wireResponse;
        }

        private static Result accepted(String wireResponse) {
            return new Result(true, wireResponse);
        }

        private static Result rejected(String wireResponse) {
            return new Result(false, wireResponse);
        }

        public boolean accepted() {
            return accepted;
        }

        public String wireResponse() {
            return wireResponse;
        }
    }
}
