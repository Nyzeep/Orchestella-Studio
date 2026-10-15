package io.github.nyzeep.orchestella;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure JVM release selection. No I/O, Android dependencies or server-directed downloads. */
public final class ReleaseSelector {
    public static final String RELEASES_API = "https://api.github.com/repos/Nyzeep/Orchestella-Studio/releases";
    public static final String RELEASES_PAGE = "https://github.com/Nyzeep/Orchestella-Studio/releases";
    public static final int MAX_RESPONSE_BYTES = 1_048_576;
    public static final int MAX_RELEASES = 100;

    public enum Channel {
        STABLE("稳定通道"),
        PREVIEW("开发预览通道");

        private final String label;

        Channel(String label) { this.label = label; }
        public String label() { return label; }
    }

    private ReleaseSelector() {}

    /** A stable build never opts into previews merely because its major version is zero. */
    public static Channel channelForBuild(String currentVersion, boolean developmentPreview) {
        SemVer current = SemVer.parseTag(currentVersion);
        return developmentPreview || (current != null && current.isPrerelease()) ? Channel.PREVIEW : Channel.STABLE;
    }

    public static final class Release {
        private final String tag;
        private final String notes;
        private final SemVer version;

        private Release(String tag, String notes, SemVer version) {
            this.tag = tag;
            this.notes = notes;
            this.version = version;
        }

        public String tag() { return tag; }
        public String notes() { return notes; }
        // Never expose html_url, browser_download_url or any other server-provided destination.
        public String pageUrl() { return RELEASES_PAGE + "/tag/" + tag; }
    }

    /**
     * Selects the greatest eligible version strictly newer than installed, or null.
     * Invalid tags/entries are ignored; invalid JSON/envelopes fail closed.
     * The caller must reject pagination rather than claim a partial list is complete.
     */
    public static Release select(String response, String currentVersion, Channel channel) {
        SemVer installed = SemVer.parseTag(currentVersion);
        if (installed == null) throw new IllegalArgumentException("当前版本号无效，无法安全比较更新");
        Objects.requireNonNull(channel, "channel");
        if (response == null || response.length() > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("发布列表为空或超出检查范围");
        }
        Object parsed = new Json(response).read();
        if (!(parsed instanceof List<?>)) throw new IllegalArgumentException("GitHub 发布列表格式无效");
        List<?> releases = (List<?>) parsed;
        if (releases.size() > MAX_RELEASES) throw new IllegalArgumentException("发布列表超出检查范围");
        Release best = null;
        for (Object entry : releases) {
            if (!(entry instanceof Map<?, ?>)) continue;
            Map<?, ?> release = (Map<?, ?>) entry;
            if (!Boolean.FALSE.equals(release.get("draft")) || !(release.get("prerelease") instanceof Boolean)) continue;
            Object rawTag = release.get("tag_name");
            if (!(rawTag instanceof String)) continue;
            String tag = (String) rawTag;
            SemVer version = SemVer.parseTag(tag);
            if (version == null || version.compareTo(installed) <= 0) continue;
            if (channel == Channel.STABLE
                    && (Boolean.TRUE.equals(release.get("prerelease")) || version.isPrerelease())) continue;
            // Require the release's own API source, not a URL nested in an asset/author object.
            Object source = release.get("url");
            String prefix = RELEASES_API + "/";
            if (!(source instanceof String) || !((String) source).startsWith(prefix)
                    || !positiveId(((String) source).substring(prefix.length()))) continue;
            String page = RELEASES_PAGE + "/tag/" + tag;
            if (!page.equals(release.get("html_url"))) continue;
            String notes = release.get("body") instanceof String ? (String) release.get("body") : "";
            Release candidate = new Release(tag, notes, version);
            if (best == null || version.compareTo(best.version) > 0
                    || (version.compareTo(best.version) == 0 && tag.compareTo(best.tag) < 0)) best = candidate;
        }
        return best;
    }

    private static boolean positiveId(String id) {
        if (id.isEmpty() || id.charAt(0) == '0') return false;
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) < '0' || id.charAt(i) > '9') return false;
        }
        return true;
    }

    /** Small strict JSON reader: preserves object boundaries, rejects duplicates and bounds nesting. */
    private static final class Json {
        private static final Object NUMBER = new Object();
        private final String text;
        private int position;

        Json(String text) { this.text = text; }

        Object read() {
            Object value = value(0);
            whitespace();
            if (position != text.length()) throw invalid();
            return value;
        }

        private Object value(int depth) {
            if (depth > 32) throw invalid();
            whitespace();
            if (position == text.length()) throw invalid();
            char c = text.charAt(position);
            if (c == '"') return string();
            if (c == '{') {
                position++;
                Map<String, Object> object = new LinkedHashMap<>();
                if (take('}')) return object;
                do {
                    whitespace();
                    String key = string();
                    if (!take(':') || object.containsKey(key)) throw invalid();
                    object.put(key, value(depth + 1));
                } while (take(','));
                if (!take('}')) throw invalid();
                return object;
            }
            if (c == '[') {
                position++;
                List<Object> array = new ArrayList<>();
                if (take(']')) return array;
                do { array.add(value(depth + 1)); } while (take(','));
                if (!take(']')) throw invalid();
                return array;
            }
            if (literal("true")) return Boolean.TRUE;
            if (literal("false")) return Boolean.FALSE;
            if (literal("null")) return null;
            number();
            return NUMBER;
        }

        private String string() {
            if (position == text.length() || text.charAt(position++) != '"') throw invalid();
            StringBuilder result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw invalid();
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (position == text.length()) throw invalid();
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"': case '\\': case '/': result.append(escaped); break;
                    case 'b': result.append('\b'); break;
                    case 'f': result.append('\f'); break;
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case 'u':
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            if (position == text.length()) throw invalid();
                            char hex = text.charAt(position++);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                    : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                    : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw invalid();
                            code = code * 16 + digit;
                        }
                        result.append((char) code);
                        break;
                    default: throw invalid();
                }
            }
            throw invalid();
        }

        private void number() {
            if (position < text.length() && text.charAt(position) == '-') position++;
            if (position == text.length()) throw invalid();
            if (text.charAt(position) == '0') position++;
            else {
                if (text.charAt(position) < '1' || text.charAt(position) > '9') throw invalid();
                digits();
            }
            if (position < text.length() && text.charAt(position) == '.') {
                position++;
                digits();
            }
            if (position < text.length() && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
                position++;
                if (position < text.length() && (text.charAt(position) == '+' || text.charAt(position) == '-')) position++;
                digits();
            }
        }

        private void digits() {
            int start = position;
            while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') position++;
            if (start == position) throw invalid();
        }

        private boolean literal(String token) {
            if (!text.startsWith(token, position)) return false;
            position += token.length();
            return true;
        }

        private boolean take(char expected) {
            whitespace();
            if (position == text.length() || text.charAt(position) != expected) return false;
            position++;
            return true;
        }

        private void whitespace() {
            while (position < text.length()) {
                char c = text.charAt(position);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return;
                position++;
            }
        }

        private IllegalArgumentException invalid() {
            // Never include untrusted response text in diagnostics.
            return new IllegalArgumentException("GitHub 发布列表 JSON 无效");
        }
    }
}
