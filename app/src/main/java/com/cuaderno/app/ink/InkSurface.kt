package com.cuaderno.app.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.ExperimentalLatencyDataApi
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.authoring.latency.LatencyDataCallback
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.input.motionprediction.MotionEventPredictor
import com.juanma.kit.Ui
import kotlin.math.hypot

/**
 * Google's ink (Jetpack Ink, androidx.ink): the stroke being written is drawn
 * by the library in the front buffer, with the pen's movement predicted a
 * few milliseconds ahead; finished strokes are drawn by the view below.
 */
@OptIn(ExperimentalLatencyDataApi::class)
class InkSurface(context: Context) : PenHost(context), Surface {
    override val view: View get() = this
    override var color = Ui.BRIGHT
    override var widthDp = 2.5f
    override var onChange: (() -> Unit)? = null
    override var onLatency: ((Float) -> Unit)? = null

    private var strokes: List<Stroke> = emptyList()
    private val history = ArrayList<List<Stroke>>()
    private var currentId: InProgressStrokeId? = null
    private var rubbing = false
    private var rubbed = false

    private val renderer = CanvasStrokeRenderer.create()
    private val identity = Matrix()
    private val scratch = StrokeInput()
    private val finished = object : View(context) {
        override fun onDraw(canvas: Canvas) {
            for (stroke in strokes) renderer.draw(canvas, stroke, identity)
        }
    }
    private val inProgress = InProgressStrokesView(context)
    private val predictor = MotionEventPredictor.newInstance(this)

    override val isEmpty: Boolean get() = strokes.isEmpty()
    override val canUndo: Boolean get() = history.isNotEmpty()

    init {
        setBackgroundColor(Ui.BG)
        addView(finished, LayoutParams(-1, -1))
        addView(inProgress, LayoutParams(-1, -1))
        inProgress.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                history.add(this@InkSurface.strokes)
                this@InkSurface.strokes = this@InkSurface.strokes + strokes.values
                finished.invalidate()
                // Same turn of the main thread as the invalidate, or the stroke flickers.
                inProgress.removeFinishedStrokes(strokes.keys)
                onChange?.invoke()
            }
        })
        // The library's own latency probe is not public API yet (restricted to its
        // group). The bench uses it anyway, to put a number next to the other two
        // engines; nothing in the real app may depend on it.
        @SuppressLint("RestrictedApi")
        inProgress.setLatencyDataCallback(LatencyDataCallback { data ->
            // Which field carries the time depends on how the library is rendering.
            val front = data.canvasFrontBufferStrokesRenderHelperData.finishesDrawCalls
            val hwui = data.hwuiInProgressStrokesRenderHelperData.finishesDrawCalls
            val drawn = if (front != Long.MIN_VALUE) front else hwui
            val ms = if (drawn == Long.MIN_VALUE) -1f else (drawn - data.osDetectsEvent) / 1e6f
            // Predicted samples carry a time in the future: not a real delay.
            if (ms > 0f && ms < 1000f) onLatency?.invoke(ms)
        })
    }

    override fun penDown(event: MotionEvent, index: Int, pen: Boolean, rub: Boolean) {
        rubbing = rub
        rubbed = false
        if (rub) {
            rubAt(event.getX(index), event.getY(index))
            return
        }
        predictor.record(event)
        // The stock pen is thinnest at no pressure and reaches its size pressing hard.
        val size = widthDp * resources.displayMetrics.density * 1.6f
        val brush = Brush.createWithColorIntArgb(StockBrushes.pressurePen(), color, size, 0.1f)
        currentId = inProgress.startStroke(event, event.getPointerId(index), brush)
    }

    override fun penMove(event: MotionEvent, index: Int, pen: Boolean) {
        if (rubbing) {
            for (h in 0 until event.historySize) rubAt(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
            rubAt(event.getX(index), event.getY(index))
            return
        }
        val id = currentId ?: return
        predictor.record(event)
        val predicted = predictor.predict()
        try {
            inProgress.addToStroke(event, event.getPointerId(index), id, predicted)
        } finally {
            predicted?.recycle()
        }
    }

    override fun penUp(event: MotionEvent, index: Int) {
        val id = currentId
        currentId = null
        rubbing = false
        if (id != null) inProgress.finishStroke(event, event.getPointerId(index), id)
    }

    override fun penCancel(event: MotionEvent) {
        val id = currentId
        currentId = null
        rubbing = false
        if (id != null) inProgress.cancelStroke(id, event)
    }

    private fun rubAt(x: Float, y: Float) {
        val reach = 12f * resources.displayMetrics.density
        val kept = strokes.filterNot { touches(it, x, y, reach + it.brush.size / 2) }
        if (kept.size == strokes.size) return
        if (!rubbed) history.add(strokes)
        rubbed = true
        strokes = kept
        finished.invalidate()
        onChange?.invoke()
    }

    private fun touches(stroke: Stroke, x: Float, y: Float, reach: Float): Boolean {
        val inputs = stroke.inputs
        if (inputs.size == 0) return false
        inputs.populate(0, scratch)
        var ax = scratch.x
        var ay = scratch.y
        if (inputs.size == 1) return hypot(ax - x, ay - y) <= reach
        for (i in 1 until inputs.size) {
            inputs.populate(i, scratch)
            val dx = scratch.x - ax
            val dy = scratch.y - ay
            val length = dx * dx + dy * dy
            val t = if (length == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / length).coerceIn(0f, 1f)
            if (hypot(x - (ax + t * dx), y - (ay + t * dy)) <= reach) return true
            ax = scratch.x
            ay = scratch.y
        }
        return false
    }

    override fun undo() {
        if (history.isEmpty()) return
        strokes = history.removeAt(history.size - 1)
        finished.invalidate()
        onChange?.invoke()
    }

    override fun clear() {
        if (strokes.isEmpty()) return
        history.add(strokes)
        strokes = emptyList()
        finished.invalidate()
        onChange?.invoke()
    }
}
