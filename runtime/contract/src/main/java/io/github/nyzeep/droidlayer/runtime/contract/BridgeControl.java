package io.github.nyzeep.droidlayer.runtime.contract;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** Strategy-neutral control behavior for an authenticated Bridge v1 byte stream. */
public final class BridgeControl {
    private static final int MAX_PAYLOAD_CHARS = 2048;
    private static final Pattern SAFE_URL_PATTERN = Pattern.compile("^[hH][tT][tT][pP][sS]?://.+");

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
        return dispatchInternal(requestLine, null, null, null, null);
    }

    /**
     * Dispatches a control request that may operate on the terminal channel. The terminal is an
     * adapter supplied at this seam; transport details remain outside this contract.
     */
    public static Result dispatch(String requestLine, Terminal terminal) {
        if (terminal == null) {
            throw new IllegalArgumentException("terminal");
        }
        return dispatchInternal(requestLine, terminal, null, null, null);
    }

    /**
     * Dispatches a control request that may operate on the power management seam.
     */
    public static Result dispatch(String requestLine, Power power) {
        if (power == null) {
            throw new IllegalArgumentException("power");
        }
        return dispatchInternal(requestLine, null, power, null, null);
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
        return dispatchInternal(requestLine, terminal, power, null, null);
    }

    /**
     * Dispatches a control request that operates on the notification seam.
     */
    public static Result dispatchNotification(String requestLine, Notification notification) {
        if (notification == null) {
            throw new IllegalArgumentException("notification");
        }
        return dispatchInternal(requestLine, null, null, notification, null);
    }

    /**
     * Dispatches a control request that operates on the url opener seam.
     */
    public static Result dispatchOpenUrl(String requestLine, UrlOpener urlOpener) {
        if (urlOpener == null) {
            throw new IllegalArgumentException("urlOpener");
        }
        return dispatchInternal(requestLine, null, null, null, urlOpener);
    }

    /**
     * Dispatches a control request across multiple configured bridge seams.
     */
    public static Result dispatch(String requestLine, Seams seams) {
        if (seams == null) {
            throw new IllegalArgumentException("seams");
        }
        return dispatchInternal(
                requestLine, seams.terminal, seams.power, seams.notification, seams.urlOpener);
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
        return dispatchInternal(requestLine, terminal, power, notification, urlOpener);
    }

    private static Result dispatchInternal(
            String requestLine,
            Terminal terminal,
            Power power,
            Notification notification,
            UrlOpener urlOpener) {
        if ("DROIDLAYER/PING\n".equals(requestLine) || "DROIDLAYER/PING\r\n".equals(requestLine)) {
            return Result.accepted("DROIDLAYER/PONG 1\n");
        }
        if (isPowerCommand(requestLine)) {
            PowerRequest req = parsePower(requestLine);
            if (req == null) {
                return Result.rejected("DROIDLAYER/ERROR malformed-command\n");
            }
            if (power == null) {
                return Result.rejected("DROIDLAYER/ERROR power-unavailable\n");
            }
            return power.power(req.action)
                    ? Result.accepted("DROIDLAYER/OK power\n")
                    : Result.rejected("DROIDLAYER/ERROR power-unavailable\n");
        }
        if (isResizeCommand(requestLine)) {
            ResizeRequest resize = parseResize(requestLine);
            if (resize == null) {
                return Result.rejected("DROIDLAYER/ERROR malformed-command\n");
            }
            if (terminal == null) {
                return Result.rejected("DROIDLAYER/ERROR resize-unavailable\n");
            }
            return terminal.resize(resize.columns, resize.rows)
                    ? Result.accepted("DROIDLAYER/OK resize\n")
                    : Result.rejected("DROIDLAYER/ERROR resize-unavailable\n");
        }
        if (isNotifyCommand(requestLine)) {
            NotifyRequest req = parseNotify(requestLine);
            if (req == null) {
                return Result.rejected("DROIDLAYER/ERROR malformed-command\n");
            }
            if (notification == null) {
                return Result.rejected("DROIDLAYER/ERROR notify-unavailable\n");
            }
            return notification.notify(req.title, req.message)
                    ? Result.accepted("DROIDLAYER/OK notify\n")
                    : Result.rejected("DROIDLAYER/ERROR notify-unavailable\n");
        }
        if (isOpenUrlCommand(requestLine)) {
            OpenUrlRequest req = parseOpenUrl(requestLine);
            if (req == null) {
                return Result.rejected("DROIDLAYER/ERROR malformed-command\n");
            }
            if (urlOpener == null) {
                return Result.rejected("DROIDLAYER/ERROR open-url-unavailable\n");
            }
            return urlOpener.openUrl(req.url)
                    ? Result.accepted("DROIDLAYER/OK open-url\n")
                    : Result.rejected("DROIDLAYER/ERROR open-url-unavailable\n");
        }
        return Result.rejected("DROIDLAYER/ERROR malformed-command\n");
    }

    public static boolean isPowerCommand(String requestLine) {
        if (requestLine == null) {
            return false;
        }
        return requestLine.startsWith("DROIDLAYER/POWER ")
                || "DROIDLAYER/POWER\n".equals(requestLine)
                || "DROIDLAYER/POWER\r\n".equals(requestLine);
    }

    public static boolean isResizeCommand(String requestLine) {
        if (requestLine == null) {
            return false;
        }
        return requestLine.startsWith("DROIDLAYER/RESIZE ")
                || "DROIDLAYER/RESIZE\n".equals(requestLine)
                || "DROIDLAYER/RESIZE\r\n".equals(requestLine);
    }

    public static boolean isNotifyCommand(String requestLine) {
        if (requestLine == null) {
            return false;
        }
        return requestLine.startsWith("DROIDLAYER/NOTIFY ")
                || "DROIDLAYER/NOTIFY\n".equals(requestLine)
                || "DROIDLAYER/NOTIFY\r\n".equals(requestLine);
    }

    public static boolean isOpenUrlCommand(String requestLine) {
        if (requestLine == null) {
            return false;
        }
        return requestLine.startsWith("DROIDLAYER/OPEN-URL ")
                || "DROIDLAYER/OPEN-URL\n".equals(requestLine)
                || "DROIDLAYER/OPEN-URL\r\n".equals(requestLine);
    }

    private static PowerRequest parsePower(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n") || requestLine.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 2 || !"DROIDLAYER/POWER".equals(fields[0])) {
            return null;
        }
        PowerAction action = PowerAction.fromWire(fields[1]);
        if (action == null) {
            return null;
        }
        return new PowerRequest(action);
    }

    private static ResizeRequest parseResize(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n") || requestLine.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 3 || !"DROIDLAYER/RESIZE".equals(fields[0])) {
            return null;
        }
        try {
            int columns = Integer.parseInt(fields[1]);
            int rows = Integer.parseInt(fields[2]);
            if (columns < 1 || columns > 1000 || rows < 1 || rows > 1000) {
                return null;
            }
            return new ResizeRequest(columns, rows);
        } catch (NumberFormatException malformedNumber) {
            return null;
        }
    }

    private static NotifyRequest parseNotify(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n") || requestLine.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 3 || !"DROIDLAYER/NOTIFY".equals(fields[0])) {
            return null;
        }
        String title = decodeNotifyField(fields[1]);
        String message = decodeNotifyField(fields[2]);
        if (title == null || message == null || title.isEmpty() || message.isEmpty()
                || title.length() > MAX_PAYLOAD_CHARS || message.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        return new NotifyRequest(title, message);
    }

    private static String decodeNotifyField(String field) {
        if (field == null || field.isEmpty()) {
            return null;
        }
        try {
            if (field.startsWith("base64:")) {
                byte[] decoded = Base64.getDecoder().decode(field.substring(7));
                return new String(decoded, StandardCharsets.UTF_8);
            }
            return URLDecoder.decode(field, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEncoding) {
            return null;
        }
    }

    private static OpenUrlRequest parseOpenUrl(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n") || requestLine.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 2 || !"DROIDLAYER/OPEN-URL".equals(fields[0])) {
            return null;
        }
        String url = fields[1];
        if (url.isEmpty() || url.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        if (!SAFE_URL_PATTERN.matcher(url).matches()) {
            return null;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                return null;
            }
        } catch (IllegalArgumentException malformedUri) {
            return null;
        }
        return new OpenUrlRequest(url);
    }

    /** Adapter for strategy-neutral power management actions (shutdown/poweroff). */
    @FunctionalInterface
    public interface Power {
        boolean power(PowerAction action);
    }

    /** Adapter for the strategy-neutral terminal behavior required by resize. */
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

    /** Aggregation of adapter seams for unified Bridge dispatching. */
    public static final class Seams {
        private final Terminal terminal;
        private final Power power;
        private final Notification notification;
        private final UrlOpener urlOpener;

        public Seams(
                Terminal terminal,
                Power power,
                Notification notification,
                UrlOpener urlOpener) {
            this.terminal = terminal;
            this.power = power;
            this.notification = notification;
            this.urlOpener = urlOpener;
        }

        public static Seams of(Notification notification) {
            return new Seams(null, null, notification, null);
        }

        public static Seams of(UrlOpener urlOpener) {
            return new Seams(null, null, null, urlOpener);
        }

        public static Seams of(Notification notification, UrlOpener urlOpener) {
            return new Seams(null, null, notification, urlOpener);
        }

        public static Seams of(
                Terminal terminal,
                Power power,
                Notification notification,
                UrlOpener urlOpener) {
            return new Seams(terminal, power, notification, urlOpener);
        }
    }

    private static final class PowerRequest {
        private final PowerAction action;

        private PowerRequest(PowerAction action) {
            this.action = action;
        }
    }

    private static final class ResizeRequest {
        private final int columns;
        private final int rows;

        private ResizeRequest(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    private static final class NotifyRequest {
        private final String title;
        private final String message;

        private NotifyRequest(String title, String message) {
            this.title = title;
            this.message = message;
        }
    }

    private static final class OpenUrlRequest {
        private final String url;

        private OpenUrlRequest(String url) {
            this.url = url;
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
