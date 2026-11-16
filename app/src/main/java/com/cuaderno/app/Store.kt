package com.cuaderno.app

import cuaderno.core.Document
import cuaderno.core.FormatException
import cuaderno.core.Notebook
import cuaderno.core.NotebookFile
import java.io.File
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Keeps the open notebook on disk. Saving never happens on the thread that
 * draws: a change asks for a save a moment later ([saveSoon], so a burst of
 * strokes is one write, on a pause in writing), and leaving the screen
 * saves at once and waits for it ([saveNow]). The write itself is atomic
 * (NotebookFile): there is the old file or the new one, never half of one.
 */
class Store(private val file: File) {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "cuaderno-save") }
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var saved = -1L

    /** What went wrong the last time something did, for the screen to show. */
    @Volatile var problem: String? = null
        private set

    /**
     * Opens the notebook, or starts an empty one the first time. A file that
     * cannot be read is never overwritten or deleted: it is moved aside
     * (name ending in ".ilegible-<time>") and a new notebook starts.
     */
    fun load(): Document {
        file.parentFile?.mkdirs()
        if (file.exists()) {
            try {
                val document = Document(NotebookFile.read(file))
                saved = document.revision
                // The last file that opened well, in case a later save goes wrong in a way nobody foresaw.
                runCatching { file.copyTo(File(file.path + ".bak"), overwrite = true) }
                return document
            } catch (e: FormatException) {
                val aside = File(file.path + ".ilegible-" + System.currentTimeMillis())
                file.renameTo(aside)
                problem = e.message
            }
        }
        val document = Document(Notebook("nb-" + System.currentTimeMillis(), "Cuaderno", now(), now(), emptyList()))
        saved = document.revision
        return document
    }

    fun saveSoon(document: Document) {
        pending?.cancel(false)
        pending = worker.schedule({ write(document) }, DELAY_MS, TimeUnit.MILLISECONDS)
    }

    /** Saves and waits. For when the app is leaving the screen and may be killed next. */
    fun saveNow(document: Document) {
        pending?.cancel(false)
        runCatching { worker.submit { write(document) }.get(10, TimeUnit.SECONDS) }
    }

    private fun write(document: Document) {
        // Pages are immutable lists: the revision read first can only be older than what gets written.
        val revision = document.revision
        if (revision == saved) return
        try {
            NotebookFile.write(file, document.notebook(now()))
            saved = revision
            problem = null
        } catch (e: Exception) {
            problem = e.message ?: e.javaClass.simpleName
        }
    }

    private fun now() = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

    private companion object {
        const val DELAY_MS = 1500L
    }
}
