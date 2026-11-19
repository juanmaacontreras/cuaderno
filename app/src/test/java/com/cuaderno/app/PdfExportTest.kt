package com.cuaderno.app

import android.graphics.Bitmap
import android.graphics.Color
import com.cuaderno.app.page.Render
import com.juanma.kit.Ui
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import cuaderno.core.Page
import cuaderno.core.Paper
import cuaderno.core.PdfPage
import cuaderno.core.Stroke
import cuaderno.core.StrokeBuilder
import cuaderno.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** A notebook with a PDF goes out with the PDF as it came (text stays text) and the ink on top, where it was written. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PdfExportTest {
    @get:Rule val folder = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun pdfbox() = PDFBoxResourceLoader.init(context)

    /**
     * A handout made with PdfBox: page 1 A4 with "Ejercicio 1" and a red square at 400..450
     * points from the top-left; page 2 stored portrait but turned 90 degrees (seen landscape).
     */
    private fun handout(locked: Boolean = false): ByteArray {
        val doc = PDDocument()
        val a = PDPage(PDRectangle.A4)
        doc.addPage(a)
        PDPageContentStream(doc, a).use { cs ->
            cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 20f); cs.newLineAtOffset(50f, 780f)
            cs.showText("Ejercicio 1"); cs.endText()
            cs.setNonStrokingColor(1f, 0f, 0f)
            cs.addRect(400f, 842f - 450f, 50f, 50f); cs.fill()
            // Left in a strange state on purpose: our ink must not inherit it.
            cs.transform(com.tom_roush.pdfbox.util.Matrix(0.1f, 0f, 0f, 0.1f, 0f, 0f))
            cs.setStrokingColor(0f, 1f, 0f)
        }
        val b = PDPage(PDRectangle.A4).apply { rotation = 90 }
        doc.addPage(b)
        if (locked) doc.protect(StandardProtectionPolicy("dueño", "", AccessPermission().apply { setCanModify(false) }).apply { encryptionKeyLength = 128 })
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, color: Int = Ui.BG, tool: Tool = Tool.PEN, width: Float = 4f): Stroke =
        StrokeBuilder(tool, color, width).apply {
            for (i in 0..10) add(x0 + (x1 - x0) * i / 10, y0 + (y1 - y0) * i / 10, 0.5f, i * 10f)
        }.build()

    private fun export(pages: List<Page>): PDDocument {
        val out = File(folder.root, "out.pdf")
        assertTrue(PdfExport.write(context, pages, out))
        return PDDocument.load(out)
    }

    /** The exported page [i] as pixels, one per point. */
    private fun seen(doc: PDDocument, i: Int): Bitmap = PDFRenderer(doc).renderImage(i, 1f)

    private fun dark(c: Int) = Color.red(c) + Color.green(c) + Color.blue(c) < 200

    @Test fun thePdfsTextStaysTextAndTheInkIsWhereItWasWritten() {
        val bytes = handout()
        val pdf = PdfPage(PdfPage.nameFor(bytes), bytes, 0)
        val page = Page("p1", 595f, 842f, Paper.BLANK, listOf(line(100f, 300f, 300f, 300f)), pdf = pdf)
        export(listOf(page)).use { doc ->
            assertEquals(1, doc.numberOfPages)
            assertTrue("searchable, not a picture", PDFTextStripper().getText(doc).contains("Ejercicio 1"))
            val image = seen(doc, 0)
            assertTrue("the ink where it was written", dark(image.getPixel(200, 300)))
            assertTrue("not elsewhere: the PDF's state did not move it", !dark(image.getPixel(200, 320)))
            val red = image.getPixel(425, 425)
            assertTrue("the PDF's own drawing is still there", Color.red(red) > 200 && Color.green(red) < 80)
        }
    }

    @Test fun onATurnedPageTheInkLandsWhereItWasSeen() {
        val bytes = handout()
        // As seen: landscape, 842 wide. A short line near its top-left corner.
        val page = Page("p2", 842f, 595f, Paper.BLANK, listOf(line(100f, 80f, 200f, 80f)), pdf = PdfPage(PdfPage.nameFor(bytes), bytes, 1))
        export(listOf(page)).use { doc ->
            val image = seen(doc, 0)
            assertTrue("rendered as seen, landscape", image.width > image.height)
            assertTrue("top-left, where it was written", dark(image.getPixel(150, 80)))
            assertTrue(!dark(image.getPixel(150, 595 - 80)))
            assertTrue(!dark(image.getPixel(842 - 150, 80)))
        }
    }

    @Test fun theNotebooksOwnPagesGoBesideThePdfsAndBlankOnesStayOut() {
        val bytes = handout()
        val pages = listOf(
            Page("p1", 595f, 842f, Paper.BLANK, emptyList(), pdf = PdfPage(PdfPage.nameFor(bytes), bytes, 0)),
            Page("p2", 595f, 842f, Paper.GRID, listOf(line(100f, 100f, 300f, 100f, Ui.RED),
                line(100f, 200f, 300f, 200f, Ui.YELLOW, Tool.HIGHLIGHTER, 12f))),
            Page("p3", 595f, 842f, Paper.GRID, emptyList()))
        export(pages).use { doc ->
            assertEquals("the blank page is left out", 2, doc.numberOfPages)
            val image = seen(doc, 1)
            assertEquals("the light paper", Render.PAPER_LIGHT, image.getPixel(30, 30) or 0xFF000000.toInt())
            val ink = image.getPixel(200, 100)
            assertTrue("the red ink", Color.red(ink) > Color.blue(ink) + 40)
            val marker = image.getPixel(200, 200)
            assertTrue("the highlighter, see-through over the paper", Color.blue(marker) in 120..230 && Color.red(marker) > 200)
        }
    }

    @Test fun aPdfLockedAgainstChangesStillGoesOut() {
        val bytes = handout(locked = true)
        val page = Page("p1", 595f, 842f, Paper.BLANK, listOf(line(100f, 300f, 300f, 300f)), pdf = PdfPage(PdfPage.nameFor(bytes), bytes, 0))
        export(listOf(page)).use { doc ->
            assertTrue(PDFTextStripper().getText(doc).contains("Ejercicio 1"))
            assertTrue(dark(seen(doc, 0).getPixel(200, 300)))
        }
    }
}
