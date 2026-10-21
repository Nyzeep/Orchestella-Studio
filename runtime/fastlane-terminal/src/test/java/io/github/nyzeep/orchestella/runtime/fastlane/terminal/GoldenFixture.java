package io.github.nyzeep.orchestella.runtime.fastlane.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the single canonical {@code protocol/fln1-golden.json} used by both the
 * Java and the C++ tests. There is deliberately no copy under test resources.
 */
final class GoldenFixture {
    record Frame(
            String name,
            String direction,
            int type,
            int sessionId,
            byte[] payload,
            byte[] frame) {}

    private GoldenFixture() {}

    static Path path() {
        String configured = System.getProperty("fln1.golden");
        if (configured != null && !configured.isEmpty()) {
            Path path = Paths.get(configured);
            if (Files.isRegularFile(path)) {
                return path;
            }
            throw new IllegalStateException("fln1.golden does not exist: " + path);
        }
        Path current = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null; depth++) {
            Path candidate = current.resolve("protocol/fln1-golden.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "protocol/fln1-golden.json not found; set -Dfln1.golden=<path>");
    }

    static List<Frame> load() {
        try {
            return parse(Files.readString(path(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read the FLN1 golden fixture", failure);
        }
    }

    private static List<Frame> parse(String json) {
        JsonReader reader = new JsonReader(json);
        reader.expect('{');
        List<Frame> frames = new ArrayList<>();
        while (true) {
            String key = reader.string();
            reader.expect(':');
            if ("frames".equals(key)) {
                reader.expect('[');
                while (true) {
                    frames.add(parseFrame(reader));
                    char next = reader.peek();
                    if (next == ',') {
                        reader.expect(',');
                        continue;
                    }
                    reader.expect(']');
                    break;
                }
            } else {
                reader.skipValue();
            }
            char next = reader.peek();
            if (next == ',') {
                reader.expect(',');
                continue;
            }
            reader.expect('}');
            break;
        }
        if (frames.isEmpty()) {
            throw new IllegalStateException("fixture has no frames");
        }
        return frames;
    }

    private static Frame parseFrame(JsonReader reader) {
        String name = null;
        String direction = null;
        int type = -1;
        int session = -1;
        byte[] payload = null;
        byte[] frame = null;
        reader.expect('{');
        while (true) {
            String key = reader.string();
            reader.expect(':');
            switch (key) {
                case "name" -> name = reader.string();
                case "direction" -> direction = reader.string();
                case "type" -> type = (int) reader.number();
                case "session_id" -> session = (int) reader.number();
                case "payload_hex" -> payload = hex(reader.string());
                case "frame_hex" -> frame = hex(reader.string());
                default -> reader.skipValue();
            }
            char next = reader.peek();
            if (next == ',') {
                reader.expect(',');
                continue;
            }
            reader.expect('}');
            break;
        }
        if (name == null || type < 0 || session < 0 || payload == null || frame == null) {
            throw new IllegalStateException("incomplete fixture frame");
        }
        return new Frame(name, direction, type, session, payload, frame);
    }

    static byte[] hex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] bytes = new byte[value.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("invalid hex");
            }
            bytes[index] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    /** Minimal JSON reader for the flat fixture schema; rejects anything else. */
    private static final class JsonReader {
        private final String text;
        private int offset;

        private JsonReader(String text) {
            this.text = text;
        }

        private char peek() {
            skipWhitespace();
            if (offset >= text.length()) {
                throw new IllegalStateException("unexpected end of JSON");
            }
            return text.charAt(offset);
        }

        private void expect(char expected) {
            if (peek() != expected) {
                throw new IllegalStateException(
                        "expected '" + expected + "' at offset " + offset);
            }
            offset++;
        }

        private String string() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (true) {
                if (offset >= text.length()) {
                    throw new IllegalStateException("unterminated string");
                }
                char current = text.charAt(offset++);
                if (current == '"') {
                    return value.toString();
                }
                if (current == '\\') {
                    char escaped = text.charAt(offset++);
                    value.append(switch (escaped) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        default -> escaped;
                    });
                } else {
                    value.append(current);
                }
            }
        }

        private long number() {
            skipWhitespace();
            int start = offset;
            while (offset < text.length()
                    && (Character.isDigit(text.charAt(offset)) || text.charAt(offset) == '-')) {
                offset++;
            }
            if (start == offset) {
                throw new IllegalStateException("expected a number at offset " + start);
            }
            return Long.parseLong(text.substring(start, offset));
        }

        private void skipValue() {
            char next = peek();
            if (next == '"') {
                string();
            } else if (next == '{' || next == '[') {
                char close = next == '{' ? '}' : ']';
                offset++;
                while (peek() != close) {
                    if (peek() == '"') {
                        string();
                    } else if (peek() == '{' || peek() == '[') {
                        skipValue();
                    } else {
                        offset++;
                    }
                }
                expect(close);
            } else {
                number();
            }
        }

        private void skipWhitespace() {
            while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) {
                offset++;
            }
        }
    }
}
