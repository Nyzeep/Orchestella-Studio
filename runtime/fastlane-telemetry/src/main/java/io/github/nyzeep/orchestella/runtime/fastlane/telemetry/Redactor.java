package io.github.nyzeep.orchestella.runtime.fastlane.telemetry;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Privacy boundary for Fast Lane telemetry.
 *
 * <p>The event schema deliberately has no free-text field: callers can only
 * supply enums, numbers and opaque identifiers. {@link #opaqueId(String)} is the
 * only string entry point, and {@link #redact(String)} is defence in depth for
 * diagnostic strings that a future caller might add. It removes credentials,
 * URL user-info, e-mail addresses, absolute paths, IP addresses and long
 * hex/base64 blobs, and replaces multi-line or oversized text (source code,
 * keystrokes) wholesale.
 *
 * <p>These rules are tested; review is not the guarantee.
 */
public final class Redactor {
    private static final Pattern OPAQUE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern DOTTED_QUAD =
            Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");
    private static final Pattern TOKEN_SHAPED_ID = Pattern.compile(
            "(?i)(?:AKIA|ASIA)[A-Z0-9]{12,}|gh[pousr]_.*|github_pat_.*|xox[baprs]-.*|sk-.*|eyJ.*");
    private static final Pattern TOKEN_PREFIX = Pattern.compile(
            "(?i)(?:ghp_|gho_|ghu_|ghs_|ghr_|github_pat_|xox[baprs]-|sk-)[A-Za-z0-9_-]{8,}");
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
            "([a-zA-Z][a-zA-Z0-9+.-]*://)[^@\\s]+@");
    private static final Pattern USERINFO_WITHOUT_SCHEME = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z0-9._-]+):([^@\\s/]+)@");
    private static final Pattern BASIC_AUTH = Pattern.compile(
            "(?i)\\bauthorization\\s*:\\s*basic\\s+[A-Za-z0-9+/=]+");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bbearer\\s+\"?[A-Za-z0-9._~+/-]+=?\"?");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])([A-Za-z0-9_-]*(?:token|password|passwd|secret|key))"
                    + "\\s*(?::=|[:=])\\s*([^\\s,;]+)");
    private static final Pattern CLI_SECRET = Pattern.compile(
            "(?i)(--(?:password|passwd|token|secret|api-key))\\s+([^\\s,;]+)");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(^|[\\s(\"'=,;])(?:/[^\"'()\\n\\r,;]+|~[/\\\\][^\"'()\\n\\r,;]+"
                    + "|[A-Za-z]:\\\\[^\"'()\\n\\r,;]+)");
    private static final Pattern IPV4 =
            Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b");
    private static final Pattern LONG_HEX = Pattern.compile("\\b[A-Fa-f0-9]{32,}\\b");
    private static final Pattern LONG_BASE64 = Pattern.compile(
            "(?<![A-Za-z0-9+/_-])[A-Za-z0-9+/_-]{40,}={0,2}(?![A-Za-z0-9+/=_-])");

    /** Ceiling for diagnostic text; anything longer is treated as content. */
    public static final int MAX_TEXT_LENGTH = 120;

    private Redactor() {
    }

    /**
     * Validates an opaque identifier. Paths, URLs, e-mail addresses, whitespace
     * and separators are rejected rather than escaped, so a caller cannot turn an
     * instance name into a leaked location.
     */
    public static String opaqueId(String value) {
        Objects.requireNonNull(value, "opaque id");
        if (!OPAQUE_ID.matcher(value).matches()
                || DOTTED_QUAD.matcher(value).matches()
                || TOKEN_SHAPED_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("opaque id must not look like a path, token or address");
        }
        return value;
    }

    /** Validates an upper-case reason code such as {@code PAYLOAD_VERIFY_FAILED}. */
    public static String reasonCode(String value) {
        Objects.requireNonNull(value, "reason code");
        if (!REASON_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("reason code must match [A-Z][A-Z0-9_]{0,63}");
        }
        return value;
    }

    /**
     * Removes user-visible content and secrets from a diagnostic string. The
     * result is safe to log; it is never used as an identifier or a metric.
     */
    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() > MAX_TEXT_LENGTH || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "[redacted-content]";
        }
        if (value.indexOf('{') >= 0 || value.indexOf('}') >= 0
                || value.contains("#include") || value.contains("#define")
                || value.startsWith("#!")) {
            return "[redacted-content]";
        }
        String result = TOKEN_PREFIX.matcher(value).replaceAll("[redacted-token]");
        result = JWT.matcher(result).replaceAll("[redacted-token]");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1[redacted]@");
        result = USERINFO_WITHOUT_SCHEME.matcher(result).replaceAll("$1:[redacted]@");
        result = BASIC_AUTH.matcher(result).replaceAll("Authorization: Basic [redacted]");
        result = BEARER.matcher(result).replaceAll("Bearer [redacted]");
        result = SECRET_ASSIGNMENT.matcher(result).replaceAll("$1=[redacted]");
        result = CLI_SECRET.matcher(result).replaceAll("$1 [redacted]");
        result = EMAIL.matcher(result).replaceAll("[redacted-email]");
        result = ABSOLUTE_PATH.matcher(result).replaceAll("$1[redacted-path]");
        result = IPV4.matcher(result).replaceAll("[redacted-ip]");
        result = LONG_HEX.matcher(result).replaceAll("[redacted-blob]");
        result = LONG_BASE64.matcher(result).replaceAll("[redacted-blob]");
        return result;
    }

    /** JSON string escaping for the few validated strings in the schema. */
    public static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }
}
