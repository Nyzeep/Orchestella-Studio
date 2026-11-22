package io.github.nyzeep.orchestella.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.DraculaPalette
import io.github.nyzeep.orchestella.TerminalSpan
import io.github.nyzeep.orchestella.ui.design.components.OeLegacyViewStyle
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W2-02: pixel gate for the 2dp left-edge command rail. The rail is a paint-only
 * decoration for archived command-head rows, so this test pins its geometry
 * (2px bar, left of the text origin) and its theme accent color directly on the
 * rendered bitmap — nothing else in the draw path may touch those pixels.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h9000dp-mdpi")
class TerminalCanvasRailDrawTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun railPaintsExactlyTheCommandHeadRowsInAccentColor() {
        val railColor = 0xFFBD93F9.toInt()
        val background = 0xFF242424.toInt()
        val spans = listOf(
            TerminalSpan("row0\nrow1\nrow2", null, null, false, false, false, false),
        )
        // Archived command head covers display line 1 only.
        val snapshot = TerminalScreenModel.Snapshot(
            spans, 0, 0, 0L,
            listOf(TerminalScreenModel.Snapshot.CommandRange(1, 2)),
        )
        val model = TerminalCanvasRenderer.LineModel.from(snapshot)

        val renderer = TerminalCanvasRenderer()
        renderer.configure(
            OeLegacyViewStyle.monoTypeface(context), 14f, 0f, 0f,
            0, DraculaPalette.themedForeground(), railColor,
        )
        renderer.setOrigin(10, 10)
        renderer.setRailGeometry(6, 2) // bar occupies x in [2, 4), 6px gap to the text origin

        val bitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(background)
        val canvas = Canvas(bitmap)
        canvas.clipRect(0, 0, 120, 90)
        renderer.draw(canvas, model, 0, false, false, 0, 0, snapshot.commandLineRanges())

        val rowHeight = renderer.rowHeightPx()
        val railX = 3 // inside the [2, 4) bar, clear of every text glyph (origin at 10)
        assertEquals(
            "command-head row 1 must carry the accent rail",
            railColor,
            bitmap.getPixel(railX, 10 + rowHeight + 2),
        )
        assertNotEquals(
            "row 0 is not a command head and must stay rail-free",
            railColor,
            bitmap.getPixel(railX, 10 + 2),
        )
        assertNotEquals(
            "row 2 is not a command head and must stay rail-free",
            railColor,
            bitmap.getPixel(railX, 10 + 2 * rowHeight + 2),
        )
    }
}
