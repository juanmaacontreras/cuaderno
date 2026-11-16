package com.cuaderno.app

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import com.cuaderno.app.ink.OwnSurface
import com.juanma.kit.Ui
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Our own ink: writing, rubbing out, undo, palm rejection, and replaying what a pen sent. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-mdpi")
class OwnSurfaceTest {
    private fun surface(w: Int = 800, h: Int = 600): OwnSurface {
        val view = OwnSurface(RuntimeEnvironment.getApplication() as Context, lowLatency = false)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        return view
    }

    private fun line(view: OwnSurface, x0: Float, y0: Float, x1: Float, y1: Float) {
        view.down(x0, y0, 0.5f, rub = false)
        for (i in 1..10) view.move(x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10, 0.5f)
        view.up()
    }

    private fun touch(view: OwnSurface, action: Int, x: Float, y: Float, tool: Int, buttons: Int = 0) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.7f })
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 1, properties, coords, 0, buttons, 1f, 1f, 0, 0, 0, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun drag(view: OwnSurface, tool: Int, x0: Float, x1: Float, y: Float, buttons: Int = 0) {
        touch(view, MotionEvent.ACTION_DOWN, x0, y, tool, buttons)
        touch(view, MotionEvent.ACTION_MOVE, (x0 + x1) / 2, y, tool, buttons)
        touch(view, MotionEvent.ACTION_MOVE, x1, y, tool, buttons)
        touch(view, MotionEvent.ACTION_UP, x1, y, tool, buttons)
    }

    @Test fun aStrokeLeavesInkWhereItPassedAndNowhereElse() {
        val view = surface()
        line(view, 100f, 100f, 300f, 100f)
        val image = view.snapshot()
        assertEquals(Ui.BRIGHT, image.getPixel(200, 100))
        assertEquals(Ui.BG, image.getPixel(200, 130))
        assertEquals(Ui.BG, image.getPixel(400, 100))
    }

    @Test fun pressingHarderDrawsWider() {
        val view = surface()
        view.widthDp = 4.5f
        view.down(100f, 100f, 0.1f, rub = false); view.move(300f, 100f, 0.1f); view.up()
        view.down(100f, 300f, 1f, rub = false); view.move(300f, 300f, 1f); view.up()
        val image = view.snapshot()
        fun thickness(y: Int) = (y - 10..y + 10).count { image.getPixel(200, it) != Ui.BG }
        assertTrue(thickness(300) > thickness(100) + 2)
    }

    @Test fun theEraserTakesWholeStrokesAndUndoBringsThemBack() {
        val view = surface()
        line(view, 100f, 100f, 300f, 100f)
        line(view, 100f, 400f, 300f, 400f)
        view.down(200f, 105f, 0.5f, rub = true); view.move(210f, 105f, 0.5f); view.up()
        assertEquals(1, view.strokes.size)
        assertEquals(Ui.BG, view.snapshot().getPixel(200, 100))
        view.undo()
        assertEquals(2, view.strokes.size)
        view.undo(); view.undo()
        assertTrue(view.isEmpty)
        assertFalse(view.canUndo)
    }

    @Test fun onceThePenWasSeenThePalmDoesNotWrite() {
        val view = surface()
        drag(view, MotionEvent.TOOL_TYPE_FINGER, 100f, 300f, 100f)
        assertEquals(1, view.strokes.size)  // no pen yet: a finger writes
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, 100f, 300f, 200f)
        drag(view, MotionEvent.TOOL_TYPE_FINGER, 100f, 300f, 300f)
        assertEquals(2, view.strokes.size)
    }

    @Test fun thePenButtonAndTheEraserEndRubOut() {
        val view = surface()
        line(view, 100f, 100f, 300f, 100f)
        line(view, 100f, 300f, 300f, 300f)
        drag(view, MotionEvent.TOOL_TYPE_STYLUS, 150f, 250f, 100f, MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertEquals(1, view.strokes.size)
        drag(view, MotionEvent.TOOL_TYPE_ERASER, 150f, 250f, 300f)
        assertTrue(view.isEmpty)
    }

    /** Every recording kept in the repo replays into the strokes that were written. */
    @Test fun recordingsReplay() {
        assertTrue(Replay.names().isNotEmpty())
        for (name in Replay.names()) {
            val recording = Replay.load(name)
            val view = surface(recording.width, recording.height)
            Replay.into(view, recording)
            val written = recording.samples.count { it.action == 'D' && it.tool != 4 && it.buttons and 0x20 == 0 }
            assertEquals(name, written, view.strokes.size)
            assertTrue(name, view.strokes.all { it.points.size > 1 })
        }
    }

    /**
     * A guard, not a latency number: what our code spends per pen sample
     * (building the piece and drawing it), which has to stay far below the
     * 4 ms between two samples of a 240 Hz pen. It fails if a change makes
     * the drawing path several times slower.
     */
    @Test fun aPenSampleCostsFarLessThanTheTimeBetweenTwo() {
        val recording = Replay.load("synthetic.csv")
        val view = surface(recording.width, recording.height)
        Replay.into(view, recording)  // warm up
        view.clear()
        val start = System.nanoTime()
        repeat(5) { Replay.into(view, recording) }
        val perSample = (System.nanoTime() - start) / 1e6 / (5 * recording.samples.size)
        println("pen sample cost: %.4f ms".format(perSample))
        assertTrue("a pen sample costs $perSample ms", perSample < 1.0)
    }
}
