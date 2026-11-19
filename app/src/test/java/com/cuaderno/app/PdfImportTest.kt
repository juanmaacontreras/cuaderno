package com.cuaderno.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cuaderno.app.page.PdfPainter
import com.cuaderno.app.page.Render
import com.juanma.kit.Ui
import cuaderno.core.Library
import cuaderno.core.NotebookFile
import cuaderno.core.Page
import cuaderno.core.Paper
import cuaderno.core.PdfPage
import cuaderno.core.StrokeBuilder
import cuaderno.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** A PDF becomes a notebook to write on: read, drawn under the ink, imported, exported. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1152dp-h720dp-land-mdpi")
class PdfImportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val root: File get() = File(context.filesDir, "notebooks")

    /**
     * Robolectric has no PDF reader: a stand-in reads "FAKEPDF 595x842;612x792" as a PDF of
     * those pages, the first with a red square at 100..200 points. The real reader is
     * Android's, tried on the emulator.
     */
    private val fake = PdfPainter.Reader { bytes ->
        val text = String(bytes)
        require(text.startsWith("FAKEPDF ")) { "not a PDF" }
        val sizes = text.removePrefix("FAKEPDF ").split(";").map { it.split("x").let { (w, h) -> w.toFloat() to h.toFloat() } }
        object : PdfPainter.Doc {
            override val pages = sizes.size
            override fun size(i: Int) = sizes[i]
            override fun render(i: Int, image: Bitmap, clip: android.graphics.Rect, transform: android.graphics.Matrix) {
                rendered++
                if (i != 0) return
                val c = Canvas(image)
                c.clipRect(clip)
                c.concat(transform)
                c.drawRect(100f, 100f, 200f, 200f, Paint().apply { color = Color.RED })
            }
            override fun close() {}
        }
    }
    private var rendered = 0

    @Before fun setUp() {
        WriteActivity.lowLatency = false
        root.deleteRecursively()
        PdfPainter.reader = fake
    }

    @org.junit.After fun realReader() { PdfPainter.reader = PdfPainter.Android }

    private fun handout(): ByteArray = "FAKEPDF 595x842;612x792".toByteArray()

    private fun sheet(bytes: ByteArray, index: Int = 0, strokes: Boolean = false): Page {
        val ink = if (!strokes) emptyList() else listOf(StrokeBuilder(Tool.PEN, Ui.ACCENT_DEEP, 3f).apply {
            add(300f, 400f, 0.5f, 0f); add(400f, 400f, 0.5f, 10f)
        }.build())
        return Page("p1", 595f, 842f, Paper.GRID, ink, pdf = PdfPage(PdfPage.nameFor(bytes), bytes, index))
    }

    private fun drawn(page: Page, dark: Boolean = false): Bitmap {
        val image = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
        Render().apply { this.dark = dark }.page(Canvas(image), page)
        return image
    }

    @Test fun thePagesOfAPdfAreReadWithTheirSizes() {
        assertEquals(listOf(595f to 842f, 612f to 792f), PdfPainter.sizes(handout()))
        assertNull("not a PDF", PdfPainter.sizes("hola".toByteArray()))
    }

    @Test fun aPdfPageIsDrawnUnderTheInkInsteadOfThePaper() {
        val image = drawn(sheet(handout(), strokes = true))
        val square = image.getPixel(150, 150)
        assertTrue("the PDF's red square", Color.red(square) > 200 && Color.green(square) < 60)
        assertEquals("white where the PDF is blank, no grid lines", Color.WHITE, image.getPixel(14, 14) or 0xFF000000.toInt())
        assertEquals("and white along a grid line", Color.WHITE, image.getPixel(14 * 3, 300))
        val ink = image.getPixel(350, 400)
        assertTrue("the ink on top", Color.blue(ink) > Color.red(ink))
    }

    @Test fun onDarkPaperThePdfTurnsDarkAndKeepsItsColour() {
        val image = drawn(sheet(handout()), dark = true)
        val blank = image.getPixel(30, 30)
        assertTrue("white became the dark sheet", Color.red(blank) < 80 && Color.blue(blank) < 90)
        val square = image.getPixel(150, 150)
        assertTrue("red stays reddish", Color.red(square) > Color.green(square) && Color.red(square) > Color.blue(square))
    }

    @Test fun aPdfThatCannotBeReadShowsPlainPaperAndTheInk() {
        val image = drawn(Page("p1", 595f, 842f, Paper.GRID, sheet(handout(), strokes = true).strokes,
            pdf = PdfPage("pdf-x.pdf", "roto".toByteArray(), 0)))
        assertEquals("the paper's colour", Render.PAPER_LIGHT, image.getPixel(30, 30))
        val ink = image.getPixel(350, 400)
        assertTrue("the ink is still there", Color.blue(ink) > Color.red(ink))
    }

    @Test fun aPdfPageOutsideTheFileShowsPlainPaper() {
        assertEquals(Render.PAPER_LIGHT, drawn(sheet(handout(), index = 5)).getPixel(30, 30))
    }

    private fun texts(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun importingMakesANotebookOfItsPagesInTheSubjectShown() {
        Library(root).addSubject("Circuitos", Ui.ACCENT)
        context.getSharedPreferences("cuaderno", 0).edit().putString("library_shown", "Circuitos").apply()
        val pdf = File(context.cacheDir, "Guía 3.pdf").apply { writeBytes(handout()) }
        val activity = Robolectric.buildActivity(LibraryActivity::class.java).setup().get()
        assertTrue("the button is there", Strings.IMPORT_PDF in texts(activity.window.decorView))
        val file = activity.importPdf(Uri.fromFile(pdf), "Circuitos")
        assertNotNull(file)
        val n = NotebookFile.read(file!!)
        assertEquals(listOf(0, 1), n.pages.map { it.pdf!!.index })
        assertEquals(listOf(612f, 792f), listOf(n.pages[1].width, n.pages[1].height))
        assertEquals("Circuitos", file.parentFile!!.name)
        activity.refresh()
        assertTrue("its cover says it is a PDF", texts(activity.window.decorView).any { it.startsWith("PDF · 2 páginas") })
    }

    @Test fun somethingThatIsNotAPdfIsNotImported() {
        val bad = File(context.cacheDir, "x.pdf").apply { writeBytes("hola".toByteArray()) }
        val activity = Robolectric.buildActivity(LibraryActivity::class.java).setup().get()
        assertNull(activity.importPdf(Uri.fromFile(bad), null))
        assertTrue("no notebook left behind", Library(root).notebooks().isEmpty())
    }

    @Test fun aZoomedInPartIsDrawnSharpAndOnlyThatPart() {
        // A tile's worth at 4 pixels a point: the square's corner at 100 points lands at pixel 0.
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(image)
        c.scale(4f, 4f)
        c.translate(-75f, -75f)
        Render().page(c, sheet(handout()), top = 75f, bottom = 125f, left = 75f, right = 125f)
        assertEquals("blank before the square", Color.WHITE, image.getPixel(98, 98))
        val inside = image.getPixel(102, 102)
        assertTrue("red right after, no blur across 2 pixels", Color.red(inside) > 200 && Color.green(inside) < 60)
    }

    @Test fun aPdfIsOpenedOnceAndDrawnWhereItShows() {
        val render = Render()
        val bytes = handout()
        val image = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
        render.page(Canvas(image), sheet(bytes))
        render.page(Canvas(image), sheet(bytes, index = 1))
        assertEquals(2, rendered)
        render.page(Canvas(image), sheet(bytes), top = 900f, bottom = 1000f)
        assertEquals("nothing of the page shows: not drawn", 2, rendered)
    }

    @Test fun exportingKeepsPdfPagesWithNothingWrittenOnThem() {
        val pages = listOf(sheet(handout()), Page("p2", 595f, 842f, Paper.BLANK, emptyList()))
        // Robolectric cannot write PDFs either: what the export takes is checked instead.
        assertEquals(listOf("p1"), PdfExport.taken(pages).map { it.id })
    }
}
