package io.github.nyzeep.orchestella.ui.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Android-free color and preference rules shared by chrome and tests. */
object ThemeColors {
    enum class Role { BACKGROUND, SURFACE, PRIMARY, ACCENT, TEXT, MUTED, OUTLINE }

    fun color(theme: AppTheme, role: Role): Int = when (role) {
        Role.BACKGROUND -> theme.background
        Role.SURFACE -> theme.surface
        Role.PRIMARY -> theme.primary
        Role.ACCENT -> theme.accent
        Role.TEXT -> theme.text
        Role.MUTED -> readable(theme.textDim, theme.surface)
        Role.OUTLINE -> blend(theme.surface, theme.text, 0.28f)
    }

    /** Prefer black/white by measured contrast; amber and pastel buttons need dark ink. */
    fun onColor(background: Int): Int =
        if (contrast(0xFF000000.toInt(), background) >= contrast(0xFFFFFFFF.toInt(), background)) {
            0xFF000000.toInt()
        } else {
            0xFFFFFFFF.toInt()
        }

    fun readable(foreground: Int, background: Int, minimum: Double = 4.5): Int {
        val opaque = foreground or 0xFF000000.toInt()
        if (contrast(opaque, background) >= minimum) return opaque
        val target = onColor(background)
        for (step in 1..20) {
            val candidate = blend(opaque, target, step / 20f)
            if (contrast(candidate, background) >= minimum) return candidate
        }
        return target
    }

    fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    fun blend(first: Int, second: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int =
            (((first ushr shift and 255) * (1f - f)) + ((second ushr shift and 255) * f)).toInt()
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Source roles are captured once, before replacing legacy hardcoded colors. */
    fun textRole(source: Int): Role {
        val rgb = source and 0x00FFFFFF
        return when (rgb) {
            0xD62828, 0x0047AB, 0xBD93F9, 0x007CFF, 0x00A1FF, 0xCBA6F7 -> Role.PRIMARY
            0xFDBB24, 0x8BE9FD, 0x00F6FF, 0xFFFF79, 0xFF79C6 -> Role.ACCENT
            0x8E929B, 0x9399B2, 0x6272A4, 0x8B949E, 0xA19E9A, 0x6B7280,
            0x9CA3AF, 0xAAAAAA, 0x888888, 0xCCCCCC -> Role.MUTED
            else -> if (isNeutral(rgb) && luminance(source) in 0.08..0.65) Role.MUTED else Role.TEXT
        }
    }

    fun backgroundRole(source: Int): Role {
        val rgb = source and 0x00FFFFFF
        return when (rgb) {
            0x0D0E11, 0x0C1017, 0x282A36, 0x07080A -> Role.BACKGROUND
            // Audit S3 blind spots: legacy card/surface tints pinned to SURFACE so
            // they no longer ride on the neutral-luminance heuristic band.
            0x121318, 0x0B0C0F, 0x0C0D10, 0x0D0E12, 0x0E0F14, 0x1F2026, 0x1E2028 -> Role.SURFACE
            // Session 17: theConstructivist dashboard XML fill family. #16171D doubles as
            // the light theme's text ink, so these must resolve to SURFACE explicitly
            // (see isLegacySurfaceHex for the inference-order guard).
            0x16171D, 0x1A1C22, 0x0C0D11 -> Role.SURFACE
            0xF5F4F0, 0xF8F8F2, 0xFFFFFF -> Role.TEXT
            0x2E323B, 0x333333 -> Role.OUTLINE
            else -> when (val role = textRole(source)) {
                Role.PRIMARY, Role.ACCENT -> role
                else -> Role.SURFACE
            }
        }
    }

    /**
     * True for the legacy dark-surface hex family used across dashboard XML drawables.
     * [0x16171D][0x16171D] collides with light themes' text ink, so background-role
     * inference must consult this set before any exact "bg == theme.text" match.
     */
    fun isLegacySurfaceHex(argb: Int): Boolean =
        (argb and 0x00FFFFFF) in legacySurfaceHexes

    private val legacySurfaceHexes = setOf(
        0x16171D, 0x1A1C22, 0x0C0D11, 0x121318, 0x0B0C0F,
        0x0C0D10, 0x0D0E12, 0x0E0F14, 0x1F2026, 0x1E2028,
    )

    /** Only this dashboard wrapper inherits its outer page's wallpaper; cards still paint surfaces. */
    fun isWallpaperContentWrapper(viewName: String, ancestorName: String): Boolean =
        viewName == "root_container" && ancestorName == "root_coordinator"

    fun fontProgress(sp: Int): Int = sp.coerceIn(10, 22) - 10
    fun fontSize(progress: Int): Int = 10 + progress.coerceIn(0, 12)
    fun boundedSpacing(value: Float, maximum: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, maximum) else 0f

    private fun isNeutral(rgb: Int): Boolean {
        val channels = listOf(rgb ushr 16 and 255, rgb ushr 8 and 255, rgb and 255)
        return channels.max() - channels.min() < 48
    }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = (color ushr shift and 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
