package io.github.nyzeep.orchestella.ui.screens.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ModernTerminalView
import io.github.nyzeep.orchestella.terminal.TerminalScreenModel
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Theme Studio preview feeds a [FakeTerminalShell] transcript into the real
 * [ModernTerminalView] session model. These tests lock the two things the user
 * sees: ANSI colour survives the ingestion and a broken background URI is a
 * clean fallback rather than a crash.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h9000dp-mdpi")
class TerminalPreviewRenderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun themeCommandKeepsSixteenAnsiColoursInSessionMode() {
        val theme = ThemeCatalog.constructivistWorkstation
        applyTerminalPalette(theme)
        val shell = FakeTerminalShell()
        shell.submit("theme", theme)

        val view = ModernTerminalView(context)
        val model = TerminalScreenModel(80, 24)
        view.setSessionScreen(model)
        // The preview owns this model, so it writes through the session entry point;
        // appendOutput is a repaint-only tick on session screens (live PTY contract).
        view.appendSessionOutput(shell.render(theme))

        val snapshot = model.displaySnapshot()
        val colours = snapshot.spans.map { it.effectiveForegroundColor }.distinct()
        assertTrue("16-colour strip lost in session mode: $colours", colours.size >= 16)
        assertTrue(
            "every ANSI slot must survive the parser, not just 16 arbitrary colours: $colours",
            colours.containsAll(theme.ansi.toSet()),
        )
        assertTrue("prompt missing from the session transcript", snapshot.text().contains("$"))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun readableBackgroundUriDecodesTheImageAfterProbingItsDimensions() {
        val file = File.createTempFile("terminal-background", ".png", context.cacheDir)
        val fixture = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        try {
            fixture.eraseColor(Color.MAGENTA)
            file.outputStream().use { stream ->
                assertTrue(fixture.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }

            val decoded = requireNotNull(decodeTerminalBackground(context, Uri.fromFile(file).toString()))
            try {
                assertEquals(8, decoded.width)
                assertEquals(4, decoded.height)
                assertEquals(Color.MAGENTA, decoded.getPixel(4, 2))
            } finally {
                decoded.recycle()
            }
        } finally {
            fixture.recycle()
            file.delete()
        }
    }

    @Test
    fun unreadableBackgroundUriDecodesToNullInsteadOfThrowing() {
        // A missing file is what a revoked/deleted grant looks like at the stream layer.
        assertNull(
            decodeTerminalBackground(context, "file:///nonexistent/terminal-background.png"),
        )
    }

    @Test
    fun emptyBackgroundUriIsTreatedAsUnreadable() {
        assertNull(decodeTerminalBackground(context, ""))
    }
}
