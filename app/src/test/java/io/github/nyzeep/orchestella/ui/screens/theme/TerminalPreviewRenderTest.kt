package io.github.nyzeep.orchestella.ui.screens.theme

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.ModernTerminalView
import io.github.nyzeep.orchestella.terminal.TerminalScreenModel
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

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
        view.appendOutput(shell.render(theme))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))

        val snapshot = model.displaySnapshot()
        val colours = snapshot.spans.map { it.effectiveForegroundColor }.distinct()
        assertTrue("16-colour strip lost in session mode: $colours", colours.size >= 16)
        assertTrue("prompt missing from the session transcript", snapshot.text().contains("$"))
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
