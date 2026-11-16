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
    /** Writes the pages that have something on them, on the paper being used; false if there are none. */
    fun write(pages: List<Page>, out: File, dark: Boolean = false): Boolean {
        val written = pages.filter { it.strokes.isNotEmpty() }
        if (written.isEmpty()) return false
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
        }
        return true
    }
}
