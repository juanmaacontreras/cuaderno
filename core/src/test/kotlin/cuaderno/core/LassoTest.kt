package cuaderno.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lasso: what a loop takes, and moving or removing what it took. */
class LassoTest {
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, tool: Tool = Tool.PEN): Stroke {
        val b = StrokeBuilder(tool, -1, 1f)
        for (i in 0..10) b.add(x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10, 0.3f + i / 100f, i * 8f, 0.2f, 1.1f)
        return b.build()
    }

    /** A box-shaped loop. */
    private fun loop(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)

    @Test fun aLoopTakesWhatIsMostlyInsideIt() {
        val inside = line(110f, 110f, 190f, 110f)
        val grazed = line(180f, 150f, 400f, 150f)      // a fifth of it inside: the loop grazed it
        val mostly = line(120f, 170f, 240f, 170f)      // most of it inside
        val outside = line(300f, 300f, 400f, 300f)
        val taken = Lasso.select(listOf(inside, grazed, mostly, outside), loop(100f, 100f, 200f, 200f))
        assertEquals(listOf(0, 2), taken)
    }

    @Test fun aLoopNotClosedClosesItselfAndTooFewPointsTakeNothing() {
        val s = line(110f, 110f, 150f, 150f)
        // Three corners of a box: closing it from the last to the first makes a triangle that holds the line.
        assertEquals(listOf(0), Lasso.select(listOf(s), floatArrayOf(100f, 100f, 200f, 100f, 100f, 200f)))
        assertEquals(emptyList<Int>(), Lasso.select(listOf(s), floatArrayOf(100f, 100f, 200f, 200f)))
    }

    @Test fun aDotIsTakenWhenItIsInside() {
        val dot = StrokeBuilder(Tool.PEN, -1, 1f).apply { add(150f, 150f, 0.5f, 0f) }.build()
        assertEquals(listOf(0), Lasso.select(listOf(dot), loop(100f, 100f, 200f, 200f)))
        assertEquals(emptyList<Int>(), Lasso.select(listOf(dot), loop(0f, 0f, 50f, 50f)))
    }

    @Test fun boundsCountTheWidth() {
        val b = Lasso.bounds(listOf(line(10f, 20f, 30f, 20f), line(15f, 5f, 15f, 40f)))
        assertArrayEquals(floatArrayOf(9.5f, 4.5f, 30.5f, 40.5f), b, 0.001f)
    }

    @Test fun movingKeepsTheOrderTheSamplesAndIsOneUndo() {
        val doc = Document(Notebook("n", "t", "c", "m", emptyList()))
        val under = line(100f, 100f, 200f, 100f, Tool.HIGHLIGHTER)
        val ink = line(100f, 100f, 200f, 100f)
        val other = line(100f, 400f, 200f, 400f)
        doc.add(0, under); doc.add(0, ink); doc.add(0, other)
        doc.move(0, listOf(0, 1), 50f, -20f)
        val now = doc.pages[0].strokes
        assertEquals(Tool.HIGHLIGHTER, now[0].tool)
        assertEquals("the highlighter stays under", Tool.PEN, now[1].tool)
        assertEquals(150f, now[1].x(0), 0.001f); assertEquals(80f, now[1].y(0), 0.001f)
        assertEquals("pressure, time and tilt travel with it", ink.pressure(3), now[1].pressure(3))
        assertEquals(ink.time(3), now[1].time(3)); assertEquals(ink.tilt(3), now[1].tilt(3))
        assertTrue("the rest is the very same stroke", now[2] === other)
        doc.undo()
        assertEquals(listOf(under, ink, other), doc.pages[0].strokes)
    }

    @Test fun removingTakesOnlyThoseAndIsOneUndo() {
        val doc = Document(Notebook("n", "t", "c", "m", emptyList()))
        val a = line(0f, 0f, 10f, 0f); val b = line(0f, 10f, 10f, 10f); val c = line(0f, 20f, 10f, 20f)
        doc.add(0, a); doc.add(0, b); doc.add(0, c)
        doc.remove(0, listOf(0, 2))
        assertEquals(listOf(b), doc.pages[0].strokes)
        doc.undo()
        assertEquals(listOf(a, b, c), doc.pages[0].strokes)
        val before = doc.revision
        doc.remove(0, emptyList())
        doc.move(0, listOf(1), 0f, 0f)
        assertEquals("nothing taken or moved is no change", before, doc.revision)
    }
}
