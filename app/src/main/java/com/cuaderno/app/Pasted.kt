package com.cuaderno.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri

/**
 * An image copied in another app (Chrome's "Copiar imagen", a screenshot's "Copiar", the
 * Gallery's share to the clipboard) and pasted on a page. Android's clipboard holds the image
 * as a link to it, which Android lets this app read while it pastes; the image itself then goes
 * through [Pictures], as one chosen from the gallery does. Text is not pasted: a page has no text.
 */
object Pasted {
    /** The links in [clip] that may be images, in order. */
    fun uris(clip: ClipData?): List<Uri> {
        if (clip == null) return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            .filter { it.scheme == "content" || it.scheme == "file" }
    }

    /** Anything copied at all: only the description, so Android does not say the app read it. */
    fun hasSomething(context: Context): Boolean = clipboard(context)?.hasPrimaryClip() == true

    /** The copied image made ready, or null when what is copied is not one. Off the main thread. */
    fun picture(context: Context, clip: ClipData?): Pictures.Ready? =
        uris(clip).firstNotNullOfOrNull { Pictures.prepare(context, it) }

    /** What is copied now. On the main thread: Android only hands it to the app in front. */
    fun current(context: Context): ClipData? = try { clipboard(context)?.primaryClip } catch (e: Exception) { null }

    private fun clipboard(context: Context) = context.getSystemService(ClipboardManager::class.java)
}
