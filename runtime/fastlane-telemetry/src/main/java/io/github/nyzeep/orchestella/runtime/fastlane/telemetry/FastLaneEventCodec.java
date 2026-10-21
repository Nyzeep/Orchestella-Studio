package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal tolerant decoder for the Fast Lane JSONL schema.
 *
 * <p>Only known fields are read; unknown top-level (and nested) fields are
 * skipped, which is the compatibility rule for an additive schema. Arrays are
 * parsed so a future field can carry one without breaking older readers. This is
 * intentionally not a general-purpose JSON binding library.
 */
public final class FastLaneEventCodec {
    private FastLaneEventCodec() {
    }

    public static FastLaneEvent decode(String jsonLine) {
        if (jsonLine == null || jsonLine.isEmpty()) {
            throw new IllegalArgumentException("empty event line");
        }
        Map<String, Object> root = new Parser(jsonLine).parseRoot();
        long schema = requireLong(root, "schema");
        if (schema != FastLaneEvent.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schema: " + schema);
        }
        FastLaneEventType type = enumValue(FastLaneEventType.class, root, "event");
        String instanceId = Redactor.opaqueId(requireString(root, "instanceId"));
        long elapsedMs = requireLong(root, "elapsedMs");
        FastLaneEventStatus status = enumValue(FastLaneEventStatus.class, root, "status");
        FastLaneDebugLevel debugLevel = enumValue(FastLaneDebugLevel.class, root, "debugLevel");

        FastLaneMetrics metrics = null;
        Map<?, ?> metricsObject = asObject(root.get("metrics"));
        if (metricsObject != null) {
            metrics = readMetrics(metricsObject);
        }
        FastLaneStopDiagnostics stop = null;
        Map<?, ?> stopObject = asObject(root.get("stop"));
        if (stopObject != null) {
            stop = readStop(stopObject);
        }
        return new FastLaneEvent(type, instanceId, elapsedMs, status, debugLevel, metrics, stop);
    }

    private static FastLaneMetrics readMetrics(Map<?, ?> source) {
        FastLaneMetrics metrics = new FastLaneMetrics();
        metrics.setStartupMs(optionalLong(source, "startupMs", -1L));
        metrics.setPayloadReadyMs(optionalLong(source, "payloadReadyMs", -1L));
        metrics.addTerminalBytes(optionalLong(source, "terminalBytes", 0L));
        metrics.addProxyBytes(optionalLong(source, "proxyBytes", 0L));
        long jobs = optionalLong(source, "distccJobs", 0L);
        long failures = optionalLong(source, "distccFailures", 0L);
        long totalMs = optionalLong(source, "distccTotalMs", 0L);
        metrics.restoreDistcc(jobs, failures, totalMs);
        long cpuPercent = optionalLong(source, "cpuPercent", -1L);
        if (cpuPercent < -1L || cpuPercent > 100L) {
            throw new IllegalArgumentException("cpuPercent must be -1 or 0..100");
        }
        long rssBytes = optionalLong(source, "rssBytes", -1L);
        metrics.sampleResources((int) cpuPercent, rssBytes);
        return metrics;
    }

    private static FastLaneStopDiagnostics readStop(Map<?, ?> source) {
        FastLaneStopStage stage = enumValue(FastLaneStopStage.class, source, "stage");
        long pid = requireLong(source, "pid");
        long graceMs = requireLong(source, "graceMs");
        Integer exitCode = optionalInteger(source, "exitCode");
        return new FastLaneStopDiagnostics(stage, pid, graceMs, exitCode);
    }

    private static Map<?, ?> asObject(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static String requireString(Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("missing string field: " + key);
        }
        return text;
    }

    private static long requireLong(Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("missing number field: " + key);
        }
        long result = number.longValue();
        if (number instanceof Double floating
                && (floating != Math.rint(floating) || floating < Long.MIN_VALUE || floating > Long.MAX_VALUE)) {
            throw new IllegalArgumentException("non-integral number field: " + key);
        }
        return result;
    }

    private static long optionalLong(Map<?, ?> source, String key, long fallback) {
        Object value = source.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("non-number field: " + key);
        }
        if (number instanceof Double floating
                && (floating != Math.rint(floating) || floating < Long.MIN_VALUE || floating > Long.MAX_VALUE)) {
            throw new IllegalArgumentException("non-integral number field: " + key);
        }
        return number.longValue();
    }

    private static Integer optionalInteger(Map<?, ?> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("non-number field: " + key);
        }
        if (number instanceof Double floating && floating != Math.rint(floating)) {
            throw new IllegalArgumentException("non-integral integer field: " + key);
        }
        long result = number.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("out-of-range integer field: " + key);
        }
        return (int) result;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Map<?, ?> source, String key) {
        String value = requireString(source, key);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("unknown " + key + ": " + value, failure);
        }
    }

    /** Recursive-descent parser for the subset of JSON the schema uses. */
    private static final class Parser {
        private static final int MAX_DEPTH = 16;
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        Map<String, Object> parseRoot() {
            skipWhitespace();
            Map<String, Object> root = parseObject(0);
            skipWhitespace();
            if (index != text.length()) {
                throw error("trailing data");
            }
            return root;
        }

        private Map<String, Object> parseObject(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                result.put(key, parseValue(depth + 1));
                skipWhitespace();
                char delimiter = next();
                if (delimiter == '}') {
                    return result;
                }
                if (delimiter != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(parseValue(depth + 1));
                skipWhitespace();
                char delimiter = next();
                if (delimiter == ']') {
                    return result;
                }
                if (delimiter != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private Object parseValue(int depth) {
            skipWhitespace();
            char character = peek();
            switch (character) {
                case '{': return parseObject(depth);
                case '[': return parseArray(depth);
                case '"': return parseString();
                case 't': literal("true"); return Boolean.TRUE;
                case 'f': literal("false"); return Boolean.FALSE;
                case 'n': literal("null"); return null;
                default:
                    if (character == '-' || (character >= '0' && character <= '9')) {
                        return parseNumber();
                    }
                    throw error("unexpected value");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char character = text.charAt(index++);
                if (character == '"') {
                    return result.toString();
                }
                if (character != '\\') {
                    result.append(character);
                    continue;
                }
                if (index >= text.length()) {
                    throw error("unterminated escape");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    case '/': result.append('/'); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        if (index + 4 > text.length()) {
                            throw error("truncated unicode escape");
                        }
                        result.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        throw error("invalid escape");
                }
            }
            throw error("unterminated string");
        }

        private Object parseNumber() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            boolean fractional = false;
            if (index < text.length() && text.charAt(index) == '.') {
                fractional = true;
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                fractional = true;
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String token = text.substring(start, index);
            try {
                if (fractional) {
                    double value = Double.parseDouble(token);
                    if (!Double.isFinite(value)) {
                        throw error("non-finite number");
                    }
                    return value;
                }
                return Long.parseLong(token);
            } catch (NumberFormatException failure) {
                throw error("invalid number");
            }
        }

        private void literal(String expected) {
            if (!text.startsWith(expected, index)) {
                throw error("expected " + expected);
            }
            index += expected.length();
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char character = text.charAt(index);
                if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private char peek() {
            if (index >= text.length()) {
                throw error("unexpected end");
            }
            return text.charAt(index);
        }

        private char next() {
            char character = peek();
            index++;
            return character;
        }

        private void expect(char expected) {
            if (next() != expected) {
                throw error("expected '" + expected + "'");
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + index);
        }
    }
}
