package com.cuaderno.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.cuaderno.app.ink.OwnSurface
import com.juanma.kit.Icons
import com.juanma.kit.Ui
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Proposals for the writing screen (phase 0's last step), drawn with the real
 * kit and the real ink so Juanma picks from images. Nothing here is the app:
 * the chosen one gets built in phase 1. Run by tools/screens.sh.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
class DesignTest {
    private val out = System.getProperty("cuaderno.screens") ?: ""
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun dp(v: Float) = Ui.dp(context, v)

    private class Paper(context: Context, val dark: Boolean) : View(context) {
        private val line = Paint().apply { color = if (dark) 0xFF3B4252.toInt() else 0xFFD8DEE9.toInt(); strokeWidth = 2f }
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(if (dark) Ui.BG else Ui.BRIGHT)
            val step = 20f * resources.displayMetrics.density
            var x = step
            while (x < width) { canvas.drawLine(x, 0f, x, height.toFloat(), line); x += step }
            var y = step
            while (y < height) { canvas.drawLine(0f, y, width.toFloat(), y, line); y += step }
        }
    }

    private fun page(dark: Boolean): FrameLayout {
        val ink = OwnSurface(context, lowLatency = false)
        ink.setBackgroundColor(0)
        ink.color = if (dark) Ui.BRIGHT else Ui.BG
        ink.tag = "ink"
        return FrameLayout(context).apply {
            addView(Paper(context, dark), FrameLayout.LayoutParams(-1, -1))
            addView(ink, FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun tool(icon: String, on: Boolean = false, dim: Boolean = false) = View(context).apply {
        val glyph = Icons.drawable(icon, if (on) Ui.BG else if (dim) Ui.MUTED else Ui.TEXT)
        val inset = dp(11f)
        background = android.graphics.drawable.LayerDrawable(
            arrayOf(Ui.rounded(if (on) Ui.ACCENT else 0, 12f, context), glyph)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
        layoutParams = LinearLayout.LayoutParams(dp(44f), dp(44f))
    }

    private fun gap(size: Float, vertical: Boolean) = View(context).apply {
        layoutParams = if (vertical) LinearLayout.LayoutParams(1, dp(size)) else LinearLayout.LayoutParams(dp(size), 1)
    }

    private fun stretch(vertical: Boolean) = View(context).apply {
        layoutParams = if (vertical) LinearLayout.LayoutParams(1, 0, 1f) else LinearLayout.LayoutParams(0, 1, 1f)
    }

    /** Pen, highlighter, eraser, lasso; colours; widths. */
    private fun addTools(bar: LinearLayout, vertical: Boolean, widths: Boolean = true) {
        bar.addView(tool("pen", on = true))
        bar.addView(tool("highlighter"))
        bar.addView(tool("eraser"))
        bar.addView(tool("lasso"))
        bar.addView(gap(10f, vertical))
        for ((i, color) in listOf(Ui.BRIGHT, Ui.ACCENT, Ui.RED, Ui.GREEN, Ui.YELLOW).withIndex()) {
            bar.addView(Ui.swatch(context, color, 22f, i == 0) {})
        }
        if (widths) {
            bar.addView(gap(6f, vertical))
            for ((i, w) in listOf(1.5f, 2.5f, 4.5f).withIndex()) {
                bar.addView(Ui.swatch(context, Ui.DIM, 4f + w * 3f, i == 1) {})
            }
        }
    }

    private fun title() = Ui.text(context, "TC2 · Síntesis de dipolos", 15f, Ui.BRIGHT, bold = true)
    private fun pages() = Ui.text(context, "3 / 14", 14f, Ui.DIM)

    /** A: one bar across the top, everything in it. */
    private fun top(dark: Boolean): View {
        val bar = Ui.row(context).apply { setBackgroundColor(Ui.SURFACE); setPadding(dp(8f), dp(6f), dp(8f), dp(6f)) }
        bar.addView(tool("back"))
        bar.addView(title(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4f); marginEnd = dp(18f) })
        addTools(bar, vertical = false)
        bar.addView(stretch(false))
        bar.addView(tool("undo"))
        bar.addView(tool("redo", dim = true))
        bar.addView(pages(), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12f); marginEnd = dp(6f) })
        bar.addView(tool("more"))
        return Ui.column(context).apply {
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(page(dark), LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    /** B: the page takes the whole screen; the tools float over it in a pill. */
    private fun floating(dark: Boolean): View {
        val pill = Ui.row(context).apply { background = Ui.rounded(Ui.SURFACE, 28f, context); setPadding(dp(8f), dp(4f), dp(8f), dp(4f)) }
        addTools(pill, vertical = false)
        pill.addView(gap(10f, false))
        pill.addView(tool("undo"))
        pill.addView(tool("redo", dim = true))
        val left = Ui.row(context).apply { background = Ui.rounded(Ui.SURFACE, 28f, context); setPadding(dp(4f), dp(4f), dp(16f), dp(4f)) }
        left.addView(tool("back"))
        left.addView(title())
        val right = Ui.row(context).apply { background = Ui.rounded(Ui.SURFACE, 28f, context); setPadding(dp(16f), dp(4f), dp(4f), dp(4f)) }
        right.addView(pages())
        right.addView(tool("more"))
        val m = dp(12f)
        return FrameLayout(context).apply {
            addView(page(dark), FrameLayout.LayoutParams(-1, -1))
            addView(left, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(m, m, m, m) })
            addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { setMargins(m, m, m, m) })
            addView(right, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(m, m, m, m) })
        }
    }

    /** C: the tools down the left edge, so the page keeps the full height. */
    private fun side(dark: Boolean): View {
        val bar = Ui.column(context).apply {
            setBackgroundColor(Ui.SURFACE); setPadding(dp(6f), dp(8f), dp(6f), dp(8f)); gravity = Gravity.CENTER_HORIZONTAL
        }
        bar.addView(tool("back"))
        bar.addView(gap(10f, true))
        addTools(bar, vertical = true, widths = false)
        bar.addView(stretch(true))
        bar.addView(tool("undo"))
        bar.addView(tool("redo", dim = true))
        bar.addView(tool("more"))
        return Ui.row(context).apply {
            addView(bar, LinearLayout.LayoutParams(-2, -1))
            addView(page(dark), LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    @Test fun writingScreenProposals() {
        assumeTrue(out.isNotEmpty())
        save(top(dark = true), "diseno-A-barra-arriba.png")
        save(floating(dark = true), "diseno-B-flotante.png")
        save(side(dark = true), "diseno-C-barra-costado.png")
        save(top(dark = false), "diseno-A-papel-claro.png")
    }

    private fun save(view: View, name: String) {
        val w = 2304; val h = 1440
        fun layout() {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
        }
        layout()
        val ink = view.findViewWithTag<OwnSurface>("ink")
        // The recording was made for a page under the bench's bar; nudge it to sit well on each layout.
        Replay.into(ink, Replay.load("synthetic.csv"), scale = ink.width / 2304f * 0.92f)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out).mkdirs()
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
