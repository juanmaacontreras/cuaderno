package com.cuaderno.app.page

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.juanma.kit.Ui
import cuaderno.core.Page
import cuaderno.core.Paper
import cuaderno.core.Stroke
import cuaderno.core.Tool

/**
 * Draws pages and strokes, in page points: the caller sets the canvas up so
 * that one unit is one point. The screen, the PDF export and the tests all
 * draw through here, so what is exported is what was seen.
 *
 * Not thread-safe: each thread that draws has its own Render.
 */
class Render {
    /** Dark sheet with light ink, or a light sheet like paper. Set before drawing. */
    var dark = false

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 0.5f }
    private val fill = Paint()
    // A stroke never changes, so its path is built once and kept while the stroke is
    // around: moving the page redraws every visible stroke on every frame. By identity,
    // not content: comparing strokes sample by sample would cost more than it saves.
    private val paths = java.util.IdentityHashMap<Stroke, Path>()

    /** The whole notebook, pages one under the other, as far as [top]..[bottom] (points) is visible. */
    fun pages(canvas: Canvas, pages: List<Page>, top: Float, bottom: Float) {
        var y = 0f
        for (page in pages) {
            if (y + page.height >= top && y <= bottom) {
                canvas.save()
                canvas.translate(0f, y)
                page(canvas, page, top - y, bottom - y)
                canvas.restore()
            }
            y += page.height + GAP
        }
    }

    /** One page with its origin at the canvas origin. Ink is clipped to the sheet. */
    fun page(canvas: Canvas, page: Page, top: Float = 0f, bottom: Float = page.height) {
        canvas.save()
        canvas.clipRect(0f, 0f, page.width, page.height)
        fill.color = if (dark) PAPER_DARK else PAPER_LIGHT
        rule.color = if (dark) RULE_DARK else RULE_LIGHT
        canvas.drawRect(0f, 0f, page.width, page.height, fill)
        paper(canvas, page)
        // Highlighter first, so it sits under the ink like on paper and the letters stay sharp.
        for (pass in Tool.entries.sortedBy { if (it == Tool.HIGHLIGHTER) 0 else 1 }) {
            for (stroke in page.strokes) {
                if (stroke.tool != pass) continue
                // Zoomed in, most of a page's strokes are off screen: skip them.
                val b = stroke.bounds
                if (b[3] + stroke.width < top || b[1] - stroke.width > bottom) continue
                stroke(canvas, stroke)
            }
        }
        canvas.restore()
    }

    private fun paper(canvas: Canvas, page: Page) {
        when (page.paper) {
            Paper.BLANK -> {}
            Paper.GRID -> {
                var x = GRID
                while (x < page.width) { canvas.drawLine(x, 0f, x, page.height, rule); x += GRID }
                var y = GRID
                while (y < page.height) { canvas.drawLine(0f, y, page.width, y, rule); y += GRID }
            }
            Paper.RULED -> {
                var y = 3 * LINE
                while (y < page.height) { canvas.drawLine(0f, y, page.width, y, rule); y += LINE }
            }
            Paper.DOTTED -> {
                var y = GRID
                while (y < page.height) {
                    var x = GRID
                    while (x < page.width) { canvas.drawCircle(x, y, 0.6f, rule); x += GRID }
                    y += GRID
                }
            }
        }
    }

    /**
     * A stroke as one path of constant width: curves between the midpoints
     * of consecutive samples, so corners come out round. Pressure is stored
     * in the stroke but not drawn: Juanma writes with a fixed-width line.
     */
    fun stroke(canvas: Canvas, stroke: Stroke) {
        // The highlighter is see-through as a whole stroke: one path, so where it crosses itself it does not darken.
        val paint = if (stroke.tool == Tool.HIGHLIGHTER) highlight else ink
        paint.color = if (stroke.tool == Tool.HIGHLIGHTER) faded(stroke.color, dark) else shown(stroke.color, dark)
        paint.strokeWidth = stroke.width
        if (stroke.size == 1) {
            canvas.drawPoint(stroke.x(0), stroke.y(0), paint)
            return
        }
        canvas.drawPath(paths[stroke] ?: build(stroke), paint)
    }

    private fun build(stroke: Stroke): Path {
        // Erased and undone strokes pile up here; start again now and then rather than track them.
        if (paths.size > MAX_PATHS) paths.clear()
        val path = Path()
        path.moveTo(stroke.x(0), stroke.y(0))
        for (i in 1 until stroke.size) {
            val ax = stroke.x(i - 1)
            val ay = stroke.y(i - 1)
            path.quadTo(ax, ay, (ax + stroke.x(i)) / 2, (ay + stroke.y(i)) / 2)
        }
        path.lineTo(stroke.x(stroke.size - 1), stroke.y(stroke.size - 1))
        paths[stroke] = path
        return path
    }

    companion object {
        /** Points between one page and the next on screen. */
        const val GAP = 14f
        private const val MAX_PATHS = 20000
        /** 5 mm squares; 8 mm lines. */
        const val GRID = 14.17f
        const val LINE = 22.68f
        const val PAPER_DARK = Ui.BG
        const val RULE_DARK = Ui.SURFACE
        const val PAPER_LIGHT = Ui.BRIGHT
        const val RULE_LIGHT = 0xFFD3D9E4.toInt()
        /** The plain ink of each paper: near-black on light, near-white on dark. */
        const val INK_ON_LIGHT = Ui.BG
        const val INK_ON_DARK = Ui.BRIGHT

        fun ink(dark: Boolean) = if (dark) INK_ON_DARK else INK_ON_LIGHT

        /**
         * The colour a stroke is drawn in. Plain ink written on one paper
         * would vanish on the other, so it is shown as the other's plain
         * ink; the file keeps what was written. Other colours stay.
         */
        fun shown(color: Int, dark: Boolean): Int = when {
            dark && color == INK_ON_LIGHT -> INK_ON_DARK
            !dark && color == INK_ON_DARK -> INK_ON_LIGHT
            else -> color
        }
        /**
         * Highlighter colours, written opaque in the file and shown see-through:
         * Nord's yellow, green, frost, purple and orange.
         */
        val HIGHLIGHTS = listOf(Ui.YELLOW, Ui.GREEN, Ui.ACCENT, 0xFFB48EAD.toInt(), Ui.ORANGE)

        /** How much of the highlighter shows: on dark paper less, or it washes the ink out. */
        fun highlightAlpha(dark: Boolean) = if (dark) 0.4f else 0.65f

        fun faded(color: Int, dark: Boolean): Int =
            (color and 0xFFFFFF) or ((highlightAlpha(dark) * 255).toInt() shl 24)

        /** What shows around and between the sheets. */
        const val DESK = 0xFF242933.toInt()

        fun height(pages: List<Page>): Float = pages.sumOf { (it.height + GAP).toDouble() }.toFloat() - GAP

        /** Index of the page at document height [y], or the nearest one. */
        fun pageAt(pages: List<Page>, y: Float): Int {
            var top = 0f
            for ((i, page) in pages.withIndex()) {
                if (y < top + page.height + GAP / 2) return i
                top += page.height + GAP
            }
            return pages.size - 1
        }

        fun top(pages: List<Page>, index: Int): Float {
            var top = 0f
            for (i in 0 until index) top += pages[i].height + GAP
            return top
        }
    }
}
