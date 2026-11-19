package com.cuaderno.app

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.cuaderno.app.page.PageView
import com.cuaderno.app.page.Render
import com.juanma.kit.Ui
import cuaderno.core.Document
import cuaderno.core.Notebook
import cuaderno.core.NotebookFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The page: the pen writes in page points, fingers only move it, and what is written survives. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-mdpi")
class PageViewTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun page(w: Int = 1206, h: Int = 800): PageView {
        val view = PageView(context, lowLatency = false)
        view.document = Document(Notebook("n", "t", "c", "m", emptyList()))
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return view
    }

    private fun line(view: PageView, x0: Float, y0: Float, x1: Float, y1: Float, rub: Boolean = false) {
        view.penDown(x0, y0, rub)
        for (i in 1..10) view.penMove(x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10)
        view.penUp()
    }

    private fun touch(view: PageView, action: Int, x: Float, y: Float, tool: Int, buttons: Int = 0) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.7f })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0, 0, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun drag(view: PageView, tool: Int, x0: Float, y0: Float, x1: Float, y1: Float, buttons: Int = 0) {
        touch(view, MotionEvent.ACTION_DOWN, x0, y0, tool, buttons)
        touch(view, MotionEvent.ACTION_MOVE, (x0 + x1) / 2, (y0 + y1) / 2, tool, buttons)
        touch(view, MotionEvent.ACTION_MOVE, x1, y1, tool, buttons)
        touch(view, MotionEvent.ACTION_UP, x1, y1, tool, buttons)
    }

    @Test fun theSheetOpensAsWideAsTheScreenAndIsA4() {
        val view = page()
        // 1206 px wide, 8 px margins: 1190 px for 595 points.
        assertEquals(2f, view.scale, 0.001f)
        assertEquals(8f, view.offsetX, 0.001f)
        val doc = view.document!!
        assertEquals(595f, doc.pages[0].width); assertEquals(842f, doc.pages[0].height)
    }

    @Test fun aStrokeIsKeptInPagePointsWhateverTheZoom() {
        val view = page()
        line(view, 208f, 208f, 408f, 208f)
        val stroke = view.document!!.pages[0].strokes.single()
        assertEquals(100f, stroke.x(0), 0.01f); assertEquals(100f, stroke.y(0), 0.01f)
        assertEquals(200f, stroke.x(stroke.size - 1), 0.01f)
        assertEquals(1.1f, stroke.width)
        val image = view.snapshot()
        assertEquals(Ui.BG, image.getPixel(300, 208))
        assertEquals(Ui.BRIGHT, image.getPixel(300, 240))
    }

    @Test fun theLineIsAsWideAtAnyPressure() {
        val view = page()
        view.widthPt = 1.8f
        view.penDown(208f, 208f, false, 0, pressure = 0.05f); view.penMove(608f, 208f, 10, pressure = 0.05f); view.penUp()
        view.penDown(208f, 408f, false, 0, pressure = 1f); view.penMove(608f, 408f, 10, pressure = 1f); view.penUp()
        val image = view.snapshot()
        fun thickness(y: Int) = (y - 10..y + 10).count { image.getPixel(400, it) == Ui.BG }
        assertTrue(thickness(208) > 0)
        assertEquals(thickness(208), thickness(408))
        // What the pen reported is still kept, for a brush that wants it later.
        assertEquals(1f, view.document!!.pages[0].strokes[1].pressure(0))
    }

    @Test fun thePaperCanBeLightOrDarkAndPlainInkFollowsIt() {
        val view = page()
        line(view, 208f, 208f, 408f, 208f)
        assertEquals(Ui.BG, view.snapshot().getPixel(300, 208))      // dark ink on light paper
        view.darkPaper = true
        val dark = view.snapshot()
        assertEquals(Ui.BRIGHT, dark.getPixel(300, 208))             // the same stroke, light on dark
        assertEquals(Ui.BG, dark.getPixel(300, 240))
        // The file keeps what was written, whatever paper shows it.
        assertEquals(Ui.BG, view.document!!.pages[0].strokes[0].color)
        view.color = Ui.RED
        line(view, 208f, 308f, 408f, 308f)
        assertEquals(Ui.RED, view.snapshot().getPixel(300, 308))
        view.darkPaper = false
        assertEquals(Ui.RED, view.snapshot().getPixel(300, 308))
    }

    @Test fun theHighlighterIsSeeThroughEvenWhileWritingAndSitsUnderTheInk() {
        val view = page()
        line(view, 208f, 208f, 408f, 208f)                           // ink first
        view.tool = cuaderno.core.Tool.HIGHLIGHTER
        view.color = Ui.YELLOW
        view.widthPt = 10f
        view.penDown(308f, 150f)
        for (i in 1..10) view.penMove(308f, 150f + 11f * i)          // across the ink, pen still down
        fun screen() = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            .also { view.draw(android.graphics.Canvas(it)) }
        val live = screen()
        val blend = live.getPixel(308, 180)
        assertTrue(blend != Render.PAPER_LIGHT && blend != Ui.YELLOW)  // see-through, not solid
        view.penUp()
        val stroke = view.document!!.pages[0].strokes[1]
        assertEquals(cuaderno.core.Tool.HIGHLIGHTER, stroke.tool)
        assertEquals(Ui.YELLOW, stroke.color)                         // the file keeps it opaque
        assertEquals(10f, stroke.width)
        val after = screen()
        // The same while writing as once lifted, all along: the pieces of the stroke in
        // progress do not darken where they meet. (Not where the ink crosses: it goes on top after.)
        // Within rounding (a channel off by one between the two ways of blending); a darker
        // overlap would be tens off.
        fun near(a: Int, b: Int) = (0..2).all { kotlin.math.abs((a shr (it * 8) and 255) - (b shr (it * 8) and 255)) <= 3 }
        for (y in 160..250) if (y !in 200..216) assertTrue("y $y", near(after.getPixel(305, y), live.getPixel(305, y)))
        assertTrue(near(blend, after.getPixel(308, 180)))
        assertEquals(Ui.BG, after.getPixel(308, 208))                 // the ink crossing it stays sharp, on top
    }

    @Test fun aFingerMovesThePageAndNeverWrites() {
        val view = page()
        line(view, 208f, 208f, 408f, 208f)
        val before = view.offsetY
        drag(view, MotionEvent.TOOL_TYPE_FINGER, 600f, 600f, 600f, 300f)
        assertEquals(before - 300f, view.offsetY, 0.5f)
        assertEquals(1, view.document!!.pages[0].strokes.size)
        // Dragging down past the top stops at the margin.
        drag(view, MotionEvent.TOOL_TYPE_FINGER, 600f, 100f, 600f, 700f)
        drag(view, MotionEvent.TOOL_TYPE_FINGER, 600f, 100f, 600f, 700f)
        assertEquals(8f, view.offsetY, 0.5f)
    }

    private fun idle(ms: Long) =
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /** Two fingers, one each side of the middle, from [from] pixels apart to [to]. */
    private fun pinch(view: PageView, from: Float, to: Float, release: Boolean = true) {
        fun event(action: Int, count: Int, span: Float) {
            val properties = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(count) { i -> MotionEvent.PointerCoords().apply {
                x = 600f + (if (i == 0) -span else span) / 2; y = 400f; pressure = 0.5f } }
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(10))
            val now = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(now, now, action, count, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
            view.dispatchTouchEvent(e)
            e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 1, from)
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, from)
        for (i in 1..5) event(MotionEvent.ACTION_MOVE, 2, from + (to - from) * i / 5)
        if (release) {
            event(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, to)
            event(MotionEvent.ACTION_UP, 1, to)
        }
    }

    @Test fun pinchedSmallerThanTheScreenTheSheetFollowsTheFingersAndSpringsBack() {
        val view = page()
        val fit = view.scale
        pinch(view, 600f, 360f, release = false)
        assertEquals(fit * 0.6f, view.scale, fit * 0.02f)       // it follows the fingers below the screen's width
        pinch(view, 600f, 360f)
        idle(400)
        assertEquals(fit, view.scale, 0.0001f)                  // and comes back when they let go
        assertEquals((1206 - 595f * fit) / 2, view.offsetX, 0.5f)
        pinch(view, 300f, 600f)
        idle(400)
        assertEquals(fit * 2, view.scale, fit * 0.05f)          // zooming in stays where it was left
    }

    @Test fun thePageCoastsAfterAFlickAndATouchStopsIt() {
        val view = page()
        repeat(3) { view.document!!.addPage() }  // room to coast, away from the end
        view.document = view.document
        fun flick(y0: Float, y1: Float, stepMs: Long) {
            touch(view, MotionEvent.ACTION_DOWN, 600f, y0, MotionEvent.TOOL_TYPE_FINGER)
            for (i in 1..6) {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(stepMs))
                touch(view, MotionEvent.ACTION_MOVE, 600f, y0 + (y1 - y0) * i / 6, MotionEvent.TOOL_TYPE_FINGER)
            }
            touch(view, MotionEvent.ACTION_UP, 600f, y1, MotionEvent.TOOL_TYPE_FINGER)
        }
        flick(700f, 400f, 8)
        val released = view.offsetY
        idle(300)
        val coasting = view.offsetY
        assertTrue("kept going: $released -> $coasting", coasting < released - 100)
        touch(view, MotionEvent.ACTION_DOWN, 600f, 400f, MotionEvent.TOOL_TYPE_FINGER)
        touch(view, MotionEvent.ACTION_UP, 600f, 400f, MotionEvent.TOOL_TYPE_FINGER)
        idle(300)
        assertEquals(coasting, view.offsetY, 0.5f)              // a finger down stops it
        // A slow drag ends where the finger left it.
        flick(400f, 380f, 200)
        val slow = view.offsetY
        idle(300)
        assertEquals(slow, view.offsetY, 0.5f)
    }

    @Test fun theEraserShowsWhereItRubsOutAndWritingClosesWhatIsOpen() {
        val view = page()
        var closed = 0
        view.onPenDown = { closed++ }
        touch(view, MotionEvent.ACTION_DOWN, 300f, 300f, MotionEvent.TOOL_TYPE_STYLUS)
        assertFalse(view.ringShown)                             // writing: no circle
        assertEquals(1, closed)
        touch(view, MotionEvent.ACTION_UP, 300f, 300f, MotionEvent.TOOL_TYPE_STYLUS)
        touch(view, MotionEvent.ACTION_DOWN, 300f, 500f, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertTrue(view.ringShown)                              // button held: the circle is there
        touch(view, MotionEvent.ACTION_UP, 300f, 500f, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertFalse(view.ringShown)
    }

    @Test fun holdingThePenAtTheEndStraightensALineAndRoundsACircle() {
        val view = page()
        fun shaky(y: Float, wait: Long) {
            view.penDown(200f, y)
            for (i in 1..30) view.penMove(200f + i * 10f, y + 6f * kotlin.math.sin(i * 0.8f))
            idle(wait)
            view.penUp()
        }
        shaky(200f, 100)                                        // lifted at once: kept as written
        shaky(300f, 700)                                        // held: a straight line
        val strokes = view.document!!.pages[0].strokes
        assertEquals(31, strokes[0].size)
        assertEquals(2, strokes[1].size)
        // Held and then moved: the line's end follows the pen.
        view.penDown(200f, 400f)
        for (i in 1..30) view.penMove(200f + i * 10f, 400f)
        idle(700)
        view.penMove(500f, 460f)
        view.penUp()
        val moved = view.document!!.pages[0].strokes[2]
        assertEquals(2, moved.size)
        assertEquals((460f - view.offsetY) / view.scale, moved.y(1), 0.01f)
        // A rough circle, held.
        view.penDown(700f, 300f)
        for (i in 1..60) {
            val a = 2 * Math.PI * i / 60
            view.penMove(600f + 100f * kotlin.math.cos(a).toFloat() * (1 + 0.05f * kotlin.math.sin(4 * a).toFloat()),
                300f + 100f * kotlin.math.sin(a).toFloat())
        }
        idle(700)
        val image = view.snapshot()                              // shown clean while the pen is still down
        view.penUp()
        val circle = view.document!!.pages[0].strokes[3]
        assertEquals(73, circle.size)
        assertEquals(Ui.BG, image.getPixel(600, 200))            // the top of the round circle
        view.document!!.undo()
        assertEquals(3, view.document!!.pages[0].strokes.size)   // one undo takes the shape away
    }

    @Test fun thePenWritesAndItsButtonAndEraserEndRubOut() {
        val view = page()
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, 208f, 208f, 408f, 208f)
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, 208f, 308f, 408f, 308f)
        assertEquals(2, view.document!!.pages[0].strokes.size)
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, 300f, 180f, 300f, 240f, MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertEquals(1, view.document!!.pages[0].strokes.size)
        drag(view, MotionEvent.TOOL_TYPE_ERASER, 300f, 280f, 300f, 340f)
        assertTrue(view.document!!.pages[0].strokes.isEmpty())
        view.document!!.undo(); view.document!!.undo()
        assertEquals(2, view.document!!.pages[0].strokes.size)
    }

    @Test fun writingLowerDownLandsOnTheNextPage() {
        val view = page()
        line(view, 208f, 208f, 408f, 208f)          // page 1 gets ink
        view.document!!.addPage(); view.document!!.addPage()
        view.document = view.document
        view.scrollBy(0f, -(842f + Render.GAP) * 2f)  // page 2 now where page 1 was
        line(view, 208f, 208f, 408f, 208f)
        val pages = view.document!!.pages
        assertEquals(3, pages.size)
        assertEquals(100f, pages[1].strokes.single().y(0), 0.01f)
    }

    @Test fun whatIsWrittenIsOnDiskAfterLeavingAndComesBack() {
        val file = File(folder.root, "notebooks/cuaderno.cuaderno")
        val store = Store(file)
        val view = page()
        view.document = store.load()
        line(view, 208f, 208f, 408f, 208f)
        store.saveNow(view.document!!)
        assertEquals(1, NotebookFile.read(file).pages[0].strokes.size)
        val again = Store(file).load()
        assertEquals(view.document!!.pages[0].strokes, again.pages[0].strokes)
        assertTrue(File(file.path + ".bak").exists())
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test fun aNotebookThatCannotBeReadIsSetAsideNotOverwritten() {
        val file = File(folder.root, "notebooks/cuaderno.cuaderno")
        file.parentFile!!.mkdirs()
        file.writeText("this is not a notebook")
        val store = Store(file)
        val document = store.load()
        assertEquals(1, document.pages.size)
        assertTrue(store.problem != null)
        val aside = file.parentFile!!.listFiles()!!.single { it.name.contains(".ilegible-") }
        assertEquals("this is not a notebook", aside.readText())
    }

    /** The PDF itself needs Android's real PDF writer, which the tests do not have: checked on a device. */
    @Test fun anEmptyNotebookExportsNothing() {
        val out = File(folder.root, "out.pdf")
        assertFalse(PdfExport.write(RuntimeEnvironment.getApplication(), page().document!!.pages, out))
        assertFalse(out.exists())
    }

    /** Every recording kept in the repo replays into the strokes that were written. */
    @Test fun recordingsReplay() {
        assertTrue(Replay.names().isNotEmpty())
        for (name in Replay.names()) {
            val recording = Replay.load(name)
            val view = page(recording.width, recording.height)
            Replay.into(view, recording)
            val written = recording.samples.count { it.action == 'D' && it.tool != 4 && it.buttons and 0x20 == 0 }
            assertEquals(name, written, view.document!!.pages.sumOf { it.strokes.size })
        }
    }

    /**
     * A guard, not a latency number: what our code spends per pen sample,
     * which has to stay far below the 4 ms between two samples of a 240 Hz
     * pen. It fails if a change makes the writing path several times slower.
     */
    @Test fun aPenSampleCostsFarLessThanTheTimeBetweenTwo() {
        val recording = Replay.load("synthetic.csv")
        val view = page(recording.width, recording.height)
        Replay.into(view, recording)  // warm up
        val start = System.nanoTime()
        repeat(5) { Replay.into(view, recording) }
        val perSample = (System.nanoTime() - start) / 1e6 / (5 * recording.samples.size)
        println("pen sample cost: %.4f ms".format(perSample))
        assertTrue("a pen sample costs $perSample ms", perSample < 1.0)
    }

    /** One finger up past the last page: far enough and let go adds a page and goes to it; not enough springs back. */
    @Test fun pullingPastTheLastPageAddsOne() {
        val view = page()
        var asked = 0
        view.onAddPage = { asked++; view.document!!.addPage(); view.document = view.document }
        val bottom = view.offsetY.let { view.scrollBy(0f, -100000f); view.offsetY }
        fun pull(by: Float) {
            touch(view, MotionEvent.ACTION_DOWN, 600f, 700f, MotionEvent.TOOL_TYPE_FINGER)
            for (i in 1..8) {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(40))
                touch(view, MotionEvent.ACTION_MOVE, 600f, 700f - by * i / 8, MotionEvent.TOOL_TYPE_FINGER)
            }
            touch(view, MotionEvent.ACTION_UP, 600f, 700f - by, MotionEvent.TOOL_TYPE_FINGER)
        }
        // A little: nothing added, back to the end.
        pull(80f)
        idle(400)
        assertEquals(0, asked)
        assertEquals(1, view.document!!.pages.size)
        assertEquals(bottom, view.offsetY, 0.5f)
        // Enough (96 dp at half the finger): a page, and the new page's top on screen.
        pull(400f)
        idle(500)
        assertEquals(1, asked)
        assertEquals(2, view.document!!.pages.size)
        assertEquals(1, view.currentPage)
        // A pen never pulls.
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, 600f, 700f, 600f, 100f)
        idle(500)
        assertEquals(2, view.document!!.pages.size)
    }
}
