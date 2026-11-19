package com.cuaderno.app.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.widget.TextView
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
import com.cuaderno.app.Strings
import com.juanma.kit.Icons
import com.juanma.kit.Ui
import cuaderno.core.Clip
import cuaderno.core.Document
import cuaderno.core.Lasso
import cuaderno.core.Page
import cuaderno.core.Picture
import cuaderno.core.Selection
import cuaderno.core.Shapes
import cuaderno.core.Stroke
import cuaderno.core.StrokeBuilder
import cuaderno.core.Tool
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The page being written on. The pen writes (or rubs out: its button, its
 * eraser end, or [erasing]; or, with [lassoing], draws a loop that takes
 * strokes); fingers only move and zoom the notebook, so a resting palm never
 * leaves ink.
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
            // Undo, redo or another notebook: what was taken may not be there any more.
            deselect()
            redraw()
        }
    var color = Render.INK_ON_LIGHT

    /** Dark sheet with light ink, or light like paper. Only how it is shown: the file does not change. */
    @Volatile var darkPaper = false
        set(value) {
            field = value
            redraw()
        }
    /** Line width in page points. */
    var widthPt = 1.1f
    /** Pen or highlighter: what the stylus writes with. */
    var tool = Tool.PEN
    var erasing = false

    /**
     * The pen draws a loop; what it takes can be moved (pen or finger), made bigger or smaller
     * by a corner, recoloured ([recolor]), copied, cut, deleted, and pasted elsewhere.
     */
    var lassoing = false
        set(value) {
            field = value
            if (!value) deselect()
            marks.update()
        }

    /** Called on the main thread when what the lasso took changes (the bar rings its colour). */
    var onSelect: (() -> Unit)? = null

    /** Strokes the lasso took: their page and their indices in it. */
    private class Picked(val page: Int, val which: Selection)
    private var picked: Picked? = null
    /** The indices the lasso took on [selectedPage], for the tests. */
    val selection: List<Int> get() = picked?.which?.strokes ?: emptyList()
    /** The indices of the pictures the lasso took on [selectedPage], for the tests. */
    val selectedPictures: List<Int> get() = picked?.which?.pictures ?: emptyList()
    val selectedPage: Int get() = picked?.page ?: -1

    /** The loop being drawn: page points of [strokePage], and the same on screen. */
    private var loop = FloatArray(256)
    private var loopSize = 0
    private val loopPath = Path()
    private var looping = false

    /**
     * The taken strokes while they are being dragged: drawn on their own, following the
     * pen or finger, over the notebook without them ([pages], the same list every frame,
     * so the kept pictures are drawn again once and not on every move).
     */
    private class Lift(val page: Int, val top: Float, val strokes: List<Stroke>, val pictures: List<Picture>, val pages: List<Page>,
                       val minX: Float, val maxX: Float, val minY: Float, val maxY: Float,
                       /** Resizing: the content's corner held, the one pulled (page points), the limits. */
                       val corner: Int, val ax: Float, val ay: Float, val cx: Float, val cy: Float,
                       val minK: Float, val maxK: Float)
    @Volatile private var lift: Lift? = null
    @Volatile private var liftX = 0f
    @Volatile private var liftY = 0f
    /** How many times as big, around the lift's held corner. */
    @Volatile private var liftK = 1f
    private var dragFromX = 0f
    private var dragFromY = 0f
    /** The finger moving the selection, or -1 (the pen moves it through [penId]). */
    private var dragFinger = -1

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

    /**
     * Pulling past the last page with a finger and letting go far enough asks for a new page
     * (as in Samsung Notes). Whoever listens adds it to the notebook and sets [document] again.
     */
    var onAddPage: (() -> Unit)? = null
    /** How far past the last page the finger has pulled the notebook, pixels; 0 when it has not. */
    @Volatile private var pull = 0f
    private val enough get() = PULL * resources.displayMetrics.density

    /** Told the page in the middle of the screen whenever that changes. On the main thread. */
    var onPageShown: ((Int) -> Unit)? = null
    private var shownPage = -1

    /** The page in the middle of the screen. */
    val currentPage: Int
        get() {
            val pages = document?.pages ?: return 0
            if (scale == 0f) return 0
            return Render.pageAt(pages, (height / 2f - offsetY) / scale)
        }

    /** Brings [index]'s top to the top of the screen, at the zoom it has. */
    fun showPage(index: Int) {
        val pages = document?.pages ?: return
        if (index !in pages.indices || scale == 0f) return
        stopMotion()
        offsetY = MARGIN * resources.displayMetrics.density - Render.top(pages, index) * scale
        clamp()
        redraw()
    }

    /** Every change to what is shown comes through here: the display, and who wants to know the page. */
    private fun redraw() {
        display.redraw()
        marks.update()
        val now = currentPage
        if (now != shownPage) {
            shownPage = now
            onPageShown?.invoke(now)
        }
    }

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
    private val marks = Marks()
    /** What can be done with what the lasso took, floating over it. */
    val actions: View = Ui.row(context).apply {
        background = Ui.rounded(Ui.RAISED, 14f, context)
        val d = resources.displayMetrics.density
        setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
        elevation = 8 * d
        for ((label, color, action) in listOf(
            Triple(Strings.COPY, Ui.TEXT) { copySelection() },
            Triple(Strings.CUT, Ui.TEXT) { copySelection(); deleteSelection() },
            Triple(Strings.DELETE, Ui.RED) { deleteSelection() })) {
            addView(Ui.text(context, label, 15f, color, bold = true).apply {
                setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
                setOnClickListener { action() }
            })
        }
        visibility = GONE
    }

    /** "Pegar", over the page while there is something copied and the lasso is in hand. */
    val pasteChip: View = Ui.row(context).apply {
        val d = resources.displayMetrics.density
        background = Ui.rounded(Ui.ACCENT, 22f, context)
        setPadding((18 * d).toInt(), (10 * d).toInt(), (22 * d).toInt(), (10 * d).toInt())
        elevation = 8 * d
        gravity = Gravity.CENTER_VERTICAL
        addView(View(context).apply { background = Icons.drawable("pages", Ui.BG) },
            android.widget.LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()))
        addView(Ui.text(context, Strings.PASTE, 16f, Ui.BG, bold = true).apply { setPadding((10 * d).toInt(), 0, 0, 0) })
        setOnClickListener { paste() }
        visibility = GONE
    }

    init {
        setBackgroundColor(Render.DESK)
        addView(display.view, LayoutParams(-1, -1))
        addView(ring, LayoutParams(-1, -1))
        addView(marks, LayoutParams(-1, -1))
        addView(actions, LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        addView(pasteChip, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (20 * resources.displayMetrics.density).toInt()
        })
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
        redraw()
    }

    // --- input ------------------------------------------------------------------

    /** A touch that began on the selection's actions or "Pegar": theirs until it lifts, so the tap lands. */
    private var onActions = false

    private fun over(view: View, event: MotionEvent) = view.visibility == VISIBLE &&
        event.x >= view.x && event.x <= view.x + view.width && event.y >= view.y && event.y <= view.y + view.height

    /** Everything is the page's, except a touch that lands on the selection's actions or "Pegar". */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onActions = penId == -1 && (over(actions, event) || over(pasteChip, event))
        }
        return !onActions
    }

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
                    // A finger was moving the selection: the palm again. Put it back.
                    if (dragFinger != -1) {
                        dragFinger = -1
                        cancelDrag()
                    }
                    // A finger was moving the page: that was the palm landing first. Put the page back.
                    if (gesture) {
                        scale = startScale; offsetX = startX; offsetY = startY
                        pull = 0f
                        gesture = false
                        redraw()
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
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && penId == -1 &&
                        (handleAt(event.x, event.y) >= 0 || onSelection(event.x, event.y))) {
                        // One finger on what the lasso took moves it, or by a corner resizes it.
                        dragFinger = event.getPointerId(index)
                        startDrag(event.x, event.y, handleAt(event.x, event.y))
                        return true
                    }
                    if (dragFinger != -1) {
                        // A second finger: zooming, not moving the selection. It stays where it got to.
                        dragFinger = -1
                        drop()
                    }
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
                } else if (dragFinger != -1) {
                    val i = event.findPointerIndex(dragFinger)
                    if (i >= 0) dragTo(event.getX(i), event.getY(i))
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
                if (id == dragFinger) {
                    dragFinger = -1
                    drop()
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
                if (dragFinger != -1) { dragFinger = -1; cancelDrag() }
                if (pull > 0f) { pull = 0f; clamp(); redraw() }
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
        if (lassoing) {
            val corner = handleAt(x, y)
            if (corner >= 0 || onSelection(x, y)) {
                startDrag(x, y, corner)
            } else {
                deselect()
                looping = true
                loopSize = 0
                loopPath.rewind()
                loopPath.moveTo(x, y)
                addLoop(x, y)
                marks.update()
            }
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
        if (lift != null) {
            dragTo(x, y)
            return
        }
        if (looping) {
            loopPath.lineTo(x, y)
            addLoop(x, y)
            marks.update()
            return
        }
        val stroke = builder ?: return
        if (x == lastX && y == lastY) return
        held?.let {
            // A line already straightened follows the pen by its end; a circle stays.
            if (it.stroke.size == 2) {
                held = Held(Shapes.segment(it.stroke, it.stroke.x(0), it.stroke.y(0), pageX(x), pageY(y)), it.top)
                lastX = x; lastY = y
                redraw()
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
        if (lift != null) {
            drop()
            return
        }
        if (looping) {
            looping = false
            take()
            return
        }
        if (rubbing || stroke == null) {
            rubbing = false
            return
        }
        document?.add(strokePage, shape ?: stroke.build())
        redraw()
        onChange?.invoke()
    }

    private fun penCancel() {
        rubbing = false
        if (looping) { looping = false; marks.update() }
        if (lift != null) cancelDrag()
        ring.hide()
        timer.removeCallbacks(hold)
        held = null
        if (builder != null) {
            builder = null
            redraw()
        }
    }

    /** The pen has been still, down, for [HOLD_MS]: if the stroke is a line or a circle, show it clean. */
    private fun tidy() {
        val stroke = builder ?: return
        if (rubbing || held != null) return
        val shape = Shapes.tidy(stroke.build()) ?: return
        held = Held(shape, strokeTop)
        redraw()   // hides the hand-drawn stroke in the front layer and draws the shape
    }

    private fun rubAt(x: Float, y: Float) {
        ring.show(x, y)
        val doc = document ?: return
        val reach = RUB * resources.displayMetrics.density / scale
        if (!doc.erase(strokePage, pageX(x), pageY(y), reach, merge = rubbed)) return
        rubbed = true
        redraw()
        onChange?.invoke()
    }

    // --- the lasso -------------------------------------------------------------------

    private fun addLoop(x: Float, y: Float) {
        if (loopSize + 2 > loop.size) loop = loop.copyOf(loop.size * 2)
        loop[loopSize++] = pageX(x)
        loop[loopSize++] = pageY(y)
    }

    /** The loop is closed: take what is mostly inside it on its page. */
    private fun take() {
        val page = document?.pages?.getOrNull(strokePage)
        val closed = loop.copyOf(loopSize)
        val which = if (page == null) Selection(emptyList())
            else Selection(Lasso.select(page.strokes, closed), Lasso.selectPictures(page.pictures, closed))
        picked = if (which.isEmpty) null else Picked(strokePage, which)
        marks.update()
        onSelect?.invoke()
    }

    /** Lets go of what the lasso took. True if something was taken. */
    fun deselect(): Boolean {
        val had = picked != null || looping
        picked = null
        looping = false
        if (lift != null) cancelDrag()
        if (had) {
            marks.update()
            onSelect?.invoke()
        }
        return had
    }

    /** What was taken, ink and highlighter as they are on the page. */
    private fun taken(): List<Stroke> {
        val p = picked ?: return emptyList()
        val strokes = document?.pages?.getOrNull(p.page)?.strokes ?: return emptyList()
        return p.which.strokes.mapNotNull { strokes.getOrNull(it) }
    }

    /** The pictures taken, as they are on the page. */
    private fun takenPictures(): List<Picture> {
        val p = picked ?: return emptyList()
        val pictures = document?.pages?.getOrNull(p.page)?.pictures ?: return emptyList()
        return p.which.pictures.mapNotNull { pictures.getOrNull(it) }
    }

    /** The colour of what was taken, if its ink is all one colour (the highlighter when there is no ink). */
    val selectionColor: Int?
        get() {
            val strokes = taken()
            val ink = strokes.filter { it.tool == Tool.PEN }.ifEmpty { strokes }
            return ink.map { it.color }.distinct().singleOrNull()
        }

    /**
     * Gives what was taken [color]: its ink, or its highlighter when there is no ink (the
     * pen's colours on a highlighter would read as ink). One undo. False if nothing is taken.
     */
    fun recolor(color: Int): Boolean {
        val p = picked ?: return false
        val strokes = document?.pages?.getOrNull(p.page)?.strokes ?: return false
        val inkOnly = p.which.strokes.filter { strokes.getOrNull(it)?.tool == Tool.PEN }
        document?.recolor(p.page, inkOnly.ifEmpty { p.which.strokes }, color)
        redraw()
        onChange?.invoke()
        onSelect?.invoke()
        return true
    }

    /** The content's bounds (strokes and their width, pictures), page points, before any drag. */
    private fun content(): FloatArray? {
        val strokes = taken()
        val pictures = takenPictures()
        return if (strokes.isEmpty() && pictures.isEmpty()) null else Lasso.bounds(strokes, pictures)
    }

    /** The box on screen, following a drag or a resize. */
    private fun screenBox(out: RectF): Boolean {
        val p = picked ?: return false
        val b = content() ?: return false
        val pages = document?.pages ?: return false
        val top = Render.top(pages, p.page)
        val l = lift
        var x0 = b[0]; var y0 = b[1]; var x1 = b[2]; var y1 = b[3]
        if (l != null) {
            val k = liftK
            x0 = l.ax + (x0 - l.ax) * k + liftX; x1 = l.ax + (x1 - l.ax) * k + liftX
            y0 = l.ay + (y0 - l.ay) * k + liftY; y1 = l.ay + (y1 - l.ay) * k + liftY
        }
        val pad = BOX_PAD
        out.set(offsetX + (x0 - pad) * scale, offsetY + (top + y0 - pad) * scale,
            offsetX + (x1 + pad) * scale, offsetY + (top + y1 + pad) * scale)
        return true
    }

    /** The corner handle under a point on screen: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right; -1 none. */
    private fun handleAt(x: Float, y: Float): Int {
        if (!screenBox(hit)) return -1
        val reach = HANDLE_HIT * resources.displayMetrics.density
        val corners = listOf(hit.left to hit.top, hit.right to hit.top, hit.left to hit.bottom, hit.right to hit.bottom)
        var best = -1
        var nearest = reach
        for ((n, c) in corners.withIndex()) {
            val d = hypot(x - c.first, y - c.second)
            if (d <= nearest) { nearest = d; best = n }
        }
        return best
    }

    private val hit = RectF()

    /** Whether a point on screen is on what was taken (its box, a little wider for a finger). */
    private fun onSelection(x: Float, y: Float): Boolean {
        if (!screenBox(hit)) return false
        val slop = HIT * resources.displayMetrics.density
        hit.inset(-slop, -slop)
        return hit.contains(x, y)
    }

    /** Lifts what was taken to drag it from [x], [y], or to resize it by [corner] (see [handleAt]; -1 moves). */
    private fun startDrag(x: Float, y: Float, corner: Int = -1) {
        val p = picked ?: return
        val pages = document?.pages ?: return
        val page = pages.getOrNull(p.page) ?: return
        val c = content() ?: return
        val pad = BOX_PAD
        val set = p.which.strokes.toHashSet()
        val pics = p.which.pictures.toHashSet()
        val without = pages.mapIndexed { i, pg ->
            if (i == p.page) pg.with(pg.strokes.filterIndexed { k, _ -> k !in set }, pg.pictures.filterIndexed { k, _ -> k !in pics }) else pg
        }
        dragFromX = x; dragFromY = y
        liftX = 0f; liftY = 0f; liftK = 1f
        // The pulled corner and the opposite one, held.
        val right = corner == 1 || corner == 3
        val bottom = corner == 2 || corner == 3
        val cx = if (right) c[2] else c[0]; val cy = if (bottom) c[3] else c[1]
        val ax = if (right) c[0] else c[2]; val ay = if (bottom) c[1] else c[3]
        val w = c[2] - c[0]; val h = c[3] - c[1]
        // Not smaller than a few points, and not out of the sheet.
        val minK = (MIN_SIZE / maxOf(w, h, 0.01f)).coerceAtMost(1f)
        val maxK = minOf(
            if (w > 0f) (if (right) page.width - ax else ax) / w else Float.MAX_VALUE,
            if (h > 0f) (if (bottom) page.height - ay else ay) / h else Float.MAX_VALUE).coerceAtLeast(1f)
        // The box stays on its sheet: ink pushed off the page would be there but never seen.
        lift = Lift(p.page, Render.top(pages, p.page), taken(), takenPictures(), without,
            minOf(0f, pad - c[0]), maxOf(0f, page.width - pad - c[2]), minOf(0f, pad - c[1]), maxOf(0f, page.height - pad - c[3]),
            corner, ax, ay, cx, cy, minK, maxK)
        actions.visibility = GONE
        redraw()
    }

    private fun dragTo(x: Float, y: Float) {
        val l = lift ?: return
        val dx = (x - dragFromX) / scale
        val dy = (y - dragFromY) / scale
        if (l.corner < 0) {
            liftX = dx.coerceIn(l.minX, l.maxX)
            liftY = dy.coerceIn(l.minY, l.maxY)
        } else {
            // Same proportions: how far along its diagonal the pulled corner went.
            val vx = l.cx - l.ax; val vy = l.cy - l.ay
            val length = vx * vx + vy * vy
            liftK = if (length == 0f) 1f else (((vx + dx) * vx + (vy + dy) * vy) / length).coerceIn(l.minK, l.maxK)
        }
        redraw()
    }

    /** Leaves the dragged strokes where they are now: one undo step. */
    private fun drop() {
        val l = lift ?: return
        val p = picked
        val dx = liftX; val dy = liftY; val k = liftK
        lift = null
        if (p != null && l.corner < 0 && (dx != 0f || dy != 0f)) {
            document?.move(l.page, p.which, dx, dy)
            onChange?.invoke()
        }
        if (p != null && l.corner >= 0 && k != 1f) {
            document?.scale(l.page, p.which, l.ax, l.ay, k)
            onChange?.invoke()
        }
        redraw()
    }

    /**
     * Puts a picture of [w] x [h] pixels in the middle of what is in view, no wider than most of
     * the sheet, taken (with the lasso in hand) so it can be moved and sized like ink. One undo.
     */
    fun insertPicture(name: String, bytes: ByteArray, w: Int, h: Int) {
        val doc = document ?: return
        if (scale == 0f || w <= 0 || h <= 0) return
        val pages = doc.pages
        val n = currentPage
        val page = pages[n]
        val top = Render.top(pages, n)
        val pad = BOX_PAD
        // Most of the sheet's width, and no taller than the part of it in view.
        val k = minOf((page.width - 2 * pad) * 0.7f / w, (height / scale) * 0.6f / h, (page.height - 2 * pad) / h)
        val pw = w * k
        val ph = h * k
        val cx = (width / 2f - offsetX) / scale
        val cy = (height / 2f - offsetY) / scale - top
        val x = (cx - pw / 2).coerceIn(pad, maxOf(pad, page.width - pad - pw))
        val y = (cy - ph / 2).coerceIn(pad, maxOf(pad, page.height - pad - ph))
        val index = doc.insert(n, Picture(name, bytes, x, y, pw, ph))
        lassoing = true
        picked = Picked(n, Selection(emptyList(), listOf(index)))
        redraw()
        onChange?.invoke()
        onSelect?.invoke()
    }

    private fun copySelection() {
        Clipboard.clip = Clip.of(taken(), takenPictures())
        marks.update()
    }

    /**
     * What was copied, in the middle of what is in view on the page there, taken so it can be
     * dragged into place. Then "Pegar" goes away (until something else is copied).
     */
    fun paste() {
        val clip = Clipboard.clip ?: return
        val doc = document ?: return
        val pages = doc.pages
        if (scale == 0f) return
        val n = currentPage
        val page = pages[n]
        val top = Render.top(pages, n)
        val cx = (width / 2f - offsetX) / scale
        val cy = (height / 2f - offsetY) / scale - top
        val pad = BOX_PAD
        val x = (cx - clip.width / 2).coerceIn(pad, maxOf(pad, page.width - pad - clip.width))
        val y = (cy - clip.height / 2).coerceIn(pad, maxOf(pad, page.height - pad - clip.height))
        val which = doc.paste(n, clip.at(x, y), clip.picturesAt(x, y))
        Clipboard.clip = null
        picked = Picked(n, which)
        redraw()
        onChange?.invoke()
        onSelect?.invoke()
    }

    /** The drag was the palm: everything back where it was. */
    private fun cancelDrag() {
        if (lift == null) return
        lift = null
        redraw()
    }

    private fun deleteSelection() {
        val p = picked ?: return
        deselect()
        document?.remove(p.page, p.which)
        redraw()
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
        if (!gesture) return
        val used = measure(event, -1)
        if (used == 0) return
        val next = if (span > 0f && startSpan > 0f) (startScale * span / startSpan).coerceIn(fit * ELASTIC_ZOOM, fit * MAX_ZOOM)
            else startScale
        // The point of the notebook under the fingers stays under them.
        offsetX = cx - (startCx - startX) * next / startScale
        offsetY = cy - (startCy - startY) * next / startScale
        scale = next
        val bottom = rangeY(next).first
        if (used == 1 && next >= fit && offsetY < bottom) {
            // Past the last page: the notebook follows at half the finger, and not much further than enough.
            pull = ((bottom - offsetY) * 0.5f).coerceAtMost(enough * 1.3f)
            clamp()
            offsetY = bottom - pull
        } else {
            pull = 0f
            clamp()
        }
        redraw()
    }

    /** The finger let go of a pull past the last page: a new page if it went far enough, back otherwise. */
    private fun letGo() {
        val from = pull
        val fromY = offsetY
        val add = from >= enough && onAddPage != null
        if (add) onAddPage?.invoke()
        pull = 0f
        val pages = document?.pages ?: return
        // To the new page's top, or back to the end of the notebook.
        val toY = if (add) (MARGIN * resources.displayMetrics.density - Render.top(pages, pages.size - 1) * scale)
            .coerceIn(rangeY(scale).first, rangeY(scale).second) else rangeY(scale).first
        settling = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (add) 320 else 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                if (!add) pull = from * (1 - t)
                offsetY = fromY + (toY - fromY) * t
                redraw()
            }
            start()
        }
    }

    /** Moves the notebook by pixels; what a one-finger drag does. */
    fun scrollBy(dx: Float, dy: Float) {
        offsetX += dx; offsetY += dy
        clamp()
        redraw()
    }

    /** The fingers let go: a sheet pinched smaller than the screen springs back; otherwise the page coasts. */
    private fun release() {
        if (pull > 0f) {
            letGo()
            return
        }
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
            redraw()
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
                redraw()
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
            // Caught half way back: finish it at once rather than leave the sheet small or pulled.
            if (scale < fit || pull > 0f) { scale = maxOf(scale, fit); pull = 0f; clamp(); redraw() }
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
        val pullRing = Paint(Paint.ANTI_ALIAS_FLAG)
        val pullText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

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
        val lifted = lift
        val more = brush.tiles.draw(canvas, lifted?.pages ?: pages, dark, x, y, s, width, height, patient)
        lifted?.let {
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(s, s)
            canvas.translate(0f, it.top)
            val page = it.pages[it.page]
            canvas.clipRect(0f, 0f, page.width, page.height)
            canvas.translate(liftX, liftY)
            val k = liftK
            if (k != 1f) {
                canvas.translate(it.ax, it.ay)
                canvas.scale(k, k)
                canvas.translate(-it.ax, -it.ay)
            }
            // Pictures under the highlighter, the highlighter under the ink, as on the page.
            for (picture in it.pictures) brush.render.picture(canvas, picture)
            for (stroke in it.strokes) if (stroke.tool == Tool.HIGHLIGHTER) brush.render.stroke(canvas, stroke)
            for (stroke in it.strokes) if (stroke.tool != Tool.HIGHLIGHTER) brush.render.stroke(canvas, stroke)
            canvas.restore()
        }
        held?.let {
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(s, s)
            canvas.translate(0f, it.top)
            brush.render.stroke(canvas, it.stroke)
            canvas.restore()
        }
        val p = pull
        if (p > 0f) pulled(canvas, brush, y + Render.height(pages) * s, height.toFloat(), p)
        return more
    }

    /**
     * Below the last page while it is pulled: a "+" and what letting go will do. It fills in
     * once the pull is enough. Drawn in the space between the sheet's end and the screen's.
     */
    private fun pulled(canvas: Canvas, brush: Brush, top: Float, bottom: Float, p: Float) {
        if (bottom - top < 1f) return
        val d = resources.displayMetrics.density
        val ready = p >= enough
        val fade = (p / enough).coerceIn(0f, 1f)
        val cx = width / 2f
        val r = 22f * d
        val cy = (top + bottom) / 2f - 14f * d
        val ring = brush.pullRing
        ring.alpha = 255
        if (ready) {
            ring.style = Paint.Style.FILL
            ring.color = Ui.ACCENT
        } else {
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = 2f * d
            ring.color = Ui.DIM
        }
        ring.alpha = (255 * fade).toInt()
        canvas.drawCircle(cx, cy, r, ring)
        ring.style = Paint.Style.STROKE
        ring.strokeWidth = 2.5f * d
        ring.strokeCap = Paint.Cap.ROUND
        ring.color = if (ready) Ui.BG else Ui.TEXT
        ring.alpha = (255 * fade).toInt()
        canvas.drawLine(cx - 9f * d, cy, cx + 9f * d, cy, ring)
        canvas.drawLine(cx, cy - 9f * d, cx, cy + 9f * d, ring)
        val text = brush.pullText
        text.textSize = 15f * d
        text.color = if (ready) Ui.TEXT else Ui.DIM
        text.alpha = (255 * fade).toInt()
        canvas.drawText(if (ready) Strings.PULL_LET_GO else Strings.PULL_MORE, cx, cy + r + 26f * d, text)
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

    /**
     * The lasso's loop while it is drawn and the dashed box around what it took. An
     * ordinary view over the page, like [Ring]: a guide, kept out of the front buffer.
     * Also places [actions] over the box.
     */
    private inner class Marks : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * d
            color = Ui.ACCENT
            pathEffect = DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f)
        }
        private val r = RectF()
        private var shown = false

        fun update() {
            val now = looping || picked != null
            if (now || shown) invalidate()
            shown = now
            place()
            val paste = lassoing && Clipboard.clip != null && !looping && lift == null
            val want = if (paste) VISIBLE else GONE
            if (pasteChip.visibility != want) pasteChip.visibility = want
        }

        private fun place() {
            val visible = picked != null && lift == null && !looping && screenBox(r)
            if (!visible) {
                if (actions.visibility != GONE) actions.visibility = GONE
                return
            }
            if (actions.measuredWidth == 0) actions.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            val w = actions.measuredWidth
            val h = actions.measuredHeight
            val gap = 18f * d
            // Above the box; below it when there is no room; always on screen.
            val above = r.top - h - gap
            val y = if (above >= gap) above else minOf(r.bottom + gap, this@PageView.height - h - gap)
            actions.translationX = (r.centerX() - w / 2f).coerceIn(gap, maxOf(gap, this@PageView.width - w - gap))
            actions.translationY = y.coerceAtLeast(0f)
            if (actions.visibility != VISIBLE) actions.visibility = VISIBLE
        }

        override fun onDraw(canvas: Canvas) {
            // The light blue is lost on light paper: the deep one there.
            paint.color = if (darkPaper) Ui.ACCENT else Ui.ACCENT_DEEP
            if (looping) canvas.drawPath(loopPath, paint)
            if (!screenBox(r)) return
            canvas.drawRoundRect(r, 6f * d, 6f * d, paint)
            // A handle at each corner to make it bigger or smaller.
            val ring = paint.color
            for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
                handle.style = Paint.Style.FILL; handle.color = Ui.BRIGHT
                canvas.drawCircle(x, y, 9f * d, handle)
                handle.style = Paint.Style.STROKE; handle.color = ring; handle.strokeWidth = 3f * d
                canvas.drawCircle(x, y, 9f * d, handle)
            }
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
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
        /** How far past the last page to pull for a new one, dp. */
        const val PULL = 96f
        /** Points of room between what the lasso took and its box. */
        const val BOX_PAD = 8f
        /** dp around the box that still count as on it, for a finger. */
        const val HIT = 12f
        /** dp from a corner handle that still take it. */
        const val HANDLE_HIT = 24f
        /** Points: what the lasso took is not made smaller than this. */
        const val MIN_SIZE = 4f
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
