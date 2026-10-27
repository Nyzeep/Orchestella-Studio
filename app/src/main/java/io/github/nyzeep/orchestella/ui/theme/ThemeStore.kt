package io.github.nyzeep.orchestella.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/** One persistent appearance source, also usable with in-memory preferences in JVM tests. */
class ThemeStore(private val prefs: SharedPreferences, private val context: Context? = null) {
    enum class Mode(val value: String, val displayName: String) {
        SYSTEM("system", "跟随系统"), LIGHT("light", "浅色"), DARK("dark", "深色")
    }

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        context.applicationContext
    ) {
        ThemeApplier.install(context)
    }

    /** Missing/obsolete ids resolve through the catalog, without overwriting saved preferences. */
    fun themeId(): String {
        val stored = prefs.getString(KEY_THEME_ID, null) ?: return ThemeCatalog.resolve(null).id
        if (stored.startsWith(CustomThemeSlot.ID_PREFIX)) return stored
        return ThemeCatalog.resolve(stored).id
    }

    /** Choosing a labeled light/dark preset also selects its mode, atomically with its colors. */
    fun setThemeId(id: String) {
        val theme = ThemeCatalog.byId(id)
        if (theme == null) {
            if (!id.startsWith(CustomThemeSlot.ID_PREFIX)) return
            val slot = resolveCustomSlot(id) ?: return
            prefs.edit().putString(KEY_THEME_ID, id)
                .putString(KEY_THEME_MODE, if (slot.isDark) Mode.DARK.value else Mode.LIGHT.value)
                .remove(KEY_CUSTOM_ACCENT).remove(KEY_CUSTOM_TEXT).apply()
            return
        }
        prefs.edit().putString(KEY_THEME_ID, theme.id)
            .putString(KEY_THEME_MODE, if (theme.isDark) Mode.DARK.value else Mode.LIGHT.value)
            .remove(KEY_CUSTOM_ACCENT).remove(KEY_CUSTOM_TEXT).apply()
    }

    /** Existing preferences retain their follow-system behavior until the user makes a selection. */
    fun mode(): Mode = Mode.entries.firstOrNull { it.value == prefs.getString(KEY_THEME_MODE, null) } ?: Mode.SYSTEM

    fun setMode(mode: Mode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.value).apply()
    }

    /** Creation-time only (ThemeApplier.prepare); live changes repaint without recreating Activities. */
    fun applyNightMode(activity: AppCompatActivity) {
        activity.delegate.localNightMode = when (mode()) {
            Mode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Mode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Mode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    // --- Custom theme slots: three editable palettes in flat JVM-testable keys ---

    fun customSlots(): List<CustomThemeSlot?> = (0 until CustomThemeSlot.SLOT_COUNT).map { customSlot(it) }

    /** A slot is null when unset or when its palette keys are incomplete (corrupt data falls back). */
    fun customSlot(index: Int): CustomThemeSlot? {
        require(index in 0 until CustomThemeSlot.SLOT_COUNT) { "Custom slot index out of range: $index" }
        val n = index + 1
        val name = prefs.getString(slotKey(n, "name"), null) ?: return null
        val dark = if (prefs.contains(slotKey(n, "dark"))) prefs.getBoolean(slotKey(n, "dark"), true) else return null
        fun color(field: String): Int? =
            if (prefs.contains(slotKey(n, field))) prefs.getInt(slotKey(n, field), 0) else null
        return CustomThemeSlot(
            name = name,
            isDark = dark,
            background = color("bg") ?: return null,
            surface = color("surface") ?: return null,
            primary = color("primary") ?: return null,
            accent = color("accent") ?: return null,
            text = color("text") ?: return null,
            textDim = color("text_dim") ?: return null,
            terminalBackground = color("term_bg") ?: return null,
            terminalForeground = color("term_fg") ?: return null,
        )
    }

    /** null resets the slot; writing one never touches the active theme id or other slots. */
    fun setCustomSlot(index: Int, slot: CustomThemeSlot?) {
        require(index in 0 until CustomThemeSlot.SLOT_COUNT) { "Custom slot index out of range: $index" }
        val n = index + 1
        prefs.edit().apply {
            SLOT_FIELDS.forEach { remove(slotKey(n, it)) }
            if (slot != null) {
                putString(slotKey(n, "name"), slot.name)
                putBoolean(slotKey(n, "dark"), slot.isDark)
                putInt(slotKey(n, "bg"), slot.background or 0xFF000000.toInt())
                putInt(slotKey(n, "surface"), slot.surface or 0xFF000000.toInt())
                putInt(slotKey(n, "primary"), slot.primary or 0xFF000000.toInt())
                putInt(slotKey(n, "accent"), slot.accent or 0xFF000000.toInt())
                putInt(slotKey(n, "text"), slot.text or 0xFF000000.toInt())
                putInt(slotKey(n, "text_dim"), slot.textDim or 0xFF000000.toInt())
                putInt(slotKey(n, "term_bg"), slot.terminalBackground or 0xFF000000.toInt())
                putInt(slotKey(n, "term_fg"), slot.terminalForeground or 0xFF000000.toInt())
            }
        }.apply()
    }

    /** "custom:1".."custom:3" resolve to their saved slot; anything else is null. */
    fun resolveCustomSlot(id: String): CustomThemeSlot? {
        if (!id.startsWith(CustomThemeSlot.ID_PREFIX)) return null
        val index = id.substringAfter(CustomThemeSlot.ID_PREFIX).toIntOrNull() ?: return null
        if (index !in 1..CustomThemeSlot.SLOT_COUNT) return null
        return customSlot(index - 1)
    }

    fun customAccent(): Int? =
        if (prefs.contains(KEY_CUSTOM_ACCENT)) prefs.getInt(KEY_CUSTOM_ACCENT, 0) else null

    fun setCustomAccent(argb: Int?) {
        prefs.edit().apply {
            if (argb == null) remove(KEY_CUSTOM_ACCENT) else putInt(KEY_CUSTOM_ACCENT, argb or 0xFF000000.toInt())
        }.apply()
    }

    fun customText(): Int? =
        if (prefs.contains(KEY_CUSTOM_TEXT)) prefs.getInt(KEY_CUSTOM_TEXT, 0) else null

    fun setCustomText(argb: Int?) {
        prefs.edit().apply {
            if (argb == null) remove(KEY_CUSTOM_TEXT) else putInt(KEY_CUSTOM_TEXT, argb or 0xFF000000.toInt())
        }.apply()
    }

    /** Only a user-picked local content URI is accepted; no path or remote URL is opened. */
    fun backgroundUri(): String? = prefs.getString(KEY_BACKGROUND_URI, null)

    fun setBackgroundUri(uri: String?) {
        require(uri == null || uri.startsWith("content://")) { "Background must be a picked content URI" }
        prefs.edit().apply {
            if (uri == null) remove(KEY_BACKGROUND_URI) else putString(KEY_BACKGROUND_URI, uri)
        }.apply()
    }

    // --- Global button corner radius token ---

    /** 0 keeps the current square look; the settings dialog offers 0/8/16 dp. */
    fun cornerRadiusDp(): Int = prefs.getInt(KEY_CORNER_RADIUS_DP, 0).coerceIn(0, MAX_CORNER_RADIUS_DP)

    fun setCornerRadiusDp(dp: Int) {
        prefs.edit().putInt(KEY_CORNER_RADIUS_DP, dp.coerceIn(0, MAX_CORNER_RADIUS_DP)).apply()
    }

    fun terminalFontSizeSp(): Int =
        prefs.getInt(KEY_TERMINAL_FONT_SP, 14).coerceIn(MIN_TERMINAL_FONT_SP, MAX_TERMINAL_FONT_SP)

    fun setTerminalFontSizeSp(sp: Int) {
        prefs.edit().putInt(KEY_TERMINAL_FONT_SP, sp.coerceIn(MIN_TERMINAL_FONT_SP, MAX_TERMINAL_FONT_SP)).apply()
    }

    fun terminalLetterSpacingEm(): Float =
        ThemeColors.boundedSpacing(prefs.getFloat(KEY_TERMINAL_LETTER_SPACING, 0f), 0.20f)

    fun setTerminalLetterSpacingEm(em: Float) {
        prefs.edit().putFloat(KEY_TERMINAL_LETTER_SPACING, ThemeColors.boundedSpacing(em, 0.20f)).apply()
    }

    fun terminalLineSpacingPx(): Float =
        ThemeColors.boundedSpacing(prefs.getFloat(KEY_TERMINAL_LINE_SPACING, 0f), 8f)

    fun setTerminalLineSpacingPx(px: Float) {
        prefs.edit().putFloat(KEY_TERMINAL_LINE_SPACING, ThemeColors.boundedSpacing(px, 8f)).apply()
    }

    /** Keep a strong reference to the listener; register in onStart, remove in onStop. */
    fun addOnThemeChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeOnThemeChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @JvmOverloads
    fun resolve(callerContext: Context? = this.context): AppTheme {
        val systemDark = if (callerContext == null) {
            ThemeCatalog.resolve(themeId()).isDark
        } else {
            // Activity/dialog resources may retain a creation-time localNightMode override.
            val uiMode = callerContext.applicationContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        return resolveForMode(systemDark)
    }

    /** Android-free mode seam shared by chrome, terminal rendering and preferences tests. */
    fun resolveForMode(systemDark: Boolean): AppTheme {
        val id = themeId()
        val base = if (id.startsWith(CustomThemeSlot.ID_PREFIX)) {
            resolveCustomSlot(id)?.toAppTheme(id) ?: catalogTheme(null, systemDark)
        } else {
            catalogTheme(id, systemDark)
        }
        return withOverrides(base)
    }

    private fun catalogTheme(id: String?, systemDark: Boolean): AppTheme {
        val isDark = when (mode()) {
            Mode.SYSTEM -> systemDark
            Mode.LIGHT -> false
            Mode.DARK -> true
        }
        return ThemeCatalog.resolveForMode(id, isDark)
    }

    private fun withOverrides(base: AppTheme): AppTheme {
        val accent = customAccent()
        val text = customText()
        if (accent == null && text == null) return base
        return base.copy(
            primary = accent ?: base.primary,
            accent = accent ?: base.accent,
            glow = accent ?: base.glow,
            text = text ?: base.text,
            terminalForeground = text ?: base.terminalForeground,
        )
    }

    /** Returns false if the provider cannot retain access; do not report a saved wallpaper then. */
    fun persistBackgroundPermission(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }

    companion object {
        const val PREFS_NAME = "orchestella_theme"
        const val KEY_THEME_ID = "theme_id"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_CUSTOM_ACCENT = "custom_accent"
        const val KEY_CUSTOM_TEXT = "custom_text"
        const val KEY_BACKGROUND_URI = "background_uri"
        const val KEY_TERMINAL_FONT_SP = "terminal_font_sp"
        const val KEY_TERMINAL_LETTER_SPACING = "terminal_letter_spacing"
        const val KEY_TERMINAL_LINE_SPACING = "terminal_line_spacing"
        const val KEY_CUSTOM_SLOT_PREFIX = "custom_slot_"
        const val KEY_CORNER_RADIUS_DP = "corner_radius_dp"
        const val MAX_CORNER_RADIUS_DP = 24
        const val MIN_TERMINAL_FONT_SP = 10
        const val MAX_TERMINAL_FONT_SP = 22

        private val SLOT_FIELDS = listOf(
            "name", "dark", "bg", "surface", "primary", "accent", "text", "text_dim", "term_bg", "term_fg",
        )
        private fun slotKey(slotNumber: Int, field: String) = "${KEY_CUSTOM_SLOT_PREFIX}${slotNumber}_$field"
    }
}
