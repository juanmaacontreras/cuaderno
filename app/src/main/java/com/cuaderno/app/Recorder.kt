package com.cuaderno.app

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.widget.FrameLayout
import cuaderno.core.Recording

/**
 * Wraps the page and, while [recording], writes down every sample the pen
 * sends on its way through (see cuaderno.core.Recording). It only looks: the
 * events reach the page untouched. A finger is recorded only until a stylus
 * shows up, so a resting palm does not end up in the file.
 */
class Recorder(context: Context) : FrameLayout(context) {
    private val samples = ArrayList<Recording.Sample>()
    private var start = 0L
    private var stylusSeen = false
    var recording = false
        private set

    val count: Int get() = samples.size

    fun begin() {
        samples.clear()
        start = 0L
        stylusSeen = false
        recording = true
    }

    fun end(): Recording {
        recording = false
        val metrics = resources.displayMetrics
        return Recording(Build.MODEL ?: "unknown", width, height, metrics.density, ArrayList(samples))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (recording) note(event)
        return super.dispatchTouchEvent(event)
    }

    private fun note(event: MotionEvent) {
        if (start == 0L) start = event.downTime
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 'D'
            MotionEvent.ACTION_MOVE -> 'M'
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 'U'
            MotionEvent.ACTION_CANCEL -> 'C'
            else -> return
        }
        if (action == 'M') {
            for (i in 0 until event.pointerCount) {
                if (!wanted(event, i)) continue
                for (h in 0 until event.historySize) {
                    add('M', event.getHistoricalEventTime(h), event.getHistoricalX(i, h), event.getHistoricalY(i, h),
                        event.getHistoricalPressure(i, h), event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h),
                        event.getHistoricalOrientation(i, h), event, i)
                }
                sample('M', event, i)
            }
        } else if (wanted(event, event.actionIndex)) {
            sample(action, event, event.actionIndex)
        }
    }

    private fun wanted(event: MotionEvent, index: Int): Boolean {
        val tool = event.getToolType(index)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
            stylusSeen = true
            return true
        }
        return !stylusSeen && event.getPointerId(index) == 0
    }

    private fun sample(action: Char, event: MotionEvent, i: Int) = add(action, event.eventTime, event.getX(i),
        event.getY(i), event.getPressure(i), event.getAxisValue(MotionEvent.AXIS_TILT, i), event.getOrientation(i), event, i)

    private fun add(action: Char, time: Long, x: Float, y: Float, pressure: Float, tilt: Float, orientation: Float,
                    event: MotionEvent, i: Int) {
        samples.add(Recording.Sample(action, time - start, x, y, pressure, tilt, orientation,
            event.getToolType(i), event.buttonState))
    }
}
