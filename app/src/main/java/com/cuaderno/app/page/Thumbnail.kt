package com.cuaderno.app.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.juanma.kit.Ui
import cuaderno.core.Page

/** A sheet as a small picture: the library's covers, the papers to choose from, the pages panel. */
object Thumbnail {
    /**
     * [page] [width] pixels wide, with round corners and a thin edge (dark paper on the dark
     * screens would not show where the sheet ends). [part] of the page's width shows: its
     * corner, enlarged. Draws with [render], which belongs to the calling thread.
     */
    fun draw(page: Page, width: Int, dark: Boolean, density: Float, part: Float = 1f, render: Render? = null): Bitmap {
        val own = render == null
        val render = render ?: Render()
        val h = (width * page.height / page.width).toInt().coerceAtLeast(1)
        val image = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val corner = 6f * density
        val box = RectF(0f, 0f, width.toFloat(), h.toFloat())
        canvas.clipPath(Path().apply { addRoundRect(box, corner, corner, Path.Direction.CW) })
        val s = width / (page.width * part)
        canvas.save()
        canvas.scale(s, s)
        render.dark = dark
        // Never under a pixel: the paper would not show at all.
        render.ruleWidth = maxOf(0.5f, 1f / s)
        render.page(canvas, page)
        if (own) render.close()
        canvas.restore()
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Ui.MUTED
        }
        canvas.drawRoundRect(box, corner, corner, edge)
        return image
    }
}
