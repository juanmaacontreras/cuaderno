package com.cuaderno.app

import android.content.Context
import com.cuaderno.app.page.Render
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import cuaderno.core.Page
import cuaderno.core.Paper
import cuaderno.core.Picture
import cuaderno.core.Stroke
import cuaderno.core.Tool
import java.io.File
import java.util.IdentityHashMap

/**
 * A notebook that holds an imported PDF, out as a PDF: each of the PDF's pages exactly as it
 * came (its text stays text, searchable and sharp), with what was written on it added on top
 * as vectors; the notebook's own pages as vectors too. Strokes are drawn like on screen
 * ([Render]): one path of constant width through the midpoints, round ends, the highlighter
 * see-through and under the ink, pictures under both. Always on light paper: a PDF is white.
 */
internal object PdfWithInk {
    fun write(context: Context, pages: List<Page>, out: File) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val sources = IdentityHashMap<ByteArray, PDDocument>()
        val doc = PDDocument()
        try {
            for (page in pages) {
                val pdf = page.pdf
                // A PDF locked against changes opens with its empty user password; its pages are
                // copied into a new document, which carries no lock.
                val source = pdf?.let { p -> sources.getOrPut(p.bytes) { PDDocument.load(p.bytes) } }
                val target: PDPage
                val placement: Matrix
                if (source != null && pdf.index < source.numberOfPages) {
                    target = doc.importPage(source.getPage(pdf.index))
                    placement = placement(target.cropBox, target.rotation, page.width, page.height)
                } else {
                    target = PDPage(PDRectangle(page.width, page.height))
                    doc.addPage(target)
                    placement = Matrix(1f, 0f, 0f, -1f, 0f, page.height)
                }
                // Appended after the page's own drawing, which is wrapped so nothing it leaves set reaches the ink.
                PDPageContentStream(doc, target, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.transform(placement)
                    cs.addRect(0f, 0f, page.width, page.height)
                    cs.clip()
                    if (pdf == null || source == null || pdf.index >= source.numberOfPages) paper(cs, page)
                    for (picture in page.pictures) picture(doc, cs, picture)
                    for (pass in listOf(Tool.HIGHLIGHTER, Tool.PEN)) {
                        for (stroke in page.strokes) if (stroke.tool == pass) stroke(cs, stroke)
                    }
                }
            }
            out.parentFile?.mkdirs()
            doc.save(out)
        } finally {
            doc.close()
            for (source in sources.values) source.close()
        }
    }

    /**
     * From the notebook's page points (y down, [width] x [height], the page as it is seen) to
     * the PDF page's own space ([crop] box, y up, before its [rotation]: a page turned 90
     * degrees is stored lying down).
     */
    fun placement(crop: PDRectangle, rotation: Int, width: Float, height: Float): Matrix {
        val llx = crop.lowerLeftX
        val lly = crop.lowerLeftY
        val w = crop.width
        val h = crop.height
        return when (((rotation % 360) + 360) % 360) {
            90 -> Matrix(0f, h / width, w / height, 0f, llx, lly)
            180 -> Matrix(-w / width, 0f, 0f, h / height, llx + w, lly)
            270 -> Matrix(0f, -h / width, -w / height, 0f, llx + w, lly + h)
            else -> Matrix(w / width, 0f, 0f, -h / height, llx, lly + h)
        }
    }

    private fun color(cs: PDPageContentStream, color: Int, stroking: Boolean) {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        if (stroking) cs.setStrokingColor(r, g, b) else cs.setNonStrokingColor(r, g, b)
    }

    /** The notebook's own page: the light sheet and its lines, as on screen. */
    private fun paper(cs: PDPageContentStream, page: Page) {
        color(cs, Render.PAPER_LIGHT, stroking = false)
        cs.addRect(0f, 0f, page.width, page.height)
        cs.fill()
        if (page.paper == Paper.BLANK) return
        color(cs, Render.RULE_LIGHT, stroking = true)
        color(cs, Render.RULE_LIGHT, stroking = false)
        cs.setLineWidth(0.5f)
        when (page.paper) {
            Paper.GRID -> {
                var x = Render.GRID
                while (x < page.width) { cs.moveTo(x, 0f); cs.lineTo(x, page.height); x += Render.GRID }
                var y = Render.GRID
                while (y < page.height) { cs.moveTo(0f, y); cs.lineTo(page.width, y); y += Render.GRID }
                cs.stroke()
            }
            Paper.RULED -> {
                var y = 3 * Render.LINE
                while (y < page.height) { cs.moveTo(0f, y); cs.lineTo(page.width, y); y += Render.LINE }
                cs.stroke()
            }
            Paper.DOTTED -> {
                val r = 0.6f
                var y = Render.GRID
                while (y < page.height) {
                    var x = Render.GRID
                    while (x < page.width) { cs.addRect(x - r, y - r, 2 * r, 2 * r); x += Render.GRID }
                    y += Render.GRID
                }
                cs.fill()
            }
            Paper.BLANK -> {}
        }
    }

    private fun picture(doc: PDDocument, cs: PDPageContentStream, picture: Picture) {
        val image: PDImageXObject = try {
            JPEGFactory.createFromByteArray(doc, picture.bytes)
        } catch (e: Exception) {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(picture.bytes, 0, picture.bytes.size) ?: return
            LosslessFactory.createFromImage(doc, bitmap)
        }
        // Upside down in a space whose y goes down: drawn from its bottom edge, upwards.
        cs.drawImage(image, picture.x, picture.y + picture.height, picture.width, -picture.height)
    }

    /** One stroke, the same path as [Render.stroke]'s, with the quadratic pieces written as cubic ones. */
    private fun stroke(cs: PDPageContentStream, stroke: Stroke) {
        cs.saveGraphicsState()
        if (stroke.tool == Tool.HIGHLIGHTER) {
            color(cs, stroke.color, stroking = true)
            cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { strokingAlphaConstant = Render.highlightAlpha(false) })
        } else {
            color(cs, Render.shown(stroke.color, false), stroking = true)
        }
        cs.setLineWidth(stroke.width)
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        var px = stroke.x(0)
        var py = stroke.y(0)
        cs.moveTo(px, py)
        if (stroke.size == 1) {
            cs.lineTo(px, py)
        } else {
            for (i in 1 until stroke.size) {
                val qx = stroke.x(i - 1)
                val qy = stroke.y(i - 1)
                val ex = (qx + stroke.x(i)) / 2
                val ey = (qy + stroke.y(i)) / 2
                cs.curveTo(px + 2f / 3 * (qx - px), py + 2f / 3 * (qy - py), ex + 2f / 3 * (qx - ex), ey + 2f / 3 * (qy - ey), ex, ey)
                px = ex
                py = ey
            }
            cs.lineTo(stroke.x(stroke.size - 1), stroke.y(stroke.size - 1))
        }
        cs.stroke()
        cs.restoreGraphicsState()
    }
}
