package io.github.nyzeep.orchestella.update;

/**
 * The only persistence the update check is allowed to touch.
 *
 * <p>Kept as an interface so the whole update decision can be exercised on the JVM without
 * Android, and so the storage keys stay in one place instead of being sprinkled across
 * activities.
 */
public interface UpdateStateStore {

    /** Epoch millis of the last completed attempt, or 0 when never attempted. */
    long lastCheckedAt();

    /** Throttle bookkeeping only: records that an attempt happened, without changing the cache. */
    void recordCheckAttempt(long nowMillis);

    /**
     * Records a completed check.
     *
     * @param version the newer version tag, or {@code null} when the app is already current
     * @param summary short, already-summarised notes (may be empty)
     * @param pageUrl the release page to open (may be null and later derived from the version)
     */
    void recordResult(long nowMillis, String version, String summary, String pageUrl);

    String cachedVersion();

    String cachedSummary();

    String cachedPageUrl();

    /** Version the user asked to skip; it stays hidden until a newer one appears. */
    String ignoredVersion();

    void setIgnoredVersion(String version);
}
