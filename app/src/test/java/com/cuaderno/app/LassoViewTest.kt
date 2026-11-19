package com.cuaderno.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.cuaderno.app.page.PageView
import cuaderno.core.Document
import cuaderno.core.Notebook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // Tapped as a finger would: through the page, which must let the tap reach it.
        view.measure(View.MeasureSpec.makeMeasureSpec(1206, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1206, 800)
        val x = view.actions.x + view.actions.width / 2f
        val y = view.actions.y + view.actions.height / 2f
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }
        android.os.Looper.getMainLooper().let { org.robolectric.Shadows.shadowOf(it).idle() }
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
}
