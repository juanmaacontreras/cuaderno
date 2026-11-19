package cuaderno.core

/**
 * What a loop drawn with the lasso takes (phase 3, approved 2026-11-19). A stroke is taken
 * when most of its samples are inside the loop, so a loop that grazes a word neither takes
 * it nor leaves it half taken. The loop closes by itself from its last point to its first.
 */
object Lasso {
    /** Of the samples, the share that must be inside. */
    const val MOST = 0.5f

    /**
     * The indices of [strokes] the loop takes, in their order. [loop] is x, y pairs in the
     * same points as the strokes; fewer than three points take nothing.
     */
    fun select(strokes: List<Stroke>, loop: FloatArray): List<Int> {
        if (loop.size < 6) return emptyList()
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (k in loop.indices step 2) {
            l = minOf(l, loop[k]); r = maxOf(r, loop[k]); t = minOf(t, loop[k + 1]); b = maxOf(b, loop[k + 1])
        }
        return strokes.indices.filter { n ->
            val s = strokes[n]
            val box = s.bounds
            // Wholly outside the loop's box: no sample can be in.
            if (box[2] < l || box[0] > r || box[3] < t || box[1] > b) return@filter false
            var inside = 0
            for (i in 0 until s.size) if (contains(loop, s.x(i), s.y(i))) inside++
            inside > s.size * MOST
        }
    }

    /** Whether a point is inside the closed loop (even-odd rule, so a loop drawn twice round still works). */
    fun contains(loop: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        val n = loop.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = loop[2 * i]; val yi = loop[2 * i + 1]
            val xj = loop[2 * j]; val yj = loop[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** left, top, right, bottom around [strokes], counting their width, in their points. */
    fun bounds(strokes: List<Stroke>): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (s in strokes) {
            val box = s.bounds
            val half = s.width / 2
            l = minOf(l, box[0] - half); t = minOf(t, box[1] - half)
            r = maxOf(r, box[2] + half); b = maxOf(b, box[3] + half)
        }
        return floatArrayOf(l, t, r, b)
    }
}
