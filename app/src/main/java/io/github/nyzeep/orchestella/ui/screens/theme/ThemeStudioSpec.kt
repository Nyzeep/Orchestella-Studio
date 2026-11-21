package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.ui.unit.dp
import io.github.nyzeep.orchestella.ui.theme.OeTextScale
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3GlassLevel
import io.github.nyzeep.orchestella.ui.theme.SurfaceOpacity
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/**
 * Structural order of the Theme Studio page (spec §3.3). Kept as data so the
 * build fails if a future edit reorders a section silently.
 */
object ThemeStudioSpec {

    val sectionOrder: List<ThemeStudioSection> = ThemeStudioSection.entries

    /** Full home preview geometry from the ticket. */
    val previewWidth = 168.dp
    val previewCorner = 28.dp
    const val previewAspectRatio = 9f / 19.5f

    /** Inline option thumbnail: 43.6 x 30, 8dp corners. */
    val thumbnailWidth = 44.dp
    val thumbnailHeight = 30.dp
    val thumbnailCorner = 8.dp

    /** Every option row must clear the 48dp interactive floor. */
    val optionRowMinHeight = 48.dp

    /** The preview terminal keeps the real 80x24 model but scales it down. */
    const val terminalColumns = 80
    const val terminalRows = 24
    val terminalHeight = 200.dp
    const val terminalHistoryLimit = 20

    val cornerOptions = listOf(8, 12, 16, 20, 24)

    val textScaleRange = OeTextScale.MIN_PERCENT..OeTextScale.MAX_PERCENT
    const val textScaleSteps = 8

    val surfaceOpacityRange = SurfaceOpacity.MIN_PERCENT..SurfaceOpacity.MAX_PERCENT
    const val surfaceOpacitySteps = 44

    val terminalFontRange = ThemeStore.MIN_TERMINAL_FONT_SP..ThemeStore.MAX_TERMINAL_FONT_SP
    const val terminalFontSteps = 11
    val terminalLetterSpacingMax = 0.20f
    val terminalLineSpacingMax = 8f

    /** The eight accent choices the legacy appearance page offered. */
    val accentSwatches: List<Int> = listOf(
        0xFFBD93F9.toInt(), 0xFF8BE9FD.toInt(), 0xFFFF79C6.toInt(), 0xFF50FA7B.toInt(),
        0xFF61AFEF.toInt(), 0xFFFE8019.toInt(), 0xFFF1FA8C.toInt(), 0xFFFF5555.toInt(),
    )

    /** The compact eight-colour board used by every custom-slot role. */
    val customSlotSwatches: List<Int> = listOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF0D0E11.toInt(), 0xFF2E323B.toInt(),
        0xFF8E929B.toInt(), 0xFFD62828.toInt(), 0xFF22C55E.toInt(), 0xFF0047AB.toInt(),
    )

    /** UI-14 split the single-form mascot into a light/dark pair: 41 presets became 42. */
    val expectedPresetCount = 42
}

/**
 * Page sections in reading order (v4 §2.2). The root page is the entry list:
 * seven named entries — four of them push a dedicated page, three stay inline —
 * with the kept legacy capabilities folded into one 「更多」 group. The test
 * pins this exact sequence.
 */
enum class ThemeStudioSection(val key: String) {
    HomePreview("home-preview"),
    Presets("presets"),
    Mode("mode"),
    Accent("accent"),
    Corner("corner"),
    TextScale("text-scale"),
    Background("background"),
    Terminal("terminal"),
    More("more"),
}

enum class PureBlackOption(val labelKey: String) {
    Standard("standard"),
    PureBlack("pure-black"),
}

enum class NavStyleOption(val key: String) {
    Floating("floating"),
    Fixed("fixed"),
}

enum class GlassOption(val key: String, val level: Oe3GlassLevel) {
    None("none", Oe3GlassLevel.None),
    Standard("standard", Oe3GlassLevel.Standard),
    Ultra("ultra", Oe3GlassLevel.Ultra),
}

enum class FontChoice(val key: String) {
    System("system"),
    Mono("mono"),
    Imported("imported"),
}

/**
 * User intent from the screen. The screen never writes a preference directly;
 * [ThemeStudioController] maps each event onto the existing ThemeStore setters.
 */
sealed interface ThemeStudioEvent {
    data class SelectTheme(val id: String) : ThemeStudioEvent
    data class SelectMode(val mode: ThemeStore.Mode) : ThemeStudioEvent
    data class SetPureBlack(val enabled: Boolean) : ThemeStudioEvent
    data class SetNavStyle(val option: NavStyleOption) : ThemeStudioEvent
    data class SetGlass(val option: GlassOption) : ThemeStudioEvent
    data class SetCornerDp(val dp: Int) : ThemeStudioEvent
    data class SetTextScaleMode(val mode: io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode) :
        ThemeStudioEvent

    data class SetTextScalePercent(val percent: Int) : ThemeStudioEvent
    data class SetSurfaceOpacity(val percent: Int) : ThemeStudioEvent
    data class SetFontChoice(val choice: FontChoice) : ThemeStudioEvent
    data class SetImportedFontLabel(val label: String?) : ThemeStudioEvent
    data class SetCustomAccent(val argb: Int?) : ThemeStudioEvent
    data class SaveCustomSlot(val index: Int, val slot: io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot, val apply: Boolean) :
        ThemeStudioEvent

    data class SetTerminalFontSizeSp(val sp: Int) : ThemeStudioEvent
    data class SetTerminalLetterSpacing(val em: Float) : ThemeStudioEvent
    data class SetTerminalLineSpacing(val px: Float) : ThemeStudioEvent
    data class SetTerminalScrimAlpha(val alpha: Int) : ThemeStudioEvent
    data class SetCursorBlink(val enabled: Boolean) : ThemeStudioEvent
    data object ClearBackground : ThemeStudioEvent
    data object ClearTerminalBackground : ThemeStudioEvent
}
