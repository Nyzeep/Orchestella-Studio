package io.github.nyzeep.orchestella.ui.oe3.tokens

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** The eight text roles Oe3 allows. Anything else is a page-local style. */
enum class Oe3TextRole { Brand, PageTitle, Section, Body, BodyStrong, Caption, NavLabel, Mono }

/**
 * Base family the shell overrides for the whole tree (UI3-12 wires Theme
 * Studio's 等宽 choice): `null` keeps the documented Default/Mono mapping.
 * The [Oe3TextRole.Mono] role always stays monospace so terminal-adjacent
 * copy never loses its shape.
 */
val LocalOe3BaseFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@Immutable
data class Oe3TypeSpec(
    val sizeSp: Float,
    val lineHeightSp: Float,
    val weight: FontWeight,
)

/**
 * Oe3 type scale. Sizes are stored in `sp`, so the platform performs the one
 * and only sp→px conversion using [Oe3TypeScale.effective]; no token
 * pre-multiplies the factor, which is what would double-apply it.
 */
object Oe3Typography {

    private val specs: Map<Oe3TextRole, Oe3TypeSpec> = mapOf(
        Oe3TextRole.Brand to Oe3TypeSpec(24f, 30f, FontWeight.SemiBold),
        Oe3TextRole.PageTitle to Oe3TypeSpec(30f, 36f, FontWeight.SemiBold),
        Oe3TextRole.Section to Oe3TypeSpec(17f, 22f, FontWeight.Medium),
        Oe3TextRole.Body to Oe3TypeSpec(14f, 20f, FontWeight.Normal),
        Oe3TextRole.BodyStrong to Oe3TypeSpec(14f, 20f, FontWeight.SemiBold),
        Oe3TextRole.Caption to Oe3TypeSpec(12f, 16f, FontWeight.Normal),
        Oe3TextRole.NavLabel to Oe3TypeSpec(10f, 14.5f, FontWeight.Medium),
        Oe3TextRole.Mono to Oe3TypeSpec(13f, 18f, FontWeight.Normal),
    )

    fun spec(role: Oe3TextRole): Oe3TypeSpec = specs.getValue(role)

    /** Composition-aware read: the theme may override the base family (mono wiring). */
    @Composable
    fun style(role: Oe3TextRole): TextStyle = styleStatic(role, LocalOe3BaseFontFamily.current)

    /** Pure mapping used outside composition (tests, Material typography). */
    fun styleStatic(role: Oe3TextRole, baseFamily: FontFamily? = null): TextStyle {
        val spec = spec(role)
        return TextStyle(
            fontFamily = when {
                role == Oe3TextRole.Mono -> FontFamily.Monospace
                baseFamily != null -> baseFamily
                else -> FontFamily.Default
            },
            fontSize = spec.sizeSp.sp,
            fontWeight = spec.weight,
            lineHeight = spec.lineHeightSp.sp,
        )
    }

    /** Bridge for Material3 consumers; Oe3 primitives read [style] directly. */
    fun materialTypography(baseFamily: FontFamily? = null): Typography = Typography(
        displaySmall = styleStatic(Oe3TextRole.Brand, baseFamily),
        headlineMedium = styleStatic(Oe3TextRole.PageTitle, baseFamily),
        titleMedium = styleStatic(Oe3TextRole.Section, baseFamily),
        titleSmall = styleStatic(Oe3TextRole.BodyStrong, baseFamily),
        bodyLarge = styleStatic(Oe3TextRole.Body, baseFamily),
        bodyMedium = styleStatic(Oe3TextRole.Body, baseFamily),
        bodySmall = styleStatic(Oe3TextRole.Caption, baseFamily),
        labelMedium = styleStatic(Oe3TextRole.Caption, baseFamily),
        labelSmall = styleStatic(Oe3TextRole.NavLabel, baseFamily),
    )
}

/**
 * One scale seam for the whole app: system font scale × in-app override,
 * applied exactly once by overriding `LocalDensity.fontScale`.
 *
 * The system factor is never reduced — accessibility settings stay
 * authoritative. Only the in-app override is held to the documented 85–130%
 * band, and degenerate inputs fall back to 1.0 instead of zeroing every style.
 */
object Oe3TypeScale {
    const val MIN = 0.85f
    const val MAX = 1.30f

    fun clamp(factor: Float): Float = when {
        !factor.isFinite() || factor <= 0f -> 1f
        else -> factor.coerceIn(MIN, MAX)
    }

    fun effective(systemFontScale: Float, appScale: Float = 1f): Float {
        val system = if (systemFontScale.isFinite() && systemFontScale > 0f) systemFontScale else 1f
        return system * clamp(appScale)
    }

    fun density(base: Density, appScale: Float): Density =
        Density(base.density, effective(base.fontScale, appScale))
}
