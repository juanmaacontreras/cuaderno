package com.cuaderno.app.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import cuaderno.core.Page
import cuaderno.core.Tool
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The notebook kept drawn, in square pictures of [SIZE] pixels at the scale it is
 * shown, so that moving the page copies a few pictures instead of drawing every
 * stroke again: without this, a frame costs in proportion to what is written
 * (a full sheet scrolled at 23 frames a second on the emulator, an empty one at 60).
 *
 * A new pen stroke is painted onto the pictures it touches; anything else that
 * changes a page (the eraser, undo, a highlighter, which goes under the ink) draws
 * those pictures again. At a new zoom the old pictures are shown stretched while
 * the sharp ones are drawn, a few per frame, so the pen is never kept waiting.
 *
 * Belongs to one thread, like the [Render] it draws with.
 */
class Tiles(private val render: Render) {
    private data class Key(val scale: Float, val i: Int, val j: Int)
    private class Tile(val key: Key, val bitmap: Bitmap)

    private val cache = LinkedHashMap<Key, Tile>(64, 0.75f, true)
    private val spare = ArrayList<Bitmap>()
    private var seen: List<Page> = emptyList()
    private var dark = false
    private var lastScale = 0f
    private val stretch = Paint(Paint.FILTER_BITMAP_FLAG)
    private val area = RectF()
    private val inner = android.graphics.Rect(PAD, PAD, PAD + SIZE, PAD + SIZE)
    private val place = android.graphics.Rect()

    /** Pictures drawn so far, for the tests: moving the page should not add to it. */
    var drawn = 0
        private set

    /**
     * Draws [pages] with the notebook's corner at [ox], [oy] pixels and [s] pixels per
     * point, on a [width] x [height] canvas already filled with the desk. [patient]
     * draws every missing picture now (an image for the tests or a render); otherwise
     * at most [BUDGET_NS] of them per frame. True if the result is not final yet and
     * another frame is wanted.
     */
    fun draw(canvas: Canvas, pages: List<Page>, dark: Boolean, ox: Float, oy: Float, s: Float,
             width: Int, height: Int, patient: Boolean = false): Boolean {
        if (pages.isEmpty()) return false
        follow(pages, dark)
        val steady = s == lastScale
        lastScale = s

        val wide = pages.maxOf { it.width } * s
        val tall = Render.height(pages) * s
        val i0 = maxOf(0, floor(-ox / SIZE).toInt())
        val i1 = minOf(ceil(wide / SIZE).toInt() - 1, floor((width - ox) / SIZE).toInt())
        val j0 = maxOf(0, floor(-oy / SIZE).toInt())
        val j1 = minOf(ceil(tall / SIZE).toInt() - 1, floor((height - oy) / SIZE).toInt())
        if (i1 < i0 || j1 < j0) return false

        // Draw what is missing, as much as fits in the frame; while the zoom is
        // still changing, nothing: those pictures would be thrown away next frame.
        val missing = ArrayList<Key>()
        val start = System.nanoTime()
        for (j in j0..j1) for (i in i0..i1) {
            val key = Key(s, i, j)
            if (cache.containsKey(key)) continue
            if (patient || (steady && System.nanoTime() - start < BUDGET_NS)) make(key, pages)
            else missing.add(key)
        }

        // Underneath, the pictures of another zoom, stretched, for what is not drawn yet.
        if (missing.isNotEmpty()) stretched(canvas, ox, oy, s, width, height)

        // Whole pixels: a picture put half way between two would come out blurred.
        val x = ox.roundToInt()
        val y = oy.roundToInt()
        for (j in j0..j1) for (i in i0..i1) {
            val tile = cache[Key(s, i, j)] ?: continue
            place.set(x + i * SIZE, y + j * SIZE, x + (i + 1) * SIZE, y + (j + 1) * SIZE)
            canvas.drawBitmap(tile.bitmap, inner, place, null)
        }

        // Nothing stretched covers it either: draw the strokes there directly, as before the pictures.
        for (key in missing) {
            if (covered(key)) continue
            bounds(key, area)
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(s, s)
            canvas.clipRect(area)
            render.pages(canvas, pages, area.top, area.bottom, area.left, area.right)
            canvas.restore()
        }

        trim(s)
        return missing.isNotEmpty()
    }

    /** Forget every picture, e.g. when the screen goes away. */
    fun clear() {
        cache.clear()
        spare.clear()
        seen = emptyList()
        lastScale = 0f
    }

    // --- keeping up with the notebook -------------------------------------------------

    /** Brings the pictures up to date with [pages]: paints new pen strokes on, draws changed areas again. */
    private fun follow(pages: List<Page>, dark: Boolean) {
        if (dark != this.dark) {
            this.dark = dark
            drop()
        }
        if (pages === seen) return
        var top = 0f
        for ((n, page) in pages.withIndex()) {
            val before = seen.getOrNull(n)
            if (page !== before) {
                // Another page there, another size or another paper: the whole sheet is drawn again.
                if (before == null || before.id != page.id || before.height != page.height || before.width != page.width ||
                    before.paper != page.paper) {
                    stale(RectF(0f, top, maxOf(page.width, before?.width ?: 0f), top + maxOf(page.height, before?.height ?: 0f)))
                } else {
                    changed(before, page, top)
                }
            }
            top += page.height + Render.GAP
        }
        // Pages that went away leave desk behind.
        if (seen.size > pages.size) stale(RectF(0f, top - Render.GAP, Float.MAX_VALUE, Float.MAX_VALUE))
        seen = pages
    }

    private fun changed(before: Page, page: Page, top: Float) {
        val old = before.strokes
        val now = page.strokes
        // The usual case: pen strokes added at the end, drawn last anyway. Paint them on.
        if (now.size > old.size && (old.size until now.size).all { now[it].tool == Tool.PEN } && prefix(old, now)) {
            for (k in old.size until now.size) paintOn(page, now[k], top)
            return
        }
        // Anything else: what was there and what is there now, by identity; draw that area again.
        val had = IdentityHashMap<Any, Boolean>(old.size * 2)
        for (stroke in old) had[stroke] = true
        val has = IdentityHashMap<Any, Boolean>(now.size * 2)
        for (stroke in now) has[stroke] = true
        val area = RectF()
        for (stroke in old) if (!has.containsKey(stroke)) grow(area, stroke, top)
        for (stroke in now) if (!had.containsKey(stroke)) grow(area, stroke, top)
        if (!area.isEmpty) stale(area)
    }

    private fun prefix(old: List<*>, now: List<*>): Boolean {
        for (k in old.indices) if (old[k] !== now[k]) return false
        return true
    }

    private fun grow(area: RectF, stroke: cuaderno.core.Stroke, top: Float) {
        val b = stroke.bounds
        val w = stroke.width
        val r = RectF(b[0] - w, top + b[1] - w, b[2] + w, top + b[3] + w)
        if (area.isEmpty) area.set(r) else area.union(r)
    }

    /** Draws [stroke] onto the pictures it falls on, at every zoom kept. */
    private fun paintOn(page: Page, stroke: cuaderno.core.Stroke, top: Float) {
        val area = RectF()
        grow(area, stroke, top)
        val tile = RectF()
        for (t in cache.values) {
            bounds(t.key, tile)
            if (!RectF.intersects(tile, area)) continue
            val c = Canvas(t.bitmap)
            origin(c, t.key)
            c.translate(0f, top)
            c.clipRect(0f, 0f, page.width, page.height)
            render.stroke(c, stroke)
        }
    }

    /** Forgets the pictures that show any of [area] (notebook points). */
    private fun stale(area: RectF) {
        val tile = RectF()
        val it = cache.values.iterator()
        while (it.hasNext()) {
            val t = it.next()
            bounds(t.key, tile)
            if (RectF.intersects(tile, area)) {
                keep(t.bitmap)
                it.remove()
            }
        }
    }

    private fun drop() {
        for (t in cache.values) keep(t.bitmap)
        cache.clear()
    }

    // --- the pictures -----------------------------------------------------------------

    private fun make(key: Key, pages: List<Page>) {
        val bitmap = spare.removeLastOrNull() ?: Bitmap.createBitmap(SIZE + 2 * PAD, SIZE + 2 * PAD, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Render.DESK)
        bounds(key, area)
        val margin = PAD / key.scale
        origin(c, key)
        render.pages(c, pages, area.top - margin, area.bottom + margin, area.left - margin, area.right + margin)
        cache[key] = Tile(key, bitmap)
        drawn++
    }

    /**
     * A picture's canvas set up in notebook points. Each one is drawn [PAD] pixels wider all
     * round and only its middle shown: the smoothing of a stroke loses ink at a canvas's edge,
     * which would leave a faint seam where a stroke crosses from one picture to the next.
     */
    private fun origin(c: Canvas, key: Key) {
        c.translate(PAD - key.i * SIZE.toFloat(), PAD - key.j * SIZE.toFloat())
        c.scale(key.scale, key.scale)
    }

    /** What a picture shows, in notebook points. */
    private fun bounds(key: Key, out: RectF) {
        val size = SIZE / key.scale
        out.set(key.i * size, key.j * size, (key.i + 1) * size, (key.j + 1) * size)
    }

    private fun stretched(canvas: Canvas, ox: Float, oy: Float, s: Float, width: Int, height: Int) {
        val screen = RectF(-ox / s, -oy / s, (width - ox) / s, (height - oy) / s)
        val tile = RectF()
        // The nearest zoom last, on top.
        for (t in cache.values.filter { it.key.scale != s }.sortedByDescending { kotlin.math.abs(it.key.scale - s) }) {
            bounds(t.key, tile)
            if (!RectF.intersects(tile, screen)) continue
            tile.set(ox + tile.left * s, oy + tile.top * s, ox + tile.right * s, oy + tile.bottom * s)
            canvas.drawBitmap(t.bitmap, inner, tile, stretch)
        }
    }

    /** Whether pictures of one other zoom cover [key]'s whole area. */
    private fun covered(key: Key): Boolean {
        bounds(key, area)
        for (k in cache.keys.map { it.scale }.toSet()) {
            if (k == key.scale) continue
            val size = SIZE / k
            val i0 = floor(area.left / size).toInt(); val i1 = ceil(area.right / size).toInt() - 1
            val j0 = floor(area.top / size).toInt(); val j1 = ceil(area.bottom / size).toInt() - 1
            var all = true
            loop@ for (j in j0..j1) for (i in i0..i1) if (!cache.containsKey(Key(k, i, j))) { all = false; break@loop }
            if (all) return true
        }
        return false
    }

    /** Keeps memory bounded: the least recently shown pictures go first, other zooms before this one. */
    private fun trim(s: Float) {
        if (cache.size <= MAX) return
        val it = cache.values.iterator()
        while (cache.size > MAX && it.hasNext()) {
            val t = it.next()
            if (t.key.scale != s) { keep(t.bitmap); it.remove() }
        }
        val again = cache.values.iterator()
        while (cache.size > MAX && again.hasNext()) { keep(again.next().bitmap); again.remove() }
    }

    // Never recycled by hand: a frame on its way to the screen may still be reading it.
    private fun keep(bitmap: Bitmap) {
        if (spare.size < SPARE) spare.add(bitmap)
    }

    companion object {
        /** Pixels on a side: a screen of the tablet is 5 x 3 of them. */
        const val SIZE = 512
        private const val PAD = 8
        /** Pictures kept, 1 MB each: two screens' worth. */
        private const val MAX = 48
        private const val SPARE = 8
        /** Time per frame for drawing new pictures when there is something to show meanwhile. */
        private const val BUDGET_NS = 6_000_000L
    }
}
