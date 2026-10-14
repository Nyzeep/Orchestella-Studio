package io.github.nyzeep.orchestella.runtime.instance.features;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Bounded guest evidence. Installation, capture, authentication and run stay separate. */
public record GuestFeatureStatus(String profile, String state, boolean installed, String reason,
                                 String generation, boolean captureVerified, Input input,
                                 boolean authenticationConfigured, boolean exposed,
                                 boolean containerInfoVerified, boolean containerRunVerified) {
    public record Input(boolean keyboard, boolean unicodeText, boolean pointer) { }
    private static final Set<String> PROFILES = Set.of("desktop-labwc", "podman", "code-server", "docker-cli");
    private static final Set<String> STATES = Set.of("planned", "installing", "installed", "starting", "awaiting-frame", "ready", "failed", "cancelled");

    public GuestFeatureStatus {
        if (!PROFILES.contains(profile) || !STATES.contains(state) || reason == null || !reason.matches("[a-z0-9-]{1,96}") || input == null
                || generation == null || !(generation.equals("none") || generation.matches("[0-9a-f]{32}"))) {
            throw new IllegalArgumentException("malformed-feature-status");
        }
        if ((input.keyboard() || input.pointer() || input.unicodeText()) && (!profile.equals("desktop-labwc") || !captureVerified)) {
            throw new IllegalArgumentException("input-unverified");
        }
        if (exposed && (!profile.equals("code-server") || state.equals("ready") && !authenticationConfigured)) {
            throw new IllegalArgumentException("auth-unverified");
        }
        if (state.equals("ready") && (!installed || generation.equals("none")
                || profile.equals("desktop-labwc") && !captureVerified
                || profile.equals("code-server") && !authenticationConfigured
                || profile.equals("podman") && !(containerInfoVerified && containerRunVerified)
                || profile.equals("docker-cli"))) {
            throw new IllegalArgumentException("ready-unverified");
        }
    }

    public boolean ready() {
        return state.equals("ready");
    }

    public static GuestFeatureStatus parse(String line) {
        if (line == null || line.getBytes(StandardCharsets.UTF_8).length > 4096 || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("oversized-feature-status");
        }
        Map<String, Object> fields = new Reader(line).document();
        if (!Long.valueOf(1).equals(fields.get("version"))) {
            throw new IllegalArgumentException("unsupported-feature-version");
        }
        String profile = text(fields, "profile", "");
        String state = text(fields, "state", "");
        String generation = text(fields, "generation", "none");
        boolean captured = text(fields, "capture", "unverified").equals("verified")
                && text(fields, "protocols", "unverified").equals("verified")
                && !generation.equals("none") && generation.equals(text(fields, "capture_generation", "none"))
                && (state.equals("awaiting-frame") || state.equals("ready"));
        Object inputFields = fields.get("input");
        if (inputFields != null && !(inputFields instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("malformed-feature-status");
        }
        Map<?, ?> inputs = inputFields == null ? Map.of() : (Map<?, ?>) inputFields;
        boolean auth = bool(fields, "authenticationConfigured") && text(fields, "health", "unverified").equals("authenticated-http") && (state.equals("ready") || state.equals("starting"));
        return new GuestFeatureStatus(profile, state, bool(fields, "installed"), text(fields, "reason", "none"), generation, captured,
                new Input(bool(inputs, "keyboard"), bool(inputs, "unicodeText"), bool(inputs, "pointer")), auth, bool(fields, "exposed"),
                text(fields, "container_info", "unverified").equals("verified"), text(fields, "container_run", "unverified").equals("verified"));
    }

    private static String text(Map<?, ?> fields, String name, String fallback) {
        Object value = fields.get(name);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("malformed-feature-status");
        }
        return text;
    }

    private static boolean bool(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException("malformed-feature-status");
        }
        return flag;
    }

    /** Small version-1 object grammar: no arrays, null, duplicate keys or hidden controls. */
    private static final class Reader {
        private final String text;
        private int position;
        Reader(String text) { this.text = text; }

        Map<String, Object> document() {
            Map<String, Object> value = object(0);
            whitespace();
            if (position != text.length()) { throw invalid(); }
            return value;
        }

        private Map<String, Object> object(int depth) {
            if (depth > 4) { throw invalid(); }
            whitespace();
            take('{');
            Map<String, Object> value = new HashMap<>();
            whitespace();
            if (peek('}')) { position++; return value; }
            while (true) {
                whitespace();
                String key = string();
                whitespace();
                take(':');
                whitespace();
                Object item;
                if (peek('"')) { item = string(); }
                else if (peek('{')) { item = object(depth + 1); }
                else if (text.startsWith("true", position)) { position += 4; item = Boolean.TRUE; }
                else if (text.startsWith("false", position)) { position += 5; item = Boolean.FALSE; }
                else { item = number(); }
                if (value.putIfAbsent(key, item) != null || value.size() > 100) { throw invalid(); }
                whitespace();
                if (peek('}')) { position++; return value; }
                take(',');
            }
        }

        private Long number() {
            int start = position;
            while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') { position++; }
            if (start == position || position - start > 19 || position - start > 1 && text.charAt(start) == '0') { throw invalid(); }
            try { return Long.valueOf(text.substring(start, position)); }
            catch (NumberFormatException failure) { throw invalid(); }
        }

        private String string() {
            take('"');
            StringBuilder value = new StringBuilder();
            while (position < text.length()) {
                char current = text.charAt(position++);
                if (current == '"') { return value.toString(); }
                if (current < 32 || current == 127) { throw invalid(); }
                if (current == '\\') {
                    if (position >= text.length()) { throw invalid(); }
                    char escaped = text.charAt(position++);
                    if (escaped == '"' || escaped == '\\' || escaped == '/') { current = escaped; }
                    else if (escaped == 'u' && position + 4 <= text.length()) {
                        try { current = (char) Integer.parseInt(text.substring(position, position + 4), 16); }
                        catch (NumberFormatException failure) { throw invalid(); }
                        position += 4;
                        if (current < 32 || current == 127 || Character.isSurrogate(current)) { throw invalid(); }
                    } else { throw invalid(); }
                }
                value.append(current);
            }
            throw invalid();
        }

        private boolean peek(char character) { return position < text.length() && text.charAt(position) == character; }
        private void take(char character) { if (!peek(character)) { throw invalid(); } position++; }
        private void whitespace() { while (position < text.length() && text.charAt(position) == ' ') { position++; } }
        private IllegalArgumentException invalid() { return new IllegalArgumentException("malformed-feature-status"); }
    }
}
