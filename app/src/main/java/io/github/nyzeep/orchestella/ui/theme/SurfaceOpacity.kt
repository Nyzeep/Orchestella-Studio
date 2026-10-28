package io.github.nyzeep.orchestella.ui.theme

/** Pure JVM math for the UI surface opacity slider (70..100 percent). */
object SurfaceOpacity {
    const val MIN_PERCENT = 70
    const val MAX_PERCENT = 100
    const val DEFAULT_PERCENT = 100
    const val MIN_TEXT_CONTRAST = 4.5

    fun clampPercent(requested: Int): Int = requested.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Keeps the surface RGB; alpha becomes percent*255/100. */
    fun applyOpacity(surfaceArgb: Int, percent: Int): Int {
        val alpha = (clampPercent(percent) * 255) / 100
        return (surfaceArgb and 0x00FFFFFF) or (alpha shl 24)
    }

    /** WCAG 2.x relative-luminance contrast after alpha-compositing the foreground onto the background. */
    fun wcagContrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double {
        val foreground = compositeOver(foregroundArgb, backgroundArgb)
        val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(backgroundArgb))
        val darker = minOf(relativeLuminance(foreground), relativeLuminance(backgroundArgb))
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Contrast check at the requested opacity: the surface is alpha-composited onto the
     * opaque surface behind it, then measured against the text color. Below the 4.5:1
     * floor it falls back to [MIN_PERCENT] (the caller guarantees the floor stays usable).
     */
    fun resolveEffectivePercent(
        surfaceArgb: Int, behindArgb: Int, textArgb: Int, requestedPercent: Int,
    ): Int {
        val requested = clampPercent(requestedPercent)
        val composited = compositeOver(applyOpacity(surfaceArgb, requested), behindArgb)
        if (wcagContrastRatio(textArgb, composited) >= MIN_TEXT_CONTRAST) return requested
        return MIN_PERCENT
    }

    /** Standard source-over compositing in un-premultiplied sRGB. */
    private fun compositeOver(foregroundArgb: Int, backgroundArgb: Int): Int {
        val fa = ((foregroundArgb ushr 24) and 0xFF) / 255.0
        val ba = ((backgroundArgb ushr 24) and 0xFF) / 255.0
        val outAlpha = fa + ba * (1.0 - fa)
        if (outAlpha <= 0.0) return 0
        fun mix(shift: Int): Int {
            val f = ((foregroundArgb ushr shift) and 0xFF) / 255.0
            val b = ((backgroundArgb ushr shift) and 0xFF) / 255.0
            val c = (f * fa + b * ba * (1.0 - fa)) / outAlpha
            return (c * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
        val r = mix(16)
        val g = mix(8)
        val b = mix(0)
        return b or (g shl 8) or (r shl 16) or (((outAlpha * 255.0) + 0.5).toInt() shl 24)
    }

    /** WCAG 2.x relative luminance of an sRGB channel triple. */
    private fun relativeLuminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb ushr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
