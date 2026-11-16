package com.cuaderno.app

/**
 * The last few thousand delays an engine reported, to show a median and a
 * 95th percentile. Adding one is a store into an array: it is called from
 * the drawing path, which must stay cheap.
 */
class Latency {
    private val values = FloatArray(4096)
    private var next = 0
    var count = 0
        private set

    @Synchronized fun add(ms: Float) {
        values[next] = ms
        next = (next + 1) % values.size
        if (count < values.size) count++
    }

    @Synchronized fun reset() {
        next = 0
        count = 0
    }

    /** median and 95th percentile, or null with nothing measured. */
    @Synchronized fun summary(): Pair<Float, Float>? {
        if (count == 0) return null
        val sorted = values.copyOf(count).also { it.sort() }
        return Pair(sorted[count / 2], sorted[minOf(count - 1, (count * 0.95f).toInt())])
    }
}
