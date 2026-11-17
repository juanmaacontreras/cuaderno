package cuaderno.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Turns a hand-drawn stroke into the clean shape it was meant to be: a
 * straight line or a circle. Used when the pen is held still at the end of a
 * stroke. Anything that is not clearly one of the two is left alone: a wrong
 * guess is worse than none. Coordinates in page points.
 */
object Shapes {
    /** The stroke as a line or a circle, or null if it is neither. */
    fun tidy(stroke: Stroke): Stroke? = line(stroke) ?: circle(stroke)

    /** A straight line from the first sample to the last, if the stroke never strays far from it. */
    fun line(stroke: Stroke): Stroke? {
        val n = stroke.size
        if (n < 2) return null
        val x0 = stroke.x(0); val y0 = stroke.y(0)
        val x1 = stroke.x(n - 1); val y1 = stroke.y(n - 1)
        val length = hypot(x1 - x0, y1 - y0)
        if (length < MIN_SIZE) return null
        var travelled = 0f
        var off = 0f
        for (i in 1 until n) {
            travelled += hypot(stroke.x(i) - stroke.x(i - 1), stroke.y(i) - stroke.y(i - 1))
            val d = abs((stroke.x(i) - x0) * (y1 - y0) - (stroke.y(i) - y0) * (x1 - x0)) / length
            if (d > off) off = d
        }
        // Went back and forth, or bowed out like an arc: not a line.
        if (travelled > 1.3f * length || off > maxOf(2f, 0.1f * length)) return null
        return segment(stroke, x0, y0, x1, y1)
    }

    /** A circle, if the stroke goes once round a middle and ends near where it began. */
    fun circle(stroke: Stroke): Stroke? {
        val n = stroke.size
        if (n < 8) return null
        val b = stroke.bounds
        val w = b[2] - b[0]; val h = b[3] - b[1]
        if (w < MIN_SIZE || h < MIN_SIZE || w > 1.6f * h || h > 1.6f * w) return null
        val fit = fit(stroke) ?: return null
        val cx = fit[0]; val cy = fit[1]; val r = fit[2]
        var turned = 0.0
        var before = atan2((stroke.y(0) - cy).toDouble(), (stroke.x(0) - cx).toDouble())
        for (i in 0 until n) {
            val d = hypot(stroke.x(i) - cx, stroke.y(i) - cy)
            if (d < 0.6f * r || d > 1.4f * r) return null
            val angle = atan2((stroke.y(i) - cy).toDouble(), (stroke.x(i) - cx).toDouble())
            var step = angle - before
            if (step > PI) step -= 2 * PI
            if (step < -PI) step += 2 * PI
            turned += step
            before = angle
        }
        // Once round, give or take: not a C, not a spiral.
        if (abs(turned) < 1.7 * PI || abs(turned) > 2.6 * PI) return null
        if (hypot(stroke.x(n - 1) - stroke.x(0), stroke.y(n - 1) - stroke.y(0)) > 0.8f * r) return null

        val start = atan2((stroke.y(0) - cy).toDouble(), (stroke.x(0) - cx).toDouble())
        val direction = if (turned >= 0) 1 else -1
        val out = StrokeBuilder(stroke.tool, stroke.color, stroke.width)
        val duration = stroke.time(n - 1)
        for (i in 0..CIRCLE_STEPS) {
            val angle = start + direction * 2 * PI * i / CIRCLE_STEPS
            out.add(cx + r * cos(angle).toFloat(), cy + r * sin(angle).toFloat(), 0.5f, duration * i / CIRCLE_STEPS)
        }
        return out.build()
    }

    /**
     * The circle closest to all the samples (Kåsa's least squares), as centre x, y and
     * radius. Unlike the middle of the bounds, a wobble on one side does not shift it.
     */
    private fun fit(stroke: Stroke): FloatArray? {
        val n = stroke.size
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += stroke.x(i); my += stroke.y(i) }
        mx /= n; my /= n
        var suu = 0.0; var svv = 0.0; var suv = 0.0
        var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (i in 0 until n) {
            val u = stroke.x(i) - mx; val v = stroke.y(i) - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-9) return null
        val a = (suuu + suvv) / 2; val c = (svvv + svuu) / 2
        val uc = (a * svv - c * suv) / det
        val vc = (c * suu - a * suv) / det
        val r = kotlin.math.sqrt(uc * uc + vc * vc + (suu + svv) / n)
        return floatArrayOf((mx + uc).toFloat(), (my + vc).toFloat(), r.toFloat())
    }

    /** A straight stroke with [like]'s tool, colour and width. */
    fun segment(like: Stroke, x0: Float, y0: Float, x1: Float, y1: Float): Stroke {
        val out = StrokeBuilder(like.tool, like.color, like.width)
        out.add(x0, y0, 0.5f, 0f)
        out.add(x1, y1, 0.5f, if (like.size > 0) like.time(like.size - 1) else 0f)
        return out.build()
    }

    /** Smaller than this (about 3.5 mm) it is a dot or a letter's tick, not a shape. */
    private const val MIN_SIZE = 10f
    private const val CIRCLE_STEPS = 72
}
