package com.cuaderno.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the real screens to PNG without a device, for design proposals and to
 * see a change before installing it. Run by tools/screens.sh; skipped otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    private val out = System.getProperty("cuaderno.screens") ?: ""

    private fun write(width: Int, height: Int, name: String, scale: Float, dark: Boolean = false,
                      then: (WriteActivity) -> Unit = {}) {
        assumeTrue(out.isNotEmpty())
        WriteActivity.lowLatency = false
        val activity = Robolectric.buildActivity(WriteActivity::class.java).setup().get()
        val root = activity.window.decorView
        layout(root, width, height)
        activity.page.darkPaper = dark
        activity.page.color = com.cuaderno.app.page.Render.ink(dark)
        Replay.into(activity.page, Replay.load("synthetic.csv"), scale = scale, dx = 20f, dy = -60f * scale)
        then(activity)
        layout(root, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(out).mkdirs()
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The tablet lying down, as he uses it. */
    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun writingOnTheTablet() = write(2304, 1440, "1-escribir-tablet.png", scale = 0.9f)

    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun writingOnDarkPaper() = write(2304, 1440, "3-escribir-papel-oscuro.png", scale = 0.9f, dark = true)

    @Config(sdk = [34], qualifiers = "w720dp-h1152dp-port-xhdpi")
    @Test fun writingOnTheTabletUpright() = write(1440, 2304, "2-escribir-tablet-vertical.png", scale = 0.55f)

    /** The highlighter in use: rows marked in three colours, the ink sharp on top. */
    private fun highlight(activity: WriteActivity) {
        val bar = activity.findViewById<View>(android.R.id.content).let { (it as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup }.getChildAt(0) as android.view.ViewGroup
        bar.getChildAt(3).performClick()                              // the highlighter (after the library button and a gap)
        val page = activity.page
        fun mark(color: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
            page.color = color
            page.penDown(x0, y0)
            for (i in 1..40) page.penMove(x0 + (x1 - x0) * i / 40, y0 + (y1 - y0) * i / 40 + 2f * kotlin.math.sin(i * 0.5f))
            page.penUp()
        }
        mark(com.juanma.kit.Ui.YELLOW, 230f, 800f, 1800f, 800f)
        mark(com.juanma.kit.Ui.GREEN, 1180f, 1088f, 1830f, 1088f)
        mark(com.juanma.kit.Ui.ACCENT, 190f, 305f, 1650f, 305f)
        page.color = com.juanma.kit.Ui.YELLOW
        bar.getChildAt(6).performClick()                              // yellow chosen, as the bar shows it
    }

    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun highlighting() = write(2304, 1440, "4-resaltador.png", scale = 0.9f) { highlight(it) }

    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun highlightingOnDarkPaper() = write(2304, 1440, "5-resaltador-papel-oscuro.png", scale = 0.9f, dark = true) { highlight(it) }

    /** Notebooks in made-up subjects (the repo is public), their first page written with the test recording. */
    private fun shelf() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val library = cuaderno.core.Library(java.io.File(app.filesDir, "notebooks"))
        library.addSubject("Circuitos", LibraryActivity.PALETTE[0])
        library.addSubject("Física II", LibraryActivity.PALETTE[1])
        library.addSubject("Análisis II", LibraryActivity.PALETTE[2])
        val now = java.time.OffsetDateTime.now().withNano(0)
        for ((n, made) in listOf(
            Triple("Teoría · Unidad 3", "Circuitos", cuaderno.core.Paper.GRID),
            Triple("Guía 2 · ejercicios", "Circuitos", cuaderno.core.Paper.GRID),
            Triple("Teoría · Unidad 2", "Circuitos", cuaderno.core.Paper.RULED),
            Triple("Ondas", "Física II", cuaderno.core.Paper.DOTTED),
        ).withIndex()) {
            val file = library.create(made.first, made.second, made.third, now.minusDays(n * 3L).toString(), "nb-$n")
            val page = com.cuaderno.app.page.PageView(app, lowLatency = false)
            page.document = cuaderno.core.Document(cuaderno.core.NotebookFile.read(file))
            layout(page, 1206, 1700)
            for (row in listOf(-60f, 420f, 900f).take(3 - n % 2)) {
                Replay.into(page, Replay.load("synthetic.csv"), scale = 0.42f - n * 0.03f, dx = 20f + n * 30f, dy = row)
            }
            cuaderno.core.NotebookFile.write(file, page.document!!.notebook(now.minusDays(n * 3L).toString()))
        }
        library.last = java.io.File(library.root, "Circuitos/nb-0.cuaderno")
    }

    private fun library(name: String, then: (LibraryActivity) -> Unit = {}) {
        assumeTrue(out.isNotEmpty())
        shelf()
        val activity = Robolectric.buildActivity(LibraryActivity::class.java).setup().get()
        activity.refresh()
        then(activity)
        val root = activity.window.decorView
        layout(root, 2304, 1440)
        val bitmap = Bitmap.createBitmap(2304, 1440, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File(out).mkdirs()
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun theLibrary() = library("6-biblioteca.png")

    @Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-xhdpi")
    @Test fun aNewNotebook() = library("7-cuaderno-nuevo.png") {
        it.newNotebook().input.setText("Guía 3 · filtros")
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
}
