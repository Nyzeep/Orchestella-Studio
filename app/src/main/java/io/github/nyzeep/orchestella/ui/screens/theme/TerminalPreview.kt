package io.github.nyzeep.orchestella.ui.screens.theme

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nyzeep.orchestella.ModernTerminalView
import io.github.nyzeep.orchestella.ui.design.tokens.OeFonts
import io.github.nyzeep.orchestella.R
import io.github.nyzeep.orchestella.terminal.TerminalScreenModel
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3Button
import io.github.nyzeep.orchestella.ui.oe3.components.Oe3ButtonStyle
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Sizes
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3TextRole
import io.github.nyzeep.orchestella.ui.oe3.tokens.Oe3Typography
import io.github.nyzeep.orchestella.ui.oe3.theme.Oe3ThemeValues
import io.github.nyzeep.orchestella.ui.theme.AppTheme
import io.github.nyzeep.orchestella.ui.theme.ThemeColors

/**
 * Live terminal preview. [renderAndroidView] false is the zero-device path:
 * Robolectric cannot draw the custom View reliably, so screenshots use the
 * pure-Compose palette sketch instead of pretending the View rendered.
 *
 * The fake shell never touches a PTY or VM; the input row owns history,
 * IME submit and the five local commands.
 */
@Composable
fun TerminalPreview(
    theme: AppTheme,
    fontSizeSp: Int,
    letterSpacingEm: Float,
    lineSpacingPx: Float,
    cursorBlink: Boolean,
    modifier: Modifier = Modifier,
    renderAndroidView: Boolean = true,
    backgroundUri: String? = null,
    // Transparent by default: a caller that supplies an image but forgets the scrim
    // should still see that image, never a solid terminal color painted over it.
    scrimColor: Color = Color.Transparent,
) {
    val session = remember { FakeTerminalShell() }
    var input by remember { mutableStateOf("") }
    var historyCursor by remember { mutableIntStateOf(-1) }
    var replayKey by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<ModernTerminalView?>(null) }
    // Decoded once per URI, off the main thread; a revoked grant falls back to
    // the solid terminal color and only shows an in-preview hint.
    val background by rememberTerminalBackground(backgroundUri)

    LaunchedEffect(theme, renderAndroidView) {
        // The parser resolves ANSI through this static palette; refresh it
        // before the sample transcript is replayed so no old span survives.
        // The pure-Compose sketch reads AppTheme directly and must not leak a
        // global parser palette into other tests/screens.
        if (renderAndroidView) applyTerminalPalette(theme)
        // A command's plain-text output (notably `theme`) must not outlive the
        // palette it described; the preview restarts from a fresh banner.
        session.resetForTheme()
        // Opens on a prompt, a typed command and its colour strip, so the very
        // first screen already demonstrates what the sliders change.
        session.seedSample(theme)
    }

    LaunchedEffect(view, theme, fontSizeSp, letterSpacingEm, lineSpacingPx, cursorBlink, replayKey) {
        val terminal = view ?: return@LaunchedEffect
        terminal.applyTheme(theme, fontSizeSp, letterSpacingEm, lineSpacingPx)
        terminal.setCursorBlinkEnabled(cursorBlink)
        // A fresh model drops scrollback from the previous replay, so changing
        // the theme cannot stack a second copy of the sample output above it.
        terminal.setSessionScreen(
            TerminalScreenModel(ThemeStudioSpec.terminalColumns, ThemeStudioSpec.terminalRows),
        )
        // The preview owns this model, so the text must be written into it. appendOutput
        // is a render tick only: the live PTY path shares a model the session pool has
        // already appended to, and appending there again would duplicate its output.
        terminal.appendSessionOutput(session.render(theme))
    }

    fun submit() {
        val line = input
        if (line.isBlank()) return
        session.submit(line, theme)
        input = ""
        historyCursor = -1
        replayKey++
    }

    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ThemeStudioSpec.terminalHeight)
                .testTag("theme-terminal:preview")
                .clip(RoundedCornerShape(12.dp))
                .background(Color(theme.terminalBackground)),
        ) {
            if (renderAndroidView) {
                AndroidTerminal(
                    theme = theme,
                    fontSizeSp = fontSizeSp,
                    letterSpacingEm = letterSpacingEm,
                    lineSpacingPx = lineSpacingPx,
                    cursorBlink = cursorBlink,
                    background = background,
                    scrimColor = scrimColor,
                    onCreated = { view = it },
                )
                if (background is TerminalBackgroundState.Failed) {
                    Text(
                        text = stringResource(R.string.ui3_theme_terminal_background_failed),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(8.dp)
                            .testTag("theme-terminal:bg-failed"),
                        style = Oe3Typography.style(Oe3TextRole.Caption),
                        color = Color(theme.terminalForeground),
                    )
                }
            } else {
                TerminalPaletteSketch(
                    theme = theme,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AnsiPaletteStrip(theme)
        Spacer(Modifier.height(8.dp))
        TerminalInputRow(
            value = input,
            onValueChange = { input = it },
            onSubmit = ::submit,
            onHistoryUp = {
                val history = session.historySnapshot()
                if (history.isNotEmpty()) {
                    historyCursor = if (historyCursor < 0) history.lastIndex
                    else (historyCursor - 1).coerceAtLeast(0)
                    input = history[historyCursor]
                }
            },
            onHistoryDown = {
                val history = session.historySnapshot()
                if (historyCursor >= 0) {
                    historyCursor++
                    if (historyCursor >= history.size) {
                        historyCursor = -1
                        input = ""
                    } else {
                        input = history[historyCursor]
                    }
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.ui3_theme_terminal_commands),
            style = TextStyle(fontFamily = OeFonts.JetBrainsMono, fontSize = 10.sp, lineHeight = 13.sp),
            color = Color(Oe3ThemeValues.colors.onSurfaceVariant),
        )
    }
}

@Composable
private fun AndroidTerminal(
    theme: AppTheme,
    fontSizeSp: Int,
    letterSpacingEm: Float,
    lineSpacingPx: Float,
    cursorBlink: Boolean,
    background: TerminalBackgroundState,
    scrimColor: Color,
    onCreated: (ModernTerminalView) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Which bitmap the native view already holds. A plain holder, not Compose
    // state: re-applying the same drawable on every recomposition is wasted work.
    val applied = remember { AppliedTerminalBackground() }
    val scrimArgb = scrimColor.toArgb()
    // The preview is a 1:1 crop of the real 80x24 view, not a fit-to-box
    // miniature: scaling the whole grid down would cancel every font-size,
    // letter-spacing and line-spacing change the sliders make. The view is
    // laid out at its natural size and clipped by the 200dp window, so a
    // larger font visibly shows fewer rows and columns, exactly like zooming
    // a terminal instead of shrinking the UI around it.
    val systemFontScale = configuration.fontScale
        .takeIf { it.isFinite() && it > 0f } ?: 1f
    val fontDp = fontSizeSp * systemFontScale
    val charWidth = (fontDp * 0.62f) + (fontDp * letterSpacingEm)
    val lineHeight = (fontDp * 1.25f) + (lineSpacingPx / density.density)
    val naturalWidth = (charWidth * ThemeStudioSpec.terminalColumns + 12f).dp
    val naturalHeight = (lineHeight * ThemeStudioSpec.terminalRows + 12f).dp
    // The 80x24 grid is taller than the 200dp window, so the view itself must be
    // clamped to the window: ModernTerminalView.updateTerminalSize() then rebuilds
    // the screen model at the rows that actually fit. Without the clamp the native
    // ScrollView follows the bottom of the 24-row transcript and the window shows
    // the blank rows below the banner — i.e. a black box even though the model has
    // text. A bigger font still shows fewer rows, which is the intended zoom.
    val previewHeight = if (naturalHeight > ThemeStudioSpec.terminalHeight) {
        ThemeStudioSpec.terminalHeight
    } else {
        naturalHeight
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
        AndroidView(
            factory = { ctx ->
                ModernTerminalView(ctx).apply {
                    setTerminalInputEnabled(false)
                    setCursorBlinkEnabled(cursorBlink)
                    applyTheme(theme, fontSizeSp, letterSpacingEm, lineSpacingPx)
                    applied.bitmap = null
                    onCreated(this)
                }
            },
            update = { terminal ->
                terminal.applyTheme(theme, fontSizeSp, letterSpacingEm, lineSpacingPx)
                terminal.setCursorBlinkEnabled(cursorBlink)
                val bitmap = (background as? TerminalBackgroundState.Loaded)?.bitmap
                when {
                    // Revoked grant / corrupt file: keep the solid terminal color.
                    bitmap == null -> if (applied.bitmap != null) {
                        terminal.clearTerminalBackgroundImage(theme.terminalBackground)
                        applied.bitmap = null
                    }
                    applied.bitmap !== bitmap -> {
                        terminal.setTerminalBackgroundImage(bitmap, scrimArgb)
                        applied.bitmap = bitmap
                    }
                }
                terminal.setTerminalScrimColor(scrimArgb)
            },
            modifier = Modifier
                // Anchor the oversized terminal grid before the viewport clips the right edge.
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .requiredWidth(naturalWidth)
                .requiredHeight(previewHeight),
        )
    }
}

/** Bitmap currently installed on the native view; identity check avoids redundant redraws. */
private class AppliedTerminalBackground {
    var bitmap: Bitmap? = null
}

@Composable
private fun TerminalInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
) {
    val colors = Oe3ThemeValues.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(colors.surfaceHigh))
            .padding(start = 12.dp, end = 6.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$",
            style = TextStyle(fontFamily = OeFonts.JetBrainsMono, fontSize = 13.sp, lineHeight = 18.sp),
            color = Color(colors.primary),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .testTag("theme-terminal:input")
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter -> {
                            onSubmit()
                            true
                        }
                        Key.DirectionUp -> {
                            onHistoryUp()
                            true
                        }
                        Key.DirectionDown -> {
                            onHistoryDown()
                            true
                        }
                        else -> false
                    }
                },
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = OeFonts.JetBrainsMono,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color(colors.onSurface),
            ),
            cursorBrush = SolidColor(Color(colors.primary)),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .height(Oe3Sizes.minTouchTarget)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ui3_theme_terminal_input_hint),
                            style = TextStyle(
                                fontFamily = OeFonts.JetBrainsMono,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                            ),
                            color = Color(colors.onSurfaceVariant),
                        )
                    }
                    inner()
                }
            },
        )
        Oe3Button(
            text = stringResource(R.string.ui3_theme_terminal_run),
            onClick = onSubmit,
            style = Oe3ButtonStyle.Tonal,
            modifier = Modifier
                .widthIn(min = 64.dp)
                .testTag("theme-terminal:send"),
        )
    }
}

/** The pure-Compose terminal sketch used in screenshots and zero-device runs. */
@Composable
fun TerminalPaletteSketch(theme: AppTheme, modifier: Modifier = Modifier) {
    val palette = terminalPalette(theme)
    val description = stringResource(R.string.ui3_theme_terminal_sketch_desc)
    Column(
        modifier = modifier
            .testTag("theme-terminal:sketch")
            .semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color(palette.ansi[2]))) { append("user@orchestella") }
                withStyle(SpanStyle(color = Color(palette.foreground))) { append(":") }
                withStyle(SpanStyle(color = Color(palette.ansi[4]))) { append("~") }
                withStyle(SpanStyle(color = Color(palette.foreground))) { append("$ theme") }
            },
            style = TextStyle(fontFamily = OeFonts.JetBrainsMono, fontSize = 9.sp, lineHeight = 12.sp),
        )
        Text(
            text = theme.displayName,
            style = TextStyle(
                fontFamily = OeFonts.JetBrainsMono,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color(palette.foreground),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color(palette.ansi[2]))) { append("✓ ") }
                withStyle(SpanStyle(color = Color(palette.foreground))) { append("80×24 · local fake shell") }
            },
            style = TextStyle(fontFamily = OeFonts.JetBrainsMono, fontSize = 8.sp, lineHeight = 11.sp),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color(palette.ansi[2]))) { append("user@orchestella") }
                withStyle(SpanStyle(color = Color(palette.foreground))) { append(":~$ ") }
                withStyle(SpanStyle(color = Color(palette.ansi[6]))) { append("ls") }
            },
            style = TextStyle(fontFamily = OeFonts.JetBrainsMono, fontSize = 8.sp, lineHeight = 11.sp),
        )
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color(palette.ansi[4]))) { append("Desktop  Documents  Downloads  Projects") }
            },
            style = TextStyle(fontFamily = OeFonts.JetBrainsMono, fontSize = 8.sp, lineHeight = 11.sp),
        )
        Spacer(Modifier.weight(1f))
        palette.ansi.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth().height(12.dp)) {
                row.forEach { argb ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(argb)),
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.ui3_theme_terminal_sketch_caption),
            style = TextStyle(fontFamily = OeFonts.JetBrainsMono, fontSize = 8.sp, lineHeight = 10.sp),
            color = Color(palette.foreground).copy(alpha = 0.72f),
        )
    }
}

@Composable
fun AnsiPaletteStrip(theme: AppTheme, modifier: Modifier = Modifier) {
    val palette = terminalPalette(theme)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        palette.ansi.chunked(8).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEachIndexed { columnIndex, argb ->
                    val index = rowIndex * 8 + columnIndex
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(argb))
                            .semantics { contentDescription = ansiLabel(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$index",
                            style = TextStyle(
                                fontFamily = OeFonts.JetBrainsMono,
                                fontSize = 8.sp,
                                lineHeight = 10.sp,
                            ),
                            color = Color(ThemeColors.onColor(argb)).copy(alpha = 0.78f),
                        )
                    }
                }
            }
        }
    }
}

/** Two large read-only swatches: terminal background and foreground. */
@Composable
fun TerminalBaseSwatches(theme: AppTheme, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalBaseSwatch(
            label = stringResource(R.string.ui3_theme_terminal_bg),
            argb = theme.terminalBackground,
            modifier = Modifier.weight(1f),
        )
        TerminalBaseSwatch(
            label = stringResource(R.string.ui3_theme_terminal_fg),
            argb = theme.terminalForeground,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TerminalBaseSwatch(label: String, argb: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(argb)),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "$label  #${String.format("%06X", argb and 0x00FFFFFF)}",
            style = TextStyle(fontFamily = OeFonts.JetBrainsMono, fontSize = 10.sp, lineHeight = 13.sp),
            color = Color(Oe3ThemeValues.colors.onSurfaceVariant),
        )
    }
}
