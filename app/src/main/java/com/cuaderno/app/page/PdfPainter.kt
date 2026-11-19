package com.cuaderno.app.page

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.juanma.kit.Ui
import cuaderno.core.PdfPage
import java.io.File
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws the pages of imported PDFs with Android's own reader ([PdfRenderer]): only the part
 * asked for, at the resolution the canvas shows it, so the text is as sharp zoomed in as at
 * the start. On the screen that part is one of the [Tiles]' pictures, drawn once and kept;
 * a pen stroke is painted on top of it without drawing the PDF again.
 *
 * Belongs to one thread, like the [Render] that owns it. [close] lets go of the open files.
 */
class PdfPainter {
    /** An open PDF. */
    interface Doc : java.io.Closeable {
        val pages: Int
        /** Page [i]'s size in points. */
        fun size(i: Int): Pair<Float, Float>
        /** Page [i] onto [image], within [clip], [transform] taking its points to pixels. */
        fun render(i: Int, image: Bitmap, clip: Rect, transform: Matrix)
    }

    /** What opens PDFs: Android's reader, or a stand-in in the tests (Robolectric has no PDF reader). */
    fun interface Reader {
        /** Throws if [bytes] are not a PDF it can read. */
        fun open(bytes: ByteArray): Doc
    }

    /** Android's own reader. It needs a file it can seek in: the bytes go to one that is gone as soon as it is open. */
    object Android : Reader {
        override fun open(bytes: ByteArray): Doc {
            // On Android the temporary folder is the app's own cache.
            val file = File.createTempFile("cuaderno", ".pdf")
            try {
                file.writeBytes(bytes)
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = try { PdfRenderer(fd) } catch (e: Throwable) { fd.close(); throw e }
                return object : Doc {
                    override val pages get() = renderer.pageCount
                    override fun size(i: Int) = renderer.openPage(i).use { Pair(it.width.toFloat(), it.height.toFloat()) }
                    override fun render(i: Int, image: Bitmap, clip: Rect, transform: Matrix) =
                        renderer.openPage(i).use { it.render(image, clip, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    override fun close() {
                        try { renderer.close() } catch (e: Exception) {}
                        try { fd.close() } catch (e: Exception) {}
                    }
                }
            } finally {
                file.delete()
            }
        }
    }

    // By the bytes' identity (arrays compare that way): every page of one PDF shares its array.
    private val open = LinkedHashMap<ByteArray, Doc?>()
    private var scratch: Bitmap? = null
    private val plain = Paint(Paint.FILTER_BITMAP_FLAG)
    private val inverted = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(onDark()) }
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val src = Rect()
    private val dst = RectF()

    /** At least this many pixels a point, for a canvas that does not say (a PDF being written: 3 is 216 dpi). */
    var minScale = 0f

    /**
     * The part [top]..[bottom], [left]..[right] (points) of [pdf] on a sheet [width] x [height],
     * on [dark] paper inverted to match it. False when the PDF cannot be read: the caller then
     * shows plain paper, and nothing is lost (the file keeps the PDF).
     */
    fun draw(canvas: Canvas, pdf: PdfPage, width: Float, height: Float, top: Float, bottom: Float,
             left: Float, right: Float, dark: Boolean): Boolean {
        val doc = document(pdf.bytes) ?: return false
        if (pdf.index >= doc.pages) return false
        val l = max(0f, left)
        val t = max(0f, top)
        val r = min(width, right)
        val b = min(height, bottom)
        if (r <= l || b <= t) return true
        @Suppress("DEPRECATION") canvas.getMatrix(matrix)
        matrix.getValues(values)
        var s = max(minScale, hypot(values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]))
        // Never a giant picture: a whole page at a big zoom is drawn a little softer instead.
        val area = (r - l) * (b - t) * s * s
        if (area > MAX_PIXELS) s *= sqrt(MAX_PIXELS / area)
        val pw = ceil((r - l) * s).toInt().coerceAtLeast(1)
        val ph = ceil((b - t) * s).toInt().coerceAtLeast(1)
        // A hardware canvas only records the drawing: a picture reused after it would show twice.
        val image = if (canvas.isHardwareAccelerated) Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888) else scratch(pw, ph)
        image.eraseColor(WHITE)
        try {
            val (w, h) = doc.size(pdf.index)
            matrix.setScale(s * width / w, s * height / h)
            matrix.postTranslate(-l * s, -t * s)
            src.set(0, 0, pw, ph)
            doc.render(pdf.index, image, src, matrix)
        } catch (e: Exception) {
            return false
        }
        dst.set(l, t, l + pw / s, t + ph / s)
        canvas.drawBitmap(image, src, dst, if (dark) inverted else plain)
        return true
    }

    /** Closes every PDF this painter opened. It opens them again if it draws later. */
    fun close() {
        for (o in open.values) o?.close()
        open.clear()
        scratch = null
    }

    private fun scratch(w: Int, h: Int): Bitmap {
        val old = scratch
        if (old != null && old.width >= w && old.height >= h) return old
        return Bitmap.createBitmap(max(w, old?.width ?: 0), max(h, old?.height ?: 0), Bitmap.Config.ARGB_8888).also { scratch = it }
    }

    private fun document(bytes: ByteArray): Doc? {
        if (open.containsKey(bytes)) return open[bytes]
        val opened = try { reader.open(bytes) } catch (e: Exception) { null } catch (e: LinkageError) { null }
        // Few at once: each holds a file open. The oldest goes.
        if (open.size >= MAX_OPEN) {
            val eldest = open.keys.first()
            open.remove(eldest)?.close()
        }
        open[bytes] = opened
        return opened
    }

    companion object {
        /** Set by the tests only. */
        var reader: Reader = Android
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val MAX_OPEN = 2
        private const val MAX_PIXELS = 6_000_000f

        /**
         * The size of each page of a PDF, in points, or null if Android cannot read it (broken,
         * or locked with a password). Reads the whole file: call it off the main thread.
         */
        fun sizes(bytes: ByteArray): List<Pair<Float, Float>>? = try {
            reader.open(bytes).use { doc -> (0 until doc.pages).map { doc.size(it) }.takeIf { it.isNotEmpty() } }
        } catch (e: Exception) { null } catch (e: LinkageError) { null }

        /**
         * Dark paper: white becomes the dark sheet and black the light ink, while colours keep
         * their hue (turned half way round, then inverted), so a red figure stays reddish.
         */
        fun onDark(): ColorMatrix {
            val hue = ColorMatrix(floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f))
            fun c(color: Int, shift: Int) = ((color shr shift) and 0xFF).toFloat()
            val bright = Ui.BRIGHT
            val sheet = Render.PAPER_DARK
            val range = ColorMatrix(floatArrayOf(
                -(c(bright, 16) - c(sheet, 16)) / 255f, 0f, 0f, 0f, c(bright, 16),
                0f, -(c(bright, 8) - c(sheet, 8)) / 255f, 0f, 0f, c(bright, 8),
                0f, 0f, -(c(bright, 0) - c(sheet, 0)) / 255f, 0f, c(bright, 0),
                0f, 0f, 0f, 1f, 0f))
            return ColorMatrix().apply { setConcat(range, hue) }
        }
    }
}
