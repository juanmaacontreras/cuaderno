package com.cuaderno.app.ink

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/** What the bench needs from an ink engine, whichever one draws. */
interface Surface {
    val view: View
    var color: Int
    var widthDp: Float
    var erasing: Boolean
    val canUndo: Boolean
    val isEmpty: Boolean
    fun undo()
    fun clear()

    /** Called after anything changes what is on the page. */
    var onChange: (() -> Unit)?

    /**
     * Called with the milliseconds between the pen reporting a sample and the
     * engine finishing the drawing calls for it. It does not include the
     * display, so it compares engines; it is not the delay the eye sees.
     * May be called from any thread, many times per frame: keep it cheap.
     */
    var onLatency: ((Float) -> Unit)?
}

/**
 * The part every engine shares: which pointer writes. Once a stylus has been
 * seen (touching or hovering), fingers stop writing: that is the palm resting
 * on the tablet. The stylus button, its eraser end, or [erasing] rub out.
 */
abstract class PenHost(context: Context) : FrameLayout(context) {
    var erasing = false
    private var activeId = -1
    private var activeIsPen = false
    private var stylusSeen = false

    protected abstract fun penDown(event: MotionEvent, index: Int, pen: Boolean, rub: Boolean)
    protected abstract fun penMove(event: MotionEvent, index: Int, pen: Boolean)
    protected abstract fun penUp(event: MotionEvent, index: Int)
    protected abstract fun penCancel(event: MotionEvent)

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (isPen(event, 0)) stylusSeen = true
        return super.onHoverEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent) = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pen = isPen(event, index)
                if (pen) stylusSeen = true
                // The pen arrives while a finger (the palm) is writing: drop that stroke.
                if (pen && activeId != -1 && !activeIsPen) {
                    penCancel(event)
                    activeId = -1
                }
                if (activeId != -1 || (!pen && stylusSeen)) return true
                activeId = event.getPointerId(index)
                activeIsPen = pen
                parent?.requestDisallowInterceptTouchEvent(true)
                // Deliver every sample as it happens instead of once per frame.
                requestUnbufferedDispatch(event)
                val rub = erasing || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER ||
                    (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                penDown(event, index, pen, rub)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activeId)
                if (i >= 0) penMove(event, i, activeIsPen)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(index) == activeId) {
                    penUp(event, index)
                    activeId = -1
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (activeId != -1) penCancel(event)
                activeId = -1
            }
        }
        return true
    }

    companion object {
        fun isPen(event: MotionEvent, index: Int): Boolean {
            val tool = event.getToolType(index)
            return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        }

        /** A finger reports its contact area as pressure, which is noise: fixed width for it. */
        fun pressure(raw: Float, pen: Boolean) = if (pen) raw.coerceIn(0f, 1f) else 0.5f

        /** When the pen reported historical sample [h] (or the latest, h = -1), on the System.nanoTime clock. */
        fun nanos(event: MotionEvent, h: Int = -1): Long = when {
            Build.VERSION.SDK_INT >= 34 -> if (h < 0) event.eventTimeNanos else event.getHistoricalEventTimeNanos(h)
            else -> (if (h < 0) event.eventTime else event.getHistoricalEventTime(h)) * 1_000_000
        }
    }
}
