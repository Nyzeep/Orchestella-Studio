package io.github.nyzeep.orchestella.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/** One persistent appearance source, also usable with in-memory preferences in JVM tests. */
class ThemeStore(private val prefs: SharedPreferences, private val context: Context? = null) {
    /**
     * [FIXED] means "the theme decides, not the system". It is not a manual override:
     * a palette that only exists in one brightness (the mascot lavender) owns the
     * decision, and [applyNightMode] honours it so resources inflate in that brightness
     * too. Picking any ordinary preset replaces it with that preset's own brightness.
     */
    enum class Mode(val value: String, val displayName: String) {
        SYSTEM("system", "跟随系统"), LIGHT("light", "浅色"), DARK("dark", "深色"),
        FIXED("fixed", "固定（主题自带明暗）");

        /** Modes the user may choose directly; FIXED is selected by picking a fixed theme. */
        val isUserSelectable: Boolean get() = this != FIXED
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
            .putString(KEY_THEME_MODE, modeForTheme(theme).value)
            .remove(KEY_CUSTOM_ACCENT).remove(KEY_CUSTOM_TEXT).apply()
    }

    /** A fixed palette pins its own brightness; every other preset pins the mode that matches its look. */
    private fun modeForTheme(theme: AppTheme): Mode = when {
        theme.isFixed -> Mode.FIXED
        theme.isDark -> Mode.DARK
        else -> Mode.LIGHT
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
            // FIXED: inflate resources in the theme's own brightness so the first
            // frame already matches, instead of flashing the system's choice.
            Mode.FIXED ->
                if (ThemeCatalog.resolve(themeId()).isDark) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
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

    // --- Terminal-only background image (independent from the global wallpaper) ---

    fun terminalBackgroundUri(): String? = prefs.getString(KEY_TERMINAL_BACKGROUND_URI, null)

    fun setTerminalBackgroundUri(uri: String?) {
        require(uri == null || uri.startsWith("content://")) { "Terminal background must be a picked content URI" }
        prefs.edit().apply {
            if (uri == null) remove(KEY_TERMINAL_BACKGROUND_URI) else putString(KEY_TERMINAL_BACKGROUND_URI, uri)
        }.apply()
    }

    /** 0 = image fully visible, 255 = fully covered by the theme's terminal background. */
    fun terminalScrimAlpha(): Int =
        prefs.getInt(KEY_TERMINAL_SCRIM_ALPHA, DEFAULT_TERMINAL_SCRIM_ALPHA).coerceIn(0, 255)

    fun setTerminalScrimAlpha(alpha: Int) {
        prefs.edit().putInt(KEY_TERMINAL_SCRIM_ALPHA, alpha.coerceIn(0, 255)).apply()
    }

    /** Scrim color for the terminal background image: theme terminal background at the chosen alpha. */
    fun terminalScrimArgb(theme: AppTheme): Int =
        (theme.terminalBackground and 0x00FFFFFF) or (terminalScrimAlpha() shl 24)

    // --- UI surface opacity token (cards and terminal chrome, 70..100 percent) ---

    fun uiSurfaceOpacityPercent(): Int =
        prefs.getInt(KEY_UI_SURFACE_OPACITY, SurfaceOpacity.DEFAULT_PERCENT)
            .coerceIn(SurfaceOpacity.MIN_PERCENT, SurfaceOpacity.MAX_PERCENT)

    fun setUiSurfaceOpacityPercent(percent: Int) {
        prefs.edit().putInt(KEY_UI_SURFACE_OPACITY, SurfaceOpacity.clampPercent(percent)).apply()
    }

    /** Alias for consumers that only read the percent (composition happens at the call site). */
    fun surfaceOpacityPercent(): Int = uiSurfaceOpacityPercent()

    // --- Global button corner radius token ---

    /** 8/12/16 dp are offered; 16 is the default. 0 still means "keep the XML geometry". */
    fun cornerRadiusDp(): Int = prefs.getInt(KEY_CORNER_RADIUS_DP, DEFAULT_CORNER_RADIUS_DP)
        .coerceIn(0, MAX_CORNER_RADIUS_DP)

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
        val stored = ThemeCatalog.resolve(id)
        // A single-form palette has nothing to switch to: honour it before the
        // mode is consulted, so a system dark change can never reach it.
        if (stored.isFixed) return stored
        val isDark = when (mode()) {
            Mode.SYSTEM -> systemDark
            Mode.LIGHT -> false
            Mode.DARK -> true
            // FIXED on a non-fixed theme (shell restored our prefs after the theme
            // changed elsewhere): lock to that theme's own brightness.
            Mode.FIXED -> stored.isDark
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
        const val KEY_TERMINAL_BACKGROUND_URI = "terminal_background_uri"
        const val KEY_TERMINAL_SCRIM_ALPHA = "terminal_scrim_alpha"
        const val DEFAULT_TERMINAL_SCRIM_ALPHA = 0xB0
        const val KEY_UI_SURFACE_OPACITY = "ui_surface_opacity_pct"
        const val MAX_CORNER_RADIUS_DP = 24
        /** Session 25: the product ships with rounded buttons, so the token is no longer opt-in. */
        const val DEFAULT_CORNER_RADIUS_DP = 16
        const val MIN_TERMINAL_FONT_SP = 10
        const val MAX_TERMINAL_FONT_SP = 22

        private val SLOT_FIELDS = listOf(
            "name", "dark", "bg", "surface", "primary", "accent", "text", "text_dim", "term_bg", "term_fg",
        )
        private fun slotKey(slotNumber: Int, field: String) = "${KEY_CUSTOM_SLOT_PREFIX}${slotNumber}_$field"
    }
}
