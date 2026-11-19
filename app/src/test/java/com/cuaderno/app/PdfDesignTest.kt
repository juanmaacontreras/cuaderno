package com.cuaderno.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cuaderno.app.page.Render
import com.juanma.kit.Ui
import cuaderno.core.Document
import cuaderno.core.Library
import cuaderno.core.Notebook
import cuaderno.core.NotebookFile
import cuaderno.core.Page
import cuaderno.core.Paper
import cuaderno.core.Picture
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Proposals for phase 4 (PDFs), drawn over the real screens, so Juanma judges images. The PDF
 * pages here are made-up course handouts drawn as pictures; nothing here is the app yet.
 * Run by tools/screens.sh.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
class PdfDesignTest {
    private val out = System.getProperty("cuaderno.screens") ?: ""
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun dp(v: Float) = Ui.dp(context, v)
    private val root: File get() = File(context.filesDir, "notebooks")
    private val now = "2026-11-19T10:00:00-03:00"

    // --- a made-up handout: "Teoría de Circuitos II · Guía 3" ------------------------------

    private val exercises = listOf(
        "Leyes de Kirchhoff" to listOf("En el circuito de la figura, hallar las corrientes de rama",
            "y la potencia entregada por cada fuente. Verificar el balance."),
        "Teorema de Thévenin" to listOf("Obtener el equivalente de Thévenin entre los bornes a y b",
            "y calcular la corriente sobre una carga de 10 Ω."),
        "Régimen sinusoidal" to listOf("Para v(t) = 220√2 sen(314 t), hallar la impedancia equivalente,",
            "la corriente fasorial y el factor de potencia."),
        "Resonancia" to listOf("Determinar la frecuencia de resonancia del RLC serie, el ancho",
            "de banda y el factor de mérito Q."),
    )

    /** Page [n] of the handout, at 2 pixels a point, as a JPEG like an imported page would be shown. */
    private fun handout(n: Int): ByteArray {
        val k = 2f
        val image = Bitmap.createBitmap((595 * k).toInt(), (842 * k).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(image)
        c.drawColor(0xFFFFFFFF.toInt())
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1F2328.toInt(); textSize = 11f * k; typeface = Typeface.SERIF }
        val head = Paint(ink).apply { textSize = 9f * k; color = 0xFF555B66.toInt() }
        val bold = Paint(ink).apply { textSize = 13f * k; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        c.drawText("Universidad · Ingeniería Electromecánica", 50f * k, 40f * k, head)
        c.drawText("Teoría de Circuitos II · Guía 3", 400f * k, 40f * k, head)
        c.drawLine(50f * k, 48f * k, 545f * k, 48f * k, head)
        val (name, lines) = exercises[n % exercises.size]
        c.drawText("Ejercicio ${n + 1}. $name", 50f * k, 90f * k, bold)
        var y = 115f
        for (line in lines) { c.drawText(line, 50f * k, y * k, ink); y += 17f }
        // The figure: a source and three resistors, as a handout draws them.
        val wire = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * k; color = 0xFF1F2328.toInt() }
        fun resistor(x0: Float, y0: Float, horizontal: Boolean) {
            val p = Path(); p.moveTo(x0 * k, y0 * k)
            for (i in 0..6) {
                val off = if (i == 0 || i == 6) 0f else if (i % 2 == 0) -6f else 6f
                if (horizontal) p.lineTo((x0 + i * 7f) * k, (y0 + off) * k) else p.lineTo((x0 + off) * k, (y0 + i * 7f) * k)
            }
            c.drawPath(p, wire)
        }
        val top = 190f; val bottom = 300f
        c.drawCircle(140f * k, 245f * k, 16f * k, wire)
        c.drawText("+", 135f * k, 238f * k, ink); c.drawText("E₁", 105f * k, 250f * k, ink)
        c.drawLine(140f * k, (top) * k, 140f * k, 229f * k, wire); c.drawLine(140f * k, 261f * k, 140f * k, bottom * k, wire)
        c.drawLine(140f * k, top * k, 230f * k, top * k, wire); resistor(230f, top, true); c.drawLine(272f * k, top * k, 360f * k, top * k, wire)
        c.drawText("R₁", 240f * k, (top - 12) * k, ink)
        c.drawLine(300f * k, top * k, 300f * k, 224f * k, wire); resistor(300f, 224f, false); c.drawLine(300f * k, 266f * k, 300f * k, bottom * k, wire)
        c.drawText("R₂", 312f * k, 250f * k, ink)
        c.drawLine(360f * k, top * k, 450f * k, top * k, wire); c.drawLine(450f * k, top * k, 450f * k, 224f * k, wire)
        resistor(450f, 224f, false); c.drawLine(450f * k, 266f * k, 450f * k, bottom * k, wire)
        c.drawText("R₃", 462f * k, 250f * k, ink)
        c.drawLine(140f * k, bottom * k, 450f * k, bottom * k, wire)
        c.drawText("E₁ = 24 V   R₁ = 4 Ω   R₂ = 6 Ω   R₃ = 12 Ω", 150f * k, 330f * k, ink)
        c.drawText("— ${n + 1} —", 285f * k, 815f * k, head)
        val bytes = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 90, bytes)
        return bytes.toByteArray()
    }

    /** The handout as a notebook: each PDF page shown under the ink (phase 4 keeps the PDF itself; here a picture). */
    private fun guide(file: File, pages: Int) {
        file.parentFile!!.mkdirs()
        val sheets = (0 until pages).map { n ->
            val bytes = handout(n)
            Page("p${n + 1}", Page.A4_WIDTH, Page.A4_HEIGHT, Paper.BLANK, emptyList(),
                listOf(Picture(Picture.nameFor(bytes, "jpg"), bytes, 0f, 0f, Page.A4_WIDTH, Page.A4_HEIGHT)))
        }
        NotebookFile.write(file, Notebook("nb-guia", "Guía 3 · Circuitos", now, now, sheets))
    }

    private fun plain(file: File, title: String, paper: Paper) {
        file.parentFile!!.mkdirs()
        val doc = Document(Notebook("nb-" + file.nameWithoutExtension, title, now, now, listOf(Page("p1", 595f, 842f, paper, emptyList()))))
        NotebookFile.write(file, doc.notebook(now))
    }

    private fun setUpLibrary(): File {
        root.deleteRecursively()
        val library = Library(root)
        library.addSubject("Circuitos", Ui.ACCENT_DEEP)
        library.addSubject("Máquinas", Ui.GREEN_DEEP)
        library.addSubject("Comunicaciones", Ui.ORANGE)
        val guia = File(root, "Circuitos/nb-guia.cuaderno")
        guia(guia, 4)
        plain(File(root, "Circuitos/nb-teoria.cuaderno"), "Teoría · Unidad 3", Paper.GRID)
        plain(File(root, "Circuitos/nb-practica.cuaderno"), "Práctica", Paper.RULED)
        library.last = guia
        return guia
    }

    private fun guia(file: File, pages: Int) = guide(file, pages)

    private fun hideUpdate(view: View) {
        if (view is TextView && view.text.startsWith(Strings.UPDATE)) view.visibility = View.GONE
        if (view is ViewGroup) for (i in 0 until view.childCount) hideUpdate(view.getChildAt(i))
    }

    private fun find(view: View, text: String): View? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), text)?.let { return it }
        return null
    }

    // --- 1: importing ------------------------------------------------------------------

    private fun library(): View {
        setUpLibrary()
        context.getSharedPreferences("cuaderno", Context.MODE_PRIVATE).edit().putString("library_shown", "Circuitos").apply()
        val activity = Robolectric.buildActivity(LibraryActivity::class.java).setup().get()
        val decor = activity.window.decorView
        layout(decor)
        // Beside "Cuaderno nuevo": "Importar PDF", outlined, the second way to start a notebook.
        val add = find(decor, "+  " + Strings.NEW_NOTEBOOK)!!
        val head = add.parent as LinearLayout
        head.addView(Ui.button(activity, "Importar PDF", filled = false) {}, head.indexOfChild(add),
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10f) })
        // A PDF's cover says so, small, in a corner.
        (find(decor, Strings.pages(4) + " · " + Strings.TODAY) as? TextView)?.text = "PDF · " + Strings.pages(4) + " · " + Strings.TODAY
        return decor
    }

    // --- 2: writing on a handout ---------------------------------------------------------

    private fun writing(): WriteActivity {
        val file = setUpLibrary()
        WriteActivity.lowLatency = false
        val intent = Intent(context, WriteActivity::class.java).putExtra(WriteActivity.NOTEBOOK, file.path)
        val activity = Robolectric.buildActivity(WriteActivity::class.java, intent).setup().get()
        layout(activity.window.decorView)
        hideUpdate(activity.window.decorView)
        return activity
    }

    /** His answer under the figure: the handout's empty half, in his ink. */
    private fun answer(activity: WriteActivity) {
        val page = activity.page
        fun x(pt: Float) = page.offsetX + pt * page.scale
        fun y(pt: Float) = page.offsetY + pt * page.scale
        fun stroke(color: Int, points: List<Pair<Float, Float>>) {
            page.color = color
            page.penDown(x(points[0].first), y(points[0].second))
            for ((px, py) in points.drop(1)) page.penMove(x(px), y(py))
            page.penUp()
        }
        // Mesh currents drawn on the figure, in blue.
        fun loop(cx: Float, cy: Float, r: Float) = (0..40).map { i ->
            val a = Math.toRadians(30.0 + i * 8.0)
            Pair(cx + r * kotlin.math.cos(a).toFloat(), cy + r * 0.8f * kotlin.math.sin(a).toFloat())
        }
        stroke(Ui.ACCENT_DEEP, loop(220f, 245f, 32f))
        stroke(Ui.ACCENT_DEEP, loop(375f, 245f, 32f))
        // The working, written below, as handwriting-like waves on lines.
        val ink = Render.ink(false)
        var line = 380f
        for (len in listOf(380f, 300f, 420f, 250f, 340f)) {
            stroke(ink, (0..60).map { i -> Pair(60f + len * i / 60, line + 4f * kotlin.math.sin(i * 1.7f) + 2f * kotlin.math.sin(i * 0.6f)) })
            line += 26f
        }
        // The result, boxed in red.
        stroke(ink, (0..30).map { i -> Pair(70f + 150f * i / 30, line + 10f + 4f * kotlin.math.sin(i * 1.9f)) })
        stroke(Ui.RED, listOf(60f to line - 8f, 232f to line - 8f, 232f to line + 26f, 60f to line + 26f, 60f to line - 8f))
        // Down to the figure and the working under it.
        page.scrollBy(0f, -165f * page.scale)
    }

    // --- 3 and 4: the pages panel with favourites, and the PDF's own index -----------------

    private fun tabs(activity: WriteActivity, chosen: Int): View = Ui.row(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8f), 0, dp(8f), dp(8f))
        for ((n, label) in listOf("Todas", "★", "Índice").withIndex()) {
            addView(Ui.text(activity, label, 13f, if (n == chosen) Ui.BG else if (n == 1) Ui.YELLOW else Ui.TEXT, bold = n == chosen).apply {
                background = if (n == chosen) Ui.rounded(Ui.ACCENT, 12f, activity) else null
                setPadding(dp(8f), dp(5f), dp(8f), dp(5f)); isSingleLine = true
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(2f) })
        }
    }

    private fun panel(activity: WriteActivity): LinearLayout {
        activity.bar.getChildAt(1).performClick()
        layout(activity.window.decorView)
        // Thumbnails are drawn on their own thread: wait for them.
        Thread.sleep(1500)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        layout(activity.window.decorView)
        return activity.pages.view
    }

    private fun favourites(): View {
        val activity = writing()
        answer(activity)
        val view = panel(activity)
        view.addView(tabs(activity, 0), 1)
        layout(activity.window.decorView)
        // A star on the pages marked: 1 and 3.
        val list = (view.getChildAt(2) as ViewGroup).getChildAt(0) as ViewGroup
        for (n in listOf(0, 2)) {
            val item = list.getChildAt(n) as? ViewGroup ?: continue
            val label = item.getChildAt(1) as TextView
            label.text = android.text.SpannableString("${n + 1}   ★").apply {
                setSpan(android.text.style.ForegroundColorSpan(Ui.YELLOW), length - 1, length, 0)
            }
        }
        // And in the page's menu: "Marcar como favorita".
        return activity.window.decorView
    }

    private fun index(): View {
        val activity = writing()
        val view = panel(activity)
        view.removeViewAt(1)
        view.addView(tabs(activity, 2), 1)
        val entries = Ui.column(activity).apply { setPadding(dp(8f), dp(4f), dp(8f), 0) }
        for ((n, e) in exercises.withIndex()) {
            entries.addView(Ui.row(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
                if (n == 0) background = Ui.rounded(Ui.RAISED, 10f, activity)
                addView(Ui.text(activity, "${n + 1}. ${e.first}", 13f, Ui.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
                addView(Ui.text(activity, "${n + 1}", 13f, Ui.DIM))
            })
        }
        view.addView(entries, 2, LinearLayout.LayoutParams(-1, -2))
        return activity.window.decorView
    }

    @Test fun pdfProposals() {
        assumeTrue(out.isNotEmpty())
        save(library(), "fase4-1-importar.png")
        val activity = writing()
        answer(activity)
        save(activity.window.decorView, "fase4-2-guia-anotada.png")
        save(favourites(), "fase4-3-favoritas.png")
        save(index(), "fase4-4-indice.png")
    }

    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, W, H)
    }

    private fun save(view: View, name: String) {
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
