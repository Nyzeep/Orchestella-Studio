package io.github.nyzeep.orchestella.ui.screens.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.nyzeep.orchestella.SettingsManager
import io.github.nyzeep.orchestella.ui.design.OeTextScale
import io.github.nyzeep.orchestella.ui.design.OeTextScaleMode
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.CustomThemeSlot
import io.github.nyzeep.orchestella.ui.theme.ThemeStore

/**
 * Everything the Theme Studio renders. Persisted fields are re-read from
 * [ThemeStore] after every event; the four presentation-only fields have no
 * existing preference key yet (pure black, glass, bottom-bar style and app
 * font family), so they stay local to this page until the shell owns them.
 * The ticket forbids inventing new keys, and this is the only honest way to
 * show those controls without pretending they survive a restart.
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
 * Adapter from [ThemeStudioEvent] to the existing ThemeStore/SettingsManager
 * setters. It never creates a preference key: every persisted write below is
 * an existing setter on an existing key.
 */
class ThemeStudioController(
    private val store: ThemeStore,
    private val cursorBlink: CursorBlinkPort = CursorBlinkPort.Default,
) {
    private var pureBlack = false
    private var glass = GlassOption.None
    private var navStyle = NavStyleOption.Floating
    private var fontChoice = FontChoice.System
    private var importedFontLabel: String? = null

    fun read(): ThemeStudioState {
        val theme = store.resolve()
        // Pure black is a dark-only override. Carrying it into a light palette
        // would leave the header claiming 纯黑 while the preview correctly
        // ignores it; drop the local flag when brightness no longer permits it.
        if (!theme.isDark) pureBlack = false
        return ThemeStudioState(
            theme = theme,
            mode = store.mode(),
            pureBlack = pureBlack,
            glass = glass,
            navStyle = navStyle,
            fontChoice = fontChoice,
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
            is ThemeStudioEvent.SetPureBlack -> pureBlack = event.enabled
            is ThemeStudioEvent.SetNavStyle -> navStyle = event.option
            is ThemeStudioEvent.SetGlass -> glass = event.option
            is ThemeStudioEvent.SetCornerDp -> store.setCornerRadiusDp(event.dp)
            is ThemeStudioEvent.SetTextScaleMode -> store.setTextScaleMode(event.mode)
            is ThemeStudioEvent.SetTextScalePercent ->
                store.setTextScale(OeTextScaleMode.Custom, event.percent)
            is ThemeStudioEvent.SetSurfaceOpacity -> store.setUiSurfaceOpacityPercent(event.percent)
            is ThemeStudioEvent.SetFontChoice -> {
                fontChoice = event.choice
                if (event.choice != FontChoice.Imported) importedFontLabel = null
            }
            is ThemeStudioEvent.SetImportedFontLabel -> {
                importedFontLabel = event.label
                if (event.label != null) fontChoice = FontChoice.Imported
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
