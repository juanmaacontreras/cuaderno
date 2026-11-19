package cuaderno.core

/**
 * What "Copiar" or "Cortar" keeps until it is pasted, on any page of any notebook: the
 * strokes with the top-left of their box (width counted) at 0, 0, so they can be put
 * anywhere. Immutable.
 */
class Clip private constructor(val strokes: List<Stroke>, val width: Float, val height: Float) {
    /** The strokes with the box's top-left at [x], [y]. */
    fun at(x: Float, y: Float): List<Stroke> = strokes.map { it.moved(x, y) }

    companion object {
        fun of(strokes: List<Stroke>): Clip? {
            if (strokes.isEmpty()) return null
            val b = Lasso.bounds(strokes)
            return Clip(strokes.map { it.moved(-b[0], -b[1]) }, b[2] - b[0], b[3] - b[1])
        }
    }
}
