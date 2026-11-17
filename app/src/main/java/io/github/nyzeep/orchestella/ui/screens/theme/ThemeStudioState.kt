package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.nyzeep.orchestella.SettingsManager
import io.github.nyzeep.orchestella.ui.theme.OeTextScale
import io.github.nyzeep.orchestella.ui.theme.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/**
 * Everything the Theme Studio renders. Persisted fields are re-read from
 * [ThemeStore] after every event. Since UI3-13 the four presentation options
 * (pure black, glass, bottom-bar style and app font family) are real
 * preferences too; only the imported-font label stays page-local, because
 * font import does not exist yet (暂不支持导入字体) and a chosen font file must
 * never be persisted as an unloadable URI.
 */
@Immutable
data class ThemeStudioState(
    val theme: AppTheme,
    val mode: ThemeStore.Mode,
    val pureBlack: Boolean = false,
    val glass: GlassOption = GlassOption.None,
    val navStyle: NavStyleOption = NavStyleOption.Floating,
    val fontChoice: FontChoice = FontChoice.System,
    val importedFontLabel: String? = null,
    val customSlots: List<CustomThemeSlot?> = List(CustomThemeSlot.SLOT_COUNT) { null },
    val customAccent: Int? = null,
    val customText: Int? = null,
    val cornerDp: Int = ThemeStore.DEFAULT_CORNER_RADIUS_DP,
    val textScaleMode: OeTextScaleMode = OeTextScaleMode.FollowSystem,
    val textScalePercent: Int = OeTextScale.DEFAULT_PERCENT,
    val surfaceOpacityPercent: Int = 100,
    val backgroundUri: String? = null,
    val terminalBackgroundUri: String? = null,
    val terminalScrimAlpha: Int = ThemeStore.DEFAULT_TERMINAL_SCRIM_ALPHA,
    val terminalFontSizeSp: Int = 14,
    val terminalLetterSpacingEm: Float = 0f,
    val terminalLineSpacingPx: Float = 0f,
    val cursorBlink: Boolean = true,
) {
    /** App-level text multiplier; 1.0 while following the system. */
    val appTextScale: Float
        get() = OeTextScale.effectiveFactor(textScaleMode, textScalePercent)

    val isFixedBrightness: Boolean
        get() = theme.isFixed || mode == ThemeStore.Mode.FIXED
}

/** Existing product switch, kept behind a port so the controller stays testable. */
interface CursorBlinkPort {
    fun read(): Boolean
    fun write(enabled: Boolean)

    companion object {
        val Default = object : CursorBlinkPort {
            override fun read(): Boolean = true
            override fun write(enabled: Boolean) = Unit
        }
    }
}

/** Existing `terminal_cursor_blink` key; no new preference is introduced. */
class SettingsCursorBlinkPort(private val settings: SettingsManager) : CursorBlinkPort {
    override fun read(): Boolean = settings.isTerminalCursorBlinkEnabled

    override fun write(enabled: Boolean) {
        settings.isTerminalCursorBlinkEnabled = enabled
    }
}

/**
 * Adapter from [ThemeStudioEvent] to the ThemeStore/SettingsManager setters.
 * Every persisted write is a setter on a ThemeStore-owned key — UI3-13 added
 * the four presentation keys to the store; nothing here invents a raw key.
 */
class ThemeStudioController(
    private val store: ThemeStore,
    private val cursorBlink: CursorBlinkPort = CursorBlinkPort.Default,
) {
    /** Page-memory only: a picked font name has no key until font import ships. */
    private var importedFontLabel: String? = null

    fun read(): ThemeStudioState {
        val theme = store.resolve()
        // Pure black is a dark-only override. Carrying it into a light palette
        // would leave the header claiming 纯黑 while the preview correctly
        // ignores it; drop it at read time while the store retains the choice.
        return ThemeStudioState(
            theme = theme,
            mode = store.mode(),
            pureBlack = theme.isDark && store.uiPureBlack(),
            glass = glassOption(store.uiGlassLevel()),
            navStyle = navStyleOption(store.uiNavStyle()),
            fontChoice = fontChoice(store.uiFontChoice()),
            importedFontLabel = importedFontLabel,
            customSlots = store.customSlots(),
            customAccent = store.customAccent(),
            customText = store.customText(),
            cornerDp = store.cornerRadiusDp(),
            textScaleMode = store.textScaleMode(),
            textScalePercent = store.textScalePercent(),
            surfaceOpacityPercent = store.uiSurfaceOpacityPercent(),
            backgroundUri = store.backgroundUri(),
            terminalBackgroundUri = store.terminalBackgroundUri(),
            terminalScrimAlpha = store.terminalScrimAlpha(),
            terminalFontSizeSp = store.terminalFontSizeSp(),
            terminalLetterSpacingEm = store.terminalLetterSpacingEm(),
            terminalLineSpacingPx = store.terminalLineSpacingPx(),
            cursorBlink = cursorBlink.read(),
        )
    }

    /** Applies [event], then returns the store-backed snapshot the UI renders. */
    fun handle(event: ThemeStudioEvent): ThemeStudioState {
        when (event) {
            is ThemeStudioEvent.SelectTheme -> store.setThemeId(event.id)
            is ThemeStudioEvent.SelectMode -> store.setMode(event.mode)
            is ThemeStudioEvent.SetPureBlack -> store.setUiPureBlack(event.enabled)
            is ThemeStudioEvent.SetNavStyle -> store.setUiNavStyle(event.option.key)
            is ThemeStudioEvent.SetGlass -> store.setUiGlassLevel(event.option.key)
            is ThemeStudioEvent.SetCornerDp -> store.setCornerRadiusDp(event.dp)
            is ThemeStudioEvent.SetTextScaleMode -> store.setTextScaleMode(event.mode)
            is ThemeStudioEvent.SetTextScalePercent ->
                store.setTextScale(OeTextScaleMode.Custom, event.percent)
            is ThemeStudioEvent.SetSurfaceOpacity -> store.setUiSurfaceOpacityPercent(event.percent)
            is ThemeStudioEvent.SetFontChoice -> {
                // "Imported" stays inert (暂不支持导入字体): persisting it would
                // save a font the app cannot load, and the store would coerce
                // it back to system anyway; skipping keeps the file untouched.
                if (event.choice != FontChoice.Imported) {
                    store.setUiFontChoice(event.choice.key)
                    importedFontLabel = null
                }
            }
            is ThemeStudioEvent.SetImportedFontLabel -> {
                // Page-memory only; the label never becomes a preference until
                // font import ships with a real key of its own.
                importedFontLabel = event.label
            }
            is ThemeStudioEvent.SetCustomAccent -> store.setCustomAccent(event.argb)
            is ThemeStudioEvent.SaveCustomSlot -> {
                store.setCustomSlot(event.index, event.slot)
                if (event.apply) store.setThemeId("custom:${event.index + 1}")
            }
            is ThemeStudioEvent.SetTerminalFontSizeSp -> store.setTerminalFontSizeSp(event.sp)
            is ThemeStudioEvent.SetTerminalLetterSpacing -> store.setTerminalLetterSpacingEm(event.em)
            is ThemeStudioEvent.SetTerminalLineSpacing -> store.setTerminalLineSpacingPx(event.px)
            is ThemeStudioEvent.SetTerminalScrimAlpha -> store.setTerminalScrimAlpha(event.alpha)
            is ThemeStudioEvent.SetCursorBlink -> cursorBlink.write(event.enabled)
            ThemeStudioEvent.ClearBackground -> store.setBackgroundUri(null)
            ThemeStudioEvent.ClearTerminalBackground -> store.setTerminalBackgroundUri(null)
        }
        return read()
    }

    /** Enum ↔ key mapping over the store's normalized values (unknown falls back to the default). */
    private fun glassOption(level: String): GlassOption =
        GlassOption.entries.firstOrNull { it.key == level } ?: GlassOption.None

    private fun navStyleOption(style: String): NavStyleOption =
        NavStyleOption.entries.firstOrNull { it.key == style } ?: NavStyleOption.Floating

    private fun fontChoice(choice: String): FontChoice =
        FontChoice.entries.firstOrNull { it.key == choice } ?: FontChoice.System
}

/** Hoisted state + event sink for the screen; the host only supplies the store. */
@Immutable
data class ThemeStudioHandle(
    val state: ThemeStudioState,
    val onEvent: (ThemeStudioEvent) -> Unit,
)

/**
 * Compose binding for [ThemeStudioController]. It also listens to the existing
 * theme preference file, so a mode/preset write from another page repaints the
 * preview instead of leaving a stale screen behind.
 */
@Composable
fun rememberThemeStudioHandle(
    store: ThemeStore,
    cursorBlink: CursorBlinkPort? = null,
): ThemeStudioHandle {
    val context = LocalContext.current
    val resolvedPort = cursorBlink ?: remember(store, context) {
        SettingsCursorBlinkPort(SettingsManager(context.applicationContext))
    }
    val controller = remember(store, resolvedPort) {
        ThemeStudioController(store, resolvedPort)
    }
    val state = remember(controller) { mutableStateOf(controller.read()) }
    DisposableEffect(store) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            state.value = controller.read()
        }
        store.addOnThemeChangeListener(listener)
        onDispose { store.removeOnThemeChangeListener(listener) }
    }
    return ThemeStudioHandle(
        state = state.value,
        onEvent = { event -> state.value = controller.handle(event) },
    )
}
