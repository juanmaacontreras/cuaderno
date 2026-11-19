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

/** Slice 2: bigger or smaller, another colour, copy and paste. */
class LassoEditTest {
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, tool: Tool = Tool.PEN, color: Int = -1): Stroke {
        val b = StrokeBuilder(tool, color, 2f)
        for (i in 0..4) b.add(x0 + (x1 - x0) * i / 4, y0 + (y1 - y0) * i / 4, 0.4f, i * 5f, 0.3f, 0.9f)
        return b.build()
    }

    private fun doc(vararg strokes: Stroke) = Document(Notebook("n", "t", "c", "m", emptyList())).apply {
        for (s in strokes) add(0, s)
    }

    @Test fun scalingGrowsAroundTheAnchorWidthToo() {
        val doc = doc(line(10f, 10f, 30f, 10f), line(100f, 100f, 200f, 100f))
        doc.scale(0, listOf(0), 10f, 10f, 2f)
        val s = doc.pages[0].strokes[0]
        assertEquals(10f, s.x(0), 0.001f); assertEquals(50f, s.x(4), 0.001f); assertEquals(10f, s.y(4), 0.001f)
        assertEquals(4f, s.width, 0.001f)
        assertEquals("the pen's record stays", 0.3f, s.tilt(2), 0.001f)
        assertEquals(100f, doc.pages[0].strokes[1].x(0), 0.001f)
        doc.undo()
        assertEquals(30f, doc.pages[0].strokes[0].x(4), 0.001f)
    }

    @Test fun recolouringIsOneUndoAndNothingWhenTheColourIsTheSame() {
        val doc = doc(line(0f, 0f, 10f, 0f, color = 1), line(0f, 5f, 10f, 5f, color = 2))
        doc.recolor(0, listOf(0, 1), 2)
        assertEquals(listOf(2, 2), doc.pages[0].strokes.map { it.color })
        val r = doc.revision
        doc.recolor(0, listOf(0, 1), 2)
        assertEquals(r, doc.revision)
        doc.undo()
        assertEquals(listOf(1, 2), doc.pages[0].strokes.map { it.color })
    }

    @Test fun aClipKeepsTheShapeAndPastesAnywhereOnTop() {
        val a = line(100f, 200f, 140f, 200f); val b = line(100f, 220f, 100f, 260f)
        val clip = Clip.of(listOf(a, b))!!
        assertEquals(42f, clip.width, 0.001f); assertEquals(62f, clip.height, 0.001f)
        val doc = doc(line(0f, 0f, 5f, 0f))
        val at = doc.paste(0, clip.at(10f, 20f))
        assertEquals(listOf(1, 2), at)
        val pasted = doc.pages[0].strokes[1]
        assertEquals("box corner at 10, 20; the line starts its half-width in", 11f, pasted.x(0), 0.001f)
        assertEquals(21f, pasted.y(0), 0.001f)
        doc.undo()
        assertEquals(1, doc.pages[0].strokes.size)
        assertEquals(null, Clip.of(emptyList()))
    }
}

/** Slice 3: pictures go wherever ink goes, and nothing that copies a page leaves them behind. */
class PicturesTest {
    private val bytes = ByteArray(32) { it.toByte() }
    private fun picture(x: Float, y: Float) = Picture(Picture.nameFor(bytes, "jpg"), bytes, x, y, 100f, 50f)
    private fun doc() = Document(Notebook("n", "t", "c", "m", emptyList()))

    @Test fun pagesThatAreCopiedOrRepaperedKeepTheirPictures() {
        val doc = doc()
        doc.insert(0, picture(10f, 10f))
        doc.duplicate(0)
        doc.paper(1, Paper.RULED)
        assertEquals(1, doc.pages[1].pictures.size)
        assertEquals(Paper.RULED, doc.pages[1].paper)
        doc.addPage()
        doc.insert(2, picture(0f, 0f))
        assertEquals("a last page with only a picture is not blank", 3,
            Document(doc.notebook("m")).pages.size)
    }

    @Test fun theLassoTakesAPictureByItsMiddleAndMovesScalesAndRemovesIt() {
        val doc = doc()
        doc.insert(0, picture(100f, 100f))           // middle at 150, 125
        val loop = floatArrayOf(120f, 100f, 180f, 100f, 180f, 150f, 120f, 150f)
        assertEquals(listOf(0), Lasso.selectPictures(doc.pages[0].pictures, loop))
        assertEquals(emptyList<Int>(), Lasso.selectPictures(doc.pages[0].pictures, floatArrayOf(0f, 0f, 120f, 0f, 120f, 120f)))
        val sel = Selection(emptyList(), listOf(0))
        doc.move(0, sel, 10f, 20f)
        assertEquals(110f, doc.pages[0].pictures[0].x); assertEquals(120f, doc.pages[0].pictures[0].y)
        doc.scale(0, sel, 110f, 120f, 2f)
        assertEquals(200f, doc.pages[0].pictures[0].width); assertEquals(110f, doc.pages[0].pictures[0].x)
        doc.remove(0, sel)
        assertEquals(0, doc.pages[0].pictures.size)
        doc.undo(); doc.undo(); doc.undo()
        assertEquals(100f, doc.pages[0].pictures[0].x)
    }

    @Test fun aClipCarriesPicturesWithTheInk() {
        val ink = StrokeBuilder(Tool.PEN, -1, 2f).apply { add(90f, 90f, 0.5f, 0f); add(95f, 95f, 0.5f, 5f) }.build()
        val clip = Clip.of(listOf(ink), listOf(picture(100f, 100f)))!!
        assertEquals(111f, clip.width, 0.001f)                    // 89 (line's edge) to 200
        val doc = doc()
        val at = doc.paste(0, clip.at(0f, 0f), clip.picturesAt(0f, 0f))
        assertEquals(Selection(listOf(0), listOf(0)), at)
        assertEquals(11f, doc.pages[0].pictures[0].x, 0.001f)
    }
}
