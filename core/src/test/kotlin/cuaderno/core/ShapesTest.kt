package cuaderno.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Holding the pen tidies a stroke only when it clearly is a line or a circle. */
class ShapesTest {
    private fun stroke(points: List<Pair<Float, Float>>): Stroke {
        val b = StrokeBuilder(Tool.PEN, 0xFF112233.toInt(), 1.1f)
        points.forEachIndexed { i, p -> b.add(p.first, p.second, 0.4f, i * 5f) }
        return b.build()
    }

    private fun ring(turns: Double, wobble: Float = 0.08f, squash: Float = 1f) = stroke((0..80).map { i ->
        val a = turns * 2 * PI * i / 80
        val r = 50f * (1 + wobble * sin(5 * a).toFloat())
        Pair(200f + r * cos(a).toFloat(), 300f + squash * r * sin(a).toFloat())
    })

    @Test fun aShakyLineBecomesStraightBetweenItsEnds() {
        val shaky = stroke((0..40).map { Pair(100f + it * 5f, 200f + it * 2f + 4f * sin(it * 0.7f)) })
        val line = Shapes.tidy(shaky)!!
        assertEquals(2, line.size)
        assertEquals(shaky.x(0), line.x(0)); assertEquals(shaky.y(0), line.y(0))
        assertEquals(shaky.x(40), line.x(1)); assertEquals(shaky.y(40), line.y(1))
        assertEquals(shaky.color, line.color); assertEquals(shaky.width, line.width)
    }

    @Test fun aRoughCircleBecomesARoundOneWhereItWas() {
        val circle = Shapes.tidy(ring(1.0))!!
        for (i in 0 until circle.size) assertEquals(50f, hypot(circle.x(i) - 200f, circle.y(i) - 300f), 2.5f)
        assertEquals(circle.x(0), circle.x(circle.size - 1), 0.01f)   // closed
        assertNotNull(Shapes.tidy(ring(-1.05)))                       // the other way round, a little overlap
        assertNotNull(Shapes.tidy(ring(0.95, squash = 0.8f)))         // slightly oval, not quite closed
    }

    @Test fun whatIsNeitherIsLeftAlone() {
        assertNull(Shapes.tidy(ring(0.5)))                            // an arc
        assertNull(Shapes.tidy(ring(0.75)))                           // a C
        assertNull(Shapes.tidy(ring(2.0)))                            // twice round
        assertNull(Shapes.tidy(ring(1.0, squash = 0.4f)))             // a long oval
        assertNull(Shapes.tidy(stroke((0..40).map { Pair(100f + it * 5f, 200f + if (it % 8 < 4) 0f else 30f) })))  // a zigzag
        assertNull(Shapes.tidy(stroke(listOf(Pair(10f, 10f), Pair(12f, 11f)))))                                  // a tick
        assertNull(Shapes.tidy(stroke((0..40).map { Pair(100f + (if (it < 20) it else 40 - it) * 5f, 200f) }.dropLast(3))))  // there and back
    }
}
