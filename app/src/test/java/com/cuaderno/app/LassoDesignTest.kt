package com.cuaderno.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.cuaderno.app.page.Render
import com.juanma.kit.Icons
import com.juanma.kit.Ui
import cuaderno.core.Document
import cuaderno.core.Notebook
import cuaderno.core.Stroke
import cuaderno.core.StrokeBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Proposals for phase 3 (the lasso), drawn over the real writing screen with real ink on dark
 * paper, so Juanma judges images. Nothing here is the app yet. Run by tools/screens.sh.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
class LassoDesignTest {
    private val out = System.getProperty("cuaderno.screens") ?: ""
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun dp(v: Float) = Ui.dp(context, v)

    /** What is drawn over the page: the lasso's loop, the selection's box and handles, a picture. */
    private inner class Over(private val draw: (Canvas, Paint) -> Unit) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) = draw(canvas, paint)
    }

    private class Screen(val activity: WriteActivity) {
        val page get() = activity.page
        val area get() = (activity.bar.parent as ViewGroup).getChildAt(1) as FrameLayout
        /** A point of page [index] on screen, inside the page's area. */
        fun x(px: Float) = page.offsetX + px * page.scale
        fun y(py: Float, index: Int = 0) = page.offsetY + (Render.top(page.document!!.pages, index) + py) * page.scale
    }

    /** A release may be found while rendering: its button is not part of the proposal. */
    private fun hideUpdate(view: View) {
        if (view is android.widget.TextView && view.text.startsWith(Strings.UPDATE)) view.visibility = View.GONE
        if (view is ViewGroup) for (i in 0 until view.childCount) hideUpdate(view.getChildAt(i))
    }

    private fun screen(): Screen {
        WriteActivity.lowLatency = false
        val activity = Robolectric.buildActivity(WriteActivity::class.java).setup().get()
        layout(activity.window.decorView)
        activity.page.darkPaper = true
        activity.page.color = Render.ink(true)
        Replay.into(activity.page, Replay.load("synthetic.csv"), scale = 0.62f, dx = 10f, dy = 150f)
        hideUpdate(activity.window.decorView)
        // The bar as proposed: the lasso after the eraser, in use.
        val bar = activity.bar
        bar.removeViewAt(3)
        bar.addView(Ui.tool(activity, "pen") {}, 3)
        bar.addView(Ui.tool(activity, "lasso", on = true) {}, 6)
        return Screen(activity)
    }

    /** The strokes of the first page in its top part: the sine and its axis. */
    private fun chosen(s: Screen): List<Stroke> {
        val strokes = s.page.document!!.pages[0].strokes
        val top = strokes.minOf { it.bounds[1] }
        val bottom = strokes.maxOf { it.bounds[3] }
        return strokes.filter { it.bounds[1] < top + (bottom - top) * 0.3f }
    }

    private fun box(strokes: List<Stroke>): RectF {
        val r = RectF(strokes.minOf { it.bounds[0] }, strokes.minOf { it.bounds[1] }, strokes.maxOf { it.bounds[2] }, strokes.maxOf { it.bounds[3] })
        r.inset(-8f, -8f)
        return r
    }

    private fun dashed(paint: Paint, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f).toFloat()
        paint.color = color
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(8f).toFloat(), dp(6f).toFloat()), 0f)
    }

    /** The selection: a dashed box and a handle at each corner to make it bigger or smaller. */
    private fun selection(canvas: Canvas, paint: Paint, r: RectF) {
        dashed(paint, Ui.ACCENT)
        canvas.drawRoundRect(r, dp(6f).toFloat(), dp(6f).toFloat(), paint)
        paint.pathEffect = null
        for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) {
            paint.style = Paint.Style.FILL; paint.color = Ui.BRIGHT
            canvas.drawCircle(x, y, dp(9f).toFloat(), paint)
            paint.style = Paint.Style.STROKE; paint.color = Ui.ACCENT; paint.strokeWidth = dp(3f).toFloat()
            canvas.drawCircle(x, y, dp(9f).toFloat(), paint)
        }
    }

    private fun onScreen(s: Screen, r: RectF, index: Int = 0) = RectF(s.x(r.left), s.y(r.top, index), s.x(r.right), s.y(r.bottom, index))

    /** What can be done with the selection, floating above it. */
    private fun actions(s: Screen, over: RectF, items: List<Pair<String, Int>>) {
        val row = Ui.row(s.activity).apply {
            background = Ui.rounded(Ui.RAISED, 14f, s.activity)
            setPadding(dp(6f), dp(4f), dp(6f), dp(4f))
            elevation = dp(8f).toFloat()
        }
        for ((label, color) in items) {
            row.addView(Ui.text(s.activity, label, 15f, color, bold = true).apply { setPadding(dp(16f), dp(12f), dp(16f), dp(12f)) })
        }
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        s.area.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = (over.centerX() - row.measuredWidth / 2f).toInt()
            topMargin = (over.top - row.measuredHeight - dp(18f)).toInt()
        })
    }

    private val menu = listOf("Copiar" to Ui.TEXT, "Cortar" to Ui.TEXT, "Borrar" to Ui.RED)

    // --- 1: the loop being drawn ------------------------------------------------------

    private fun loop(): View {
        val s = screen()
        val r = box(chosen(s))
        val c = onScreen(s, r)
        s.area.addView(Over { canvas, paint ->
            dashed(paint, Ui.ACCENT)
            val path = Path()
            val cx = c.centerX(); val cy = c.centerY()
            val rx = c.width() / 2 + dp(30f); val ry = c.height() / 2 + dp(26f)
            // A hand-drawn loop, not quite closed: the pen is still going round.
            for (k in 0..92) {
                val a = Math.toRadians(200.0 + k * 3.6).toFloat()
                val wobble = 1f + 0.04f * sin(k * 0.7f)
                val x = cx + rx * wobble * cos(a); val y = cy + ry * wobble * sin(a)
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }, FrameLayout.LayoutParams(-1, -1))
        return s.activity.window.decorView
    }

    // --- 2: selected --------------------------------------------------------------------

    private fun selected(): View {
        val s = screen()
        val c = onScreen(s, box(chosen(s)))
        s.area.addView(Over { canvas, paint -> selection(canvas, paint, c) }, FrameLayout.LayoutParams(-1, -1))
        actions(s, c, menu)
        return s.activity.window.decorView
    }

    // --- 3: moved, bigger, another colour -----------------------------------------------

    private fun moved(): View {
        val s = screen()
        val picked = chosen(s)
        val r = box(picked)
        val k = 1.2f
        val dx = 200f; val dy = 12f
        val changed = picked.map { stroke ->
            val b = StrokeBuilder(stroke.tool, Ui.ACCENT_DEEP, stroke.width)
            for (i in 0 until stroke.size) {
                b.add(r.left + dx + (stroke.x(i) - r.left) * k, r.top + dy + (stroke.y(i) - r.top) * k, stroke.pressure(i), stroke.time(i))
            }
            b.build()
        }
        val page = s.page.document!!.pages[0]
        val set = picked.toSet()
        s.page.document = Document(Notebook("n", "t", "c", "m", listOf(page.with(page.strokes.filterNot { it in set } + changed))))
        val bar = s.activity.bar
        // The colour tapped while selected recolours it: the blue is ringed.
        bar.removeViewAt(8); bar.addView(Ui.swatch(s.activity, Render.ink(true), 22f, false) {}, 8)
        bar.removeViewAt(9); bar.addView(Ui.swatch(s.activity, Ui.ACCENT_DEEP, 22f, true) {}, 9)
        val c = onScreen(s, RectF(r.left + dx, r.top + dy, r.left + dx + r.width() * k, r.top + dy + r.height() * k))
        s.area.addView(Over { canvas, paint ->
            selection(canvas, paint, c)
            // Where it was, faint.
            dashed(paint, Ui.MUTED)
            canvas.drawRoundRect(onScreen(s, r), dp(6f).toFloat(), dp(6f).toFloat(), paint)
        }, FrameLayout.LayoutParams(-1, -1))
        actions(s, c, menu)
        return s.activity.window.decorView
    }

    // --- 4: paste on another page --------------------------------------------------------

    private fun paste(): View {
        val s = screen()
        s.page.document!!.addPage()
        s.page.document = s.page.document
        s.page.showPage(1)
        val chip = Ui.row(s.activity).apply {
            background = Ui.rounded(Ui.ACCENT, 22f, s.activity)
            setPadding(dp(18f), dp(10f), dp(22f), dp(10f))
            elevation = dp(8f).toFloat()
            gravity = Gravity.CENTER_VERTICAL
            addView(View(s.activity).apply { background = Icons.drawable("pages", Ui.BG) }, android.widget.LinearLayout.LayoutParams(dp(20f), dp(20f)))
            addView(Ui.text(s.activity, "Pegar", 16f, Ui.BG, bold = true).apply { setPadding(dp(10f), 0, 0, 0) })
        }
        s.area.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(20f) })
        return s.activity.window.decorView
    }

    // --- 5: a picture on the page ------------------------------------------------------

    /** A made-up photo of a blackboard: green-grey, with a chalk circuit-ish line. */
    private fun photo(w: Int, h: Int): Bitmap {
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        canvas.drawColor(0xFF33443D.toInt())
        val chalk = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = w / 120f; color = 0xDDE8E8E0.toInt() }
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.5f); lineTo(w * 0.25f, h * 0.5f)
            for (k in 0..5) { lineTo(w * (0.28f + k * 0.05f), h * (if (k % 2 == 0) 0.38f else 0.62f)) }
            lineTo(w * 0.6f, h * 0.5f); lineTo(w * 0.72f, h * 0.5f)
            moveTo(w * 0.72f, h * 0.36f); lineTo(w * 0.72f, h * 0.64f)
            moveTo(w * 0.76f, h * 0.36f); lineTo(w * 0.76f, h * 0.64f)
            moveTo(w * 0.76f, h * 0.5f); lineTo(w * 0.92f, h * 0.5f)
        }
        canvas.drawPath(path, chalk)
        return image
    }

    private fun picture(): View {
        val s = screen()
        val strokes = s.page.document!!.pages[0].strokes
        val top = strokes.minOf { it.bounds[1] }
        val r = RectF(350f, top + 30f, 575f, top + 30f + 125f)
        val c = onScreen(s, r)
        val image = photo(c.width().toInt(), c.height().toInt())
        s.area.addView(Over { canvas, paint ->
            canvas.drawBitmap(image, c.left, c.top, null)
            selection(canvas, paint, RectF(c.left - dp(4f), c.top - dp(4f), c.right + dp(4f), c.bottom + dp(4f)))
        }, FrameLayout.LayoutParams(-1, -1))
        actions(s, c, menu)
        return s.activity.window.decorView
    }

    // --------------------------------------------------------------------------------

    @Test fun lassoProposals() {
        assumeTrue(out.isNotEmpty())
        save(loop(), "fase3-1-lazo.png")
        save(selected(), "fase3-2-seleccion.png")
        save(moved(), "fase3-3-mover-agrandar-color.png")
        save(paste(), "fase3-4-pegar.png")
        save(picture(), "fase3-5-imagen.png")
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, W, H)
    }

    private fun save(view: View, name: String) {
        layout(view)
        hideUpdate(view)
        layout(view)
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out).mkdirs()
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val W = 2304
        const val H = 1440
    }
}
