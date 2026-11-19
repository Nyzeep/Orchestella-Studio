package io.github.nyzeep.orchestella.ui.design.tokens

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Semantic text roles shared by the design system. */
enum class OeTypeRole { Brand, PageTitle, Section, Body, BodyStrong, Caption, NavLabel, Mono, Meta }

@Immutable
data class OeTypeSpec(
    val sizeSp: Float,
    val lineHeightSp: Float,
    val weight: FontWeight,
)

/**
 * The one type scale for the whole app (UI-01 single source of truth).
 *
 * Sizes are stored in `sp`, so the platform performs the one and only sp→px
 * conversion using [effectiveScale]; no token pre-multiplies the factor, which
 * is what would double-apply it.
 */
object OeType {

    private val specs: Map<OeTypeRole, OeTypeSpec> = mapOf(
        OeTypeRole.Brand to OeTypeSpec(24f, 30f, FontWeight.SemiBold),
        OeTypeRole.PageTitle to OeTypeSpec(30f, 36f, FontWeight.SemiBold),
        OeTypeRole.Section to OeTypeSpec(17f, 22f, FontWeight.Medium),
        OeTypeRole.Body to OeTypeSpec(14f, 20f, FontWeight.Normal),
        OeTypeRole.BodyStrong to OeTypeSpec(14f, 20f, FontWeight.SemiBold),
        OeTypeRole.Caption to OeTypeSpec(12f, 16f, FontWeight.Normal),
        OeTypeRole.NavLabel to OeTypeSpec(10f, 14.5f, FontWeight.Medium),
        OeTypeRole.Mono to OeTypeSpec(13f, 18f, FontWeight.Normal),
        OeTypeRole.Meta to OeTypeSpec(13f, 18f, FontWeight.Normal),
    )

    fun spec(role: OeTypeRole): OeTypeSpec = specs.getValue(role)

    /** Pure mapping used outside composition (tests, Material typography). */
    fun styleStatic(role: OeTypeRole, baseFamily: FontFamily? = null): TextStyle {
        val spec = spec(role)
        return TextStyle(
            fontFamily = when {
                role == OeTypeRole.Mono -> FontFamily.Monospace
                baseFamily != null -> baseFamily
                else -> FontFamily.Default
            },
            fontSize = spec.sizeSp.sp,
            fontWeight = spec.weight,
            lineHeight = spec.lineHeightSp.sp,
        )
    }

    /** Bridge for Material3 consumers; Oe3 primitives read the Compose style directly. */
    fun materialTypography(baseFamily: FontFamily? = null): Typography = Typography(
        displaySmall = styleStatic(OeTypeRole.Brand, baseFamily),
        headlineMedium = styleStatic(OeTypeRole.PageTitle, baseFamily),
        titleMedium = styleStatic(OeTypeRole.Section, baseFamily),
        titleSmall = styleStatic(OeTypeRole.BodyStrong, baseFamily),
        bodyLarge = styleStatic(OeTypeRole.Body, baseFamily),
        bodyMedium = styleStatic(OeTypeRole.Body, baseFamily),
        bodySmall = styleStatic(OeTypeRole.Caption, baseFamily),
        labelMedium = styleStatic(OeTypeRole.Caption, baseFamily),
        labelSmall = styleStatic(OeTypeRole.NavLabel, baseFamily),
    )

    // --- One scale seam: system font scale × in-app override, applied once ---

    const val SCALE_MIN = 0.85f
    const val SCALE_MAX = 1.30f

    fun clampScale(factor: Float): Float = when {
        !factor.isFinite() || factor <= 0f -> 1f
        else -> factor.coerceIn(SCALE_MIN, SCALE_MAX)
    }

    fun effectiveScale(systemFontScale: Float, appScale: Float = 1f): Float {
        val system = if (systemFontScale.isFinite() && systemFontScale > 0f) systemFontScale else 1f
        return system * clampScale(appScale)
    }

    fun density(base: Density, appScale: Float): Density =
        Density(base.density, effectiveScale(base.fontScale, appScale))
}
