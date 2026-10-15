package io.github.nyzeep.orchestella;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
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
            ReleaseSelector.RELEASES_API + "?per_page=" + ReleaseSelector.MAX_RELEASES;

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

    @FunctionalInterface
    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    private final String apiUrl;
    private final SettingsManager settings;
    private final boolean developmentPreview;
    private final ConnectionFactory connections;

    public GitHubUpdateChecker(SettingsManager settings) {
        this(DEFAULT_RELEASES_API_URL, settings);
    }

    /** Retains the constructor signature; unapproved endpoints are refused before opening a connection. */
    public GitHubUpdateChecker(String apiUrl, SettingsManager settings) {
        this(apiUrl, settings, BuildConfig.DEVELOPMENT_PREVIEW,
                url -> (HttpURLConnection) url.openConnection());
    }

    // Test seam replaces only connection creation; the real HTTP/JSON/selection path still executes.
    GitHubUpdateChecker(String apiUrl, SettingsManager settings, boolean developmentPreview, ConnectionFactory connections) {
        this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.developmentPreview = developmentPreview;
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public static ReleaseSelector.Channel channelForVersion(String currentVersion) {
        return ReleaseSelector.channelForBuild(currentVersion, BuildConfig.DEVELOPMENT_PREVIEW);
    }

    /** Checks only when invoked. No scheduling, installation, download or background retry. */
    public UpdateResult checkForUpdate(String currentVersion) {
        return checkForUpdate(currentVersion, false);
    }

    /** force is reserved for an explicit user action, preserving the existing About behavior. */
    public UpdateResult checkForUpdate(String currentVersion, boolean force) {
        if (!force && !settings.isUpdateCheckEnabled()) return UpdateResult.disabled();
        if (SemVer.parseTag(currentVersion) == null) return UpdateResult.error("当前版本号无效，无法安全比较更新");
        if (!DEFAULT_RELEASES_API_URL.equals(apiUrl)) return UpdateResult.error("更新来源不在允许的 GitHub 仓库范围内");

        HttpURLConnection connection = null;
        try {
            connection = connections.open(new URL(apiUrl));
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "Orchestella-Studio-Android/" + currentVersion);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) return UpdateResult.error("GitHub API 返回状态码 " + responseCode);
            String links = connection.getHeaderField("Link");
            if (links != null && links.contains("rel=\"next\"")) {
                return UpdateResult.error("发布列表超出检查范围，请在 GitHub Releases 页面手动查看");
            }
            if (connection.getContentLengthLong() > ReleaseSelector.MAX_RESPONSE_BYTES) {
                return UpdateResult.error("发布列表超出检查范围");
            }
            ReleaseSelector.Channel channel = ReleaseSelector.channelForBuild(currentVersion, developmentPreview);
            String responseBody = readResponse(connection);
            ReleaseSelector.Release release;
            try {
                release = ReleaseSelector.select(responseBody, currentVersion, channel);
            } catch (IllegalArgumentException ex) {
                // Only selector diagnostics are fixed local messages; transport errors are not.
                return UpdateResult.error(ex.getMessage());
            }
            return release == null ? UpdateResult.upToDate(currentVersion)
                    : UpdateResult.updateAvailable(release.tag(), release.notes(), release.pageUrl());
        } catch (Exception ex) {
            // Transport errors may contain an untrusted URL/body; keep diagnostics content-free.
            return UpdateResult.error("检查更新失败，请稍后手动重试");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > ReleaseSelector.MAX_RESPONSE_BYTES - bytes.size()) {
                    throw new IllegalArgumentException("发布列表超出检查范围");
                }
                bytes.write(buffer, 0, count);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        }
    }

    static boolean isNewerVersion(String remote, String current) {
        SemVer candidate = SemVer.parseTag(remote);
        SemVer installed = SemVer.parseTag(current);
        return candidate != null && installed != null && candidate.compareTo(installed) > 0;
    }
}
