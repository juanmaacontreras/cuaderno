package com.cuaderno.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.cuaderno.app.page.PageView
import com.cuaderno.app.page.Render
import com.juanma.kit.Icons
import com.juanma.kit.Ui
import cuaderno.core.Document
import cuaderno.core.Notebook
import cuaderno.core.Page
import cuaderno.core.Paper
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Proposals for phase 2 (notebooks), drawn with the real kit, the real page
 * and real ink so Juanma judges images. Nothing here is the app yet: what he
 * approves gets built. Subject names are made up (the repo is public).
 * Run by tools/screens.sh.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
class NotebooksDesignTest {
    private val out = System.getProperty("cuaderno.screens") ?: ""
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun dp(v: Float) = Ui.dp(context, v)

    // --- pages with real handwriting, for the thumbnails --------------------------------

    /**
     * The test recording written on a page, once per [rows] (pixels down the 2 px-per-point page),
     * placed differently each time so the sheets differ; [keep] cuts the last part off.
     */
    private fun written(paper: Paper, dx: Float, scale: Float, rows: List<Float>, keep: Float = 1f): Page {
        val view = PageView(context, lowLatency = false)
        view.document = Document(Notebook("n", "t", "c", "m", emptyList()), paper)
        view.measure(View.MeasureSpec.makeMeasureSpec(1206, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1700, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1206, 1700)
        for (dy in rows) Replay.into(view, Replay.load("synthetic.csv"), scale = scale, dx = dx, dy = dy)
        val page = view.document!!.pages[0]
        return page.with(page.strokes.take((page.strokes.size * keep).toInt()))
    }

    private fun blank(paper: Paper) = Page("b", Page.A4_WIDTH, Page.A4_HEIGHT, paper, emptyList())

    /** A sheet as a small picture with round corners, [widthDp] wide; [part] of its width shows (its corner, enlarged). */
    private fun thumbnail(page: Page, widthDp: Float, dark: Boolean = false, part: Float = 1f): Bitmap {
        val w = dp(widthDp)
        val h = (w * page.height / page.width).toInt()
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val corner = dp(6f).toFloat()
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, Path.Direction.CW) })
        canvas.scale(w / (page.width * part), w / (page.width * part))
        Render().also { it.dark = dark }.page(canvas, page)
        return image
    }

    private fun picture(bitmap: Bitmap, ring: Boolean = false) = ImageView(context).apply {
        setImageBitmap(bitmap)
        val edge = dp(3f)
        setPadding(edge, edge, edge, edge)
        if (ring) background = GradientDrawable().apply { cornerRadius = dp(9f).toFloat(); setColor(Ui.ACCENT) }
        layoutParams = LinearLayout.LayoutParams(bitmap.width + 2 * edge, bitmap.height + 2 * edge)
    }

    // --- small pieces ---------------------------------------------------------------

    private fun icon(name: String, color: Int, sizeDp: Float = 22f) = View(context).apply {
        background = Icons.drawable(name, color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun dot(color: Int) = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(12f), dp(12f))
    }

    private fun stretch() = View(context).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    private fun pill(label: String, on: Boolean) = Ui.text(context, label, 15f, if (on) Ui.BG else Ui.TEXT, bold = on).apply {
        setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
        background = Ui.rounded(if (on) Ui.ACCENT else Ui.RAISED, 20f, context)
    }

    /** One line of the subjects list: dot, name, count; [on] is the one open. */
    private fun subject(name: String, count: Int, color: Int?, on: Boolean, glyph: String? = null): View {
        val row = Ui.row(context).apply {
            setPadding(dp(14f), dp(11f), dp(14f), dp(11f))
            if (on) background = Ui.rounded(Ui.RAISED, 12f, context)
        }
        when {
            glyph != null -> row.addView(icon(glyph, Ui.DIM, 20f))
            color != null -> row.addView(dot(color))
            else -> row.addView(View(context), LinearLayout.LayoutParams(dp(12f), 1))
        }
        row.addView(Ui.text(context, name, 16f, if (on) Ui.BRIGHT else Ui.TEXT, bold = on),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12f) })
        if (count > 0) row.addView(Ui.text(context, "$count", 14f, Ui.DIM))
        return row
    }

    // --- 1: the library --------------------------------------------------------------

    private class Cover(val title: String, val about: String, val page: Page)

    private fun covers() = listOf(
        Cover("Teoría · Unidad 3", "18 páginas · hoy", written(Paper.GRID, 20f, 0.42f, listOf(-60f, 420f, 900f))),
        Cover("Guía 2 · ejercicios", "9 páginas · ayer", written(Paper.GRID, 120f, 0.32f, listOf(40f, 450f), keep = 0.8f)),
        Cover("Teoría · Unidad 2", "22 páginas · 10 nov", written(Paper.RULED, 40f, 0.4f, listOf(-40f, 500f, 1050f), keep = 0.9f)),
        Cover("Parcial 1 · repaso", "4 páginas · 3 nov", written(Paper.GRID, 60f, 0.36f, listOf(-80f), keep = 0.6f)),
        Cover("Teoría · Unidad 1", "16 páginas · 27 oct", written(Paper.BLANK, 0f, 0.45f, listOf(-80f, 480f, 1000f), keep = 0.95f)),
    )

    private fun library(): View {
        val rail = Ui.column(context).apply {
            setBackgroundColor(Ui.SURFACE)
            setPadding(dp(14f), dp(22f), dp(14f), dp(16f))
        }
        rail.addView(Ui.text(context, "Cuadernos", 22f, Ui.BRIGHT, bold = true).apply { setPadding(dp(14f), 0, 0, dp(16f)) })
        rail.addView(subject("Todos", 11, null, false))
        rail.addView(subject("Circuitos", 5, Ui.ACCENT, true))
        rail.addView(subject("Física II", 2, Ui.ORANGE, false))
        rail.addView(subject("Análisis II", 2, Ui.GREEN, false))
        rail.addView(subject("Electrónica", 2, Ui.YELLOW, false))
        rail.addView(subject("Materia nueva", 0, null, false, glyph = "add"))
        rail.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(subject("Papelera", 1, null, false, glyph = "delete"))

        val head = Ui.row(context)
        val names = Ui.column(context)
        names.addView(Ui.text(context, "Circuitos", 28f, Ui.BRIGHT, bold = true))
        names.addView(Ui.text(context, "5 cuadernos", 15f, Ui.DIM))
        head.addView(names)
        head.addView(stretch())
        head.addView(Ui.button(context, "+  Cuaderno nuevo", filled = true) {})

        val grid = Ui.column(context)
        var row = Ui.row(context)
        for ((n, cover) in covers().withIndex()) {
            if (n % 4 == 0) {
                row = Ui.row(context).apply { gravity = Gravity.TOP }
                grid.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (n == 0) 26f else 22f) })
            }
            val card = Ui.column(context)
            card.addView(picture(thumbnail(cover.page, 168f)))
            card.addView(Ui.text(context, cover.title, 15f, Ui.BRIGHT, bold = true).apply { setPadding(0, dp(10f), 0, 0) })
            card.addView(Ui.text(context, cover.about, 13f, Ui.DIM))
            row.addView(card, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(30f) })
        }

        val main = Ui.column(context).apply { setBackgroundColor(Ui.BG); setPadding(dp(36f), dp(28f), dp(36f), 0) }
        main.addView(head, LinearLayout.LayoutParams(-1, -2))
        main.addView(grid)
        return Ui.row(context).apply {
            gravity = Gravity.TOP
            addView(rail, LinearLayout.LayoutParams(dp(250f), -1))
            addView(main, LinearLayout.LayoutParams(0, -1, 1f))
        }
    }

    // --- 2: a new notebook -------------------------------------------------------------

    private fun newNotebook(): View {
        val card = Ui.column(context).apply {
            background = Ui.rounded(Ui.SURFACE, 22f, context)
            setPadding(dp(30f), dp(26f), dp(30f), dp(26f))
        }
        card.addView(Ui.text(context, "Cuaderno nuevo", 22f, Ui.BRIGHT, bold = true))
        card.addView(Ui.text(context, "Nombre", 14f, Ui.DIM).apply { setPadding(0, dp(20f), 0, dp(8f)) })
        card.addView(Ui.text(context, "Guía 3 · filtros", 17f, Ui.BRIGHT).apply {
            setPadding(dp(16f), dp(13f), dp(16f), dp(13f))
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(Ui.BG); setStroke(dp(2f), Ui.ACCENT) }
        }, LinearLayout.LayoutParams(-1, -2))
        card.addView(Ui.text(context, "Materia", 14f, Ui.DIM).apply { setPadding(0, dp(20f), 0, dp(8f)) })
        val subjects = Ui.row(context)
        for ((i, name) in listOf("Circuitos", "Física II", "Análisis II", "Electrónica", "+").withIndex()) {
            subjects.addView(pill(name, i == 0), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8f) })
        }
        card.addView(subjects)
        card.addView(Ui.text(context, "Papel", 14f, Ui.DIM).apply { setPadding(0, dp(20f), 0, dp(8f)) })
        val papers = Ui.row(context).apply { gravity = Gravity.TOP }
        for ((paper, name) in listOf(Paper.GRID to "Cuadriculado", Paper.RULED to "Rayado", Paper.DOTTED to "Punteado", Paper.BLANK to "Liso")) {
            val on = paper == Paper.GRID
            val option = Ui.column(context).apply { gravity = Gravity.CENTER_HORIZONTAL }
            option.addView(picture(thumbnail(blank(paper), 96f, part = 0.3f), ring = on))
            option.addView(Ui.text(context, name, 14f, if (on) Ui.BRIGHT else Ui.TEXT, bold = on).apply { setPadding(0, dp(8f), 0, 0) })
            papers.addView(option, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(22f) })
        }
        card.addView(papers)
        val buttons = Ui.row(context).apply { setPadding(0, dp(26f), 0, 0) }
        buttons.addView(stretch())
        buttons.addView(Ui.button(context, "Cancelar", filled = false) {}, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10f) })
        buttons.addView(Ui.button(context, "Crear", filled = true) {})
        card.addView(buttons, LinearLayout.LayoutParams(-1, -2))

        return FrameLayout(context).apply {
            addView(library(), FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply { setBackgroundColor(0xB3242933.toInt()) }, FrameLayout.LayoutParams(-1, -1))
            addView(card, FrameLayout.LayoutParams(dp(600f), -2, Gravity.CENTER))
        }
    }

    // --- 3: the pages of a notebook, beside the real writing screen ------------------------

    private fun pagesPanel(activity: WriteActivity) {
        // The bar has the library and pages buttons now (v20, v21).
        val row = activity.bar.parent as ViewGroup

        val panel = Ui.column(activity).apply {
            setBackgroundColor(Ui.BG)
            setPadding(dp(18f), dp(18f), dp(18f), 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        panel.addView(Ui.text(activity, "Páginas · 4", 15f, Ui.TEXT, bold = true).apply { setPadding(0, 0, 0, dp(14f)) })
        val sheets = listOf(
            activity.page.document!!.pages[0],
            written(Paper.GRID, 20f, 0.42f, listOf(-60f, 420f, 900f)),
            written(Paper.GRID, 40f, 0.4f, listOf(-40f, 500f), keep = 0.9f),
            written(Paper.GRID, 10f, 0.45f, listOf(-80f), keep = 0.5f),
        )
        for ((n, sheet) in sheets.withIndex()) {
            val item = Ui.column(activity).apply { gravity = Gravity.CENTER_HORIZONTAL }
            item.addView(picture(thumbnail(sheet, 132f), ring = n == 0))
            item.addView(Ui.text(activity, "${n + 1}", 13f, if (n == 0) Ui.BRIGHT else Ui.DIM, bold = n == 0).apply { setPadding(0, dp(5f), 0, dp(14f)) })
            panel.addView(item)
        }
        // Over the page, not beside it: opening it does not shrink the sheet or move the writing.
        val area = row.getChildAt(1) as FrameLayout
        panel.elevation = dp(6f).toFloat()
        area.addView(panel, FrameLayout.LayoutParams(dp(176f), -1, Gravity.START))

        // Holding a page down opens what can be done with it.
        val menu = Ui.column(activity).apply {
            background = Ui.rounded(Ui.RAISED, 14f, activity)
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            elevation = dp(8f).toFloat()
        }
        for ((label, glyph, color) in listOf(
            Triple("Página nueva después", "add", Ui.TEXT),
            Triple("Duplicar", "pages", Ui.TEXT),
            Triple("Papel: cuadriculado", "dot", Ui.TEXT),
            Triple("Mandar a la papelera", "delete", Ui.RED),
        )) {
            val line = Ui.row(activity).apply { setPadding(dp(14f), dp(12f), dp(22f), dp(12f)) }
            line.addView(icon(glyph, color, 20f))
            line.addView(Ui.text(activity, label, 15f, color).apply { setPadding(dp(14f), 0, 0, 0) })
            menu.addView(line)
        }
        area.addView(menu, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(186f); topMargin = dp(290f)
        })
        // The page being held, marked.
        (panel.getChildAt(2) as ViewGroup).getChildAt(0).alpha = 0.7f
    }

    private fun writing(name: String) {
        WriteActivity.lowLatency = false
        val activity = Robolectric.buildActivity(WriteActivity::class.java).setup().get()
        val root = activity.window.decorView
        layout(root)
        activity.page.color = Render.ink(false)
        // Written first: writing rebuilds the bar, which would drop the buttons added to it.
        Replay.into(activity.page, Replay.load("synthetic.csv"), scale = 0.62f, dx = 10f, dy = -40f)
        pagesPanel(activity)
        layout(root)
        save(root, name)
    }

    // --------------------------------------------------------------------------------

    @Test fun notebooksProposals() {
        assumeTrue(out.isNotEmpty())
        save(library(), "fase2-1-biblioteca.png")
        save(newNotebook(), "fase2-2-cuaderno-nuevo.png")
        writing("fase2-3-paginas.png")
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, W, H)
    }

    private fun save(view: View, name: String) {
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
