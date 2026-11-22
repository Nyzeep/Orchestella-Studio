package io.github.nyzeep.orchestella

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nyzeep.orchestella.terminal.TerminalCanvasAccessibilityHelper
import io.github.nyzeep.orchestella.terminal.TerminalScreenModel
import io.github.nyzeep.orchestella.ui.theme.ThemeCatalog
import io.github.nyzeep.orchestella.terminal.TerminalSelectionGeometry
import java.io.ByteArrayOutputStream
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * W2-02: integrated Canvas-renderer gates on [ModernTerminalView]. The selectable
 * TextView stays the runtime fallback, so every behavior here must hold in both
 * modes: same content, same pure-text clipboard, same scroll semantics.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w412dp-h9000dp-mdpi")
class ModernTerminalViewCanvasTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun idleMainLooper() {
        // flushPendingOutput is throttled by FLUSH_INTERVAL_MS (32ms).
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
    }

    private fun layoutView(view: ModernTerminalView) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 1600)
    }

    private fun newSessionView(vararg chunks: String): Pair<ModernTerminalView, TerminalScreenModel> {
        val view = ModernTerminalView(context)
        val model = TerminalScreenModel(80, 24)
        view.setSessionScreen(model)
        chunks.forEach { view.appendSessionOutput(it) }
        return view to model
    }

    @Test
    fun canvasModeRendersTheSameContentAsTheTextViewFallback() {
        val (view, model) = newSessionView("\u001b[32mgreen\u001b[0m plain\r\n中文混排\r\n")
        idleMainLooper()
        val modelText = model.displaySnapshot().text()
        assertEquals(modelText, (view.getChildAt(0) as android.widget.TextView).text.toString())

        view.setCanvasRenderingEnabled(true)
        val canvasModel = view.canvasModel
        assertEquals(modelText, canvasModel!!.text)
        assertEquals(3, canvasModel.lineCount)

        view.setCanvasRenderingEnabled(false)
        assertEquals(modelText, (view.getChildAt(0) as android.widget.TextView).text.toString())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun selectionCopyAndScrollToBottomMatchAcrossModes() {
        val (view, model) = newSessionView("shared-cmd\r\nshared-out\r\n")
        idleMainLooper()
        layoutView(view)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // TextView fallback mode: select the whole transcript and copy.
        val transcript = view.getChildAt(0) as android.widget.TextView
        android.text.Selection.setSelection(transcript.text as android.text.Spannable, 0, transcript.text.length)
        assertTrue(view.copySelection())
        val fromTextView = clipboard.primaryClip!!.getItemAt(0).text.toString()

        // Canvas mode: the same snapshot must yield the exact same pure text.
        view.setCanvasRenderingEnabled(true)
        view.selectAllTerminalText()
        assertTrue(view.copySelection())
        val fromCanvas = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertEquals(model.displaySnapshot().text(), fromTextView)
        assertEquals(fromTextView, fromCanvas)

        // Follow semantics: scrollToBottom lands on the maximum scroll in canvas mode too.
        view.setCanvasRenderingEnabled(false)
        idleMainLooper()
        layoutView(view)
        view.setCanvasRenderingEnabled(true)
        idleMainLooper()
        layoutView(view)
        view.scrollToBottom()
        idleMainLooper()
        val viewport = view.height - view.paddingTop - view.paddingBottom
        val expectedMax = maxOf(0, (view.canvasChild() as View).height - viewport)
        assertEquals(
            "scrollToBottom must reach the bottom in canvas mode",
            expectedMax,
            view.scrollY,
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun letterSpacingIsCountedExactlyOnceAgainstTheMonoCell() {
        // W1-06 P2-2 hard gate on the production formula: cellStep comes from
        // measure("MM") - measure("M"), and a drawn run adds exactly one
        // inter-cell spacing term per glyph — the two must agree, or runs silently
        // shatter into per-cell draws (correct but slow) or drift (wrong).
        val view = ModernTerminalView(context)
        view.applyTheme(ThemeCatalog.constructivistWorkstation, 14, 0.1f, 0f)
        val renderer = view.canvasRenderer
        val cellStep = renderer.cellStepPx()

        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.typeface = io.github.nyzeep.orchestella.ui.design.components.OeLegacyViewStyle.monoTypeface(context)
        paint.textSize = renderer.textSizePx()
        paint.letterSpacing = 0.1f
        val advance = paint.measureText("M")
        val spacing = 0.1f * paint.textSize
        assertTrue(
            "cell step must carry exactly one letterSpacing term: advance=$advance spacing=$spacing cellStep=$cellStep",
            kotlin.math.abs(advance + spacing - cellStep) <= cellStep * 0.01f,
        )
    }

    @Test
    fun canvasCopySelectionIsPureTextWithoutRailPollution() {
        val (view, model) = newSessionView("cmd-echo\r\nplain output\r\n")
        idleMainLooper()
        view.setCanvasRenderingEnabled(true)

        view.selectAllTerminalText()
        assertTrue(view.copySelection())
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertEquals(model.displaySnapshot().text(), copied)
        // The 2dp command rail is paint-only: no decoration can enter the clipboard.
        assertFalse(copied.contains("\u258c"))

        view.clearCanvasSelection()
        assertFalse(view.copySelection())
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun canvasDrawsOnlyTheVisibleWindowRegardlessOfHistorySize() {
        val (view, _) = newSessionView()
        layoutView(view)
        view.setCanvasRenderingEnabled(true)
        val canvasView = view.canvasChild() as View

        val first = StringBuilder()
        repeat(500) { first.append("row-").append(it).append("\r\n") }
        view.appendSessionOutput(first.toString())

        val bitmap = Bitmap.createBitmap(1080, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val clipHeight = 400
        canvas.clipRect(0, 0, 1080, clipHeight)
        canvasView.draw(canvas)
        val renderer = view.canvasRenderer
        val maxRows = clipHeight / renderer.rowHeightPx() + 2
        assertTrue(
            "drawn rows must stay within the viewport: ${renderer.lastDrawnRowCount} > $maxRows",
            renderer.lastDrawnRowCount in 1..maxRows,
        )
        val drawnAt500Lines = renderer.lastDrawnRowCount

        val second = StringBuilder()
        repeat(1500) { second.append("more-").append(it).append("\r\n") }
        view.appendSessionOutput(second.toString())
        canvasView.draw(canvas)
        assertEquals(
            "drawn rows must not grow with history depth",
            drawnAt500Lines,
            renderer.lastDrawnRowCount,
        )
    }

    @Test
    fun canvasSelectionHitTestRoundTripsThroughCellGeometry() {
        val (view, _) = newSessionView("ab中文de\r\n")
        idleMainLooper()
        view.setCanvasRenderingEnabled(true)
        val model = view.canvasModel!!
        // "ab中文de": a b 中[2 cols] 文[2 cols] d e — mid-wide columns snap to cell starts.
        assertEquals(2, TerminalSelectionGeometry.offsetAtColumn(model.text, model.lineStart(0), model.lineEnd(0), 3))
        assertEquals(3, TerminalSelectionGeometry.offsetAtColumn(model.text, model.lineStart(0), model.lineEnd(0), 4))
        assertEquals(4, TerminalSelectionGeometry.offsetAtColumn(model.text, model.lineStart(0), model.lineEnd(0), 6))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun commandRailAndSelectionPaintDoNotTouchTheTextBuffer() {
        val (view, _) = newSessionView("before\r\n")
        idleMainLooper()
        view.setCanvasRenderingEnabled(true)
        val canvasView = view.canvasChild() as View
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.clipRect(0, 0, 400, 400)
        // Selection on: the renderer must not modify model text while painting bands.
        view.selectAllTerminalText()
        canvasView.draw(canvas)
        val textDuringSelection = view.canvasModel!!.text
        view.clearCanvasSelection()
        canvasView.draw(canvas)
        assertEquals(textDuringSelection, view.canvasModel!!.text)
    }

    @Test
    fun presentationProbeFiresOncePerContentRender() {
        val view = ModernTerminalView(context)
        var fired = 0
        var stamp = 0L
        view.setPresentProbe { stamp = it; fired++ }

        view.appendOutput("probe\r\n")
        idleMainLooper()
        // No draw yet: the probe stays armed, not fired.
        assertEquals(0, fired)
        (view.getChildAt(0) as android.widget.TextView).draw(Canvas())
        assertEquals(1, fired)
        assertTrue(stamp > 0)
        // A redraw without a content render must not fire again.
        (view.getChildAt(0) as android.widget.TextView).draw(Canvas())
        assertEquals(1, fired)

        view.appendOutput("more\r\n")
        idleMainLooper()
        (view.getChildAt(0) as android.widget.TextView).draw(Canvas())
        assertEquals(2, fired)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun presentationProbeWorksThroughTheCanvasPathToo() {
        val (view, _) = newSessionView()
        var fired = 0
        view.setPresentProbe { fired++ }
        view.setCanvasRenderingEnabled(true)
        view.appendSessionOutput("canvas probe\r\n")
        (view.canvasChild() as View).draw(Canvas(Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)))
        assertEquals(1, fired)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun longPressSelectionRunsThroughTheRealTouchPipeline() {
        // Regression for the two-axis review P1-1: the canvas child must claim the
        // DOWN so MOVE/UP/CANCEL reach its listener — otherwise a held scroll
        // triggers an unrecoverable phantom selection and drags stay dead.
        val (view, _) = newSessionView("touch-target\r\n")
        layoutView(view)
        view.setCanvasRenderingEnabled(true)
        val canvas = view.canvasChild() as View
        val renderer = view.canvasRenderer

        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 20f, 22f, 0)
        assertTrue("the canvas must claim the DOWN to stay in the touch pipeline", canvas.dispatchTouchEvent(down))
        down.recycle()

        // Hold past the long-press timeout: selection begins at the touched cell.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 100L))
        assertTrue("held press must start a canvas selection", view.isCanvasSelectionActive)
        val start = view.canvasSelectionEnd()

        // A drag past the slop extends the selection (MOVE reaches the listener).
        val move = android.view.MotionEvent.obtain(0, 50, android.view.MotionEvent.ACTION_MOVE, 20f + renderer.cellStepPx() * 5f, 22f, 0)
        canvas.dispatchTouchEvent(move)
        move.recycle()
        assertTrue("drag must extend the selection", view.canvasSelectionEnd() > start)

        // UP after a drag keeps the selection for the floating action mode.
        val up = android.view.MotionEvent.obtain(0, 60, android.view.MotionEvent.ACTION_UP, 20f + renderer.cellStepPx() * 5f, 22f, 0)
        canvas.dispatchTouchEvent(up)
        up.recycle()
        assertTrue("dragged selection must survive the UP", view.isCanvasSelectionActive)

        // A fresh tap without drag dismisses it.
        val tapDown = android.view.MotionEvent.obtain(0, 70, android.view.MotionEvent.ACTION_DOWN, 40f, 40f, 0)
        canvas.dispatchTouchEvent(tapDown)
        tapDown.recycle()
        val tapUp = android.view.MotionEvent.obtain(0, 80, android.view.MotionEvent.ACTION_UP, 40f, 40f, 0)
        canvas.dispatchTouchEvent(tapUp)
        tapUp.recycle()
        assertFalse("a tap must dismiss the selection", view.isCanvasSelectionActive)
    }

    @Test
    fun canvasModeKeepsTheCjkImePathIntact() {
        // Ticket acceptance #4: CJK composition must survive the canvas switch —
        // the IME connection lives on the host ScrollView, not the swapped child.
        val view = ModernTerminalView(context)
        val pty = ByteArrayOutputStream()
        val sink = object : TerminalInputSink {
            override fun sendBytes(bytes: ByteArray) { pty.writeBytes(bytes) }
        }
        view.setInputSink(sink)
        view.setCanvasRenderingEnabled(true)

        val connection = view.currentInputConnection!!
        assertTrue(connection.setComposingText("你好", 1))
        assertTrue(connection.finishComposingText())
        assertTrue(view.sendTerminalText("\n"))
        idleMainLooper()

        val sent = pty.toString(java.nio.charset.StandardCharsets.UTF_8)
        assertTrue("CJK composition bytes must reach the PTY: <$sent>", sent.contains("你好"))
        assertTrue("commit newline must reach the PTY", sent.contains("\r"))
    }

    @Test
    fun pasteInterceptorCanDeferPasting() {
        val view = ModernTerminalView(context)
        val pty = ByteArrayOutputStream()
        val sink = object : TerminalInputSink {
            override fun sendBytes(bytes: ByteArray) { pty.writeBytes(bytes) }
            override fun trySendBytes(bytes: ByteArray): Boolean {
                pty.writeBytes(bytes); return true
            }
        }
        view.setInputSink(sink)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("t", "hello"))

        view.setPasteInterceptor { false }
        assertFalse(view.pasteFromClipboard())
        assertEquals(0, pty.size())

        view.setPasteInterceptor { true }
        assertTrue(view.pasteFromClipboard())
        assertTrue(pty.size() > 0)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun accessibilityHelperExposesVisibleRowsAsTextNodes() {
        val (view, _) = newSessionView("alpha\r\n\r\nbeta\r\n")
        idleMainLooper()
        view.setCanvasRenderingEnabled(true)
        val renderer = view.canvasRenderer
        val helper = object : TerminalCanvasAccessibilityHelper(
            view,
            { view.canvasModel },
            { renderer },
            { 0 },
            { 4 * renderer.rowHeightPx() },
            { true },
            {},
        ) {
            fun nodeFor(id: Int) = androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                .obtain().also { onPopulateNodeForVirtualView(id, it) }
            fun visibleIds(): List<Int> {
                val ids = mutableListOf<Int>()
                getVisibleVirtualViews(ids)
                return ids
            }
            fun rowAt(x: Float, y: Float) = getVirtualViewAt(x, y)
        }

        // Viewport of four rows over a three-line model (plus the trailing cursor row).
        val ids = helper.visibleIds()
        assertEquals(listOf(0, 1, 2, 3), ids)

        val node0 = helper.nodeFor(0)
        assertEquals("alpha", node0.text.toString())
        val bounds0 = android.graphics.Rect()
        node0.getBoundsInParent(bounds0)
        assertEquals(renderer.originYPx(), bounds0.top)

        val node1 = helper.nodeFor(1)
        assertEquals(context.getString(R.string.terminal_a11y_blank_row), node1.text.toString())

        assertEquals("beta", helper.nodeFor(2).text.toString())

        assertTrue(
            helper.nodeFor(0).actions and androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_COPY != 0,
        )
        assertEquals(1, helper.rowAt(0f, renderer.originYPx() + (renderer.rowHeightPx() * 1.5f).toFloat()))
        assertEquals(-1, helper.rowAt(0f, -100f))
    }
}
