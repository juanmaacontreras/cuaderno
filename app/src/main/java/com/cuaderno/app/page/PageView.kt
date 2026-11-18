package com.cuaderno.app.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.animation.ValueAnimator
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import android.view.View
import android.widget.FrameLayout
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import androidx.graphics.surface.SurfaceControlCompat
import java.util.concurrent.atomic.AtomicBoolean
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer
import cuaderno.core.Document
import cuaderno.core.Shapes
import cuaderno.core.Stroke
import cuaderno.core.StrokeBuilder
import cuaderno.core.Tool
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The page being written on. The pen writes (or rubs out: its button, its
 * eraser end, or [erasing]); fingers only move and zoom the notebook, so a
 * resting palm never leaves ink.
 *
 * Strokes live in the [Document] in page points. The view only keeps where
 * the notebook sits on screen: [scale] pixels per point and the offset of
 * its top-left corner.
 *
 * With [lowLatency] the stroke in progress goes straight to the front buffer
 * (the image the screen is already showing) instead of waiting for the next
 * frame; without it everything is drawn in onDraw, which is what the tests
 * and the renders use.
 *
 * Battery: nothing here runs on a timer. The view draws when the pen or a
 * finger moves, and is otherwise idle.
 */
class PageView(context: Context, lowLatency: Boolean) : FrameLayout(context) {
    /** A piece of the stroke in progress, in pixels, complete in itself so another thread can draw it. */
    private class Piece(val color: Int, val width: Float, val x0: Float, val y0: Float,
                        val cx: Float, val cy: Float, val x1: Float, val y1: Float, val line: Boolean,
                        val alpha: Float = 1f)

    private interface Display {
        val view: View
        fun add(piece: Piece)
        /** The notebook or its place on screen changed: draw it all again. */
        fun redraw()
    }

    var document: Document? = null
        set(value) {
            field = value
            display.redraw()
        }
    var color = Render.INK_ON_LIGHT

    /** Dark sheet with light ink, or light like paper. Only how it is shown: the file does not change. */
    @Volatile var darkPaper = false
        set(value) {
            field = value
            display.redraw()
        }
    /** Line width in page points. */
    var widthPt = 1.1f
    /** Pen or highlighter: what the stylus writes with. */
    var tool = Tool.PEN
    var erasing = false

    /** Called on the main thread after the notebook changes. */
    var onChange: (() -> Unit)? = null

    /**
     * When set, told about once a second while the page is being redrawn: frames shown
     * in that second, the slowest one in milliseconds, and the screen's refresh rate.
     * Only the front-buffer display measures. On the main thread.
     */
    var onFluency: ((frames: Int, worstMs: Int, screenHz: Int) -> Unit)? = null

    /** Called when the pen touches the page, to put away whatever was open over it. */
    var onPenDown: (() -> Unit)? = null

    @Volatile var scale = 0f
        private set
    @Volatile var offsetX = 0f
        private set
    @Volatile var offsetY = 0f
        private set
    private var fit = 1f

    // The pen
    private var penId = -1
    private var builder: StrokeBuilder? = null

    /** A stroke tidied into a shape while the pen is still down: drawn with the notebook, added on lifting. */
    private class Held(val stroke: Stroke, val top: Float)
    @Volatile private var held: Held? = null
    private var holdX = 0f
    private var holdY = 0f
    private val timer = Handler(Looper.getMainLooper())
    private val hold = Runnable { tidy() }
    private var strokePage = 0
    private var strokeTop = 0f
    private var strokeStart = 0L
    private var rubbing = false
    private var rubbed = false
    private var lastX = 0f
    private var lastY = 0f
    private var beforeX = 0f
    private var beforeY = 0f
    private var count = 0

    // Fingers
    private val ignored = HashSet<Int>()
    private var gesture = false
    private var startScale = 0f
    private var startX = 0f
    private var startY = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var startSpan = 0f
    private var tracker: VelocityTracker? = null
    private val scroller = OverScroller(context)
    private var settling: ValueAnimator? = null
    private val ring = Ring()

    /** Whether the eraser's circle is on screen (for the tests). */
    val ringShown: Boolean get() = ring.shown

    private val display: Display = if (lowLatency) FrontBuffer() else Buffered()

    init {
        setBackgroundColor(Render.DESK)
        addView(display.view, LayoutParams(-1, -1))
        addView(ring, LayoutParams(-1, -1))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val margin = MARGIN * resources.displayMetrics.density
        val pageWidth = document?.pages?.firstOrNull()?.width ?: cuaderno.core.Page.A4_WIDTH
        val wasFit = scale == 0f || scale == fit
        fit = (w - 2 * margin) / pageWidth
        if (wasFit) {
            // The sheet as wide as the screen, like paper on a desk.
            scale = fit
            if (oldw <= 0) offsetY = margin
        }
        clamp()
        display.redraw()
    }

    // --- input ------------------------------------------------------------------

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        // Touching the page stops it where it is.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) stopMotion()
        if (penId == -1 && !isPen(event, index)) {
            if (tracker == null) tracker = VelocityTracker.obtain()
            tracker?.addMovement(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (isPen(event, index)) {
                    if (penId != -1) return true
                    // A finger was moving the page: that was the palm landing first. Put the page back.
                    if (gesture) {
                        scale = startScale; offsetX = startX; offsetY = startY
                        gesture = false
                        display.redraw()
                    }
                    for (i in 0 until event.pointerCount) if (i != index) ignored.add(event.getPointerId(i))
                    penId = event.getPointerId(index)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Deliver every sample as it happens instead of once per frame.
                    requestUnbufferedDispatch(event)
                    val rub = erasing || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
                        (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                    penDown(event.getX(index), event.getY(index), rub, event.eventTime,
                        event.getPressure(index), event.getAxisValue(MotionEvent.AXIS_TILT, index), event.getOrientation(index))
                } else {
                    // A finger that lands while the pen writes is the hand: ignored until it lifts.
                    if (penId != -1) ignored.add(event.getPointerId(index))
                    rebase(event, -1)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (penId != -1) {
                    val i = event.findPointerIndex(penId)
                    if (i < 0) return true
                    for (h in 0 until event.historySize) {
                        penMove(event.getHistoricalX(i, h), event.getHistoricalY(i, h), event.getHistoricalEventTime(h),
                            event.getHistoricalPressure(i, h), event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h),
                            event.getHistoricalOrientation(i, h))
                    }
                    penMove(event.getX(i), event.getY(i), event.eventTime, event.getPressure(i),
                        event.getAxisValue(MotionEvent.AXIS_TILT, i), event.getOrientation(i))
                } else {
                    fingers(event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(index)
                if (id == penId) {
                    penUp()
                    penId = -1
                }
                ignored.remove(id)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    ignored.clear()
                    if (gesture) release()
                    gesture = false
                    tracker?.recycle()
                    tracker = null
                } else {
                    rebase(event, index)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (penId != -1) penCancel()
                penId = -1
                ignored.clear()
                if (gesture) release()
                gesture = false
                tracker?.recycle()
                tracker = null
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timer.removeCallbacks(hold)
        stopMotion()
    }

    override fun onInterceptHoverEvent(event: MotionEvent) = true

    /** The pen near the page with the eraser on shows where it would rub out. */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val rub = isPen(event, 0) && (erasing || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0)
        if (rub && event.actionMasked != MotionEvent.ACTION_HOVER_EXIT) ring.show(event.x, event.y) else ring.hide()
        return true
    }

    private fun isPen(event: MotionEvent, index: Int): Boolean {
        val tool = event.getToolType(index)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    // --- the pen (also the entry points the tests use; x and y in pixels) ----------------

    fun penDown(x: Float, y: Float, rub: Boolean = erasing, timeMs: Long = 0, pressure: Float = 0.5f,
                tilt: Float = 0f, orientation: Float = 0f) {
        val doc = document ?: return
        onPenDown?.invoke()
        val pages = doc.pages
        strokePage = Render.pageAt(pages, (y - offsetY) / scale)
        strokeTop = Render.top(pages, strokePage)
        rubbing = rub
        rubbed = false
        if (rub) {
            rubAt(x, y)
            return
        }
        strokeStart = timeMs
        builder = StrokeBuilder(tool, color, widthPt).also {
            it.add(pageX(x), pageY(y), pressure, 0f, tilt, orientation)
        }
        count = 1
        lastX = x; lastY = y
        held = null
        holdX = x; holdY = y
        timer.removeCallbacks(hold)
        timer.postDelayed(hold, HOLD_MS)
        display.add(Piece(liveColor(), widthPt * scale, x, y, x, y, x, y, line = true, alpha = liveAlpha()))
    }

    // The stroke in progress is drawn opaque, piece by piece; a highlighter's whole layer is
    // then shown see-through. See-through pieces would darken where each one overlaps the last.
    private fun liveColor() = if (tool == Tool.HIGHLIGHTER) color else Render.shown(color, darkPaper)
    private fun liveAlpha() = if (tool == Tool.HIGHLIGHTER) Render.highlightAlpha(darkPaper) else 1f

    fun penMove(x: Float, y: Float, timeMs: Long = 0, pressure: Float = 0.5f, tilt: Float = 0f, orientation: Float = 0f) {
        if (rubbing) {
            rubAt(x, y)
            return
        }
        val stroke = builder ?: return
        if (x == lastX && y == lastY) return
        held?.let {
            // A line already straightened follows the pen by its end; a circle stays.
            if (it.stroke.size == 2) {
                held = Held(Shapes.segment(it.stroke, it.stroke.x(0), it.stroke.y(0), pageX(x), pageY(y)), it.top)
                lastX = x; lastY = y
                display.redraw()
            }
            return
        }
        // Still means within a few pixels of where it stopped: a held pen trembles.
        if (hypot(x - holdX, y - holdY) > HOLD_SLOP * resources.displayMetrics.density) {
            holdX = x; holdY = y
            timer.removeCallbacks(hold)
            timer.postDelayed(hold, HOLD_MS)
        }
        stroke.add(pageX(x), pageY(y), pressure, (timeMs - strokeStart).toFloat(), tilt, orientation)
        // The curve between the midpoints around the previous sample: the same shape Render draws later.
        val fromX = if (count == 1) lastX else (beforeX + lastX) / 2
        val fromY = if (count == 1) lastY else (beforeY + lastY) / 2
        display.add(Piece(liveColor(), widthPt * scale, fromX, fromY, lastX, lastY, (lastX + x) / 2, (lastY + y) / 2, line = false, alpha = liveAlpha()))
        beforeX = lastX; beforeY = lastY
        lastX = x; lastY = y
        count++
    }

    fun penUp() {
        val stroke = builder
        builder = null
        ring.hide()
        timer.removeCallbacks(hold)
        val shape = held?.stroke
        held = null
        if (rubbing || stroke == null) {
            rubbing = false
            return
        }
        document?.add(strokePage, shape ?: stroke.build())
        display.redraw()
        onChange?.invoke()
    }

    private fun penCancel() {
        rubbing = false
        ring.hide()
        timer.removeCallbacks(hold)
        held = null
        if (builder != null) {
            builder = null
            display.redraw()
        }
    }

    /** The pen has been still, down, for [HOLD_MS]: if the stroke is a line or a circle, show it clean. */
    private fun tidy() {
        val stroke = builder ?: return
        if (rubbing || held != null) return
        val shape = Shapes.tidy(stroke.build()) ?: return
        held = Held(shape, strokeTop)
        display.redraw()   // hides the hand-drawn stroke in the front layer and draws the shape
    }

    private fun rubAt(x: Float, y: Float) {
        ring.show(x, y)
        val doc = document ?: return
        val reach = RUB * resources.displayMetrics.density / scale
        if (!doc.erase(strokePage, pageX(x), pageY(y), reach, merge = rubbed)) return
        rubbed = true
        display.redraw()
        onChange?.invoke()
    }

    private fun pageX(x: Float) = (x - offsetX) / scale
    private fun pageY(y: Float) = (y - offsetY) / scale - strokeTop

    // --- fingers: move and zoom ------------------------------------------------------

    /** Where the fingers are, leaving out the pen, ignored ones and the one lifting ([leaving]). */
    private fun measure(event: MotionEvent, leaving: Int): Int {
        var n = 0
        var sx = 0f
        var sy = 0f
        var ax = 0f; var ay = 0f; var bx = 0f; var by = 0f
        for (i in 0 until event.pointerCount) {
            if (i == leaving || isPen(event, i) || event.getPointerId(i) in ignored) continue
            if (n == 0) { ax = event.getX(i); ay = event.getY(i) }
            if (n == 1) { bx = event.getX(i); by = event.getY(i) }
            if (n < 2) { sx += event.getX(i); sy += event.getY(i) }
            n++
        }
        if (n == 0) return 0
        val used = minOf(n, 2)
        cx = sx / used; cy = sy / used
        span = if (used == 2) hypot(bx - ax, by - ay) else 0f
        return used
    }
    private var cx = 0f
    private var cy = 0f
    private var span = 0f

    /** The set of fingers changed: measure from here on. */
    private fun rebase(event: MotionEvent, leaving: Int) {
        if (penId != -1 || measure(event, leaving) == 0) {
            gesture = false
            return
        }
        gesture = true
        startScale = scale; startX = offsetX; startY = offsetY
        startCx = cx; startCy = cy; startSpan = span
    }

    private fun fingers(event: MotionEvent) {
        if (!gesture || measure(event, -1) == 0) return
        val next = if (span > 0f && startSpan > 0f) (startScale * span / startSpan).coerceIn(fit * ELASTIC_ZOOM, fit * MAX_ZOOM)
            else startScale
        // The point of the notebook under the fingers stays under them.
        offsetX = cx - (startCx - startX) * next / startScale
        offsetY = cy - (startCy - startY) * next / startScale
        scale = next
        clamp()
        display.redraw()
    }

    /** Moves the notebook by pixels; what a one-finger drag does. */
    fun scrollBy(dx: Float, dy: Float) {
        offsetX += dx; offsetY += dy
        clamp()
        display.redraw()
    }

    /** The fingers let go: a sheet pinched smaller than the screen springs back; otherwise the page coasts. */
    private fun release() {
        if (scale < fit) {
            settle()
            return
        }
        val t = tracker ?: return
        t.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
        val vx = t.xVelocity
        val vy = t.yVelocity
        if (hypot(vx, vy) < ViewConfiguration.get(context).scaledMinimumFlingVelocity) return
        val x = rangeX(scale)
        val y = rangeY(scale)
        scroller.fling(offsetX.toInt(), offsetY.toInt(), vx.toInt(), vy.toInt(),
            x.first.toInt(), x.second.toInt(), y.first.toInt(), y.second.toInt())
        Choreographer.getInstance().postFrameCallback(coast)
    }

    /** One step per screen frame while the page coasts; nothing runs once it has stopped. */
    private val coast = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!scroller.computeScrollOffset()) return
            offsetX = scroller.currX.toFloat()
            offsetY = scroller.currY.toFloat()
            display.redraw()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Back to the sheet as wide as the screen, keeping what is in the middle of the screen there. */
    private fun settle() {
        val fromScale = scale
        val fromX = offsetX
        val fromY = offsetY
        val toY = (height / 2f - (height / 2f - fromY) * fit / fromScale)
            .coerceIn(rangeY(fit).first, rangeY(fit).second)
        val toX = rangeX(fit).second
        settling = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                scale = fromScale + (fit - fromScale) * t
                offsetX = fromX + (toX - fromX) * t
                offsetY = fromY + (toY - fromY) * t
                display.redraw()
            }
            start()
        }
    }

    private fun stopMotion() {
        scroller.forceFinished(true)
        Choreographer.getInstance().removeFrameCallback(coast)
        settling?.let {
            it.cancel()
            settling = null
            // Caught half way back: finish it at once rather than leave the sheet small.
            if (scale < fit) { scale = fit; clamp(); display.redraw() }
        }
    }

    /** Where the notebook's left edge may be at [s] pixels per point: lowest and highest offset. */
    private fun rangeX(s: Float): Pair<Float, Float> {
        val margin = MARGIN * resources.displayMetrics.density
        val contentW = (document?.pages?.firstOrNull()?.width ?: 0f) * s
        if (contentW <= width - 2 * margin) return Pair((width - contentW) / 2, (width - contentW) / 2)
        return Pair(width - contentW - margin, margin)
    }

    private fun rangeY(s: Float): Pair<Float, Float> {
        val margin = MARGIN * resources.displayMetrics.density
        val contentH = Render.height(document?.pages ?: emptyList()) * s
        if (contentH <= height - 2 * margin) return Pair(margin, margin)
        return Pair(height - contentH - margin, margin)
    }

    private fun clamp() {
        if (document == null || width == 0 || scale == 0f) return
        val x = rangeX(scale)
        val y = rangeY(scale)
        offsetX = offsetX.coerceIn(x.first, x.second)
        offsetY = offsetY.coerceIn(y.first, y.second)
    }

    // --- drawing ----------------------------------------------------------------------

    private class Brush {
        val render = Render()
        val tiles = Tiles(render)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun piece(canvas: Canvas, piece: Piece) {
            paint.color = piece.color
            paint.strokeWidth = piece.width
            if (piece.line) {
                if (piece.x0 == piece.x1 && piece.y0 == piece.y1) canvas.drawPoint(piece.x0, piece.y0, paint)
                else canvas.drawLine(piece.x0, piece.y0, piece.x1, piece.y1, paint)
                return
            }
            path.rewind()
            path.moveTo(piece.x0, piece.y0)
            path.quadTo(piece.cx, piece.cy, piece.x1, piece.y1)
            canvas.drawPath(path, paint)
        }
    }

    /**
     * The notebook where it sits on screen. Called from whichever thread draws.
     * True if it is not final yet (pictures still to draw) and wants another frame.
     */
    private fun scene(canvas: Canvas, brush: Brush, width: Int, height: Int, patient: Boolean = false): Boolean {
        canvas.drawColor(Render.DESK)
        val pages = document?.pages ?: return false
        val s = scale
        if (s == 0f) return false
        val x = offsetX
        val y = offsetY
        val dark = darkPaper
        brush.render.dark = dark
        val more = brush.tiles.draw(canvas, pages, dark, x, y, s, width, height, patient)
        held?.let {
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(s, s)
            canvas.translate(0f, it.top)
            brush.render.stroke(canvas, it.stroke)
            canvas.restore()
        }
        return more
    }

    /** The view as an image, for tests and renders. */
    fun snapshot(): android.graphics.Bitmap {
        val image = android.graphics.Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888)
        scene(Canvas(image), Brush(), width, height, patient = true)
        return image
    }

    /**
     * The circle the eraser rubs out within. An ordinary view over the page:
     * a frame behind the pen, which is fine for a guide and keeps it out of
     * the front buffer, where it would leave a trail.
     */
    private inner class Ring : View(context) {
        var shown = false
            private set
        private var cx = 0f
        private var cy = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
            color = 0xFF8792A8.toInt()  // reads on light and dark paper
        }

        fun show(x: Float, y: Float) {
            shown = true
            cx = x; cy = y
            invalidate()
        }

        fun hide() {
            if (!shown) return
            shown = false
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (shown) canvas.drawCircle(cx, cy, RUB * resources.displayMetrics.density, paint)
        }
    }

    /** Everything in onDraw, one frame behind the pen. */
    private inner class Buffered : View(context), Display {
        private val brush = Brush()
        private val live = ArrayList<Piece>()
        override val view: View get() = this

        override fun add(piece: Piece) {
            live.add(piece)
            invalidate()
        }

        override fun redraw() {
            live.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (scene(canvas, brush, width, height)) postInvalidateOnAnimation()
            val alpha = live.firstOrNull()?.alpha ?: 1f
            if (alpha < 1f) canvas.saveLayerAlpha(null, (alpha * 255).toInt())
            for (piece in live) brush.piece(canvas, piece)
            if (alpha < 1f) canvas.restore()
        }
    }

    /**
     * Two layers on one surface. The stroke in progress goes to the front
     * buffer, the image the screen is already showing; the notebook is drawn
     * in the normal, double-buffered layer, and drawing it hides the front
     * one. So a stroke moves from one layer to the other when it ends, and
     * undo, the eraser, moving and zooming redraw only the second.
     */
    private inner class FrontBuffer : Display, CanvasFrontBufferedRenderer.Callback<Piece> {
        private val surface = SurfaceView(context)
        // Both callbacks run on the library's render thread, never the main one.
        private val brush = Brush()
        private val renderer = CanvasFrontBufferedRenderer(surface, this)
        override val view: View get() = surface

        override fun add(piece: Piece) = renderer.renderFrontBufferedLayer(piece)

        // Moving the page asks for a redraw with every finger sample. The library takes
        // each request as a whole new frame and stalls when they pile up, so there is at
        // most one per screen frame and one in flight; a request that arrives meanwhile
        // is drawn when that one is done.
        private var scheduled = false
        private val busy = AtomicBoolean(false)
        private val again = AtomicBoolean(false)
        @Volatile private var busySince = 0L

        override fun redraw() {
            if (scheduled) return
            scheduled = true
            surface.postOnAnimation {
                scheduled = false
                val stuck = SystemClock.uptimeMillis() - busySince > 500
                if (busy.compareAndSet(false, true) || stuck) {
                    busySince = SystemClock.uptimeMillis()
                    renderer.renderMultiBufferedLayer(emptyList())
                } else {
                    again.set(true)
                }
            }
        }

        // Fluency, for the readout: counted on the render thread, reported on the main one.
        private var windowStart = 0L
        private var frames = 0
        private var worst = 0L

        private fun measured(now: Long) {
            val took = now - busySince
            if (now - windowStart > 1500) { windowStart = now; frames = 0; worst = 0 }  // a pause: start over
            frames++
            if (took > worst) worst = took
            if (now - windowStart >= 1000) {
                val f = frames
                val w = worst.toInt()
                windowStart = now; frames = 0; worst = 0
                surface.post { onFluency?.invoke(f, w, (surface.display?.refreshRate ?: 0f).roundToInt()) }
            }
        }

        override fun onMultiBufferedLayerRenderComplete(
            frontBufferedLayerSurfaceControl: SurfaceControlCompat,
            multiBufferedLayerSurfaceControl: SurfaceControlCompat,
            transaction: SurfaceControlCompat.Transaction,
        ) {
            if (onFluency != null) measured(SystemClock.uptimeMillis())
            busy.set(false)
            if (again.getAndSet(false)) surface.post { redraw() }
        }

        @Volatile private var alpha = 1f

        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Piece) {
            alpha = param.alpha
            brush.piece(canvas, param)
        }

        /** The front layer as a whole is see-through for the highlighter, solid for the pen. */
        override fun onFrontBufferedLayerRenderComplete(
            frontBufferedLayerSurfaceControl: SurfaceControlCompat,
            transaction: SurfaceControlCompat.Transaction,
        ) {
            transaction.setAlpha(frontBufferedLayerSurfaceControl, alpha)
        }

        /** [params] are the pieces drawn since the last time; the notebook already holds that stroke. */
        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Piece>) {
            // The view's size: the buffer may be the panel's way round, the canvas is turned to match the view.
            val w = surface.width.takeIf { it > 0 } ?: bufferWidth
            val h = surface.height.takeIf { it > 0 } ?: bufferHeight
            if (scene(canvas, brush, w, h)) surface.post { redraw() }
        }
    }

    private companion object {
        /** dp around the sheet. */
        const val MARGIN = 8f
        /** dp the eraser reaches around the pen tip. */
        const val RUB = 10f
        /** Holding the pen this long, this still (dp), at the end of a stroke tidies it. */
        const val HOLD_MS = 600L
        const val HOLD_SLOP = 4f
        /** While pinching, the sheet can get this much smaller than the screen; letting go brings it back. */
        const val ELASTIC_ZOOM = 0.5f
        const val MAX_ZOOM = 5f
    }
}
