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
 * The Theme Studio preview owns its own [TerminalScreenModel] and feeds it text
 * through [ModernTerminalView.appendOutput]. That ingestion path must reach the
 * model in session mode too: the old `if (!sessionScreen)` guard in the flush
 * task silently dropped every chunk, which is why the preview rendered an
 * empty block.
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
    fun sessionModelReceivesAppendedOutput() {
        val view = ModernTerminalView(context)
        val model = TerminalScreenModel(80, 24)
        view.setSessionScreen(model)
        view.appendOutput("hello session\r\nsecond line")

        assertFalse(
            "output must not be consumed before the throttled flush runs",
            model.displaySnapshot().text().contains("hello session"),
        )

        idleMainLooper()

        val text = model.displaySnapshot().text()
        assertTrue("session model dropped the appended chunk: <$text>", text.contains("hello session"))
        assertTrue("session model dropped the second chunk line: <$text>", text.contains("second line"))
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
        view.appendOutput("\u001b[32mcolor-band\u001b[0m\r\n")

        idleMainLooper()

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
