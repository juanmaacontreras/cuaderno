package com.cuaderno.app

import android.graphics.Bitmap
import android.graphics.Canvas
import com.cuaderno.app.page.Render
import com.cuaderno.app.page.Tiles
import com.juanma.kit.Ui
import cuaderno.core.Document
import cuaderno.core.Notebook
import cuaderno.core.Page
import cuaderno.core.Stroke
import cuaderno.core.StrokeBuilder
import cuaderno.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.sin

/** The notebook kept as pictures looks exactly like the notebook drawn from scratch, whatever happens to it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TilesTest {
    private val w = 1206
    private val h = 800

    private fun stroke(x: Float, y: Float, length: Float, tool: Tool = Tool.PEN, color: Int = Ui.BG, width: Float = 1.1f): Stroke {
        val b = StrokeBuilder(tool, color, width)
        for (k in 0..20) b.add(x + length * k / 20, y + 6f * sin(k * 0.9f), 0.5f, k * 4f)
        return b.build()
    }

    /** Strokes everywhere, many across the edges between pictures (every 256 points at scale 2). */
    private fun document(): Document {
        val strokes = ArrayList<Stroke>()
        var y = 30f
        while (y < 800f) {
            var x = 20f
            while (x < 560f) { strokes.add(stroke(x, y, 40f)); x += 47f }
            y += 37f
        }
        strokes.add(stroke(100f, 250f, 300f, Tool.HIGHLIGHTER, Ui.YELLOW, 10f))
        return Document(Notebook("n", "t", "c", "m", listOf(Page("p0", Page.A4_WIDTH, Page.A4_HEIGHT, cuaderno.core.Paper.GRID, strokes))))
    }

    // Drawn on a larger canvas and cut: at a canvas's own edge the smoothing loses ink, here too.
    private fun scratch(doc: Document, dark: Boolean, ox: Float, oy: Float, s: Float): Bitmap {
        val image = Bitmap.createBitmap(w + 32, h + 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Render.DESK)
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        Render().also { it.dark = dark }.pages(canvas, doc.pages, -oy / s, (h + 32 - oy) / s)
        return image
    }

    private class Shown(val image: Bitmap, val more: Boolean)

    private fun tiled(tiles: Tiles, render: Render, doc: Document, dark: Boolean, ox: Float, oy: Float, s: Float,
                      patient: Boolean = true): Shown {
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(Render.DESK)
        render.dark = dark
        val more = tiles.draw(canvas, doc.pages, dark, ox, oy, s, w, h, patient)
        return Shown(image, more)
    }

    /**
     * Pixels that differ by more than rounding. Drawn in pieces, an edge's smoothing can come out a
     * sixteenth of a pixel off (16 levels), at an odd zoom two, on a few loose pixels. A seam where
     * strokes cross from one picture to the next shows on dozens, a missing or stale stroke on hundreds.
     */
    private fun differ(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            if ((0..2).any { kotlin.math.abs((p shr (it * 8) and 255) - (q shr (it * 8) and 255)) > 24 }) n++
        }
        return n
    }

    private fun same(tiles: Tiles, render: Render, doc: Document, dark: Boolean = false,
                     ox: Float = 8f, oy: Float = 8f, s: Float = 2f, what: String) {
        val n = differ(scratch(doc, dark, ox, oy, s), tiled(tiles, render, doc, dark, ox, oy, s).image)
        assertTrue("$what: $n pixels differ", n <= 8)
    }

    @Test fun piecedTogetherItIsTheSameAsDrawnAtOnce() {
        val render = Render()
        val doc = document()
        same(Tiles(render), render, doc, what = "light paper")
        same(Tiles(render), render, doc, dark = true, what = "dark paper")
        same(Tiles(render), render, doc, ox = -300f, oy = -900f, s = 3.3f, what = "zoomed in, further down")
    }

    @Test fun itKeepsUpWithWritingTheHighlighterTheEraserAndUndo() {
        val render = Render()
        val tiles = Tiles(render)
        val doc = document()
        same(tiles, render, doc, what = "first")
        val before = tiles.drawn

        // A pen stroke across four pictures is painted on: nothing is drawn again.
        doc.add(0, stroke(240f, 250f, 40f, color = Ui.RED))
        same(tiles, render, doc, what = "a pen stroke")
        assertEquals(before, tiles.drawn)

        doc.add(0, stroke(60f, 100f, 200f, Tool.HIGHLIGHTER, Ui.GREEN, 10f))
        same(tiles, render, doc, what = "a highlighter goes under the ink")
        assertTrue(tiles.drawn > before)

        doc.erase(0, 270f, 250f, 10f, merge = false)
        same(tiles, render, doc, what = "erased")
        doc.undo()
        same(tiles, render, doc, what = "undone")
        doc.redo()
        same(tiles, render, doc, what = "redone")
        same(tiles, render, doc, dark = true, what = "then dark paper")
    }

    /** A real JPEG: a red and blue picture with a dark bar, to be seen clearly on either paper. */
    private fun photo(): ByteArray {
        val image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(image)
        c.drawColor(0xFFBF616A.toInt())
        c.drawRect(100f, 0f, 200f, 100f, android.graphics.Paint().apply { color = 0xFF5E81AC.toInt() })
        c.drawRect(0f, 40f, 200f, 60f, android.graphics.Paint().apply { color = 0xFF2E3440.toInt() })
        val out = java.io.ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    @Test fun aPictureShowsUnderTheInkAndFollowsMovingAndUndo() {
        val render = Render()
        val tiles = Tiles(render)
        val doc = document()
        same(tiles, render, doc, what = "first")
        val bytes = photo()
        doc.insert(0, cuaderno.core.Picture(cuaderno.core.Picture.nameFor(bytes, "jpg"), bytes, 230f, 200f, 200f, 100f))
        same(tiles, render, doc, what = "a picture placed")
        val shown = tiled(tiles, render, doc, false, 8f, 8f, 2f).image
        // Inside the picture, between the strokes: its red, not the paper.
        val red = shown.getPixel(8 + (235 * 2), 8 + (205 * 2))
        assertTrue("the picture is drawn: ${Integer.toHexString(red)}", (red shr 16 and 255) > 150 && (red and 255) < 140)
        val sel = cuaderno.core.Selection(emptyList(), listOf(0))
        doc.move(0, sel, 100f, 300f)
        same(tiles, render, doc, what = "moved: gone from where it was")
        doc.scale(0, sel, 330f, 500f, 0.5f)
        same(tiles, render, doc, what = "smaller")
        doc.undo(); doc.undo()
        same(tiles, render, doc, what = "back by undo")
        doc.remove(0, sel)
        same(tiles, render, doc, what = "removed")
    }

    @Test fun anotherPaperShowsOnTheWholePageAtOnce() {
        val render = Render()
        val tiles = Tiles(render)
        val doc = document()
        same(tiles, render, doc, what = "first")
        doc.paper(0, cuaderno.core.Paper.RULED)
        same(tiles, render, doc, what = "ruled")
        doc.undo()
        same(tiles, render, doc, what = "squared again")
    }

    @Test fun movingThePageOnlyDrawsWhatComesIntoView() {
        val render = Render()
        val tiles = Tiles(render)
        val doc = document()
        tiled(tiles, render, doc, false, 8f, 8f, 2f)
        val first = tiles.drawn
        // Moved less than a picture: nothing new to draw.
        tiled(tiles, render, doc, false, 8f, -40f, 2f, patient = false)
        same(tiles, render, doc, oy = -40f, what = "moved a little")
        assertEquals(first, tiles.drawn)
        // A picture further: one more row at most, never the whole screen again.
        tiled(tiles, render, doc, false, 8f, -560f, 2f, patient = false)
        assertTrue("${tiles.drawn - first} new", tiles.drawn - first <= 3)
    }

    @Test fun aNewZoomShowsTheOldPicturesStretchedUntilTheSharpOnesAreDrawn() {
        val render = Render()
        val tiles = Tiles(render)
        val doc = document()
        tiled(tiles, render, doc, false, 8f, 8f, 2f)
        val first = tiles.drawn
        // The fingers are zooming: nothing new is drawn, the old pictures stand in.
        val zooming = tiled(tiles, render, doc, false, 8f, 8f, 2.4f, patient = false)
        assertTrue(zooming.more)
        assertEquals(first, tiles.drawn)
        assertTrue("something is shown", differ(zooming.image, Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Render.DESK) }) > 1000)
        // They stopped: a few frames later it is sharp.
        var frames = 1
        while (tiled(tiles, render, doc, false, 8f, 8f, 2.4f, patient = false).more) assertTrue(++frames < 60)
        same(tiles, render, doc, s = 2.4f, what = "sharp at the new zoom")
        assertFalse(tiled(tiles, render, doc, false, 8f, 8f, 2.4f, patient = false).more)
    }
}
