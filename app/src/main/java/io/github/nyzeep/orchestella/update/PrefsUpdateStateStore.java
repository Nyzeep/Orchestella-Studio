package io.github.nyzeep.orchestella.update;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.nyzeep.orchestella.SettingsManager;

/**
 * SharedPreferences-backed {@link UpdateStateStore}.
 *
 * <p>Lives in the same file as the user settings so the existing legacy migration keeps
 * carrying it forward, but uses its own keys: turning update checks off must not erase a
 * cached answer, and a cached answer must never look like a user setting.
 */
public final class PrefsUpdateStateStore implements UpdateStateStore {

    public static final String KEY_LAST_CHECKED = "update_last_checked_at";
    public static final String KEY_VERSION = "update_available_version";
    public static final String KEY_SUMMARY = "update_available_summary";
    public static final String KEY_PAGE = "update_available_page";
    public static final String KEY_IGNORED = "update_ignored_version";

    private final SharedPreferences preferences;

    public PrefsUpdateStateStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public static PrefsUpdateStateStore from(Context context) {
        return new PrefsUpdateStateStore(context.getApplicationContext()
                .getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE));
    }

    @Override
    public long lastCheckedAt() {
        return preferences.getLong(KEY_LAST_CHECKED, 0L);
    }

    @Override
    public void recordCheckAttempt(long nowMillis) {
        preferences.edit().putLong(KEY_LAST_CHECKED, nowMillis).apply();
    }

    @Override
    public void recordResult(long nowMillis, String version, String summary, String pageUrl) {
        SharedPreferences.Editor editor = preferences.edit().putLong(KEY_LAST_CHECKED, nowMillis);
        if (version == null) {
            editor.remove(KEY_VERSION).remove(KEY_SUMMARY).remove(KEY_PAGE);
        } else {
            editor.putString(KEY_VERSION, version)
                    .putString(KEY_SUMMARY, summary == null ? "" : summary)
                    .putString(KEY_PAGE, pageUrl == null ? "" : pageUrl);
        }
        editor.apply();
    }

    @Override
    public String cachedVersion() {
        return preferences.getString(KEY_VERSION, null);
    }

    @Override
    public String cachedSummary() {
        return preferences.getString(KEY_SUMMARY, "");
    }

    @Override
    public String cachedPageUrl() {
        return preferences.getString(KEY_PAGE, "");
    }

    @Override
    public String ignoredVersion() {
        return preferences.getString(KEY_IGNORED, null);
    }

    @Override
    public void setIgnoredVersion(String version) {
        if (version == null) {
            preferences.edit().remove(KEY_IGNORED).apply();
        } else {
            preferences.edit().putString(KEY_IGNORED, version).apply();
        }
    }
}
