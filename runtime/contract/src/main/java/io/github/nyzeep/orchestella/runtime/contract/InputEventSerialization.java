package io.github.nyzeep.orchestella.runtime.contract;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Compact line serializer and deserializer for {@link InputEvent} hierarchy.
 *
 * <p>Strictly enforces maximum single-line payload boundaries ({@value InputEvent#MAX_WIRE_LENGTH} characters)
 * to ensure deterministic memory and transport safety across bridge channels.
 */
public final class InputEventSerialization {
    private static final String PREFIX_POINTER = "INPUT/POINTER";
    private static final String PREFIX_TOUCH = "INPUT/TOUCH";
    private static final String PREFIX_KEY = "INPUT/KEY";
    private static final String PREFIX_TEXT = "INPUT/TEXT";
    private static final String PREFIX_GESTURE = "INPUT/GESTURE";

    private InputEventSerialization() {}

    /**
     * Serializes an {@link InputEvent} into a single wire line.
     *
     * @param event input event to serialize
     * @return wire line string terminating with {@code \n}
     * @throws IllegalArgumentException if the event is null or the serialized line exceeds {@value InputEvent#MAX_WIRE_LENGTH} characters
     */
    public static String serialize(InputEvent event) {
        Objects.requireNonNull(event, "event");
        StringBuilder sb = new StringBuilder();

        if (event instanceof PointerEvent) {
            PointerEvent p = (PointerEvent) event;
            sb.append(PREFIX_POINTER).append(' ')
                    .append(p.action().name()).append(' ')
                    .append(p.x()).append(' ')
                    .append(p.y()).append(' ')
                    .append(p.deltaX()).append(' ')
                    .append(p.deltaY()).append(' ')
                    .append(p.button()).append(' ')
                    .append(p.timestampNs()).append('\n');
        } else if (event instanceof TouchEvent) {
            TouchEvent t = (TouchEvent) event;
            sb.append(PREFIX_TOUCH).append(' ')
                    .append(t.action().name()).append(' ')
                    .append(t.pointerId()).append(' ')
                    .append(t.x()).append(' ')
                    .append(t.y()).append(' ')
                    .append(t.timestampNs()).append('\n');
        } else if (event instanceof KeyEvent) {
            KeyEvent k = (KeyEvent) event;
            sb.append(PREFIX_KEY).append(' ')
                    .append(k.action().name()).append(' ')
                    .append(k.keyCode()).append(' ')
                    .append(k.modifiers()).append(' ')
                    .append(k.timestampNs()).append('\n');
        } else if (event instanceof TextCommitEvent) {
            TextCommitEvent tc = (TextCommitEvent) event;
            String base64 = Base64.getEncoder().encodeToString(tc.text().getBytes(StandardCharsets.UTF_8));
            sb.append(PREFIX_TEXT).append(" base64:")
                    .append(base64).append(' ')
                    .append(tc.timestampNs()).append('\n');
        } else if (event instanceof GestureEvent) {
            GestureEvent g = (GestureEvent) event;
            sb.append(PREFIX_GESTURE).append(' ')
                    .append(g.gestureType().name()).append(' ')
                    .append(g.scaleFactor()).append(' ')
                    .append(g.focusX()).append(' ')
                    .append(g.focusY()).append(' ')
                    .append(g.timestampNs()).append('\n');
        } else {
            throw new IllegalArgumentException("Unsupported event type: " + event.getClass().getName());
        }

        String wire = sb.toString();
        if (wire.length() > InputEvent.MAX_WIRE_LENGTH) {
            throw new IllegalArgumentException(
                    "Serialized input event exceeds maximum allowed limit of "
                            + InputEvent.MAX_WIRE_LENGTH + " characters: length=" + wire.length());
        }
        return wire;
    }

    /**
     * Deserializes a wire line into an {@link InputEvent}.
     *
     * @param wire single-line wire representation
     * @return deserialized input event
     * @throws IllegalArgumentException if the wire is null, exceeds max length, or has malformed syntax
     */
    public static InputEvent deserialize(String wire) {
        if (wire == null) {
            throw new IllegalArgumentException("wire must not be null");
        }
        if (wire.length() > InputEvent.MAX_WIRE_LENGTH) {
            throw new IllegalArgumentException(
                    "Wire string exceeds maximum allowed limit of "
                            + InputEvent.MAX_WIRE_LENGTH + " characters: length=" + wire.length());
        }

        String trimmed = wire.endsWith("\n") ? wire.substring(0, wire.length() - 1) : wire;
        if (trimmed.endsWith("\r")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        trimmed = trimmed.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Wire string is empty");
        }

        String[] tokens = trimmed.split(" ", -1);
        try {
            switch (tokens[0]) {
                case PREFIX_POINTER:
                    if (tokens.length != 8) {
                        throw new IllegalArgumentException("Expected 8 tokens for pointer event, found: " + tokens.length);
                    }
                    PointerEvent.Action pAction = PointerEvent.Action.valueOf(tokens[1]);
                    double px = Double.parseDouble(tokens[2]);
                    double py = Double.parseDouble(tokens[3]);
                    double deltaX = Double.parseDouble(tokens[4]);
                    double deltaY = Double.parseDouble(tokens[5]);
                    int button = Integer.parseInt(tokens[6]);
                    long pTs = Long.parseLong(tokens[7]);
                    return new PointerEvent(pAction, px, py, deltaX, deltaY, button, pTs);

                case PREFIX_TOUCH:
                    if (tokens.length != 6) {
                        throw new IllegalArgumentException("Expected 6 tokens for touch event, found: " + tokens.length);
                    }
                    TouchEvent.Action tAction = TouchEvent.Action.valueOf(tokens[1]);
                    int pointerId = Integer.parseInt(tokens[2]);
                    double tx = Double.parseDouble(tokens[3]);
                    double ty = Double.parseDouble(tokens[4]);
                    long tTs = Long.parseLong(tokens[5]);
                    return new TouchEvent(tAction, pointerId, tx, ty, tTs);

                case PREFIX_KEY:
                    if (tokens.length != 5) {
                        throw new IllegalArgumentException("Expected 5 tokens for key event, found: " + tokens.length);
                    }
                    KeyEvent.Action kAction = KeyEvent.Action.valueOf(tokens[1]);
                    int keyCode = Integer.parseInt(tokens[2]);
                    int modifiers = Integer.parseInt(tokens[3]);
                    long kTs = Long.parseLong(tokens[4]);
                    return new KeyEvent(kAction, keyCode, modifiers, kTs);

                case PREFIX_TEXT:
                    if (tokens.length != 3) {
                        throw new IllegalArgumentException("Expected 3 tokens for text commit event, found: " + tokens.length);
                    }
                    if (!tokens[1].startsWith("base64:")) {
                        throw new IllegalArgumentException("Text payload must start with 'base64:': " + tokens[1]);
                    }
                    byte[] decoded = Base64.getDecoder().decode(tokens[1].substring("base64:".length()));
                    String text = new String(decoded, StandardCharsets.UTF_8);
                    long tcTs = Long.parseLong(tokens[2]);
                    return new TextCommitEvent(text, tcTs);

                case PREFIX_GESTURE:
                    if (tokens.length != 6) {
                        throw new IllegalArgumentException("Expected 6 tokens for gesture event, found: " + tokens.length);
                    }
                    GestureEvent.GestureType gType = GestureEvent.GestureType.valueOf(tokens[1]);
                    double scaleFactor = Double.parseDouble(tokens[2]);
                    double focusX = Double.parseDouble(tokens[3]);
                    double focusY = Double.parseDouble(tokens[4]);
                    long gTs = Long.parseLong(tokens[5]);
                    return new GestureEvent(gType, scaleFactor, focusX, focusY, gTs);

                default:
                    throw new IllegalArgumentException("Unknown event prefix: " + tokens[0]);
            }
        } catch (Exception ex) {
            if (ex instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) ex;
            }
            throw new IllegalArgumentException("Malformed wire format: " + trimmed, ex);
        }
    }
}
