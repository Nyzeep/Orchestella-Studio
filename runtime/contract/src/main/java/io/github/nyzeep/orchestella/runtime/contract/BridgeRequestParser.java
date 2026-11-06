package io.github.nyzeep.orchestella.runtime.contract;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Request-line parsing for the Bridge v1 control verbs, extracted verbatim from
 * {@link BridgeControl} so routing ({@code dispatch}) and per-verb field parsing evolve in
 * separate files.
 *
 * <p>Package-private on purpose: the wire contract is exercised only through
 * {@link BridgeControl}; nothing here changes the public API.
 */
final class BridgeRequestParser {
    private static final int MAX_PAYLOAD_CHARS = 2048;
    private static final Pattern SAFE_URL_PATTERN = Pattern.compile("^[hH][tT][tT][pP][sS]?://.+");

    private BridgeRequestParser() {}

    static boolean matchesVerb(String line, String verb) {
        if (line == null) {
            return false;
        }
        return line.startsWith("ORCHESTELLA/" + verb + " ")
                || ("ORCHESTELLA/" + verb + "\n").equals(line)
                || ("ORCHESTELLA/" + verb + "\r\n").equals(line)
                || line.startsWith("DROIDLAYER/" + verb + " ")
                || ("DROIDLAYER/" + verb + "\n").equals(line)
                || ("DROIDLAYER/" + verb + "\r\n").equals(line);
    }

    static boolean isVerbField(String field, String verb) {
        return ("ORCHESTELLA/" + verb).equals(field) || ("DROIDLAYER/" + verb).equals(field);
    }

    static PowerRequest parsePower(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n") || requestLine.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 2 || !isVerbField(fields[0], "POWER")) {
            return null;
        }
        BridgeControl.PowerAction action = BridgeControl.PowerAction.fromWire(fields[1]);
        if (action == null) {
            return null;
        }
        return new PowerRequest(action);
    }

    static ResizeRequest parseResize(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n") || requestLine.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 3 || !isVerbField(fields[0], "RESIZE")) {
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

    static NotifyRequest parseNotify(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n") || requestLine.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 3 || !isVerbField(fields[0], "NOTIFY")) {
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

    static OpenUrlRequest parseOpenUrl(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n") || requestLine.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length != 2 || !isVerbField(fields[0], "OPEN-URL")) {
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

    static DisplayRequest parseDisplay(String requestLine) {
        if (requestLine == null || !requestLine.endsWith("\n") || requestLine.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        String line = requestLine.substring(0, requestLine.length() - 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String[] fields = line.split(" ", -1);
        if (fields.length < 2 || !isVerbField(fields[0], "DISPLAY")) {
            return null;
        }
        String subcommand = fields[1];
        if ("MODE".equals(subcommand)) {
            if (fields.length != 5) {
                return null;
            }
            try {
                int width = Integer.parseInt(fields[2]);
                int height = Integer.parseInt(fields[3]);
                double scale = Double.parseDouble(fields[4]);
                if (width < 100 || width > 7680 || height < 100 || height > 7680) {
                    return null;
                }
                if (Double.isNaN(scale) || Double.isInfinite(scale) || scale < 0.1 || scale > 5.0) {
                    return null;
                }
                return new DisplayRequest(DisplayCommandType.MODE, width, height, scale, 0);
            } catch (NumberFormatException e) {
                return null;
            }
        } else if ("RESIZE".equals(subcommand)) {
            if (fields.length != 4) {
                return null;
            }
            try {
                int width = Integer.parseInt(fields[2]);
                int height = Integer.parseInt(fields[3]);
                if (width < 100 || width > 7680 || height < 100 || height > 7680) {
                    return null;
                }
                return new DisplayRequest(DisplayCommandType.RESIZE, width, height, 0.0, 0);
            } catch (NumberFormatException e) {
                return null;
            }
        } else if ("FPS".equals(subcommand)) {
            if (fields.length != 3) {
                return null;
            }
            try {
                int fps = Integer.parseInt(fields[2]);
                if (fps < 1 || fps > 240) {
                    return null;
                }
                return new DisplayRequest(DisplayCommandType.FPS, 0, 0, 0.0, fps);
            } catch (NumberFormatException e) {
                return null;
            }
        } else if ("LAUNCH".equals(subcommand)) {
            if (fields.length != 2) {
                return null;
            }
            return new DisplayRequest(DisplayCommandType.LAUNCH, 0, 0, 0.0, 0);
        }
        return null;
    }

    enum DisplayCommandType {
        MODE,
        RESIZE,
        FPS,
        LAUNCH
    }

    static final class DisplayRequest {
        final DisplayCommandType type;
        final int width;
        final int height;
        final double scale;
        final int fps;

        DisplayRequest(DisplayCommandType type, int width, int height, double scale, int fps) {
            this.type = type;
            this.width = width;
            this.height = height;
            this.scale = scale;
            this.fps = fps;
        }
    }

    static final class PowerRequest {
        final BridgeControl.PowerAction action;

        PowerRequest(BridgeControl.PowerAction action) {
            this.action = action;
        }
    }

    static final class ResizeRequest {
        final int columns;
        final int rows;

        ResizeRequest(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }

    static final class NotifyRequest {
        final String title;
        final String message;

        NotifyRequest(String title, String message) {
            this.title = title;
            this.message = message;
        }
    }

    static final class OpenUrlRequest {
        final String url;

        OpenUrlRequest(String url) {
            this.url = url;
        }
    }
}
