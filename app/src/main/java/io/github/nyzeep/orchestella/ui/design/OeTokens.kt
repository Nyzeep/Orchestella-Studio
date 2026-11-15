package io.github.nyzeep.orchestella.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Oe v2 geometry. The single Kotlin source for shape / space / size / motion,
 * mirrored by `res/values/oe_design_tokens.xml` for the XML pages — one scale,
 * two consumers (same contract as [io.github.nyzeep.orchestella.ui.compose.OeSpacing]).
 *
 * The user's "button corner" preference only changes [OeShape.control] and
 * below. Surface radii ([hero], [card], [block]) are layout, not preference:
 * letting a 16dp control setting stretch a 24dp card is what made the previous
 * UI read as a stack of unrelated boxes.
 */
object OeShape {
    val hero = 32.dp
    val card = 24.dp
    val block = 20.dp
    val control = 16.dp
    val badge = 14.dp

    /** Squircle stand-in for the 96dp empty-state badge (no superellipse on Android). */
    val badgeHero = 32.dp

    /** Fully rounded capsule: segmented tracks, chips, pills. */
    val capsule: Shape = RoundedCornerShape(percent = 50)

    /** Blank shape used by the 2dp segmented inset. */
    val none: Shape = RoundedCornerShape(0.dp)

    /**
     * Maps the stored button-corner preference (8/12/16) onto a control radius.
     * Values outside the supported band are clamped instead of trusted, so a
     * hand-edited preference file cannot turn a button into a circle or a square.
     */
    fun controlRadius(preferred: Dp): Dp = preferred.coerceIn(8.dp, 16.dp)

    fun controlShape(preferred: Dp): Shape = RoundedCornerShape(controlRadius(preferred))
}

/** Vertical / horizontal rhythm. Values mirror `oe_space_*` in XML. */
object OeSpace {
    val page = 16.dp
    val card = 16.dp
    val gap = 12.dp
    val group = 28.dp

    /** Space above a group title when the title is rendered by [OeSection]. */
    val headerTop = 24.dp
    val headerBottom = 8.dp

    /** Divider start inset measured from the card edge: 16 row pad + 24 icon + 16 gap. */
    val rowDividerInset = 56.dp

    val hairline = 1.dp
}

/** Component geometry. Values mirror `oe_*` in XML where a counterpart exists. */
object OeSize {
    val row = 56.dp
    val rowDense = 48.dp
    val topBar = 56.dp
    val dock = 62.dp

    /** Bottom content padding that lets the last row scroll clear of the floating dock. */
    val dockClearance = 96.dp

    val icon = 24.dp
    val iconSmall = 20.dp
    val iconLarge = 40.dp
    val tap = 44.dp
    val chevron = 20.dp

    val switchTrackWidth = 52.dp
    val switchTrackHeight = 32.dp
    val switchThumb = 20.dp
    val switchInset = 6.dp

    val segmented = 40.dp
    val buttonPrimary = 48.dp
    val buttonSecondary = 44.dp
    val buttonGhost = 36.dp

    val emptyBadge = 96.dp
    val sparklineWidth = 40.dp
    val sparklineHeight = 24.dp
}

/** Motion durations in milliseconds. Mirrors `oe_motion_*` where a counterpart exists. */
object OeMotion {
    const val press = 120
    const val switch = 260
    const val sheetIn = 250
    const val sheetOut = 150
    const val stagger = 40
    const val staggerMax = 5
}

/** The five text roles Oe v2 allows. Anything else is a page-specific style. */
enum class OeTextRole {
    PageTitle,
    Section,
    Row,
    Body,
    Caption,
    Label,
    EmptyTitle,
    StatValue,
}

/**
 * Type scale. Sizes are in `sp`, which means the platform already applies the
 * user's system font scale exactly once. [OeTextScale] is the *additional*
 * in-app preference reserved for the appearance settings (ticket 05); it is
 * 1.0 while the app follows the system.
 *
 * Numbers use tabular figures via `fontFeatureSettings = "tnum"` (Compose does
 * not expose a `FontFeature` type on this BOM version — the TextStyle field is
 * the supported entry point).
 */
object OeType {
    const val PAGE_TITLE_SP = 28f
    const val SECTION_SP = 15f
    const val ROW_SP = 15f
    const val BODY_SP = 13f
    const val CAPTION_SP = 11f
    const val LABEL_SP = 12f
    const val EMPTY_TITLE_SP = 17f
    const val STAT_VALUE_SP = 28f

    private data class Spec(val sizeSp: Float, val weight: FontWeight, val lineHeightSp: Float)

    private fun spec(role: OeTextRole): Spec = when (role) {
        OeTextRole.PageTitle -> Spec(PAGE_TITLE_SP, FontWeight.SemiBold, 34f)
        OeTextRole.Section -> Spec(SECTION_SP, FontWeight.SemiBold, 20f)
        OeTextRole.Row -> Spec(ROW_SP, FontWeight.Medium, 20f)
        OeTextRole.Body -> Spec(BODY_SP, FontWeight.Normal, 18f)
        OeTextRole.Caption -> Spec(CAPTION_SP, FontWeight.Normal, 15f)
        OeTextRole.Label -> Spec(LABEL_SP, FontWeight.Medium, 16f)
        OeTextRole.EmptyTitle -> Spec(EMPTY_TITLE_SP, FontWeight.SemiBold, 22f)
        OeTextRole.StatValue -> Spec(STAT_VALUE_SP, FontWeight.SemiBold, 32f)
    }

    /** Pure style resolver; [scale] is the in-app multiplier, never the system factor. */
    fun style(role: OeTextRole, scale: Float = 1f, tabular: Boolean = false): TextStyle {
        val spec = spec(role)
        val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
        return TextStyle(
            fontSize = (spec.sizeSp * safeScale).sp,
            fontWeight = spec.weight,
            lineHeight = (spec.lineHeightSp * safeScale).sp,
            fontFeatureSettings = if (tabular) "tnum" else null,
        )
    }

    /** Tabular variant of any style. Use for counters, durations, sizes, telemetry. */
    fun numeric(style: TextStyle): TextStyle = style.copy(fontFeatureSettings = "tnum")

    @Composable
    fun pageTitle(): TextStyle = style(OeTextRole.PageTitle, OeTheme.textScale)
    @Composable
    fun section(): TextStyle = style(OeTextRole.Section, OeTheme.textScale)
    @Composable
    fun row(): TextStyle = style(OeTextRole.Row, OeTheme.textScale)
    @Composable
    fun body(): TextStyle = style(OeTextRole.Body, OeTheme.textScale)
    @Composable
    fun caption(): TextStyle = style(OeTextRole.Caption, OeTheme.textScale)
    @Composable
    fun label(): TextStyle = style(OeTextRole.Label, OeTheme.textScale)
    @Composable
    fun emptyTitle(): TextStyle = style(OeTextRole.EmptyTitle, OeTheme.textScale)
    @Composable
    fun statValue(): TextStyle = numeric(style(OeTextRole.StatValue, OeTheme.textScale))
}

/** How the in-app text scale is sourced (ticket 05 wires the picker). */
enum class OeTextScaleMode { FollowSystem, Custom }

/**
 * In-app text scaling. When the app follows the system, the factor stays 1.0
 * because `sp` already scales with `Configuration.fontScale`; multiplying here
 * as well would double-scale every label. Only the explicit 85–130% override
 * produces a factor.
 */
object OeTextScale {
    const val MIN_PERCENT = 85
    const val MAX_PERCENT = 130

    fun effectiveFactor(
        systemFontScale: Float = 1f,
        mode: OeTextScaleMode = OeTextScaleMode.FollowSystem,
        customPercent: Int = 100,
    ): Float = when (mode) {
        OeTextScaleMode.FollowSystem -> 1f
        OeTextScaleMode.Custom -> (customPercent / 100f).coerceIn(
            MIN_PERCENT / 100f,
            MAX_PERCENT / 100f,
        )
    }

    /** Reads the system value once; used by pages that want to show the effective scale. */
    fun systemScale(fontScale: Float): Float = if (fontScale.isFinite() && fontScale > 0f) fontScale else 1f
}

/** Liquid-glass level; filled by ticket 02, declared here so [OeTheme] is stable. */
enum class OeGlassLevel { None, Standard, Ultra }

/**
 * Ambient design state. [cornerDp] is the user preference for controls,
 * [textScale] the in-app multiplier, [glassLevel] the material tier.
 */
@Immutable
data class OeThemeData(
    val cornerDp: Dp = OeShape.control,
    val isDark: Boolean = false,
    val glassLevel: OeGlassLevel = OeGlassLevel.None,
    val textScale: Float = 1f,
)

val LocalOeTheme = staticCompositionLocalOf { OeThemeData() }

/** Composable view over [LocalOeTheme]; components read shape/type through this. */
object OeTheme {
    val data: OeThemeData
        @Composable get() = LocalOeTheme.current

    val cornerDp: Dp
        @Composable get() = LocalOeTheme.current.cornerDp

    val isDark: Boolean
        @Composable get() = LocalOeTheme.current.isDark

    val glassLevel: OeGlassLevel
        @Composable get() = LocalOeTheme.current.glassLevel

    val textScale: Float
        @Composable get() = LocalOeTheme.current.textScale

    /** Control radius after the 8..16 clamp. */
    val controlCorner: Dp
        @Composable get() = OeShape.controlRadius(cornerDp)

    /** Shape for buttons, inputs, segmented tracks. Never for cards. */
    val controlShape: Shape
        @Composable get() = RoundedCornerShape(controlCorner)
}

/**
 * Sparkline normalisation. Kept pure so the JVM test can pin the contract:
 * finite values map into 0..1, a flat series sits at 0.5, and NaN/Infinity
 * never reach the Canvas.
 */
object OeSparkline {
    fun normalize(values: List<Float>): List<Float> {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return emptyList()
        if (finite.size == 1) return listOf(0.5f)
        val min = finite.min()
        val max = finite.max()
        if (max - min < 1e-6f) return List(finite.size) { 0.5f }
        return finite.map { ((it - min) / (max - min)).coerceIn(0f, 1f) }
    }
}
