package io.github.nyzeep.orchestella.ui.screens.config

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.SettingsManager

/**
 * The tiny storage seam behind [ConfigPreferences].
 *
 * Production uses [SharedPreferencesConfigStore]; the JVM test uses an
 * in-memory map, so persistence round-trips are verified without Robolectric.
 */
interface ConfigStore {
    fun getString(key: String): String?
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
}

/** Reads/writes the app's single settings file, so existing keys stay shared. */
class SharedPreferencesConfigStore(context: Context) : ConfigStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(SettingsManager.PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        preferences.getBoolean(key, defaultValue)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
    }
}

/** The user-picked entry motion for settings sub-pages and the config groups. */
enum class SectionMotion(val key: String, @StringRes val labelRes: Int) {
    Standard("standard", R.string.config_motion_standard),
    Reduced("reduced", R.string.config_motion_reduced),
    Off("off", R.string.config_motion_off);

    companion object {
        /** Unknown or missing values fall back to the shipped behaviour. */
        fun fromKey(key: String?): SectionMotion =
            entries.firstOrNull { it.key == key } ?: Standard
    }
}

/**
 * Default mirror policy. Keys mirror `DistroMirrorProvisioner.MirrorSource`
 * names, so the mirror page can map the stored value onto its spinner without
 * a second enum table.
 */
enum class DefaultMirror(val key: String, @StringRes val labelRes: Int) {
    MultiTier("MULTI_TIER", R.string.config_mirror_multi_tier),
    Tuna("TUNA", R.string.config_mirror_tuna),
    Ustc("USTC", R.string.config_mirror_ustc),
    Aliyun("ALIYUN", R.string.config_mirror_aliyun);

    companion object {
        /** Unknown or missing values fall back to the existing multi-tier default. */
        fun fromKey(key: String?): DefaultMirror =
            entries.firstOrNull { it.key == key } ?: MultiTier
    }
}

/** Immutable snapshot of the four playability options shown on the config page. */
@Immutable
data class ConfigOptions(
    val sectionMotion: SectionMotion = SectionMotion.Standard,
    val defaultMirror: DefaultMirror = DefaultMirror.MultiTier,
    val bootRestore: Boolean = false,
    val updateCheck: Boolean = true,
)

/**
 * Persistence for the config page's options.
 *
 * Two of the four options are existing product switches (`boot_auto_start`,
 * `update_check_enabled`); they are read/written here through the same
 * `orchestella_settings` file so the config page and the launch/update paths
 * never disagree. The other two are new keys under the `ui3_config_` prefix.
 */
class ConfigPreferences(private val store: ConfigStore) {

    fun sectionMotion(): SectionMotion =
        SectionMotion.fromKey(store.getString(KEY_SECTION_MOTION))

    fun setSectionMotion(value: SectionMotion) {
        store.putString(KEY_SECTION_MOTION, value.key)
    }

    fun defaultMirror(): DefaultMirror =
        DefaultMirror.fromKey(store.getString(KEY_DEFAULT_MIRROR))

    fun setDefaultMirror(value: DefaultMirror) {
        store.putString(KEY_DEFAULT_MIRROR, value.key)
    }

    /** Boot cold-restore; ADR-0011 keeps this off unless the user opts in. */
    fun bootRestore(): Boolean = store.getBoolean(KEY_BOOT_RESTORE, false)

    fun setBootRestore(enabled: Boolean) {
        store.putBoolean(KEY_BOOT_RESTORE, enabled)
    }

    /** GitHub release check; enabled by default and fully disableable (ADR-0015). */
    fun updateCheck(): Boolean = store.getBoolean(KEY_UPDATE_CHECK, true)

    fun setUpdateCheck(enabled: Boolean) {
        store.putBoolean(KEY_UPDATE_CHECK, enabled)
    }

    /** One read for the screen's initial state. */
    fun readOptions(): ConfigOptions = ConfigOptions(
        sectionMotion = sectionMotion(),
        defaultMirror = defaultMirror(),
        bootRestore = bootRestore(),
        updateCheck = updateCheck(),
    )

    /** Writes only the fields that changed, then returns the new snapshot. */
    fun writeOptions(previous: ConfigOptions, next: ConfigOptions): ConfigOptions {
        if (next.sectionMotion != previous.sectionMotion) setSectionMotion(next.sectionMotion)
        if (next.defaultMirror != previous.defaultMirror) setDefaultMirror(next.defaultMirror)
        if (next.bootRestore != previous.bootRestore) setBootRestore(next.bootRestore)
        if (next.updateCheck != previous.updateCheck) setUpdateCheck(next.updateCheck)
        return next
    }

    companion object {
        /** New key: standards/reduced/off entrance motion. */
        const val KEY_SECTION_MOTION = "ui3_config_section_motion"

        /** New key: default software-source policy. */
        const val KEY_DEFAULT_MIRROR = "ui3_config_default_mirror"

        /** Existing product key; default false lives in SettingsManager/ADR-0011. */
        const val KEY_BOOT_RESTORE = SettingsManager.KEY_BOOT_AUTO_START

        /** Existing product key; default true lives in SettingsManager/ADR-0015. */
        const val KEY_UPDATE_CHECK = SettingsManager.KEY_UPDATE_CHECK
    }
}
