package io.github.nyzeep.orchestella.update;

import io.github.nyzeep.orchestella.GitHubUpdateChecker;
import io.github.nyzeep.orchestella.ReleaseSelector;
import io.github.nyzeep.orchestella.SemVer;
import java.util.Objects;

/**
 * Decides whether the app should tell the user about a newer release.
 *
 * <p>Pure state machine over {@link UpdateStateStore}: no Android, no network, no clock of its
 * own. The rules are the product contract (see {@code docs/standards/APP-UPDATE.md}):
 *
 * <ul>
 *   <li>one automatic check per {@link #DEFAULT_INTERVAL_MS}; a manual check always bypasses it</li>
 *   <li>only a strictly newer version is announced — never the same version, never a downgrade</li>
 *   <li>“暂时忽略” hides exactly that version; a newer one re-surfaces the badge</li>
 *   <li>a failed check keeps the last known answer instead of clearing the badge</li>
 * </ul>
 */
public final class UpdateCenter {

    /** Six hours: enough to catch a release the same day without polling GitHub all day. */
    public static final long DEFAULT_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    /** A pending update, ready for the badge and the card. */
    public static final class Available {
        private final String version;
        private final String summary;
        private final String pageUrl;

        Available(String version, String summary, String pageUrl) {
            this.version = version;
            this.summary = summary;
            this.pageUrl = pageUrl;
        }

        public String version() {
            return version;
        }

        public String summary() {
            return summary;
        }

        public String pageUrl() {
            return pageUrl;
        }
    }

    private final UpdateStateStore store;
    private final long intervalMillis;

    public UpdateCenter(UpdateStateStore store) {
        this(store, DEFAULT_INTERVAL_MS);
    }

    public UpdateCenter(UpdateStateStore store, long intervalMillis) {
        this.store = Objects.requireNonNull(store, "store");
        if (intervalMillis < 0) {
            throw new IllegalArgumentException("intervalMillis must not be negative");
        }
        this.intervalMillis = intervalMillis;
    }

    /** True when the automatic check may run now; a manual check ignores this. */
    public boolean shouldCheck(long nowMillis) {
        long last = store.lastCheckedAt();
        return last <= 0L || nowMillis - last >= intervalMillis;
    }

    /** Null when there is nothing to announce for the installed version. */
    public Available pending(String installedVersion) {
        String cached = store.cachedVersion();
        if (cached == null || cached.trim().isEmpty()) {
            return null;
        }
        String ignored = store.ignoredVersion();
        if (ignored != null && ignored.equals(cached)) {
            return null;
        }
        SemVer candidate = SemVer.parseTag(cached);
        SemVer installed = SemVer.parseTag(installedVersion);
        if (candidate == null || installed == null || candidate.compareTo(installed) <= 0) {
            return null;
        }
        String url = store.cachedPageUrl();
        if (url == null || url.isEmpty()) {
            url = ReleaseSelector.RELEASES_PAGE + "/tag/" + cached;
        }
        String summary = store.cachedSummary();
        return new Available(cached, summary == null ? "" : summary, url);
    }

    /** Maps a completed check onto the cache. Never throws and never clears on transport errors. */
    public void record(GitHubUpdateChecker.UpdateResult result, long nowMillis) {
        Objects.requireNonNull(result, "result");
        switch (result.status()) {
            case UPDATE_AVAILABLE:
                store.recordResult(nowMillis, result.latestVersion(),
                        UpdateNotes.summarize(result.releaseNotes()), result.releaseUrl());
                break;
            case UP_TO_DATE:
                store.recordResult(nowMillis, null, null, null);
                break;
            case ERROR:
                // Keep the last known answer: a flaky network must not hide a real update.
                store.recordCheckAttempt(nowMillis);
                break;
            case DISABLED:
            default:
                // Zero telemetry: when the user turned checks off, persist nothing at all.
                break;
        }
    }

    public void ignore(String version) {
        if (version != null && !version.trim().isEmpty()) {
            store.setIgnoredVersion(version.trim());
        }
    }

    /** Clears a previous “暂时忽略” so the badge can come back on the next check. */
    public void clearIgnored() {
        store.setIgnoredVersion(null);
    }
}
