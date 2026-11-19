package cuaderno.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Tool(val code: Int) {
    PEN(0), HIGHLIGHTER(1);

    companion object {
        fun of(code: Int): Tool = entries.firstOrNull { it.code == code }
            ?: throw FormatException("unknown tool $code")
    }
}

/**
 * One stroke of ink, finished and immutable. Positions and [width] are in
 * page points (1/72 inch), never screen pixels, so a page looks the same on
 * any device. Every sample keeps what the pen reported (pressure, time since
 * the stroke began, tilt, orientation) even when the current brush ignores
 * it: a later brush can use it, and it cannot be recovered afterwards.
 */
class Stroke(val tool: Tool, val color: Int, val width: Float, private val data: FloatArray) {
    init {
        require(data.size % STRIDE == 0 && data.isNotEmpty()) { "a stroke needs at least one whole sample" }
    }

    val size: Int get() = data.size / STRIDE

    fun x(i: Int) = data[i * STRIDE]
    fun y(i: Int) = data[i * STRIDE + 1]
    /** 0..1; 0.5 for a finger, which has no real pressure. */
    fun pressure(i: Int) = data[i * STRIDE + 2]
    /** Milliseconds since the first sample of this stroke. */
    fun time(i: Int) = data[i * STRIDE + 3]
    /** Radians from perpendicular to the page; 0 when not reported. */
    fun tilt(i: Int) = data[i * STRIDE + 4]
    /** Radians, the direction the pen points; 0 when not reported. */
    fun orientation(i: Int) = data[i * STRIDE + 5]

    internal fun channel(i: Int, channel: Int) = data[i * STRIDE + channel]

    /** The same stroke [dx], [dy] points away; everything the pen reported stays. */
    fun moved(dx: Float, dy: Float): Stroke {
        val copy = data.copyOf()
        for (i in 0 until size) {
            copy[i * STRIDE] += dx
            copy[i * STRIDE + 1] += dy
        }
        return Stroke(tool, color, width, copy)
    }

    /** left, top, right, bottom of the samples (not counting the stroke's width). */
    val bounds: FloatArray by lazy {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until size) {
            l = min(l, x(i)); t = min(t, y(i)); r = max(r, x(i)); b = max(b, y(i))
        }
        floatArrayOf(l, t, r, b)
    }

    /** Distance from a point to the stroke's centre line. What the eraser and the lasso ask. */
    fun distanceTo(px: Float, py: Float): Float {
        if (size == 1) return hypot(x(0) - px, y(0) - py)
        var best = Float.MAX_VALUE
        for (i in 1 until size) {
            val ax = x(i - 1); val ay = y(i - 1)
            val dx = x(i) - ax; val dy = y(i) - ay
            val length = dx * dx + dy * dy
            val t = if (length == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / length).coerceIn(0f, 1f)
            best = min(best, hypot(px - (ax + t * dx), py - (ay + t * dy)))
        }
        return best
    }

    override fun equals(other: Any?) = other is Stroke && tool == other.tool && color == other.color &&
        width == other.width && data.contentEquals(other.data)

    override fun hashCode() = 31 * (31 * tool.hashCode() + color) + data.contentHashCode()

    companion object {
        const val STRIDE = 6
    }
}

/** Collects the samples of a stroke while it is being written. */
class StrokeBuilder(val tool: Tool, val color: Int, val width: Float) {
    private var data = FloatArray(64 * Stroke.STRIDE)
    var size = 0
        private set

    fun add(x: Float, y: Float, pressure: Float, timeMs: Float, tilt: Float = 0f, orientation: Float = 0f) {
        if ((size + 1) * Stroke.STRIDE > data.size) data = data.copyOf(data.size * 2)
        val at = size * Stroke.STRIDE
        data[at] = x; data[at + 1] = y; data[at + 2] = pressure
        data[at + 3] = timeMs; data[at + 4] = tilt; data[at + 5] = orientation
        size++
    }

    fun build() = Stroke(tool, color, width, data.copyOf(size * Stroke.STRIDE))
}

/** A file this version cannot read. Never swallowed: losing notes silently is the one thing that must not happen. */
class FormatException(message: String) : Exception(message)
