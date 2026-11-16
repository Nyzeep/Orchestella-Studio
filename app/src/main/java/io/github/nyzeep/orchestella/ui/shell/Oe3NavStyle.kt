package io.github.nyzeep.orchestella.ui.shell

import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel

/**
 * Bottom-bar geometry (Theme Studio "底栏样式", spec §3.3 item 6): floating is
 * the default capsule; fixed docks the bar flush to the screen bottom. The
 * glass level is an independent preference the shell forwards to the theme,
 * so it is not part of this enum.
 */
enum class Oe3NavStyle {
    /** Capsule with side/bottom margins floating over the content. */
    Floating,

    /** Full-width bar docked to the bottom edge, extending behind the system bar. */
    Fixed,
    ;

    /** Store-facing key, shared with ThemeStore `ui_nav_style`'s value set. */
    val key: String
        get() = name.lowercase()

    companion object {
        /** Maps ThemeStore's normalized `ui_nav_style` value; unknown falls back. */
        fun fromKey(key: String?): Oe3NavStyle =
            entries.firstOrNull { it.key == key } ?: Floating
    }
}

/**
 * Glass level mapped from ThemeStore's normalized `ui_glass_level` value;
 * unknown values fall back to [Oe3GlassLevel.None] exactly like the store.
 */
fun glassLevelFromKey(key: String?): Oe3GlassLevel = when (key) {
    "standard" -> Oe3GlassLevel.Standard
    "ultra" -> Oe3GlassLevel.Ultra
    else -> Oe3GlassLevel.None
}
