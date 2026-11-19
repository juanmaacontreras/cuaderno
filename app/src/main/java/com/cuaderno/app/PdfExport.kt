package com.cuaderno.app

import android.graphics.pdf.PdfDocument
import com.cuaderno.app.page.Render
import cuaderno.core.Page
import java.io.File

/**
 * The notebook as a PDF, one PDF page per sheet at its real size (A4 stays
 * A4), drawn by the same code as the screen. This is the way out of the app:
 * whatever happens to Cuaderno, the notes can leave as a PDF.
 */
object PdfExport {
    /** The pages that go out: those with ink, a picture or a PDF page on them. */
    fun taken(pages: List<Page>): List<Page> = pages.filter { !it.isBlank }

    /**
     * Writes the pages that have something on them (ink, a picture, a PDF page), on the paper
     * being used; false if there are none. A notebook with an imported PDF goes out through
     * [PdfWithInk]: the PDF as it came, the ink on top as vectors, on light paper.
     */
    fun write(context: android.content.Context, pages: List<Page>, out: File, dark: Boolean = false): Boolean {
        val written = taken(pages)
        if (written.isEmpty()) return false
        if (written.any { it.pdf != null }) {
            PdfWithInk.write(context, written, out)
            return true
        }
        val render = Render().also { it.dark = dark }
        val pdf = PdfDocument()
        try {
            for ((i, page) in written.withIndex()) {
                val info = PdfDocument.PageInfo.Builder(page.width.toInt(), page.height.toInt(), i + 1).create()
                val sheet = pdf.startPage(info)
                render.page(sheet.canvas, page)
                pdf.finishPage(sheet)
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
            render.close()
        }
        return true
    }
}
