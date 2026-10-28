package io.github.nyzeep.orchestella.runtime.fastlane.capability.accelerator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PVM-09: the protected compiler payload's own manifest.
 *
 * <p>A parseable manifest is the only evidence that the payload compiler is
 * installed. A missing, unreadable, truncated or otherwise defective manifest
 * never counts as "present": the caller gets {@link Optional#empty()} and
 * {@link AcceleratorFacts#payloadCompilerPresent()} stays false, so the policy
 * refuses the {@code PVM_PAYLOAD} lane instead of guessing.
 *
 * <p>The on-disk format is the schema-1 JSON document produced by the payload
 * staging script. Unknown fields are ignored so a newer generator can add
 * fields, but a duplicate key, a wrong type, a missing required field or a
 * trailing character is a manifest failure, not a warning. The manifest
 * carries no path, token or device identifier; reading the asset stays in the
 * platform adapter and never enters these facts.
 *
 * <p>This class is dependency-free on purpose: the module is a plain
 * {@code java-library} built with {@code -Xlint:all -Werror}, so the minimal
 * JSON reader lives here as a private nested type rather than as a shared
 * utility.
 */
public final class PvmCompilerManifest {
    /** The only accepted schema tag; a string {@code "1"} is not accepted. */
    public static final int SCHEMA = 1;

    /** The only supported topology: the compiler payload is single-vCPU. */
    private static final String VCPU_TOPOLOGY_ONE_CPU = "one_cpu";

    /** Memory bounds, in MiB, that the payload lane is allowed to ask for. */
    private static final int MEMORY_MIB_MIN = 1;
    private static final int MEMORY_MIB_MAX = 16384;

    /** A bare shared-object file name: no directories, no separators. */
    private static final Pattern PAYLOAD_BINARY = Pattern.compile("[A-Za-z0-9._-]+\\.so");

    /** Lower-case hex SHA-256, exactly as the generator writes it. */
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Schema-1 documents are two levels deep; anything beyond this is noise. */
    private static final int MAX_JSON_DEPTH = 8;

    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_ENGINE = "engine";
    private static final String KEY_COMPILER_VERSION = "compiler_version";
    private static final String KEY_TARGET_TRIPLE = "target_triple";
    private static final String KEY_VCPU_TOPOLOGY = "vcpu_topology";
    private static final String KEY_MEMORY_MIB = "memory_mib";
    private static final String KEY_PAYLOAD_BINARY = "payload_binary";
    private static final String KEY_LIBS = "libs";
    private static final String KEY_NAME = "name";
    private static final String KEY_SIZE = "size";
    private static final String KEY_SHA256 = "sha256";

    private final String engine;
    private final String compilerVersion;
    private final String targetTriple;
    private final int memoryMiB;
    private final String payloadBinary;
    private final List<Lib> libs;

    private PvmCompilerManifest(
            String engine,
            String compilerVersion,
            String targetTriple,
            int memoryMiB,
            String payloadBinary,
            List<Lib> libs) {
        this.engine = engine;
        this.compilerVersion = compilerVersion;
        this.targetTriple = targetTriple;
        this.memoryMiB = memoryMiB;
        this.payloadBinary = payloadBinary;
        this.libs = libs;
    }

    /**
     * Parses a manifest body. Any defect — null, blank, malformed JSON, wrong
     * schema, missing or blank field, out-of-range memory, bad library entry,
     * duplicate key — yields {@link Optional#empty()}.
     */
    public static Optional<PvmCompilerManifest> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        Object root;
        try {
            root = new JsonReader(json).readDocument();
        } catch (RuntimeException malformedDocument) {
            // Parser detail never escapes: a malformed manifest is simply "no
            // payload compiler", not an error the facts may report.
            return Optional.empty();
        }
        if (!(root instanceof Map<?, ?> document)) {
            return Optional.empty();
        }
        Object schema = document.get(KEY_SCHEMA);
        if (!(schema instanceof Long tag) || tag != SCHEMA) {
            return Optional.empty();
        }
        // Engine is an optional schema-1 field: older manifests without it
        // stay parseable, and an absent engine is surfaced as an empty string
        // so the runtime gate can refuse it explicitly.
        String engine = trimmedText(document, KEY_ENGINE);
        if (engine == null) {
            engine = "";
        }
        String compilerVersion = trimmedText(document, KEY_COMPILER_VERSION);
        String targetTriple = trimmedText(document, KEY_TARGET_TRIPLE);
        String payloadBinary = trimmedText(document, KEY_PAYLOAD_BINARY);
        if (compilerVersion == null || targetTriple == null || payloadBinary == null) {
            return Optional.empty();
        }
        if (!PAYLOAD_BINARY.matcher(payloadBinary).matches()) {
            return Optional.empty();
        }
        if (!VCPU_TOPOLOGY_ONE_CPU.equals(document.get(KEY_VCPU_TOPOLOGY))) {
            return Optional.empty();
        }
        Object memory = document.get(KEY_MEMORY_MIB);
        if (!(memory instanceof Long memoryMiB)
                || memoryMiB < MEMORY_MIB_MIN
                || memoryMiB > MEMORY_MIB_MAX) {
            return Optional.empty();
        }
        Optional<List<Lib>> libs = readLibs(document.get(KEY_LIBS));
        if (libs.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PvmCompilerManifest(
                engine,
                compilerVersion,
                targetTriple,
                memoryMiB.intValue(),
                payloadBinary,
                libs.get()));
    }

    /**
     * The engine this payload was built with; {@code ""} when the manifest
     * predates the field. The runtime gate only accepts {@code "clang"} — a
     * {@code "fake"} engine produces fabricated objects and must never be
     * reported as an available compiler.
     */
    public String engine() {
        return engine;
    }

    /** The compiler version the payload was built from; never blank. */
    public String compilerVersion() {
        return compilerVersion;
    }

    /** The triple the payload was compiled for; never blank. */
    public String targetTriple() {
        return targetTriple;
    }

    /** Always {@code "one_cpu"}; the payload lane is single-vCPU. */
    public String vcpuTopology() {
        return VCPU_TOPOLOGY_ONE_CPU;
    }

    /** Requested payload memory in MiB, always within {@code 1..16384}. */
    public int memoryMiB() {
        return memoryMiB;
    }

    /** Payload binary name, a bare {@code .so} file name without directories. */
    public String payloadBinary() {
        return payloadBinary;
    }

    /** In-bounds libraries, in ascending name order; never empty, immutable. */
    public List<Lib> libs() {
        return libs;
    }

    /** One shared object shipped beside the payload. */
    public record Lib(String name, long size, String sha256) {
        public Lib {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(sha256, "sha256");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (size <= 0L) {
                throw new IllegalArgumentException("size must be positive");
            }
        }
    }

    /** Returns empty when the {@code libs} value is not a non-empty array. */
    private static Optional<List<Lib>> readLibs(Object rawLibs) {
        if (!(rawLibs instanceof List<?> entries) || entries.isEmpty()) {
            return Optional.empty();
        }
        List<Lib> libs = new ArrayList<>(entries.size());
        Set<String> names = new HashSet<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)) {
                return Optional.empty();
            }
            String name = trimmedText(fields, KEY_NAME);
            if (name == null || !name.endsWith(".so")) {
                return Optional.empty();
            }
            Object size = fields.get(KEY_SIZE);
            if (!(size instanceof Long bytes) || bytes <= 0L) {
                return Optional.empty();
            }
            String sha256 = text(fields, KEY_SHA256);
            if (sha256 == null || !SHA256.matcher(sha256).matches()) {
                return Optional.empty();
            }
            if (!names.add(name)) {
                return Optional.empty();
            }
            libs.add(new Lib(name, bytes, sha256));
        }
        // The generator writes libs sorted; sorting here keeps the accessor
        // contract ("ascending by name") true for hand-made manifests too.
        libs.sort(Comparator.comparing(Lib::name));
        return Optional.of(List.copyOf(libs));
    }

    /** Returns the string value, or null when absent or of another type. */
    private static String text(Map<?, ?> fields, String key) {
        return fields.get(key) instanceof String value ? value : null;
    }

    /** Returns the stripped string value, or null when absent or blank. */
    private static String trimmedText(Map<?, ?> fields, String key) {
        String value = text(fields, key);
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * Minimal strict JSON reader: objects, arrays, strings, decimal integers,
     * {@code true}, {@code false} and {@code null}.
     *
     * <p>Strictness is the point — a manifest that is not exactly valid JSON is
     * not a manifest. It refuses trailing characters, illegal or unknown
     * escapes, raw control characters in strings, leading zeros, any fraction
     * or exponent (this schema has no floating point), out-of-range integers,
     * duplicate object keys and nesting deeper than {@link #MAX_JSON_DEPTH}.
     */
    private static final class JsonReader {
        private final String text;
        private int index;

        JsonReader(String text) {
            this.text = text;
        }

        /** Reads one value and requires the whole input to be consumed. */
        Object readDocument() {
            Object value = readValue(0);
            skipWhitespace();
            if (index != text.length()) {
                throw new JsonException();
            }
            return value;
        }

        private Object readValue(int depth) {
            skipWhitespace();
            char next = peek();
            if (next == '{') {
                return readObject(depth);
            }
            if (next == '[') {
                return readArray(depth);
            }
            if (next == '"') {
                return readString();
            }
            if (next == 't') {
                return readLiteral("true", Boolean.TRUE);
            }
            if (next == 'f') {
                return readLiteral("false", Boolean.FALSE);
            }
            if (next == 'n') {
                return readLiteral("null", null);
            }
            return readNumber();
        }

        private Map<String, Object> readObject(int depth) {
            if (depth >= MAX_JSON_DEPTH) {
                throw new JsonException();
            }
            index++;
            Map<String, Object> fields = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return fields;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new JsonException();
                }
                String key = readString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new JsonException();
                }
                index++;
                if (fields.containsKey(key)) {
                    // A repeated key has no single meaning; refuse the document.
                    throw new JsonException();
                }
                fields.put(key, readValue(depth + 1));
                skipWhitespace();
                char separator = peek();
                index++;
                if (separator == '}') {
                    return fields;
                }
                if (separator != ',') {
                    throw new JsonException();
                }
            }
        }

        private List<Object> readArray(int depth) {
            if (depth >= MAX_JSON_DEPTH) {
                throw new JsonException();
            }
            index++;
            List<Object> values = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return values;
            }
            while (true) {
                values.add(readValue(depth + 1));
                skipWhitespace();
                char separator = peek();
                index++;
                if (separator == ']') {
                    return values;
                }
                if (separator != ',') {
                    throw new JsonException();
                }
            }
        }

        private String readString() {
            index++;
            StringBuilder out = new StringBuilder();
            while (true) {
                char next = peek();
                index++;
                if (next == '"') {
                    return out.toString();
                }
                if (next == '\\') {
                    out.append(readEscape());
                } else if (next < 0x20) {
                    // Raw control characters are not allowed inside JSON strings.
                    throw new JsonException();
                } else {
                    out.append(next);
                }
            }
        }

        private char readEscape() {
            char escape = peek();
            index++;
            return switch (escape) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> readUnicodeEscape();
                default -> throw new JsonException();
            };
        }

        private char readUnicodeEscape() {
            if (index + 4 > text.length()) {
                throw new JsonException();
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = hexDigit(text.charAt(index + offset));
                if (digit < 0) {
                    throw new JsonException();
                }
                value = (value << 4) | digit;
            }
            index += 4;
            return (char) value;
        }

        private Object readLiteral(String literal, Object value) {
            if (!text.startsWith(literal, index)) {
                throw new JsonException();
            }
            index += literal.length();
            return value;
        }

        /** Decimal integers only: no sign other than {@code -}, no fraction. */
        private Long readNumber() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            int digitsStart = index;
            while (index < text.length() && isDigit(text.charAt(index))) {
                index++;
            }
            if (index == digitsStart) {
                throw new JsonException();
            }
            if (text.charAt(digitsStart) == '0' && index - digitsStart > 1) {
                throw new JsonException();
            }
            if (index < text.length()) {
                char next = text.charAt(index);
                if (next == '.' || next == 'e' || next == 'E') {
                    throw new JsonException();
                }
            }
            try {
                return Long.valueOf(text.substring(start, index));
            } catch (NumberFormatException overflow) {
                throw new JsonException();
            }
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char next = text.charAt(index);
                if (next != ' ' && next != '\t' && next != '\n' && next != '\r') {
                    return;
                }
                index++;
            }
        }

        private char peek() {
            if (index >= text.length()) {
                throw new JsonException();
            }
            return text.charAt(index);
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private static int hexDigit(char value) {
            if (value >= '0' && value <= '9') {
                return value - '0';
            }
            if (value >= 'a' && value <= 'f') {
                return value - 'a' + 10;
            }
            if (value >= 'A' && value <= 'F') {
                return value - 'A' + 10;
            }
            return -1;
        }
    }

    /**
     * Internal refusal signal. Malformed input is ordinary here, so the stack
     * trace is disabled: the exception never leaves {@link #parse(String)}.
     */
    private static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonException() {
            super(null, null, false, false);
        }
    }
}
