package com.cuaderno.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.cuaderno.app.page.Clipboard
import com.cuaderno.app.page.PageView
import com.juanma.kit.Ui
import cuaderno.core.Document
import cuaderno.core.Notebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The lasso on the page: a loop takes strokes, the pen or a finger drags them, "Borrar" takes
 * them away, and each is one undo. The page opens at 2 pixels a point with its corner at 8, 8.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-mdpi")
class LassoViewTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun page(): PageView {
        val view = PageView(context, lowLatency = false)
        view.document = Document(Notebook("n", "t", "c", "m", emptyList()))
        // In a window, as in the app: a tap's click only runs on a view that is attached.
        Robolectric.buildActivity(Activity::class.java).setup().get().setContentView(view)
        view.measure(View.MeasureSpec.makeMeasureSpec(1206, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1206, 800)
        return view
    }

    private fun line(view: PageView, x0: Float, y0: Float, x1: Float, y1: Float) {
        view.penDown(x0, y0)
        for (i in 1..10) view.penMove(x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10)
        view.penUp()
    }

    /** A box-shaped loop drawn with the pen, in pixels. */
    private fun loop(view: PageView, l: Float, t: Float, r: Float, b: Float) {
        view.penDown(l, t)
        for ((x, y) in listOf(r to t, r to b, l to b)) view.penMove(x, y)
        view.penUp()
    }

    /** Two lines: one near the top (taken in most tests), one further down. */
    private fun written(): PageView {
        val view = page()
        line(view, 108f, 108f, 308f, 108f)   // page 50..150, 50
        line(view, 108f, 408f, 308f, 408f)   // page 50..150, 200
        view.lassoing = true
        return view
    }

    private fun touch(view: PageView, action: Int, x: Float, y: Float, tool: Int) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.7f })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun strokes(view: PageView) = view.document!!.pages[0].strokes

    @Before fun nothingCopied() { Clipboard.clip = null }

    /** Tapped as a finger would: through the page, which must let the tap reach [target]. */
    private fun tap(view: PageView, target: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1206, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1206, 800)
        // [target] may sit inside a row: where it is on the page.
        var x = target.width / 2f
        var y = target.height / 2f
        var v: View = target
        while (v !== view) {
            x += v.x; y += v.y
            v = v.parent as View
        }
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun item(view: PageView, n: Int) = (view.actions as android.view.ViewGroup).getChildAt(n)

    @Test fun aLoopTakesWhatIsInsideAndOffersToDeleteIt() {
        val view = written()
        assertEquals(View.GONE, view.actions.visibility)
        loop(view, 80f, 80f, 340f, 140f)
        assertEquals(listOf(0), view.selection)
        assertEquals(0, view.selectedPage)
        assertEquals(View.VISIBLE, view.actions.visibility)
        assertEquals("the lasso writes nothing", 2, strokes(view).size)
        // A loop around nothing lets go of what was taken.
        loop(view, 600f, 600f, 700f, 700f)
        assertEquals(emptyList<Int>(), view.selection)
        assertEquals(View.GONE, view.actions.visibility)
    }

    @Test fun thePenDragsItAndUndoBringsItBack() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        val before = strokes(view)[0]
        view.penDown(200f, 108f)
        view.penMove(300f, 158f)
        view.penMove(400f, 208f)
        assertEquals("nothing changes until it is let go", before, strokes(view)[0])
        view.penUp()
        val moved = strokes(view)[0]
        assertEquals(150f, moved.x(0), 0.01f); assertEquals(100f, moved.y(0), 0.01f)
        assertTrue("the other one stays", strokes(view)[1].y(0) == 200f)
        assertEquals("still taken, to move again", listOf(0), view.selection)
        view.document!!.undo()
        assertEquals(before, strokes(view)[0])
    }

    @Test fun whileDraggedItIsSeenWhereThePenIs() {
        val view = written()
        view.darkPaper = true
        loop(view, 80f, 80f, 340f, 140f)
        view.penDown(200f, 108f)
        view.penMove(200f, 308f)                          // 100 points down: page y 150, pixels 308
        val image = view.snapshot()
        assertTrue("ink where it was taken to", Color.red(image.getPixel(200, 308)) > 200)
        assertTrue("paper where it was", Color.red(image.getPixel(200, 108)) < 100)
        view.penUp()
    }

    @Test fun aFingerOnItDragsItAFingerElsewhereMovesThePage() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        touch(view, MotionEvent.ACTION_DOWN, 200f, 108f, MotionEvent.TOOL_TYPE_FINGER)
        touch(view, MotionEvent.ACTION_MOVE, 200f, 158f, MotionEvent.TOOL_TYPE_FINGER)
        touch(view, MotionEvent.ACTION_UP, 200f, 158f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(75f, strokes(view)[0].y(0), 0.01f)
        val y = view.offsetY
        touch(view, MotionEvent.ACTION_DOWN, 900f, 600f, MotionEvent.TOOL_TYPE_FINGER)
        touch(view, MotionEvent.ACTION_MOVE, 900f, 500f, MotionEvent.TOOL_TYPE_FINGER)
        touch(view, MotionEvent.ACTION_UP, 900f, 500f, MotionEvent.TOOL_TYPE_FINGER)
        assertTrue("the page moved", view.offsetY < y)
        assertEquals("the selection did not", 75f, strokes(view)[0].y(0), 0.01f)
    }

    @Test fun aPalmThatMovedItBeforeThePenLandsPutsItBack() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        val revision = view.document!!.revision
        touch(view, MotionEvent.ACTION_DOWN, 200f, 108f, MotionEvent.TOOL_TYPE_FINGER)
        touch(view, MotionEvent.ACTION_MOVE, 200f, 200f, MotionEvent.TOOL_TYPE_FINGER)
        // The pen lands while the palm is still down.
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER },
            MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 200f; y = 200f },
            MotionEvent.PointerCoords().apply { x = 600f; y = 600f; pressure = 0.5f })
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        view.onTouchEvent(down)
        down.recycle()
        assertEquals("nothing moved", revision, view.document!!.revision)
        assertEquals(50f, strokes(view)[0].y(0), 0.01f)
    }

    @Test fun deleteTakesItAwayAsOneUndo() {
        val view = written()
        loop(view, 80f, 80f, 340f, 440f)
        assertEquals(listOf(0, 1), view.selection)
        tap(view, (view.actions as android.view.ViewGroup).getChildAt(2))   // "Borrar"
        assertEquals(0, strokes(view).size)
        assertEquals(View.GONE, view.actions.visibility)
        view.document!!.undo()
        assertEquals(2, strokes(view).size)
    }

    @Test fun itCannotBePushedOffTheSheet() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        view.penDown(200f, 108f)
        view.penMove(-2000f, -2000f)
        view.penUp()
        val s = strokes(view)[0]
        // The box keeps 8 points of room and the line is 1.1 wide: its left edge and top at 0.
        assertEquals(8.55f, s.x(0), 0.01f); assertEquals(8.55f, s.y(0), 0.01f)
    }

    @Test fun leavingTheLassoOrUndoingLetsGo() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        view.lassoing = false
        assertEquals(emptyList<Int>(), view.selection)
        view.lassoing = true
        loop(view, 80f, 80f, 340f, 140f)
        view.document = view.document              // what undo and redo do from the bar
        assertEquals(emptyList<Int>(), view.selection)
        assertFalse(view.deselect())
    }

    // --- slice 2: corners, colour, copy, cut, paste ------------------------------------

    @Test fun aCornerMakesItBiggerAroundTheOppositeOneAsOneUndo() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        // The line is page 50..150 at 50, 1.1 wide: its box (8 points of room) ends at 158.55, 58.55,
        // so the bottom-right handle is at 8 + 2 * that.
        view.penDown(325.1f, 125.1f)
        view.penMove(425.1f, 125.1f)
        view.penMove(527.3f, 125.1f)                   // 101.1 points right: about twice as wide
        assertEquals("nothing changes until it is let go", 150f, strokes(view)[0].x(10), 0.01f)
        view.penUp()
        val s = strokes(view)[0]
        assertEquals("the held corner (its edge, width counted) stays", 49.45f, s.bounds[0] - s.width / 2, 0.01f)
        assertEquals(250.5f, s.x(10), 0.1f)
        assertEquals(2.2f, s.width, 0.01f)
        assertEquals(listOf(0), view.selection)
        view.document!!.undo()
        assertEquals(150f, strokes(view)[0].x(10), 0.01f)
    }

    @Test fun aCornerCannotTakeItOffTheSheetOrToNothing() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        view.penDown(325.1f, 125.1f)
        view.penMove(5000f, 125.1f)
        view.penUp()
        assertTrue("its right edge at most the sheet's", strokes(view)[0].bounds[2] + strokes(view)[0].width / 2 <= 595.01f)
        view.document!!.undo()
        view.penDown(325.1f, 125.1f)
        view.penMove(-5000f, 125.1f)
        view.penUp()
        val s = strokes(view)[0]
        assertTrue("still a few points wide", s.bounds[2] - s.bounds[0] + s.width >= 3.99f)
    }

    @Test fun aColourRecoloursWhatWasTakenAsOneUndo() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        val before = strokes(view)[0].color
        assertEquals(before, view.selectionColor)
        assertTrue(view.recolor(Ui.RED))
        assertEquals(Ui.RED, strokes(view)[0].color)
        assertEquals("the other one keeps its colour", before, strokes(view)[1].color)
        assertEquals(Ui.RED, view.selectionColor)
        view.document!!.undo()
        assertEquals(before, strokes(view)[0].color)
        view.deselect()
        assertFalse("nothing taken, nothing recoloured", view.recolor(Ui.RED))
    }

    @Test fun copyThenPasteOnAnotherPageTakesThePastedOnes() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        assertEquals(View.GONE, view.pasteChip.visibility)
        tap(view, item(view, 0))                        // "Copiar"
        assertEquals(View.VISIBLE, view.pasteChip.visibility)
        assertEquals("copying leaves it", 2, strokes(view).size)
        view.document!!.addPage()
        view.document = view.document
        view.showPage(1)
        tap(view, view.pasteChip)
        val pasted = view.document!!.pages[1].strokes
        assertEquals(1, pasted.size)
        assertEquals("the same line", 100f, pasted[0].x(10) - pasted[0].x(0), 0.01f)
        assertEquals(1, view.selectedPage)
        assertEquals(listOf(0), view.selection)
        assertEquals("used: it goes", View.GONE, view.pasteChip.visibility)
        view.document!!.undo()
        assertEquals(0, view.document!!.pages[1].strokes.size)
    }

    @Test fun cutTakesItAwayAndKeepsItToPaste() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        tap(view, item(view, 1))                        // "Cortar"
        assertEquals(1, strokes(view).size)
        assertEquals(View.VISIBLE, view.pasteChip.visibility)
        view.lassoing = false
        assertEquals("only with the lasso in hand", View.GONE, view.pasteChip.visibility)
        view.lassoing = true
        assertEquals(View.VISIBLE, view.pasteChip.visibility)
        tap(view, view.pasteChip)
        assertEquals(2, strokes(view).size)
    }

    // --- slice 3: pictures --------------------------------------------------------------

    private fun photo(w: Int = 400, h: Int = 200): ByteArray {
        val image = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(0xFFBF616A.toInt())
        val out = java.io.ByteArrayOutputStream()
        image.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    @Test fun anInsertedPictureLandsInViewTakenWithTheLassoInHand() {
        val view = page()
        val bytes = photo()
        view.insertPicture("pic-a.jpg", bytes, 400, 200)
        assertTrue(view.lassoing)
        assertEquals(listOf(0), view.selectedPictures)
        assertEquals(View.VISIBLE, view.actions.visibility)
        val p = view.document!!.pages[0].pictures.single()
        assertEquals("twice as wide as tall, as the image", 2f, p.width / p.height, 0.001f)
        assertTrue("on the sheet", p.x >= 0f && p.x + p.width <= 595f && p.y >= 0f)
        // In the middle of what is in view: 800 pixels tall at 2 a point is 400 points from the top.
        assertEquals(196f, p.y + p.height / 2, 1f)
        val image = view.snapshot()
        val red = image.getPixel((8 + (p.x + p.width / 2) * 2).toInt(), (8 + (p.y + p.height / 2) * 2).toInt())
        // The picture's red (BF616A), not the light paper (ECEFF4, red too but blue as well).
        assertTrue("drawn on the page: ${Integer.toHexString(red)}", (red shr 16 and 255) > 150 && (red and 255) < 150)
        view.document!!.undo()
        assertEquals(0, view.document!!.pages[0].pictures.size)
    }

    @Test fun aPictureMovesGrowsAndGoesLikeInk() {
        val view = page()
        view.insertPicture("pic-a.jpg", photo(), 400, 200)
        val p = view.document!!.pages[0].pictures.single()
        val cx = 8 + (p.x + p.width / 2) * 2
        val cy = 8 + (p.y + p.height / 2) * 2
        view.penDown(cx, cy); view.penMove(cx + 40f, cy + 60f); view.penUp()
        val moved = view.document!!.pages[0].pictures.single()
        assertEquals(p.x + 20f, moved.x, 0.01f); assertEquals(p.y + 30f, moved.y, 0.01f)
        // The bottom-right handle, 8 points of room out from the corner.
        val hx = 8 + (moved.x + moved.width + 8f) * 2
        val hy = 8 + (moved.y + moved.height + 8f) * 2
        view.penDown(hx, hy); view.penMove(hx - moved.width, hy - moved.height); view.penUp()
        val smaller = view.document!!.pages[0].pictures.single()
        assertEquals("half as big around the top-left", moved.width / 2, smaller.width, 1f)
        assertEquals(moved.x, smaller.x, 0.01f)
        tap(view, item(view, 2))                        // "Borrar"
        assertEquals(0, view.document!!.pages[0].pictures.size)
    }

    @Test fun theLassoTakesPicturesWithInkAndCopiesThemToo() {
        val view = written()
        view.insertPicture("pic-a.jpg", photo(), 400, 200)
        view.deselect()
        // A loop round the whole page takes both lines and the picture.
        loop(view, 10f, 10f, 1190f, 790f)
        assertEquals(listOf(0, 1), view.selection)
        assertEquals(listOf(0), view.selectedPictures)
        tap(view, item(view, 0))                        // "Copiar"
        view.document!!.addPage()
        view.document = view.document
        view.showPage(1)
        tap(view, view.pasteChip)
        assertEquals(1, view.document!!.pages[1].pictures.size)
        assertEquals(2, view.document!!.pages[1].strokes.size)
    }

    private fun idle(ms: Long) =
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    @Test fun heldNearTheBottomEdgeTheSelectionTakesThePageDown() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        val top = view.offsetY
        view.penDown(200f, 108f)
        view.penMove(200f, 450f)
        view.penMove(200f, 785f)                       // 15 px from the bottom
        idle(300)
        assertEquals("not yet: passing by an edge does nothing", top, view.offsetY, 0.01f)
        idle(300)
        assertTrue("the page moved up under it: ${view.offsetY}", view.offsetY < top - 100f)
        // Under the pen still: the stroke's start is where the pen holds it, as it went.
        idle(2000)
        val stopped = view.offsetY
        idle(500)
        assertEquals("at the sheet's end the page stops: it would leave the selection behind", stopped, view.offsetY, 0.01f)
        view.penUp()
        // Without the page moving it would have gone 677 px, 338.5 points, down: to 388.5.
        val y = strokes(view)[0].y(0)
        assertTrue("further down the sheet than the pen alone took it: $y", y > 388.5f + 300f)
        assertTrue("and still on it", y < 842f)
    }

    @Test fun movingAwayFromTheEdgeStopsThePage() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        val top = view.offsetY
        view.penDown(200f, 108f)
        view.penMove(200f, 785f)
        idle(200)
        view.penMove(200f, 600f)                       // back out before it starts
        idle(1000)
        assertEquals(top, view.offsetY, 0.01f)
        view.penMove(200f, 785f)                       // and in again: the wait starts over
        idle(300)
        assertEquals(top, view.offsetY, 0.01f)
        idle(300)
        assertTrue(view.offsetY < top)
        view.penUp()
    }

    @Test fun aFingerHeldStillOffersPasteThereAndPastesThere() {
        val view = written()
        loop(view, 80f, 80f, 340f, 140f)
        tap(view, item(view, 0))                        // "Copiar"
        view.deselect()
        view.canPaste = { Clipboard.clip != null }
        view.onPasteAt = { x, y -> view.paste(x, y) }
        val top = view.offsetY
        touch(view, MotionEvent.ACTION_DOWN, 600f, 600f, MotionEvent.TOOL_TYPE_FINGER)
        idle(300)
        assertEquals("not yet", View.GONE, view.pasteHere.visibility)
        idle(300)
        assertEquals(View.VISIBLE, view.pasteHere.visibility)
        assertTrue("above the finger", view.pasteHere.y + view.pasteHere.height < 600f)
        touch(view, MotionEvent.ACTION_UP, 600f, 600f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals("holding did not move the page", top, view.offsetY, 0.01f)
        tap(view, view.pasteHere)
        assertEquals(View.GONE, view.pasteHere.visibility)
        assertEquals(3, strokes(view).size)
        // Centred where the finger was: 600 px is (600 - 8) / 2 = 296 points across and down.
        val pasted = strokes(view)[2]
        assertEquals(296f, (pasted.bounds[0] + pasted.bounds[2]) / 2, 0.5f)
        assertEquals(296f, (pasted.bounds[1] + pasted.bounds[3]) / 2, 0.5f)
    }

    @Test fun aFingerThatMovesOrNothingCopiedOffersNoPaste() {
        val view = written()
        var copied = true
        view.canPaste = { copied }
        touch(view, MotionEvent.ACTION_DOWN, 600f, 600f, MotionEvent.TOOL_TYPE_FINGER)
        touch(view, MotionEvent.ACTION_MOVE, 600f, 560f, MotionEvent.TOOL_TYPE_FINGER)   // scrolling
        idle(700)
        assertEquals(View.GONE, view.pasteHere.visibility)
        touch(view, MotionEvent.ACTION_UP, 600f, 560f, MotionEvent.TOOL_TYPE_FINGER)
        copied = false
        touch(view, MotionEvent.ACTION_DOWN, 600f, 600f, MotionEvent.TOOL_TYPE_FINGER)
        idle(700)
        assertEquals(View.GONE, view.pasteHere.visibility)
        touch(view, MotionEvent.ACTION_UP, 600f, 600f, MotionEvent.TOOL_TYPE_FINGER)
        // Offered, then the pen touches the page: put away.
        copied = true
        touch(view, MotionEvent.ACTION_DOWN, 600f, 600f, MotionEvent.TOOL_TYPE_FINGER)
        idle(700)
        touch(view, MotionEvent.ACTION_UP, 600f, 600f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(View.VISIBLE, view.pasteHere.visibility)
        touch(view, MotionEvent.ACTION_DOWN, 300f, 300f, MotionEvent.TOOL_TYPE_STYLUS)
        touch(view, MotionEvent.ACTION_UP, 300f, 300f, MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(View.GONE, view.pasteHere.visibility)
    }
}
