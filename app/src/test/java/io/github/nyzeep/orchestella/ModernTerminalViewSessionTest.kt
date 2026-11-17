package io.github.nyzeep.orchestella

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.terminal.TerminalScreenModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Two different writers share the session screen, and the view must keep them apart:
 *
 * - The Theme Studio preview owns its model, so [ModernTerminalView.appendSessionOutput]
 *   writes the text into it (the old `if (!sessionScreen)` guard in the flush task made
 *   `appendOutput` a no-op there, which is why the preview rendered an empty block).
 * - The live shell shares its model with the session pool's pump, which appends every
 *   chunk itself; [ModernTerminalView.appendOutput] is only a repaint request there and
 *   must never append a second time.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h9000dp-mdpi")
class ModernTerminalViewSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idleMainLooper() {
        // flushPendingOutput is throttled by FLUSH_INTERVAL_MS (32ms).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
    }

    @Test
    fun appendSessionOutputWritesIntoTheSessionModel() {
        val view = ModernTerminalView(context)
        val model = TerminalScreenModel(80, 24)
        view.setSessionScreen(model)
        view.appendSessionOutput("hello session\r\nsecond line")

        val text = model.displaySnapshot().text()
        assertTrue("session model dropped the appended chunk: <$text>", text.contains("hello session"))
        assertTrue("session model dropped the second chunk line: <$text>", text.contains("second line"))
    }

    /**
     * The live terminal shares its model with [PersistentTerminalSessionPool], which
     * appends every PTY chunk itself; TerminalActivity then forwards the chunk to
     * [ModernTerminalView.appendOutput] only to request a repaint. Appending there again
     * would duplicate every line of real shell output.
     */
    @Test
    fun appendOutputInSessionModeRepaintsWithoutDuplicatingTheSharedModel() {
        val view = ModernTerminalView(context)
        val model = TerminalScreenModel(80, 24)
        // What the session pool's pump loop already did before the view hears about it.
        model.append("from the pty pump\r\n")
        view.setSessionScreen(model)

        view.appendOutput("from the pty pump\r\n")
        idleMainLooper()

        val text = model.displaySnapshot().text()
        assertEquals(
            "session-mode appendOutput must be a render tick, not a second write: <$text>",
            1,
            Regex("from the pty pump").findAll(text).count(),
        )
    }

    @Test
    fun sessionOutputReachesTheRenderedTranscript() {
        val view = ModernTerminalView(context)
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1600)
        val model = TerminalScreenModel(80, 24)
        view.setSessionScreen(model)
        view.appendSessionOutput("\u001b[32mcolor-band\u001b[0m\r\n")

        val rendered = view.getChildAt(0) as android.widget.TextView
        assertTrue(
            "rendered transcript is empty after session output: <${rendered.text}>",
            rendered.text.toString().contains("color-band"),
        )
    }

    @Test
    fun backgroundImageAndScrimComposeIntoTheViewsBackground() {
        val view = ModernTerminalView(context)
        val bitmap = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        view.setTerminalBackgroundImage(bitmap, 0x80242424.toInt())

        val layer = view.background as android.graphics.drawable.LayerDrawable
        val scrim = layer.getDrawable(1) as android.graphics.drawable.ColorDrawable
        assertEquals(
            "scrim must be the terminal background at the chosen alpha",
            0x80242424.toInt(),
            scrim.color,
        )

        view.setTerminalScrimColor(0x40242424)
        assertEquals(0x40242424, (layer.getDrawable(1) as android.graphics.drawable.ColorDrawable).color)

        view.clearTerminalBackgroundImage(0xFF242424.toInt())
        assertFalse(
            "clearing must drop the layered background",
            view.background is android.graphics.drawable.LayerDrawable,
        )
    }

    @Test
    fun ptyModeKeepsFlushingIntoTheOwnedScreen() {
        // sessionScreen == false is the real PTY path; its behaviour must stay as it was.
        val view = ModernTerminalView(context)
        view.appendOutput("pty output")

        idleMainLooper()

        assertTrue(
            "non-session output must still render into the view transcript",
            (view.getChildAt(0) as android.widget.TextView).text.toString().contains("pty output"),
        )
    }
}
