package io.github.nyzeep.orchestella.guestassets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Protocol parsing and wire serialization utilities for DroidLayer Display Agent.
 */
public final class DisplayAgentProtocol {

    public static final int FRAME_MAGIC = 0x444C4652; // "DLFR" in ASCII
    public static final int HEADER_SIZE = 36;
    public static final int MIN_DIMENSION = 100;
    public static final int MAX_DIMENSION = 7680;
    public static final double MIN_SCALE = 0.1;
    public static final double MAX_SCALE = 5.0;
    public static final int MIN_FPS = 1;
    public static final int MAX_FPS = 240;

    public enum DisplayCommandType {
        MODE,
        RESIZE,
        FPS
    }

    public record DisplayCommand(
            DisplayCommandType type,
            int width,
            int height,
            double scale,
            int fps) {

        public String successResponse() {
            return "DROIDLAYER/OK display\n";
        }
    }

    public enum InputEventType {
        POINTER,
        TOUCH,
        KEY,
        TEXT
    }

    public record InputEvent(
            InputEventType type,
            String action,
            double x,
            double y,
            double deltaX,
            double deltaY,
            int button,
            int pointerId,
            int keyCode,
            int modifiers,
            String textPayload,
            long timestampNs) {}

    public record FrameHeader(
            int magic,
            int frameNumber,
            int width,
            int height,
            int stride,
            int format,
            long timestampNs,
            int payloadLength) {}

    private DisplayAgentProtocol() {}

    public static boolean isValidDisplayCommand(String line) {
        return parseDisplayCommand(line) != null;
    }

    public static DisplayCommand parseDisplayCommand(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        String[] tokens = trimmed.split(" ");
        if (tokens.length < 2 || !"DROIDLAYER/DISPLAY".equals(tokens[0])) {
            return null;
        }

        String sub = tokens[1];
        try {
            if ("MODE".equals(sub)) {
                if (tokens.length != 5) {
                    return null;
                }
                int width = Integer.parseInt(tokens[2]);
                int height = Integer.parseInt(tokens[3]);
                double scale = Double.parseDouble(tokens[4]);
                if (width < MIN_DIMENSION || width > MAX_DIMENSION
                        || height < MIN_DIMENSION || height > MAX_DIMENSION
                        || Double.isNaN(scale) || Double.isInfinite(scale)
                        || scale < MIN_SCALE || scale > MAX_SCALE) {
                    return null;
                }
                return new DisplayCommand(DisplayCommandType.MODE, width, height, scale, 0);
            } else if ("RESIZE".equals(sub)) {
                if (tokens.length != 4) {
                    return null;
                }
                int width = Integer.parseInt(tokens[2]);
                int height = Integer.parseInt(tokens[3]);
                if (width < MIN_DIMENSION || width > MAX_DIMENSION
                        || height < MIN_DIMENSION || height > MAX_DIMENSION) {
                    return null;
                }
                return new DisplayCommand(DisplayCommandType.RESIZE, width, height, 1.0, 0);
            } else if ("FPS".equals(sub)) {
                if (tokens.length != 3) {
                    return null;
                }
                int fps = Integer.parseInt(tokens[2]);
                if (fps < MIN_FPS || fps > MAX_FPS) {
                    return null;
                }
                return new DisplayCommand(DisplayCommandType.FPS, 0, 0, 0.0, fps);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }

        return null;
    }

    public static String buildWlrRandrCommand(String output, int width, int height, Double scale) {
        StringBuilder sb = new StringBuilder();
        sb.append("wlr-randr --output ").append(output).append(" --custom-mode ").append(width).append("x").append(height);
        if (scale != null && scale > 0.0) {
            sb.append(" --scale ").append(scale);
        }
        return sb.toString();
    }

    public static InputEvent parseInputEvent(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        String[] tokens = trimmed.split(" ");
        if (tokens.length < 2) {
            return null;
        }

        try {
            switch (tokens[0]) {
                case "INPUT/POINTER":
                    if (tokens.length != 8) {
                        return null;
                    }
                    String pAction = tokens[1];
                    double px = Double.parseDouble(tokens[2]);
                    double py = Double.parseDouble(tokens[3]);
                    double deltaX = Double.parseDouble(tokens[4]);
                    double deltaY = Double.parseDouble(tokens[5]);
                    int button = Integer.parseInt(tokens[6]);
                    long pTs = Long.parseLong(tokens[7]);
                    return new InputEvent(InputEventType.POINTER, pAction, px, py, deltaX, deltaY, button, 0, 0, 0, null, pTs);

                case "INPUT/TOUCH":
                    if (tokens.length != 6) {
                        return null;
                    }
                    String tAction = tokens[1];
                    int pointerId = Integer.parseInt(tokens[2]);
                    double tx = Double.parseDouble(tokens[3]);
                    double ty = Double.parseDouble(tokens[4]);
                    long tTs = Long.parseLong(tokens[5]);
                    return new InputEvent(InputEventType.TOUCH, tAction, tx, ty, 0.0, 0.0, 0, pointerId, 0, 0, null, tTs);

                case "INPUT/KEY":
                    if (tokens.length != 5) {
                        return null;
                    }
                    String kAction = tokens[1];
                    int keyCode = Integer.parseInt(tokens[2]);
                    int modifiers = Integer.parseInt(tokens[3]);
                    long kTs = Long.parseLong(tokens[4]);
                    return new InputEvent(InputEventType.KEY, kAction, 0.0, 0.0, 0.0, 0.0, 0, 0, keyCode, modifiers, null, kTs);

                case "INPUT/TEXT":
                    if (tokens.length != 3) {
                        return null;
                    }
                    if (!tokens[1].startsWith("base64:")) {
                        return null;
                    }
                    String b64 = tokens[1].substring("base64:".length());
                    byte[] decoded = Base64.getDecoder().decode(b64);
                    String text = new String(decoded, StandardCharsets.UTF_8);
                    long textTs = Long.parseLong(tokens[2]);
                    return new InputEvent(InputEventType.TEXT, "COMMIT", 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0, text, textTs);

                default:
                    return null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public static byte[] encodeFrameHeader(
            int frameNumber,
            int width,
            int height,
            int stride,
            int format,
            long timestampNs,
            int payloadLength) {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(FRAME_MAGIC);
        buf.putInt(frameNumber);
        buf.putInt(width);
        buf.putInt(height);
        buf.putInt(stride);
        buf.putInt(format);
        buf.putLong(timestampNs);
        buf.putInt(payloadLength);
        return buf.array();
    }

    public static FrameHeader decodeFrameHeader(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.order(ByteOrder.BIG_ENDIAN);
        int magic = buf.getInt();
        int frameNumber = buf.getInt();
        int width = buf.getInt();
        int height = buf.getInt();
        int stride = buf.getInt();
        int format = buf.getInt();
        long timestampNs = buf.getLong();
        int payloadLength = buf.getInt();
        return new FrameHeader(magic, frameNumber, width, height, stride, format, timestampNs, payloadLength);
    }
}
