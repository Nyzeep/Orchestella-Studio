package io.github.nyzeep.droidlayer;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Objects;

/**
 * Manages user preferences and system settings (ADR-0011, ADR-0015, AC-3).
 *
 * <p>Settings include:
 * <ul>
 *   <li>Boot auto-start: disabled by default (ADR-0011 §4, US-35)</li>
 *   <li>GitHub update check: enabled by default, fully disableable with 0 telemetry (ADR-0015 §1, US-36)</li>
 *   <li>Battery policy: Balanced (default) vs Performance (ADR-0011 §5, US-25)</li>
 *   <li>Last active instance ID: used for cold restore and auto-start</li>
 * </ul>
 */
public final class SettingsManager {
    public static final String PREFS_NAME = "orchestella_settings";

    public static final String KEY_BOOT_AUTO_START = "boot_auto_start";
    public static final String KEY_UPDATE_CHECK = "update_check_enabled";
    public static final String KEY_BATTERY_POLICY = "battery_policy";
    public static final String KEY_LAST_ACTIVE_INSTANCE_ID = "last_active_instance_id";
    public static final String KEY_ONBOARDING_COMPLETED = "onboarding_completed";

    private final SharedPreferences preferences;

    public SettingsManager(SharedPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    public SettingsManager(Context context) {
        this(context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    /** Boot auto-start setting: strictly false by default per ADR-0011. */
    public boolean isBootAutoStartEnabled() {
        return preferences.getBoolean(KEY_BOOT_AUTO_START, false);
    }

    public void setBootAutoStartEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_BOOT_AUTO_START, enabled).apply();
    }

    /** GitHub update check setting: true by default, disableable per ADR-0015. */
    public boolean isUpdateCheckEnabled() {
        return preferences.getBoolean(KEY_UPDATE_CHECK, true);
    }

    public void setUpdateCheckEnabled(boolean enabled) {
        preferences.edit().putBoolean(KEY_UPDATE_CHECK, enabled).apply();
    }

    /** Battery policy setting: default BALANCED per ADR-0011. */
    public BatteryPolicy getBatteryPolicy() {
        String name = preferences.getString(KEY_BATTERY_POLICY, BatteryPolicy.BALANCED.name());
        try {
            return BatteryPolicy.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return BatteryPolicy.BALANCED;
        }
    }

    public void setBatteryPolicy(BatteryPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        preferences.edit().putString(KEY_BATTERY_POLICY, policy.name()).apply();
    }

    /** Tracks the last active instance ID for auto-start or cold restore. */
    public String getLastActiveInstanceId() {
        return preferences.getString(KEY_LAST_ACTIVE_INSTANCE_ID, null);
    }

    public void setLastActiveInstanceId(String instanceId) {
        preferences.edit().putString(KEY_LAST_ACTIVE_INSTANCE_ID, instanceId).apply();
    }

    /** Setup wizard completion status: false on fresh install (Ticket 53, US-28). */
    public boolean isOnboardingCompleted() {
        return preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false);
    }

    public void setOnboardingCompleted(boolean completed) {
        preferences.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply();
    }
}
