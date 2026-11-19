package com.cuaderno.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import cuaderno.core.Picture
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * A picture chosen to go on a page, made ready: turned upright (a phone photo says in its
 * EXIF how it was held), no longer than [LONGEST] pixels on its long side, as JPEG. A photo of
 * the board stays readable and the notebook file stays a few hundred kilobytes per picture,
 * which matters because every save rewrites the whole file.
 */
object Pictures {
    class Ready(val name: String, val bytes: ByteArray, val width: Int, val height: Int)

    const val LONGEST = 1600

    /** Reads [uri]; null if it is not an image this can read. Off the main thread: it decodes. */
    fun prepare(context: Context, uri: Uri): Ready? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            // Decoded at a power of two at or above the size wanted, then scaled down exactly.
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONGEST) sample *= 2
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            val turn = resolver.openInputStream(uri)?.use { orientation(it) } ?: 0
            decoded?.let { ready(upright(it, turn)) }
        }
    } catch (e: Exception) {
        null
    }

    private fun orientation(stream: java.io.InputStream): Int = when (
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    /** [image] turned [degrees] and no longer than [LONGEST]. */
    fun upright(image: Bitmap, degrees: Int): Bitmap {
        val k = minOf(1f, LONGEST.toFloat() / max(image.width, image.height))
        if (degrees == 0 && k == 1f) return image
        val m = Matrix().apply { postScale(k, k); postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, m, true)
    }

    fun ready(image: Bitmap): Ready {
        val out = ByteArrayOutputStream()
        // Transparent parts (a screenshot's corners) on white, as on paper.
        val solid = if (!image.hasAlpha()) image else Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(image, 0f, 0f, null) }
        }
        solid.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        val bytes = out.toByteArray()
        return Ready(Picture.nameFor(bytes, "jpg"), bytes, solid.width, solid.height)
    }

    /** Sharp enough for writing on a board or a screenshot's text. */
    private const val QUALITY = 88
}
