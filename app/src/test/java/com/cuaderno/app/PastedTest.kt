package com.cuaderno.app

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PastedTest {
    private val context = RuntimeEnvironment.getApplication()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    /** An image another app put on the clipboard as a link, as Chrome does. */
    private fun copied(w: Int, h: Int): Uri {
        val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF5E81AC.toInt()) }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val uri = Uri.parse("content://com.android.chrome.FileProvider/images/screenshot/copied.png")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    @Test fun anImageCopiedInAnotherAppIsPastedAsAPicture() {
        val uri = copied(300, 150)
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "imagen", uri))
        assertTrue(Pasted.hasSomething(context))
        val ready = Pasted.picture(context, Pasted.current(context))!!
        assertEquals(300, ready.width); assertEquals(150, ready.height)
        val decoded = BitmapFactory.decodeByteArray(ready.bytes, 0, ready.bytes.size)
        assertEquals("the image itself, as JPEG", 300, decoded.width)
        assertTrue(ready.name.endsWith(".jpg"))
    }

    @Test fun aBigOneComesInNoLongerThanAPicturePicked() {
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "imagen", copied(4000, 1000)))
        val ready = Pasted.picture(context, Pasted.current(context))!!
        assertEquals(Pictures.LONGEST, ready.width)
        assertEquals(Pictures.LONGEST / 4, ready.height)
    }

    @Test fun textIsNotAPicture() {
        clipboard.setPrimaryClip(ClipData.newPlainText("texto", "https://example.com/foto.png"))
        assertTrue(Pasted.uris(Pasted.current(context)).isEmpty())
        assertNull(Pasted.picture(context, Pasted.current(context)))
    }

    @Test fun aLinkToSomethingThatIsNotAnImageGivesNothing() {
        val uri = Uri.parse("content://downloads/apunte.pdf")
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream("%PDF-1.4".toByteArray()) }
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "pdf", uri))
        assertNull(Pasted.picture(context, Pasted.current(context)))
    }

    @Test fun nothingCopiedOffersNothing() {
        assertFalse(Pasted.hasSomething(context))
        assertNull(Pasted.picture(context, null))
    }
}
