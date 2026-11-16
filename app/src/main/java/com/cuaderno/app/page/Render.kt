package com.cuaderno.app.page

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.juanma.kit.Ui
import cuaderno.core.Page
import cuaderno.core.Paper
import cuaderno.core.Stroke

/**
 * Draws pages and strokes, in page points: the caller sets the canvas up so
 * that one unit is one point. The screen, the PDF export and the tests all
 * draw through here, so what is exported is what was seen.
 *
 * Not thread-safe: each thread that draws has its own Render.
 */
class Render {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULE; strokeWidth = 0.5f }
    private val fill = Paint().apply { color = PAPER }
    private val path = Path()

    /** The whole notebook, pages one under the other, as far as [top]..[bottom] (points) is visible. */
    fun pages(canvas: Canvas, pages: List<Page>, top: Float, bottom: Float) {
        var y = 0f
        for (page in pages) {
            if (y + page.height >= top && y <= bottom) {
                canvas.save()
                canvas.translate(0f, y)
                page(canvas, page)
                canvas.restore()
            }
            y += page.height + GAP
        }
    }

    /** One page with its origin at the canvas origin. Ink is clipped to the sheet. */
    fun page(canvas: Canvas, page: Page) {
        canvas.save()
        canvas.clipRect(0f, 0f, page.width, page.height)
        canvas.drawRect(0f, 0f, page.width, page.height, fill)
        paper(canvas, page)
        for (stroke in page.strokes) stroke(canvas, stroke)
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
        ink.color = stroke.color
        ink.strokeWidth = stroke.width
        if (stroke.size == 1) {
            canvas.drawPoint(stroke.x(0), stroke.y(0), ink)
            return
        }
        path.rewind()
        path.moveTo(stroke.x(0), stroke.y(0))
        for (i in 1 until stroke.size) {
            val ax = stroke.x(i - 1)
            val ay = stroke.y(i - 1)
            path.quadTo(ax, ay, (ax + stroke.x(i)) / 2, (ay + stroke.y(i)) / 2)
        }
        path.lineTo(stroke.x(stroke.size - 1), stroke.y(stroke.size - 1))
        canvas.drawPath(path, ink)
    }

    companion object {
        /** Points between one page and the next on screen. */
        const val GAP = 14f
        /** 5 mm squares; 8 mm lines. */
        const val GRID = 14.17f
        const val LINE = 22.68f
        const val PAPER = Ui.BG
        const val RULE = Ui.SURFACE
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
