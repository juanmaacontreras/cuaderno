package com.cuaderno.app.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import androidx.graphics.lowlatency.LowLatencyCanvasView
import com.juanma.kit.Ui
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.hypot

/**
 * Our own ink: the stroke from Enlace's board (the one Juanma said feels like
 * Samsung Notes), shown one of two ways. Classic draws into a bitmap and
 * waits for the next frame, exactly as the board did. Low latency draws each
 * new piece straight into the front buffer (the image the screen is already
 * scanning out), skipping that wait.
 *
 * Bench code: positions are screen pixels and nothing is saved. The real
 * page (phase 1) keeps strokes in cuaderno.core, in page points.
 */
class OwnSurface(context: Context, lowLatency: Boolean) : PenHost(context), Surface {
    class Point(val x: Float, val y: Float, val pressure: Float)

    class Stroke(val color: Int, val width: Float) {
        val points = ArrayList<Point>()
    }

    /** A piece of a stroke, complete in itself so another thread can draw it. */
    private class Piece(
        val kind: Int, val color: Int, val width: Float,
        val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val nanos: Long,
    )

    /** Paint and path are not thread-safe: each thread that draws has its own brush. */
    private class Brush {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun draw(canvas: Canvas, piece: Piece) {
            paint.color = piece.color
            paint.strokeWidth = piece.width
            when (piece.kind) {
                DOT -> canvas.drawPoint(piece.x0, piece.y0, paint)
                LINE -> canvas.drawLine(piece.x0, piece.y0, piece.x1, piece.y1, paint)
                else -> {
                    path.rewind()
                    path.moveTo(piece.x0, piece.y0)
                    path.quadTo(piece.x1, piece.y1, piece.x2, piece.y2)
                    canvas.drawPath(path, paint)
                }
            }
        }

        fun drawAll(canvas: Canvas, stroke: Stroke) {
            for (i in stroke.points.indices) piece(stroke, i, 0)?.let { draw(canvas, it) }
            tail(stroke, 0)?.let { draw(canvas, it) }
        }
    }

    private interface Display {
        val view: View
        fun add(piece: Piece)
        /** The committed strokes changed (a stroke ended, undo, eraser): draw the page again. */
        fun redraw()
    }

    override val view: View get() = this
    override var color = Ui.BRIGHT
    override var widthDp = 2.5f
    override var onChange: (() -> Unit)? = null
    override var onLatency: ((Float) -> Unit)? = null

    @Volatile var strokes: List<Stroke> = emptyList()
        private set
    private val history = ArrayList<List<Stroke>>()
    private var current: Stroke? = null
    private var rubbing = false
    private var rubbed = false
    private val display: Display = if (lowLatency) FrontBuffer() else Buffered()

    override val isEmpty: Boolean get() = strokes.isEmpty()
    override val canUndo: Boolean get() = history.isNotEmpty()

    init {
        setBackgroundColor(Ui.BG)
        addView(display.view, LayoutParams(-1, -1))
    }

    // --- input ----------------------------------------------------------------

    override fun penDown(event: MotionEvent, index: Int, pen: Boolean, rub: Boolean) =
        down(event.getX(index), event.getY(index), pressure(event.getPressure(index), pen), rub, nanos(event))

    override fun penMove(event: MotionEvent, index: Int, pen: Boolean) {
        for (h in 0 until event.historySize) {
            move(event.getHistoricalX(index, h), event.getHistoricalY(index, h),
                pressure(event.getHistoricalPressure(index, h), pen), nanos(event, h))
        }
        move(event.getX(index), event.getY(index), pressure(event.getPressure(index), pen), nanos(event))
    }

    override fun penUp(event: MotionEvent, index: Int) = up()

    override fun penCancel(event: MotionEvent) {
        rubbing = false
        if (current != null) {
            current = null
            display.redraw()
        }
    }

    // --- strokes (also the entry points the tests use) ----------------------------

    fun down(x: Float, y: Float, pressure: Float, rub: Boolean = erasing, nanos: Long = 0) {
        rubbing = rub
        rubbed = false
        if (rub) {
            rubAt(x, y)
            return
        }
        val stroke = Stroke(color, widthDp * resources.displayMetrics.density)
        stroke.points.add(Point(x, y, pressure))
        current = stroke
        piece(stroke, 0, nanos)?.let { display.add(it) }
    }

    fun move(x: Float, y: Float, pressure: Float, nanos: Long = 0) {
        if (rubbing) {
            rubAt(x, y)
            return
        }
        val stroke = current ?: return
        val last = stroke.points.last()
        if (last.x == x && last.y == y) return
        stroke.points.add(Point(x, y, pressure))
        piece(stroke, stroke.points.size - 1, nanos)?.let { display.add(it) }
    }

    fun up() {
        val stroke = current
        current = null
        if (rubbing || stroke == null) {
            rubbing = false
            return
        }
        history.add(strokes)
        strokes = strokes + stroke
        display.redraw()
        onChange?.invoke()
    }

    private fun rubAt(x: Float, y: Float) {
        val reach = 12f * resources.displayMetrics.density
        val kept = strokes.filterNot { touches(it, x, y, reach + it.width) }
        if (kept.size == strokes.size) return
        // One undo step per rubbing gesture, however many strokes it took.
        if (!rubbed) history.add(strokes)
        rubbed = true
        strokes = kept
        display.redraw()
        onChange?.invoke()
    }

    private fun touches(stroke: Stroke, x: Float, y: Float, reach: Float): Boolean {
        val points = stroke.points
        if (points.size == 1) return hypot(points[0].x - x, points[0].y - y) <= reach
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val dx = points[i].x - a.x
            val dy = points[i].y - a.y
            val length = dx * dx + dy * dy
            val t = if (length == 0f) 0f else (((x - a.x) * dx + (y - a.y) * dy) / length).coerceIn(0f, 1f)
            if (hypot(x - (a.x + t * dx), y - (a.y + t * dy)) <= reach) return true
        }
        return false
    }

    override fun undo() {
        if (history.isEmpty()) return
        strokes = history.removeAt(history.size - 1)
        display.redraw()
        onChange?.invoke()
    }

    override fun clear() {
        if (strokes.isEmpty()) return
        history.add(strokes)
        strokes = emptyList()
        display.redraw()
        onChange?.invoke()
    }

    /** The page as an image, for tests and renders. */
    fun snapshot(): Bitmap {
        val image = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Ui.BG)
        val brush = Brush()
        for (stroke in strokes) brush.drawAll(canvas, stroke)
        return image
    }

    // --- the two ways to show it ----------------------------------------------------

    /** As Enlace's board: pieces go into a bitmap, the bitmap goes out with the next frame. */
    private inner class Buffered : View(context), Display {
        private var bitmap: Bitmap? = null
        private var ink: Canvas? = null
        private val brush = Brush()
        private val waiting = LongArray(256)
        private var waitingCount = 0

        override val view: View get() = this

        override fun add(piece: Piece) {
            ink?.let { brush.draw(it, piece) }
            if (waitingCount < waiting.size) waiting[waitingCount++] = piece.nanos
            invalidate()
        }

        override fun redraw() {
            val canvas = ink ?: return
            bitmap?.eraseColor(0)
            for (stroke in strokes) brush.drawAll(canvas, stroke)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            if (w <= 0 || h <= 0) return
            val fresh = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = fresh
            ink = Canvas(fresh)
            redraw()
        }

        override fun onDraw(canvas: Canvas) {
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            val report = onLatency
            if (report != null) {
                val now = System.nanoTime()
                for (i in 0 until waitingCount) if (waiting[i] != 0L) report((now - waiting[i]) / 1e6f)
            }
            waitingCount = 0
        }
    }

    /** Pieces go straight to the front buffer; a finished stroke moves to the normal, double-buffered page. */
    private inner class FrontBuffer : Display, LowLatencyCanvasView.Callback {
        private val canvasView = LowLatencyCanvasView(context)
        private val queue = ConcurrentLinkedQueue<Piece>()
        // Both callbacks run on the library's render thread, never the main one.
        private val brush = Brush()

        override val view: View get() = canvasView

        init {
            canvasView.setRenderCallback(this)
        }

        override fun add(piece: Piece) {
            queue.add(piece)
            canvasView.renderFrontBufferedLayer()
        }

        override fun redraw() = canvasView.commit()

        override fun onDrawFrontBufferedLayer(canvas: Canvas, width: Int, height: Int) {
            val report = onLatency
            while (true) {
                val piece = queue.poll() ?: break
                brush.draw(canvas, piece)
                if (report != null && piece.nanos != 0L) report((System.nanoTime() - piece.nanos) / 1e6f)
            }
        }

        override fun onRedrawRequested(canvas: Canvas, width: Int, height: Int) {
            canvas.drawColor(Ui.BG)
            for (stroke in strokes) brush.drawAll(canvas, stroke)
        }
    }

    private companion object {
        const val DOT = 0
        const val LINE = 1
        const val CURVE = 2

        fun width(stroke: Stroke, pressure: Float) = stroke.width * (0.4f + 1.2f * pressure)

        /**
         * The piece of [stroke] that point [index] adds: a curve between the
         * midpoints around the previous point, so corners come out round, as
         * wide as the pressure there. The first point alone is a dot.
         */
        fun piece(stroke: Stroke, index: Int, nanos: Long): Piece? {
            val points = stroke.points
            if (index == 0) {
                val p = points[0]
                return Piece(DOT, stroke.color, width(stroke, p.pressure), p.x, p.y, 0f, 0f, 0f, 0f, nanos)
            }
            val a = points[index - 1]
            val b = points[index]
            val fromX: Float
            val fromY: Float
            if (index == 1) {
                fromX = a.x; fromY = a.y
            } else {
                val before = points[index - 2]
                fromX = (before.x + a.x) / 2; fromY = (before.y + a.y) / 2
            }
            return Piece(CURVE, stroke.color, width(stroke, (a.pressure + b.pressure) / 2),
                fromX, fromY, a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2, nanos)
        }

        /** The last half segment, which the curves leave undrawn until the stroke ends. */
        fun tail(stroke: Stroke, nanos: Long): Piece? {
            val points = stroke.points
            if (points.size < 2) return null
            val a = points[points.size - 2]
            val b = points[points.size - 1]
            return Piece(LINE, stroke.color, width(stroke, b.pressure),
                (a.x + b.x) / 2, (a.y + b.y) / 2, b.x, b.y, 0f, 0f, nanos)
        }
    }
}
