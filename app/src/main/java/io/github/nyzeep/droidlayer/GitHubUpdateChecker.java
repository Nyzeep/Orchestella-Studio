package io.github.nyzeep.droidlayer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Checks for new releases on GitHub Releases API (ADR-0015, US-36, AC-3).
 *
 * <p>Strict zero-telemetry discipline:
 * <ul>
 *   <li>When update checks are disabled, strictly 0 network connections are made</li>
 *   <li>No device identifiers, no metrics, no telemetry payloads sent</li>
 *   <li>Only standard GitHub API headers (User-Agent, Accept) are used</li>
 * </ul>
 */
public final class GitHubUpdateChecker {
    public static final String DEFAULT_RELEASES_API_URL =
            "https://api.github.com/repos/Nyzeep/Orchestella-Studio/releases/latest";

    public enum Status {
        DISABLED,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        ERROR
    }

    public static final class UpdateResult {
        private final Status status;
        private final String latestVersion;
        private final String releaseNotes;
        private final String releaseUrl;
        private final String errorMessage;

        private UpdateResult(
                Status status,
                String latestVersion,
                String releaseNotes,
                String releaseUrl,
                String errorMessage) {
            this.status = status;
            this.latestVersion = latestVersion;
            this.releaseNotes = releaseNotes;
            this.releaseUrl = releaseUrl;
            this.errorMessage = errorMessage;
        }

        public static UpdateResult disabled() {
            return new UpdateResult(Status.DISABLED, null, null, null, "更新检查已在设置中关闭（零遥测）");
        }

        public static UpdateResult upToDate(String version) {
            return new UpdateResult(Status.UP_TO_DATE, version, null, null, null);
        }

        public static UpdateResult updateAvailable(String version, String notes, String url) {
            return new UpdateResult(Status.UPDATE_AVAILABLE, version, notes, url, null);
        }

        public static UpdateResult error(String message) {
            return new UpdateResult(Status.ERROR, null, null, null, message);
        }

        public Status status() {
            return status;
        }

        public String latestVersion() {
            return latestVersion;
        }

        public String releaseNotes() {
            return releaseNotes;
        }

        public String releaseUrl() {
            return releaseUrl;
        }

        public String errorMessage() {
            return errorMessage;
        }
    }

    private final String apiUrl;
    private final SettingsManager settings;

    public GitHubUpdateChecker(SettingsManager settings) {
        this(DEFAULT_RELEASES_API_URL, settings);
    }

    public GitHubUpdateChecker(String apiUrl, SettingsManager settings) {
        this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Checks for updates against GitHub Releases.
     *
     * @param currentVersion current application version name (e.g. "0.1.0-dev")
     * @return UpdateResult containing version info or error
     */
    public UpdateResult checkForUpdate(String currentVersion) {
        if (!settings.isUpdateCheckEnabled()) {
            return UpdateResult.disabled();
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(apiUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Orchestella-Studio-Android/" + (currentVersion != null ? currentVersion : "1.0"));
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return UpdateResult.error("GitHub API 返回状态码 " + responseCode);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            String responseBody = sb.toString();
            String tagName = extractStringField(responseBody, "tag_name");
            String releaseNotes = extractStringField(responseBody, "body");
            String releaseUrl = extractStringField(responseBody, "html_url");

            String cleanRemote = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            String cleanCurrent = currentVersion != null && currentVersion.startsWith("v")
                    ? currentVersion.substring(1)
                    : (currentVersion != null ? currentVersion : "");

            if (!cleanRemote.isBlank() && isNewerVersion(cleanRemote, cleanCurrent)) {
                return UpdateResult.updateAvailable(tagName, releaseNotes, releaseUrl);
            } else {
                return UpdateResult.upToDate(tagName.isBlank() ? currentVersion : tagName);
            }
        } catch (Exception ex) {
            return UpdateResult.error("检查更新失败: " + ex.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static boolean isNewerVersion(String remote, String current) {
        if (remote.isBlank()) {
            return false;
        }
        if (current.isBlank()) {
            return true;
        }
        // Dev versions always show update when a formal release tag exists
        if (current.contains("-dev")) {
            return true;
        }
        return !remote.equalsIgnoreCase(current);
    }

    static String extractStringField(String json, String field) {
        if (json == null || field == null) {
            return "";
        }
        String pattern = "\"" + field + "\"\\s*:\\s*\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (!m.find()) {
            return "";
        }
        int start = m.end();
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                if (c == 'n') sb.append('\n');
                else if (c == 'r') sb.append('\r');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
