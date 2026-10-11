package io.github.nyzeep.orchestella.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Persistence for appearance settings: selected built-in theme, custom accent and
 * text colors, optional user background image, and terminal typography (font size
 * and letter spacing). The resolved [AppTheme] honors user overrides on top of the
 * selected built-in.
 */
class ThemeStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("orchestella_theme", Context.MODE_PRIVATE)

    fun themeId(): String = prefs.getString(KEY_THEME_ID, null) ?: "dracula"

    fun setThemeId(id: String) {
        prefs.edit().putString(KEY_THEME_ID, id).apply()
    }

    /** Custom accent color (ARGB int), or null when following the built-in theme. */
    fun customAccent(): Int? =
        if (prefs.contains(KEY_CUSTOM_ACCENT)) prefs.getInt(KEY_CUSTOM_ACCENT, 0) else null

    fun setCustomAccent(argb: Int?) {
        prefs.edit().apply {
            if (argb == null) remove(KEY_CUSTOM_ACCENT) else putInt(KEY_CUSTOM_ACCENT, argb)
        }.apply()
    }

    /** Custom primary text color (ARGB int), or null when following the built-in theme. */
    fun customText(): Int? =
        if (prefs.contains(KEY_CUSTOM_TEXT)) prefs.getInt(KEY_CUSTOM_TEXT, 0) else null

    fun setCustomText(argb: Int?) {
        prefs.edit().apply {
            if (argb == null) remove(KEY_CUSTOM_TEXT) else putInt(KEY_CUSTOM_TEXT, argb)
        }.apply()
    }

    /** User background image (content URI string), or null for the plain theme background. */
    fun backgroundUri(): String? = prefs.getString(KEY_BACKGROUND_URI, null)

    fun setBackgroundUri(uri: String?) {
        prefs.edit().apply {
            if (uri == null) remove(KEY_BACKGROUND_URI) else putString(KEY_BACKGROUND_URI, uri)
        }.apply()
    }

    /** Terminal font size in SP. */
    fun terminalFontSizeSp(): Int = prefs.getInt(KEY_TERMINAL_FONT_SP, 14)

    fun setTerminalFontSizeSp(sp: Int) {
        prefs.edit().putInt(KEY_TERMINAL_FONT_SP, sp.coerceIn(MIN_TERMINAL_FONT_SP, MAX_TERMINAL_FONT_SP)).apply()
    }

    /** Terminal letter spacing in em (e.g. 0.0 - 0.20). */
    fun terminalLetterSpacingEm(): Float = prefs.getFloat(KEY_TERMINAL_LETTER_SPACING, 0f)

    fun setTerminalLetterSpacingEm(em: Float) {
        prefs.edit().putFloat(KEY_TERMINAL_LETTER_SPACING, em.coerceIn(0f, 0.20f)).apply()
    }

    fun terminalLineSpacingPx(): Float = prefs.getFloat(KEY_TERMINAL_LINE_SPACING, 0f)

    fun setTerminalLineSpacingPx(px: Float) {
        prefs.edit().putFloat(KEY_TERMINAL_LINE_SPACING, px.coerceIn(0f, 8f)).apply()
    }

    /** Resolves the effective theme with user overrides applied. */
    fun resolve(): AppTheme {
        val base = ThemeCatalog.resolve(themeId())
        val accent = customAccent()
        val text = customText()
        if (accent == null && text == null) {
            return base
        }
        return base.copy(
            primary = accent ?: base.primary,
            glow = accent ?: base.glow,
            text = text ?: base.text,
            terminalForeground = text ?: base.terminalForeground,
        )
    }

    /** Takes a persistable permission for the picked background image. */
    fun persistBackgroundPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }

    companion object {
        private const val KEY_THEME_ID = "theme_id"
        private const val KEY_CUSTOM_ACCENT = "custom_accent"
        private const val KEY_CUSTOM_TEXT = "custom_text"
        private const val KEY_BACKGROUND_URI = "background_uri"
        private const val KEY_TERMINAL_FONT_SP = "terminal_font_sp"
        private const val KEY_TERMINAL_LETTER_SPACING = "terminal_letter_spacing"
        private const val KEY_TERMINAL_LINE_SPACING = "terminal_line_spacing"

        const val MIN_TERMINAL_FONT_SP = 10
        const val MAX_TERMINAL_FONT_SP = 22
    }
}
