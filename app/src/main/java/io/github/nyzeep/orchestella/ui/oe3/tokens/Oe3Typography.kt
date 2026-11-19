package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.nyzeep.orchestella.ui.design.tokens.OeType
import io.github.nyzeep.orchestella.ui.design.tokens.OeTypeRole
import io.github.nyzeep.orchestella.ui.design.tokens.OeTypeSpec

/**
 * Legacy names for the text roles. The enum and spec types live in
 * `ui/design/tokens` since UI-01; these aliases keep every existing import
 * (`Oe3TextRole.Brand`, `Oe3TypeSpec`) source-compatible.
 */
typealias Oe3TextRole = OeTypeRole
typealias Oe3TypeSpec = OeTypeSpec

/**
 * Base family the shell overrides for the whole tree (UI3-12 wires Theme
 * Studio's 等宽 choice): `null` keeps the documented Default/Mono mapping.
 * The [Oe3TextRole.Mono] role always stays monospace so terminal-adjacent
 * copy never loses its shape.
 */
val LocalOe3BaseFontFamily = staticCompositionLocalOf<FontFamily?> { null }

/**
 * Legacy entry point for the type scale. The values live in [OeType]
 * (`ui/design/tokens`) since UI-01; this object only delegates so existing
 * call sites and tests keep their names and numbers unchanged.
 */
object Oe3Typography {

    fun spec(role: Oe3TextRole): Oe3TypeSpec = OeType.spec(role)

    /** Composition-aware read: the theme may override the base family (mono wiring). */
    @Composable
    fun style(role: Oe3TextRole): TextStyle = OeType.styleStatic(role, LocalOe3BaseFontFamily.current)

    /** Pure mapping used outside composition (tests, Material typography). */
    fun styleStatic(role: Oe3TextRole, baseFamily: FontFamily? = null): TextStyle =
        OeType.styleStatic(role, baseFamily)

    /** Bridge for Material3 consumers; Oe3 primitives read [style] directly. */
    fun materialTypography(baseFamily: FontFamily? = null): Typography =
        OeType.materialTypography(baseFamily)
}

/**
 * Legacy entry point for the one scale seam: system font scale × in-app
 * override, applied exactly once by overriding `LocalDensity.fontScale`.
 *
 * The system factor is never reduced — accessibility settings stay
 * authoritative. Only the in-app override is held to the documented 85–130%
 * band, and degenerate inputs fall back to 1.0 instead of zeroing every style.
 */
object Oe3TypeScale {
    const val MIN = OeType.SCALE_MIN
    const val MAX = OeType.SCALE_MAX

    fun clamp(factor: Float): Float = OeType.clampScale(factor)

    fun effective(systemFontScale: Float, appScale: Float = 1f): Float =
        OeType.effectiveScale(systemFontScale, appScale)

    fun density(base: Density, appScale: Float): Density = OeType.density(base, appScale)
}
